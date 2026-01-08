package com.example.temporal.core

import io.temporal.client.{WorkflowClient, WorkflowOptions, WorkflowStub}
import io.temporal.common.RetryOptions
import zio._

import java.time.Duration as JDuration
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

/**
 * ZIO-friendly workflow operations
 * Wraps Temporal workflow client operations in ZIO effects
 */
object ZTemporalWorkflow {

  case class WorkflowConfig(
    taskQueue: String,
    workflowId: String,
    workflowExecutionTimeout: Option[Duration] = None,
    workflowRunTimeout: Option[Duration] = None,
    workflowTaskTimeout: Option[Duration] = None,
    retryOptions: Option[RetryConfig] = None
  )

  case class RetryConfig(
    initialInterval: Duration = Duration("1 second"),
    backoffCoefficient: Double = 2.0,
    maximumInterval: Duration = Duration("1 minute"),
    maximumAttempts: Int = 0 // 0 means unlimited
  )

  def newWorkflowStub[T: ClassTag](
    config: WorkflowConfig
  ): ZIO[TemporalClient.Service, Throwable, T] = {
    for {
      client <- TemporalClient.client
      stub <- ZIO.attempt {
        val builder = WorkflowOptions.newBuilder()
          .setTaskQueue(config.taskQueue)
          .setWorkflowId(config.workflowId)

        config.workflowExecutionTimeout.foreach { d =>
          builder.setWorkflowExecutionTimeout(JDuration.ofMillis(d.toMillis))
        }

        config.workflowRunTimeout.foreach { d =>
          builder.setWorkflowRunTimeout(JDuration.ofMillis(d.toMillis))
        }

        config.workflowTaskTimeout.foreach { d =>
          builder.setWorkflowTaskTimeout(JDuration.ofMillis(d.toMillis))
        }

        config.retryOptions.foreach { retry =>
          val retryOpts = RetryOptions.newBuilder()
            .setInitialInterval(JDuration.ofMillis(retry.initialInterval.toMillis))
            .setBackoffCoefficient(retry.backoffCoefficient)
            .setMaximumInterval(JDuration.ofMillis(retry.maximumInterval.toMillis))
            .setMaximumAttempts(retry.maximumAttempts)
            .build()
          builder.setRetryOptions(retryOpts)
        }

        val clazz = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]
        client.newWorkflowStub(clazz, builder.build())
      }
    } yield stub
  }

  def execute[T, R](
    workflow: T,
    f: T => R
  ): Task[R] = ZIO.attemptBlocking(f(workflow))

  def executeAsync[T](
    workflow: T,
    f: T => Unit
  ): Task[WorkflowStub] = ZIO.attemptBlocking {
    f(workflow)
    WorkflowStub.fromTyped(workflow)
  }

  def getResult[R: ClassTag](stub: WorkflowStub): Task[R] = {
    val clazz = implicitly[ClassTag[R]].runtimeClass.asInstanceOf[Class[R]]
    ZIO.attemptBlocking(stub.getResult(clazz))
  }

  def signal[T](
    workflow: T,
    f: T => Unit
  ): Task[Unit] = ZIO.attemptBlocking(f(workflow))

  def query[T, R](
    workflow: T,
    f: T => R
  ): Task[R] = ZIO.attemptBlocking(f(workflow))

  def getExistingWorkflow[T: ClassTag](
    workflowId: String
  ): ZIO[TemporalClient.Service, Throwable, T] = {
    for {
      client <- TemporalClient.client
      stub <- ZIO.attempt {
        val clazz = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]
        client.newWorkflowStub(clazz, workflowId)
      }
    } yield stub
  }
}
