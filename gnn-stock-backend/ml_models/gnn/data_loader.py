"""
Data loader for GNN model — loads Company nodes and edges from Neo4j
and builds a PyTorch Geometric Data object with real price change targets.

Improvements:
- market_cap and current_price fetched from yfinance (not Neo4j which has 0s)
- lookback extended to 90 days for more stable targets
- BELONGS_TO edges (industry) included in graph
- node degree as structural feature
"""

import logging
from datetime import datetime, timedelta
from typing import Optional

import pandas as pd
import torch
from torch_geometric.data import Data

from database.neo4j_client import Neo4jClient

logger = logging.getLogger(__name__)

_EXCHANGE_ORDER = ["HOSE", "HNX", "UPCOM"]

_NODES_QUERY = """
MATCH (c:Company)
RETURN c.ticker AS ticker, c.exchange AS exchange
"""

# Include both CORRELATED_WITH and BELONGS_TO edges
_EDGES_QUERY = """
MATCH (a:Company)-[r]-(b:Company)
RETURN DISTINCT a.ticker AS src, b.ticker AS dst
UNION
MATCH (c:Company)-[:BELONGS_TO]->(i:Industry)<-[:BELONGS_TO]-(d:Company)
WHERE c.ticker <> d.ticker
RETURN DISTINCT c.ticker AS src, d.ticker AS dst
"""


def _one_hot_exchange(exchange: Optional[str]) -> list[float]:
    vec = [0.0, 0.0, 0.0]
    if exchange in _EXCHANGE_ORDER:
        vec[_EXCHANGE_ORDER.index(exchange)] = 1.0
    return vec


def _fetch_price_data(tickers: list[str], lookback_days: int = 90) -> tuple[dict[str, float], dict[str, float], dict[str, float]]:
    """
    Fetch market_cap, current_price, and % price change over lookback_days.
    Returns three dicts: market_caps, prices, pct_changes
    """
    import yfinance as yf

    end = datetime.today()
    start = end - timedelta(days=lookback_days + 10)

    yf_tickers = [f"{t}.VN" for t in tickers]
    market_caps: dict[str, float] = {}
    prices: dict[str, float] = {}
    pct_changes: dict[str, float] = {}

    try:
        raw = yf.download(
            yf_tickers,
            start=start.strftime("%Y-%m-%d"),
            end=end.strftime("%Y-%m-%d"),
            progress=False,
            auto_adjust=True,
            threads=True,
        )
        if raw.empty:
            return market_caps, prices, pct_changes

        if isinstance(raw.columns, pd.MultiIndex):
            close_df = raw["Close"].copy()
            volume_df = raw["Volume"].copy() if "Volume" in raw.columns.get_level_values(0) else None
        else:
            close_df = raw[["Close"]].copy()
            volume_df = None

        close_df.columns = [c.replace(".VN", "") if isinstance(c, str) else c for c in close_df.columns]

        for ticker in tickers:
            if ticker not in close_df.columns:
                continue
            series = close_df[ticker].dropna()
            if len(series) < 2:
                continue

            current = float(series.iloc[-1])
            prices[ticker] = current

            # % change over full lookback period
            pct = ((series.iloc[-1] - series.iloc[0]) / series.iloc[0]) * 100
            pct_changes[ticker] = float(pct)

            # Approximate market cap: price * avg volume (rough proxy)
            if volume_df is not None and ticker in volume_df.columns:
                avg_vol = float(volume_df[ticker].dropna().mean())
                market_caps[ticker] = current * avg_vol / 1e9  # in billions
            else:
                market_caps[ticker] = 0.0

    except Exception as e:
        logger.warning("_fetch_price_data: failed — %s", e)

    return market_caps, prices, pct_changes


def load_data_from_neo4j(client: Neo4jClient, fetch_targets: bool = True) -> Data:
    """
    Query Company nodes and edges from Neo4j and return a PyTorch Geometric
    Data object suitable for StockGraphSAGE.

    Features per node (x): [market_cap_norm, price_norm, hose, hnx, upcom, degree_norm, pct_change_90d]
    edge_index: CORRELATED_WITH + industry co-membership edges
    y: % price change over 90 days

    Raises:
        ValueError: if the graph contains no Company nodes.
    """
    node_records = client.run_query(_NODES_QUERY)

    if not node_records:
        raise ValueError(
            "load_data_from_neo4j: Knowledge Graph contains no Company nodes. "
            "Run the ingest worker first."
        )

    tickers: list[str] = []
    exchanges: list[Optional[str]] = []

    for record in node_records:
        tickers.append(record.get("ticker") or "UNKNOWN")
        exchanges.append(record.get("exchange"))

    n_nodes = len(tickers)
    ticker_to_idx = {t: i for i, t in enumerate(tickers)}

    # Fetch real market data from yfinance
    market_caps: dict[str, float] = {}
    prices: dict[str, float] = {}
    y_values = [0.0] * n_nodes

    if fetch_targets:
        logger.info("load_data_from_neo4j: fetching 90-day price data for %d tickers...", n_nodes)
        market_caps, prices, pct_changes = _fetch_price_data(tickers, lookback_days=90)
        for ticker, pct in pct_changes.items():
            if ticker in ticker_to_idx:
                y_values[ticker_to_idx[ticker]] = pct
        logger.info("load_data_from_neo4j: got data for %d/%d tickers", len(pct_changes), n_nodes)

    y = torch.tensor(y_values, dtype=torch.float).unsqueeze(1)

    # Build edge_index (CORRELATED_WITH + industry co-membership)
    edge_records = client.run_query(_EDGES_QUERY)
    src_indices: list[int] = []
    dst_indices: list[int] = []

    for record in edge_records:
        src_ticker = record.get("src")
        dst_ticker = record.get("dst")
        if src_ticker in ticker_to_idx and dst_ticker in ticker_to_idx:
            src_indices.append(ticker_to_idx[src_ticker])
            dst_indices.append(ticker_to_idx[dst_ticker])

    if src_indices:
        edge_index = torch.tensor([src_indices, dst_indices], dtype=torch.long)
    else:
        edge_index = torch.zeros((2, 0), dtype=torch.long)

    # Node degree (normalized)
    degree = torch.zeros(n_nodes, dtype=torch.float)
    for idx in src_indices:
        degree[idx] += 1
    for idx in dst_indices:
        degree[idx] += 1
    degree_norm = (degree / (degree.max() + 1e-8)).unsqueeze(1)

    # Normalize price and market_cap
    max_price = max(prices.values()) if prices else 1.0
    max_mcap = max(market_caps.values()) if market_caps else 1.0
    
    # Avoid division by zero
    if max_price == 0:
        max_price = 1.0
    if max_mcap == 0:
        max_mcap = 1.0

    feature_rows: list[list[float]] = []
    for i, ticker in enumerate(tickers):
        price_norm = prices.get(ticker, 0.0) / max_price
        mcap_norm = market_caps.get(ticker, 0.0) / max_mcap
        one_hot = _one_hot_exchange(exchanges[i])
        feature_rows.append([mcap_norm, price_norm] + one_hot)

    x_base = torch.tensor(feature_rows, dtype=torch.float)
    x = torch.cat([x_base, degree_norm, y], dim=1)  # [N, 7]

    data = Data(x=x, edge_index=edge_index, y=y)
    data.tickers = tickers
    return data
