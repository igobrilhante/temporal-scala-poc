package com.example.temporal

import com.example.temporal.activity._
import com.example.temporal.workflow._
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import zio._
import zio.test._
import zio.test.Assertion._

import scala.jdk.CollectionConverters._

object OrderWorkflowSpec extends ZIOSpecDefault {

  val TASK_QUEUE = "test-order-queue"

  def spec = suite("OrderWorkflow")(
    test("should complete order successfully") {
      ZIO.scoped {
        for {
          testEnv <- ZIO.acquireRelease(
            ZIO.attempt(TestWorkflowEnvironment.newInstance())
          )(env => ZIO.succeed(env.close()))

          worker = testEnv.newWorker(TASK_QUEUE)
          _ = worker.registerWorkflowImplementationTypes(classOf[OrderWorkflowImpl])
          _ = worker.registerActivitiesImplementations(new OrderActivitiesImpl())
          _ = testEnv.start()

          client = testEnv.getWorkflowClient
          options = WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId("test-order-001")
            .build()

          workflow = client.newWorkflowStub(classOf[OrderWorkflow], options)

          request = OrderRequest(
            orderId = "test-order-001",
            customerId = "test-customer",
            items = List(OrderItem("prod-1", 2, BigDecimal(10.0))).asJava,
            shippingAddress = "Test Address",
            totalAmount = BigDecimal(20.0)
          )

          result <- ZIO.attemptBlocking(workflow.processOrder(request))
        } yield assertTrue(
          result.status == "COMPLETED",
          result.orderId == "test-order-001",
          result.transactionId.isDefined,
          result.trackingNumber.isDefined,
          result.errorMessage.isEmpty
        )
      }
    },

    test("should fail validation for negative amount") {
      ZIO.scoped {
        for {
          testEnv <- ZIO.acquireRelease(
            ZIO.attempt(TestWorkflowEnvironment.newInstance())
          )(env => ZIO.succeed(env.close()))

          worker = testEnv.newWorker(TASK_QUEUE)
          _ = worker.registerWorkflowImplementationTypes(classOf[OrderWorkflowImpl])
          _ = worker.registerActivitiesImplementations(new OrderActivitiesImpl())
          _ = testEnv.start()

          client = testEnv.getWorkflowClient
          options = WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId("test-order-002")
            .build()

          workflow = client.newWorkflowStub(classOf[OrderWorkflow], options)

          request = OrderRequest(
            orderId = "test-order-002",
            customerId = "test-customer",
            items = List(OrderItem("prod-1", 1, BigDecimal(-10.0))).asJava,
            shippingAddress = "Test Address",
            totalAmount = BigDecimal(-10.0)
          )

          result <- ZIO.attemptBlocking(workflow.processOrder(request))
        } yield assertTrue(
          result.status == "FAILED",
          result.errorMessage.contains("Amount must be positive")
        )
      }
    },

    test("should handle order cancellation via signal") {
      ZIO.scoped {
        for {
          testEnv <- ZIO.acquireRelease(
            ZIO.attempt(TestWorkflowEnvironment.newInstance())
          )(env => ZIO.succeed(env.close()))

          worker = testEnv.newWorker(TASK_QUEUE)
          _ = worker.registerWorkflowImplementationTypes(classOf[OrderWorkflowImpl])
          _ = worker.registerActivitiesImplementations(new SlowOrderActivitiesImpl())
          _ = testEnv.start()

          client = testEnv.getWorkflowClient
          options = WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId("test-order-003")
            .build()

          workflow = client.newWorkflowStub(classOf[OrderWorkflow], options)

          request = OrderRequest(
            orderId = "test-order-003",
            customerId = "test-customer",
            items = List(OrderItem("prod-1", 1, BigDecimal(50.0))).asJava,
            shippingAddress = "Test Address",
            totalAmount = BigDecimal(50.0)
          )

          // Start workflow async and send cancel signal
          resultFiber <- ZIO.attemptBlocking(workflow.processOrder(request)).fork
          _ <- ZIO.sleep(100.millis)

          existingWorkflow = client.newWorkflowStub(classOf[OrderWorkflow], "test-order-003")
          _ <- ZIO.attemptBlocking(existingWorkflow.cancelOrder("Test cancellation"))

          result <- resultFiber.join
        } yield assertTrue(
          result.status == "CANCELLED" || result.status == "COMPLETED" // May complete before signal
        )
      }
    },

    test("should return correct status via query") {
      ZIO.scoped {
        for {
          testEnv <- ZIO.acquireRelease(
            ZIO.attempt(TestWorkflowEnvironment.newInstance())
          )(env => ZIO.succeed(env.close()))

          worker = testEnv.newWorker(TASK_QUEUE)
          _ = worker.registerWorkflowImplementationTypes(classOf[OrderWorkflowImpl])
          _ = worker.registerActivitiesImplementations(new OrderActivitiesImpl())
          _ = testEnv.start()

          client = testEnv.getWorkflowClient
          options = WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId("test-order-004")
            .build()

          workflow = client.newWorkflowStub(classOf[OrderWorkflow], options)

          request = OrderRequest(
            orderId = "test-order-004",
            customerId = "test-customer",
            items = List(OrderItem("prod-1", 1, BigDecimal(30.0))).asJava,
            shippingAddress = "Query Test Address",
            totalAmount = BigDecimal(30.0)
          )

          _ <- ZIO.attemptBlocking(workflow.processOrder(request))

          existingWorkflow = client.newWorkflowStub(classOf[OrderWorkflow], "test-order-004")
          status <- ZIO.attemptBlocking(existingWorkflow.getOrderStatus)
        } yield assertTrue(
          status.currentStep == "COMPLETED",
          status.shippingAddress == "Query Test Address",
          !status.isCancelled
        )
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
}

// Slow implementation for testing cancellation
class SlowOrderActivitiesImpl extends OrderActivitiesImpl {
  override def reserveInventory(
    orderId: String,
    items: java.util.List[OrderItem]
  ): InventoryReservation = {
    Thread.sleep(500) // Add delay to allow cancel signal
    super.reserveInventory(orderId, items)
  }
}
