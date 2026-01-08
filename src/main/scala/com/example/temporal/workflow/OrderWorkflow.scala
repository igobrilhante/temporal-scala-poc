package com.example.temporal.workflow

import com.example.temporal.activity._
import io.temporal.workflow.{QueryMethod, SignalMethod, WorkflowInterface, WorkflowMethod}

/**
 * Workflow interface for order processing
 * Demonstrates signals for updates and queries for state inspection
 */
@WorkflowInterface
trait OrderWorkflow {

  @WorkflowMethod
  def processOrder(request: OrderRequest): OrderResult

  @SignalMethod
  def cancelOrder(reason: String): Unit

  @SignalMethod
  def updateShippingAddress(newAddress: String): Unit

  @QueryMethod
  def getOrderStatus: OrderStatus
}

// Domain models for workflow
case class OrderRequest(
  orderId: String,
  customerId: String,
  items: java.util.List[OrderItem],
  shippingAddress: String,
  totalAmount: BigDecimal
)

case class OrderResult(
  orderId: String,
  status: String,
  transactionId: Option[String],
  trackingNumber: Option[String],
  errorMessage: Option[String]
)

case class OrderStatus(
  orderId: String,
  currentStep: String,
  isCancelled: Boolean,
  shippingAddress: String
)
