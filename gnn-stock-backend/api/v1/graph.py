"""
FastAPI router for graph impact endpoint.
Prefix /api/v1/graph is handled in main.py.
"""

import time

from fastapi import APIRouter, HTTPException, Query

from database.neo4j_client import Neo4jClient
from schemas.graph_schemas import ImpactResponse, RelatedCompany

router = APIRouter()

_IMPACT_QUERY = """
MATCH path = (start:Company {ticker: $ticker})-[*1..2]-(related:Company)
WHERE related.ticker <> $ticker
RETURN DISTINCT related.ticker AS ticker,
       related.name AS name,
       related.exchange AS exchange,
       [r IN relationships(path) | type(r)] AS relationship_path,
       length(path) AS hop_distance
"""


@router.get("/impact", response_model=ImpactResponse)
async def get_impact(
    ticker: str = Query(..., min_length=2, max_length=10, pattern="^[A-Z]+$"),
) -> ImpactResponse:
    """Return all Company nodes reachable within 2 hops from the given ticker."""
    client = Neo4jClient.get_instance()

    # Check ticker exists
    exists_result = client.run_query(
        "MATCH (c:Company {ticker: $ticker}) RETURN c.ticker AS ticker LIMIT 1",
        {"ticker": ticker},
    )
    if not exists_result:
        raise HTTPException(
            status_code=404,
            detail={"error": "Ticker not found", "ticker": ticker},
        )

    # Run 2-hop traversal and measure query time
    start_time = time.monotonic()
    rows = client.run_query(_IMPACT_QUERY, {"ticker": ticker})
    query_time_ms = int((time.monotonic() - start_time) * 1000)

    related_companies = [
        RelatedCompany(
            ticker=row["ticker"],
            name=row["name"],
            exchange=row["exchange"],
            relationship_path=row["relationship_path"],
            hop_distance=row["hop_distance"],
        )
        for row in rows
    ]

    return ImpactResponse(
        ticker=ticker,
        related_companies=related_companies,
        query_time_ms=query_time_ms,
    )
