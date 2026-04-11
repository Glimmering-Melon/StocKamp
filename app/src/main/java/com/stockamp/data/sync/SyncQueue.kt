package com.stockamp.data.sync

/**
 * Persistent queue for sync operations with retry logic.
 * This interface will be fully implemented in task 7.
 */
interface SyncQueue {
    suspend fun enqueue(operation: SyncOperation)
    suspend fun dequeue(): SyncOperation?
    suspend fun peek(): SyncOperation?
    suspend fun remove(operation: SyncOperation)
    suspend fun clear()
    suspend fun getAll(): List<SyncOperation>
    suspend fun size(): Int
}
