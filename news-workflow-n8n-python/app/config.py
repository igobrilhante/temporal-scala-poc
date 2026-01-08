"""Application configuration."""

from functools import lru_cache
from typing import Optional

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""

    # API Configuration
    api_host: str = "0.0.0.0"
    api_port: int = 8000

    # OpenAI Configuration
    openai_api_key: Optional[str] = None
    openai_model: str = "gpt-4"

    # Logging
    log_level: str = "INFO"

    # Scraping Configuration
    request_timeout: int = 30
    max_retries: int = 3

    # Source Credibility - Known reliable sources
    reliable_sources: list[str] = [
        "bbc.co.uk", "bbc.com",
        "reuters.com",
        "apnews.com",
        "nytimes.com",
        "theguardian.com",
        "washingtonpost.com",
        "npr.org",
        "bloomberg.com",
        "economist.com",
        "ft.com",
        "wsj.com",
    ]

    # Unreliable/Satire sources
    unreliable_sources: list[str] = [
        "theonion.com",
        "babylonbee.com",
        "clickhole.com",
    ]

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


@lru_cache
def get_settings() -> Settings:
    """Get cached settings instance."""
    return Settings()
