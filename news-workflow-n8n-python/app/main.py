"""FastAPI application for News Verification Workflow."""

import structlog
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from . import __version__
from .config import get_settings
from .models import (
    ArticleContent,
    ClassificationResult,
    ClassifyNewsRequest,
    ExtractClaimsRequest,
    ExtractClaimsResponse,
    ExtractContentRequest,
    FetchHeadlinesRequest,
    FetchHeadlinesResponse,
    FindSourcesRequest,
    FindSourcesResponse,
    HealthResponse,
)
from .services.news_scraper import get_scraper_service
from .services.ai_agent import get_ai_service
from .services.news_classifier import get_classifier_service

# Configure structured logging
structlog.configure(
    processors=[
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.JSONRenderer(),
    ]
)
logger = structlog.get_logger()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manage application lifecycle."""
    logger.info("starting_application", version=__version__)
    yield
    # Cleanup on shutdown
    scraper = get_scraper_service()
    ai_service = get_ai_service()
    classifier = get_classifier_service()
    await scraper.close()
    await ai_service.close()
    await classifier.close()
    logger.info("application_shutdown")


app = FastAPI(
    title="News Verification API",
    description="API endpoints for the News Verification Workflow with n8n",
    version=__version__,
    lifespan=lifespan,
)

# CORS middleware for n8n access
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ==================== Health Check ====================


@app.get("/health", response_model=HealthResponse, tags=["Health"])
async def health_check():
    """Check API health status."""
    return HealthResponse(status="healthy", version=__version__)


# ==================== News Scraper Endpoints ====================


@app.post(
    "/api/v1/scraper/headlines",
    response_model=FetchHeadlinesResponse,
    tags=["Scraper"],
    summary="Fetch headlines from a news source",
)
async def fetch_headlines(request: FetchHeadlinesRequest):
    """
    Fetch headlines from a news source (RSS feed or website).

    This endpoint:
    - Detects the source type (RSS or HTML)
    - Extracts headlines with titles, URLs, and summaries
    - Returns structured headline data for further processing
    """
    try:
        scraper = get_scraper_service()
        return await scraper.fetch_headlines(request)
    except Exception as e:
        logger.error("fetch_headlines_failed", error=str(e))
        raise HTTPException(status_code=500, detail=str(e))


@app.post(
    "/api/v1/scraper/content",
    response_model=ArticleContent,
    tags=["Scraper"],
    summary="Extract full content from an article",
)
async def extract_content(request: ExtractContentRequest):
    """
    Extract full article content from a URL.

    This endpoint:
    - Fetches the article page
    - Extracts the main content (removing navigation, ads, etc.)
    - Returns cleaned text content
    """
    try:
        scraper = get_scraper_service()
        return await scraper.extract_article_content(request.article_url, request.headline)
    except Exception as e:
        logger.error("extract_content_failed", error=str(e))
        raise HTTPException(status_code=500, detail=str(e))


# ==================== AI Agent Endpoints ====================


@app.post(
    "/api/v1/agent/sources",
    response_model=FindSourcesResponse,
    tags=["AI Agent"],
    summary="Find alternative news sources",
)
async def find_sources(request: FindSourcesRequest):
    """
    Find alternative sources reporting similar news.

    This endpoint:
    - Generates search queries from the headline
    - Searches for related articles from different sources
    - Analyzes if sources support or contradict the original
    """
    try:
        ai_service = get_ai_service()
        return await ai_service.find_alternative_sources(
            headline=request.headline,
            original_content=request.original_content,
            original_source=request.original_source,
            max_sources=request.max_sources,
        )
    except Exception as e:
        logger.error("find_sources_failed", error=str(e))
        raise HTTPException(status_code=500, detail=str(e))


@app.post(
    "/api/v1/agent/claims",
    response_model=ExtractClaimsResponse,
    tags=["AI Agent"],
    summary="Extract factual claims from article",
)
async def extract_claims(request: ExtractClaimsRequest):
    """
    Extract verifiable factual claims from article content.

    This endpoint:
    - Analyzes the article text
    - Identifies specific, verifiable claims
    - Returns a list of key claims for fact-checking
    """
    try:
        ai_service = get_ai_service()
        return await ai_service.extract_claims(
            headline=request.headline,
            content=request.content,
        )
    except Exception as e:
        logger.error("extract_claims_failed", error=str(e))
        raise HTTPException(status_code=500, detail=str(e))


# ==================== Classifier Endpoints ====================


@app.post(
    "/api/v1/classifier/classify",
    response_model=ClassificationResult,
    tags=["Classifier"],
    summary="Classify a news article",
)
async def classify_news(request: ClassifyNewsRequest):
    """
    Classify a news article as likely real, fake, or uncertain.

    This endpoint analyzes multiple factors:
    - Source credibility (25%)
    - Linguistic patterns (20%)
    - Cross-reference analysis (30%)
    - AI-based analysis (25%)

    Returns a verdict with confidence score and detailed factor analysis.
    """
    try:
        classifier = get_classifier_service()
        return await classifier.classify(request)
    except Exception as e:
        logger.error("classify_news_failed", error=str(e))
        raise HTTPException(status_code=500, detail=str(e))


# ==================== Main Entry Point ====================


def main():
    """Run the application with uvicorn."""
    import uvicorn

    settings = get_settings()
    uvicorn.run(
        "app.main:app",
        host=settings.api_host,
        port=settings.api_port,
        reload=True,
        log_level=settings.log_level.lower(),
    )


if __name__ == "__main__":
    main()
