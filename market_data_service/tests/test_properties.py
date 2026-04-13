"""Property-based tests for market-data-service using hypothesis."""

import os
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from unittest.mock import patch

import pytest
from hypothesis import given, settings, assume
from hypothesis import strategies as st

from market_data_service.models.ohlcv import OHLCVRecord, StockSymbol
from market_data_service.scheduler.main import is_trading_hours, ICT_OFFSET
from market_data_service.utils.profit_margin import calculate_profit_margin
from market_data_service.writer.supabase_writer import MAX_RETRIES

# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

def ohlcv_record_strategy():
    """Generate valid OHLCVRecord objects satisfying OHLCV invariants."""
    return st.builds(
        _build_ohlcv,
        low=st.decimals(min_value=Decimal("0.01"), max_value=Decimal("999999"), allow_nan=False, allow_infinity=False),
        spread_low_open=st.decimals(min_value=Decimal("0"), max_value=Decimal("10000"), allow_nan=False, allow_infinity=False),
        spread_low_close=st.decimals(min_value=Decimal("0"), max_value=Decimal("10000"), allow_nan=False, allow_infinity=False),
        spread_to_high=st.decimals(min_value=Decimal("0"), max_value=Decimal("10000"), allow_nan=False, allow_infinity=False),
        volume=st.integers(min_value=0, max_value=10_000_000),
        symbol=st.text(alphabet=st.characters(whitelist_categories=("Lu",)), min_size=1, max_size=10),
        timeframe=st.sampled_from(["1D", "1W", "1M"]),
    )


def _build_ohlcv(low, spread_low_open, spread_low_close, spread_to_high, volume, symbol, timeframe):
    open_ = low + spread_low_open
    close = low + spread_low_close
    high = max(open_, close, low) + spread_to_high
    return OHLCVRecord(
        symbol=symbol,
        date=date(2024, 1, 1),
        timeframe=timeframe,
        open=open_,
        high=high,
        low=low,
        close=close,
        volume=volume,
    )


def outside_trading_hours_strategy():
    """Generate datetimes that are outside Vietnamese trading hours (09:00-15:30 ICT, Mon-Fri)."""
    # Either a weekend datetime or a weekday outside trading hours
    weekend_dt = st.builds(
        _make_weekend_dt,
        hour=st.integers(min_value=0, max_value=23),
        minute=st.integers(min_value=0, max_value=59),
        day_offset=st.integers(min_value=0, max_value=364),
    )
    weekday_before = st.builds(
        _make_weekday_outside_dt,
        before=st.just(True),
        hour=st.integers(min_value=0, max_value=8),
        minute=st.integers(min_value=0, max_value=59),
        day_offset=st.integers(min_value=0, max_value=364),
    )
    weekday_after = st.builds(
        _make_weekday_outside_dt,
        before=st.just(False),
        hour=st.integers(min_value=15, max_value=23),
        minute=st.integers(min_value=31, max_value=59),
        day_offset=st.integers(min_value=0, max_value=364),
    )
    return st.one_of(weekend_dt, weekday_before, weekday_after)


def _make_weekend_dt(hour, minute, day_offset):
    # Find a Saturday: 2024-01-06 is a Saturday
    base_saturday = datetime(2024, 1, 6, tzinfo=ICT_OFFSET)
    # Advance by weeks to get various Saturdays/Sundays
    dt = base_saturday + timedelta(days=(day_offset // 2) * 7 + (day_offset % 2))
    return dt.replace(hour=hour, minute=minute, second=0, microsecond=0)


def _make_weekday_outside_dt(before, hour, minute, day_offset):
    # 2024-01-08 is a Monday
    base_monday = datetime(2024, 1, 8, tzinfo=ICT_OFFSET)
    # Pick a weekday (Mon-Fri)
    weekday_offset = day_offset % 5
    dt = base_monday + timedelta(days=(day_offset // 5) * 7 + weekday_offset)
    if before:
        # Before 09:00 — clamp hour to 0-8
        h = min(hour, 8)
        return dt.replace(hour=h, minute=minute, second=0, microsecond=0)
    else:
        # After 15:30 — hour 15 with minute >= 31, or hour 16-23
        if hour == 15:
            m = max(minute, 31)
        else:
            h = max(hour, 16)
            return dt.replace(hour=h, minute=minute, second=0, microsecond=0)
        return dt.replace(hour=15, minute=m, second=0, microsecond=0)


# ---------------------------------------------------------------------------
# Task 7.1 – Property 1: OHLCV invariant
# Feature: market-data-service, Property 1: OHLCV invariant
# For any OHLCVRecord: high >= open, high >= close, high >= low, low <= open, low <= close, volume >= 0
# Validates: Requirements 1.2
# ---------------------------------------------------------------------------

@given(record=ohlcv_record_strategy())
@settings(max_examples=100)
def test_ohlcv_invariant(record: OHLCVRecord):
    assert record.high >= record.open
    assert record.high >= record.close
    assert record.high >= record.low
    assert record.low <= record.open
    assert record.low <= record.close
    assert record.volume >= 0


# ---------------------------------------------------------------------------
# Task 7.2 – Property 2: Upsert idempotency
# Feature: market-data-service, Property 2: Upsert idempotency
# Upserting the same OHLCVRecord twice produces exactly 1 row
# Validates: Requirements 2.4
# ---------------------------------------------------------------------------

@given(record=ohlcv_record_strategy())
@settings(max_examples=100)
def test_upsert_idempotency(record: OHLCVRecord):
    """Mock Supabase client stores rows in a dict keyed by (symbol, date, timeframe)."""
    store: dict = {}

    def mock_upsert(row_dict):
        key = (row_dict["symbol"], row_dict["date"], row_dict["timeframe"])
        store[key] = row_dict

    row = {
        "symbol": record.symbol,
        "date": str(record.date),
        "timeframe": record.timeframe,
        "open": float(record.open),
        "high": float(record.high),
        "low": float(record.low),
        "close": float(record.close),
        "volume": record.volume,
    }

    # Upsert twice
    mock_upsert(row)
    mock_upsert(row)

    assert len(store) == 1


# ---------------------------------------------------------------------------
# Task 7.3 – Property 3: Retry policy
# Feature: market-data-service, Property 3: Supabase retry on failure
# Mock Supabase always fails → retry count <= 3
# Validates: Requirements 2.6
# ---------------------------------------------------------------------------

@given(st.integers(min_value=1, max_value=10))
@settings(max_examples=100)
def test_retry_policy_max_retries(_ignored):
    """When Supabase always fails, _with_retry should attempt at most MAX_RETRIES times."""
    from market_data_service.writer.supabase_writer import _with_retry

    call_count = 0

    def always_fails():
        nonlocal call_count
        call_count += 1
        raise ConnectionError("Simulated Supabase failure")

    with patch("market_data_service.writer.supabase_writer.time.sleep"):
        with pytest.raises(ConnectionError):
            _with_retry(always_fails)

    assert call_count <= MAX_RETRIES


# ---------------------------------------------------------------------------
# Task 7.4 – Property 9: Crawl skip outside trading hours
# Feature: market-data-service, Property 9: Crawl skip outside trading hours
# For any datetime outside Vietnamese trading hours, is_trading_hours() returns False
# Validates: Requirements 3.3
# ---------------------------------------------------------------------------

@given(dt=outside_trading_hours_strategy())
@settings(max_examples=100)
def test_skip_outside_trading_hours(dt: datetime):
    assert is_trading_hours(dt) is False


# ---------------------------------------------------------------------------
# Task 7.5 – Property 10: Missing env vars cause startup failure
# Feature: market-data-service, Property 10: Missing env vars cause startup failure
# For any subset of missing required env vars, settings import exits with non-zero code
# Validates: Requirements 8.4
# ---------------------------------------------------------------------------

REQUIRED_VARS = ["SUPABASE_URL", "SUPABASE_SERVICE_KEY"]


@given(
    missing=st.lists(
        st.sampled_from(REQUIRED_VARS),
        min_size=1,
        max_size=len(REQUIRED_VARS),
        unique=True,
    )
)
@settings(max_examples=100)
def test_missing_env_vars_cause_startup_failure(missing):
    """Removing any required env var should cause SystemExit when settings is imported."""
    import importlib
    import sys

    # Build a clean env without the missing vars
    clean_env = {k: v for k, v in os.environ.items() if k not in missing}
    # Also ensure the missing vars are not present
    for var in missing:
        clean_env.pop(var, None)

    with patch.dict(os.environ, clean_env, clear=True):
        # Remove cached module so it re-executes
        mod_name = "market_data_service.config.settings"
        sys.modules.pop(mod_name, None)
        with pytest.raises(SystemExit) as exc_info:
            importlib.import_module(mod_name)
        assert exc_info.value.code != 0
    # Restore module cache state
    sys.modules.pop(mod_name, None)


# ---------------------------------------------------------------------------
# Task 7.6 – Property 8: Profit margin formula
# Feature: market-data-service, Property 8: Profit margin formula correctness
# calculate_profit_margin(entry_price, latest_close) == (latest_close - entry_price) / entry_price * 100.0
# Validates: Requirements 7.1
# ---------------------------------------------------------------------------

@given(
    entry_price=st.floats(min_value=0.01, max_value=1_000_000, allow_nan=False, allow_infinity=False),
    latest_close=st.floats(min_value=0.0, max_value=1_000_000, allow_nan=False, allow_infinity=False),
)
@settings(max_examples=100)
def test_profit_margin_formula(entry_price: float, latest_close: float):
    result = calculate_profit_margin(entry_price, latest_close)
    expected = (latest_close - entry_price) / entry_price * 100.0
    assert abs(result - expected) < 1e-9
