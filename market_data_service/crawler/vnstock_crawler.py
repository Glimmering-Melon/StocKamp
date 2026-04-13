"""Yahoo Finance crawler for Vietnamese stock market data.

Uses yfinance which fetches from Yahoo Finance — no API key required,
no hard rate limits, supports batch downloads for speed.

VN stocks on Yahoo Finance use the '.VN' suffix (e.g. ACB.VN, VCB.VN).
"""

import logging
import time
from datetime import date
from decimal import Decimal, InvalidOperation

import yfinance as yf

from market_data_service.crawler.base import DataCrawler
from market_data_service.models.ohlcv import OHLCVRecord, StockSymbol

logger = logging.getLogger(__name__)

EXCHANGES = ["HOSE", "HNX", "UPCOM"]

# Map our timeframe codes → yfinance interval strings
TIMEFRAME_MAP = {
    "1D": "1d",
    "1W": "1wk",
    "1M": "1mo",
}

# How many symbols to download per yfinance batch call
BATCH_SIZE = 50
# Delay between batches to avoid Yahoo Finance rate limiting (seconds)
BATCH_DELAY = 2.0


class VnstockCrawler(DataCrawler):
    """Crawls OHLCV data from Yahoo Finance (no rate limits, batch support)."""

    def fetch_symbols(self) -> list[StockSymbol]:
        """Fetch all listed VN stocks using KBS listing + .VN suffix validation."""
        try:
            from vnstock import Vnstock  # type: ignore
            stock = Vnstock().stock(symbol="ACB", source="KBS")
            df = stock.listing.symbols_by_exchange()
            if df is None or df.empty:
                logger.warning("No symbols returned from KBS listing")
                return []

            symbols = []
            seen: set[str] = set()
            for _, row in df.iterrows():
                symbol_code = str(row.get("symbol", row.get("ticker", ""))).strip().upper()
                if not symbol_code or symbol_code in seen:
                    continue
                row_exchange = str(row.get("exchange", "")).strip().upper()
                if row_exchange not in EXCHANGES:
                    continue
                seen.add(symbol_code)
                name = str(row.get("organ_name", row.get("name", symbol_code))).strip()
                sector = row.get("icb_name2", row.get("sector"))
                sector = str(sector).strip() if sector and str(sector) != "nan" else None
                symbols.append(StockSymbol(
                    symbol=symbol_code,
                    name=name,
                    exchange=row_exchange,
                    sector=sector,
                    is_active=True,
                ))

            logger.info("Total unique symbols fetched: %d", len(symbols))
            return symbols

        except Exception as exc:
            logger.warning("Failed to fetch symbols: %s", exc)
            return []

    def fetch_ohlcv(
        self,
        symbol: str,
        timeframe: str,
        start_date: date,
        end_date: date,
    ) -> list[OHLCVRecord]:
        """Fetch OHLCV for a single symbol via yfinance."""
        if timeframe not in TIMEFRAME_MAP:
            raise ValueError(f"Unsupported timeframe '{timeframe}'. Must be one of {list(TIMEFRAME_MAP)}")

        ticker = symbol + ".VN"
        interval = TIMEFRAME_MAP[timeframe]
        try:
            df = yf.download(
                ticker,
                start=str(start_date),
                end=str(end_date),
                interval=interval,
                progress=False,
                auto_adjust=True,
            )
            if df is None or df.empty:
                return []
            return _parse_single_df(df, symbol, timeframe)
        except Exception as exc:
            logger.warning("Failed to fetch OHLCV for %s [%s]: %s", symbol, timeframe, exc)
            return []

    def fetch_ohlcv_bulk(
        self,
        symbols: list[StockSymbol],
        timeframes: list[str],
        start_date: date,
        end_date: date,
    ) -> tuple[list[OHLCVRecord], int, int]:
        """
        Fetch OHLCV for all symbols × timeframes using yfinance batch downloads.
        Returns (all_records, symbols_succeeded, symbols_failed).
        """
        all_records: list[OHLCVRecord] = []
        symbol_errors: set[str] = set()
        tickers = [s.symbol + ".VN" for s in symbols]

        logger.info(
            "Starting yfinance bulk crawl: %d symbols × %d timeframes",
            len(symbols), len(timeframes),
        )

        for timeframe in timeframes:
            interval = TIMEFRAME_MAP[timeframe]
            logger.info("Fetching timeframe %s ...", timeframe)

            # Process in batches to avoid yfinance memory issues with 1900+ symbols
            for batch_start in range(0, len(tickers), BATCH_SIZE):
                batch_tickers = tickers[batch_start: batch_start + BATCH_SIZE]
                batch_symbols = symbols[batch_start: batch_start + BATCH_SIZE]

                try:
                    df = yf.download(
                        batch_tickers,
                        start=str(start_date),
                        end=str(end_date),
                        interval=interval,
                        progress=False,
                        auto_adjust=True,
                        group_by="ticker",
                    )

                    if df is None or df.empty:
                        logger.warning("No data returned for batch %d-%d [%s]",
                                       batch_start, batch_start + len(batch_tickers), timeframe)
                        continue

                    for stock_sym in batch_symbols:
                        ticker = stock_sym.symbol + ".VN"
                        try:
                            # Multi-ticker df has MultiIndex columns
                            if hasattr(df.columns, "levels"):
                                sym_df = df[ticker] if ticker in df.columns.get_level_values(0) else None
                            else:
                                sym_df = df  # single ticker fallback

                            if sym_df is None or sym_df.empty:
                                continue

                            records = _parse_single_df(sym_df, stock_sym.symbol, timeframe)
                            all_records.extend(records)
                        except Exception as exc:
                            logger.warning("Parse error for %s [%s]: %s", stock_sym.symbol, timeframe, exc)
                            symbol_errors.add(stock_sym.symbol)

                    logger.info(
                        "Batch %d-%d [%s] done",
                        batch_start + 1, batch_start + len(batch_tickers), timeframe,
                    )
                    time.sleep(BATCH_DELAY)

                except Exception as exc:
                    logger.warning("Batch %d-%d [%s] failed: %s",
                                   batch_start, batch_start + len(batch_tickers), timeframe, exc)
                    for s in batch_symbols:
                        symbol_errors.add(s.symbol)

        symbols_failed = len(symbol_errors)
        symbols_succeeded = len(symbols) - symbols_failed
        logger.info(
            "Bulk crawl done: %d records, %d succeeded, %d failed",
            len(all_records), symbols_succeeded, symbols_failed,
        )
        return all_records, symbols_succeeded, symbols_failed


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _parse_single_df(df, symbol: str, timeframe: str) -> list[OHLCVRecord]:
    """Parse a single-symbol yfinance DataFrame into OHLCVRecord list."""
    records = []

    # yfinance may return MultiIndex columns even for single ticker
    if hasattr(df.columns, "levels"):
        # Flatten: pick first level values
        df = df.droplevel(1, axis=1) if df.columns.nlevels > 1 else df

    # Normalize column names to lowercase
    df.columns = [str(c).lower() for c in df.columns]

    for idx, row in df.iterrows():
        try:
            record_date = idx.date() if hasattr(idx, "date") else idx
            if record_date is None:
                continue
            open_ = _to_decimal(row.get("open", 0))
            high_ = _to_decimal(row.get("high", 0))
            low_ = _to_decimal(row.get("low", 0))
            close_ = _to_decimal(row.get("close", 0))
            # Skip rows where any price field is NaN
            if any(v is None for v in (open_, high_, low_, close_)):
                continue
            records.append(OHLCVRecord(
                symbol=symbol,
                date=record_date,
                timeframe=timeframe,
                open=open_,
                high=high_,
                low=low_,
                close=close_,
                volume=_to_int(row.get("volume", 0)),
            ))
        except Exception as e:
            logger.warning("Skipping malformed row for %s: %s", symbol, e)

    return records


def _to_decimal(value) -> Decimal | None:
    """Convert to Decimal, returning None for NaN/None/invalid values."""
    try:
        import math
        if value is None:
            return None
        f = float(value)
        if math.isnan(f) or math.isinf(f):
            return None
        return Decimal(str(f))
    except (InvalidOperation, TypeError, ValueError):
        return None


def _to_int(value) -> int:
    """Convert volume to int, treating NaN/None as 0."""
    try:
        import math
        if value is None or (isinstance(value, float) and math.isnan(value)):
            return 0
        return int(value)
    except (TypeError, ValueError):
        return 0
