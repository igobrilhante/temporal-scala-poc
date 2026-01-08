package com.example.temporal.news.activity

import com.example.temporal.core.ZTemporalActivity.ZActivityImplementation
import com.example.temporal.news.model._
import io.temporal.activity.Activity
import org.jsoup.Jsoup
import sttp.client3._
import sttp.client3.ziojson._
import zio._
import zio.json._

import scala.jdk.CollectionConverters._

/**
 * ZIO-based implementation of AIAgentActivities
 * Uses OpenAI API for intelligent news analysis
 */
class AIAgentActivitiesImpl(apiKey: Option[String] = None) extends AIAgentActivities with ZActivityImplementation {

  private val openAiApiKey: String = apiKey
    .orElse(sys.env.get("OPENAI_API_KEY"))
    .getOrElse("")

  private val userAgent = "Mozilla/5.0 (compatible; NewsVerificationBot/1.0)"

  private val newsScraperActivities = new NewsScraperActivitiesImpl()

  override def findAlternativeSources(
    headline: NewsHeadline,
    articleContent: String,
    maxSources: Int
  ): SourceAnalysis = run {
    for {
      _ <- ZIO.logInfo(s"Finding alternative sources for: ${headline.title}")

      // Step 1: Generate search queries using AI
      searchQueries <- generateSearchQueries(headline.title, articleContent)
      _ <- ZIO.logInfo(s"Generated ${searchQueries.size} search queries")

      // Step 2: Search for alternative sources
      searchResults <- ZIO.foreach(searchQueries.take(3)) { query =>
        ZIO.attempt {
          newsScraperActivities.searchNews(query, maxSources)
        }.catchAll(_ => ZIO.succeed(java.util.Collections.emptyList[NewsHeadline]()))
      }

      allResults = searchResults.flatMap(_.asScala).distinctBy(_.url).filterNot(_.url == headline.url)
      _ <- ZIO.logInfo(s"Found ${allResults.size} potential alternative sources")

      // Step 3: Analyze each source
      analyzedSources <- ZIO.foreach(allResults.take(maxSources)) { altHeadline =>
        analyzeSource(headline, articleContent, altHeadline)
      }

      validSources = analyzedSources.filter(_.similarity > 0.3)

      supportingCount = validSources.count(_.stance == "supporting")
      contradictingCount = validSources.count(_.stance == "contradicting")
      neutralCount = validSources.count(_.stance == "neutral")

      result = SourceAnalysis(
        headline = headline,
        alternativeSources = validSources.asJava,
        totalSourcesFound = validSources.size,
        supportingCount = supportingCount,
        contradictingCount = contradictingCount,
        neutralCount = neutralCount
      )

      _ <- ZIO.logInfo(s"Analysis complete: ${validSources.size} relevant sources found")
    } yield result
  }

  override def extractKeyClaims(headline: NewsHeadline, content: String): java.util.List[String] = run {
    for {
      _ <- ZIO.logInfo(s"Extracting key claims from: ${headline.title}")

      claims <- if (openAiApiKey.nonEmpty) {
        extractClaimsWithAI(headline.title, content)
      } else {
        extractClaimsHeuristically(headline.title, content)
      }

      _ <- ZIO.logInfo(s"Extracted ${claims.size} key claims")
    } yield claims.asJava
  }

  override def compareArticles(
    originalTitle: String,
    originalContent: String,
    comparisonTitle: String,
    comparisonContent: String
  ): ArticleComparison = run {
    for {
      _ <- ZIO.logInfo(s"Comparing articles: '$originalTitle' vs '$comparisonTitle'")

      comparison <- if (openAiApiKey.nonEmpty) {
        compareWithAI(originalTitle, originalContent, comparisonTitle, comparisonContent)
      } else {
        compareHeuristically(originalTitle, originalContent, comparisonTitle, comparisonContent)
      }

      _ <- ZIO.logInfo(s"Comparison result: ${comparison.stance} (similarity: ${comparison.similarity})")
    } yield comparison
  }

  private def generateSearchQueries(title: String, content: String): Task[List[String]] = {
    if (openAiApiKey.nonEmpty) {
      generateQueriesWithAI(title, content)
    } else {
      generateQueriesHeuristically(title)
    }
  }

  private def generateQueriesWithAI(title: String, content: String): Task[List[String]] = {
    val prompt = s"""Given this news headline and content, generate 3 different search queries
                    |to find other news sources covering the same story.
                    |Return only the queries, one per line.
                    |
                    |Headline: $title
                    |Content: ${content.take(1000)}""".stripMargin

    callOpenAI(prompt).map { response =>
      response.split("\n").map(_.trim).filter(_.nonEmpty).take(3).toList
    }.catchAll { _ =>
      generateQueriesHeuristically(title)
    }
  }

  private def generateQueriesHeuristically(title: String): Task[List[String]] = ZIO.succeed {
    val words = title.split("\\s+").filter(_.length > 3)
    val keyWords = words.take(5).mkString(" ")

    List(
      title,
      keyWords,
      words.take(3).mkString(" ") + " news"
    )
  }

  private def analyzeSource(
    original: NewsHeadline,
    originalContent: String,
    alternative: NewsHeadline
  ): Task[AlternativeSource] = {
    for {
      // Fetch content from alternative source
      altContent <- ZIO.attempt {
        newsScraperActivities.extractArticleContent(alternative.url)
      }.catchAll(_ => ZIO.succeed(ArticleContent(alternative.url, "", "", None, None, false)))

      // Compare the articles
      comparison <- ZIO.attempt {
        compareArticles(
          original.title,
          originalContent.take(2000),
          alternative.title,
          altContent.content.take(2000)
        )
      }.catchAll(_ => ZIO.succeed(ArticleComparison(0.0, "neutral", java.util.Collections.emptyList(), java.util.Collections.emptyList(), "")))

    } yield AlternativeSource(
      sourceName = alternative.source,
      url = alternative.url,
      title = alternative.title,
      similarity = comparison.similarity,
      stance = comparison.stance,
      snippet = alternative.summary.getOrElse(altContent.content.take(200))
    )
  }

  private def extractClaimsWithAI(title: String, content: String): Task[List[String]] = {
    val prompt = s"""Extract the 5 most important factual claims from this news article.
                    |Return each claim on a separate line.
                    |
                    |Title: $title
                    |Content: ${content.take(2000)}""".stripMargin

    callOpenAI(prompt).map { response =>
      response.split("\n").map(_.trim).filter(_.nonEmpty).toList
    }
  }

  private def extractClaimsHeuristically(title: String, content: String): Task[List[String]] = ZIO.succeed {
    // Simple heuristic: extract sentences with key patterns
    val sentences = (title + ". " + content)
      .split("[.!?]")
      .map(_.trim)
      .filter(_.length > 30)
      .filter(s => s.contains(" said ") || s.contains(" announced ") ||
        s.contains(" reported ") || s.contains(" according to ") ||
        s.matches(".*\\d+.*") || // Contains numbers
        s.contains(" will ") || s.contains(" has ") || s.contains(" have "))
      .take(5)
      .toList

    if (sentences.isEmpty) List(title) else sentences
  }

  private def compareWithAI(
    title1: String,
    content1: String,
    title2: String,
    content2: String
  ): Task[ArticleComparison] = {
    val prompt = s"""Compare these two news articles and analyze:
                    |1. How similar are they (0.0 to 1.0)?
                    |2. What is the stance of article 2 relative to article 1? (supporting/contradicting/neutral)
                    |3. What facts do they share?
                    |4. Are there any conflicting claims?
                    |
                    |Article 1 Title: $title1
                    |Article 1 Content: ${content1.take(1000)}
                    |
                    |Article 2 Title: $title2
                    |Article 2 Content: ${content2.take(1000)}
                    |
                    |Respond in JSON format:
                    |{"similarity": 0.X, "stance": "...", "sharedFacts": [...], "conflictingClaims": [...], "analysis": "..."}""".stripMargin

    callOpenAI(prompt).flatMap { response =>
      ZIO.fromEither(response.fromJson[AIComparisonResponse])
        .map(r => ArticleComparison(
          r.similarity,
          r.stance,
          r.sharedFacts.asJava,
          r.conflictingClaims.asJava,
          r.analysis
        ))
        .catchAll(_ => compareHeuristically(title1, content1, title2, content2))
    }
  }

  private def compareHeuristically(
    title1: String,
    content1: String,
    title2: String,
    content2: String
  ): Task[ArticleComparison] = ZIO.succeed {
    // Simple word overlap comparison
    val words1 = (title1 + " " + content1).toLowerCase.split("\\W+").toSet
    val words2 = (title2 + " " + content2).toLowerCase.split("\\W+").toSet

    val commonWords = words1.intersect(words2)
    val similarity = if (words1.nonEmpty) commonWords.size.toDouble / words1.size else 0.0

    // Determine stance based on presence of contradicting language
    val contradictingIndicators = Set("false", "fake", "debunked", "incorrect", "wrong", "denies", "disputed")
    val hasContradiction = words2.exists(contradictingIndicators.contains)

    val stance = if (hasContradiction) "contradicting"
    else if (similarity > 0.5) "supporting"
    else "neutral"

    ArticleComparison(
      similarity = math.min(similarity * 2, 1.0), // Scale up for heuristic
      stance = stance,
      sharedFacts = commonWords.filter(_.length > 5).take(5).toList.asJava,
      conflictingClaims = List.empty[String].asJava,
      analysis = s"Heuristic comparison based on ${commonWords.size} common words"
    )
  }

  private def callOpenAI(prompt: String): Task[String] = {
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
            max_tokens = 1000
          ).toJson)
          .response(asJson[OpenAIResponse])

        response <- request.send(backend)

        content <- ZIO.fromEither(response.body)
          .map(_.choices.headOption.map(_.message.content).getOrElse(""))
          .mapError(e => new RuntimeException(s"OpenAI API error: $e"))

      } yield content
    }
  }
}

// OpenAI API models
case class OpenAIMessage(role: String, content: String)
object OpenAIMessage {
  implicit val encoder: JsonEncoder[OpenAIMessage] = DeriveJsonEncoder.gen[OpenAIMessage]
  implicit val decoder: JsonDecoder[OpenAIMessage] = DeriveJsonDecoder.gen[OpenAIMessage]
}

case class OpenAIRequest(model: String, messages: List[OpenAIMessage], max_tokens: Int)
object OpenAIRequest {
  implicit val encoder: JsonEncoder[OpenAIRequest] = DeriveJsonEncoder.gen[OpenAIRequest]
}

case class OpenAIChoice(message: OpenAIMessage)
object OpenAIChoice {
  implicit val decoder: JsonDecoder[OpenAIChoice] = DeriveJsonDecoder.gen[OpenAIChoice]
}

case class OpenAIResponse(choices: List[OpenAIChoice])
object OpenAIResponse {
  implicit val decoder: JsonDecoder[OpenAIResponse] = DeriveJsonDecoder.gen[OpenAIResponse]
}

case class AIComparisonResponse(
  similarity: Double,
  stance: String,
  sharedFacts: List[String],
  conflictingClaims: List[String],
  analysis: String
)
object AIComparisonResponse {
  implicit val decoder: JsonDecoder[AIComparisonResponse] = DeriveJsonDecoder.gen[AIComparisonResponse]
}
