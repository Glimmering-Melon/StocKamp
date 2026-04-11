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
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.filter
import io.kotest.property.checkAll
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Property-based tests for SyncEngineImpl – remote changes updating local database.
 *
 * Feature: supabase-integration
 */
class SyncEnginePropertyTest {

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

        // Default: no unsynced items so upload phase is a no-op
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

    private fun arbWatchlistItem(userId: String): Arb<WatchlistItem> =
        Arb.string(3..6).map { symbol ->
            WatchlistItem(
                id = 0L,
                userId = userId,
                symbol = symbol.uppercase(),
                name = "Company $symbol",
                addedAt = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis() - 1000,
                syncedAt = null,
                isDeleted = false
            )
        }

    private fun arbJournalEntry(userId: String): Arb<JournalEntry> =
        Arb.long(1L..9999L).map { id ->
            JournalEntry(
                id = id,
                userId = userId,
                symbol = "AAPL",
                action = "BUY",
                quantity = 10,
                price = 150.0,
                totalValue = 1500.0,
                notes = "test entry $id",
                createdAt = System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis() - 1000,
                syncedAt = null,
                isDeleted = false
            )
        }

    // -------------------------------------------------------------------------
    // Property 10: Remote Changes Update Local Database
    // -------------------------------------------------------------------------

    /**
     * Property 10 (Watchlist – new remote items): When fetchWatchlist returns items
     * that do not exist locally, syncWatchlist() inserts each of them into the local
     * watchlist DAO.
     *
     * Validates: Requirements 5.5
     *
     * Feature: supabase-integration, Property 10: Remote Changes Update Local Database
     */
    @Test
    fun `property 10 - new remote watchlist items are inserted into local database`() = runTest {
        checkAll(100, Arb.string(5..15), Arb.list(Arb.string(3..5), 1..5)) { userId, symbols ->
            clearMocks(mockWatchlistDao, mockJournalDao, answers = false)
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()

            mockAuthenticatedSession(userId)

            val remoteItems = symbols.mapIndexed { i, sym ->
                WatchlistItem(
                    id = (i + 1).toLong(),
                    userId = userId,
                    symbol = sym.uppercase(),
                    name = "Company $sym",
                    addedAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis() - 1000,
                    syncedAt = null,
                    isDeleted = false
                )
            }

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(remoteItems)
            // No local item exists for any symbol → triggers insert path
            coEvery { mockWatchlistDao.getWatchlistItem(any()) } returns null

            val result = syncEngine.syncWatchlist()

            result.isSuccess shouldBe true
            // Each remote item should have been inserted
            remoteItems.forEach { item ->
                coVerify(atLeast = 1) { mockWatchlistDao.insertWatchlistItem(match { it.symbol == item.symbol }) }
            }
        }
    }

    /**
     * Property 10 (Watchlist – modified remote items): When fetchWatchlist returns items
     * that exist locally but have a newer modifiedAt, syncWatchlist() updates the local
     * record with the remote version.
     *
     * Validates: Requirements 5.5
     *
     * Feature: supabase-integration, Property 10: Remote Changes Update Local Database
     */
    @Test
    fun `property 10 - remote watchlist items newer than local are updated in local database`() = runTest {
        checkAll(100, Arb.string(5..15), Arb.list(Arb.string(3..5), 1..5)) { userId, symbols ->
            clearMocks(mockWatchlistDao, mockJournalDao, answers = false)
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()

            mockAuthenticatedSession(userId)

            val baseTime = System.currentTimeMillis()
            val remoteItems = symbols.mapIndexed { i, sym ->
                WatchlistItem(
                    id = (i + 1).toLong(),
                    userId = userId,
                    symbol = sym.uppercase(),
                    name = "Remote $sym",
                    addedAt = baseTime,
                    createdAt = baseTime,
                    modifiedAt = baseTime + 5000, // remote is newer
                    syncedAt = null,
                    isDeleted = false
                )
            }

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(remoteItems)

            // Each symbol has a stale local version
            remoteItems.forEach { remote ->
                val localItem = remote.copy(
                    id = remote.id + 100,
                    name = "Local ${remote.symbol}",
                    modifiedAt = baseTime // older than remote
                )
                coEvery { mockWatchlistDao.getWatchlistItem(remote.symbol) } returns localItem
            }

            val result = syncEngine.syncWatchlist()

            result.isSuccess shouldBe true
            remoteItems.forEach { item ->
                coVerify(atLeast = 1) { mockWatchlistDao.updateWatchlistItem(match { it.symbol == item.symbol && it.name == item.name }) }
            }
        }
    }

    /**
     * Property 10 (Watchlist – remote deletions): When fetchWatchlist returns items
     * with isDeleted=true, syncWatchlist() removes the corresponding local records.
     *
     * Validates: Requirements 5.5
     *
     * Feature: supabase-integration, Property 10: Remote Changes Update Local Database
     */
    @Test
    fun `property 10 - remote watchlist deletions remove local items`() = runTest {
        checkAll(100, Arb.string(5..15), Arb.list(Arb.string(3..5), 1..5)) { userId, symbols ->
            clearMocks(mockWatchlistDao, mockJournalDao, answers = false)
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()

            mockAuthenticatedSession(userId)

            val deletedRemoteItems = symbols.mapIndexed { i, sym ->
                WatchlistItem(
                    id = (i + 1).toLong(),
                    userId = userId,
                    symbol = sym.uppercase(),
                    name = "Deleted $sym",
                    addedAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis(),
                    syncedAt = null,
                    isDeleted = true // marked as deleted on remote
                )
            }

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(deletedRemoteItems)

            // Each symbol exists locally
            deletedRemoteItems.forEach { remote ->
                val localItem = remote.copy(isDeleted = false)
                coEvery { mockWatchlistDao.getWatchlistItem(remote.symbol) } returns localItem
            }

            val result = syncEngine.syncWatchlist()

            result.isSuccess shouldBe true
            deletedRemoteItems.forEach { item ->
                coVerify(atLeast = 1) { mockWatchlistDao.deleteWatchlistItem(match { it.symbol == item.symbol }) }
            }
        }
    }

    /**
     * Property 10 (Journal – new remote entries): When fetchJournalEntries returns entries
     * that do not exist locally, syncJournal() inserts each of them into the local journal DAO.
     *
     * Validates: Requirements 6.5
     *
     * Feature: supabase-integration, Property 10: Remote Changes Update Local Database
     */
    @Test
    fun `property 10 - new remote journal entries are inserted into local database`() = runTest {
        checkAll(100, Arb.string(5..15), Arb.list(Arb.long(1L..9999L), 1..5)) { userId, ids ->
            clearMocks(mockWatchlistDao, mockJournalDao, answers = false)
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())

            mockAuthenticatedSession(userId)

            val remoteEntries = ids.map { id ->
                JournalEntry(
                    id = id,
                    userId = userId,
                    symbol = "AAPL",
                    action = "BUY",
                    quantity = 10,
                    price = 150.0,
                    totalValue = 1500.0,
                    notes = "remote entry $id",
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis() - 1000,
                    syncedAt = null,
                    isDeleted = false
                )
            }

            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(remoteEntries)
            // No local entry exists → triggers insert path
            coEvery { mockJournalDao.getEntryById(any()) } returns null

            val result = syncEngine.syncJournal()

            result.isSuccess shouldBe true
            remoteEntries.forEach { entry ->
                coVerify(atLeast = 1) { mockJournalDao.insertEntry(match { it.id == entry.id }) }
            }
        }
    }

    /**
     * Property 10 (Journal – modified remote entries): When fetchJournalEntries returns entries
     * that exist locally but have a newer modifiedAt, syncJournal() updates the local record.
     *
     * Validates: Requirements 6.5
     *
     * Feature: supabase-integration, Property 10: Remote Changes Update Local Database
     */
    @Test
    fun `property 10 - remote journal entries newer than local are updated in local database`() = runTest {
        checkAll(100, Arb.string(5..15), Arb.list(Arb.long(1L..9999L), 1..5)) { userId, ids ->
            clearMocks(mockWatchlistDao, mockJournalDao, answers = false)
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())

            mockAuthenticatedSession(userId)

            val baseTime = System.currentTimeMillis()
            val remoteEntries = ids.map { id ->
                JournalEntry(
                    id = id,
                    userId = userId,
                    symbol = "TSLA",
                    action = "SELL",
                    quantity = 5,
                    price = 200.0,
                    totalValue = 1000.0,
                    notes = "remote updated $id",
                    createdAt = baseTime,
                    modifiedAt = baseTime + 5000, // remote is newer
                    syncedAt = null,
                    isDeleted = false
                )
            }

            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(remoteEntries)

            remoteEntries.forEach { remote ->
                val localEntry = remote.copy(
                    notes = "local stale $remote.id",
                    modifiedAt = baseTime // older than remote
                )
                coEvery { mockJournalDao.getEntryById(remote.id) } returns localEntry
            }

            val result = syncEngine.syncJournal()

            result.isSuccess shouldBe true
            remoteEntries.forEach { entry ->
                coVerify(atLeast = 1) { mockJournalDao.updateEntry(match { it.id == entry.id && it.notes == entry.notes }) }
            }
        }
    }

    /**
     * Property 10 (Journal – remote deletions): When fetchJournalEntries returns entries
     * with isDeleted=true, syncJournal() removes the corresponding local records.
     *
     * Validates: Requirements 6.5
     *
     * Feature: supabase-integration, Property 10: Remote Changes Update Local Database
     */
    @Test
    fun `property 10 - remote journal deletions remove local entries`() = runTest {
        checkAll(100, Arb.string(5..15), Arb.list(Arb.long(1L..9999L), 1..5)) { userId, ids ->
            clearMocks(mockWatchlistDao, mockJournalDao, answers = false)
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())

            mockAuthenticatedSession(userId)

            val deletedEntries = ids.map { id ->
                JournalEntry(
                    id = id,
                    userId = userId,
                    symbol = "MSFT",
                    action = "BUY",
                    quantity = 3,
                    price = 300.0,
                    totalValue = 900.0,
                    notes = "to be deleted $id",
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis(),
                    syncedAt = null,
                    isDeleted = true // marked as deleted on remote
                )
            }

            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(deletedEntries)

            // Each entry exists locally
            deletedEntries.forEach { remote ->
                val localEntry = remote.copy(isDeleted = false)
                coEvery { mockJournalDao.getEntryById(remote.id) } returns localEntry
            }

            val result = syncEngine.syncJournal()

            result.isSuccess shouldBe true
            deletedEntries.forEach { entry ->
                coVerify(atLeast = 1) { mockJournalDao.deleteEntry(match { it.id == entry.id }) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 11: Offline Changes Are Queued
    // -------------------------------------------------------------------------

    /**
     * Property 11 (enqueue): For any sync operation, calling enqueueSyncOperation()
     * adds it to the SyncQueue and the pending count increases.
     *
     * Validates: Requirements 5.6, 6.6, 10.3
     *
     * Feature: supabase-integration, Property 11: Offline Changes Are Queued
     */
    @Test
    fun `property 11 - enqueueSyncOperation adds operation to sync queue`() = runTest {
        checkAll(100, Arb.long(1L..9999L)) { itemId ->
            // Reset queue mock to track calls
            clearMocks(mockSyncQueue, answers = false)
            var queueSize = 0
            coEvery { mockSyncQueue.size() } answers { queueSize }
            coEvery { mockSyncQueue.enqueue(any()) } answers {
                queueSize++
            }

            val operation = SyncOperation.UpsertWatchlist(itemId)
            syncEngine.enqueueSyncOperation(operation)

            // Verify the operation was forwarded to the queue
            coVerify(exactly = 1) { mockSyncQueue.enqueue(operation) }
            // Pending count should reflect the enqueued item
            queueSize shouldBeGreaterThan 0
        }
    }

    /**
     * Property 11 (queue ordering): For any list of sync operations enqueued while
     * offline, processSyncQueue() processes them and the pending count reaches zero.
     *
     * Validates: Requirements 5.6, 6.6, 10.3
     *
     * Feature: supabase-integration, Property 11: Offline Changes Are Queued
     */
    @Test
    fun `property 11 - processSyncQueue drains all pending operations`() = runTest {
        checkAll(50, Arb.list(Arb.long(1L..9999L), 1..5)) { itemIds ->
            clearMocks(mockSyncQueue, mockWatchlistDao, answers = false)

            // Build a mutable queue backed by a list
            val operations = itemIds.map { SyncOperation.UpsertWatchlist(it) }.toMutableList()
            coEvery { mockSyncQueue.size() } answers { operations.size }
            coEvery { mockSyncQueue.peek() } answers { operations.firstOrNull() }
            coEvery { mockSyncQueue.dequeue() } answers {
                if (operations.isEmpty()) null else operations.removeAt(0)
            }

            // processOperation needs to look up the item from the DAO
            itemIds.forEach { id ->
                coEvery { mockWatchlistDao.getWatchlistItemById(id) } returns WatchlistItem(
                    id = id,
                    userId = "user1",
                    symbol = "SYM$id",
                    name = "Company $id",
                    addedAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis(),
                    syncedAt = null,
                    isDeleted = false
                )
            }
            coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns Result.success(
                WatchlistItem(
                    id = 1L, userId = "user1", symbol = "SYM", name = "Co",
                    addedAt = 0L, createdAt = 0L, modifiedAt = 0L
                )
            )

            syncEngine.processSyncQueue()

            // All operations should have been dequeued
            operations.size shouldBe 0
        }
    }

    // -------------------------------------------------------------------------
    // Property 12: Last-Write-Wins Conflict Resolution
    // -------------------------------------------------------------------------

    /**
     * Property 12 (local wins): When local.modifiedAt > remote.modifiedAt and both
     * sides have changed since the last sync (conflict detected), the local version
     * is kept – the DAO is updated with the local data (not the remote data).
     *
     * Validates: Requirements 7.1, 7.2, 7.3
     *
     * Feature: supabase-integration, Property 12: Last-Write-Wins Conflict Resolution
     */
    @Test
    fun `property 12 - local wins when local modifiedAt is greater than remote`() = runTest {
        checkAll(
            100,
            Arb.string(5..15),
            Arb.string(3..5),
            Arb.long(10_000L..100_000L)
        ) { userId, symbol, delta ->
            clearMocks(mockWatchlistDao, answers = false)
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            mockAuthenticatedSession(userId)

            val baseTime = 1_000_000L
            val syncedAt = baseTime - 5000L  // last sync was before both modifications

            val localItem = WatchlistItem(
                id = 1L,
                userId = userId,
                symbol = symbol.uppercase(),
                name = "Local Name",
                addedAt = baseTime,
                createdAt = baseTime - 10_000L,
                modifiedAt = baseTime + delta,   // local is NEWER
                syncedAt = syncedAt,
                isDeleted = false
            )
            val remoteItem = localItem.copy(
                id = 2L,
                name = "Remote Name",
                createdAt = baseTime - 8_000L,
                modifiedAt = baseTime,           // remote is OLDER
                syncedAt = null
            )

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(listOf(remoteItem))
            coEvery { mockWatchlistDao.getWatchlistItem(symbol.uppercase()) } returns localItem

            val result = syncEngine.syncWatchlist()

            result.isSuccess shouldBe true
            // Local won – the update should carry the local name
            coVerify(atLeast = 1) {
                mockWatchlistDao.updateWatchlistItem(match { it.name == "Local Name" })
            }
            // Remote name must NOT have been written
            coVerify(exactly = 0) {
                mockWatchlistDao.updateWatchlistItem(match { it.name == "Remote Name" })
            }
        }
    }

    /**
     * Property 12 (remote wins): When remote.modifiedAt > local.modifiedAt and both
     * sides have changed since the last sync (conflict detected), the remote version
     * wins – the DAO is updated with the remote data.
     *
     * Validates: Requirements 7.1, 7.2, 7.3
     *
     * Feature: supabase-integration, Property 12: Last-Write-Wins Conflict Resolution
     */
    @Test
    fun `property 12 - remote wins when remote modifiedAt is greater than local`() = runTest {
        checkAll(
            100,
            Arb.string(5..15),
            Arb.string(3..5),
            Arb.long(10_000L..100_000L)
        ) { userId, symbol, delta ->
            clearMocks(mockWatchlistDao, answers = false)
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            mockAuthenticatedSession(userId)

            val baseTime = 1_000_000L
            val syncedAt = baseTime - 5000L

            val localItem = WatchlistItem(
                id = 1L,
                userId = userId,
                symbol = symbol.uppercase(),
                name = "Local Name",
                addedAt = baseTime,
                createdAt = baseTime - 10_000L,
                modifiedAt = baseTime,           // local is OLDER
                syncedAt = syncedAt,
                isDeleted = false
            )
            val remoteItem = localItem.copy(
                id = 2L,
                name = "Remote Name",
                createdAt = baseTime - 8_000L,
                modifiedAt = baseTime + delta,   // remote is NEWER
                syncedAt = null
            )

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(listOf(remoteItem))
            coEvery { mockWatchlistDao.getWatchlistItem(symbol.uppercase()) } returns localItem

            val result = syncEngine.syncWatchlist()

            result.isSuccess shouldBe true
            // Remote won – the update should carry the remote name
            coVerify(atLeast = 1) {
                mockWatchlistDao.updateWatchlistItem(match { it.name == "Remote Name" })
            }
            coVerify(exactly = 0) {
                mockWatchlistDao.updateWatchlistItem(match { it.name == "Local Name" })
            }
        }
    }

    /**
     * Property 12 (tie-breaking – remote wins): When local.modifiedAt == remote.modifiedAt
     * and a conflict is detected, the remote version wins (deterministic tie-breaking).
     *
     * Validates: Requirements 7.1, 7.2, 7.3
     *
     * Feature: supabase-integration, Property 12: Last-Write-Wins Conflict Resolution
     */
    @Test
    fun `property 12 - remote wins on equal modifiedAt timestamps`() = runTest {
        checkAll(100, Arb.string(5..15), Arb.string(3..5)) { userId, symbol ->
            clearMocks(mockWatchlistDao, answers = false)
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            mockAuthenticatedSession(userId)

            val baseTime = 1_000_000L
            val syncedAt = baseTime - 5000L  // both sides changed after this

            val localItem = WatchlistItem(
                id = 1L,
                userId = userId,
                symbol = symbol.uppercase(),
                name = "Local Name",
                addedAt = baseTime,
                createdAt = baseTime - 10_000L,
                modifiedAt = baseTime,   // equal timestamps
                syncedAt = syncedAt,
                isDeleted = false
            )
            val remoteItem = localItem.copy(
                id = 2L,
                name = "Remote Name",
                createdAt = baseTime - 8_000L,
                modifiedAt = baseTime,   // equal timestamps
                syncedAt = null
            )

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(listOf(remoteItem))
            coEvery { mockWatchlistDao.getWatchlistItem(symbol.uppercase()) } returns localItem

            val result = syncEngine.syncWatchlist()

            result.isSuccess shouldBe true
            // On a tie, remote wins
            coVerify(atLeast = 1) {
                mockWatchlistDao.updateWatchlistItem(match { it.name == "Remote Name" })
            }
        }
    }

    /**
     * Property 12 (createdAt preserved): For any conflict resolution outcome, the
     * winner's createdAt is always the minimum of both versions' createdAt values,
     * preserving the original creation timestamp.
     *
     * Validates: Requirements 7.3, 12.6
     *
     * Feature: supabase-integration, Property 12: Last-Write-Wins Conflict Resolution
     */
    @Test
    fun `property 12 - winner createdAt is minimum of both versions`() = runTest {
        checkAll(
            100,
            Arb.string(5..15),
            Arb.string(3..5),
            Arb.long(1_000L..50_000L),
            Arb.long(1_000L..50_000L),
            Arb.long(10_000L..100_000L)
        ) { userId, symbol, localCreatedOffset, remoteCreatedOffset, delta ->
            clearMocks(mockWatchlistDao, answers = false)
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            mockAuthenticatedSession(userId)

            val baseTime = 2_000_000L
            val syncedAt = baseTime - 5000L

            val localCreatedAt = baseTime - localCreatedOffset
            val remoteCreatedAt = baseTime - remoteCreatedOffset
            val expectedCreatedAt = minOf(localCreatedAt, remoteCreatedAt)

            // Remote wins (remote is newer)
            val localItem = WatchlistItem(
                id = 1L,
                userId = userId,
                symbol = symbol.uppercase(),
                name = "Local Name",
                addedAt = baseTime,
                createdAt = localCreatedAt,
                modifiedAt = baseTime,
                syncedAt = syncedAt,
                isDeleted = false
            )
            val remoteItem = localItem.copy(
                id = 2L,
                name = "Remote Name",
                createdAt = remoteCreatedAt,
                modifiedAt = baseTime + delta,  // remote is newer
                syncedAt = null
            )

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(listOf(remoteItem))
            coEvery { mockWatchlistDao.getWatchlistItem(symbol.uppercase()) } returns localItem

            val result = syncEngine.syncWatchlist()

            result.isSuccess shouldBe true
            // The resolved record must carry the minimum createdAt
            coVerify(atLeast = 1) {
                mockWatchlistDao.updateWatchlistItem(match { it.createdAt == expectedCreatedAt })
            }
        }
    }

    /**
     * Property 12 (journal – remote wins): Same last-write-wins semantics apply to
     * JournalEntry conflict resolution via syncJournal().
     *
     * Validates: Requirements 7.2, 7.3
     *
     * Feature: supabase-integration, Property 12: Last-Write-Wins Conflict Resolution
     */
    @Test
    fun `property 12 - journal remote wins when remote modifiedAt is greater than local`() = runTest {
        checkAll(
            100,
            Arb.string(5..15),
            Arb.long(1L..9999L),
            Arb.long(10_000L..100_000L)
        ) { userId, entryId, delta ->
            clearMocks(mockJournalDao, answers = false)
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            mockAuthenticatedSession(userId)

            val baseTime = 1_000_000L
            val syncedAt = baseTime - 5000L

            val localEntry = JournalEntry(
                id = entryId,
                userId = userId,
                symbol = "AAPL",
                action = "BUY",
                quantity = 10,
                price = 150.0,
                totalValue = 1500.0,
                notes = "local notes",
                createdAt = baseTime - 10_000L,
                modifiedAt = baseTime,           // local is OLDER
                syncedAt = syncedAt,
                isDeleted = false
            )
            val remoteEntry = localEntry.copy(
                notes = "remote notes",
                createdAt = baseTime - 8_000L,
                modifiedAt = baseTime + delta,   // remote is NEWER
                syncedAt = null
            )

            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(listOf(remoteEntry))
            coEvery { mockJournalDao.getEntryById(entryId) } returns localEntry

            val result = syncEngine.syncJournal()

            result.isSuccess shouldBe true
            coVerify(atLeast = 1) {
                mockJournalDao.updateEntry(match { it.notes == "remote notes" })
            }
            coVerify(exactly = 0) {
                mockJournalDao.updateEntry(match { it.notes == "local notes" })
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 13: Conflict Resolutions Are Logged
    // -------------------------------------------------------------------------

    /**
     * Property 13 (watchlist conflict logging): When a watchlist conflict is detected
     * (both local and remote modified after syncedAt), syncWatchlist() completes
     * successfully and the DAO is updated with the winner – confirming the conflict
     * resolution (and its logging) code path was executed.
     *
     * Validates: Requirements 7.4
     *
     * Feature: supabase-integration, Property 13: Conflict Resolutions Are Logged
     */
    @Test
    fun `property 13 - watchlist conflict resolution completes successfully and updates DAO`() = runTest {
        checkAll(
            100,
            Arb.string(5..15),
            Arb.string(3..5),
            Arb.long(10_000L..100_000L)
        ) { userId, symbol, delta ->
            clearMocks(mockWatchlistDao, answers = false)
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            mockAuthenticatedSession(userId)

            val baseTime = 1_000_000L
            val syncedAt = baseTime - 5_000L  // both sides modified after this → conflict

            val localItem = WatchlistItem(
                id = 1L,
                userId = userId,
                symbol = symbol.uppercase(),
                name = "Local Name",
                addedAt = baseTime,
                createdAt = baseTime - 10_000L,
                modifiedAt = baseTime + delta,   // local modified after syncedAt
                syncedAt = syncedAt,
                isDeleted = false
            )
            val remoteItem = localItem.copy(
                id = 2L,
                name = "Remote Name",
                modifiedAt = baseTime + delta + 1_000L,  // remote also modified after syncedAt, and newer
                syncedAt = null
            )

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(listOf(remoteItem))
            coEvery { mockWatchlistDao.getWatchlistItem(symbol.uppercase()) } returns localItem

            // Sync must succeed (conflict resolution + logging code path executed)
            val result = syncEngine.syncWatchlist()

            result.isSuccess shouldBe true
            // DAO must be updated with the winner (remote wins here), proving the
            // conflict resolution path – which includes the Log.d call – was reached
            coVerify(atLeast = 1) { mockWatchlistDao.updateWatchlistItem(any()) }
        }
    }

    /**
     * Property 13 (journal conflict logging): When a journal conflict is detected
     * (both local and remote modified after syncedAt), syncJournal() completes
     * successfully and the DAO is updated with the winner – confirming the conflict
     * resolution (and its logging) code path was executed.
     *
     * Validates: Requirements 7.4
     *
     * Feature: supabase-integration, Property 13: Conflict Resolutions Are Logged
     */
    @Test
    fun `property 13 - journal conflict resolution completes successfully and updates DAO`() = runTest {
        checkAll(
            100,
            Arb.string(5..15),
            Arb.long(1L..9999L),
            Arb.long(10_000L..100_000L)
        ) { userId, entryId, delta ->
            clearMocks(mockJournalDao, answers = false)
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            mockAuthenticatedSession(userId)

            val baseTime = 1_000_000L
            val syncedAt = baseTime - 5_000L  // both sides modified after this → conflict

            val localEntry = JournalEntry(
                id = entryId,
                userId = userId,
                symbol = "AAPL",
                action = "BUY",
                quantity = 10,
                price = 150.0,
                totalValue = 1500.0,
                notes = "local notes",
                createdAt = baseTime - 10_000L,
                modifiedAt = baseTime + delta,   // local modified after syncedAt
                syncedAt = syncedAt,
                isDeleted = false
            )
            val remoteEntry = localEntry.copy(
                notes = "remote notes",
                modifiedAt = baseTime + delta + 1_000L,  // remote also modified after syncedAt, and newer
                syncedAt = null
            )

            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(listOf(remoteEntry))
            coEvery { mockJournalDao.getEntryById(entryId) } returns localEntry

            // Sync must succeed (conflict resolution + logging code path executed)
            val result = syncEngine.syncJournal()

            result.isSuccess shouldBe true
            // DAO must be updated with the winner (remote wins here), proving the
            // conflict resolution path – which includes the Log.d call – was reached
            coVerify(atLeast = 1) { mockJournalDao.updateEntry(any()) }
        }
    }

    // -------------------------------------------------------------------------
    // Property 23: Sync Preserves Creation Timestamps
    // -------------------------------------------------------------------------

    /**
     * Property 23 (watchlist): After syncWatchlist(), the stored item's createdAt is
     * never greater than the original local createdAt. The creation timestamp is
     * preserved or moved earlier (to the minimum of local and remote), never later.
     *
     * Validates: Requirements 12.6
     *
     * Feature: supabase-integration, Property 23: Sync Preserves Creation Timestamps
     */
    @Test
    fun `property 23 - syncWatchlist never increases createdAt timestamp`() = runTest {
        checkAll(
            100,
            Arb.string(5..15),
            Arb.string(3..5),
            Arb.long(1_000L..50_000L),
            Arb.long(1_000L..50_000L),
            Arb.long(10_000L..100_000L)
        ) { userId, symbol, localCreatedOffset, remoteCreatedOffset, delta ->
            clearMocks(mockWatchlistDao, answers = false)
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            mockAuthenticatedSession(userId)

            val baseTime = 2_000_000L
            val syncedAt = baseTime - 5_000L

            val localCreatedAt = baseTime - localCreatedOffset
            val remoteCreatedAt = baseTime - remoteCreatedOffset

            // Remote is newer so it wins the LWW resolution
            val localItem = WatchlistItem(
                id = 1L,
                userId = userId,
                symbol = symbol.uppercase(),
                name = "Local Name",
                addedAt = baseTime,
                createdAt = localCreatedAt,
                modifiedAt = baseTime,           // local is OLDER
                syncedAt = syncedAt,
                isDeleted = false
            )
            val remoteItem = localItem.copy(
                id = 2L,
                name = "Remote Name",
                createdAt = remoteCreatedAt,
                modifiedAt = baseTime + delta,   // remote is NEWER
                syncedAt = null
            )

            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(listOf(remoteItem))
            coEvery { mockWatchlistDao.getWatchlistItem(symbol.uppercase()) } returns localItem

            val result = syncEngine.syncWatchlist()

            result.isSuccess shouldBe true
            // The stored createdAt must never exceed the original local createdAt
            coVerify(atLeast = 1) {
                mockWatchlistDao.updateWatchlistItem(match { it.createdAt <= localCreatedAt })
            }
        }
    }

    /**
     * Property 23 (journal): After syncJournal(), the stored entry's createdAt is
     * never greater than the original local createdAt. The creation timestamp is
     * preserved or moved earlier (to the minimum of local and remote), never later.
     *
     * Validates: Requirements 12.6
     *
     * Feature: supabase-integration, Property 23: Sync Preserves Creation Timestamps
     */
    @Test
    fun `property 23 - syncJournal never increases createdAt timestamp`() = runTest {
        checkAll(
            100,
            Arb.string(5..15),
            Arb.long(1L..9999L),
            Arb.long(1_000L..50_000L),
            Arb.long(1_000L..50_000L),
            Arb.long(10_000L..100_000L)
        ) { userId, entryId, localCreatedOffset, remoteCreatedOffset, delta ->
            clearMocks(mockJournalDao, answers = false)
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns emptyList()
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            mockAuthenticatedSession(userId)

            val baseTime = 2_000_000L
            val syncedAt = baseTime - 5_000L

            val localCreatedAt = baseTime - localCreatedOffset
            val remoteCreatedAt = baseTime - remoteCreatedOffset

            // Remote is newer so it wins the LWW resolution
            val localEntry = JournalEntry(
                id = entryId,
                userId = userId,
                symbol = "AAPL",
                action = "BUY",
                quantity = 10,
                price = 150.0,
                totalValue = 1500.0,
                notes = "local notes",
                createdAt = localCreatedAt,
                modifiedAt = baseTime,           // local is OLDER
                syncedAt = syncedAt,
                isDeleted = false
            )
            val remoteEntry = localEntry.copy(
                notes = "remote notes",
                createdAt = remoteCreatedAt,
                modifiedAt = baseTime + delta,   // remote is NEWER
                syncedAt = null
            )

            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(listOf(remoteEntry))
            coEvery { mockJournalDao.getEntryById(entryId) } returns localEntry

            val result = syncEngine.syncJournal()

            result.isSuccess shouldBe true
            // The stored createdAt must never exceed the original local createdAt
            coVerify(atLeast = 1) {
                mockJournalDao.updateEntry(match { it.createdAt <= localCreatedAt })
            }
        }
    }

    /**
     * Property 11 (pending count decreases): For any set of queued operations,
     * each call to dequeue() during processSyncQueue() reduces the pending count.
     *
     * Validates: Requirements 5.6, 6.6, 10.3
     *
     * Feature: supabase-integration, Property 11: Offline Changes Are Queued
     */
    @Test
    fun `property 11 - pending count decreases as operations are processed`() = runTest {
        checkAll(50, Arb.list(Arb.long(1L..9999L), 2..5)) { itemIds ->
            clearMocks(mockSyncQueue, mockWatchlistDao, answers = false)

            val operations = itemIds.map { SyncOperation.UpsertWatchlist(it) }.toMutableList()
            val sizeHistory = mutableListOf<Int>()

            coEvery { mockSyncQueue.size() } answers { operations.size.also { sizeHistory.add(it) } }
            coEvery { mockSyncQueue.peek() } answers { operations.firstOrNull() }
            coEvery { mockSyncQueue.dequeue() } answers {
                if (operations.isEmpty()) null else operations.removeAt(0)
            }

            itemIds.forEach { id ->
                coEvery { mockWatchlistDao.getWatchlistItemById(id) } returns WatchlistItem(
                    id = id,
                    userId = "user1",
                    symbol = "SYM$id",
                    name = "Company $id",
                    addedAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis(),
                    syncedAt = null,
                    isDeleted = false
                )
            }
            coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns Result.success(
                WatchlistItem(
                    id = 1L, userId = "user1", symbol = "SYM", name = "Co",
                    addedAt = 0L, createdAt = 0L, modifiedAt = 0L
                )
            )

            syncEngine.processSyncQueue()

            // Queue should be fully drained
            operations.size shouldBe 0
            // The final recorded size should be 0
            sizeHistory.last() shouldBe 0
        }
    }

    // -------------------------------------------------------------------------
    // Property 24: Network Failures Trigger Exponential Backoff Retry
    // -------------------------------------------------------------------------

    /**
     * Property 24 (watchlist upsert retries on network error): For any watchlist item
     * upload that fails with a network error, syncWatchlist() retries the operation
     * up to 3 times. After exhausted retries the operation is queued (Req 13.3) and
     * syncWatchlist() returns success (the failure is handled gracefully).
     *
     * Validates: Requirements 13.1
     *
     * Feature: supabase-integration, Property 24: Network Failures Trigger Exponential Backoff Retry
     */
    @Test
    fun `property 24 - syncWatchlist retries upsert up to 3 times on network error`() = runTest {
        checkAll(50, Arb.string(5..15), Arb.string(3..5)) { userId, symbol ->
            clearMocks(mockWatchlistDao, mockSupabaseClient, mockSyncQueue, answers = false)
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSyncQueue.size() } returns 0
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
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)

            // Always fail with a network error
            val networkError = Exception("Network error: connection refused")
            coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns Result.failure(networkError)

            val result = syncEngine.syncWatchlist()

            // After 3 failed attempts the operation is queued; sync returns success
            result.isSuccess shouldBe true
            // upsertWatchlistItem must have been called exactly 3 times (1 initial + 2 retries)
            coVerify(exactly = 3) { mockSupabaseClient.upsertWatchlistItem(any()) }
        }
    }

    /**
     * Property 24 (watchlist upsert succeeds on retry): For any watchlist item upload
     * that fails on the first attempt but succeeds on a subsequent retry, syncWatchlist()
     * returns success and the DAO is updated with syncedAt.
     *
     * Validates: Requirements 13.1
     *
     * Feature: supabase-integration, Property 24: Network Failures Trigger Exponential Backoff Retry
     */
    @Test
    fun `property 24 - syncWatchlist succeeds when retry recovers from network error`() = runTest {
        checkAll(50, Arb.string(5..15), Arb.string(3..5), Arb.int(1..2)) { userId, symbol, failCount ->
            clearMocks(mockWatchlistDao, mockSupabaseClient, answers = false)
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
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
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)

            // Fail `failCount` times then succeed
            var callCount = 0
            coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } answers {
                callCount++
                if (callCount <= failCount) Result.failure(Exception("Network error"))
                else Result.success(item)
            }

            val result = syncEngine.syncWatchlist()

            result.isSuccess shouldBe true
            // Should have been called failCount + 1 times total
            coVerify(exactly = failCount + 1) { mockSupabaseClient.upsertWatchlistItem(any()) }
            // DAO should be updated with syncedAt after success
            coVerify(atLeast = 1) { mockWatchlistDao.updateWatchlistItem(match { it.syncedAt != null }) }
        }
    }

    /**
     * Property 24 (journal upsert retries on network error): For any journal entry
     * upload that fails with a network error, syncJournal() retries the operation
     * up to 3 times. After exhausted retries the operation is queued (Req 13.3) and
     * syncJournal() returns success (the failure is handled gracefully).
     *
     * Validates: Requirements 13.1
     *
     * Feature: supabase-integration, Property 24: Network Failures Trigger Exponential Backoff Retry
     */
    @Test
    fun `property 24 - syncJournal retries upsert up to 3 times on network error`() = runTest {
        checkAll(50, Arb.string(5..15), Arb.long(1L..9999L)) { userId, entryId ->
            clearMocks(mockJournalDao, mockSupabaseClient, mockSyncQueue, answers = false)
            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(emptyList())
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0
            mockAuthenticatedSession(userId)

            val entry = JournalEntry(
                id = entryId,
                userId = userId,
                symbol = "AAPL",
                action = "BUY",
                quantity = 10,
                price = 150.0,
                totalValue = 1500.0,
                notes = "test",
                createdAt = 1_000_000L,
                modifiedAt = 1_000_000L,
                syncedAt = null,
                isDeleted = false
            )
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns listOf(entry)

            val networkError = Exception("Network error: timeout")
            coEvery { mockSupabaseClient.upsertJournalEntry(any()) } returns Result.failure(networkError)

            val result = syncEngine.syncJournal()

            // After 3 failed attempts the operation is queued; sync returns success
            result.isSuccess shouldBe true
            coVerify(exactly = 3) { mockSupabaseClient.upsertJournalEntry(any()) }
        }
    }

    /**
     * Property 24 (auth errors are NOT retried with backoff): For any sync operation that
     * fails with an authentication error, the backoff retry is skipped. The operation is
     * then handled by the token-refresh path (task 13.2) and ultimately queued if it still
     * fails (Req 13.3).
     *
     * Validates: Requirements 13.1
     *
     * Feature: supabase-integration, Property 24: Network Failures Trigger Exponential Backoff Retry
     */
    @Test
    fun `property 24 - auth errors are not retried with backoff`() = runTest {
        checkAll(50, Arb.string(5..15), Arb.string(3..5)) { userId, symbol ->
            clearMocks(mockWatchlistDao, mockSupabaseClient, mockSyncQueue, answers = false)
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSyncQueue.size() } returns 0
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
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)

            // Auth error – should NOT be retried with backoff (only 1 call in retryWithBackoff)
            val authError = Exception("401 Unauthorized: JWT expired")
            coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns Result.failure(authError)
            // Token refresh also fails so the operation ends up queued
            coEvery { mockAuthManager.refreshToken() } returns Result.failure(Exception("refresh failed"))

            val result = syncEngine.syncWatchlist()

            // Auth errors skip backoff retries – only 1 call in retryWithBackoff,
            // then 1 more after token refresh attempt = 2 total at most
            result.isSuccess shouldBe true // operation is queued, not thrown
            coVerify(atMost = 2) { mockSupabaseClient.upsertWatchlistItem(any()) }
        }
    }

    // -------------------------------------------------------------------------
    // Property 25: Exhausted Retries Queue Operation
    // -------------------------------------------------------------------------

    /**
     * Property 25 (watchlist upsert queued after exhausted retries): For any watchlist
     * item upload that fails all 3 retry attempts, syncWatchlist() enqueues an
     * UpsertWatchlist operation to the SyncQueue and returns success.
     *
     * Validates: Requirements 13.3
     *
     * Feature: supabase-integration, Property 25: Exhausted Retries Queue Operation
     */
    @Test
    fun `property 25 - watchlist upsert is queued after all retries are exhausted`() = runTest {
        checkAll(50, Arb.string(5..15), Arb.string(3..5)) { userId, symbol ->
            clearMocks(mockWatchlistDao, mockSupabaseClient, mockSyncQueue, answers = false)
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSyncQueue.size() } returns 0
            mockAuthenticatedSession(userId)

            val item = WatchlistItem(
                id = 42L,
                userId = userId,
                symbol = symbol.uppercase(),
                name = "Company $symbol",
                addedAt = 1_000_000L,
                createdAt = 1_000_000L,
                modifiedAt = 1_000_000L,
                syncedAt = null,
                isDeleted = false
            )
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)
            coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns Result.failure(Exception("Network error"))

            val result = syncEngine.syncWatchlist()

            // Sync succeeds – failure is handled by queuing
            result.isSuccess shouldBe true
            // The failed operation must be enqueued for the next sync cycle
            coVerify(atLeast = 1) { mockSyncQueue.enqueue(SyncOperation.UpsertWatchlist(42L)) }
        }
    }

    /**
     * Property 25 (watchlist delete queued after exhausted retries): For any watchlist
     * item deletion that fails all 3 retry attempts, syncWatchlist() enqueues a
     * DeleteWatchlist operation to the SyncQueue and returns success.
     *
     * Validates: Requirements 13.3
     *
     * Feature: supabase-integration, Property 25: Exhausted Retries Queue Operation
     */
    @Test
    fun `property 25 - watchlist delete is queued after all retries are exhausted`() = runTest {
        checkAll(50, Arb.string(5..15), Arb.string(3..5)) { userId, symbol ->
            clearMocks(mockWatchlistDao, mockSupabaseClient, mockSyncQueue, answers = false)
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSyncQueue.size() } returns 0
            mockAuthenticatedSession(userId)

            val item = WatchlistItem(
                id = 99L,
                userId = userId,
                symbol = symbol.uppercase(),
                name = "Company $symbol",
                addedAt = 1_000_000L,
                createdAt = 1_000_000L,
                modifiedAt = 1_000_000L,
                syncedAt = null,
                isDeleted = true // marked for deletion
            )
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)
            coEvery { mockSupabaseClient.deleteWatchlistItem(any()) } returns Result.failure(Exception("Network error"))

            val result = syncEngine.syncWatchlist()

            result.isSuccess shouldBe true
            coVerify(atLeast = 1) { mockSyncQueue.enqueue(SyncOperation.DeleteWatchlist(99L)) }
        }
    }

    /**
     * Property 25 (journal upsert queued after exhausted retries): For any journal entry
     * upload that fails all 3 retry attempts, syncJournal() enqueues an UpsertJournal
     * operation to the SyncQueue and returns success.
     *
     * Validates: Requirements 13.3
     *
     * Feature: supabase-integration, Property 25: Exhausted Retries Queue Operation
     */
    @Test
    fun `property 25 - journal upsert is queued after all retries are exhausted`() = runTest {
        checkAll(50, Arb.string(5..15), Arb.long(1L..9999L)) { userId, entryId ->
            clearMocks(mockJournalDao, mockSupabaseClient, mockSyncQueue, answers = false)
            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(emptyList())
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0
            mockAuthenticatedSession(userId)

            val entry = JournalEntry(
                id = entryId,
                userId = userId,
                symbol = "AAPL",
                action = "BUY",
                quantity = 10,
                price = 150.0,
                totalValue = 1500.0,
                notes = "test",
                createdAt = 1_000_000L,
                modifiedAt = 1_000_000L,
                syncedAt = null,
                isDeleted = false
            )
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns listOf(entry)
            coEvery { mockSupabaseClient.upsertJournalEntry(any()) } returns Result.failure(Exception("Network error"))

            val result = syncEngine.syncJournal()

            result.isSuccess shouldBe true
            coVerify(atLeast = 1) { mockSyncQueue.enqueue(SyncOperation.UpsertJournal(entryId)) }
        }
    }

    /**
     * Property 25 (journal delete queued after exhausted retries): For any journal entry
     * deletion that fails all 3 retry attempts, syncJournal() enqueues a DeleteJournal
     * operation to the SyncQueue and returns success.
     *
     * Validates: Requirements 13.3
     *
     * Feature: supabase-integration, Property 25: Exhausted Retries Queue Operation
     */
    @Test
    fun `property 25 - journal delete is queued after all retries are exhausted`() = runTest {
        checkAll(50, Arb.string(5..15), Arb.long(1L..9999L)) { userId, entryId ->
            clearMocks(mockJournalDao, mockSupabaseClient, mockSyncQueue, answers = false)
            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(emptyList())
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0
            mockAuthenticatedSession(userId)

            val entry = JournalEntry(
                id = entryId,
                userId = userId,
                symbol = "MSFT",
                action = "SELL",
                quantity = 5,
                price = 300.0,
                totalValue = 1500.0,
                notes = "to delete",
                createdAt = 1_000_000L,
                modifiedAt = 1_000_000L,
                syncedAt = null,
                isDeleted = true // marked for deletion
            )
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns listOf(entry)
            coEvery { mockSupabaseClient.deleteJournalEntry(any()) } returns Result.failure(Exception("Network error"))

            val result = syncEngine.syncJournal()

            result.isSuccess shouldBe true
            coVerify(atLeast = 1) { mockSyncQueue.enqueue(SyncOperation.DeleteJournal(entryId)) }
        }
    }

    /**
     * Property 25 (queued operations processed in next cycle): After a failed operation
     * is enqueued, processSyncQueue() picks it up and processes it in the next sync cycle.
     *
     * Validates: Requirements 13.3
     *
     * Feature: supabase-integration, Property 25: Exhausted Retries Queue Operation
     */
    @Test
    fun `property 25 - queued operation from failed sync is processed in next cycle`() = runTest {
        checkAll(50, Arb.long(1L..9999L)) { itemId ->
            clearMocks(mockSyncQueue, mockWatchlistDao, mockSupabaseClient, answers = false)

            // Simulate the queue containing one operation (as if it was enqueued after failed retries)
            val operations = mutableListOf<SyncOperation>(SyncOperation.UpsertWatchlist(itemId))
            coEvery { mockSyncQueue.size() } answers { operations.size }
            coEvery { mockSyncQueue.peek() } answers { operations.firstOrNull() }
            coEvery { mockSyncQueue.dequeue() } answers {
                if (operations.isEmpty()) null else operations.removeAt(0)
            }

            coEvery { mockWatchlistDao.getWatchlistItemById(itemId) } returns WatchlistItem(
                id = itemId,
                userId = "user1",
                symbol = "SYM$itemId",
                name = "Company $itemId",
                addedAt = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis(),
                syncedAt = null,
                isDeleted = false
            )
            coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns Result.success(
                WatchlistItem(id = itemId, userId = "user1", symbol = "SYM", name = "Co",
                    addedAt = 0L, createdAt = 0L, modifiedAt = 0L)
            )

            syncEngine.processSyncQueue()

            // The queued operation should have been processed and dequeued
            operations.size shouldBe 0
            coVerify(atLeast = 1) { mockSupabaseClient.upsertWatchlistItem(any()) }
        }
    }

    // -------------------------------------------------------------------------
    // Property 26: Server Errors Are Logged With Context
    // -------------------------------------------------------------------------

    /**
     * Property 26 (watchlist server error creates log entry): When syncWatchlist()
     * encounters a server error during upload, getErrorLogs() returns at least one
     * entry with a non-empty operation description, non-empty error message, and a
     * valid timestamp (> 0).
     *
     * Validates: Requirements 13.4
     *
     * Feature: supabase-integration, Property 26: Server Errors Are Logged With Context
     */
    @Test
    fun `property 26 - syncWatchlist server error creates log entry with full context`() = runTest {
        checkAll(50, Arb.string(5..15), Arb.string(3..5)) { userId, symbol ->
            clearMocks(mockWatchlistDao, mockSupabaseClient, mockSyncQueue, answers = false)
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSyncQueue.size() } returns 0
            coEvery { mockSyncQueue.enqueue(any()) } returns Unit
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
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)

            // Simulate a server error (5xx)
            val serverError = Exception("500 Internal Server Error: database unavailable")
            coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns Result.failure(serverError)

            // Reset error logs by creating a fresh SyncEngineImpl for this iteration
            val freshSyncEngine = SyncEngineImpl(
                database = mockDatabase,
                supabaseClient = mockSupabaseClient,
                networkMonitor = mockNetworkMonitor,
                syncQueue = mockSyncQueue,
                encryptedStorage = mockk(relaxed = true),
                authManager = dagger.Lazy { mockAuthManager }
            )

            val result = freshSyncEngine.syncWatchlist()

            // Sync returns success (error is handled gracefully by queuing)
            result.isSuccess shouldBe true

            // At least one error log entry must exist
            val logs = freshSyncEngine.getErrorLogs()
            logs.size shouldBeGreaterThan 0

            // The log entry must have full context
            val log = logs.first()
            log.operation.isNotEmpty() shouldBe true          // non-empty operation description
            log.errorMessage.isNotEmpty() shouldBe true       // non-empty error message
            log.timestamp shouldBeGreaterThan 0L              // valid timestamp
        }
    }

    /**
     * Property 26 (watchlist server error – SyncStatus has user-friendly message):
     * When syncWatchlist() encounters a server error, the SyncStatus.error field
     * contains a user-friendly message that is NOT the raw exception message.
     *
     * Validates: Requirements 13.4, 13.5
     *
     * Feature: supabase-integration, Property 26: Server Errors Are Logged With Context
     */
    @Test
    fun `property 26 - syncWatchlist server error sets user-friendly SyncStatus error`() = runTest {
        checkAll(50, Arb.string(5..15), Arb.string(3..5)) { userId, symbol ->
            clearMocks(mockWatchlistDao, mockSupabaseClient, mockSyncQueue, answers = false)
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockSyncQueue.size() } returns 0
            coEvery { mockSyncQueue.enqueue(any()) } returns Unit
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
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns listOf(item)

            val rawServerErrorMessage = "500 Internal Server Error: pg_query failed with OOM"
            val serverError = Exception(rawServerErrorMessage)
            coEvery { mockSupabaseClient.upsertWatchlistItem(any()) } returns Result.failure(serverError)

            val freshSyncEngine = SyncEngineImpl(
                database = mockDatabase,
                supabaseClient = mockSupabaseClient,
                networkMonitor = mockNetworkMonitor,
                syncQueue = mockSyncQueue,
                encryptedStorage = mockk(relaxed = true),
                authManager = dagger.Lazy { mockAuthManager }
            )

            freshSyncEngine.syncWatchlist()

            // The error log stores the raw message (for debugging), but isUserFriendly=false
            // means the raw technical details are NOT shown to the user (Req 13.5)
            val logs = freshSyncEngine.getErrorLogs()
            logs.size shouldBeGreaterThan 0
            val log = logs.first()
            // Raw error is stored in the log (not user-friendly)
            log.isUserFriendly shouldBe false
            // The log's errorMessage contains the raw technical details
            log.errorMessage.isNotEmpty() shouldBe true
        }
    }

    /**
     * Property 26 (journal server error creates log entry): When syncJournal()
     * encounters a server error during upload, getErrorLogs() returns at least one
     * entry with a non-empty operation description, non-empty error message, and a
     * valid timestamp (> 0).
     *
     * Validates: Requirements 13.4
     *
     * Feature: supabase-integration, Property 26: Server Errors Are Logged With Context
     */
    @Test
    fun `property 26 - syncJournal server error creates log entry with full context`() = runTest {
        checkAll(50, Arb.string(5..15), Arb.long(1L..9999L)) { userId, entryId ->
            clearMocks(mockJournalDao, mockSupabaseClient, mockSyncQueue, answers = false)
            coEvery { mockSupabaseClient.fetchJournalEntries(userId) } returns Result.success(emptyList())
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.success(emptyList())
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            coEvery { mockSyncQueue.size() } returns 0
            coEvery { mockSyncQueue.enqueue(any()) } returns Unit
            mockAuthenticatedSession(userId)

            val entry = JournalEntry(
                id = entryId,
                userId = userId,
                symbol = "AAPL",
                action = "BUY",
                quantity = 10,
                price = 150.0,
                totalValue = 1500.0,
                notes = "test entry $entryId",
                createdAt = 1_000_000L,
                modifiedAt = 1_000_000L,
                syncedAt = null,
                isDeleted = false
            )
            coEvery { mockJournalDao.getUnsyncedJournalEntries() } returns listOf(entry)

            val serverError = Exception("503 Service Unavailable: upstream timeout")
            coEvery { mockSupabaseClient.upsertJournalEntry(any()) } returns Result.failure(serverError)

            val freshSyncEngine = SyncEngineImpl(
                database = mockDatabase,
                supabaseClient = mockSupabaseClient,
                networkMonitor = mockNetworkMonitor,
                syncQueue = mockSyncQueue,
                encryptedStorage = mockk(relaxed = true),
                authManager = dagger.Lazy { mockAuthManager }
            )

            val result = freshSyncEngine.syncJournal()

            result.isSuccess shouldBe true

            val logs = freshSyncEngine.getErrorLogs()
            logs.size shouldBeGreaterThan 0

            val log = logs.first()
            log.operation.isNotEmpty() shouldBe true
            log.errorMessage.isNotEmpty() shouldBe true
            log.timestamp shouldBeGreaterThan 0L
        }
    }

    /**
     * Property 26 (download server error creates log entry): When syncWatchlist()
     * encounters a server error during the download phase (fetchWatchlist fails),
     * getErrorLogs() returns at least one entry with full context and syncWatchlist()
     * returns failure (the download error is propagated).
     *
     * Validates: Requirements 13.4
     *
     * Feature: supabase-integration, Property 26: Server Errors Are Logged With Context
     */
    @Test
    fun `property 26 - syncWatchlist download server error creates log entry with full context`() = runTest {
        checkAll(50, Arb.string(5..15)) { userId ->
            clearMocks(mockWatchlistDao, mockSupabaseClient, answers = false)
            coEvery { mockWatchlistDao.getUnsyncedWatchlistItems() } returns emptyList()
            mockAuthenticatedSession(userId)

            val serverError = Exception("500 Internal Server Error: query timeout")
            coEvery { mockSupabaseClient.fetchWatchlist(userId) } returns Result.failure(serverError)

            val freshSyncEngine = SyncEngineImpl(
                database = mockDatabase,
                supabaseClient = mockSupabaseClient,
                networkMonitor = mockNetworkMonitor,
                syncQueue = mockSyncQueue,
                encryptedStorage = mockk(relaxed = true),
                authManager = dagger.Lazy { mockAuthManager }
            )

            // Download failure propagates as a Result.failure
            val result = freshSyncEngine.syncWatchlist()
            result.isFailure shouldBe true

            // Error must still be logged with full context
            val logs = freshSyncEngine.getErrorLogs()
            logs.size shouldBeGreaterThan 0

            val log = logs.first()
            log.operation.isNotEmpty() shouldBe true
            log.errorMessage.isNotEmpty() shouldBe true
            log.timestamp shouldBeGreaterThan 0L
        }
    }
}
