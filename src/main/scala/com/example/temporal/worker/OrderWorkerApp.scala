package com.example.temporal.worker

import com.example.temporal.activity.OrderActivitiesImpl
import com.example.temporal.core.{TemporalClient, TemporalWorker}
import com.example.temporal.workflow.OrderWorkflowImpl
import zio._
import zio.logging.backend.SLF4J

/**
 * Worker application that processes Temporal workflows and activities
 * Run this before starting workflow executions
 */
object OrderWorkerApp extends ZIOAppDefault {

  val TASK_QUEUE = "order-processing-queue"

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  val program: ZIO[TemporalWorker.Service, Throwable, Unit] = for {
    _ <- Console.printLine("Starting Temporal Worker...")
    _ <- Console.printLine(s"Task Queue: $TASK_QUEUE")

    // Register workflow and activity implementations
    _ <- TemporalWorker.registerWorkflowImplementationTypes(classOf[OrderWorkflowImpl])
    _ <- TemporalWorker.registerActivitiesImplementations(new OrderActivitiesImpl())

    _ <- Console.printLine("Workflow and Activities registered")

    // Start the worker
    _ <- TemporalWorker.start
    _ <- Console.printLine("Worker started. Press Ctrl+C to stop.")

    // Keep running until interrupted
    _ <- ZIO.never
  } yield ()

  val layers: ZLayer[Any, Throwable, TemporalWorker.Service] =
    TemporalClient.Config.layer >>>
      TemporalClient.layer >>>
      (ZLayer.succeed(TemporalWorker.Config(TASK_QUEUE)) ++ TemporalClient.layer) >>>
      TemporalWorker.layer

  override def run: ZIO[Any, Any, Any] =
    program
      .provideSome[Any](layers)
      .tapError(err => Console.printLineError(s"Worker failed: $err"))
      .exitCode
}
