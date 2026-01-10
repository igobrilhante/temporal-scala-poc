"""News Classifier Service - Analyzes and classifies news articles."""

import re
import time
from typing import Optional

import structlog
from openai import AsyncOpenAI

from ..config import get_settings
from ..models import (
    AnalysisFactor,
    ClassificationResult,
    ClassifyNewsRequest,
    NewsVerdict,
)

logger = structlog.get_logger()


class NewsClassifierService:
    """Service for classifying news as real, fake, or uncertain."""

    # Sensationalist/clickbait patterns
    CLICKBAIT_PATTERNS = [
        r"you won't believe",
        r"shocking",
        r"breaking:",
        r"urgent:",
        r"!!+",
        r"\?\?+",
        r"this is huge",
        r"spread this",
        r"share before",
        r"they don't want you to know",
        r"exposed",
        r"bombshell",
        r"jaw-dropping",
        r"mind-blowing",
    ]

    # Emotional/manipulative language patterns
    EMOTIONAL_PATTERNS = [
        r"\b(evil|horrific|terrifying|outrageous|disgusting)\b",
        r"\b(always|never|everyone|nobody|all)\b.*\b(must|should|will)\b",
        r"wake up",
        r"sheeple",
        r"mainstream media",
        r"fake news",
    ]

    def __init__(self):
        self.settings = get_settings()
        self.openai_client: Optional[AsyncOpenAI] = None
        if self.settings.openai_api_key:
            self.openai_client = AsyncOpenAI(api_key=self.settings.openai_api_key)

        self.reliable_sources = set(self.settings.reliable_sources)
        self.unreliable_sources = set(self.settings.unreliable_sources)

    async def close(self):
        """Close clients."""
        if self.openai_client:
            await self.openai_client.close()

    def _analyze_source_credibility(self, domain: str) -> tuple[float, str]:
        """Analyze source credibility based on domain."""
        domain_lower = domain.lower()

        if any(reliable in domain_lower for reliable in self.reliable_sources):
            return 0.9, "mainstream_reliable"

        if any(unreliable in domain_lower for unreliable in self.unreliable_sources):
            return 0.1, "known_satire_or_unreliable"

        # Check for suspicious TLDs
        suspicious_tlds = [".ru", ".xyz", ".info", ".biz"]
        if any(domain_lower.endswith(tld) for tld in suspicious_tlds):
            return 0.3, "suspicious_tld"

        return 0.5, "unknown_source"

    def _analyze_linguistic_patterns(
        self, headline: str, content: str
    ) -> tuple[float, str]:
        """Analyze linguistic patterns for credibility signals."""
        text = f"{headline} {content}".lower()
        issues = []

        # Check for clickbait patterns
        clickbait_count = sum(
            1 for pattern in self.CLICKBAIT_PATTERNS if re.search(pattern, text, re.I)
        )
        if clickbait_count > 0:
            issues.append(f"{clickbait_count} clickbait patterns")

        # Check for emotional manipulation
        emotional_count = sum(
            1 for pattern in self.EMOTIONAL_PATTERNS if re.search(pattern, text, re.I)
        )
        if emotional_count > 0:
            issues.append(f"{emotional_count} emotional patterns")

        # Check for excessive punctuation in headline
        if re.search(r"[!?]{2,}", headline):
            issues.append("excessive punctuation")

        # Check for ALL CAPS words (more than 2)
        caps_words = len(re.findall(r"\b[A-Z]{3,}\b", headline))
        if caps_words > 2:
            issues.append(f"{caps_words} ALL CAPS words")

        # Calculate score
        issue_count = len(issues)
        if issue_count == 0:
            return 0.9, "no_concerning_patterns"
        elif issue_count <= 2:
            return 0.6, f"minor_concerns: {', '.join(issues)}"
        else:
            return 0.3, f"major_concerns: {', '.join(issues)}"

    def _analyze_cross_references(
        self, alternative_sources: list[dict]
    ) -> tuple[float, int, int, str]:
        """Analyze cross-references from alternative sources."""
        if not alternative_sources:
            return 0.5, 0, 0, "no_alternative_sources_found"

        supporting = sum(1 for s in alternative_sources if s.get("supports_original", True))
        contradicting = len(alternative_sources) - supporting

        if supporting > 2 and contradicting == 0:
            return 0.9, supporting, contradicting, "multiple_supporting_sources"
        elif supporting > contradicting:
            return 0.7, supporting, contradicting, "mostly_supported"
        elif contradicting > supporting:
            return 0.3, supporting, contradicting, "more_contradictions"
        else:
            return 0.5, supporting, contradicting, "mixed_signals"

    async def _ai_classification(
        self, headline: str, content: str, claims: list[str]
    ) -> Optional[tuple[NewsVerdict, float, str]]:
        """Use AI to classify the news article."""
        if not self.openai_client:
            return None

        try:
            system_prompt = """You are an expert fact-checker and misinformation analyst.
            Analyze news articles and classify them as:
            - LIKELY_REAL: Multiple credible indicators, factual language, verifiable claims
            - LIKELY_FAKE: Red flags like sensationalism, unverified claims, known false patterns
            - UNCERTAIN: Mixed signals, needs more investigation

            Respond with JSON: {"verdict": "VERDICT", "confidence": 0.0-1.0, "reasoning": "explanation"}"""

            prompt = f"""Analyze this news article:

            Headline: {headline}

            Content excerpt: {content[:2000]}

            Key claims identified:
            {chr(10).join(f'- {claim}' for claim in claims[:5])}

            Classify this article and explain your reasoning."""

            response = await self.openai_client.chat.completions.create(
                model=self.settings.openai_model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": prompt},
                ],
                temperature=0.2,
                max_tokens=500,
            )

            result_text = response.choices[0].message.content
            # Parse JSON response
            import json

            # Find JSON in response
            start = result_text.find("{")
            end = result_text.rfind("}") + 1
            if start >= 0 and end > start:
                result = json.loads(result_text[start:end])
                verdict = NewsVerdict(result["verdict"])
                confidence = float(result["confidence"])
                reasoning = result.get("reasoning", "AI analysis complete")
                return verdict, confidence, reasoning

        except Exception as e:
            logger.error("ai_classification_failed", error=str(e))

        return None

    async def classify(self, request: ClassifyNewsRequest) -> ClassificationResult:
        """Classify a news article."""
        start_time = time.time()
        logger.info("classifying_news", headline=request.headline[:50])

        factors = []

        # Factor 1: Source Credibility (25%)
        source_score, source_detail = self._analyze_source_credibility(
            request.source_domain
        )
        factors.append(
            AnalysisFactor(
                name="source_credibility",
                score=source_score,
                weight=0.25,
                details=source_detail,
            )
        )

        # Factor 2: Linguistic Analysis (20%)
        linguistic_score, linguistic_detail = self._analyze_linguistic_patterns(
            request.headline, request.content
        )
        factors.append(
            AnalysisFactor(
                name="linguistic_analysis",
                score=linguistic_score,
                weight=0.20,
                details=linguistic_detail,
            )
        )

        # Factor 3: Cross-Reference (30%)
        cross_ref_score, supporting, contradicting, cross_ref_detail = (
            self._analyze_cross_references(request.alternative_sources)
        )
        factors.append(
            AnalysisFactor(
                name="cross_reference",
                score=cross_ref_score,
                weight=0.30,
                details=cross_ref_detail,
            )
        )

        # Factor 4: AI Analysis (25%)
        ai_result = await self._ai_classification(
            request.headline, request.content, request.claims
        )

        if ai_result:
            ai_verdict, ai_confidence, ai_reasoning = ai_result
            ai_score = ai_confidence if ai_verdict == NewsVerdict.LIKELY_REAL else (1 - ai_confidence)
            factors.append(
                AnalysisFactor(
                    name="ai_analysis",
                    score=ai_score,
                    weight=0.25,
                    details=ai_reasoning,
                )
            )
        else:
            # Without AI, redistribute weights
            for factor in factors:
                factor.weight = factor.weight / 0.75

        # Calculate weighted score
        total_score = sum(f.score * f.weight for f in factors)

        # Determine verdict
        if total_score >= 0.7:
            verdict = NewsVerdict.LIKELY_REAL
        elif total_score <= 0.4:
            verdict = NewsVerdict.LIKELY_FAKE
        else:
            verdict = NewsVerdict.UNCERTAIN

        # Generate summary
        summary_parts = []
        if source_score >= 0.8:
            summary_parts.append("from reliable source")
        elif source_score <= 0.3:
            summary_parts.append("from questionable source")

        if linguistic_score <= 0.5:
            summary_parts.append("contains concerning language patterns")

        if supporting > 0:
            summary_parts.append(f"{supporting} supporting sources")
        if contradicting > 0:
            summary_parts.append(f"{contradicting} contradicting sources")

        summary = "; ".join(summary_parts) if summary_parts else "Analysis complete"

        classification_time_ms = int((time.time() - start_time) * 1000)

        logger.info(
            "classification_complete",
            headline=request.headline[:50],
            verdict=verdict.value,
            confidence=round(total_score, 2),
            time_ms=classification_time_ms,
        )

        return ClassificationResult(
            headline=request.headline,
            verdict=verdict,
            confidence=round(total_score, 2),
            factors=factors,
            supporting_sources=supporting,
            contradicting_sources=contradicting,
            summary=summary,
            classification_time_ms=classification_time_ms,
        )


# Singleton instance
_classifier_service: Optional[NewsClassifierService] = None


def get_classifier_service() -> NewsClassifierService:
    """Get or create the classifier service instance."""
    global _classifier_service
    if _classifier_service is None:
        _classifier_service = NewsClassifierService()
    return _classifier_service
