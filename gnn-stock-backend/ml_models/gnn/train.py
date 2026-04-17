import logging

import torch
from torch_geometric.data import Data

from .model import StockGraphSAGE

logger = logging.getLogger(__name__)


def train_model(
    data: Data,
    epochs: int = 200,
    learning_rate: float = 0.01,
) -> StockGraphSAGE:
    """
    Train a StockGraphSAGE model on the given graph data.

    Args:
        data: PyTorch Geometric Data object with x, edge_index, and y tensors.
        epochs: Number of training iterations (default 200).
        learning_rate: Adam optimizer learning rate (default 0.01).

    Returns:
        Trained StockGraphSAGE model.
    """
    in_channels = data.x.shape[1]
    model = StockGraphSAGE(in_channels=in_channels, hidden_channels=64, out_channels=1)

    criterion = torch.nn.MSELoss()
    optimizer = torch.optim.Adam(model.parameters(), lr=learning_rate)

    model.train()
    for epoch in range(1, epochs + 1):
        optimizer.zero_grad()
        out = model(data)
        loss = criterion(out, data.y)
        loss.backward()
        optimizer.step()

        # Log every epoch using print so it shows in terminal immediately
        print(f"Epoch {epoch}/{epochs} — Loss: {loss.item():.6f}", flush=True)
        if epoch % 10 == 0:
            logger.info("Epoch %d/%d — Loss: %.6f", epoch, epochs, loss.item())

    return model
