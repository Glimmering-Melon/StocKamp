import argparse
from pathlib import Path

import numpy as np
import tensorflow as tf
import yfinance as yf
from sklearn.preprocessing import MinMaxScaler


TIME_STEPS = 30


def create_sequences(values: np.ndarray, time_steps: int) -> tuple[np.ndarray, np.ndarray]:
    x, y = [], []
    for index in range(time_steps, len(values)):
        x.append(values[index - time_steps:index, 0])
        y.append(values[index, 0])

    x = np.array(x, dtype=np.float32)
    y = np.array(y, dtype=np.float32)
    return x.reshape((x.shape[0], x.shape[1], 1)), y


def load_close_prices(symbol: str, period: str) -> np.ndarray:
    data = yf.download(symbol, period=period, interval="1d", auto_adjust=False, progress=False)
    if data.empty or "Close" not in data:
        raise ValueError(f"No Close price data found for {symbol}.")

    close = data["Close"]
    if hasattr(close, "columns"):
        close = close.iloc[:, 0]

    close = close.dropna().astype("float32").to_numpy().reshape(-1, 1)
    if len(close) <= TIME_STEPS:
        raise ValueError(f"Need more than {TIME_STEPS} close prices to train.")

    return close


def build_model(time_steps: int) -> tf.keras.Model:
    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(time_steps, 1)),
            # unroll=True keeps the static 30-step LSTM easier to convert to TFLite.
            tf.keras.layers.LSTM(64, return_sequences=True, unroll=True),
            tf.keras.layers.Dropout(0.2),
            tf.keras.layers.LSTM(32, unroll=True),
            tf.keras.layers.Dense(1),
        ]
    )
    model.compile(optimizer="adam", loss="mean_squared_error")
    return model


def main() -> None:
    parser = argparse.ArgumentParser(description="Train an LSTM stock model and export TFLite.")
    parser.add_argument("--symbol", default="FPT.VN", help="Yahoo Finance symbol, e.g. FPT.VN")
    parser.add_argument("--period", default="5y", help="History period accepted by yfinance.")
    parser.add_argument("--epochs", type=int, default=25)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument(
        "--output",
        default="app/src/main/assets/lstm_stock_model.tflite",
        help="Output .tflite path.",
    )
    args = parser.parse_args()

    tf.keras.utils.set_random_seed(42)

    close_prices = load_close_prices(args.symbol, args.period)

    scaler = MinMaxScaler(feature_range=(0, 1))
    scaled_close = scaler.fit_transform(close_prices)

    x_train, y_train = create_sequences(scaled_close, TIME_STEPS)

    model = build_model(TIME_STEPS)
    model.fit(
        x_train,
        y_train,
        epochs=args.epochs,
        batch_size=args.batch_size,
        validation_split=0.1,
        shuffle=False,
    )

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(tflite_model)

    print(f"Saved TFLite model to: {output_path}")
    print(f"Training close range: min={float(close_prices.min()):.2f}, max={float(close_prices.max()):.2f}")


if __name__ == "__main__":
    main()
