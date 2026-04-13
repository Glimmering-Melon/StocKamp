from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal


@dataclass
class StockSymbol:
    symbol: str
    name: str
    exchange: str  # 'HOSE' | 'HNX' | 'UPCOM'
    sector: str | None
    is_active: bool = True


@dataclass
class OHLCVRecord:
    symbol: str
    date: date
    timeframe: str  # '1D' | '1W' | '1M'
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    volume: int


@dataclass
class RunStats:
    start_time: datetime
    end_time: datetime
    symbols_attempted: int
    symbols_succeeded: int
    symbols_failed: int
    records_upserted: int
