package com.stockamp.data.sample

import com.stockamp.data.model.*

object SampleData {

    val stocks = listOf(
        Stock("VNM", "Vinamilk", "Thực phẩm", "HOSE", 74500.0, 73800.0, 700.0, 0.95, 2_340_000, 170_000_000_000, 89000.0, 65000.0),
        Stock("VIC", "Vingroup", "Bất động sản", "HOSE", 42100.0, 42800.0, -700.0, -1.64, 3_120_000, 180_000_000_000, 52000.0, 38000.0),
        Stock("VHM", "Vinhomes", "Bất động sản", "HOSE", 38950.0, 39200.0, -250.0, -0.64, 1_890_000, 130_000_000_000, 48000.0, 34000.0),
        Stock("VCB", "Vietcombank", "Ngân hàng", "HOSE", 88200.0, 87500.0, 700.0, 0.80, 1_560_000, 450_000_000_000, 98000.0, 78000.0),
        Stock("FPT", "FPT Corp", "Công nghệ", "HOSE", 125000.0, 123500.0, 1500.0, 1.21, 2_780_000, 200_000_000_000, 135000.0, 90000.0),
        Stock("MWG", "Thế Giới Di Động", "Bán lẻ", "HOSE", 52300.0, 53100.0, -800.0, -1.51, 1_450_000, 75_000_000_000, 62000.0, 42000.0),
        Stock("HPG", "Hòa Phát", "Thép", "HOSE", 25800.0, 25400.0, 400.0, 1.57, 5_670_000, 120_000_000_000, 32000.0, 21000.0),
        Stock("MSN", "Masan Group", "Tiêu dùng", "HOSE", 68500.0, 69200.0, -700.0, -1.01, 890_000, 95_000_000_000, 85000.0, 55000.0),
        Stock("TCB", "Techcombank", "Ngân hàng", "HOSE", 48700.0, 48200.0, 500.0, 1.04, 2_340_000, 170_000_000_000, 55000.0, 40000.0),
        Stock("VPB", "VPBank", "Ngân hàng", "HOSE", 19800.0, 20100.0, -300.0, -1.49, 4_560_000, 140_000_000_000, 24000.0, 17000.0),
        Stock("GAS", "PV Gas", "Dầu khí", "HOSE", 78900.0, 78000.0, 900.0, 1.15, 1_230_000, 150_000_000_000, 92000.0, 70000.0),
        Stock("SAB", "Sabeco", "Đồ uống", "HOSE", 59200.0, 58800.0, 400.0, 0.68, 450_000, 38_000_000_000, 72000.0, 52000.0),
        Stock("VRE", "Vincom Retail", "Bất động sản", "HOSE", 24100.0, 24500.0, -400.0, -1.63, 2_100_000, 56_000_000_000, 31000.0, 20000.0),
        Stock("PLX", "Petrolimex", "Dầu khí", "HOSE", 38600.0, 38200.0, 400.0, 1.05, 780_000, 50_000_000_000, 45000.0, 33000.0),
        Stock("BID", "BIDV", "Ngân hàng", "HOSE", 46300.0, 45800.0, 500.0, 1.09, 3_200_000, 235_000_000_000, 52000.0, 40000.0),
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
        MarketSector("Ngân hàng", 0.85, 4),
        MarketSector("Bất động sản", -1.24, 3),
        MarketSector("Công nghệ", 1.45, 1),
        MarketSector("Thực phẩm", 0.62, 1),
        MarketSector("Dầu khí", 1.10, 2),
        MarketSector("Bán lẻ", -0.89, 1),
        MarketSector("Thép", 1.57, 1),
        MarketSector("Tiêu dùng", -0.55, 1),
        MarketSector("Đồ uống", 0.68, 1),
    )

    val sampleJournalEntries = listOf(
        JournalEntry(id = 1, symbol = "FPT", action = "BUY", quantity = 100, transactionDate = "2026-03-01", notes = "Kỳ vọng tăng trưởng AI"),
        JournalEntry(id = 2, symbol = "VCB", action = "BUY", quantity = 200, transactionDate = "2026-03-05", notes = "Ngân hàng top tier, giá hợp lý"),
        JournalEntry(id = 3, symbol = "HPG", action = "SELL", quantity = 500, transactionDate = "2026-03-10", notes = "Chốt lời sau khi tăng 15%"),
        JournalEntry(id = 4, symbol = "VNM", action = "BUY", quantity = 150, transactionDate = "2026-03-15", notes = "Cổ tức ổn định, giá chiết khấu"),
        JournalEntry(id = 5, symbol = "MWG", action = "SELL", quantity = 300, transactionDate = "2026-03-20", notes = "Cắt lỗ do báo cáo quý kém"),
    )
}
