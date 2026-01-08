package com.example.temporal.news.activity

import com.example.temporal.news.model._
import io.temporal.activity.{ActivityInterface, ActivityMethod}

/**
 * Activities for classifying news as real or fake
 * Uses AI and heuristics to analyze credibility
 */
@ActivityInterface
trait NewsClassifierActivities {

  /**
   * Classify a news headline based on source analysis
   * Considers multiple factors to determine credibility
   */
  @ActivityMethod
  def classifyNews(
    headline: NewsHeadline,
    sourceAnalysis: SourceAnalysis,
    keyClaims: java.util.List[String]
  ): ClassificationResult

  /**
   * Calculate credibility score for a news source
   */
  @ActivityMethod
  def calculateSourceCredibility(sourceDomain: String): SourceCredibility

  /**
   * Analyze linguistic patterns that may indicate fake news
   */
  @ActivityMethod
  def analyzeLinguisticPatterns(
    title: String,
    content: String
  ): LinguisticAnalysis
}

case class SourceCredibility(
  domain: String,
  credibilityScore: Double, // 0.0 to 1.0
  category: String,         // "mainstream", "independent", "satire", "known_fake", "unknown"
  factCheckHistory: Option[String]
)

case class LinguisticAnalysis(
  sensationalismScore: Double,
  emotionalLanguageScore: Double,
  clickbaitScore: Double,
  hasExaggeratedClaims: Boolean,
  suspiciousPatterns: java.util.List[String]
)
