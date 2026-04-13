-- =============================================================================
-- Migration 001: Initial Schema
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Task 2.1: stock_symbols table
-- Stores the list of tradable stock symbols across HOSE, HNX, and UPCOM.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_symbols (
    symbol      TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    exchange    TEXT NOT NULL,  -- 'HOSE', 'HNX', 'UPCOM'
    sector      TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT true
);

-- -----------------------------------------------------------------------------
-- Task 2.2: stock_prices table
-- Stores OHLCV price data keyed by (symbol, date, timeframe).
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_prices (
    symbol      TEXT NOT NULL,
    date        DATE NOT NULL,
    timeframe   TEXT NOT NULL,  -- '1D', '1W', '1M'
    open        NUMERIC NOT NULL,
    high        NUMERIC NOT NULL,
    low         NUMERIC NOT NULL,
    close       NUMERIC NOT NULL,
    volume      BIGINT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, date, timeframe)
);

-- -----------------------------------------------------------------------------
-- Task 2.3: crawl_metadata table
-- Stores key/value metadata about the most recent crawl run.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS crawl_metadata (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- Task 2.4: Index on stock_prices(symbol, timeframe, date DESC)
-- Optimises queries that filter by symbol + timeframe and order by date.
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_stock_prices_symbol_timeframe_date
    ON stock_prices (symbol, timeframe, date DESC);
