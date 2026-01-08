package com.example.temporal.news.model

import zio.json._

/**
 * Domain models for news verification workflow
 */

case class NewsHeadline(
  title: String,
  source: String,
  url: String,
  publishedAt: Option[String] = None,
  summary: Option[String] = None
)

object NewsHeadline {
  implicit val encoder: JsonEncoder[NewsHeadline] = DeriveJsonEncoder.gen[NewsHeadline]
  implicit val decoder: JsonDecoder[NewsHeadline] = DeriveJsonDecoder.gen[NewsHeadline]
}

case class AlternativeSource(
  sourceName: String,
  url: String,
  title: String,
  similarity: Double, // 0.0 to 1.0
  stance: String,     // "supporting", "contradicting", "neutral"
  snippet: String
)

object AlternativeSource {
  implicit val encoder: JsonEncoder[AlternativeSource] = DeriveJsonEncoder.gen[AlternativeSource]
  implicit val decoder: JsonDecoder[AlternativeSource] = DeriveJsonDecoder.gen[AlternativeSource]
}

case class SourceAnalysis(
  headline: NewsHeadline,
  alternativeSources: java.util.List[AlternativeSource],
  totalSourcesFound: Int,
  supportingCount: Int,
  contradictingCount: Int,
  neutralCount: Int
)

object SourceAnalysis {
  implicit val encoder: JsonEncoder[SourceAnalysis] = DeriveJsonEncoder.gen[SourceAnalysis]
  implicit val decoder: JsonDecoder[SourceAnalysis] = DeriveJsonDecoder.gen[SourceAnalysis]
}

sealed trait VerificationVerdict
object VerificationVerdict {
  case object LikelyReal extends VerificationVerdict
  case object LikelyFake extends VerificationVerdict
  case object Uncertain extends VerificationVerdict
  case object NeedsMoreInvestigation extends VerificationVerdict

  implicit val encoder: JsonEncoder[VerificationVerdict] = JsonEncoder[String].contramap {
    case LikelyReal => "LIKELY_REAL"
    case LikelyFake => "LIKELY_FAKE"
    case Uncertain => "UNCERTAIN"
    case NeedsMoreInvestigation => "NEEDS_MORE_INVESTIGATION"
  }

  implicit val decoder: JsonDecoder[VerificationVerdict] = JsonDecoder[String].map {
    case "LIKELY_REAL" => LikelyReal
    case "LIKELY_FAKE" => LikelyFake
    case "UNCERTAIN" => Uncertain
    case _ => NeedsMoreInvestigation
  }
}

case class ClassificationResult(
  headline: NewsHeadline,
  verdict: String,
  confidenceScore: Double, // 0.0 to 1.0
  reasoning: String,
  factorsAnalyzed: java.util.List[String],
  recommendations: java.util.List[String]
)

object ClassificationResult {
  implicit val encoder: JsonEncoder[ClassificationResult] = DeriveJsonEncoder.gen[ClassificationResult]
  implicit val decoder: JsonDecoder[ClassificationResult] = DeriveJsonDecoder.gen[ClassificationResult]
}

case class NewsVerificationRequest(
  newsSource: String,           // URL or source identifier
  maxHeadlines: Int = 5,
  searchDepth: Int = 3,         // How many alternative sources to find per headline
  openAiApiKey: Option[String] = None
)

case class NewsVerificationResult(
  requestId: String,
  source: String,
  headlinesAnalyzed: Int,
  classifications: java.util.List[ClassificationResult],
  overallStats: VerificationStats,
  processingTimeMs: Long
)

case class VerificationStats(
  totalHeadlines: Int,
  likelyReal: Int,
  likelyFake: Int,
  uncertain: Int,
  averageConfidence: Double
)

object VerificationStats {
  implicit val encoder: JsonEncoder[VerificationStats] = DeriveJsonEncoder.gen[VerificationStats]
  implicit val decoder: JsonDecoder[VerificationStats] = DeriveJsonDecoder.gen[VerificationStats]
}
