package com.example.temporal.activity

import com.example.temporal.core.ZTemporalActivity.ZActivityImplementation
import zio._

import java.util.UUID
import scala.jdk.CollectionConverters._

/**
 * ZIO-based implementation of OrderActivities
 * Each activity method runs ZIO effects within the Temporal activity context
 */
class OrderActivitiesImpl extends OrderActivities with ZActivityImplementation {

  override def validateOrder(
    orderId: String,
    customerId: String,
    amount: BigDecimal
  ): OrderValidationResult = run {
    for {
      _ <- ZIO.logInfo(s"Validating order $orderId for customer $customerId")
      _ <- ZIO.sleep(100.millis) // Simulate validation logic

      result <- ZIO.succeed {
        if (amount <= 0) {
          OrderValidationResult(isValid = false, message = "Amount must be positive")
        } else if (amount > 10000) {
          OrderValidationResult(isValid = false, message = "Amount exceeds maximum limit")
        } else {
          OrderValidationResult(isValid = true, message = "Order validated successfully")
        }
      }

      _ <- ZIO.logInfo(s"Order $orderId validation result: ${result.isValid}")
    } yield result
  }

  override def reserveInventory(
    orderId: String,
    items: java.util.List[OrderItem]
  ): InventoryReservation = run {
    for {
      _ <- ZIO.logInfo(s"Reserving inventory for order $orderId with ${items.size()} items")
      _ <- ZIO.sleep(200.millis) // Simulate inventory check

      reservationId <- ZIO.succeed(UUID.randomUUID().toString)

      // Simulate some items potentially being unavailable
      failedItems <- ZIO.succeed {
        items.asScala.filter(_.quantity > 100).map(_.productId).asJava
      }

      result <- ZIO.succeed {
        InventoryReservation(
          reservationId = reservationId,
          success = failedItems.isEmpty,
          failedItems = failedItems
        )
      }

      _ <- ZIO.logInfo(s"Inventory reservation $reservationId created for order $orderId")
    } yield result
  }

  override def processPayment(
    orderId: String,
    customerId: String,
    amount: BigDecimal
  ): PaymentResult = run {
    for {
      _ <- ZIO.logInfo(s"Processing payment of $amount for order $orderId")
      _ <- ZIO.sleep(500.millis) // Simulate payment processing

      transactionId <- ZIO.succeed(s"TXN-${UUID.randomUUID().toString.take(8).toUpperCase}")

      // Simulate occasional payment failures for testing
      result <- ZIO.succeed {
        if (amount == 999) {
          PaymentResult(
            transactionId = transactionId,
            success = false,
            errorMessage = Some("Payment declined by processor")
          )
        } else {
          PaymentResult(
            transactionId = transactionId,
            success = true,
            errorMessage = None
          )
        }
      }

      _ <- ZIO.logInfo(s"Payment $transactionId processed: success=${result.success}")
    } yield result
  }

  override def shipOrder(
    orderId: String,
    shippingAddress: String
  ): ShipmentResult = run {
    for {
      _ <- ZIO.logInfo(s"Creating shipment for order $orderId to $shippingAddress")
      _ <- ZIO.sleep(300.millis) // Simulate shipment creation

      trackingNumber <- ZIO.succeed(s"SHIP-${UUID.randomUUID().toString.take(10).toUpperCase}")

      result <- ZIO.succeed {
        ShipmentResult(
          trackingNumber = trackingNumber,
          estimatedDelivery = "3-5 business days",
          success = true
        )
      }

      _ <- ZIO.logInfo(s"Shipment created with tracking number $trackingNumber")
    } yield result
  }

  override def sendNotification(
    customerId: String,
    message: String
  ): NotificationResult = run {
    for {
      _ <- ZIO.logInfo(s"Sending notification to customer $customerId: $message")
      _ <- ZIO.sleep(100.millis) // Simulate notification sending

      result <- ZIO.succeed {
        NotificationResult(sent = true, channel = "email")
      }

      _ <- ZIO.logInfo(s"Notification sent to customer $customerId via ${result.channel}")
    } yield result
  }
}
