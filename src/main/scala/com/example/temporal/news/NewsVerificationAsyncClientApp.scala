package com.example.temporal.news

import com.example.temporal.core.{TemporalClient, ZTemporalWorkflow}
import com.example.temporal.news.model._
import com.example.temporal.news.workflow.{NewsVerificationWorkflow, VerificationProgress}
import zio._
import zio.logging.backend.SLF4J

import java.util.UUID
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Async client application for news verification
 * Demonstrates async execution, progress monitoring, and signals
 */
object NewsVerificationAsyncClientApp extends ZIOAppDefault {

  val TASK_QUEUE = "news-verification-queue"

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  val program: ZIO[TemporalClient.Service & ZIOAppArgs, Throwable, Unit] = for {
    args <- ZIOAppArgs.getArgs

    _ <- Console.printLine("=" * 70)
    _ <- Console.printLine("  News Verification - Async Mode with Progress Monitoring")
    _ <- Console.printLine("=" * 70)
    _ <- Console.printLine("")

    newsSource = args.headOption.getOrElse("https://feeds.bbci.co.uk/news/rss.xml")
    maxHeadlines = args.lift(1).flatMap(_.toIntOption).getOrElse(5)

    _ <- Console.printLine(s"News Source: $newsSource")
    _ <- Console.printLine(s"Max Headlines: $maxHeadlines")
    _ <- Console.printLine("")

    workflowId = s"news-async-${UUID.randomUUID().toString.take(8)}"

    request = NewsVerificationRequest(
      newsSource = newsSource,
      maxHeadlines = maxHeadlines,
      searchDepth = 3
    )

    workflowConfig = ZTemporalWorkflow.WorkflowConfig(
      taskQueue = TASK_QUEUE,
      workflowId = workflowId,
      workflowExecutionTimeout = Some(15.minutes)
    )

    workflow <- ZTemporalWorkflow.newWorkflowStub[NewsVerificationWorkflow](workflowConfig)

    _ <- Console.printLine(s"Starting async workflow: $workflowId")
    _ <- Console.printLine("")

    // Start workflow asynchronously
    stub <- ZTemporalWorkflow.executeAsync(workflow, _.verifyNews(request))

    _ <- Console.printLine("Workflow started. Monitoring progress...")
    _ <- Console.printLine("")

    // Monitor progress until completion
    _ <- monitorProgress(workflowId).fork

    // Wait for result
    result <- ZTemporalWorkflow.getResult[NewsVerificationResult](stub)

    _ <- Console.printLine("")
    _ <- Console.printLine("=" * 70)
    _ <- Console.printLine("  FINAL RESULTS")
    _ <- Console.printLine("=" * 70)
    _ <- Console.printLine(s"Request ID: ${result.requestId}")
    _ <- Console.printLine(s"Headlines Analyzed: ${result.headlinesAnalyzed}")
    _ <- Console.printLine(s"Processing Time: ${result.processingTimeMs}ms")
    _ <- Console.printLine("")
    _ <- Console.printLine("Statistics:")
    _ <- Console.printLine(s"  Likely Real: ${result.overallStats.likelyReal}")
    _ <- Console.printLine(s"  Likely Fake: ${result.overallStats.likelyFake}")
    _ <- Console.printLine(s"  Uncertain: ${result.overallStats.uncertain}")
    _ <- Console.printLine(s"  Avg Confidence: ${(result.overallStats.averageConfidence * 100).toInt}%")

  } yield ()

  def monitorProgress(workflowId: String): ZIO[TemporalClient.Service, Throwable, Unit] = {
    def loop(lastStep: String): ZIO[TemporalClient.Service, Throwable, Unit] = {
      for {
        _ <- ZIO.sleep(2.seconds)
        existingWorkflow <- ZTemporalWorkflow.getExistingWorkflow[NewsVerificationWorkflow](workflowId)
          .catchAll(_ => ZIO.fail(new RuntimeException("Workflow completed")))
        progress <- ZTemporalWorkflow.query(existingWorkflow, _.getProgress)
          .catchAll(_ => ZIO.fail(new RuntimeException("Query failed")))

        _ <- if (progress.currentStep != lastStep || progress.currentStep == "PROCESSING_HEADLINES") {
          val headlineInfo = progress.currentHeadline.map(h => s" - ${h.take(40)}...").getOrElse("")
          Console.printLine(
            s"  [${progress.headlinesProcessed}/${progress.totalHeadlines}] ${progress.currentStep}$headlineInfo"
          )
        } else ZIO.unit

        _ <- if (progress.currentStep == "COMPLETED" || progress.isStopped) {
          ZIO.unit
        } else {
          loop(progress.currentStep)
        }
      } yield ()
    }

    loop("").catchAll(_ => ZIO.unit)
  }

  val layers: ZLayer[Any, Throwable, TemporalClient.Service] =
    TemporalClient.Config.layer >>> TemporalClient.layer

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    program
      .provideSome[ZIOAppArgs](layers)
      .tapError(err => Console.printLineError(s"Async client failed: $err"))
      .exitCode
}
