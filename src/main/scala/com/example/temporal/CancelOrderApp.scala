package com.example.temporal

import com.example.temporal.core.{TemporalClient, ZTemporalWorkflow}
import com.example.temporal.workflow.OrderWorkflow
import zio._
import zio.logging.backend.SLF4J

/**
 * Application to demonstrate sending signals to running workflows
 * Use this to cancel an order that's in progress
 */
object CancelOrderApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  val program: ZIO[TemporalClient.Service & ZIOAppArgs, Throwable, Unit] = for {
    args <- ZIOAppArgs.getArgs
    _ <- args.headOption match {
      case Some(workflowId) =>
        for {
          _ <- Console.printLine(s"Sending cancel signal to workflow: $workflowId")
          workflow <- ZTemporalWorkflow.getExistingWorkflow[OrderWorkflow](workflowId)
          _ <- ZTemporalWorkflow.signal(workflow, _.cancelOrder("Cancelled by user request"))
          _ <- Console.printLine("Cancel signal sent successfully")
        } yield ()
      case None =>
        Console.printLine("Usage: sbt \"runMain com.example.temporal.CancelOrderApp <workflow-id>\"")
    }
  } yield ()

  val layers: ZLayer[Any, Throwable, TemporalClient.Service] =
    TemporalClient.Config.layer >>> TemporalClient.layer

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    program
      .provideSome[ZIOAppArgs](layers)
      .tapError(err => Console.printLineError(s"Cancel failed: $err"))
      .exitCode
}
