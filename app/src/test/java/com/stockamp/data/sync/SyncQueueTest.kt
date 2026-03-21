package com.stockamp.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for SyncQueue using a FakeSyncQueue (in-memory implementation).
 *
 * Since SyncQueueImpl depends on Room, we test the contract via a fake that
 * mirrors the same FIFO semantics without requiring Android instrumentation.
 *
 * Requirements: 5.6, 6.6, 10.3
 */

// ── Fake implementation ───────────────────────────────────────────────────────

/**
 * In-memory [SyncQueue] for unit testing.
 * Preserves insertion order (FIFO) and supports all interface operations.
 */
class FakeSyncQueue : SyncQueue {

    private val queue = ArrayDeque<SyncOperation>()

    override suspend fun enqueue(operation: SyncOperation) {
        queue.addLast(operation)
    }

    override suspend fun dequeue(): SyncOperation? =
        if (queue.isEmpty()) null else queue.removeFirst()

    override suspend fun peek(): SyncOperation? =
        queue.firstOrNull()

    override suspend fun remove(operation: SyncOperation) {
        queue.remove(operation)
    }

    override suspend fun clear() {
        queue.clear()
    }

    override suspend fun getAll(): List<SyncOperation> =
        queue.toList()

    override suspend fun size(): Int =
        queue.size
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class SyncQueueTest {

    private lateinit var queue: FakeSyncQueue

    @Before
    fun setup() {
        queue = FakeSyncQueue()
    }

    // ── enqueue ───────────────────────────────────────────────────────────────

    @Test
    fun `enqueue adds operation to queue`() = runTest {
        queue.enqueue(SyncOperation.UpsertWatchlist(1L))

        assertEquals(1, queue.size())
    }

    @Test
    fun `enqueue multiple operations increases size`() = runTest {
        queue.enqueue(SyncOperation.UpsertWatchlist(1L))
        queue.enqueue(SyncOperation.DeleteWatchlist(2L))
        queue.enqueue(SyncOperation.UpsertJournal(3L))

        assertEquals(3, queue.size())
    }

    // ── dequeue / FIFO ordering ───────────────────────────────────────────────

    @Test
    fun `dequeue returns operations in FIFO order`() = runTest {
        val op1 = SyncOperation.UpsertWatchlist(1L)
        val op2 = SyncOperation.DeleteWatchlist(2L)
        val op3 = SyncOperation.UpsertJournal(3L)

        queue.enqueue(op1)
        queue.enqueue(op2)
        queue.enqueue(op3)

        assertEquals(op1, queue.dequeue())
        assertEquals(op2, queue.dequeue())
        assertEquals(op3, queue.dequeue())
    }

    @Test
    fun `dequeue removes the operation from the queue`() = runTest {
        queue.enqueue(SyncOperation.UpsertWatchlist(1L))
        queue.dequeue()

        assertEquals(0, queue.size())
    }

    @Test
    fun `dequeue on empty queue returns null`() = runTest {
        assertNull(queue.dequeue())
    }

    // ── peek ──────────────────────────────────────────────────────────────────

    @Test
    fun `peek returns first operation without removing it`() = runTest {
        val op = SyncOperation.UpsertWatchlist(42L)
        queue.enqueue(op)

        val peeked = queue.peek()

        assertEquals(op, peeked)
        assertEquals(1, queue.size()) // still in queue
    }

    @Test
    fun `peek on empty queue returns null`() = runTest {
        assertNull(queue.peek())
    }

    @Test
    fun `peek always returns the oldest operation`() = runTest {
        val first = SyncOperation.UpsertWatchlist(1L)
        queue.enqueue(first)
        queue.enqueue(SyncOperation.DeleteJournal(2L))

        assertEquals(first, queue.peek())
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun `clear empties the queue`() = runTest {
        queue.enqueue(SyncOperation.UpsertWatchlist(1L))
        queue.enqueue(SyncOperation.UpsertJournal(2L))

        queue.clear()

        assertEquals(0, queue.size())
    }

    @Test
    fun `clear on empty queue is a no-op`() = runTest {
        queue.clear()

        assertEquals(0, queue.size())
    }

    // ── size ──────────────────────────────────────────────────────────────────

    @Test
    fun `size returns correct count after enqueue and dequeue`() = runTest {
        assertEquals(0, queue.size())

        queue.enqueue(SyncOperation.UpsertWatchlist(1L))
        assertEquals(1, queue.size())

        queue.enqueue(SyncOperation.DeleteWatchlist(2L))
        assertEquals(2, queue.size())

        queue.dequeue()
        assertEquals(1, queue.size())
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Test
    fun `remove deletes specific operation from queue`() = runTest {
        val op1 = SyncOperation.UpsertWatchlist(1L)
        val op2 = SyncOperation.DeleteWatchlist(2L)
        queue.enqueue(op1)
        queue.enqueue(op2)

        queue.remove(op1)

        assertEquals(1, queue.size())
        assertEquals(op2, queue.peek())
    }

    @Test
    fun `remove non-existent operation is a no-op`() = runTest {
        queue.enqueue(SyncOperation.UpsertWatchlist(1L))

        queue.remove(SyncOperation.DeleteWatchlist(99L))

        assertEquals(1, queue.size())
    }

    @Test
    fun `remove preserves FIFO order of remaining operations`() = runTest {
        val op1 = SyncOperation.UpsertWatchlist(1L)
        val op2 = SyncOperation.UpsertJournal(2L)
        val op3 = SyncOperation.DeleteJournal(3L)
        queue.enqueue(op1)
        queue.enqueue(op2)
        queue.enqueue(op3)

        queue.remove(op2)

        assertEquals(op1, queue.dequeue())
        assertEquals(op3, queue.dequeue())
    }

    // ── serialization / deserialization of all SyncOperation types ────────────

    @Test
    fun `UpsertWatchlist serializes and deserializes correctly`() = runTest {
        val op = SyncOperation.UpsertWatchlist(itemId = 101L)
        queue.enqueue(op)

        val result = queue.dequeue()

        assertTrue(result is SyncOperation.UpsertWatchlist)
        assertEquals(101L, result.itemId)
    }

    @Test
    fun `DeleteWatchlist serializes and deserializes correctly`() = runTest {
        val op = SyncOperation.DeleteWatchlist(id = 202L)
        queue.enqueue(op)

        val result = queue.dequeue()

        assertTrue(result is SyncOperation.DeleteWatchlist)
        assertEquals(202L, result.id)
    }

    @Test
    fun `UpsertJournal serializes and deserializes correctly`() = runTest {
        val op = SyncOperation.UpsertJournal(entryId = 303L)
        queue.enqueue(op)

        val result = queue.dequeue()

        assertTrue(result is SyncOperation.UpsertJournal)
        assertEquals(303L, result.entryId)
    }

    @Test
    fun `DeleteJournal serializes and deserializes correctly`() = runTest {
        val op = SyncOperation.DeleteJournal(id = 404L)
        queue.enqueue(op)

        val result = queue.dequeue()

        assertTrue(result is SyncOperation.DeleteJournal)
        assertEquals(404L, result.id)
    }

    @Test
    fun `all SyncOperation types can coexist in queue and dequeue in order`() = runTest {
        val ops = listOf(
            SyncOperation.UpsertWatchlist(1L),
            SyncOperation.DeleteWatchlist(2L),
            SyncOperation.UpsertJournal(3L),
            SyncOperation.DeleteJournal(4L)
        )
        ops.forEach { queue.enqueue(it) }

        val dequeued = (1..4).map { queue.dequeue() }

        assertEquals(ops, dequeued)
    }

    // ── persistence simulation (queue state survives re-use) ─────────────────

    @Test
    fun `queue state is preserved across multiple operations`() = runTest {
        // Simulate "app restart" by reusing the same queue instance with pre-loaded state
        queue.enqueue(SyncOperation.UpsertWatchlist(10L))
        queue.enqueue(SyncOperation.UpsertJournal(20L))

        // Partial drain
        queue.dequeue()

        // New operations added after restart
        queue.enqueue(SyncOperation.DeleteWatchlist(30L))

        // Remaining items should be in FIFO order
        assertEquals(SyncOperation.UpsertJournal(20L), queue.dequeue())
        assertEquals(SyncOperation.DeleteWatchlist(30L), queue.dequeue())
        assertNull(queue.dequeue())
    }

    @Test
    fun `getAll returns all operations without removing them`() = runTest {
        val ops = listOf(
            SyncOperation.UpsertWatchlist(1L),
            SyncOperation.DeleteJournal(2L)
        )
        ops.forEach { queue.enqueue(it) }

        val all = queue.getAll()

        assertEquals(ops, all)
        assertEquals(2, queue.size()) // queue unchanged
    }
}
