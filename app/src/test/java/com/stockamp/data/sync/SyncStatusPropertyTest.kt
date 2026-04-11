package com.stockamp.data.sync

import com.stockamp.data.auth.AuthManager
import com.stockamp.data.local.JournalDao
import com.stockamp.data.local.StocKampDatabase
import com.stockamp.data.local.WatchlistDao
import com.stockamp.data.model.JournalEntry
import com.stockamp.data.model.WatchlistItem
import com.stockamp.data.network.ConnectivityStatus
import com.stockamp.data.network.NetworkMonitor
import com.stockamp.data.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.user.UserInfo
import io.github.jan.supabase.gotrue.user.UserSession
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Property-based tests for SyncEngine sync status visibility.
 *
 * Feature: supabase-integration
 */
class SyncStatusPropertyTest {

    private lateinit var syncEngine: SyncEngineImpl
    private lateinit var mockDatabase: StocKampDatabase
    private lateinit var mockWatchlistDao: WatchlistDao
    private lateinit var mockJournalDao: JournalDao
    private lateinit var mockSupabaseClient: SupabaseClient
    private lateinit var mockNetworkMonitor: NetworkMonitor
    private lateinit var mockSyncQueue: SyncQueue
    private lateinit var mockAuthManager: AuthManager

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

        // Default: no unsynced items
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
        coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()

        // Default: queue is empty
        coEvery { mockSyncQueue.size() } returns 0
        coEvery { mockSyncQueue.peek() } returns null

        // Default: network is online
        every { mockNetworkMonitor.observeConnectivity() } returns flowOf(ConnectivityStatus.AVAILABLE)

        syncEngine = SyncEngineImpl(
            database = mockDatabase,
            supabaseClient = mockSupabaseClient,
            networkMonitor = mockNetworkMonitor,
            syncQueue = mockSyncQueue,
            encryptedStorage = mockk(relaxed = true),
            authManager = dagger.Lazy { mockAuthManager }
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mockAuthenticatedSession(userId: String) {
        val mockUserInfo = mockk<UserInfo> { every { id } returns userId }
        val mockSession = mockk<UserSession> { every { user } returns mockUserInfo }
        every { mockSupabaseClient.getCurrentSession() } returns mockSession
    }

    private fun makeSyncEngine(): SyncEngineImpl = SyncEngineImpl(
        database = mockDatabase,
        supabaseClient = mockSupabaseClient,
        networkMonitor = mockNetworkMonitor,
        syncQueue = mockSyncQueue,
        encryptedStorage = mockk(relaxed = true),
        authManager = dagger.Lazy { mockAuthManager }
    )

    // -------------------------------------------------------------------------
    // Property 18: Sync Status Reflects Current State
    // -------------------------------------------------------------------------

    /**
     * Property 18 (IDLE after successful full sync): After performFullSync() succeeds,
     * getSyncStatus() emits a SyncStatus with state == IDLE.
     *
     * Validates: Requirements 10.5, 15.1
     *
     * Feature: supabase-integration, Property 18: Sync Status Reflects Current State
     */
    @Test
    fun `property 18 - getSyncStatus emits IDLE state after successful performFullSync`() = runTest {
        checkAll(50, Arb.string(5..15)) { userId ->
            val engine = makeSyncEngine()
            mockAuthenticatedSession(userId)

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(emptyList())
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0

            val result = engine.performFullSync()

            result.isSuccess shouldBe true
            val status = engine.getSyncStatus().first()
            status.state shouldBe SyncState.IDLE
        }
    }

    /**
     * Property 18 (ERROR state after failed full sync): After performFullSync() fails
     * (e.g. download error), getSyncStatus() emits a SyncStatus with state == ERROR.
     *
     * Validates: Requirements 10.5, 15.1
     *
     * Feature: supabase-integration, Property 18: Sync Status Reflects Current State
     */
    @Test
    fun `property 18 - getSyncStatus emits ERROR state after failed performFullSync`() = runTest {
        checkAll(50, Arb.string(5..15)) { userId ->
            val engine = makeSyncEngine()
            mockAuthenticatedSession(userId)

            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0

            // Simulate a download failure
            val serverError = Exception("500 Internal Server Error")
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.failure(serverError)

            val result = engine.performFullSync()

            result.isFailure shouldBe true
            val status = engine.getSyncStatus().first()
            status.state shouldBe SyncState.ERROR
        }
    }

    /**
     * Property 18 (OFFLINE state when network is unavailable): When the network monitor
     * emits UNAVAILABLE, getSyncStatus() reflects OFFLINE state.
     *
     * Validates: Requirements 10.5, 15.1
     *
     * Feature: supabase-integration, Property 18: Sync Status Reflects Current State
     */
    @Test
    fun `property 18 - getSyncStatus emits OFFLINE state when network is unavailable`() = runTest {
        every { mockNetworkMonitor.observeConnectivity() } returns flowOf(ConnectivityStatus.UNAVAILABLE)

        val engine = makeSyncEngine()

        // Allow the init coroutine to process the connectivity event
        val status = engine.getSyncStatus().first()
        status.state shouldBe SyncState.OFFLINE
    }

    /**
     * Property 18 (error message is non-null on ERROR state): When getSyncStatus()
     * emits ERROR state, the error field is non-null and non-empty.
     *
     * Validates: Requirements 10.5, 15.1
     *
     * Feature: supabase-integration, Property 18: Sync Status Reflects Current State
     */
    @Test
    fun `property 18 - getSyncStatus error field is non-null when state is ERROR`() = runTest {
        checkAll(50, Arb.string(5..15)) { userId ->
            val engine = makeSyncEngine()
            mockAuthenticatedSession(userId)

            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.failure(Exception("Server error"))

            engine.performFullSync()

            val status = engine.getSyncStatus().first()
            status.state shouldBe SyncState.ERROR
            status.error shouldNotBe null
            status.error!!.isNotEmpty() shouldBe true
        }
    }

    // -------------------------------------------------------------------------
    // Property 30: Sync Progress Shows Pending Count
    // -------------------------------------------------------------------------

    /**
     * Property 30 (pending count reflects enqueued operations): When operations are
     * enqueued via enqueueSyncOperation(), getPendingOperationsCount() reflects the
     * number of enqueued items.
     *
     * Validates: Requirements 15.2
     *
     * Feature: supabase-integration, Property 30: Sync Progress Shows Pending Count
     */
    @Test
    fun `property 30 - getPendingOperationsCount reflects enqueued operation count`() = runTest {
        checkAll(50, Arb.list(Arb.long(1L..9999L), 1..5)) { itemIds ->
            val engine = makeSyncEngine()

            var queueSize = 0
            coEvery { mockSyncQueue.size() } answers { queueSize }
            coEvery { mockSyncQueue.enqueue(any()) } answers { queueSize++ }

            for (id in itemIds) {
                engine.enqueueSyncOperation(SyncOperation.UpsertWatchlist(id))
            }

            val pendingCount = engine.getPendingOperationsCount().first()
            pendingCount shouldBe itemIds.size
        }
    }

    /**
     * Property 30 (SyncStatus.pendingCount matches queue size after failed sync):
     * When sync operations fail and are queued, SyncStatus.pendingCount equals the
     * number of failed operations added to the queue.
     *
     * Validates: Requirements 15.2
     *
     * Feature: supabase-integration, Property 30: Sync Progress Shows Pending Count
     */
    @Test
    fun `property 30 - SyncStatus pendingCount matches queue size after failed uploads`() = runTest {
        checkAll(30, Arb.string(5..15), Arb.list(Arb.string(3..5), 1..4)) { userId, symbols ->
            val engine = makeSyncEngine()
            mockAuthenticatedSession(userId)

            val items = symbols.mapIndexed { i, sym ->
                WatchlistItem(
                    id = (i + 1).toLong(),
                    userId = userId,
                    symbol = sym.uppercase(),
                    name = "Company $sym",
                    addedAt = 1_000_000L,
                    createdAt = 1_000_000L,
                    modifiedAt = 1_000_000L,
                    syncedAt = null,
                    isDeleted = false
                )
            }

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(emptyList())
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns items
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns Result.failure(Exception("Network error"))

            var enqueuedCount = 0
            coEvery { mockSyncQueue.enqueue(any()) } answers { enqueuedCount++ }
            coEvery { mockSyncQueue.size() } answers { enqueuedCount }

            engine.performFullSync()

            val status = engine.getSyncStatus().first()
            status.pendingCount shouldBe enqueuedCount
        }
    }

    /**
     * Property 30 (pending count is zero after successful sync): After performFullSync()
     * succeeds with no queued operations, getPendingOperationsCount() emits 0.
     *
     * Validates: Requirements 15.2
     *
     * Feature: supabase-integration, Property 30: Sync Progress Shows Pending Count
     */
    @Test
    fun `property 30 - getPendingOperationsCount is zero after successful full sync`() = runTest {
        checkAll(50, Arb.string(5..15)) { userId ->
            val engine = makeSyncEngine()
            mockAuthenticatedSession(userId)

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(emptyList())
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0

            val result = engine.performFullSync()

            result.isSuccess shouldBe true
            val pendingCount = engine.getPendingOperationsCount().first()
            pendingCount shouldBe 0
        }
    }

    // -------------------------------------------------------------------------
    // Property 31: Successful Sync Updates Timestamp
    // -------------------------------------------------------------------------

    /**
     * Property 31 (getLastSyncTimestamp is non-null after successful full sync):
     * After performFullSync() succeeds, getLastSyncTimestamp() returns a non-null
     * timestamp greater than 0.
     *
     * Validates: Requirements 15.3
     *
     * Feature: supabase-integration, Property 31: Successful Sync Updates Timestamp
     */
    @Test
    fun `property 31 - getLastSyncTimestamp returns non-null timestamp after successful performFullSync`() = runTest {
        checkAll(50, Arb.string(5..15)) { userId ->
            val engine = makeSyncEngine()
            mockAuthenticatedSession(userId)

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(emptyList())
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0

            val result = engine.performFullSync()

            result.isSuccess shouldBe true
            val timestamp = engine.getLastSyncTimestamp().first()
            timestamp shouldNotBe null
            timestamp!! shouldBeGreaterThan 0L
        }
    }

    /**
     * Property 31 (SyncStatus.lastSync is set after successful full sync):
     * After performFullSync() succeeds, SyncStatus.lastSync is non-null and > 0.
     *
     * Validates: Requirements 15.3
     *
     * Feature: supabase-integration, Property 31: Successful Sync Updates Timestamp
     */
    @Test
    fun `property 31 - SyncStatus lastSync is set after successful performFullSync`() = runTest {
        checkAll(50, Arb.string(5..15)) { userId ->
            val engine = makeSyncEngine()
            mockAuthenticatedSession(userId)

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(emptyList())
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0

            val result = engine.performFullSync()

            result.isSuccess shouldBe true
            val status = engine.getSyncStatus().first()
            status.lastSync shouldNotBe null
            status.lastSync!! shouldBeGreaterThan 0L
        }
    }

    /**
     * Property 31 (timestamp is null before any sync): Before any sync is performed,
     * getLastSyncTimestamp() emits null.
     *
     * Validates: Requirements 15.3
     *
     * Feature: supabase-integration, Property 31: Successful Sync Updates Timestamp
     */
    @Test
    fun `property 31 - getLastSyncTimestamp is null before any sync`() = runTest {
        val engine = makeSyncEngine()

        val timestamp = engine.getLastSyncTimestamp().first()
        timestamp shouldBe null
    }

    /**
     * Property 31 (timestamp not updated after failed sync): After performFullSync()
     * fails, getLastSyncTimestamp() remains null (no successful sync has occurred).
     *
     * Validates: Requirements 15.3
     *
     * Feature: supabase-integration, Property 31: Successful Sync Updates Timestamp
     */
    @Test
    fun `property 31 - getLastSyncTimestamp remains null after failed performFullSync`() = runTest {
        checkAll(50, Arb.string(5..15)) { userId ->
            val engine = makeSyncEngine()
            mockAuthenticatedSession(userId)

            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.failure(Exception("Server error"))

            engine.performFullSync()

            val timestamp = engine.getLastSyncTimestamp().first()
            timestamp shouldBe null
        }
    }

    // -------------------------------------------------------------------------
    // Property 32: Sync Errors Display Indicator
    // -------------------------------------------------------------------------

    /**
     * Property 32 (ERROR state when sync fails): When a sync error occurs during
     * performFullSync(), getSyncStatus() emits ERROR state with a non-null error message.
     *
     * Validates: Requirements 15.4
     *
     * Feature: supabase-integration, Property 32: Sync Errors Display Indicator
     */
    @Test
    fun `property 32 - getSyncStatus emits ERROR state with non-null error when sync fails`() = runTest {
        checkAll(50, Arb.string(5..15)) { userId ->
            val engine = makeSyncEngine()
            mockAuthenticatedSession(userId)

            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.failure(Exception("500 Server Error"))

            engine.performFullSync()

            val status = engine.getSyncStatus().first()
            status.state shouldBe SyncState.ERROR
            status.error shouldNotBe null
            status.error!!.isNotEmpty() shouldBe true
        }
    }

    /**
     * Property 32 (error indicator cleared after successful sync): After a failed sync
     * followed by a successful sync, getSyncStatus() emits IDLE state with null error.
     *
     * Validates: Requirements 15.4
     *
     * Feature: supabase-integration, Property 32: Sync Errors Display Indicator
     */
    @Test
    fun `property 32 - error indicator is cleared after subsequent successful sync`() = runTest {
        checkAll(30, Arb.string(5..15)) { userId ->
            val engine = makeSyncEngine()
            mockAuthenticatedSession(userId)

            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0

            // First sync fails
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.failure(Exception("Server error"))
            engine.performFullSync()
            engine.getSyncStatus().first().state shouldBe SyncState.ERROR

            // Second sync succeeds
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(emptyList())
            val result = engine.performFullSync()

            result.isSuccess shouldBe true
            val status = engine.getSyncStatus().first()
            status.state shouldBe SyncState.IDLE
            status.error shouldBe null
        }
    }

    /**
     * Property 32 (upload error sets ERROR state): When an upload fails during
     * syncWatchlist(), getSyncStatus() emits ERROR state with a non-null error message.
     *
     * Validates: Requirements 15.4
     *
     * Feature: supabase-integration, Property 32: Sync Errors Display Indicator
     */
    @Test
    fun `property 32 - upload failure sets ERROR state with non-null error message`() = runTest {
        checkAll(30, Arb.string(5..15), Arb.string(3..5)) { userId, symbol ->
            val engine = makeSyncEngine()
            mockAuthenticatedSession(userId)

            val item = WatchlistItem(
                id = 1L,
                userId = userId,
                symbol = symbol.uppercase(),
                name = "Company $symbol",
                addedAt = 1_000_000L,
                createdAt = 1_000_000L,
                modifiedAt = 1_000_000L,
                syncedAt = null,
                isDeleted = false
            )

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)
            coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns Result.failure(Exception("Network error"))
            coEvery { mockSyncQueue.size() } returns 0
            coEvery { mockSyncQueue.enqueue(any()) } returns Unit

            engine.syncWatchlist()

            val status = engine.getSyncStatus().first()
            status.state shouldBe SyncState.ERROR
            status.error shouldNotBe null
            status.error!!.isNotEmpty() shouldBe true
        }
    }
}
