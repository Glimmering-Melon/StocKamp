package com.stockamp.data.sample

import com.stockamp.data.model.*

object SampleData {

    val stocks = listOf(
        Stock("AAPL", "Apple Inc.", "Technology", "NASDAQ", 178.50, 177.25, 1.25, 0.71, 52_340_000, 2_800_000_000_000, 199.62, 143.90),
        Stock("MSFT", "Microsoft Corp.", "Technology", "NASDAQ", 415.20, 413.80, 1.40, 0.34, 22_100_000, 3_100_000_000_000, 430.82, 309.45),
        Stock("GOOGL", "Alphabet Inc.", "Technology", "NASDAQ", 155.80, 154.90, 0.90, 0.58, 24_500_000, 1_950_000_000_000, 174.70, 120.21),
        Stock("AMZN", "Amazon.com Inc.", "Consumer Cyclical", "NASDAQ", 185.60, 184.20, 1.40, 0.76, 35_200_000, 1_920_000_000_000, 201.20, 138.01),
        Stock("TSLA", "Tesla Inc.", "Automotive", "NASDAQ", 175.30, 178.50, -3.20, -1.79, 98_500_000, 558_000_000_000, 278.98, 138.80),
        Stock("NVDA", "NVIDIA Corp.", "Technology", "NASDAQ", 875.40, 868.20, 7.20, 0.83, 42_300_000, 2_160_000_000_000, 974.00, 392.30),
        Stock("META", "Meta Platforms Inc.", "Technology", "NASDAQ", 505.80, 502.30, 3.50, 0.70, 15_800_000, 1_300_000_000_000, 542.81, 296.37),
        Stock("JPM", "JPMorgan Chase & Co.", "Financial", "NYSE", 196.50, 195.20, 1.30, 0.67, 8_900_000, 567_000_000_000, 205.88, 143.64),
        Stock("V", "Visa Inc.", "Financial", "NYSE", 280.30, 278.90, 1.40, 0.50, 6_500_000, 575_000_000_000, 290.96, 227.77),
        Stock("JNJ", "Johnson & Johnson", "Healthcare", "NYSE", 158.40, 157.80, 0.60, 0.38, 7_200_000, 414_000_000_000, 175.97, 143.13),
        Stock("WMT", "Walmart Inc.", "Consumer Defensive", "NYSE", 168.50, 167.90, 0.60, 0.36, 9_100_000, 454_000_000_000, 173.39, 143.83),
        Stock("UNH", "UnitedHealth Group", "Healthcare", "NYSE", 527.80, 525.40, 2.40, 0.46, 3_400_000, 490_000_000_000, 554.70, 436.38),
        Stock("DIS", "Walt Disney Co.", "Communication", "NYSE", 112.50, 113.80, -1.30, -1.14, 11_200_000, 206_000_000_000, 123.74, 78.73),
        Stock("NFLX", "Netflix Inc.", "Communication", "NASDAQ", 628.50, 625.20, 3.30, 0.53, 5_400_000, 272_000_000_000, 639.00, 395.00),
        Stock("BA", "Boeing Co.", "Industrials", "NYSE", 192.30, 194.50, -2.20, -1.13, 7_800_000, 116_000_000_000, 267.54, 159.72),
    )

    fun generatePriceHistory(symbol: String, days: Int = 30): List<StockPrice> {
        val stock = stocks.find { it.symbol == symbol } ?: return emptyList()
        val basePrice = stock.currentPrice
        val prices = mutableListOf<StockPrice>()
        var price = basePrice * 0.92

        for (i in days downTo 0) {
            val change = (Math.random() - 0.48) * basePrice * 0.03
            price += change
            price = price.coerceIn(basePrice * 0.8, basePrice * 1.15)
            val high = price * (1 + Math.random() * 0.02)
            val low = price * (1 - Math.random() * 0.02)
            val open = price * (1 + (Math.random() - 0.5) * 0.015)
            prices.add(
                StockPrice(
                    symbol = symbol,
                    date = "2026-${String.format("%02d", (3 - i / 30).coerceAtLeast(1))}-${String.format("%02d", (i % 28 + 1))}",
                    open = open,
                    high = high,
                    low = low,
                    close = price,
                    volume = (500_000 + (Math.random() * 3_000_000)).toLong()
                )
            )
        }
        return prices
    }

    val sectors = listOf(
        MarketSector("Technology", 0.65, 6),
        MarketSector("Financial", 0.58, 2),
        MarketSector("Healthcare", 0.42, 2),
        MarketSector("Consumer Cyclical", 0.76, 1),
        MarketSector("Consumer Defensive", 0.36, 1),
        MarketSector("Communication", -0.31, 2),
        MarketSector("Automotive", -1.79, 1),
        MarketSector("Industrials", -1.13, 1),
    )

    val sampleJournalEntries = listOf(
        JournalEntry(id = 1, symbol = "FPT", action = "BUY", quantity = 100, transactionDate = "2026-03-01", notes = "Kỳ vọng tăng trưởng AI"),
        JournalEntry(id = 2, symbol = "VCB", action = "BUY", quantity = 200, transactionDate = "2026-03-05", notes = "Ngân hàng top tier, giá hợp lý"),
        JournalEntry(id = 3, symbol = "HPG", action = "SELL", quantity = 500, transactionDate = "2026-03-10", notes = "Chốt lời sau khi tăng 15%"),
        JournalEntry(id = 4, symbol = "VNM", action = "BUY", quantity = 150, transactionDate = "2026-03-15", notes = "Cổ tức ổn định, giá chiết khấu"),
        JournalEntry(id = 5, symbol = "MWG", action = "SELL", quantity = 300, transactionDate = "2026-03-20", notes = "Cắt lỗ do báo cáo quý kém"),
    )
}

