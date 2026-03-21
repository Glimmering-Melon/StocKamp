package com.stockamp.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors network connectivity using ConnectivityManager.NetworkCallback.
 *
 * Emits [ConnectivityStatus] changes as the device network state changes:
 * - AVAILABLE: a suitable network is available
 * - LOSING: the network is about to be lost
 * - LOST: the network has been lost
 * - UNAVAILABLE: no network is available (initial state or request failed)
 *
 * Requirements: 1.6, 2.5, 8.5, 8.6
 */
@Singleton
class NetworkMonitorImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : NetworkMonitor {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun observeConnectivity(): Flow<ConnectivityStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(ConnectivityStatus.AVAILABLE)
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                trySend(ConnectivityStatus.LOSING)
            }

            override fun onLost(network: Network) {
                trySend(ConnectivityStatus.LOST)
            }

            override fun onUnavailable() {
                trySend(ConnectivityStatus.UNAVAILABLE)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Emit the current connectivity state immediately
        val currentStatus = if (isCurrentlyOnline()) {
            ConnectivityStatus.AVAILABLE
        } else {
            ConnectivityStatus.UNAVAILABLE
        }
        trySend(currentStatus)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    override fun isOnline(): Flow<Boolean> =
        observeConnectivity().map { it == ConnectivityStatus.AVAILABLE }

    private fun isCurrentlyOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
