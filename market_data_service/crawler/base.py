from abc import ABC, abstractmethod
from datetime import date

from market_data_service.models.ohlcv import OHLCVRecord, StockSymbol


class DataCrawler(ABC):
    @abstractmethod
    def fetch_symbols(self) -> list[StockSymbol]: ...

    @abstractmethod
    def fetch_ohlcv(
        self,
        symbol: str,
        timeframe: str,
        start_date: date,
        end_date: date,
    ) -> list[OHLCVRecord]: ...
