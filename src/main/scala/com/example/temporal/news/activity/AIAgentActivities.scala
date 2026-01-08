package com.example.temporal.news.activity

import com.example.temporal.news.model._
import io.temporal.activity.{ActivityInterface, ActivityMethod}

/**
 * Activities for AI-powered news analysis
 * Uses LLM to find alternative sources and analyze content
 */
@ActivityInterface
trait AIAgentActivities {

  /**
   * Use AI to find alternative sources reporting on the same story
   * The AI agent will:
   * 1. Extract key facts from the headline
   * 2. Generate search queries
   * 3. Analyze found sources for relevance
   */
  @ActivityMethod
  def findAlternativeSources(
    headline: NewsHeadline,
    articleContent: String,
    maxSources: Int
  ): SourceAnalysis

  /**
   * Use AI to extract key claims from an article
   */
  @ActivityMethod
  def extractKeyClaims(headline: NewsHeadline, content: String): java.util.List[String]

  /**
   * Use AI to compare two articles and determine their stance relationship
   */
  @ActivityMethod
  def compareArticles(
    originalTitle: String,
    originalContent: String,
    comparisonTitle: String,
    comparisonContent: String
  ): ArticleComparison
}

case class ArticleComparison(
  similarity: Double,
  stance: String, // "supporting", "contradicting", "neutral"
  sharedFacts: java.util.List[String],
  conflictingClaims: java.util.List[String],
  analysis: String
)
