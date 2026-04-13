def calculate_profit_margin(entry_price: float, latest_close: float) -> float:
    return (latest_close - entry_price) / entry_price * 100.0
