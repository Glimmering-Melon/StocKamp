package com.stockamp.data.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for NetworkMonitor using a FakeNetworkMonitor.
 *
 * Since NetworkMonitorImpl depends on Android's ConnectivityManager (a system service),
 * we test the contract via a fake implementation that allows manual state control.
 *
 * Requirements: 1.6, 2.5
 */

/**
 * Fake implementation of [NetworkMonitor] for testing.
 * Allows manually setting connectivity state without Android system services.
 */
class FakeNetworkMonitor : NetworkMonitor {

    private val _status = MutableStateFlow(ConnectivityStatus.UNAVAILABLE)

    /** Set the current connectivity status. */
    fun setStatus(status: ConnectivityStatus) {
        _status.value = status
    }

    /** Convenience helpers */
    fun setOnline() = setStatus(ConnectivityStatus.AVAILABLE)
    fun setOffline() = setStatus(ConnectivityStatus.UNAVAILABLE)

    override fun observeConnectivity(): Flow<ConnectivityStatus> =
        _status.distinctUntilChanged()

    override fun isOnline(): Flow<Boolean> =
        observeConnectivity().map { it == ConnectivityStatus.AVAILABLE }
}

class NetworkMonitorTest {

    private lateinit var networkMonitor: FakeNetworkMonitor

    @Before
    fun setup() {
        networkMonitor = FakeNetworkMonitor()
    }

    // ── isOnline() tests ──────────────────────────────────────────────────────

    @Test
    fun `isOnline emits true when status is AVAILABLE`() = runTest {
        networkMonitor.setStatus(ConnectivityStatus.AVAILABLE)

        val online = networkMonitor.isOnline().first()

        assertTrue(online)
    }

    @Test
    fun `isOnline emits false when status is UNAVAILABLE`() = runTest {
        networkMonitor.setStatus(ConnectivityStatus.UNAVAILABLE)

        val online = networkMonitor.isOnline().first()

        assertFalse(online)
    }

    @Test
    fun `isOnline emits false when status is LOST`() = runTest {
        networkMonitor.setStatus(ConnectivityStatus.LOST)

        val online = networkMonitor.isOnline().first()

        assertFalse(online)
    }

    @Test
    fun `isOnline emits false when status is LOSING`() = runTest {
        networkMonitor.setStatus(ConnectivityStatus.LOSING)

        val online = networkMonitor.isOnline().first()

        assertFalse(online)
    }

    // ── observeConnectivity() tests ───────────────────────────────────────────

    @Test
    fun `observeConnectivity emits AVAILABLE when network becomes available`() = runTest {
        networkMonitor.setStatus(ConnectivityStatus.UNAVAILABLE)

        val emissions = mutableListOf<ConnectivityStatus>()
        val job = launch {
            networkMonitor.observeConnectivity().take(2).toList(emissions)
        }

        networkMonitor.setStatus(ConnectivityStatus.AVAILABLE)
        job.join()

        assertTrue(emissions.contains(ConnectivityStatus.AVAILABLE))
        assertEquals(ConnectivityStatus.AVAILABLE, emissions.last())
    }

    @Test
    fun `observeConnectivity emits LOST when network is lost`() = runTest {
        networkMonitor.setStatus(ConnectivityStatus.AVAILABLE)

        val emissions = mutableListOf<ConnectivityStatus>()
        val job = launch {
            networkMonitor.observeConnectivity().take(2).toList(emissions)
        }

        networkMonitor.setStatus(ConnectivityStatus.LOST)
        job.join()

        assertTrue(emissions.contains(ConnectivityStatus.LOST))
        assertEquals(ConnectivityStatus.LOST, emissions.last())
    }

    // ── State transition tests ────────────────────────────────────────────────

    @Test
    fun `state transitions AVAILABLE to LOSING to LOST are emitted in order`() = runTest {
        networkMonitor.setStatus(ConnectivityStatus.AVAILABLE)

        val emissions = mutableListOf<ConnectivityStatus>()
        val job = launch {
            networkMonitor.observeConnectivity().take(3).toList(emissions)
        }

        networkMonitor.setStatus(ConnectivityStatus.LOSING)
        networkMonitor.setStatus(ConnectivityStatus.LOST)
        job.join()

        assertEquals(
            listOf(ConnectivityStatus.AVAILABLE, ConnectivityStatus.LOSING, ConnectivityStatus.LOST),
            emissions
        )
    }

    @Test
    fun `duplicate status emissions are deduplicated by distinctUntilChanged`() = runTest {
        networkMonitor.setStatus(ConnectivityStatus.AVAILABLE)

        val emissions = mutableListOf<ConnectivityStatus>()
        val job = launch {
            // We expect only 2 distinct values: AVAILABLE then LOST
            networkMonitor.observeConnectivity().take(2).toList(emissions)
        }

        // Setting the same status twice should not produce a duplicate emission
        networkMonitor.setStatus(ConnectivityStatus.AVAILABLE)
        networkMonitor.setStatus(ConnectivityStatus.LOST)
        job.join()

        assertEquals(
            listOf(ConnectivityStatus.AVAILABLE, ConnectivityStatus.LOST),
            emissions
        )
    }

    @Test
    fun `isOnline transitions from true to false when network is lost`() = runTest {
        networkMonitor.setStatus(ConnectivityStatus.AVAILABLE)

        val emissions = mutableListOf<Boolean>()
        val job = launch {
            networkMonitor.isOnline().take(2).toList(emissions)
        }

        networkMonitor.setStatus(ConnectivityStatus.LOST)
        job.join()

        assertEquals(listOf(true, false), emissions)
    }

    @Test
    fun `isOnline transitions from false to true when network becomes available`() = runTest {
        networkMonitor.setStatus(ConnectivityStatus.UNAVAILABLE)

        val emissions = mutableListOf<Boolean>()
        val job = launch {
            networkMonitor.isOnline().take(2).toList(emissions)
        }

        networkMonitor.setStatus(ConnectivityStatus.AVAILABLE)
        job.join()

        assertEquals(listOf(false, true), emissions)
    }
}
