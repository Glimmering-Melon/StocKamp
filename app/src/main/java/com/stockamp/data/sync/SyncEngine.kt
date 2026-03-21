package com.stockamp.data.sync

import kotlinx.coroutines.flow.Flow

/**
 * Coordinates bidirectional synchronization between Room and Supabase.
 * This interface will be fully implemented in task 8.
 */
interface SyncEngine {
    // Sync Operations
    suspend fun syncWatchlist(): Result<Unit>
    suspend fun syncJournal(): Result<Unit>
    suspend fun performFullSync(): Result<Unit>
    suspend fun performInitialMigration(): Result<Unit>
    
    // Real-time Subscriptions
    suspend fun subscribeToRealtimeUpdates()
    suspend fun unsubscribeFromRealtimeUpdates()
    
    // Sync Status
    fun getSyncStatus(): Flow<SyncStatus>
    fun getPendingOperationsCount(): Flow<Int>
    fun getLastSyncTimestamp(): Flow<Long?>
    
    // Manual Triggers
    suspend fun enqueueSyncOperation(operation: SyncOperation)
    suspend fun processSyncQueue()

    // Account Deletion (Requirements 14.2, 14.3, 14.6)
    /** Deletes all remote Watchlist and Journal data for [userId] from Supabase. Does NOT touch local data. */
    suspend fun deleteUserData(userId: String): Result<Unit>

    /** Clears all local Room data and the sync queue. Called only after all remote deletions succeed. */
    suspend fun clearLocalUserData()

    // Error Logs (Requirements 13.4, 13.5)
    fun getErrorLogs(): List<SyncErrorLog>
}

/**
 * A structured log entry for a sync error.
 *
 * @param timestamp       When the error occurred (epoch millis).
 * @param operation       Human-readable description of the operation that failed
 *                        (e.g. "upsert watchlist item AAPL").
 * @param errorMessage    The raw technical error message (for debugging / server logs).
 * @param isUserFriendly  Whether [errorMessage] is safe to show directly to the user.
 *                        When false, callers should substitute a generic message.
 */
data class SyncErrorLog(
    val timestamp: Long,
    val operation: String,
    val errorMessage: String,
    val isUserFriendly: Boolean
)

/**
 * Represents the current synchronization status.
 */
data class SyncStatus(
    val state: SyncState,
    val pendingCount: Int,
    val lastSync: Long?,
    val error: String?
)

/**
 * Possible synchronization states.
 */
enum class SyncState {
    IDLE, SYNCING, ERROR, OFFLINE
}

/**
 * Represents a detected conflict between a local and remote record.
 *
 * A conflict exists when both the local and remote versions have been modified
 * since the last successful sync (i.e., both sides changed independently).
 *
 * @param localModifiedAt  Epoch-millis timestamp of the local record's last modification.
 * @param remoteModifiedAt Epoch-millis timestamp of the remote record's last modification.
 * @param syncedAt         Epoch-millis timestamp of the last successful sync, or null if
 *                         the record has never been synced.
 */
data class ConflictInfo(
    val localModifiedAt: Long,
    val remoteModifiedAt: Long,
    val syncedAt: Long?
)

/**
 * Represents a synchronization operation to be performed.
 */
sealed class SyncOperation {
    data class UpsertWatchlist(val itemId: Long) : SyncOperation()
    data class DeleteWatchlist(val id: Long) : SyncOperation()
    data class UpsertJournal(val entryId: Long) : SyncOperation()
    data class DeleteJournal(val id: Long) : SyncOperation()
}
