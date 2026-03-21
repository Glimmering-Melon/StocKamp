package com.stockamp.data.sync

import com.stockamp.data.local.StocKampDatabase
import com.stockamp.data.model.SyncQueueItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps SyncOperation subtypes to/from the string keys stored in sync_queue.operationType.
 */
private object OperationType {
    const val UPSERT_WATCHLIST = "UPSERT_WATCHLIST"
    const val DELETE_WATCHLIST = "DELETE_WATCHLIST"
    const val UPSERT_JOURNAL = "UPSERT_JOURNAL"
    const val DELETE_JOURNAL = "DELETE_JOURNAL"
}

/** Converts a [SyncOperation] to a [SyncQueueItem] ready for persistence. */
private fun SyncOperation.toQueueItem(): SyncQueueItem {
    val (type, id) = when (this) {
        is SyncOperation.UpsertWatchlist -> OperationType.UPSERT_WATCHLIST to itemId
        is SyncOperation.DeleteWatchlist -> OperationType.DELETE_WATCHLIST to this.id
        is SyncOperation.UpsertJournal   -> OperationType.UPSERT_JOURNAL   to entryId
        is SyncOperation.DeleteJournal   -> OperationType.DELETE_JOURNAL   to this.id
    }
    // entityData stores the id as a JSON string for forward-compatibility
    return SyncQueueItem(
        operationType = type,
        entityId = id,
        entityData = """{"id":$id}""",
        createdAt = System.currentTimeMillis()
    )
}

/** Converts a persisted [SyncQueueItem] back to a [SyncOperation], or null if unknown type. */
private fun SyncQueueItem.toOperation(): SyncOperation? = when (operationType) {
    OperationType.UPSERT_WATCHLIST -> SyncOperation.UpsertWatchlist(entityId)
    OperationType.DELETE_WATCHLIST -> SyncOperation.DeleteWatchlist(entityId)
    OperationType.UPSERT_JOURNAL   -> SyncOperation.UpsertJournal(entityId)
    OperationType.DELETE_JOURNAL   -> SyncOperation.DeleteJournal(entityId)
    else -> null
}

/**
 * Room-backed FIFO implementation of [SyncQueue].
 *
 * Operations are ordered by [SyncQueueItem.createdAt] (ascending) so the oldest
 * enqueued operation is always dequeued first.  Retry count and last-attempt
 * timestamp are tracked on the [SyncQueueItem] row and can be updated by the
 * caller via [SyncEngine] retry logic.
 */
@Singleton
class SyncQueueImpl @Inject constructor(
    private val database: StocKampDatabase
) : SyncQueue {

    private val dao get() = database.syncQueueDao()

    override suspend fun enqueue(operation: SyncOperation) {
        dao.insert(operation.toQueueItem())
    }

    /**
     * Removes and returns the oldest operation (FIFO).
     * Returns null when the queue is empty.
     */
    override suspend fun dequeue(): SyncOperation? {
        val item = dao.peek() ?: return null
        dao.delete(item)
        return item.toOperation()
    }

    /** Returns the oldest operation without removing it, or null if empty. */
    override suspend fun peek(): SyncOperation? =
        dao.peek()?.toOperation()

    /**
     * Removes the first queue row that matches [operation]'s type and entity id.
     * No-op if no matching row exists.
     */
    override suspend fun remove(operation: SyncOperation) {
        val (type, id) = when (operation) {
            is SyncOperation.UpsertWatchlist -> OperationType.UPSERT_WATCHLIST to operation.itemId
            is SyncOperation.DeleteWatchlist -> OperationType.DELETE_WATCHLIST to operation.id
            is SyncOperation.UpsertJournal   -> OperationType.UPSERT_JOURNAL   to operation.entryId
            is SyncOperation.DeleteJournal   -> OperationType.DELETE_JOURNAL   to operation.id
        }
        val match = dao.getAllOperations().firstOrNull { it.operationType == type && it.entityId == id }
        match?.let { dao.delete(it) }
    }

    override suspend fun clear() {
        dao.clear()
    }

    override suspend fun getAll(): List<SyncOperation> =
        dao.getAllOperations().mapNotNull { it.toOperation() }

    override suspend fun size(): Int =
        dao.getCount()
}
