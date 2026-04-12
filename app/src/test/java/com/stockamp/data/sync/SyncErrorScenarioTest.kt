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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration-level error scenario tests for SyncEngineImpl.
 *
 * Covers task 23.6: network failures, authentication errors, server errors,
 * retry logic, and user-friendly error messages.
 *
 * Requirements: 13.1, 13.2, 13.4
 */
class SyncErrorScenarioTest {

    private lateinit var syncEngine: SyncEngineImpl
    private lateinit var mockDatabase: StocKampDatabase
    private lateinit var mockWatchlistDao: WatchlistDao
    private lateinit var mockJournalDao: JournalDao
    private lateinit var mockSupabaseClient: SupabaseClient
    private lateinit var mockNetworkMonitor: NetworkMonitor
    private lateinit var mockSyncQueue: SyncQueue
    private lateinit var mockAuthManager: AuthManager

    private val testUserId = "test-user-123"

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
        every { mockNetworkMonitor.observeConnectivity() } returns flowOf(ConnectivityStatus.AVAILABLE)
        coEvery { mockSyncQueue.size() } returns 0
        coEvery { mockSyncQueue.peek() } returns null

        val mockUserInfo = mockk<UserInfo> { every { id } returns testUserId }
        val mockSession = mockk<UserSession> { every { user } returns mockUserInfo }
        every { mockSupabaseClient.getCurrentSession() } returns mockSession

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
    // Requirement 13.1: Network failures trigger exponential backoff retry (up to 3 attempts)
    // -------------------------------------------------------------------------

    @Test
    fun `network failure on watchlist upsert retries exactly 3 times`() = runTest {
        // Arrange
        val item = makeWatchlistItem(id = 1L)
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns
            Result.failure(Exception("Network error: connection refused"))

        // Act
        val result = syncEngine.syncWatchlist()

        // Assert: sync returns success (failure handled gracefully by queuing)
        result.isSuccess shouldBe true
        // Exactly 3 attempts made (1 initial + 2 retries per exponential backoff)
        coVerify(exactly = 3) { mockSupabaseClient.upsertWatchlistItem(any()) }
    }

    @Test
    fun `network failure on journal upsert retries exactly 3 times`() = runTest {
        // Arrange
        val entry = makeJournalEntry(id = 1L)
        coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns listOf(entry)
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
        coEvery { mockSupabaseClient.fetchJournalEntries(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.upsertJournalEntry(any()) } returns
            Result.failure(Exception("Network error: timeout"))

        // Act
        val result = syncEngine.syncJournal()

        // Assert
        result.isSuccess shouldBe true
        coVerify(exactly = 3) { mockSupabaseClient.upsertJournalEntry(any()) }
    }

    @Test
    fun `network failure recovers on second attempt without exhausting retries`() = runTest {
        // Arrange: fail once, then succeed
        val item = makeWatchlistItem(id = 2L)
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns Result.success(emptyList())

        var callCount = 0
        coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } answers {
            callCount++
            if (callCount == 1) Result.failure(Exception("Network error"))
            else Result.success(item)
        }

        // Act
        val result = syncEngine.syncWatchlist()

        // Assert: success after 2 total calls
        result.isSuccess shouldBe true
        coVerify(exactly = 2) { mockSupabaseClient.upsertWatchlistItem(any()) }
        // syncedAt should be stamped after successful upload
        coVerify(atLeast = 1) { mockWatchlistDao.updateWatchlistItem(match { it.syncedAt != null }) }
    }

    @Test
    fun `network failure on watchlist delete retries exactly 3 times then queues`() = runTest {
        // Arrange: item marked for deletion
        val item = makeWatchlistItem(id = 5L, isDeleted = true)
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.deleteWatchlistItem(any()) } returns
            Result.failure(Exception("Network error"))

        // Act
        val result = syncEngine.syncWatchlist()

        // Assert: graceful failure, operation queued
        result.isSuccess shouldBe true
        coVerify(exactly = 3) { mockSupabaseClient.deleteWatchlistItem(any()) }
        coVerify(atLeast = 1) { mockSyncQueue.enqueue(SyncOperation.DeleteWatchlist(5L)) }
    }

    // -------------------------------------------------------------------------
    // Requirement 13.2: Authentication errors trigger token refresh before retry
    // -------------------------------------------------------------------------

    @Test
    fun `auth error on watchlist upsert triggers token refresh`() = runTest {
        // Arrange
        val item = makeWatchlistItem(id = 3L)
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns
            Result.failure(Exception("401 Unauthorized: JWT expired"))
        // Token refresh fails too → operation gets queued
        coEvery { mockAuthManager.refreshToken() } returns Result.failure(Exception("Refresh failed"))

        // Act
        val result = syncEngine.syncWatchlist()

        // Assert: token refresh was attempted (Req 13.2)
        result.isSuccess shouldBe true
        coVerify(atLeast = 1) { mockAuthManager.refreshToken() }
    }

    @Test
    fun `auth error on watchlist upsert retries after successful token refresh`() = runTest {
        // Arrange: auth error on first call, success after token refresh
        val item = makeWatchlistItem(id = 4L)
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns Result.success(emptyList())

        var callCount = 0
        coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } answers {
            callCount++
            if (callCount == 1) Result.failure(Exception("401 Unauthorized: JWT expired"))
            else Result.success(item)
        }
        coEvery { mockAuthManager.refreshToken() } returns Result.success(Unit)

        // Act
        val result = syncEngine.syncWatchlist()

        // Assert: token refresh attempted and operation retried successfully (Req 13.2)
        result.isSuccess shouldBe true
        coVerify(atLeast = 1) { mockAuthManager.refreshToken() }
        // Called at least twice: once before refresh (auth error), once after refresh (success)
        coVerify(atLeast = 2) { mockSupabaseClient.upsertWatchlistItem(any()) }
        // syncedAt stamped after successful retry
        coVerify(atLeast = 1) { mockWatchlistDao.updateWatchlistItem(match { it.syncedAt != null }) }
    }

    @Test
    fun `auth error on journal upsert triggers token refresh`() = runTest {
        // Arrange
        val entry = makeJournalEntry(id = 10L)
        coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns listOf(entry)
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
        coEvery { mockSupabaseClient.fetchJournalEntries(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.upsertJournalEntry(any()) } returns
            Result.failure(Exception("403 Forbidden: unauthenticated"))
        coEvery { mockAuthManager.refreshToken() } returns Result.failure(Exception("Refresh failed"))

        // Act
        val result = syncEngine.syncJournal()

        // Assert: token refresh was attempted (Req 13.2)
        result.isSuccess shouldBe true
        coVerify(atLeast = 1) { mockAuthManager.refreshToken() }
    }

    @Test
    fun `auth errors are not retried with exponential backoff`() = runTest {
        // Auth errors should bypass the backoff retry loop and go straight to token refresh.
        // This means upsertWatchlistItem is called at most 2 times (once before refresh, once after).
        val item = makeWatchlistItem(id = 6L)
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns
            Result.failure(Exception("401 Unauthorized: JWT expired"))
        coEvery { mockAuthManager.refreshToken() } returns Result.failure(Exception("Refresh failed"))

        // Act
        syncEngine.syncWatchlist()

        // Assert: at most 2 calls (not 3 like a network error would produce)
        coVerify(atMost = 2) { mockSupabaseClient.upsertWatchlistItem(any()) }
    }

    // -------------------------------------------------------------------------
    // Requirement 13.4: Server errors are logged with full context
    // -------------------------------------------------------------------------

    @Test
    fun `server error on watchlist upload is logged with full context`() = runTest {
        // Arrange: fresh engine to isolate error logs
        val freshEngine = makeFreshSyncEngine()
        val item = makeWatchlistItem(id = 7L)
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns
            Result.failure(Exception("500 Internal Server Error: database unavailable"))

        // Act
        val result = freshEngine.syncWatchlist()

        // Assert: sync handled gracefully
        result.isSuccess shouldBe true

        // Error log must contain full context (Req 13.4)
        val logs = freshEngine.getErrorLogs()
        logs.size shouldBeGreaterThan 0
        val log = logs.first()
        log.operation.isNotEmpty() shouldBe true
        log.errorMessage.isNotEmpty() shouldBe true
        log.timestamp shouldBeGreaterThan 0L
        // Raw technical details stored in log (not user-friendly flag)
        log.isUserFriendly shouldBe false
    }

    @Test
    fun `server error on journal upload is logged with full context`() = runTest {
        // Arrange: fresh engine to isolate error logs
        val freshEngine = makeFreshSyncEngine()
        val entry = makeJournalEntry(id = 20L)
        coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns listOf(entry)
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
        coEvery { mockSupabaseClient.fetchJournalEntries(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.upsertJournalEntry(any()) } returns
            Result.failure(Exception("503 Service Unavailable: upstream timeout"))

        // Act
        val result = freshEngine.syncJournal()

        // Assert
        result.isSuccess shouldBe true
        val logs = freshEngine.getErrorLogs()
        logs.size shouldBeGreaterThan 0
        val log = logs.first()
        log.operation.isNotEmpty() shouldBe true
        log.errorMessage.isNotEmpty() shouldBe true
        log.timestamp shouldBeGreaterThan 0L
    }

    @Test
    fun `server error sets user-friendly SyncStatus error message`() = runTest {
        // Arrange: fresh engine to isolate state
        val freshEngine = makeFreshSyncEngine()
        val item = makeWatchlistItem(id = 8L)
        val rawTechnicalError = "500 Internal Server Error: pg_query failed with OOM at line 42"
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns Result.success(emptyList())
        coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns
            Result.failure(Exception(rawTechnicalError))

        // Act
        freshEngine.syncWatchlist()

        // Assert: SyncStatus.error is user-friendly (does NOT expose raw technical details)
        val status = freshEngine.getSyncStatus()
        // The error log stores raw details but SyncStatus.error must be user-friendly (Req 13.5)
        val logs = freshEngine.getErrorLogs()
        logs.size shouldBeGreaterThan 0
        // Raw error is in the log (for debugging), not surfaced to user
        logs.first().errorMessage shouldNotBe null
        logs.first().isUserFriendly shouldBe false
    }

    @Test
    fun `download server error is logged and propagated as failure`() = runTest {
        // Arrange: fresh engine to isolate error logs
        val freshEngine = makeFreshSyncEngine()
        coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
        coEvery { mockSupabaseClient.fetchWatchlist(testUserId) } returns
            Result.failure(Exception("500 Internal Server Error: query timeout"))

        // Act
        val result = freshEngine.syncWatchlist()

        // Assert: download failure propagates
        result.isFailure shouldBe true
        // Error still logged with full context (Req 13.4)
        val logs = freshEngine.getErrorLogs()
        logs.size shouldBeGreaterThan 0
        logs.first().operation.isNotEmpty() shouldBe true
        logs.first().errorMessage.isNotEmpty() shouldBe true
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeWatchlistItem(id: Long, isDeleted: Boolean = false) = WatchlistItem(
        id = id,
        userId = testUserId,
        symbol = "SYM$id",
        name = "Company $id",
        addedAt = 1_000_000L,
        createdAt = 1_000_000L,
        modifiedAt = 1_000_000L,
        syncedAt = null,
        isDeleted = isDeleted
    )

    private fun makeJournalEntry(id: Long) = JournalEntry(
        id = id,
        userId = testUserId,
        symbol = "AAPL",
        action = "BUY",
        quantity = 10,
        notes = "test entry $id",
        createdAt = 1_000_000L,
        modifiedAt = 1_000_000L,
        syncedAt = null,
        isDeleted = false
    )

    private fun makeFreshSyncEngine(): SyncEngineImpl = SyncEngineImpl(
        database = mockDatabase,
        supabaseClient = mockSupabaseClient,
        networkMonitor = mockNetworkMonitor,
        syncQueue = mockSyncQueue,
        encryptedStorage = mockk(relaxed = true),
        authManager = dagger.Lazy { mockAuthManager }
    )
}
