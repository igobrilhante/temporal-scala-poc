package com.example.temporal.news.workflow

import com.example.temporal.news.model._
import io.temporal.workflow.{QueryMethod, SignalMethod, WorkflowInterface, WorkflowMethod}

/**
 * Workflow interface for news verification
 * Orchestrates the process of:
 * 1. Fetching headlines from a news source
 * 2. Using AI to find alternative sources
 * 3. Classifying news as real or fake
 */
@WorkflowInterface
trait NewsVerificationWorkflow {

  /**
   * Main workflow method that processes news verification
   */
  @WorkflowMethod
  def verifyNews(request: NewsVerificationRequest): NewsVerificationResult

  /**
   * Signal to skip a specific headline
   */
  @SignalMethod
  def skipHeadline(headlineTitle: String): Unit

  /**
   * Signal to stop processing and return partial results
   */
  @SignalMethod
  def stopProcessing(): Unit

  /**
   * Query current processing status
   */
  @QueryMethod
  def getProgress: VerificationProgress
}

case class VerificationProgress(
  requestId: String,
  currentStep: String,
  headlinesProcessed: Int,
  totalHeadlines: Int,
  currentHeadline: Option[String],
  isStopped: Boolean
)
