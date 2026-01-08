package com.example.temporal.news.activity

import com.example.temporal.news.model._
import io.temporal.activity.{ActivityInterface, ActivityMethod}

/**
 * Activities for scraping news headlines from various sources
 */
@ActivityInterface
trait NewsScraperActivities {

  /**
   * Fetch headlines from a news source
   * Supports RSS feeds and HTML scraping
   */
  @ActivityMethod
  def fetchHeadlines(sourceUrl: String, maxHeadlines: Int): java.util.List[NewsHeadline]

  /**
   * Extract full article content from a headline URL
   */
  @ActivityMethod
  def extractArticleContent(url: String): ArticleContent

  /**
   * Search for news using a search engine
   */
  @ActivityMethod
  def searchNews(query: String, maxResults: Int): java.util.List[NewsHeadline]
}

case class ArticleContent(
  url: String,
  title: String,
  content: String,
  author: Option[String],
  publishedDate: Option[String],
  extractedSuccessfully: Boolean
)
