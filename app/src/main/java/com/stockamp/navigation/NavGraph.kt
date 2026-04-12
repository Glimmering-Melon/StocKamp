package com.stockamp.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.*
import androidx.navigation.compose.*
import com.stockamp.ui.auth.LoginScreen
import com.stockamp.ui.auth.RegisterScreen
import com.stockamp.ui.home.HomeScreen
import com.stockamp.ui.journal.AddEditJournalScreen
import com.stockamp.ui.journal.JournalScreen
import com.stockamp.ui.main.MainViewModel
import com.stockamp.ui.market.MarketOverviewScreen
import com.stockamp.ui.market.StockDetailScreen
import com.stockamp.ui.news.NewsListScreen
import com.stockamp.ui.profile.ProfileScreen
import com.stockamp.ui.watchlist.WatchlistScreen

data class BottomNavItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StocKampNavHost(
    viewModel: MainViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = if (uiState.isAuthenticated) Screen.Main.route else Screen.Login.route

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { fadeIn(animationSpec = tween(300)) },
        exitTransition = { fadeOut(animationSpec = tween(300)) }
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) }
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Register.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Main.route) {
            MainScreen(navController = navController)
        }

        composable(
            route = Screen.StockDetail.route,
            arguments = listOf(navArgument("symbol") { type = NavType.StringType })
        ) { backStackEntry ->
            val symbol = backStackEntry.arguments?.getString("symbol") ?: ""
            StockDetailScreen(
                symbol = symbol,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.News.route,
            arguments = listOf(navArgument("symbol") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { backStackEntry ->
            val symbol = backStackEntry.arguments?.getString("symbol")
            NewsListScreen(
                initialSymbolFilter = symbol,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AddEditJournal.route,
            arguments = listOf(navArgument("entryId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getString("entryId")?.toLongOrNull()
            AddEditJournalScreen(
                entryId = entryId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun MainScreen(navController: NavController, viewModel: MainViewModel = hiltViewModel()) {
    val bottomNavItems = listOf(
        BottomNavItem("Trang chủ", Icons.Filled.Home, Icons.Outlined.Home, "home"),
        BottomNavItem("Thị trường", Icons.Filled.BarChart, Icons.Outlined.BarChart, "market"),
        BottomNavItem("Tin tức", Icons.Filled.Newspaper, Icons.Outlined.Newspaper, "news_tab"),
        BottomNavItem("Nhật ký", Icons.Filled.MenuBook, Icons.Outlined.MenuBook, "journal"),
        BottomNavItem("Hồ sơ", Icons.Filled.Person, Icons.Outlined.Person, "profile"),
    )
    val innerNavController = rememberNavController()
    val navBackStackEntry by innerNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                bottomNavItems.forEach { item ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (currentRoute == item.route) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.title
                            )
                        },
                        label = {
                            Text(
                                item.title,
                                fontWeight = if (currentRoute == item.route) FontWeight.SemiBold else FontWeight.Normal,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        selected = currentRoute == item.route,
                        onClick = {
                            innerNavController.navigate(item.route) {
                                popUpTo(innerNavController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = innerNavController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    onStockClick = { symbol ->
                        navController.navigate(Screen.StockDetail.createRoute(symbol))
                    },
                    onSearchClick = {
                        innerNavController.navigate("market")
                    },
                    onNewsClick = {
                        innerNavController.navigate("news_tab")
                    }
                )
            }
            composable("market") {
                MarketOverviewScreen(
                    onStockClick = { symbol ->
                        navController.navigate(Screen.StockDetail.createRoute(symbol))
                    },
                    onWatchlistClick = {
                        innerNavController.navigate("watchlist") {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable("watchlist") {
                WatchlistScreen(
                    onStockClick = { symbol ->
                        navController.navigate(Screen.StockDetail.createRoute(symbol))
                    }
                )
            }
            composable("news_tab") {
                NewsListScreen(
                    onNavigateBack = null
                )
            }
            composable("journal") {
                JournalScreen(
                    onAddEntry = {
                        navController.navigate(Screen.AddEditJournal.createRoute())
                    },
                    onEditEntry = { id ->
                        navController.navigate(Screen.AddEditJournal.createRoute(id))
                    }
                )
            }
            composable("profile") {
                ProfileScreen(
                    onLogout = {
                        viewModel.logout()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Main.route) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
