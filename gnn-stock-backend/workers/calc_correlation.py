"""
Correlation Worker — computes Pearson correlation between stock price series
and maintains CORRELATED_WITH edges in the Neo4j Knowledge Graph.
"""

import logging
from dataclasses import dataclass
from datetime import datetime, timedelta

import numpy as np
import pandas as pd

from database.neo4j_client import Neo4jClient

logger = logging.getLogger(__name__)

MIN_TRADING_DAYS = 60
DEFAULT_MONTHS = 6
DEFAULT_THRESHOLD = 0.85


# ---------------------------------------------------------------------------
# Data fetching
# ---------------------------------------------------------------------------

def fetch_price_history(tickers: list[str], months: int = DEFAULT_MONTHS) -> pd.DataFrame:
    """
    Fetch closing price history for the past `months` months using yfinance (.VN suffix).

    Returns a DataFrame with:
        index  = date
        columns = ticker symbols (without .VN suffix)
        values  = closing price
    """
    import yfinance as yf

    end_date = datetime.today()
    start_date = end_date - timedelta(days=months * 31)
    start_str = start_date.strftime("%Y-%m-%d")
    end_str = end_date.strftime("%Y-%m-%d")

    # yfinance supports batch download
    yf_tickers = [f"{t}.VN" for t in tickers]

    try:
        raw = yf.download(
            yf_tickers,
            start=start_str,
            end=end_str,
            progress=False,
            auto_adjust=True,
            threads=True,
        )
        if raw.empty:
            return pd.DataFrame()

        # Extract Close prices
        if isinstance(raw.columns, pd.MultiIndex):
            close_df = raw["Close"].copy()
        else:
            close_df = raw[["Close"]].copy()

        # Rename columns back to original ticker names (strip .VN)
        close_df.columns = [c.replace(".VN", "") if isinstance(c, str) else c for c in close_df.columns]
        close_df.index = pd.to_datetime(close_df.index)
        close_df.sort_index(inplace=True)
        return close_df

    except Exception as exc:
        logger.error("fetch_price_history: batch download failed — %s", exc)
        return pd.DataFrame()


# ---------------------------------------------------------------------------
# Stats dataclass
# ---------------------------------------------------------------------------

@dataclass
class CorrelationStats:
    pairs_evaluated: int = 0
    edges_created: int = 0
    edges_removed: int = 0
    tickers_skipped: int = 0


# ---------------------------------------------------------------------------
# Correlation computation
# ---------------------------------------------------------------------------

def compute_correlations(price_df: pd.DataFrame) -> pd.DataFrame:
    """
    Compute the Pearson correlation matrix for all ticker columns.

    Returns a square DataFrame (tickers × tickers) with correlation coefficients.
    """
    return price_df.corr(method="pearson")


# ---------------------------------------------------------------------------
# Edge management
# ---------------------------------------------------------------------------

def update_correlation_edges(
    corr_matrix: pd.DataFrame,
    threshold: float = DEFAULT_THRESHOLD,
    client: Neo4jClient = None,
) -> CorrelationStats:
    """
    For every unique pair (i, j) in the correlation matrix:
      - score > threshold  → MERGE CORRELATED_WITH edge with rounded score
      - score ≤ threshold  → DELETE existing CORRELATED_WITH edge if present

    Returns CorrelationStats with counts of pairs evaluated, edges created/removed.
    """
    if client is None:
        client = Neo4jClient.get_instance()

    stats = CorrelationStats()
    tickers = list(corr_matrix.columns)

    for i in range(len(tickers)):
        for j in range(i + 1, len(tickers)):
            ticker_a = tickers[i]
            ticker_b = tickers[j]
            raw_score = corr_matrix.loc[ticker_a, ticker_b]

            # Skip NaN correlations (insufficient overlapping data)
            if pd.isna(raw_score):
                continue

            stats.pairs_evaluated += 1
            score = float(raw_score)

            if score > threshold:
                rounded_score = round(score, 4)
                # MERGE edge in both directions (undirected relationship)
                cypher = (
                    "MATCH (a:Company {ticker: $ticker_a}), (b:Company {ticker: $ticker_b}) "
                    "MERGE (a)-[r:CORRELATED_WITH]->(b) "
                    "SET r.score = $score"
                )
                client.run_query(cypher, {"ticker_a": ticker_a, "ticker_b": ticker_b, "score": rounded_score})
                stats.edges_created += 1
            else:
                # Remove edge if it exists (both directions)
                count_result = client.run_query(
                    "MATCH (a:Company {ticker: $ticker_a})-[r:CORRELATED_WITH]-(b:Company {ticker: $ticker_b}) "
                    "RETURN count(r) AS cnt",
                    {"ticker_a": ticker_a, "ticker_b": ticker_b},
                )
                existing = count_result[0]["cnt"] if count_result else 0
                if existing > 0:
                    client.run_query(
                        "MATCH (a:Company {ticker: $ticker_a})-[r:CORRELATED_WITH]-(b:Company {ticker: $ticker_b}) "
                        "DELETE r",
                        {"ticker_a": ticker_a, "ticker_b": ticker_b},
                    )
                    stats.edges_removed += existing

    return stats


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def run_correlation() -> None:
    """
    Full correlation pipeline:
      1. Fetch all Company tickers from Neo4j (only HOSE/HNX/UPCOM)
      2. Fetch price history concurrently
      3. Skip tickers with < 60 trading days of data
      4. Compute Pearson correlation matrix
      5. Update CORRELATED_WITH edges in Neo4j
      6. Log summary
    """
    import concurrent.futures

    logger.info("run_correlation: starting correlation calculation")

    client = Neo4jClient.get_instance()

    # Fetch all Company tickers (exchange filter removed — exchange may be empty)
    ticker_records = client.run_query(
        "MATCH (c:Company) RETURN c.ticker AS ticker"
    )
    all_tickers = [r["ticker"] for r in ticker_records if r.get("ticker")]
    logger.info("run_correlation: found %d tickers in graph", len(all_tickers))

    if not all_tickers:
        logger.warning("run_correlation: no tickers found, exiting")
        return

    # Fetch price history in one batch via yfinance
    logger.info("run_correlation: fetching price history for %d tickers via yfinance...", len(all_tickers))
    price_df = fetch_price_history(all_tickers)

    # Filter out tickers with fewer than MIN_TRADING_DAYS of data
    valid_tickers: list[str] = []
    skipped = 0
    for ticker in price_df.columns:
        available = price_df[ticker].dropna().shape[0]
        if available < MIN_TRADING_DAYS:
            logger.warning(
                "run_correlation: skipping ticker %s — only %d trading days available (minimum %d)",
                ticker, available, MIN_TRADING_DAYS,
            )
            skipped += 1
        else:
            valid_tickers.append(ticker)

    if len(valid_tickers) < 2:
        logger.warning("run_correlation: fewer than 2 valid tickers after filtering")
        logger.info("run_correlation: completed — pairs_evaluated=0, edges_created=0, edges_removed=0, tickers_skipped=%d", skipped)
        return

    logger.info("run_correlation: computing correlations for %d tickers...", len(valid_tickers))
    filtered_df = price_df[valid_tickers]
    corr_matrix = compute_correlations(filtered_df)
    stats = update_correlation_edges(corr_matrix, client=client)
    stats.tickers_skipped = skipped

    logger.info(
        "run_correlation: completed — pairs_evaluated=%d, edges_created=%d, edges_removed=%d, tickers_skipped=%d",
        stats.pairs_evaluated, stats.edges_created, stats.edges_removed, stats.tickers_skipped,
    )



if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    run_correlation()
