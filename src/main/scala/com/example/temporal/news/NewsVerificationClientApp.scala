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
 * Client application for news verification
 * Demonstrates starting and monitoring the verification workflow
 */
object NewsVerificationClientApp extends ZIOAppDefault {

  val TASK_QUEUE = "news-verification-queue"

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  // Example news sources to verify
  val exampleSources = List(
    "https://rss.nytimes.com/services/xml/rss/nyt/World.xml",
    "https://feeds.bbci.co.uk/news/rss.xml",
    "https://www.theguardian.com/world/rss"
  )

  val program: ZIO[TemporalClient.Service & ZIOAppArgs, Throwable, Unit] = for {
    args <- ZIOAppArgs.getArgs

    _ <- Console.printLine("=" * 70)
    _ <- Console.printLine("  News Verification System - Powered by Temporal + ZIO + AI")
    _ <- Console.printLine("=" * 70)
    _ <- Console.printLine("")

    // Get news source from args or use default
    newsSource = args.headOption.getOrElse(exampleSources.head)
    maxHeadlines = args.lift(1).flatMap(_.toIntOption).getOrElse(3)

    _ <- Console.printLine(s"News Source: $newsSource")
    _ <- Console.printLine(s"Max Headlines: $maxHeadlines")
    _ <- Console.printLine("")

    workflowId = s"news-verify-${UUID.randomUUID().toString.take(8)}"

    request = NewsVerificationRequest(
      newsSource = newsSource,
      maxHeadlines = maxHeadlines,
      searchDepth = 3
    )

    // Create workflow stub
    workflowConfig = ZTemporalWorkflow.WorkflowConfig(
      taskQueue = TASK_QUEUE,
      workflowId = workflowId,
      workflowExecutionTimeout = Some(10.minutes)
    )

    workflow <- ZTemporalWorkflow.newWorkflowStub[NewsVerificationWorkflow](workflowConfig)

    _ <- Console.printLine(s"Starting workflow: $workflowId")
    _ <- Console.printLine("This may take a few minutes...")
    _ <- Console.printLine("")

    // Execute workflow (this blocks until completion)
    result <- ZTemporalWorkflow.execute(workflow, _.verifyNews(request))

    // Display results
    _ <- displayResults(result)

  } yield ()

  def displayResults(result: NewsVerificationResult): ZIO[Any, Throwable, Unit] = for {
    _ <- Console.printLine("")
    _ <- Console.printLine("=" * 70)
    _ <- Console.printLine("  VERIFICATION RESULTS")
    _ <- Console.printLine("=" * 70)
    _ <- Console.printLine("")
    _ <- Console.printLine(s"Request ID: ${result.requestId}")
    _ <- Console.printLine(s"Source: ${result.source}")
    _ <- Console.printLine(s"Headlines Analyzed: ${result.headlinesAnalyzed}")
    _ <- Console.printLine(s"Processing Time: ${result.processingTimeMs}ms")
    _ <- Console.printLine("")

    // Display overall stats
    _ <- Console.printLine("-" * 40)
    _ <- Console.printLine("OVERALL STATISTICS")
    _ <- Console.printLine("-" * 40)
    _ <- Console.printLine(s"  Likely Real:    ${result.overallStats.likelyReal}")
    _ <- Console.printLine(s"  Likely Fake:    ${result.overallStats.likelyFake}")
    _ <- Console.printLine(s"  Uncertain:      ${result.overallStats.uncertain}")
    _ <- Console.printLine(s"  Avg Confidence: ${(result.overallStats.averageConfidence * 100).toInt}%")
    _ <- Console.printLine("")

    // Display individual classifications
    _ <- Console.printLine("-" * 40)
    _ <- Console.printLine("DETAILED CLASSIFICATIONS")
    _ <- Console.printLine("-" * 40)

    _ <- ZIO.foreach(result.classifications.asScala.zipWithIndex) { case (classification, idx) =>
      displayClassification(classification, idx + 1)
    }

    _ <- Console.printLine("")
    _ <- Console.printLine("=" * 70)
    _ <- Console.printLine("  Verification Complete")
    _ <- Console.printLine("=" * 70)
  } yield ()

  def displayClassification(c: ClassificationResult, index: Int): ZIO[Any, Throwable, Unit] = for {
    _ <- Console.printLine("")
    _ <- Console.printLine(s"[$index] ${c.headline.title.take(60)}...")
    _ <- Console.printLine(s"    Source: ${c.headline.source}")
    _ <- Console.printLine(s"    Verdict: ${coloredVerdict(c.verdict)}")
    _ <- Console.printLine(s"    Confidence: ${(c.confidenceScore * 100).toInt}%")

    _ <- if (c.reasoning.nonEmpty) {
      Console.printLine(s"    Reasoning: ${c.reasoning.take(100)}...")
    } else ZIO.unit

    _ <- Console.printLine(s"    Factors Analyzed:")
    _ <- ZIO.foreach(c.factorsAnalyzed.asScala) { factor =>
      Console.printLine(s"      - $factor")
    }

    _ <- if (!c.recommendations.isEmpty) {
      Console.printLine(s"    Recommendations:") *>
        ZIO.foreach(c.recommendations.asScala) { rec =>
          Console.printLine(s"      * $rec")
        }
    } else ZIO.unit

  } yield ()

  def coloredVerdict(verdict: String): String = verdict match {
    case "LIKELY_REAL" => s"✓ $verdict"
    case "LIKELY_FAKE" => s"✗ $verdict"
    case _ => s"? $verdict"
  }

  val layers: ZLayer[Any, Throwable, TemporalClient.Service] =
    TemporalClient.Config.layer >>> TemporalClient.layer

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    program
      .provideSome[ZIOAppArgs](layers)
      .tapError(err => Console.printLineError(s"Client failed: $err"))
      .exitCode
}
