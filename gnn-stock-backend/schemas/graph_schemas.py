from pydantic import BaseModel


class RelatedCompany(BaseModel):
    ticker: str
    name: str
    exchange: str
    relationship_path: list[str]  # e.g. ["SUPPLIES_TO", "BELONGS_TO"]
    hop_distance: int  # 1 or 2


class ImpactResponse(BaseModel):
    ticker: str
    related_companies: list[RelatedCompany]
    query_time_ms: int


class PredictionItem(BaseModel):
    ticker: str
    predicted_pct_change: float  # e.g. +2.5 means predicted +2.5%


class PredictionResponse(BaseModel):
    predictions: list[PredictionItem]
    model_trained: bool
    tickers_count: int
