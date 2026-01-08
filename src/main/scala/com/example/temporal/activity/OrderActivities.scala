package com.example.temporal.activity

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Activity interface for order processing operations
 * Each method represents an atomic operation that can be retried independently
 */
@ActivityInterface
trait OrderActivities {

  @ActivityMethod
  def validateOrder(orderId: String, customerId: String, amount: BigDecimal): OrderValidationResult

  @ActivityMethod
  def reserveInventory(orderId: String, items: java.util.List[OrderItem]): InventoryReservation

  @ActivityMethod
  def processPayment(orderId: String, customerId: String, amount: BigDecimal): PaymentResult

  @ActivityMethod
  def shipOrder(orderId: String, shippingAddress: String): ShipmentResult

  @ActivityMethod
  def sendNotification(customerId: String, message: String): NotificationResult
}

// Domain models for activities
case class OrderItem(
  productId: String,
  quantity: Int,
  unitPrice: BigDecimal
)

case class OrderValidationResult(
  isValid: Boolean,
  message: String
)

case class InventoryReservation(
  reservationId: String,
  success: Boolean,
  failedItems: java.util.List[String]
)

case class PaymentResult(
  transactionId: String,
  success: Boolean,
  errorMessage: Option[String]
)

case class ShipmentResult(
  trackingNumber: String,
  estimatedDelivery: String,
  success: Boolean
)

case class NotificationResult(
  sent: Boolean,
  channel: String
)
