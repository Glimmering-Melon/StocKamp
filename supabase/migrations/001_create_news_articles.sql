-- Migration: 001_create_news_articles
-- Creates the news_articles table for the Stock News feature.
-- Requirements: 3.1, 3.2, 3.3, 3.4

-- ============================================================
-- Table
-- ============================================================
CREATE TABLE news_articles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title           TEXT NOT NULL,
    url             TEXT NOT NULL UNIQUE,
    summary         TEXT,
    source_name     TEXT NOT NULL,
    published_at    TIMESTAMPTZ NOT NULL,
    stock_symbols   TEXT[] DEFAULT '{}',
    sentiment_label TEXT CHECK (sentiment_label IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL')),
    sentiment_score FLOAT CHECK (sentiment_score >= 0.0 AND sentiment_score <= 1.0),
    status          TEXT NOT NULL DEFAULT 'pending_analysis'
                    CHECK (status IN ('pending_analysis', 'analyzed', 'analysis_failed')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- Indexes
-- ============================================================

-- Optimise queries sorted by newest articles first (Requirement 3.4, 4.1)
CREATE INDEX idx_news_published_at ON news_articles (published_at DESC);

-- GIN index for array containment queries on stock_symbols (Requirement 3.4, 6.2)
CREATE INDEX idx_news_stock_symbols ON news_articles USING GIN (stock_symbols);

-- Partial index to speed up AI_Processor fetching pending articles (Requirement 2.1)
CREATE INDEX idx_news_status ON news_articles (status) WHERE status = 'pending_analysis';

-- ============================================================
-- Row Level Security
-- ============================================================

ALTER TABLE news_articles ENABLE ROW LEVEL SECURITY;

-- Anyone (including anonymous users) can read articles (Requirement 3.2)
CREATE POLICY "public_read"
    ON news_articles
    FOR SELECT
    USING (true);

-- Only the service role (Python backend) can insert / update / delete (Requirement 3.2)
CREATE POLICY "service_write"
    ON news_articles
    FOR ALL
    USING (auth.role() = 'service_role');

-- ============================================================
-- Realtime CDC
-- ============================================================
-- Enable Realtime Change Data Capture so the Android app receives
-- live INSERT events for newly analysed articles (Requirement 10.1).
--
-- Run this statement in the Supabase SQL editor, or uncomment it
-- here if your Supabase project allows DDL on publications:
--
--   ALTER PUBLICATION supabase_realtime ADD TABLE news_articles;
--
-- Alternatively, enable Realtime for this table via the Supabase
-- Dashboard → Database → Replication → supabase_realtime publication.
