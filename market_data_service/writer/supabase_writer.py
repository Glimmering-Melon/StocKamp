import json
import logging
import time
from datetime import datetime, timezone

from supabase import create_client, Client

from market_data_service.models.ohlcv import OHLCVRecord, RunStats, StockSymbol

logger = logging.getLogger(__name__)

BATCH_SIZE = 500
MAX_RETRIES = 3
BACKOFF_DELAYS = [1, 2, 4]  # seconds


def _with_retry(operation):
    """Execute operation with exponential backoff retry logic."""
    last_exc = None
    for attempt, delay in enumerate(BACKOFF_DELAYS):
        try:
            return operation()
        except Exception as exc:
            last_exc = exc
            if attempt < MAX_RETRIES - 1:
                logger.warning(
                    "Supabase call failed (attempt %d/%d), retrying in %ds: %s",
                    attempt + 1,
                    MAX_RETRIES,
                    delay,
                    exc,
                )
                time.sleep(delay)
            else:
                logger.error(
                    "Supabase call failed after %d retries: %s",
                    MAX_RETRIES,
                    exc,
                )
    raise last_exc


class SupabaseWriter:
    def __init__(self, supabase_url: str, supabase_key: str) -> None:
        self._client: Client = create_client(supabase_url, supabase_key)

    def upsert_symbols(self, symbols: list[StockSymbol]) -> int:
        """Upsert stock symbols in batches. Returns total count of upserted rows."""
        if not symbols:
            return 0

        total = 0
        for i in range(0, len(symbols), BATCH_SIZE):
            batch = symbols[i : i + BATCH_SIZE]
            rows = [
                {
                    "symbol": s.symbol,
                    "name": s.name,
                    "exchange": s.exchange,
                    "sector": s.sector,
                    "is_active": s.is_active,
                }
                for s in batch
            ]

            def _upsert(rows=rows):
                return (
                    self._client.table("stock_symbols")
                    .upsert(rows, on_conflict="symbol")
                    .execute()
                )

            _with_retry(_upsert)
            total += len(batch)
            logger.info(
                "Upserted %d rows into stock_symbols (batch %d–%d)",
                len(batch),
                i + 1,
                i + len(batch),
            )

        return total

    def upsert_ohlcv(self, records: list[OHLCVRecord]) -> int:
        """Upsert OHLCV records in batches. Returns total count of upserted rows."""
        if not records:
            return 0

        total = 0
        for i in range(0, len(records), BATCH_SIZE):
            batch = records[i : i + BATCH_SIZE]
            rows = [
                {
                    "symbol": r.symbol,
                    "date": str(r.date),
                    "timeframe": r.timeframe,
                    "open": float(r.open),
                    "high": float(r.high),
                    "low": float(r.low),
                    "close": float(r.close),
                    "volume": r.volume,
                }
                for r in batch
            ]

            def _upsert(rows=rows):
                return (
                    self._client.table("stock_prices")
                    .upsert(rows, on_conflict="symbol,date,timeframe")
                    .execute()
                )

            _with_retry(_upsert)
            total += len(batch)
            logger.info(
                "Upserted %d rows into stock_prices (batch %d–%d)",
                len(batch),
                i + 1,
                i + len(batch),
            )

        return total

    def update_crawl_metadata(self, run_stats: RunStats) -> None:
        """Upsert last_crawled_at and last_run_stats into crawl_metadata table."""
        now_iso = datetime.now(timezone.utc).isoformat()

        stats_summary = {
            "start_time": run_stats.start_time.isoformat(),
            "end_time": run_stats.end_time.isoformat(),
            "symbols_attempted": run_stats.symbols_attempted,
            "symbols_succeeded": run_stats.symbols_succeeded,
            "symbols_failed": run_stats.symbols_failed,
            "records_upserted": run_stats.records_upserted,
        }

        rows = [
            {"key": "last_crawled_at", "value": now_iso},
            {"key": "last_run_stats", "value": json.dumps(stats_summary)},
        ]

        def _upsert():
            return (
                self._client.table("crawl_metadata")
                .upsert(rows, on_conflict="key")
                .execute()
            )

        _with_retry(_upsert)
        logger.info("Updated crawl_metadata: last_crawled_at=%s", now_iso)
