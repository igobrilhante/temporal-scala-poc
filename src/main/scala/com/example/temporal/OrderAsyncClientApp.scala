package com.example.temporal

import com.example.temporal.activity.OrderItem
import com.example.temporal.core.{TemporalClient, ZTemporalWorkflow}
import com.example.temporal.workflow.{OrderRequest, OrderResult, OrderStatus, OrderWorkflow}
import zio._
import zio.logging.backend.SLF4J

import java.util.UUID
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._

/**
 * Async client application demonstrating signals and queries
 * Shows how to interact with a running workflow
 */
object OrderAsyncClientApp extends ZIOAppDefault {

  val TASK_QUEUE = "order-processing-queue"

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  val program: ZIO[TemporalClient.Service, Throwable, Unit] = for {
    _ <- Console.printLine("=== Temporal Scala ZIO POC - Async Order Processing ===")
    _ <- Console.printLine("")

    orderId = s"ORD-ASYNC-${UUID.randomUUID().toString.take(8).toUpperCase}"
    customerId = "CUST-002"

    _ <- Console.printLine(s"Creating async order: $orderId")

    items = List(
      OrderItem("PROD-001", 1, BigDecimal(99.99))
    ).asJava

    request = OrderRequest(
      orderId = orderId,
      customerId = customerId,
      items = items,
      shippingAddress = "456 Oak Ave, Town, Country",
      totalAmount = BigDecimal(99.99)
    )

    workflowConfig = ZTemporalWorkflow.WorkflowConfig(
      taskQueue = TASK_QUEUE,
      workflowId = orderId,
      workflowExecutionTimeout = Some(5.minutes)
    )

    workflow <- ZTemporalWorkflow.newWorkflowStub[OrderWorkflow](workflowConfig)

    _ <- Console.printLine("Starting workflow asynchronously...")

    // Start workflow asynchronously
    stub <- ZTemporalWorkflow.executeAsync(workflow, _.processOrder(request))

    _ <- Console.printLine("Workflow started, monitoring status...")
    _ <- Console.printLine("")

    // Poll status a few times
    _ <- pollStatus(orderId, 5)

    _ <- Console.printLine("Waiting for final result...")

    // Get the final result
    result <- ZTemporalWorkflow.getResult[OrderResult](stub)

    _ <- Console.printLine("")
    _ <- Console.printLine("=== Final Order Result ===")
    _ <- Console.printLine(s"Order ID: ${result.orderId}")
    _ <- Console.printLine(s"Status: ${result.status}")
    _ <- result.transactionId.fold(ZIO.unit)(txn => Console.printLine(s"Transaction ID: $txn"))
    _ <- result.trackingNumber.fold(ZIO.unit)(t => Console.printLine(s"Tracking Number: $t"))
    _ <- result.errorMessage.fold(ZIO.unit)(e => Console.printLine(s"Error: $e"))
    _ <- Console.printLine("")
    _ <- Console.printLine("=== Done ===")
  } yield ()

  def pollStatus(workflowId: String, times: Int): ZIO[TemporalClient.Service, Throwable, Unit] = {
    if (times <= 0) ZIO.unit
    else {
      for {
        _ <- ZIO.sleep(500.millis)
        existingWorkflow <- ZTemporalWorkflow.getExistingWorkflow[OrderWorkflow](workflowId)
        status <- ZTemporalWorkflow.query(existingWorkflow, _.getOrderStatus)
        _ <- Console.printLine(s"  Status: ${status.currentStep} | Cancelled: ${status.isCancelled}")
        _ <- pollStatus(workflowId, times - 1).when(status.currentStep != "COMPLETED" && status.currentStep != "CANCELLED")
      } yield ()
    }
  }

  val layers: ZLayer[Any, Throwable, TemporalClient.Service] =
    TemporalClient.Config.layer >>> TemporalClient.layer

  override def run: ZIO[Any, Any, Any] =
    program
      .provideSome[Any](layers)
      .tapError(err => Console.printLineError(s"Async client failed: $err"))
      .exitCode
}
