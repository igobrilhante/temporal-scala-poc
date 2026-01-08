package com.example.temporal.news.workflow

import com.example.temporal.core.ZTemporalActivity
import com.example.temporal.news.activity._
import com.example.temporal.news.model._
import io.temporal.workflow.Workflow
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Implementation of NewsVerificationWorkflow
 * Orchestrates the multi-step news verification process
 */
class NewsVerificationWorkflowImpl extends NewsVerificationWorkflow {

  private val logger = Workflow.getLogger(classOf[NewsVerificationWorkflowImpl])

  // Activity stubs with appropriate timeouts
  private val scraperActivities: NewsScraperActivities = ZTemporalActivity.newActivityStub[NewsScraperActivities](
    ZTemporalActivity.ActivityConfig(
      startToCloseTimeout = 60.seconds,
      retryOptions = Some(ZTemporalActivity.RetryConfig(
        initialInterval = 2.seconds,
        backoffCoefficient = 2.0,
        maximumInterval = 30.seconds,
        maximumAttempts = 3
      ))
    )
  )

  private val aiAgentActivities: AIAgentActivities = ZTemporalActivity.newActivityStub[AIAgentActivities](
    ZTemporalActivity.ActivityConfig(
      startToCloseTimeout = 120.seconds, // AI operations may take longer
      retryOptions = Some(ZTemporalActivity.RetryConfig(
        initialInterval = 5.seconds,
        backoffCoefficient = 2.0,
        maximumInterval = 60.seconds,
        maximumAttempts = 2
      ))
    )
  )

  private val classifierActivities: NewsClassifierActivities = ZTemporalActivity.newActivityStub[NewsClassifierActivities](
    ZTemporalActivity.ActivityConfig(
      startToCloseTimeout = 90.seconds,
      retryOptions = Some(ZTemporalActivity.RetryConfig(
        initialInterval = 2.seconds,
        backoffCoefficient = 2.0,
        maximumInterval = 30.seconds,
        maximumAttempts = 3
      ))
    )
  )

  // Workflow state
  private var requestId: String = ""
  private var currentStep: String = "INITIALIZED"
  private var headlinesProcessed: Int = 0
  private var totalHeadlines: Int = 0
  private var currentHeadline: Option[String] = None
  private var isStopped: Boolean = false
  private var skippedHeadlines: Set[String] = Set.empty
  private val startTime: Long = System.currentTimeMillis()

  override def verifyNews(request: NewsVerificationRequest): NewsVerificationResult = {
    requestId = s"VER-${UUID.randomUUID().toString.take(8).toUpperCase}"
    logger.info(s"Starting news verification workflow: $requestId")
    logger.info(s"Source: ${request.newsSource}, Max headlines: ${request.maxHeadlines}")

    try {
      // Step 1: Fetch headlines from the news source
      currentStep = "FETCHING_HEADLINES"
      logger.info("Step 1: Fetching headlines from source")

      val headlines = scraperActivities.fetchHeadlines(request.newsSource, request.maxHeadlines)
      totalHeadlines = headlines.size()

      if (headlines.isEmpty) {
        logger.warn("No headlines found from source")
        return createEmptyResult(request, "No headlines found from source")
      }

      logger.info(s"Found ${headlines.size()} headlines to process")

      // Step 2: Process each headline
      currentStep = "PROCESSING_HEADLINES"
      val classifications = new java.util.ArrayList[ClassificationResult]()

      headlines.asScala.foreach { headline =>
        if (isStopped) {
          logger.info("Processing stopped by signal")
          return createPartialResult(request, classifications, "Processing stopped by user")
        }

        if (skippedHeadlines.contains(headline.title)) {
          logger.info(s"Skipping headline: ${headline.title}")
          headlinesProcessed += 1
        } else {
          val result = processHeadline(headline, request.searchDepth)
          result.foreach(classifications.add)
          headlinesProcessed += 1
        }
      }

      // Step 3: Calculate overall statistics
      currentStep = "CALCULATING_STATS"
      val stats = calculateStats(classifications.asScala.toList)

      currentStep = "COMPLETED"
      val processingTime = System.currentTimeMillis() - startTime

      logger.info(s"Workflow $requestId completed. Processed ${classifications.size()} headlines in ${processingTime}ms")

      NewsVerificationResult(
        requestId = requestId,
        source = request.newsSource,
        headlinesAnalyzed = classifications.size(),
        classifications = classifications,
        overallStats = stats,
        processingTimeMs = processingTime
      )

    } catch {
      case e: Exception =>
        currentStep = "ERROR"
        logger.error(s"Workflow $requestId failed: ${e.getMessage}")
        createEmptyResult(request, s"Workflow failed: ${e.getMessage}")
    }
  }

  override def skipHeadline(headlineTitle: String): Unit = {
    logger.info(s"Signal received: skip headline '$headlineTitle'")
    skippedHeadlines = skippedHeadlines + headlineTitle
  }

  override def stopProcessing(): Unit = {
    logger.info("Signal received: stop processing")
    isStopped = true
  }

  override def getProgress: VerificationProgress = {
    VerificationProgress(
      requestId = requestId,
      currentStep = currentStep,
      headlinesProcessed = headlinesProcessed,
      totalHeadlines = totalHeadlines,
      currentHeadline = currentHeadline,
      isStopped = isStopped
    )
  }

  private def processHeadline(
    headline: NewsHeadline,
    searchDepth: Int
  ): Option[ClassificationResult] = {
    currentHeadline = Some(headline.title)
    logger.info(s"Processing headline: ${headline.title}")

    try {
      // Step 2a: Extract article content
      logger.info(s"  Extracting article content...")
      val articleContent = scraperActivities.extractArticleContent(headline.url)

      val contentToAnalyze = if (articleContent.extractedSuccessfully) {
        articleContent.content
      } else {
        headline.summary.getOrElse(headline.title)
      }

      // Step 2b: Use AI to find alternative sources
      logger.info(s"  Finding alternative sources with AI agent...")
      val sourceAnalysis = aiAgentActivities.findAlternativeSources(
        headline,
        contentToAnalyze,
        searchDepth
      )

      // Step 2c: Extract key claims
      logger.info(s"  Extracting key claims...")
      val keyClaims = aiAgentActivities.extractKeyClaims(headline, contentToAnalyze)

      // Step 2d: Classify the news
      logger.info(s"  Classifying news...")
      val classification = classifierActivities.classifyNews(
        headline,
        sourceAnalysis,
        keyClaims
      )

      logger.info(s"  Result: ${classification.verdict} (${(classification.confidenceScore * 100).toInt}% confidence)")
      Some(classification)

    } catch {
      case e: Exception =>
        logger.error(s"  Failed to process headline: ${e.getMessage}")
        None
    }
  }

  private def calculateStats(classifications: List[ClassificationResult]): VerificationStats = {
    if (classifications.isEmpty) {
      return VerificationStats(0, 0, 0, 0, 0.0)
    }

    val likelyReal = classifications.count(_.verdict == "LIKELY_REAL")
    val likelyFake = classifications.count(_.verdict == "LIKELY_FAKE")
    val uncertain = classifications.count(_.verdict == "UNCERTAIN")
    val avgConfidence = classifications.map(_.confidenceScore).sum / classifications.size

    VerificationStats(
      totalHeadlines = classifications.size,
      likelyReal = likelyReal,
      likelyFake = likelyFake,
      uncertain = uncertain,
      averageConfidence = avgConfidence
    )
  }

  private def createEmptyResult(request: NewsVerificationRequest, message: String): NewsVerificationResult = {
    NewsVerificationResult(
      requestId = requestId,
      source = request.newsSource,
      headlinesAnalyzed = 0,
      classifications = new java.util.ArrayList(),
      overallStats = VerificationStats(0, 0, 0, 0, 0.0),
      processingTimeMs = System.currentTimeMillis() - startTime
    )
  }

  private def createPartialResult(
    request: NewsVerificationRequest,
    classifications: java.util.ArrayList[ClassificationResult],
    message: String
  ): NewsVerificationResult = {
    NewsVerificationResult(
      requestId = requestId,
      source = request.newsSource,
      headlinesAnalyzed = classifications.size(),
      classifications = classifications,
      overallStats = calculateStats(classifications.asScala.toList),
      processingTimeMs = System.currentTimeMillis() - startTime
    )
  }
}
