"""Main entry point for the market data service scheduler.

Usage:
    python -m market_data_service.scheduler.main [--backfill] [--force]

Options:
    --backfill  Fetch 1 year of historical data (default: last 7 days)
    --force     Skip Vietnamese trading hours check
"""

import argparse
import logging
import sys
from datetime import date, datetime, timedelta, timezone

from market_data_service.config.logging_config import setup_logging

logger = logging.getLogger(__name__)

# Vietnamese timezone offset: UTC+7
ICT_OFFSET = timezone(timedelta(hours=7))

# Trading hours in ICT
TRADING_START = (9, 0)   # 09:00
TRADING_END = (15, 30)   # 15:30


def is_trading_hours(dt: datetime) -> bool:
    """Return True if dt falls within Vietnamese trading hours.

    Trading hours: Monday–Friday, 09:00–15:30 ICT (UTC+7).
    """
    ict_dt = dt.astimezone(ICT_OFFSET)
    # Weekday check: 0=Monday, 4=Friday, 5=Saturday, 6=Sunday
    if ict_dt.weekday() >= 5:
        return False
    t = (ict_dt.hour, ict_dt.minute)
    return TRADING_START <= t <= TRADING_END


def main(backfill: bool = False, force: bool = False) -> None:
    setup_logging()

    # Import settings here to trigger env var validation at startup
    from market_data_service.config import settings  # noqa: F401

    now = datetime.now(timezone.utc)

    if not force and not is_trading_hours(now):
        ict_now = now.astimezone(ICT_OFFSET)
        logger.info(
            "Outside Vietnamese trading hours (%s ICT). Skipping crawl. "
            "Use --force to override.",
            ict_now.strftime("%Y-%m-%d %H:%M %Z"),
        )
        sys.exit(0)

    from market_data_service.crawler.vnstock_crawler import VnstockCrawler
    from market_data_service.models.ohlcv import RunStats
    from market_data_service.writer.supabase_writer import SupabaseWriter

    start_time = datetime.now(timezone.utc)
    logger.info("Crawl started at %s", start_time.isoformat())

    today = date.today()
    if backfill:
        start_date = today - timedelta(days=365)
        logger.info("Backfill mode: fetching data from %s to %s", start_date, today)
    else:
        start_date = today - timedelta(days=7)
        logger.info("Incremental mode: fetching data from %s to %s", start_date, today)

    crawler = VnstockCrawler()
    writer = SupabaseWriter(
        supabase_url=settings.SUPABASE_URL,
        supabase_key=settings.SUPABASE_SERVICE_KEY,
    )

    # Fetch and upsert symbols
    symbols = crawler.fetch_symbols()
    writer.upsert_symbols(symbols)
    logger.info("Upserted %d symbols", len(symbols))

    timeframes = ["1D", "1W", "1M"]

    # Concurrent bulk fetch — much faster than sequential per-symbol loop
    all_records, symbols_succeeded, symbols_failed = crawler.fetch_ohlcv_bulk(
        symbols=symbols,
        timeframes=timeframes,
        start_date=start_date,
        end_date=today,
    )
    symbols_attempted = len(symbols)

    # Upsert all records in batches
    records_upserted = 0
    if all_records:
        records_upserted = writer.upsert_ohlcv(all_records)
        logger.info("Upserted %d total OHLCV records", records_upserted)

    end_time = datetime.now(timezone.utc)

    run_stats = RunStats(
        start_time=start_time,
        end_time=end_time,
        symbols_attempted=symbols_attempted,
        symbols_succeeded=symbols_succeeded,
        symbols_failed=symbols_failed,
        records_upserted=records_upserted,
    )

    writer.update_crawl_metadata(run_stats)

    duration = (end_time - start_time).total_seconds()
    logger.info(
        "Crawl finished. start=%s end=%s duration=%.1fs "
        "symbols_attempted=%d symbols_succeeded=%d symbols_failed=%d records_upserted=%d",
        start_time.isoformat(),
        end_time.isoformat(),
        duration,
        symbols_attempted,
        symbols_succeeded,
        symbols_failed,
        records_upserted,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Market data service scheduler")
    parser.add_argument(
        "--backfill",
        action="store_true",
        help="Fetch 1 year of historical data instead of the last 7 days",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Skip Vietnamese trading hours check",
    )
    args = parser.parse_args()
    main(backfill=args.backfill, force=args.force)
