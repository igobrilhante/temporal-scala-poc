package com.example.temporal.workflow

import com.example.temporal.activity._
import com.example.temporal.core.ZTemporalActivity
import io.temporal.workflow.Workflow
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

/**
 * Implementation of the OrderWorkflow
 * Orchestrates the order processing saga with compensation logic
 */
class OrderWorkflowImpl extends OrderWorkflow {

  private val logger = Workflow.getLogger(classOf[OrderWorkflowImpl])

  // Activity stub with retry configuration
  private val activities: OrderActivities = ZTemporalActivity.newActivityStub[OrderActivities](
    ZTemporalActivity.ActivityConfig(
      startToCloseTimeout = 30.seconds,
      retryOptions = Some(ZTemporalActivity.RetryConfig(
        initialInterval = 1.second,
        backoffCoefficient = 2.0,
        maximumInterval = 30.seconds,
        maximumAttempts = 3
      ))
    )
  )

  // Workflow state
  private var currentStep: String = "INITIALIZED"
  private var isCancelled: Boolean = false
  private var cancellationReason: String = ""
  private var shippingAddress: String = ""
  private var reservationId: Option[String] = None

  override def processOrder(request: OrderRequest): OrderResult = {
    shippingAddress = request.shippingAddress
    val orderId = request.orderId

    logger.info(s"Starting order processing for order $orderId")

    try {
      // Step 1: Validate Order
      currentStep = "VALIDATING"
      logger.info(s"Step 1: Validating order $orderId")
      val validationResult = activities.validateOrder(
        orderId,
        request.customerId,
        request.totalAmount
      )

      if (!validationResult.isValid) {
        currentStep = "VALIDATION_FAILED"
        return OrderResult(
          orderId = orderId,
          status = "FAILED",
          transactionId = None,
          trackingNumber = None,
          errorMessage = Some(validationResult.message)
        )
      }

      // Check for cancellation
      if (checkCancellation(orderId)) {
        return createCancelledResult(orderId)
      }

      // Step 2: Reserve Inventory
      currentStep = "RESERVING_INVENTORY"
      logger.info(s"Step 2: Reserving inventory for order $orderId")
      val inventoryResult = activities.reserveInventory(orderId, request.items)
      reservationId = Some(inventoryResult.reservationId)

      if (!inventoryResult.success) {
        currentStep = "INVENTORY_FAILED"
        return OrderResult(
          orderId = orderId,
          status = "FAILED",
          transactionId = None,
          trackingNumber = None,
          errorMessage = Some(s"Items unavailable: ${inventoryResult.failedItems}")
        )
      }

      // Check for cancellation
      if (checkCancellation(orderId)) {
        compensateInventory(orderId)
        return createCancelledResult(orderId)
      }

      // Step 3: Process Payment
      currentStep = "PROCESSING_PAYMENT"
      logger.info(s"Step 3: Processing payment for order $orderId")
      val paymentResult = activities.processPayment(
        orderId,
        request.customerId,
        request.totalAmount
      )

      if (!paymentResult.success) {
        currentStep = "PAYMENT_FAILED"
        compensateInventory(orderId)
        return OrderResult(
          orderId = orderId,
          status = "FAILED",
          transactionId = Some(paymentResult.transactionId),
          trackingNumber = None,
          errorMessage = paymentResult.errorMessage
        )
      }

      // Check for cancellation (would need refund)
      if (checkCancellation(orderId)) {
        compensatePayment(orderId, paymentResult.transactionId)
        compensateInventory(orderId)
        return createCancelledResult(orderId)
      }

      // Step 4: Ship Order
      currentStep = "SHIPPING"
      logger.info(s"Step 4: Shipping order $orderId to $shippingAddress")
      val shipmentResult = activities.shipOrder(orderId, shippingAddress)

      if (!shipmentResult.success) {
        currentStep = "SHIPPING_FAILED"
        compensatePayment(orderId, paymentResult.transactionId)
        compensateInventory(orderId)
        return OrderResult(
          orderId = orderId,
          status = "FAILED",
          transactionId = Some(paymentResult.transactionId),
          trackingNumber = None,
          errorMessage = Some("Shipping failed")
        )
      }

      // Step 5: Send Confirmation
      currentStep = "SENDING_NOTIFICATION"
      logger.info(s"Step 5: Sending confirmation for order $orderId")
      activities.sendNotification(
        request.customerId,
        s"Your order $orderId has been shipped! Tracking: ${shipmentResult.trackingNumber}"
      )

      currentStep = "COMPLETED"
      logger.info(s"Order $orderId completed successfully")

      OrderResult(
        orderId = orderId,
        status = "COMPLETED",
        transactionId = Some(paymentResult.transactionId),
        trackingNumber = Some(shipmentResult.trackingNumber),
        errorMessage = None
      )

    } catch {
      case e: Exception =>
        currentStep = "ERROR"
        logger.error(s"Order $orderId failed with error: ${e.getMessage}")
        OrderResult(
          orderId = orderId,
          status = "ERROR",
          transactionId = None,
          trackingNumber = None,
          errorMessage = Some(e.getMessage)
        )
    }
  }

  override def cancelOrder(reason: String): Unit = {
    logger.info(s"Cancel signal received with reason: $reason")
    isCancelled = true
    cancellationReason = reason
  }

  override def updateShippingAddress(newAddress: String): Unit = {
    if (currentStep == "VALIDATING" || currentStep == "RESERVING_INVENTORY" || currentStep == "PROCESSING_PAYMENT") {
      logger.info(s"Updating shipping address to: $newAddress")
      shippingAddress = newAddress
    } else {
      logger.warn(s"Cannot update shipping address in current step: $currentStep")
    }
  }

  override def getOrderStatus: OrderStatus = {
    OrderStatus(
      orderId = "", // Will be filled by the actual workflow context
      currentStep = currentStep,
      isCancelled = isCancelled,
      shippingAddress = shippingAddress
    )
  }

  private def checkCancellation(orderId: String): Boolean = {
    if (isCancelled) {
      logger.info(s"Order $orderId cancelled: $cancellationReason")
      currentStep = "CANCELLED"
      true
    } else {
      false
    }
  }

  private def createCancelledResult(orderId: String): OrderResult = {
    OrderResult(
      orderId = orderId,
      status = "CANCELLED",
      transactionId = None,
      trackingNumber = None,
      errorMessage = Some(s"Order cancelled: $cancellationReason")
    )
  }

  private def compensateInventory(orderId: String): Unit = {
    reservationId.foreach { resId =>
      logger.info(s"Compensating: Releasing inventory reservation $resId for order $orderId")
      // In a real implementation, you would call an activity to release the reservation
    }
  }

  private def compensatePayment(orderId: String, transactionId: String): Unit = {
    logger.info(s"Compensating: Refunding payment $transactionId for order $orderId")
    // In a real implementation, you would call an activity to process the refund
  }
}
