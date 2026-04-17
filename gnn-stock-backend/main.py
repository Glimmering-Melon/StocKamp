"""
FastAPI entrypoint for GNN Stock Backend.
"""

import logging
import os
import sys
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from api.v1.graph import router as graph_router
from api.v1.predict import router as predict_router
from database.neo4j_client import Neo4jClient

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: connect to Neo4j and verify connectivity
    try:
        Neo4jClient.get_instance()
        logger.info("Connected to Neo4j successfully.")
    except Exception as e:
        logger.error("Failed to connect to Neo4j: %s", e)
        sys.exit(1)

    yield

    # Shutdown (no-op; driver lifecycle managed by Neo4jClient)


app = FastAPI(title="GNN Stock Backend", lifespan=lifespan)

app.include_router(graph_router, prefix="/api/v1/graph")

try:
    app.include_router(predict_router, prefix="/api/v1/predict")
    print("✅ predict router loaded")
    app.openapi_schema = None
except Exception as e:
    print(f"❌ predict router failed: {e}")

# Debug: print all registered routes
for route in app.routes:
    print(f"ROUTE: {getattr(route, 'methods', '?')} {getattr(route, 'path', '?')}")


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("Unhandled exception for request %s", request.url)
    return JSONResponse(
        status_code=500,
        content={"error": "Internal server error"},
    )


if __name__ == "__main__":
    API_HOST = os.getenv("API_HOST", "0.0.0.0")
    API_PORT = int(os.getenv("API_PORT", "8000"))
    uvicorn.run(app, host=API_HOST, port=API_PORT)
