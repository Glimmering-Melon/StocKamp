"""AI_Processor worker — runs FinBERT/PhoBERT sentiment analysis on pending articles."""

import logging
from typing import Optional

from models.article import PendingArticle, SentimentResult

logger = logging.getLogger(__name__)

# Graceful import: transformers/torch may not be installed
try:
    from transformers import pipeline as hf_pipeline
    _TRANSFORMERS_AVAILABLE = True
except ImportError:
    _TRANSFORMERS_AVAILABLE = False
    logger.warning(
        "transformers library is not installed. AI_Processor will skip sentiment analysis. "
        "Install with: pip install transformers torch"
    )

# FinBERT label mapping (lowercase → uppercase canonical)
_LABEL_MAP: dict[str, str] = {
    "positive": "POSITIVE",
    "negative": "NEGATIVE",
    "neutral": "NEUTRAL",
    # PhoBERT / other models may use different casing — normalise all
    "POSITIVE": "POSITIVE",
    "NEGATIVE": "NEGATIVE",
    "NEUTRAL": "NEUTRAL",
    # Some models use LABEL_0/1/2 — treat unknown as NEUTRAL
    # Map cho các model trả về dạng LABEL_X
    # (Lưu ý: Bạn cần check lại config của model cụ thể đang dùng để map 0,1,2 cho đúng)
    "LABEL_0": "NEGATIVE",  # Thường gặp ở mrm8488/distilroberta...
    "LABEL_1": "NEUTRAL",
    "LABEL_2": "POSITIVE",

    # Map dự phòng cho PhoBERT hoặc các model có nhãn rút gọn
    "POS": "POSITIVE",
    "NEG": "NEGATIVE",
    "NEU": "NEUTRAL",
}

_BATCH_SIZE = 32
_FETCH_LIMIT = 500  # tăng từ 100 lên 500 bài mỗi lần xử lý


class AIProcessor:
    """Fetches pending articles from Supabase, runs sentiment analysis, and writes results back."""

    def __init__(
        self,
        supabase_url: str,
        supabase_key: str,
        model_name: str = "lxyuan/distilbert-base-multilingual-cased-sentiments-student",
    ) -> None:
        self._supabase_url = supabase_url
        self._supabase_key = supabase_key
        self._model_name = model_name
        self._pipe = None  # loaded lazily / at startup

        if _TRANSFORMERS_AVAILABLE:
            self._load_model()
        else:
            logger.warning("Skipping model load — transformers not available.")

    # ------------------------------------------------------------------
    # Model loading
    # ------------------------------------------------------------------

    def _load_model(self) -> None:
        """Load the HuggingFace sentiment-analysis pipeline."""
        try:
            logger.info("Loading sentiment model: %s", self._model_name)
            self._pipe = hf_pipeline(
                "sentiment-analysis",
                model=self._model_name,
                tokenizer=self._model_name,
                truncation=True,
                max_length=512,
            )
            logger.info("Model loaded successfully.")
        except Exception:
            logger.exception("Failed to load model '%s'.", self._model_name)
            self._pipe = None

    # ------------------------------------------------------------------
    # Supabase helpers
    # ------------------------------------------------------------------

    def _get_client(self):
        """Return a Supabase client (imported lazily to avoid hard dependency at module level)."""
        from supabase import create_client  # type: ignore
        return create_client(self._supabase_url, self._supabase_key)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def run(self) -> dict:
        """Main entry point called by the APScheduler job.

        Returns a dict with processing statistics.
        """
        if not _TRANSFORMERS_AVAILABLE or self._pipe is None:
            logger.warning("AI_Processor skipped: model not available.")
            return {"skipped": True, "reason": "model_not_available"}

        pending = await self.fetch_pending(limit=_FETCH_LIMIT)
        if not pending:
            logger.info("No pending articles to process.")
            return {"processed": 0, "failed": 0}

        logger.info("Processing %d pending articles.", len(pending))
        results = self.analyze_batch(pending)
        await self.update_articles(results)

        processed = sum(1 for r in results if r.status == "analyzed")
        failed = sum(1 for r in results if r.status == "analysis_failed")
        logger.info("AI_Processor done: %d analyzed, %d failed.", processed, failed)
        return {"processed": processed, "failed": failed}

    async def fetch_pending(self, limit: int = _FETCH_LIMIT) -> list[PendingArticle]:
        """Fetch up to *limit* articles with status='pending_analysis' from Supabase."""
        try:
            client = self._get_client()
            response = (
                client.table("news_articles")
                .select("id, title, summary, status")
                .eq("status", "pending_analysis")
                .limit(limit)
                .execute()
            )
            rows = response.data or []
            return [PendingArticle(**row) for row in rows]
        except Exception:
            logger.exception("fetch_pending failed.")
            return []

    def analyze_batch(self, articles: list[PendingArticle]) -> list[SentimentResult]:
        """Run sentiment analysis in batches of *_BATCH_SIZE*.

        Each article's text is the concatenation of title and summary.
        Labels are mapped to POSITIVE / NEGATIVE / NEUTRAL.
        Per-article exceptions are caught and result in analysis_failed status.
        """
        results: list[SentimentResult] = []

        for i in range(0, len(articles), _BATCH_SIZE):
            batch = articles[i : i + _BATCH_SIZE]
            texts = [_build_text(a) for a in batch]

            try:
                raw_outputs = self._pipe(texts, batch_size=_BATCH_SIZE)  # type: ignore[misc]
            except Exception:
                logger.exception("Model inference failed for batch starting at index %d.", i)
                # Mark every article in this batch as failed
                for article in batch:
                    results.append(
                        SentimentResult(
                            article_id=article.id,
                            label="NEUTRAL",  # placeholder — won't be written
                            score=0.0,
                            status="analysis_failed",
                        )
                    )
                continue

            for article, output in zip(batch, raw_outputs):
                try:
                    raw_label: str = output.get("label", "")
                    score: float = float(output.get("score", 0.0))
                    label = _LABEL_MAP.get(raw_label, "NEUTRAL")
                    results.append(
                        SentimentResult(
                            article_id=article.id,
                            label=label,  # type: ignore[arg-type]
                            score=score,
                            status="analyzed",
                        )
                    )
                except Exception:
                    logger.exception(
                        "Failed to parse model output for article %s.", article.id
                    )
                    results.append(
                        SentimentResult(
                            article_id=article.id,
                            label="NEUTRAL",
                            score=0.0,
                            status="analysis_failed",
                        )
                    )

        return results

    async def update_articles(self, results: list[SentimentResult]) -> None:
        """Write sentiment_label, sentiment_score, and status back to Supabase."""
        if not results:
            return

        try:
            client = self._get_client()
        except Exception:
            logger.exception("Could not create Supabase client for update_articles.")
            return

        for result in results:
            try:
                payload: dict = {"status": result.status}
                if result.status == "analyzed":
                    payload["sentiment_label"] = result.label
                    payload["sentiment_score"] = result.score

                client.table("news_articles").update(payload).eq(
                    "id", result.article_id
                ).execute()
            except Exception:
                logger.exception(
                    "Failed to update article %s in Supabase.", result.article_id
                )


# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------

def _build_text(article: PendingArticle) -> str:
    """Concatenate title and summary for model input."""
    parts = [article.title]
    if article.summary:
        parts.append(article.summary)
    return " ".join(parts)
