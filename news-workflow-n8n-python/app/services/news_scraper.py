"""News Scraper Service - Fetches and extracts news content."""

import time
from datetime import datetime
from typing import Optional
from urllib.parse import urlparse

import feedparser
import httpx
import structlog
from bs4 import BeautifulSoup
from tenacity import retry, stop_after_attempt, wait_exponential

from ..config import get_settings
from ..models import (
    ArticleContent,
    FetchHeadlinesRequest,
    FetchHeadlinesResponse,
    Headline,
    SourceType,
)

logger = structlog.get_logger()


class NewsScraperService:
    """Service for scraping news headlines and content."""

    def __init__(self):
        self.settings = get_settings()
        self.client = httpx.AsyncClient(
            timeout=self.settings.request_timeout,
            follow_redirects=True,
            headers={
                "User-Agent": "Mozilla/5.0 (compatible; NewsVerificationBot/1.0)"
            },
        )

    async def close(self):
        """Close the HTTP client."""
        await self.client.aclose()

    def _detect_source_type(self, url: str, content: str) -> SourceType:
        """Detect the type of news source."""
        if url.endswith(".xml") or url.endswith("/rss") or "/feed" in url:
            return SourceType.RSS
        if "<?xml" in content[:100] or "<rss" in content[:200]:
            return SourceType.RSS
        return SourceType.HTML

    def _extract_domain(self, url: str) -> str:
        """Extract domain from URL."""
        parsed = urlparse(url)
        domain = parsed.netloc
        if domain.startswith("www."):
            domain = domain[4:]
        return domain

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=1, max=10),
    )
    async def _fetch_url(self, url: str) -> str:
        """Fetch URL content with retry logic."""
        response = await self.client.get(url)
        response.raise_for_status()
        return response.text

    def _parse_rss_feed(
        self, content: str, source_url: str, max_headlines: int
    ) -> list[Headline]:
        """Parse RSS feed content."""
        feed = feedparser.parse(content)
        headlines = []

        for entry in feed.entries[:max_headlines]:
            published_date = None
            if hasattr(entry, "published_parsed") and entry.published_parsed:
                try:
                    published_date = datetime(*entry.published_parsed[:6])
                except (TypeError, ValueError):
                    pass

            summary = None
            if hasattr(entry, "summary"):
                soup = BeautifulSoup(entry.summary, "html.parser")
                summary = soup.get_text()[:300]

            headlines.append(
                Headline(
                    title=entry.title,
                    url=entry.link,
                    source_domain=self._extract_domain(entry.link),
                    summary=summary,
                    published_date=published_date,
                )
            )

        return headlines

    def _parse_html_page(
        self, content: str, source_url: str, max_headlines: int
    ) -> list[Headline]:
        """Parse HTML page for news headlines."""
        soup = BeautifulSoup(content, "lxml")
        headlines = []
        source_domain = self._extract_domain(source_url)

        # Common selectors for news headlines
        selectors = [
            "article h2 a",
            "article h3 a",
            ".headline a",
            ".news-title a",
            "h2.title a",
            "h3.title a",
            ".story-heading a",
            ".post-title a",
        ]

        seen_urls = set()

        for selector in selectors:
            if len(headlines) >= max_headlines:
                break

            elements = soup.select(selector)
            for elem in elements:
                if len(headlines) >= max_headlines:
                    break

                href = elem.get("href", "")
                if not href or href in seen_urls:
                    continue

                # Make absolute URL
                if href.startswith("/"):
                    parsed = urlparse(source_url)
                    href = f"{parsed.scheme}://{parsed.netloc}{href}"
                elif not href.startswith("http"):
                    continue

                seen_urls.add(href)
                title = elem.get_text(strip=True)

                if title and len(title) > 10:
                    headlines.append(
                        Headline(
                            title=title,
                            url=href,
                            source_domain=source_domain,
                            summary=None,
                            published_date=None,
                        )
                    )

        return headlines

    async def fetch_headlines(
        self, request: FetchHeadlinesRequest
    ) -> FetchHeadlinesResponse:
        """Fetch headlines from a news source."""
        start_time = time.time()
        logger.info("fetching_headlines", source_url=request.source_url)

        content = await self._fetch_url(request.source_url)
        source_type = request.source_type or self._detect_source_type(
            request.source_url, content
        )

        if source_type == SourceType.RSS:
            headlines = self._parse_rss_feed(
                content, request.source_url, request.max_headlines
            )
        else:
            headlines = self._parse_html_page(
                content, request.source_url, request.max_headlines
            )

        fetch_time_ms = int((time.time() - start_time) * 1000)

        logger.info(
            "headlines_fetched",
            count=len(headlines),
            source_type=source_type.value,
            time_ms=fetch_time_ms,
        )

        return FetchHeadlinesResponse(
            source_url=request.source_url,
            source_type=source_type,
            headlines=headlines,
            fetch_time_ms=fetch_time_ms,
        )

    async def extract_article_content(
        self, article_url: str, headline: str
    ) -> ArticleContent:
        """Extract full content from an article URL."""
        start_time = time.time()
        logger.info("extracting_content", url=article_url)

        content = await self._fetch_url(article_url)
        soup = BeautifulSoup(content, "lxml")

        # Remove unwanted elements
        for tag in soup(["script", "style", "nav", "header", "footer", "aside", "ads"]):
            tag.decompose()

        # Try to find article content
        article_selectors = [
            "article",
            '[role="main"]',
            ".article-body",
            ".story-body",
            ".post-content",
            ".entry-content",
            "#article-body",
            ".article-content",
        ]

        article_text = ""
        for selector in article_selectors:
            elem = soup.select_one(selector)
            if elem:
                paragraphs = elem.find_all("p")
                article_text = " ".join(p.get_text(strip=True) for p in paragraphs)
                if len(article_text) > 200:
                    break

        # Fallback to all paragraphs
        if len(article_text) < 200:
            paragraphs = soup.find_all("p")
            article_text = " ".join(p.get_text(strip=True) for p in paragraphs)

        # Clean up text
        article_text = " ".join(article_text.split())

        extraction_time_ms = int((time.time() - start_time) * 1000)
        word_count = len(article_text.split())

        logger.info(
            "content_extracted",
            url=article_url,
            word_count=word_count,
            time_ms=extraction_time_ms,
        )

        return ArticleContent(
            url=article_url,
            headline=headline,
            content=article_text[:10000],  # Limit content size
            word_count=word_count,
            extraction_time_ms=extraction_time_ms,
        )


# Singleton instance
_scraper_service: Optional[NewsScraperService] = None


def get_scraper_service() -> NewsScraperService:
    """Get or create the scraper service instance."""
    global _scraper_service
    if _scraper_service is None:
        _scraper_service = NewsScraperService()
    return _scraper_service
