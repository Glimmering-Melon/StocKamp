package com.stockamp.data.sync

import com.stockamp.data.model.JournalEntry
import com.stockamp.data.model.WatchlistItem
import com.stockamp.data.repository.JournalRepository
import com.stockamp.data.repository.WatchlistRepository
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Property-based tests for timestamp management.
 *
 * Validates that creation and modification timestamps are correctly recorded
 * and updated by WatchlistRepository and JournalRepository.
 *
 * Feature: supabase-integration
 */

// ── Fake DAOs ─────────────────────────────────────────────────────────────────

private class FakeWatchlistDao : com.stockamp.data.local.WatchlistDao {
    private val items = mutableListOf<WatchlistItem>()
    private var nextId = 1L

    override fun getAllWatchlistItems() =
        kotlinx.coroutines.flow.flowOf(items.filter { !it.isDeleted }.toList())

    override suspend fun getWatchlistItem(symbol: String) =
        items.firstOrNull { it.symbol == symbol && !it.isDeleted }

    override suspend fun getWatchlistItemById(id: Long) =
        items.firstOrNull { it.id == id }

    override suspend fun insertWatchlistItem(item: WatchlistItem) {
        val stored = if (item.id == 0L) item.copy(id = nextId++) else item
        items.removeAll { it.id == stored.id }
        items.add(stored)
    }

    override suspend fun updateWatchlistItem(item: WatchlistItem) {
        items.replaceAll { if (it.id == item.id) item else it }
    }

    override suspend fun deleteWatchlistItem(item: WatchlistItem) {
        items.removeAll { it.id == item.id }
    }

    override suspend fun deleteBySymbol(symbol: String) {
        items.removeAll { it.symbol == symbol }
    }

    override suspend fun isInWatchlist(symbol: String) =
        items.count { it.symbol == symbol && !it.isDeleted }

    override suspend fun getUnsyncedWatchlistItems() =
        items.filter { it.syncedAt == null || it.modifiedAt > it.syncedAt!! }

    override suspend fun deleteAllWatchlistItems() = items.clear()
}

private class FakeJournalDao : com.stockamp.data.local.JournalDao {
    private val entries = mutableListOf<JournalEntry>()
    private var nextId = 1L

    override fun getAllEntries() =
        kotlinx.coroutines.flow.flowOf(entries.filter { !it.isDeleted }.toList())

    override suspend fun getEntryById(id: Long) =
        entries.firstOrNull { it.id == id }

    override fun getEntriesBySymbol(symbol: String) =
        kotlinx.coroutines.flow.flowOf(entries.filter { it.symbol == symbol && !it.isDeleted })

    override suspend fun insertEntry(entry: JournalEntry): Long {
        val stored = if (entry.id == 0L) entry.copy(id = nextId++) else entry
        entries.removeAll { it.id == stored.id }
        entries.add(stored)
        return stored.id
    }

    override suspend fun updateEntry(entry: JournalEntry) {
        entries.replaceAll { if (it.id == entry.id) entry else it }
    }

    override suspend fun deleteEntry(entry: JournalEntry) {
        entries.removeAll { it.id == entry.id }
    }

    override suspend fun getTotalPnL() =
        entries.filter { !it.isDeleted }.sumOf {
            if (it.action == "BUY") -it.totalValue else it.totalValue
        }

    override suspend fun getTotalTrades() =
        entries.count { !it.isDeleted }

    override suspend fun getUnsyncedJournalEntries() =
        entries.filter { it.syncedAt == null || it.modifiedAt > it.syncedAt!! }

    override suspend fun deleteAllJournalEntries() = entries.clear()
}

// ── Generators ────────────────────────────────────────────────────────────────

private fun arbSymbol(): Arb<String> =
    Arb.string(3..5).map { it.uppercase().filter { c -> c.isLetter() }.take(5).padEnd(3, 'X') }

private fun arbJournalEntry(): Arb<JournalEntry> =
    Arb.long(1L..9_999L).map { seed ->
        JournalEntry(
            symbol = "SYM${seed % 100}",
            action = if (seed % 2 == 0L) "BUY" else "SELL",
            quantity = ((seed % 50) + 1).toInt(),
            price = 10.0 + (seed % 990),
            notes = "note $seed"
            // createdAt intentionally left at default (0L triggers repo logic)
        )
    }

private fun arbJournalEntryWithCreatedAt(): Arb<JournalEntry> =
    Arb.long(1L..9_999L).map { seed ->
        JournalEntry(
            symbol = "SYM${seed % 100}",
            action = if (seed % 2 == 0L) "BUY" else "SELL",
            quantity = ((seed % 50) + 1).toInt(),
            price = 10.0 + (seed % 990),
            notes = "note $seed",
            createdAt = 1_000_000L + seed  // pre-set non-zero createdAt
        )
    }

// ── Property 21: Creation Timestamps Are Recorded ────────────────────────────

/**
 * Property 21: Creation Timestamps Are Recorded
 *
 * For any newly created watchlist item or journal entry, the Room database should
 * record a creation timestamp in UTC format.
 *
 * Validates: Requirements 12.1, 12.3
 *
 * Feature: supabase-integration, Property 21: Creation Timestamps Are Recorded
 */
class CreationTimestampPropertyTest {

    private lateinit var watchlistDao: FakeWatchlistDao
    private lateinit var journalDao: FakeJournalDao
    private lateinit var watchlistRepo: WatchlistRepository
    private lateinit var journalRepo: JournalRepository

    @Before
    fun setup() {
        watchlistDao = FakeWatchlistDao()
        journalDao = FakeJournalDao()
        watchlistRepo = WatchlistRepository(watchlistDao)
        journalRepo = JournalRepository(journalDao)
    }

    /**
     * Property 21 (watchlist createdAt is non-zero): For any WatchlistItem inserted via
     * WatchlistRepository.addToWatchlist(), the stored createdAt field must be > 0.
     *
     * Validates: Requirements 12.1
     */
    @Test
    fun `property 21 - watchlist createdAt is non-zero after addToWatchlist`() = runTest {
        checkAll(100, arbSymbol(), Arb.string(3..20)) { symbol, name ->
            val dao = FakeWatchlistDao()
            val repo = WatchlistRepository(dao)

            val before = System.currentTimeMillis()
            repo.addToWatchlist(symbol, name)
            val after = System.currentTimeMillis()

            val stored = dao.getWatchlistItem(symbol)
            stored shouldNotBe null
            stored!!.createdAt shouldBeGreaterThan 0L
            stored.createdAt shouldBeGreaterThanOrEqualTo before
            stored.createdAt shouldBeLessThanOrEqualTo after
        }
    }

    /**
     * Property 21 (journal createdAt is non-zero when entry.createdAt == 0): For any
     * JournalEntry with createdAt == 0L inserted via JournalRepository.addEntry(), the
     * stored createdAt field must be set to a non-zero UTC timestamp.
     *
     * Validates: Requirements 12.3
     */
    @Test
    fun `property 21 - journal createdAt is set when entry has zero createdAt`() = runTest {
        checkAll(100, arbJournalEntry()) { entry ->
            val dao = FakeJournalDao()
            val repo = JournalRepository(dao)

            // entry.createdAt == 0L by default from arbJournalEntry
            val entryWithZeroCreatedAt = entry.copy(createdAt = 0L)
            val before = System.currentTimeMillis()
            val id = repo.addEntry(entryWithZeroCreatedAt)
            val after = System.currentTimeMillis()

            val stored = dao.getEntryById(id)
            stored shouldNotBe null
            stored!!.createdAt shouldBeGreaterThan 0L
            stored.createdAt shouldBeGreaterThanOrEqualTo before
            stored.createdAt shouldBeLessThanOrEqualTo after
        }
    }

    /**
     * Property 21 (journal createdAt is preserved when already set): For any JournalEntry
     * with a pre-existing non-zero createdAt, JournalRepository.addEntry() must not
     * overwrite it.
     *
     * Validates: Requirements 12.3
     */
    @Test
    fun `property 21 - journal createdAt is preserved when already non-zero`() = runTest {
        checkAll(100, arbJournalEntryWithCreatedAt()) { entry ->
            val dao = FakeJournalDao()
            val repo = JournalRepository(dao)

            val originalCreatedAt = entry.createdAt
            val id = repo.addEntry(entry)

            val stored = dao.getEntryById(id)
            stored shouldNotBe null
            stored!!.createdAt shouldBe originalCreatedAt
        }
    }

    /**
     * Property 21 (watchlist createdAt does not change on update): For any WatchlistItem,
     * the createdAt timestamp must remain unchanged after calling
     * WatchlistRepository.updateWatchlistItem().
     *
     * Validates: Requirements 12.1
     */
    @Test
    fun `property 21 - watchlist createdAt is unchanged after update`() = runTest {
        checkAll(100, arbSymbol(), Arb.string(3..20)) { symbol, name ->
            val dao = FakeWatchlistDao()
            val repo = WatchlistRepository(dao)

            repo.addToWatchlist(symbol, name)
            val inserted = dao.getWatchlistItem(symbol)!!
            val originalCreatedAt = inserted.createdAt

            // Perform an update
            repo.updateWatchlistItem(inserted.copy(name = "Updated $name"))

            val updated = dao.getWatchlistItem(symbol)
            updated shouldNotBe null
            updated!!.createdAt shouldBe originalCreatedAt
        }
    }

    /**
     * Property 21 (journal createdAt does not change on update): For any JournalEntry,
     * the createdAt timestamp must remain unchanged after calling
     * JournalRepository.updateEntry().
     *
     * Validates: Requirements 12.3
     */
    @Test
    fun `property 21 - journal createdAt is unchanged after update`() = runTest {
        checkAll(100, arbJournalEntryWithCreatedAt()) { entry ->
            val dao = FakeJournalDao()
            val repo = JournalRepository(dao)

            val id = repo.addEntry(entry)
            val inserted = dao.getEntryById(id)!!
            val originalCreatedAt = inserted.createdAt

            // Perform an update
            repo.updateEntry(inserted.copy(notes = "updated notes"))

            val updated = dao.getEntryById(id)
            updated shouldNotBe null
            updated!!.createdAt shouldBe originalCreatedAt
        }
    }
}

// ── Property 22: Modification Timestamps Are Updated ─────────────────────────

/**
 * Property 22: Modification Timestamps Are Updated
 *
 * For any modification to a watchlist item or journal entry, the Room database should
 * update the modification timestamp to the current UTC time.
 *
 * Validates: Requirements 12.2, 12.4
 *
 * Feature: supabase-integration, Property 22: Modification Timestamps Are Updated
 */
class ModificationTimestampPropertyTest {

    private lateinit var watchlistDao: FakeWatchlistDao
    private lateinit var journalDao: FakeJournalDao
    private lateinit var watchlistRepo: WatchlistRepository
    private lateinit var journalRepo: JournalRepository

    @Before
    fun setup() {
        watchlistDao = FakeWatchlistDao()
        journalDao = FakeJournalDao()
        watchlistRepo = WatchlistRepository(watchlistDao)
        journalRepo = JournalRepository(journalDao)
    }

    /**
     * Property 22 (watchlist modifiedAt >= original after update): For any WatchlistItem
     * updated via WatchlistRepository.updateWatchlistItem(), the stored modifiedAt must
     * be >= the modifiedAt at insertion time.
     *
     * Validates: Requirements 12.2
     */
    @Test
    fun `property 22 - watchlist modifiedAt is >= original modifiedAt after update`() = runTest {
        checkAll(100, arbSymbol(), Arb.string(3..20)) { symbol, name ->
            val dao = FakeWatchlistDao()
            val repo = WatchlistRepository(dao)

            repo.addToWatchlist(symbol, name)
            val inserted = dao.getWatchlistItem(symbol)!!
            val originalModifiedAt = inserted.modifiedAt

            repo.updateWatchlistItem(inserted.copy(name = "Updated $name"))

            val updated = dao.getWatchlistItem(symbol)
            updated shouldNotBe null
            updated!!.modifiedAt shouldBeGreaterThanOrEqualTo originalModifiedAt
        }
    }

    /**
     * Property 22 (watchlist modifiedAt is set to a non-zero UTC timestamp on update):
     * After WatchlistRepository.updateWatchlistItem(), the modifiedAt field must be > 0.
     *
     * Validates: Requirements 12.2
     */
    @Test
    fun `property 22 - watchlist modifiedAt is non-zero after update`() = runTest {
        checkAll(100, arbSymbol(), Arb.string(3..20)) { symbol, name ->
            val dao = FakeWatchlistDao()
            val repo = WatchlistRepository(dao)

            repo.addToWatchlist(symbol, name)
            val inserted = dao.getWatchlistItem(symbol)!!

            val before = System.currentTimeMillis()
            repo.updateWatchlistItem(inserted.copy(name = "Updated $name"))
            val after = System.currentTimeMillis()

            val updated = dao.getWatchlistItem(symbol)
            updated shouldNotBe null
            updated!!.modifiedAt shouldBeGreaterThan 0L
            updated.modifiedAt shouldBeGreaterThanOrEqualTo before
            updated.modifiedAt shouldBeLessThanOrEqualTo after
        }
    }

    /**
     * Property 22 (journal modifiedAt >= original after update): For any JournalEntry
     * updated via JournalRepository.updateEntry(), the stored modifiedAt must be >=
     * the modifiedAt at insertion time.
     *
     * Validates: Requirements 12.4
     */
    @Test
    fun `property 22 - journal modifiedAt is >= original modifiedAt after update`() = runTest {
        checkAll(100, arbJournalEntryWithCreatedAt()) { entry ->
            val dao = FakeJournalDao()
            val repo = JournalRepository(dao)

            val id = repo.addEntry(entry)
            val inserted = dao.getEntryById(id)!!
            val originalModifiedAt = inserted.modifiedAt

            repo.updateEntry(inserted.copy(notes = "updated notes"))

            val updated = dao.getEntryById(id)
            updated shouldNotBe null
            updated!!.modifiedAt shouldBeGreaterThanOrEqualTo originalModifiedAt
        }
    }

    /**
     * Property 22 (journal modifiedAt is set to a non-zero UTC timestamp on update):
     * After JournalRepository.updateEntry(), the modifiedAt field must be > 0.
     *
     * Validates: Requirements 12.4
     */
    @Test
    fun `property 22 - journal modifiedAt is non-zero after update`() = runTest {
        checkAll(100, arbJournalEntryWithCreatedAt()) { entry ->
            val dao = FakeJournalDao()
            val repo = JournalRepository(dao)

            val id = repo.addEntry(entry)
            val inserted = dao.getEntryById(id)!!

            val before = System.currentTimeMillis()
            repo.updateEntry(inserted.copy(notes = "updated notes"))
            val after = System.currentTimeMillis()

            val updated = dao.getEntryById(id)
            updated shouldNotBe null
            updated!!.modifiedAt shouldBeGreaterThan 0L
            updated.modifiedAt shouldBeGreaterThanOrEqualTo before
            updated.modifiedAt shouldBeLessThanOrEqualTo after
        }
    }

    /**
     * Property 22 (watchlist modifiedAt >= createdAt after update): After an update,
     * modifiedAt should be >= createdAt, confirming the modification happened after
     * creation.
     *
     * Validates: Requirements 12.2
     */
    @Test
    fun `property 22 - watchlist modifiedAt is >= createdAt after update`() = runTest {
        checkAll(100, arbSymbol(), Arb.string(3..20)) { symbol, name ->
            val dao = FakeWatchlistDao()
            val repo = WatchlistRepository(dao)

            repo.addToWatchlist(symbol, name)
            val inserted = dao.getWatchlistItem(symbol)!!

            repo.updateWatchlistItem(inserted.copy(name = "Updated $name"))

            val updated = dao.getWatchlistItem(symbol)
            updated shouldNotBe null
            updated!!.modifiedAt shouldBeGreaterThanOrEqualTo updated.createdAt
        }
    }

    /**
     * Property 22 (journal modifiedAt >= createdAt after update): After an update,
     * modifiedAt should be >= createdAt, confirming the modification happened after
     * creation.
     *
     * Validates: Requirements 12.4
     */
    @Test
    fun `property 22 - journal modifiedAt is >= createdAt after update`() = runTest {
        checkAll(100, arbJournalEntryWithCreatedAt()) { entry ->
            val dao = FakeJournalDao()
            val repo = JournalRepository(dao)

            val id = repo.addEntry(entry)
            val inserted = dao.getEntryById(id)!!

            repo.updateEntry(inserted.copy(notes = "updated notes"))

            val updated = dao.getEntryById(id)
            updated shouldNotBe null
            updated!!.modifiedAt shouldBeGreaterThanOrEqualTo updated.createdAt
        }
    }
}
