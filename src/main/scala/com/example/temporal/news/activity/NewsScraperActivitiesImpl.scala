package com.example.temporal.news.activity

import com.example.temporal.core.ZTemporalActivity.ZActivityImplementation
import com.example.temporal.news.model._
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import sttp.client3._
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio._

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * ZIO-based implementation of NewsScraperActivities
 * Uses Jsoup for HTML parsing and STTP for HTTP requests
 */
class NewsScraperActivitiesImpl extends NewsScraperActivities with ZActivityImplementation {

  private val userAgent = "Mozilla/5.0 (compatible; NewsVerificationBot/1.0)"

  override def fetchHeadlines(sourceUrl: String, maxHeadlines: Int): java.util.List[NewsHeadline] = run {
    for {
      _ <- ZIO.logInfo(s"Fetching headlines from: $sourceUrl")

      headlines <- if (sourceUrl.contains("rss") || sourceUrl.endsWith(".xml")) {
        fetchFromRss(sourceUrl, maxHeadlines)
      } else {
        fetchFromHtml(sourceUrl, maxHeadlines)
      }

      _ <- ZIO.logInfo(s"Found ${headlines.size} headlines from $sourceUrl")
    } yield headlines.asJava
  }

  override def extractArticleContent(url: String): ArticleContent = run {
    for {
      _ <- ZIO.logInfo(s"Extracting article content from: $url")

      content <- ZIO.attempt {
        val doc = Jsoup.connect(url)
          .userAgent(userAgent)
          .timeout(10000)
          .get()

        val title = doc.title()

        // Try common article content selectors
        val articleSelectors = List(
          "article",
          "[itemprop=articleBody]",
          ".article-content",
          ".post-content",
          ".entry-content",
          ".story-body",
          "main"
        )

        val content = articleSelectors
          .map(selector => Option(doc.select(selector).first()))
          .collectFirst { case Some(el) => el.text() }
          .getOrElse(doc.body().text().take(5000))

        val author = Option(doc.select("[rel=author], .author, .byline").first())
          .map(_.text())

        val publishedDate = Option(doc.select("time, [datetime], .published, .date").first())
          .map(_.text())

        ArticleContent(
          url = url,
          title = title,
          content = content.take(10000),
          author = author,
          publishedDate = publishedDate,
          extractedSuccessfully = content.nonEmpty
        )
      }.catchAll { e =>
        ZIO.succeed(ArticleContent(
          url = url,
          title = "",
          content = "",
          author = None,
          publishedDate = None,
          extractedSuccessfully = false
        ))
      }

      _ <- ZIO.logInfo(s"Extracted ${content.content.length} chars from $url")
    } yield content
  }

  override def searchNews(query: String, maxResults: Int): java.util.List[NewsHeadline] = run {
    for {
      _ <- ZIO.logInfo(s"Searching news for: $query")

      // Use DuckDuckGo HTML search as a simple search
      searchUrl = s"https://html.duckduckgo.com/html/?q=${java.net.URLEncoder.encode(query + " news", "UTF-8")}"

      results <- ZIO.attempt {
        val doc = Jsoup.connect(searchUrl)
          .userAgent(userAgent)
          .timeout(15000)
          .get()

        val results = doc.select(".result")
          .asScala
          .take(maxResults)
          .flatMap { result =>
            for {
              titleEl <- Option(result.select(".result__a").first())
              title = titleEl.text()
              url = titleEl.attr("href")
              snippet = Option(result.select(".result__snippet").first()).map(_.text()).getOrElse("")
            } yield NewsHeadline(
              title = title,
              source = extractDomain(url),
              url = url,
              summary = Some(snippet)
            )
          }
          .toList

        results
      }.catchAll { e =>
        ZIO.logError(s"Search failed: ${e.getMessage}") *>
          ZIO.succeed(List.empty[NewsHeadline])
      }

      _ <- ZIO.logInfo(s"Found ${results.size} search results for: $query")
    } yield results.asJava
  }

  private def fetchFromRss(url: String, maxHeadlines: Int): Task[List[NewsHeadline]] = {
    ZIO.attempt {
      val doc = Jsoup.connect(url)
        .userAgent(userAgent)
        .timeout(10000)
        .ignoreContentType(true)
        .get()

      val items = doc.select("item, entry")

      items.asScala
        .take(maxHeadlines)
        .map { item =>
          val title = Option(item.select("title").first()).map(_.text()).getOrElse("")
          val link = Option(item.select("link").first())
            .map(el => if (el.text().nonEmpty) el.text() else el.attr("href"))
            .getOrElse("")
          val pubDate = Option(item.select("pubDate, published").first()).map(_.text())
          val description = Option(item.select("description, summary").first()).map(_.text())

          NewsHeadline(
            title = title,
            source = extractDomain(url),
            url = link,
            publishedAt = pubDate,
            summary = description.map(_.take(500))
          )
        }
        .toList
    }
  }

  private def fetchFromHtml(url: String, maxHeadlines: Int): Task[List[NewsHeadline]] = {
    ZIO.attempt {
      val doc = Jsoup.connect(url)
        .userAgent(userAgent)
        .timeout(10000)
        .get()

      // Common headline selectors for news sites
      val headlineSelectors = List(
        "h1 a", "h2 a", "h3 a",
        ".headline a",
        ".title a",
        "article h2 a",
        ".story-heading a",
        "[data-testid=headline] a"
      )

      val headlines = headlineSelectors
        .flatMap { selector =>
          doc.select(selector).asScala.map { el =>
            val title = el.text()
            val link = el.absUrl("href")
            (title, link)
          }
        }
        .filter { case (title, link) =>
          title.length > 20 && link.nonEmpty && !link.contains("#")
        }
        .distinctBy(_._1)
        .take(maxHeadlines)
        .map { case (title, link) =>
          NewsHeadline(
            title = title,
            source = extractDomain(url),
            url = link,
            publishedAt = None,
            summary = None
          )
        }

      headlines
    }
  }

  private def extractDomain(url: String): String = {
    Try {
      val uri = new java.net.URI(url)
      uri.getHost.replaceFirst("^www\\.", "")
    }.getOrElse(url)
  }
}
