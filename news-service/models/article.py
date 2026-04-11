from dataclasses import dataclass, field
from typing import Literal, Optional
from pydantic import BaseModel


class RawArticle(BaseModel):
    """Raw article fetched from a news source before parsing."""
    url: str
    source_name: str
    raw_html: Optional[str] = None
    raw_feed_entry: Optional[dict] = None


class ParsedArticle(BaseModel):
    """Article after parsing, ready to be saved to Supabase."""
    title: str
    url: str
    summary: Optional[str] = None
    source_name: str
    published_at: str  # ISO 8601 UTC string
    stock_symbols: list[str] = field(default_factory=list)
    status: str = "pending_analysis"


class PendingArticle(BaseModel):
    """Article fetched from Supabase with status=pending_analysis."""
    id: str
    title: str
    summary: Optional[str] = None
    status: str = "pending_analysis"


@dataclass
class SentimentResult:
    """Result of sentiment analysis for a single article."""
    article_id: str
    label: Literal["POSITIVE", "NEGATIVE", "NEUTRAL"]
    score: float  # [0.0, 1.0]
    status: Literal["analyzed", "analysis_failed"]
