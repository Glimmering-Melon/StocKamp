import torch
import torch.nn.functional as F
from torch import Tensor
from torch_geometric.nn import SAGEConv
from torch_geometric.data import Data


class StockGraphSAGE(torch.nn.Module):
    """
    GraphSAGE-based GNN for node regression on stock knowledge graphs.
    Predicts percentage price change per node (raw regression output).
    """

    def __init__(self, in_channels: int, hidden_channels: int, out_channels: int = 1):
        super().__init__()
        self.conv1 = SAGEConv(in_channels, hidden_channels)
        self.conv2 = SAGEConv(hidden_channels, out_channels)

    def forward(self, data: Data) -> Tensor:
        x, edge_index = data.x, data.edge_index
        x = self.conv1(x, edge_index)
        x = F.relu(x)
        x = self.conv2(x, edge_index)
        return x  # shape [N, 1], raw regression output — no final activation
