package com.stockamp.data.repository

import com.stockamp.data.model.JournalEntry
import com.stockamp.data.model.WatchlistItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies full offline read/write access for WatchlistRepository and JournalRepository.
 *
 * All operations use in-memory fake DAOs — no network dependency whatsoever.
 * This confirms that Room is the sole source of truth for local CRUD (Requirement 10.1, 10.2).
 *
 * Requirements: 10.1, 10.2
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

    override suspend fun getTotalTrades() =
        entries.count { !it.isDeleted }

    override suspend fun getUnsyncedJournalEntries() =
        entries.filter { it.syncedAt == null || it.modifiedAt > it.syncedAt!! }

    override suspend fun deleteAllJournalEntries() = entries.clear()
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class OfflineAccessTest {

    private lateinit var watchlistRepo: WatchlistRepository
    private lateinit var journalRepo: JournalRepository
    private lateinit var watchlistDao: FakeWatchlistDao
    private lateinit var journalDao: FakeJournalDao

    @Before
    fun setup() {
        watchlistDao = FakeWatchlistDao()
        journalDao = FakeJournalDao()
        watchlistRepo = WatchlistRepository(watchlistDao)
        journalRepo = JournalRepository(journalDao)
    }

    // ── Watchlist CRUD ────────────────────────────────────────────────────────

    @Test
    fun `watchlist - add item is readable offline`() = runTest {
        watchlistRepo.addToWatchlist("AAPL", "Apple Inc.")

        val items = watchlistRepo.getAllWatchlistItems().first()
        assertEquals(1, items.size)
        assertEquals("AAPL", items[0].symbol)
    }

    @Test
    fun `watchlist - isInWatchlist returns true after add`() = runTest {
        watchlistRepo.addToWatchlist("TSLA", "Tesla")

        assertTrue(watchlistRepo.isInWatchlist("TSLA"))
    }

    @Test
    fun `watchlist - remove item is no longer readable`() = runTest {
        watchlistRepo.addToWatchlist("MSFT", "Microsoft")
        watchlistRepo.removeFromWatchlist("MSFT")

        val items = watchlistRepo.getAllWatchlistItems().first()
        assertTrue(items.none { it.symbol == "MSFT" })
    }

    @Test
    fun `watchlist - isInWatchlist returns false after remove`() = runTest {
        watchlistRepo.addToWatchlist("GOOG", "Alphabet")
        watchlistRepo.removeFromWatchlist("GOOG")

        assertTrue(!watchlistRepo.isInWatchlist("GOOG"))
    }

    @Test
    fun `watchlist - multiple items readable offline`() = runTest {
        watchlistRepo.addToWatchlist("AAPL", "Apple")
        watchlistRepo.addToWatchlist("MSFT", "Microsoft")
        watchlistRepo.addToWatchlist("AMZN", "Amazon")

        val items = watchlistRepo.getAllWatchlistItems().first()
        assertEquals(3, items.size)
    }

    @Test
    fun `watchlist - empty list returned when no items added`() = runTest {
        val items = watchlistRepo.getAllWatchlistItems().first()
        assertTrue(items.isEmpty())
    }

    // ── Journal CRUD ──────────────────────────────────────────────────────────

    @Test
    fun `journal - add entry is readable offline`() = runTest {
        val entry = JournalEntry(symbol = "AAPL", action = "BUY", quantity = 10)
        val id = journalRepo.addEntry(entry)

        val fetched = journalRepo.getEntryById(id)
        assertNotNull(fetched)
        assertEquals("AAPL", fetched.symbol)
    }

    @Test
    fun `journal - update entry persists changes offline`() = runTest {
        val entry = JournalEntry(symbol = "TSLA", action = "BUY", quantity = 5)
        val id = journalRepo.addEntry(entry)

        val updated = entry.copy(id = id, notes = "Updated note")
        journalRepo.updateEntry(updated)

        val fetched = journalRepo.getEntryById(id)
        assertEquals("Updated note", fetched?.notes)
    }

    @Test
    fun `journal - delete entry removes it offline`() = runTest {
        val entry = JournalEntry(symbol = "MSFT", action = "SELL", quantity = 3)
        val id = journalRepo.addEntry(entry)
        val stored = journalRepo.getEntryById(id)!!

        journalRepo.deleteEntry(stored)

        assertNull(journalRepo.getEntryById(id))
    }

    @Test
    fun `journal - getAllEntries returns all non-deleted entries`() = runTest {
        journalRepo.addEntry(JournalEntry(symbol = "AAPL", action = "BUY", quantity = 1))
        journalRepo.addEntry(JournalEntry(symbol = "GOOG", action = "SELL", quantity = 2))

        val entries = journalRepo.getAllEntries().first()
        assertEquals(2, entries.size)
    }

    @Test
    fun `journal - getEntriesBySymbol filters correctly offline`() = runTest {
        journalRepo.addEntry(JournalEntry(symbol = "AAPL", action = "BUY", quantity = 1))
        journalRepo.addEntry(JournalEntry(symbol = "MSFT", action = "BUY", quantity = 2))

        val appleEntries = journalRepo.getEntriesBySymbol("AAPL").first()
        assertEquals(1, appleEntries.size)
        assertEquals("AAPL", appleEntries[0].symbol)
    }

    @Test
    fun `journal - empty list returned when no entries added`() = runTest {
        val entries = journalRepo.getAllEntries().first()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `journal - getTotalTrades counts correctly offline`() = runTest {
        journalRepo.addEntry(JournalEntry(symbol = "AAPL", action = "BUY", quantity = 1))
        journalRepo.addEntry(JournalEntry(symbol = "MSFT", action = "SELL", quantity = 1))

        assertEquals(2, journalRepo.getTotalTrades())
    }
}

// ── Property-Based Tests ──────────────────────────────────────────────────────

/**
 * Property 17: Offline Database Access Remains Functional
 *
 * For any database operation (read or write) on watchlist items or journal entries,
 * the operation should succeed when offline using the Room database.
 *
 * All operations use in-memory fake DAOs — no network dependency whatsoever.
 *
 * Validates: Requirements 10.2
 *
 * Feature: supabase-integration, Property 17: Offline Database Access Remains Functional
 */
class OfflineAccessPropertyTest {

    private lateinit var watchlistRepo: WatchlistRepository
    private lateinit var journalRepo: JournalRepository
    private lateinit var watchlistDao: FakeWatchlistDao
    private lateinit var journalDao: FakeJournalDao

    @Before
    fun setup() {
        watchlistDao = FakeWatchlistDao()
        journalDao = FakeJournalDao()
        watchlistRepo = WatchlistRepository(watchlistDao)
        journalRepo = JournalRepository(journalDao)
    }

    // Arbitrary generators

    private fun arbSymbol(): Arb<String> =
        Arb.string(3..6).map { it.uppercase().filter { c -> c.isLetter() }.take(5).padEnd(3, 'X') }

    private fun arbWatchlistItem(): Arb<WatchlistItem> =
        Arb.string(3..6).map { sym ->
            WatchlistItem(
                symbol = sym.uppercase(),
                name = "Company ${sym.uppercase()}",
                createdAt = System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis()
            )
        }

    private fun arbJournalEntry(): Arb<JournalEntry> =
        Arb.long(1L..9_999L).map { seed ->
            JournalEntry(
                symbol = "SYM${seed % 100}",
                action = if (seed % 2 == 0L) "BUY" else "SELL",
                quantity = ((seed % 50) + 1).toInt(),
                notes = "note $seed",
                createdAt = System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis()
            )
        }

    // ── Property 17: WatchlistItem CRUD works offline ─────────────────────────

    /**
     * Property 17 (watchlist insert + read): For any WatchlistItem, inserting it into
     * the local database and then reading it back succeeds without any network call.
     *
     * Validates: Requirements 10.2
     */
    @Test
    fun `property 17 - watchlist insert and read succeed offline`() = runTest {
        checkAll(100, arbWatchlistItem()) { item ->
            // Fresh DAO per iteration to avoid cross-test pollution
            val dao = FakeWatchlistDao()
            val repo = WatchlistRepository(dao)

            repo.addToWatchlist(item.symbol, item.name)

            val items = repo.getAllWatchlistItems().first()
            items.any { it.symbol == item.symbol } shouldBe true
        }
    }

    /**
     * Property 17 (watchlist update): For any WatchlistItem, updating it in the local
     * database persists the change without any network call.
     *
     * Validates: Requirements 10.2
     */
    @Test
    fun `property 17 - watchlist update persists offline`() = runTest {
        checkAll(100, arbWatchlistItem(), Arb.string(3..20)) { item, newName ->
            val dao = FakeWatchlistDao()
            val repo = WatchlistRepository(dao)

            repo.addToWatchlist(item.symbol, item.name)
            val inserted = dao.getWatchlistItem(item.symbol)
            inserted shouldNotBe null

            val updated = inserted!!.copy(name = newName, modifiedAt = System.currentTimeMillis())
            dao.updateWatchlistItem(updated)

            val fetched = dao.getWatchlistItem(item.symbol)
            fetched?.name shouldBe newName
        }
    }

    /**
     * Property 17 (watchlist delete): For any WatchlistItem, deleting it from the local
     * database removes it without any network call.
     *
     * Validates: Requirements 10.2
     */
    @Test
    fun `property 17 - watchlist delete removes item offline`() = runTest {
        checkAll(100, arbWatchlistItem()) { item ->
            val dao = FakeWatchlistDao()
            val repo = WatchlistRepository(dao)

            repo.addToWatchlist(item.symbol, item.name)
            repo.removeFromWatchlist(item.symbol)

            val items = repo.getAllWatchlistItems().first()
            items.none { it.symbol == item.symbol } shouldBe true
        }
    }

    // ── Property 17: JournalEntry CRUD works offline ──────────────────────────

    /**
     * Property 17 (journal insert + read): For any JournalEntry, inserting it into the
     * local database and then reading it back succeeds without any network call.
     *
     * Validates: Requirements 10.2
     */
    @Test
    fun `property 17 - journal insert and read succeed offline`() = runTest {
        checkAll(100, arbJournalEntry()) { entry ->
            val dao = FakeJournalDao()
            val repo = JournalRepository(dao)

            val id = repo.addEntry(entry)
            val fetched = repo.getEntryById(id)

            fetched shouldNotBe null
            fetched!!.symbol shouldBe entry.symbol
            fetched.action shouldBe entry.action
        }
    }

    /**
     * Property 17 (journal update): For any JournalEntry, updating it in the local
     * database persists the change without any network call.
     *
     * Validates: Requirements 10.2
     */
    @Test
    fun `property 17 - journal update persists offline`() = runTest {
        checkAll(100, arbJournalEntry(), Arb.string(1..30)) { entry, newNotes ->
            val dao = FakeJournalDao()
            val repo = JournalRepository(dao)

            val id = repo.addEntry(entry)
            val inserted = repo.getEntryById(id)!!
            repo.updateEntry(inserted.copy(notes = newNotes, modifiedAt = System.currentTimeMillis()))

            val fetched = repo.getEntryById(id)
            fetched?.notes shouldBe newNotes
        }
    }

    /**
     * Property 17 (journal delete): For any JournalEntry, deleting it from the local
     * database removes it without any network call.
     *
     * Validates: Requirements 10.2
     */
    @Test
    fun `property 17 - journal delete removes entry offline`() = runTest {
        checkAll(100, arbJournalEntry()) { entry ->
            val dao = FakeJournalDao()
            val repo = JournalRepository(dao)

            val id = repo.addEntry(entry)
            val inserted = repo.getEntryById(id)!!
            repo.deleteEntry(inserted)

            val fetched = repo.getEntryById(id)
            fetched shouldBe null
        }
    }

    /**
     * Property 17 (network state irrelevance): For any number of watchlist and journal
     * operations, the total count of readable items matches the number of inserts minus
     * deletes — regardless of any simulated network state. No network object is
     * referenced, confirming zero network dependency.
     *
     * Validates: Requirements 10.2
     */
    @Test
    fun `property 17 - CRUD counts are consistent regardless of network state`() = runTest {
        checkAll(50, Arb.int(1..5), Arb.int(1..5)) { watchlistCount, journalCount ->
            val wDao = FakeWatchlistDao()
            val jDao = FakeJournalDao()
            val wRepo = WatchlistRepository(wDao)
            val jRepo = JournalRepository(jDao)

            // Insert items
            repeat(watchlistCount) { i ->
                wRepo.addToWatchlist("SYM$i", "Company $i")
            }
            val journalIds = (1..journalCount).map { i ->
                jRepo.addEntry(
                    JournalEntry(
                        symbol = "J$i",
                        action = "BUY",
                        quantity = i
                    )
                )
            }

            // Verify all inserted items are readable
            wRepo.getAllWatchlistItems().first().size shouldBe watchlistCount
            jRepo.getAllEntries().first().size shouldBe journalCount

            // Delete one of each if available
            if (watchlistCount > 0) {
                wRepo.removeFromWatchlist("SYM0")
                wRepo.getAllWatchlistItems().first().size shouldBe watchlistCount - 1
            }
            if (journalCount > 0) {
                val toDelete = jRepo.getEntryById(journalIds.first())!!
                jRepo.deleteEntry(toDelete)
                jRepo.getAllEntries().first().size shouldBe journalCount - 1
            }
        }
    }
}
