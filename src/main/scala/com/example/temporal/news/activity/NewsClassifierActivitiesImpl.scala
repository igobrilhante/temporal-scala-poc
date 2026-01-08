package com.example.temporal.news.activity

import com.example.temporal.core.ZTemporalActivity.ZActivityImplementation
import com.example.temporal.news.model._
import sttp.client3._
import sttp.client3.ziojson._
import zio._
import zio.json._

import scala.jdk.CollectionConverters._

/**
 * ZIO-based implementation of NewsClassifierActivities
 * Uses AI and heuristics for fake news detection
 */
class NewsClassifierActivitiesImpl(apiKey: Option[String] = None) extends NewsClassifierActivities with ZActivityImplementation {

  private val openAiApiKey: String = apiKey
    .orElse(sys.env.get("OPENAI_API_KEY"))
    .getOrElse("")

  // Known credible news sources (simplified list)
  private val credibleSources = Set(
    "reuters.com", "apnews.com", "bbc.com", "bbc.co.uk",
    "nytimes.com", "washingtonpost.com", "theguardian.com",
    "npr.org", "pbs.org", "cnn.com", "abcnews.go.com",
    "cbsnews.com", "nbcnews.com", "usatoday.com",
    "wsj.com", "economist.com", "ft.com", "bloomberg.com",
    "g1.globo.com", "folha.uol.com.br", "estadao.com.br"
  )

  // Known satire or fake news sites
  private val satireSites = Set(
    "theonion.com", "babylonbee.com", "clickhole.com",
    "newsthump.com", "private-eye.co.uk", "sensacionalista.com.br"
  )

  private val knownFakeSites = Set(
    // This would be populated with known fake news domains
  )

  override def classifyNews(
    headline: NewsHeadline,
    sourceAnalysis: SourceAnalysis,
    keyClaims: java.util.List[String]
  ): ClassificationResult = run {
    for {
      _ <- ZIO.logInfo(s"Classifying news: ${headline.title}")

      // Factor 1: Source credibility
      sourceCredibility <- ZIO.succeed(calculateSourceCredibility(headline.source))

      // Factor 2: Linguistic analysis
      linguisticAnalysis <- ZIO.succeed(analyzeLinguisticPatterns(headline.title, headline.summary.getOrElse("")))

      // Factor 3: Cross-reference analysis
      crossRefScore = calculateCrossReferenceScore(sourceAnalysis)

      // Factor 4: AI classification (if available)
      aiAnalysis <- if (openAiApiKey.nonEmpty) {
        classifyWithAI(headline, sourceAnalysis, keyClaims.asScala.toList)
      } else {
        ZIO.succeed(HeuristicClassification("UNCERTAIN", 0.5, "AI analysis not available"))
      }

      // Combine all factors for final classification
      result = combineFactors(
        headline,
        sourceCredibility,
        linguisticAnalysis,
        crossRefScore,
        aiAnalysis,
        sourceAnalysis
      )

      _ <- ZIO.logInfo(s"Classification result: ${result.verdict} (confidence: ${result.confidenceScore})")
    } yield result
  }

  override def calculateSourceCredibility(sourceDomain: String): SourceCredibility = {
    val domain = sourceDomain.toLowerCase.replaceFirst("^www\\.", "")

    val (score, category) = if (credibleSources.contains(domain)) {
      (0.9, "mainstream")
    } else if (satireSites.contains(domain)) {
      (0.1, "satire")
    } else if (knownFakeSites.contains(domain)) {
      (0.05, "known_fake")
    } else if (domain.endsWith(".gov") || domain.endsWith(".edu")) {
      (0.85, "institutional")
    } else if (domain.contains("blog") || domain.contains("wordpress")) {
      (0.4, "independent")
    } else {
      (0.5, "unknown")
    }

    SourceCredibility(
      domain = domain,
      credibilityScore = score,
      category = category,
      factCheckHistory = None
    )
  }

  override def analyzeLinguisticPatterns(title: String, content: String): LinguisticAnalysis = {
    val text = (title + " " + content).toLowerCase

    // Sensationalism indicators
    val sensationalWords = Set(
      "shocking", "unbelievable", "you won't believe", "mind-blowing",
      "bombshell", "explosive", "devastating", "horrifying", "terrifying",
      "chocante", "inacreditável", "impressionante", "devastador"
    )
    val sensationalismScore = sensationalWords.count(text.contains).toDouble / 5.0 min 1.0

    // Emotional language
    val emotionalWords = Set(
      "outrage", "fury", "anger", "hate", "love", "amazing", "terrible",
      "disgusting", "beautiful", "horrible", "incredible", "unreal",
      "raiva", "ódio", "incrível", "horrível", "maravilhoso"
    )
    val emotionalScore = emotionalWords.count(text.contains).toDouble / 5.0 min 1.0

    // Clickbait patterns
    val clickbaitPatterns = List(
      "you won't believe",
      "what happens next",
      "doctors hate",
      "one weird trick",
      "number \\d+ will shock",
      "this is why",
      "here's what",
      "veja o que aconteceu",
      "você não vai acreditar"
    )
    val clickbaitScore = clickbaitPatterns.count(p => text.matches(s".*$p.*")).toDouble / 3.0 min 1.0

    // Exaggerated claims
    val exaggerationWords = Set(
      "always", "never", "everyone", "no one", "proves", "exposed",
      "sempre", "nunca", "todos", "ninguém", "provado"
    )
    val hasExaggeratedClaims = exaggerationWords.count(text.contains) >= 2

    // Suspicious patterns
    val suspiciousPatterns = List(
      ("ALL_CAPS_WORDS", title.split("\\s+").count(w => w.length > 3 && w == w.toUpperCase) >= 2),
      ("EXCESSIVE_PUNCTUATION", title.count(c => c == '!' || c == '?') >= 2),
      ("MISSING_ATTRIBUTION", !text.contains("according to") && !text.contains("said") && !text.contains("reported")),
      ("NO_DATES", !text.matches(".*\\d{4}.*") && !text.matches(".*\\d{1,2}/\\d{1,2}.*"))
    ).filter(_._2).map(_._1)

    LinguisticAnalysis(
      sensationalismScore = sensationalismScore,
      emotionalLanguageScore = emotionalScore,
      clickbaitScore = clickbaitScore,
      hasExaggeratedClaims = hasExaggeratedClaims,
      suspiciousPatterns = suspiciousPatterns.asJava
    )
  }

  private def calculateCrossReferenceScore(sourceAnalysis: SourceAnalysis): Double = {
    val total = sourceAnalysis.totalSourcesFound
    if (total == 0) return 0.3 // No corroboration is suspicious but not conclusive

    val supportingRatio = sourceAnalysis.supportingCount.toDouble / total
    val contradictingRatio = sourceAnalysis.contradictingCount.toDouble / total

    // Higher score means more likely to be real
    val baseScore = 0.5 + (supportingRatio * 0.4) - (contradictingRatio * 0.3)
    math.max(0.0, math.min(1.0, baseScore))
  }

  private def classifyWithAI(
    headline: NewsHeadline,
    sourceAnalysis: SourceAnalysis,
    keyClaims: List[String]
  ): Task[HeuristicClassification] = {
    val prompt = s"""Analyze this news article for potential misinformation.

Title: ${headline.title}
Source: ${headline.source}
Summary: ${headline.summary.getOrElse("N/A")}

Key claims: ${keyClaims.mkString(", ")}

Cross-reference findings:
- Supporting sources: ${sourceAnalysis.supportingCount}
- Contradicting sources: ${sourceAnalysis.contradictingCount}
- Neutral sources: ${sourceAnalysis.neutralCount}

Based on your analysis, classify this as:
- LIKELY_REAL: Multiple credible sources confirm, no contradictions
- LIKELY_FAKE: Major contradictions, known false claims, or no corroboration
- UNCERTAIN: Mixed signals, needs more investigation

Respond in JSON format:
{"verdict": "...", "confidence": 0.X, "reasoning": "..."}"""

    ZIO.scoped {
      for {
        backend <- HttpClientZioBackend.scoped()

        request = basicRequest
          .post(uri"https://api.openai.com/v1/chat/completions")
          .header("Authorization", s"Bearer $openAiApiKey")
          .header("Content-Type", "application/json")
          .body(OpenAIRequest(
            model = "gpt-4o-mini",
            messages = List(OpenAIMessage("user", prompt)),
            max_tokens = 500
          ).toJson)
          .response(asJson[OpenAIResponse])

        response <- request.send(backend)

        content <- ZIO.fromEither(response.body)
          .map(_.choices.headOption.map(_.message.content).getOrElse(""))
          .mapError(e => new RuntimeException(s"OpenAI API error: $e"))

        classification <- ZIO.fromEither(content.fromJson[HeuristicClassification])
          .catchAll(_ => ZIO.succeed(HeuristicClassification("UNCERTAIN", 0.5, "Failed to parse AI response")))

      } yield classification
    }.catchAll { e =>
      ZIO.succeed(HeuristicClassification("UNCERTAIN", 0.5, s"AI analysis failed: ${e.getMessage}"))
    }
  }

  private def combineFactors(
    headline: NewsHeadline,
    sourceCredibility: SourceCredibility,
    linguistic: LinguisticAnalysis,
    crossRefScore: Double,
    aiAnalysis: HeuristicClassification,
    sourceAnalysis: SourceAnalysis
  ): ClassificationResult = {
    // Weight factors
    val weights = Map(
      "source" -> 0.25,
      "linguistic" -> 0.20,
      "crossRef" -> 0.30,
      "ai" -> 0.25
    )

    // Calculate linguistic credibility (inverse of suspicious patterns)
    val linguisticCredibility = 1.0 - (
      linguistic.sensationalismScore * 0.3 +
        linguistic.clickbaitScore * 0.4 +
        linguistic.emotionalLanguageScore * 0.2 +
        (if (linguistic.hasExaggeratedClaims) 0.1 else 0.0)
      )

    // Weighted average
    val aiScore = aiAnalysis.verdict match {
      case "LIKELY_REAL" => 0.9
      case "LIKELY_FAKE" => 0.1
      case _ => 0.5
    }

    val combinedScore =
      sourceCredibility.credibilityScore * weights("source") +
        linguisticCredibility * weights("linguistic") +
        crossRefScore * weights("crossRef") +
        aiScore * weights("ai")

    // Determine verdict
    val (verdict, confidence) = if (combinedScore >= 0.7) {
      ("LIKELY_REAL", combinedScore)
    } else if (combinedScore <= 0.3) {
      ("LIKELY_FAKE", 1.0 - combinedScore)
    } else {
      ("UNCERTAIN", 0.5 - math.abs(combinedScore - 0.5))
    }

    // Build analysis factors
    val factors = List(
      s"Source credibility: ${sourceCredibility.category} (${(sourceCredibility.credibilityScore * 100).toInt}%)",
      s"Linguistic analysis: ${(linguisticCredibility * 100).toInt}% credible",
      s"Cross-reference: ${sourceAnalysis.supportingCount} supporting, ${sourceAnalysis.contradictingCount} contradicting",
      s"AI analysis: ${aiAnalysis.verdict} (${(aiAnalysis.confidence * 100).toInt}% confident)"
    )

    // Build recommendations
    val recommendations = List.newBuilder[String]
    if (sourceCredibility.category == "unknown") {
      recommendations += "Verify the source's credibility and history"
    }
    if (sourceAnalysis.totalSourcesFound < 2) {
      recommendations += "Look for additional sources to corroborate the story"
    }
    if (linguistic.clickbaitScore > 0.5) {
      recommendations += "Be cautious of clickbait-style headlines"
    }
    if (verdict == "UNCERTAIN") {
      recommendations += "Wait for more information before sharing"
    }

    ClassificationResult(
      headline = headline,
      verdict = verdict,
      confidenceScore = math.max(0.0, math.min(1.0, confidence)),
      reasoning = aiAnalysis.reasoning,
      factorsAnalyzed = factors.asJava,
      recommendations = recommendations.result().asJava
    )
  }
}

case class HeuristicClassification(
  verdict: String,
  confidence: Double,
  reasoning: String
)

object HeuristicClassification {
  implicit val decoder: JsonDecoder[HeuristicClassification] = DeriveJsonDecoder.gen[HeuristicClassification]
}
