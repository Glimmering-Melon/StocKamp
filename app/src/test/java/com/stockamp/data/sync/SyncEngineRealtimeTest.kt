package com.stockamp.data.sync

import com.stockamp.data.auth.AuthManager
import com.stockamp.data.local.JournalDao
import com.stockamp.data.local.StocKampDatabase
import com.stockamp.data.local.WatchlistDao
import com.stockamp.data.network.ConnectivityStatus
import com.stockamp.data.network.NetworkMonitor
import com.stockamp.data.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.user.UserInfo
import io.github.jan.supabase.gotrue.user.UserSession
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for realtime subscription lifecycle in SyncEngineImpl.
 *
 * Property 14: Realtime Subscriptions Active When Online
 * Property 15: Offline Transition Unsubscribes Realtime
 * Property 16: Online Transition Resubscribes Realtime
 *
 * Validates: Requirements 8.1, 8.2, 8.5, 8.6
 *
 * Feature: supabase-integration
 */
class SyncEngineRealtimeTest {

    private lateinit var mockDatabase: StocKampDatabase
    private lateinit var mockWatchlistDao: WatchlistDao
    private lateinit var mockJournalDao: JournalDao
    private lateinit var mockSupabaseClient: SupabaseClient
    private lateinit var mockNetworkMonitor: NetworkMonitor
    private lateinit var mockSyncQueue: SyncQueue
    private lateinit var mockAuthManager: AuthManager

    /** Shared flow used to control connectivity emissions in each test. */
    private lateinit var connectivityFlow: MutableSharedFlow<ConnectivityStatus>

    @Before
    fun setup() {
        mockDatabase = mockk(relaxed = true)
        mockWatchlistDao = mockk(relaxed = true)
        mockJournalDao = mockk(relaxed = true)
        mockSupabaseClient = mockk(relaxed = true)
        mockNetworkMonitor = mockk(relaxed = true)
        mockSyncQueue = mockk(relaxed = true)
        mockAuthManager = mockk(relaxed = true)

        every { mockDatabase.watchlistDao() } returns mockWatchlistDao
        every { mockDatabase.journalDao() } returns mockJournalDao
        coEvery { mockSyncQueue.size() } returns 0
        coEvery { mockSyncQueue.peek() } returns null
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mockAuthenticatedSession(userId: String = "user-123") {
        val mockUserInfo = mockk<UserInfo> { every { id } returns userId }
        val mockSession = mockk<UserSession> { every { user } returns mockUserInfo }
        every { mockSupabaseClient.getCurrentSession() } returns mockSession
    }

    /**
     * Builds a SyncEngineImpl backed by a [MutableSharedFlow] so tests can
     * push connectivity events after construction.
     */
    private fun buildEngineWithFlow(flow: MutableSharedFlow<ConnectivityStatus>): SyncEngineImpl {
        every { mockNetworkMonitor.observeConnectivity() } returns flow
        val lazyAuthManager = dagger.Lazy { mockAuthManager }
        return SyncEngineImpl(
            database = mockDatabase,
            supabaseClient = mockSupabaseClient,
            networkMonitor = mockNetworkMonitor,
            syncQueue = mockSyncQueue,
            encryptedStorage = mockk(relaxed = true),
            authManager = lazyAuthManager
        )
    }

    // -------------------------------------------------------------------------
    // Property 14: Realtime Subscriptions Active When Online
    // -------------------------------------------------------------------------

    /**
     * Property 14: When the device is online (AVAILABLE) and the user is
     * authenticated, the SyncEngine subscribes to both watchlist and journal
     * realtime channels.
     *
     * Validates: Requirements 8.1, 8.2
     */
    @Test
    fun `property 14 - subscribeToWatchlistChanges and subscribeToJournalChanges called when online and authenticated`() =
        runTest {
            mockAuthenticatedSession("user-abc")

            val flow = MutableSharedFlow<ConnectivityStatus>()
            val engine = buildEngineWithFlow(flow)

            // Emit AVAILABLE – engine should subscribe
            flow.emit(ConnectivityStatus.AVAILABLE)

            verify(atLeast = 1) { mockSupabaseClient.subscribeToWatchlistChanges(any(), any()) }
            verify(atLeast = 1) { mockSupabaseClient.subscribeToJournalChanges(any(), any()) }
        }

    // -------------------------------------------------------------------------
    // Property 15: Offline Transition Unsubscribes Realtime
    // -------------------------------------------------------------------------

    /**
     * Property 15: When connectivity transitions to LOST, the SyncEngine calls
     * unsubscribeAll() on the SupabaseClient to tear down realtime channels.
     *
     * Validates: Requirements 8.5
     */
    @Test
    fun `property 15 - unsubscribeAll called when connectivity transitions to LOST`() = runTest {
        mockAuthenticatedSession()

        val flow = MutableSharedFlow<ConnectivityStatus>()
        val engine = buildEngineWithFlow(flow)

        // Go offline
        flow.emit(ConnectivityStatus.LOST)

        verify(atLeast = 1) { mockSupabaseClient.unsubscribeAll() }
    }

    // -------------------------------------------------------------------------
    // Property 15 (extended): UNAVAILABLE also triggers unsubscribe
    // -------------------------------------------------------------------------

    /**
     * Property 15 (extended): UNAVAILABLE connectivity status should also cause
     * the SyncEngine to unsubscribe from realtime channels, not just LOST.
     *
     * Validates: Requirements 8.5
     */
    @Test
    fun `property 15 extended - unsubscribeAll called when connectivity is UNAVAILABLE`() = runTest {
        mockAuthenticatedSession()

        val flow = MutableSharedFlow<ConnectivityStatus>()
        val engine = buildEngineWithFlow(flow)

        flow.emit(ConnectivityStatus.UNAVAILABLE)

        verify(atLeast = 1) { mockSupabaseClient.unsubscribeAll() }
    }

    // -------------------------------------------------------------------------
    // Property 16: Online Transition Resubscribes Realtime
    // -------------------------------------------------------------------------

    /**
     * Property 16: When connectivity transitions from LOST back to AVAILABLE
     * (and the user is authenticated), the SyncEngine resubscribes to realtime
     * channels by calling subscribeToWatchlistChanges again.
     *
     * Validates: Requirements 8.6
     */
    @Test
    fun `property 16 - subscribeToWatchlistChanges called again after reconnecting`() = runTest {
        mockAuthenticatedSession()

        val flow = MutableSharedFlow<ConnectivityStatus>()
        val engine = buildEngineWithFlow(flow)

        // First go offline, then come back online
        flow.emit(ConnectivityStatus.LOST)
        flow.emit(ConnectivityStatus.AVAILABLE)

        // subscribeToWatchlistChanges should have been called at least once
        // (on the AVAILABLE emission after LOST)
        verify(atLeast = 1) { mockSupabaseClient.subscribeToWatchlistChanges(any(), any()) }
    }

    /**
     * Property 16 (journal): When connectivity transitions from LOST back to AVAILABLE,
     * the SyncEngine also resubscribes to journal realtime channels.
     *
     * Validates: Requirements 8.2, 8.6
     */
    @Test
    fun `property 16 - subscribeToJournalChanges called again after reconnecting`() = runTest {
        mockAuthenticatedSession()

        val flow = MutableSharedFlow<ConnectivityStatus>()
        val engine = buildEngineWithFlow(flow)

        flow.emit(ConnectivityStatus.LOST)
        flow.emit(ConnectivityStatus.AVAILABLE)

        verify(atLeast = 1) { mockSupabaseClient.subscribeToJournalChanges(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Requirement 8.3: Realtime events update Room database
    // -------------------------------------------------------------------------

    /**
     * Requirement 8.3: When a realtime INSERT event is received for a WatchlistItem,
     * the SyncEngine should invoke the watchlist DAO to persist the change in Room.
     *
     * This test captures the onEvent callback registered with subscribeToWatchlistChanges
     * and invokes it directly to verify the DAO is called.
     *
     * Validates: Requirements 8.3
     */
    @Test
    fun `req 8-3 - realtime watchlist INSERT event triggers Room insert`() = runTest {
        mockAuthenticatedSession()

        val capturedCallback = slot<(com.stockamp.data.model.WatchlistItem, com.stockamp.data.supabase.ChangeType) -> Unit>()
        every { mockSupabaseClient.subscribeToWatchlistChanges(any(), capture(capturedCallback)) } just Runs

        val flow = MutableSharedFlow<ConnectivityStatus>()
        val engine = buildEngineWithFlow(flow)

        // Trigger subscription
        flow.emit(ConnectivityStatus.AVAILABLE)

        // Simulate a realtime INSERT event
        val incomingItem = com.stockamp.data.model.WatchlistItem(
            id = 99L,
            userId = "user-123",
            symbol = "AAPL",
            name = "Apple Inc.",
            addedAt = System.currentTimeMillis()
        )
        capturedCallback.captured.invoke(incomingItem, com.stockamp.data.supabase.ChangeType.INSERT)

        // Allow the launched coroutine inside the callback to execute
        testScheduler.advanceUntilIdle()

        coVerify(atLeast = 1) { mockWatchlistDao.insertWatchlistItem(any()) }
    }

    /**
     * Requirement 8.3: When a realtime UPDATE event is received for a WatchlistItem,
     * the SyncEngine should invoke the watchlist DAO to update the record in Room.
     *
     * Validates: Requirements 8.3
     */
    @Test
    fun `req 8-3 - realtime watchlist UPDATE event triggers Room update`() = runTest {
        mockAuthenticatedSession()

        val capturedCallback = slot<(com.stockamp.data.model.WatchlistItem, com.stockamp.data.supabase.ChangeType) -> Unit>()
        every { mockSupabaseClient.subscribeToWatchlistChanges(any(), capture(capturedCallback)) } just Runs

        val existingItem = com.stockamp.data.model.WatchlistItem(
            id = 1L, userId = "user-123", symbol = "TSLA", name = "Tesla", addedAt = 0L
        )
        coEvery { mockWatchlistDao.getWatchlistItemById(1L) } returns existingItem

        val flow = MutableSharedFlow<ConnectivityStatus>()
        val engine = buildEngineWithFlow(flow)

        flow.emit(ConnectivityStatus.AVAILABLE)

        val updatedItem = existingItem.copy(name = "Tesla Inc.")
        capturedCallback.captured.invoke(updatedItem, com.stockamp.data.supabase.ChangeType.UPDATE)

        testScheduler.advanceUntilIdle()

        coVerify(atLeast = 1) { mockWatchlistDao.updateWatchlistItem(any()) }
    }

    /**
     * Requirement 8.3: When a realtime DELETE event is received for a WatchlistItem,
     * the SyncEngine should invoke the watchlist DAO to remove the record from Room.
     *
     * Validates: Requirements 8.3
     */
    @Test
    fun `req 8-3 - realtime watchlist DELETE event triggers Room delete`() = runTest {
        mockAuthenticatedSession()

        val capturedCallback = slot<(com.stockamp.data.model.WatchlistItem, com.stockamp.data.supabase.ChangeType) -> Unit>()
        every { mockSupabaseClient.subscribeToWatchlistChanges(any(), capture(capturedCallback)) } just Runs

        val existingItem = com.stockamp.data.model.WatchlistItem(
            id = 5L, userId = "user-123", symbol = "GOOG", name = "Alphabet", addedAt = 0L
        )
        coEvery { mockWatchlistDao.getWatchlistItemById(5L) } returns existingItem

        val flow = MutableSharedFlow<ConnectivityStatus>()
        val engine = buildEngineWithFlow(flow)

        flow.emit(ConnectivityStatus.AVAILABLE)

        capturedCallback.captured.invoke(existingItem, com.stockamp.data.supabase.ChangeType.DELETE)

        testScheduler.advanceUntilIdle()

        coVerify(atLeast = 1) { mockWatchlistDao.deleteWatchlistItem(any()) }
    }
}
