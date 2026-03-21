package com.stockamp.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Main : Screen("main")
    data object Home : Screen("home")
    data object StockDetail : Screen("stock_detail/{symbol}") {
        fun createRoute(symbol: String) = "stock_detail/$symbol"
    }
    data object AddEditJournal : Screen("add_edit_journal?entryId={entryId}") {
        fun createRoute(entryId: Long? = null) =
            if (entryId != null) "add_edit_journal?entryId=$entryId" else "add_edit_journal"
    }
    data object Profile : Screen("profile")
}
