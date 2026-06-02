"""
Ingest Worker — fetches all VN stock tickers via vnstock3 and populates
the Neo4j Knowledge Graph with Company and Industry nodes.
"""

import logging
from dataclasses import dataclass, field

from database.neo4j_client import Neo4jClient

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Data fetching
# ---------------------------------------------------------------------------

def fetch_all_tickers() -> list[dict]:
    """
    Fetch all tickers from HOSE, HNX, and UPCOM using vnstock3 all_symbols()
    for the ticker list, then enrich with exchange/icb via direct TCBS API call.

    Returns a list of dicts with keys:
        ticker, name, exchange, market_cap, current_price, icb_code, industry_name
    """
    import requests
    from vnstock3 import Vnstock

    results: list[dict] = []

    try:
        # Step 1: get full ticker + name list via TCBS (only working listing endpoint)
        stock = Vnstock().stock(symbol="HPG", source="TCBS")
        all_df = stock.listing.all_symbols()
        all_df.columns = [c.lower() for c in all_df.columns]
        logger.info("fetch_all_tickers: got %d symbols", len(all_df))

        # Step 2: get exchange info from TCBS stock screening API (single bulk call)
        # This endpoint returns exchange, icbCode, marketCap, closePrice for all stocks
        url = "https://apipubaws.tcbs.com.vn/stock-insight/v1/stock/second-tc-price?tickers="
        tickers_list = all_df["ticker"].tolist()

        # Fetch in chunks of 200 to avoid URL length limits
        exchange_map: dict[str, dict] = {}
        chunk_size = 200
        for i in range(0, len(tickers_list), chunk_size):
            chunk = tickers_list[i:i + chunk_size]
            try:
                resp = requests.get(
                    url + ",".join(chunk),
                    headers={"Accept": "application/json"},
                    timeout=15,
                )
                if resp.status_code == 200:
                    data = resp.json().get("data", [])
                    for item in data:
                        t = item.get("ticker", "")
                        if t:
                            exchange_map[t] = item
            except Exception as e:
                logger.warning("fetch_all_tickers: chunk %d failed — %s", i, e)

        logger.info("fetch_all_tickers: got exchange info for %d tickers", len(exchange_map))

        # Step 3: merge
        target_exchanges = {"HOSE", "HNX", "UPCOM"}
        for _, row in all_df.iterrows():
            ticker = str(row.get("ticker", "") or "").strip()
            if not ticker:
                continue

            name = str(row.get("organ_name", "") or "")
            info = exchange_map.get(ticker, {})

            exchange = str(info.get("exchange", "") or "").upper()
            # TCBS may return "HSX" for HOSE — normalize
            if exchange in ("HSX", "HCM", "HOSE"):
                exchange = "HOSE"
            elif exchange == "HNX":
                exchange = "HNX"
            elif exchange in ("UPC", "UPCOM"):
                exchange = "UPCOM"

            results.append({
                "ticker": ticker,
                "name": name,
                "exchange": exchange,
                "market_cap": float(info.get("marketCap", 0) or 0),
                "current_price": float(info.get("closePrice", 0) or 0),
                "icb_code": str(info.get("icbCode", "") or ""),
                "industry_name": str(info.get("icbName", "") or ""),
            })

    except Exception as exc:
        logger.error("fetch_all_tickers: failed — %s", exc)

    return results


# ---------------------------------------------------------------------------
# Stats dataclass
# ---------------------------------------------------------------------------

@dataclass
class IngestStats:
    total_processed: int = 0
    created: int = 0
    updated: int = 0
    errors: int = 0

    def __add__(self, other: "IngestStats") -> "IngestStats":
        return IngestStats(
            total_processed=self.total_processed + other.total_processed,
            created=self.created + other.created,
            updated=self.updated + other.updated,
            errors=self.errors + other.errors,
        )


# ---------------------------------------------------------------------------
# Batch ingestion
# ---------------------------------------------------------------------------

def ingest_batch(batch: list[dict], client: Neo4jClient) -> IngestStats:
    """
    MERGE Company and Industry nodes, create BELONGS_TO edges.
    Errors per-ticker are caught, logged, and counted — processing continues.
    """
    stats = IngestStats()

    for item in batch:
        ticker = item.get("ticker", "<unknown>")
        try:
            # --- Company node ---
            company_props = {
                "ticker": item["ticker"],
                "name": item.get("name", ""),
                "exchange": item.get("exchange", ""),
                "market_cap": item.get("market_cap", 0.0),
                "current_price": item.get("current_price", 0.0),
            }
            company_id = client.create_node("Company", company_props)

            # --- Industry node (only when icb_code is present) ---
            icb_code = item.get("icb_code", "")
            if icb_code:
                industry_props = {
                    "icb_code": icb_code,
                    "name": item.get("industry_name", ""),
                }
                industry_id = client.create_node("Industry", industry_props)

                # --- BELONGS_TO edge ---
                client.create_edge(company_id, industry_id, "BELONGS_TO")

            stats.total_processed += 1
            stats.created += 1  # MERGE means create-or-update; we count as created

        except Exception as exc:
            logger.error("ingest_batch: error processing ticker %s — %s", ticker, exc)
            stats.errors += 1

    return stats


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

BATCH_SIZE = 50


def run_ingest() -> None:
    """
    Full ingestion pipeline:
      1. Fetch all tickers from HOSE / HNX / UPCOM
      2. Split into batches of at most BATCH_SIZE (50)
      3. Ingest each batch into Neo4j
      4. Log a summary report
    """
    logger.info("run_ingest: starting data ingestion")

    tickers = fetch_all_tickers()
    logger.info("run_ingest: fetched %d tickers total", len(tickers))

    client = Neo4jClient.get_instance()

    total_stats = IngestStats()
    batches = [tickers[i : i + BATCH_SIZE] for i in range(0, len(tickers), BATCH_SIZE)]

    for idx, batch in enumerate(batches, start=1):
        logger.info("run_ingest: processing batch %d/%d (%d tickers)", idx, len(batches), len(batch))
        batch_stats = ingest_batch(batch, client)
        total_stats = total_stats + batch_stats

    logger.info(
        "run_ingest: completed — total_processed=%d, created=%d, updated=%d, errors=%d",
        total_stats.total_processed,
        total_stats.created,
        total_stats.updated,
        total_stats.errors,
    )


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    run_ingest()
