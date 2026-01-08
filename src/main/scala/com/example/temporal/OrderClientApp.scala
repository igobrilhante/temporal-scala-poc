package com.example.temporal

import com.example.temporal.activity.OrderItem
import com.example.temporal.core.{TemporalClient, ZTemporalWorkflow}
import com.example.temporal.workflow.{OrderRequest, OrderResult, OrderWorkflow}
import zio._
import zio.logging.backend.SLF4J

import java.util.UUID
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._

/**
 * Client application that starts workflow executions
 * Make sure the worker is running before executing this
 */
object OrderClientApp extends ZIOAppDefault {

  val TASK_QUEUE = "order-processing-queue"

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  val program: ZIO[TemporalClient.Service, Throwable, Unit] = for {
    _ <- Console.printLine("=== Temporal Scala ZIO POC - Order Processing ===")
    _ <- Console.printLine("")

    orderId = s"ORD-${UUID.randomUUID().toString.take(8).toUpperCase}"
    customerId = "CUST-001"

    _ <- Console.printLine(s"Creating order: $orderId")

    // Create order items
    items = List(
      OrderItem("PROD-001", 2, BigDecimal(29.99)),
      OrderItem("PROD-002", 1, BigDecimal(49.99)),
      OrderItem("PROD-003", 3, BigDecimal(9.99))
    ).asJava

    totalAmount = BigDecimal(139.94)

    request = OrderRequest(
      orderId = orderId,
      customerId = customerId,
      items = items,
      shippingAddress = "123 Main St, City, Country",
      totalAmount = totalAmount
    )

    // Create workflow stub
    workflowConfig = ZTemporalWorkflow.WorkflowConfig(
      taskQueue = TASK_QUEUE,
      workflowId = orderId,
      workflowExecutionTimeout = Some(5.minutes)
    )

    workflow <- ZTemporalWorkflow.newWorkflowStub[OrderWorkflow](workflowConfig)

    _ <- Console.printLine(s"Starting workflow execution...")
    _ <- Console.printLine("")

    // Execute workflow synchronously
    result <- ZTemporalWorkflow.execute(workflow, _.processOrder(request))

    _ <- Console.printLine("=== Order Processing Result ===")
    _ <- Console.printLine(s"Order ID: ${result.orderId}")
    _ <- Console.printLine(s"Status: ${result.status}")
    _ <- result.transactionId match {
      case Some(txn) => Console.printLine(s"Transaction ID: $txn")
      case None => ZIO.unit
    }
    _ <- result.trackingNumber match {
      case Some(tracking) => Console.printLine(s"Tracking Number: $tracking")
      case None => ZIO.unit
    }
    _ <- result.errorMessage match {
      case Some(error) => Console.printLine(s"Error: $error")
      case None => ZIO.unit
    }
    _ <- Console.printLine("")
    _ <- Console.printLine("=== Done ===")
  } yield ()

  val layers: ZLayer[Any, Throwable, TemporalClient.Service] =
    TemporalClient.Config.layer >>> TemporalClient.layer

  override def run: ZIO[Any, Any, Any] =
    program
      .provideSome[Any](layers)
      .tapError(err => Console.printLineError(s"Client failed: $err"))
      .exitCode
}
