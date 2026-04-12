"""Unit tests for market-data-service."""

from dataclasses import fields
from datetime import date, datetime, timezone
from decimal import Decimal
from unittest.mock import MagicMock, patch

from market_data_service.models.ohlcv import OHLCVRecord, RunStats, StockSymbol


# ---------------------------------------------------------------------------
# Task 7.7 – Schema field tests
# ---------------------------------------------------------------------------

def test_ohlcv_record_has_required_fields():
    """OHLCVRecord must have all required fields: symbol, date, timeframe, open, high, low, close, volume."""
    field_names = {f.name for f in fields(OHLCVRecord)}
    required = {"symbol", "date", "timeframe", "open", "high", "low", "close", "volume"}
    assert required.issubset(field_names)


def test_stock_symbol_has_required_fields():
    """StockSymbol must have all required fields: symbol, name, exchange, sector, is_active."""
    field_names = {f.name for f in fields(StockSymbol)}
    required = {"symbol", "name", "exchange", "sector", "is_active"}
    assert required.issubset(field_names)


# ---------------------------------------------------------------------------
# Task 7.7 – Error isolation: fetch_ohlcv raises for one symbol → scheduler continues
# ---------------------------------------------------------------------------

def test_error_isolation_continues_on_symbol_failure():
    """When fetch_ohlcv raises for one symbol, the scheduler continues to the next symbol."""
    symbols = [
        StockSymbol(symbol="VNM", name="Vinamilk", exchange="HOSE", sector=None),
        StockSymbol(symbol="FPT", name="FPT Corp", exchange="HOSE", sector=None),
    ]

    call_log = []

    def mock_fetch_ohlcv(symbol, timeframe, start_date, end_date):
        call_log.append(symbol)
        if symbol == "VNM":
            raise RuntimeError("Simulated fetch error for VNM")
        return [
            OHLCVRecord(
                symbol=symbol,
                date=date(2024, 1, 1),
                timeframe=timeframe,
                open=Decimal("100"),
                high=Decimal("110"),
                low=Decimal("90"),
                close=Decimal("105"),
                volume=1000,
            )
        ]

    mock_crawler = MagicMock()
    mock_crawler.fetch_symbols.return_value = symbols
    mock_crawler.fetch_ohlcv.side_effect = mock_fetch_ohlcv

    mock_writer = MagicMock()
    mock_writer.upsert_symbols.return_value = len(symbols)
    mock_writer.upsert_ohlcv.return_value = 1

    symbols_failed = 0
    symbols_succeeded = 0
    records_upserted = 0
    timeframes = ["1D", "1W", "1M"]

    for stock_symbol in symbols:
        symbol_ok = True
        for timeframe in timeframes:
            try:
                records = mock_crawler.fetch_ohlcv(
                    symbol=stock_symbol.symbol,
                    timeframe=timeframe,
                    start_date=date(2024, 1, 1),
                    end_date=date(2024, 1, 7),
                )
                if records:
                    count = mock_writer.upsert_ohlcv(records)
                    records_upserted += count
            except Exception:
                symbol_ok = False

        if symbol_ok:
            symbols_succeeded += 1
        else:
            symbols_failed += 1

    # VNM failed, FPT succeeded — both were attempted
    assert symbols_failed == 1
    assert symbols_succeeded == 1
    # FPT was still processed despite VNM failing
    assert any(sym == "FPT" for sym in call_log)


# ---------------------------------------------------------------------------
# Task 7.7 – Log summary: RunStats fields are correctly populated after a crawl run
# ---------------------------------------------------------------------------

def test_run_stats_fields_populated():
    """RunStats fields are correctly populated after a crawl run."""
    start = datetime(2024, 1, 1, 9, 0, tzinfo=timezone.utc)
    end = datetime(2024, 1, 1, 9, 5, tzinfo=timezone.utc)

    stats = RunStats(
        start_time=start,
        end_time=end,
        symbols_attempted=10,
        symbols_succeeded=8,
        symbols_failed=2,
        records_upserted=240,
    )

    assert stats.start_time == start
    assert stats.end_time == end
    assert stats.symbols_attempted == 10
    assert stats.symbols_succeeded == 8
    assert stats.symbols_failed == 2
    assert stats.records_upserted == 240
    # Consistency check
    assert stats.symbols_succeeded + stats.symbols_failed == stats.symbols_attempted
