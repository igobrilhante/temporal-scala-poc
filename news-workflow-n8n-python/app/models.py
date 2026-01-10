"""Domain models for the News Verification Workflow."""

from datetime import datetime
from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


class NewsVerdict(str, Enum):
    """Classification verdict for news articles."""

    LIKELY_REAL = "LIKELY_REAL"
    LIKELY_FAKE = "LIKELY_FAKE"
    UNCERTAIN = "UNCERTAIN"


class SourceType(str, Enum):
    """Type of news source."""

    RSS = "RSS"
    HTML = "HTML"
    API = "API"


# ==================== Request Models ====================


class FetchHeadlinesRequest(BaseModel):
    """Request to fetch headlines from a news source."""

    source_url: str = Field(..., description="URL of the news source (RSS feed or website)")
    max_headlines: int = Field(default=5, ge=1, le=50, description="Maximum number of headlines to fetch")
    source_type: Optional[SourceType] = Field(default=None, description="Type of source (auto-detected if not provided)")


class ExtractContentRequest(BaseModel):
    """Request to extract full content from an article URL."""

    article_url: str = Field(..., description="URL of the article to extract content from")
    headline: str = Field(..., description="Original headline of the article")


class FindSourcesRequest(BaseModel):
    """Request to find alternative sources for a news article."""

    headline: str = Field(..., description="Headline to search for")
    original_content: str = Field(..., description="Original article content")
    original_source: str = Field(..., description="Original source domain")
    max_sources: int = Field(default=5, ge=1, le=20, description="Maximum alternative sources to find")


class ExtractClaimsRequest(BaseModel):
    """Request to extract factual claims from article content."""

    headline: str = Field(..., description="Article headline")
    content: str = Field(..., description="Article content to analyze")


class ClassifyNewsRequest(BaseModel):
    """Request to classify a news article."""

    headline: str = Field(..., description="Article headline")
    content: str = Field(..., description="Article content")
    source_domain: str = Field(..., description="Domain of the original source")
    alternative_sources: list[dict] = Field(default_factory=list, description="Alternative sources found")
    claims: list[str] = Field(default_factory=list, description="Extracted claims from the article")


# ==================== Response Models ====================


class Headline(BaseModel):
    """A news headline extracted from a source."""

    title: str = Field(..., description="Headline title")
    url: str = Field(..., description="URL to the full article")
    source_domain: str = Field(..., description="Domain of the source")
    summary: Optional[str] = Field(default=None, description="Brief summary if available")
    published_date: Optional[datetime] = Field(default=None, description="Publication date")


class FetchHeadlinesResponse(BaseModel):
    """Response containing fetched headlines."""

    source_url: str
    source_type: SourceType
    headlines: list[Headline]
    fetch_time_ms: int


class ArticleContent(BaseModel):
    """Extracted content from a news article."""

    url: str
    headline: str
    content: str
    word_count: int
    extraction_time_ms: int


class AlternativeSource(BaseModel):
    """An alternative source reporting similar news."""

    title: str
    url: str
    source_domain: str
    similarity_score: float = Field(..., ge=0.0, le=1.0)
    snippet: Optional[str] = None
    supports_original: bool = True


class FindSourcesResponse(BaseModel):
    """Response containing alternative sources."""

    original_headline: str
    sources_found: list[AlternativeSource]
    search_queries_used: list[str]
    search_time_ms: int


class ExtractClaimsResponse(BaseModel):
    """Response containing extracted claims."""

    headline: str
    claims: list[str]
    claim_count: int
    extraction_time_ms: int


class AnalysisFactor(BaseModel):
    """A factor considered in the classification analysis."""

    name: str
    score: float = Field(..., ge=0.0, le=1.0)
    weight: float = Field(..., ge=0.0, le=1.0)
    details: str


class ClassificationResult(BaseModel):
    """Result of news classification."""

    headline: str
    verdict: NewsVerdict
    confidence: float = Field(..., ge=0.0, le=1.0)
    factors: list[AnalysisFactor]
    supporting_sources: int
    contradicting_sources: int
    summary: str
    classification_time_ms: int


class WorkflowResult(BaseModel):
    """Final result of the complete verification workflow."""

    request_id: str
    source_url: str
    headlines_analyzed: int
    results: list[ClassificationResult]
    statistics: dict
    total_time_ms: int
    timestamp: datetime = Field(default_factory=datetime.utcnow)


# ==================== Health Check ====================


class HealthResponse(BaseModel):
    """Health check response."""

    status: str = "healthy"
    version: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)
