"""
GNN prediction endpoint — trains StockGraphSAGE on the Knowledge Graph
and returns predicted % price change per ticker.
"""

import logging

from fastapi import APIRouter, HTTPException, Query

from database.neo4j_client import Neo4jClient
from schemas.graph_schemas import PredictionItem, PredictionResponse

router = APIRouter()
logger = logging.getLogger(__name__)

# Cache trained model and data in memory
_model_cache: dict = {}


@router.post("/load")
async def load_model() -> dict:
    """
    Load a previously trained model from gnn_model.pt file.
    Use this after training locally with the Python script.
    """
    import os
    import torch
    from ml_models.gnn.model import StockGraphSAGE
    from ml_models.gnn.data_loader import load_data_from_neo4j

    if not os.path.exists("gnn_model.pt"):
        raise HTTPException(status_code=404, detail="No saved model found. Train first using the Python script.")

    try:
        checkpoint = torch.load("gnn_model.pt", map_location="cpu")
        in_channels = checkpoint["in_channels"]
        model = StockGraphSAGE(in_channels=in_channels, hidden_channels=64, out_channels=1)
        model.load_state_dict(checkpoint["model_state"])
        model.eval()

        # Load graph data (without fetching new targets)
        client = Neo4jClient.get_instance()
        data = load_data_from_neo4j(client, fetch_targets=False)

        # Pad features to match saved model's in_channels
        if data.x.shape[1] < in_channels:
            pad = torch.zeros(data.x.shape[0], in_channels - data.x.shape[1])
            data.x = torch.cat([data.x, pad], dim=1)

        data.tickers = checkpoint["tickers"]
        _model_cache["model"] = model
        _model_cache["data"] = data

        return {"status": "loaded", "in_channels": in_channels, "tickers": len(checkpoint["tickers"])}
    except Exception as e:
        logger.exception("Load model failed")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/prepare")
async def prepare_data() -> dict:
    """
    Fetch price change targets from yfinance and cache the graph data.
    Run this BEFORE /train. Takes 2-3 minutes for full dataset.
    """
    try:
        from ml_models.gnn.data_loader import load_data_from_neo4j
        client = Neo4jClient.get_instance()
        logger.info("GNN prepare: fetching graph data + price targets...")
        data = load_data_from_neo4j(client, fetch_targets=True)

        import torch
        mask = data.y.squeeze() != 0
        n_valid = mask.sum().item()
        logger.info("GNN prepare: %d/%d nodes have valid targets", n_valid, data.x.shape[0])

        _model_cache["raw_data"] = data
        return {
            "status": "ready",
            "nodes": data.x.shape[0],
            "nodes_with_targets": int(n_valid),
            "edges": data.edge_index.shape[1],
        }
    except Exception as e:
        logger.exception("GNN prepare failed")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/train")
async def train_gnn(epochs: int = Query(default=100, ge=10, le=500)) -> dict:
    """
    Train the GNN model. Call /prepare first to fetch price data,
    or use cached data from a previous /prepare call.
    """
    try:
        import torch
        from ml_models.gnn.train import train_model

        # Use cached data from /prepare, or fetch without targets as fallback
        if "raw_data" in _model_cache:
            data = _model_cache["raw_data"]
            logger.info("GNN train: using cached data")
        else:
            from ml_models.gnn.data_loader import load_data_from_neo4j
            client = Neo4jClient.get_instance()
            data = load_data_from_neo4j(client, fetch_targets=False)
            logger.info("GNN train: no cached data, using graph structure only")

        mask = data.y.squeeze() != 0
        n_valid = mask.sum().item()
        logger.info("GNN train: %d/%d nodes have valid targets", n_valid, data.x.shape[0])

        if n_valid < 10:
            raise ValueError(f"Too few nodes with valid targets: {n_valid}")

        y_filled = data.y.clone()
        mean_y = data.y[mask].mean()
        y_filled[~mask] = mean_y

        x_augmented = torch.cat([data.x, y_filled], dim=1)
        from torch_geometric.data import Data
        train_data = Data(x=x_augmented, edge_index=data.edge_index, y=data.y)
        train_data.tickers = data.tickers

        logger.info("GNN train: %d nodes, %d edges, %d features",
                    train_data.x.shape[0], train_data.edge_index.shape[1], train_data.x.shape[1])
        model = train_model(train_data, epochs=epochs)

        _model_cache["model"] = model
        _model_cache["data"] = train_data

        # Save model to disk so it survives server restarts
        torch.save({
            "model_state": model.state_dict(),
            "tickers": train_data.tickers,
            "in_channels": train_data.x.shape[1],
        }, "gnn_model.pt")
        logger.info("GNN train: model saved to gnn_model.pt")

        return {
            "status": "trained",
            "nodes": train_data.x.shape[0],
            "nodes_with_targets": int(n_valid),
            "edges": train_data.edge_index.shape[1],
            "epochs": epochs,
        }
    except Exception as e:
        logger.exception("GNN train failed")
        raise HTTPException(status_code=500, detail=str(e))

        # Filter to only nodes with non-zero targets for training
        mask = data.y.squeeze() != 0
        n_valid = mask.sum().item()
        logger.info("GNN train: %d/%d nodes have valid targets", n_valid, data.x.shape[0])

        if n_valid < 10:
            raise ValueError(f"Too few nodes with valid targets: {n_valid}")

        # Use price change as a feature too (from yfinance close prices)
        # Augment x with normalized y as self-supervised signal
        # For nodes without target, use mean of valid targets
        y_filled = data.y.clone()
        mean_y = data.y[mask].mean()
        y_filled[~mask] = mean_y

        # Add y as extra feature to help model learn
        x_augmented = torch.cat([data.x, y_filled], dim=1)
        train_data = Data(x=x_augmented, edge_index=data.edge_index, y=data.y)
        train_data.tickers = data.tickers

        logger.info("GNN train: %d nodes, %d edges, %d features", 
                    train_data.x.shape[0], train_data.edge_index.shape[1], train_data.x.shape[1])
        model = train_model(train_data, epochs=epochs)

        _model_cache["model"] = model
        _model_cache["data"] = train_data

        return {
            "status": "trained",
            "nodes": train_data.x.shape[0],
            "nodes_with_targets": int(n_valid),
            "edges": train_data.edge_index.shape[1],
            "epochs": epochs,
        }
    except Exception as e:
        logger.exception("GNN train failed")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/predict", response_model=PredictionResponse)
async def predict(
    ticker: str = Query(default=None, min_length=2, max_length=10, pattern="^[A-Z]+$"),
    top_n: int = Query(default=20, ge=1, le=100),
) -> PredictionResponse:
    """
    Return GNN predictions for all tickers (or a specific ticker).
    Must call /train first.
    """
    if "model" not in _model_cache:
        raise HTTPException(
            status_code=400,
            detail="Model not trained yet. Call POST /api/v1/predict/train first.",
        )

    import torch

    model = _model_cache["model"]
    data = _model_cache["data"]
    tickers = data.tickers

    model.eval()
    with torch.no_grad():
        preds = model(data).squeeze(1).tolist()  # [N]

    results = [
        PredictionItem(ticker=t, predicted_pct_change=round(p, 4))
        for t, p in zip(tickers, preds)
    ]

    # Filter by specific ticker if provided
    if ticker:
        results = [r for r in results if r.ticker == ticker]
        if not results:
            raise HTTPException(status_code=404, detail=f"Ticker {ticker} not found in graph")
    else:
        # Sort by predicted change descending, return top_n
        results.sort(key=lambda r: r.predicted_pct_change, reverse=True)
        results = results[:top_n]

    return PredictionResponse(
        predictions=results,
        model_trained=True,
        tickers_count=len(tickers),
    )
