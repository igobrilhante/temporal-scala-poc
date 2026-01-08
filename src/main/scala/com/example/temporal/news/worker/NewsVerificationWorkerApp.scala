package com.example.temporal.news.worker

import com.example.temporal.core.{TemporalClient, TemporalWorker}
import com.example.temporal.news.activity._
import com.example.temporal.news.workflow.NewsVerificationWorkflowImpl
import zio._
import zio.logging.backend.SLF4J

/**
 * Worker application for news verification workflows
 * Registers workflow and activity implementations
 */
object NewsVerificationWorkerApp extends ZIOAppDefault {

  val TASK_QUEUE = "news-verification-queue"

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  val program: ZIO[TemporalWorker.Service, Throwable, Unit] = for {
    _ <- Console.printLine("=" * 60)
    _ <- Console.printLine("  News Verification Worker")
    _ <- Console.printLine("=" * 60)
    _ <- Console.printLine(s"Task Queue: $TASK_QUEUE")
    _ <- Console.printLine("")

    // Get API key from environment
    apiKey = sys.env.get("OPENAI_API_KEY")
    _ <- apiKey match {
      case Some(_) => Console.printLine("OpenAI API key found - AI features enabled")
      case None => Console.printLine("WARNING: OPENAI_API_KEY not set - using heuristic analysis only")
    }
    _ <- Console.printLine("")

    // Register workflow implementation
    _ <- TemporalWorker.registerWorkflowImplementationTypes(classOf[NewsVerificationWorkflowImpl])

    // Register activity implementations
    _ <- TemporalWorker.registerActivitiesImplementations(
      new NewsScraperActivitiesImpl(),
      new AIAgentActivitiesImpl(apiKey),
      new NewsClassifierActivitiesImpl(apiKey)
    )

    _ <- Console.printLine("Workflow and Activities registered:")
    _ <- Console.printLine("  - NewsVerificationWorkflow")
    _ <- Console.printLine("  - NewsScraperActivities")
    _ <- Console.printLine("  - AIAgentActivities")
    _ <- Console.printLine("  - NewsClassifierActivities")
    _ <- Console.printLine("")

    // Start the worker
    _ <- TemporalWorker.start
    _ <- Console.printLine("Worker started. Press Ctrl+C to stop.")
    _ <- Console.printLine("")

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
