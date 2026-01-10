"""AI Agent Service - Uses AI for news analysis and source finding."""

import json
import time
from typing import Optional

import httpx
import structlog
from openai import AsyncOpenAI

from ..config import get_settings
from ..models import (
    AlternativeSource,
    ExtractClaimsResponse,
    FindSourcesResponse,
)

logger = structlog.get_logger()


class AIAgentService:
    """Service for AI-powered news analysis."""

    def __init__(self):
        self.settings = get_settings()
        self.openai_client: Optional[AsyncOpenAI] = None
        if self.settings.openai_api_key:
            self.openai_client = AsyncOpenAI(api_key=self.settings.openai_api_key)

        self.http_client = httpx.AsyncClient(
            timeout=30,
            headers={
                "User-Agent": "Mozilla/5.0 (compatible; NewsVerificationBot/1.0)"
            },
        )

    async def close(self):
        """Close clients."""
        await self.http_client.aclose()
        if self.openai_client:
            await self.openai_client.close()

    def _generate_search_queries(self, headline: str) -> list[str]:
        """Generate search queries from a headline."""
        # Remove common words and create variations
        stop_words = {"the", "a", "an", "is", "are", "was", "were", "be", "been", "being"}
        words = [w for w in headline.split() if w.lower() not in stop_words]

        queries = [
            headline,  # Original headline
            " ".join(words[:7]),  # First significant words
            " ".join(words[-5:]) if len(words) > 5 else " ".join(words),  # Last words
        ]

        return queries

    async def _search_news_api(
        self, query: str, max_results: int = 5
    ) -> list[dict]:
        """
        Search for news using a simulated search.

        In production, this would use:
        - Google Custom Search API
        - Bing News Search API
        - NewsAPI.org
        - GDELT API
        """
        # For POC, we return simulated results
        # In production, integrate with actual news search APIs
        logger.info("searching_news", query=query[:50])

        # Simulated search results structure
        # Real implementation would call external API
        return []

    async def _analyze_with_ai(
        self, prompt: str, system_prompt: str
    ) -> Optional[str]:
        """Make an AI completion request."""
        if not self.openai_client:
            logger.warning("openai_not_configured")
            return None

        try:
            response = await self.openai_client.chat.completions.create(
                model=self.settings.openai_model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": prompt},
                ],
                temperature=0.3,
                max_tokens=1000,
            )
            return response.choices[0].message.content
        except Exception as e:
            logger.error("ai_analysis_failed", error=str(e))
            return None

    async def find_alternative_sources(
        self,
        headline: str,
        original_content: str,
        original_source: str,
        max_sources: int = 5,
    ) -> FindSourcesResponse:
        """Find alternative sources reporting similar news."""
        start_time = time.time()
        logger.info("finding_sources", headline=headline[:50])

        queries = self._generate_search_queries(headline)
        all_sources: list[AlternativeSource] = []

        # Search using generated queries
        for query in queries:
            results = await self._search_news_api(query, max_results=max_sources)
            for result in results:
                if result.get("domain") != original_source:
                    all_sources.append(
                        AlternativeSource(
                            title=result.get("title", ""),
                            url=result.get("url", ""),
                            source_domain=result.get("domain", ""),
                            similarity_score=result.get("similarity", 0.5),
                            snippet=result.get("snippet"),
                            supports_original=True,  # Would be determined by AI
                        )
                    )

        # If AI is available, analyze sources for contradictions
        if self.openai_client and all_sources:
            system_prompt = """You are a news analysis assistant. Analyze alternative news sources
            and determine if they support or contradict the original article.
            Return JSON with source analysis."""

            prompt = f"""Original headline: {headline}
            Original content summary: {original_content[:500]}

            Alternative sources to analyze:
            {json.dumps([s.model_dump() for s in all_sources[:5]], indent=2)}

            For each source, determine if it supports or contradicts the original."""

            analysis = await self._analyze_with_ai(prompt, system_prompt)
            if analysis:
                # Parse AI response and update sources
                logger.info("ai_source_analysis_complete")

        # Deduplicate and limit
        seen_domains = set()
        unique_sources = []
        for source in all_sources:
            if source.source_domain not in seen_domains:
                seen_domains.add(source.source_domain)
                unique_sources.append(source)
                if len(unique_sources) >= max_sources:
                    break

        search_time_ms = int((time.time() - start_time) * 1000)

        return FindSourcesResponse(
            original_headline=headline,
            sources_found=unique_sources,
            search_queries_used=queries,
            search_time_ms=search_time_ms,
        )

    async def extract_claims(
        self, headline: str, content: str
    ) -> ExtractClaimsResponse:
        """Extract factual claims from article content."""
        start_time = time.time()
        logger.info("extracting_claims", headline=headline[:50])

        claims = []

        if self.openai_client:
            system_prompt = """You are a fact-checking assistant. Extract specific, verifiable
            factual claims from news articles. Focus on:
            - Specific numbers, dates, or statistics
            - Quotes attributed to named individuals
            - Events with specific locations and times
            - Policy announcements or decisions

            Return as a JSON array of claim strings."""

            prompt = f"""Headline: {headline}

            Article content:
            {content[:3000]}

            Extract 3-7 key factual claims that can be verified."""

            response = await self._analyze_with_ai(prompt, system_prompt)
            if response:
                try:
                    # Try to parse JSON response
                    claims = json.loads(response)
                    if not isinstance(claims, list):
                        claims = [str(claims)]
                except json.JSONDecodeError:
                    # Extract claims from text response
                    lines = response.strip().split("\n")
                    claims = [
                        line.strip("- •123456789.)")
                        for line in lines
                        if line.strip() and len(line.strip()) > 10
                    ]
        else:
            # Heuristic claim extraction without AI
            sentences = content.split(". ")
            claim_indicators = [
                "said", "announced", "reported", "according to",
                "percent", "%", "million", "billion",
                "will", "has been", "have been",
            ]
            for sentence in sentences:
                if any(indicator in sentence.lower() for indicator in claim_indicators):
                    if 20 < len(sentence) < 300:
                        claims.append(sentence.strip())
                        if len(claims) >= 5:
                            break

        extraction_time_ms = int((time.time() - start_time) * 1000)

        return ExtractClaimsResponse(
            headline=headline,
            claims=claims[:7],
            claim_count=len(claims),
            extraction_time_ms=extraction_time_ms,
        )


# Singleton instance
_ai_service: Optional[AIAgentService] = None


def get_ai_service() -> AIAgentService:
    """Get or create the AI service instance."""
    global _ai_service
    if _ai_service is None:
        _ai_service = AIAgentService()
    return _ai_service
