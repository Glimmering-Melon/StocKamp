package com.stockamp.data.network

import kotlinx.coroutines.flow.Flow

/**
 * Monitors network connectivity to trigger sync operations.
 * This interface will be fully implemented in task 6.
 */
interface NetworkMonitor {
    fun isOnline(): Flow<Boolean>
    fun observeConnectivity(): Flow<ConnectivityStatus>
}

/**
 * Represents the current network connectivity status.
 */
enum class ConnectivityStatus {
    AVAILABLE, UNAVAILABLE, LOSING, LOST
}
