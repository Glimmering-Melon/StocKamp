package com.stockamp.data.sync

import android.util.Log
import com.stockamp.data.auth.AuthManager
import com.stockamp.data.local.StocKampDatabase
import com.stockamp.data.model.JournalEntry
import com.stockamp.data.model.WatchlistItem
import com.stockamp.data.network.ConnectivityStatus
import com.stockamp.data.network.NetworkMonitor
import com.stockamp.data.storage.EncryptedStorage
import com.stockamp.data.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncEngine"

/**
 * Core implementation of [SyncEngine].
 *
 * Handles bidirectional sync for Watchlist (tasks 8.2, 8.3) and Journal (tasks 8.4, 8.5):
 *  - Upload: query Room for unsynced items, push to Supabase, update syncedAt
 *  - Download: fetch remote items from Supabase, upsert into Room
 *  - Deletions: items with isDeleted=true are pushed as deletes then removed locally
 *  - Offline: operations are queued via [SyncQueue] and processed when online
 */
@Singleton
class SyncEngineImpl @Inject constructor(
    private val database: StocKampDatabase,
    private val supabaseClient: SupabaseClient,
    private val networkMonitor: NetworkMonitor,
    private val syncQueue: SyncQueue,
    private val encryptedStorage: EncryptedStorage,
    private val authManager: dagger.Lazy<AuthManager> // Lazy to break circular dependency
) : SyncEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -------------------------------------------------------------------------
    // Internal state flows
    // -------------------------------------------------------------------------

    private val _syncStatus = MutableStateFlow(SyncStatus(SyncState.IDLE, 0, null, null))
    private val _pendingCount = MutableStateFlow(0)
    private val _lastSyncTimestamp = MutableStateFlow<Long?>(null)

    // In-memory error log (Requirements 13.4, 13.5)
    private val errorLogs = mutableListOf<SyncErrorLog>()

    init {
        scope.launch {
            networkMonitor.observeConnectivity().collect { status ->
                val newState = when (status) {
                    ConnectivityStatus.AVAILABLE -> SyncState.IDLE
                    ConnectivityStatus.LOSING,
                    ConnectivityStatus.LOST,
                    ConnectivityStatus.UNAVAILABLE -> SyncState.OFFLINE
                }
                updateSyncState(newState)

                // Req 8.5 / 8.6: manage realtime subscription lifecycle on connectivity changes
                when (status) {
                    ConnectivityStatus.AVAILABLE -> {
                        if (currentUserId() != null) {
                            subscribeToRealtimeUpdates()
                        }
                        // Req 10.4: process queued changes within 10 seconds of coming online
                        processSyncQueue()
                    }
                    ConnectivityStatus.LOSING,
                    ConnectivityStatus.LOST,
                    ConnectivityStatus.UNAVAILABLE -> {
                        unsubscribeFromRealtimeUpdates()
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // SyncEngine – status flows
    // -------------------------------------------------------------------------

    override fun getSyncStatus(): Flow<SyncStatus> = _syncStatus.asStateFlow()

    override fun getPendingOperationsCount(): Flow<Int> = _pendingCount.asStateFlow()

    override fun getLastSyncTimestamp(): Flow<Long?> = _lastSyncTimestamp.asStateFlow()

    // -------------------------------------------------------------------------
    // SyncEngine – error logs (Requirements 13.4, 13.5)
    // -------------------------------------------------------------------------

    override fun getErrorLogs(): List<SyncErrorLog> = errorLogs.toList()

    // -------------------------------------------------------------------------
    // SyncEngine – queue management
    // -------------------------------------------------------------------------

    override suspend fun enqueueSyncOperation(operation: SyncOperation) {
        syncQueue.enqueue(operation)
        refreshPendingCount()
    }

    override suspend fun processSyncQueue() {
        val pending = syncQueue.size()
        if (pending == 0) return

        updateSyncState(SyncState.SYNCING)

        try {
            while (true) {
                val operation = syncQueue.peek() ?: break
                processOperation(operation)
                syncQueue.dequeue()
                refreshPendingCount()
            }

            val now = System.currentTimeMillis()
            _lastSyncTimestamp.value = now
            _syncStatus.value = SyncStatus(
                state = SyncState.IDLE,
                pendingCount = 0,
                lastSync = now,
                error = null
            )
        } catch (e: Exception) {
            val count = syncQueue.size()
            _pendingCount.value = count
            logSyncError(
                operation = "processSyncQueue",
                error = e,
                userFriendlyMessage = "Sync failed. Your changes will be retried automatically."
            )
            _syncStatus.value = _syncStatus.value.copy(
                pendingCount = count,
                lastSync = _lastSyncTimestamp.value
            )
        }    }

    // -------------------------------------------------------------------------
    // SyncEngine – watchlist sync (tasks 8.2 + 8.3)
    // -------------------------------------------------------------------------

    /**
     * Bidirectional watchlist sync:
     *  1. Download remote changes and upsert into Room (task 8.3)
     *  2. Upload local unsynced / deleted items to Supabase (task 8.2)
     */
    override suspend fun syncWatchlist(): Result<Unit> = runCatching {
        val userId = currentUserId() ?: return@runCatching

        // --- Download (task 8.3) ---
        supabaseClient.fetchWatchlist(userId).onSuccess { remoteItems ->
            val watchlistDao = database.watchlistDao()
            for (remote in remoteItems) {
                if (remote.isDeleted) {
                    // Remote deletion: remove from local DB if present (deletion wins per Req 7.5)
                    val local = watchlistDao.getWatchlistItem(remote.symbol)
                    if (local != null) {
                        // Check for deletion conflict: local was modified after last sync
                        val localModifiedAfterSync = local.syncedAt == null || local.modifiedAt > local.syncedAt
                        if (localModifiedAfterSync) {
                            Log.d(TAG, "DELETION CONFLICT: watchlist ${remote.symbol} - local was modified but remote deletion wins")
                        }
                        watchlistDao.deleteWatchlistItem(local)
                        Log.d(TAG, "syncWatchlist: deleted local item ${remote.symbol} (remote deletion)")
                    }
                } else {
                    val local = watchlistDao.getWatchlistItem(remote.symbol)
                    if (local == null) {
                        // New remote item – insert with syncedAt stamped
                        watchlistDao.insertWatchlistItem(remote.copy(syncedAt = System.currentTimeMillis()))
                        Log.d(TAG, "syncWatchlist: inserted remote item ${remote.symbol}")
                    } else {
                        // Conflict detection (task 9.1): check if both sides changed since last sync
                        val isConflict = detectConflict(local.modifiedAt, remote.modifiedAt, local.syncedAt)
                        // Last-write-wins resolution (task 9.2)
                        val winner = resolveConflict(local, remote)
                        if (winner.modifiedAt >= remote.modifiedAt && !isConflict) {
                            // Local is already up-to-date; nothing to do
                        } else {
                            val resolvedItem = winner.copy(id = local.id, syncedAt = System.currentTimeMillis())
                            watchlistDao.updateWatchlistItem(resolvedItem)
                            if (isConflict) {
                                val winnerLabel = if (winner.modifiedAt == local.modifiedAt) "local" else "remote"
                                Log.d(TAG, "CONFLICT RESOLVED: watchlist ${remote.symbol} | " +
                                        "local=${local.modifiedAt} remote=${remote.modifiedAt} " +
                                        "winner=$winnerLabel createdAt=${resolvedItem.createdAt}")
                            } else {
                                Log.d(TAG, "syncWatchlist: resolved item ${remote.symbol} -> winner modifiedAt=${winner.modifiedAt}")
                            }
                        }
                    }
                }
            }
        }.onFailure { e ->
            logSyncError(
                operation = "syncWatchlist: download remote items for userId=$userId",
                error = e,
                userFriendlyMessage = "Unable to download watchlist changes. Will retry automatically."
            )
            throw e
        }

        // --- Upload (task 8.2) ---
        val watchlistDao = database.watchlistDao()
        val unsynced = watchlistDao.getUnsyncedWatchlistItems()
        for (item in unsynced) {
            if (item.isDeleted) {
                // Push deletion to Supabase then remove locally (with retry, Req 13.1, 13.2)
                retryWithTokenRefresh { supabaseClient.deleteWatchlistItem(item.id) }
                    .onSuccess {
                        watchlistDao.deleteWatchlistItem(item)
                        Log.d(TAG, "syncWatchlist: pushed deletion for item id=${item.id}")
                    }.onFailure { e ->
                        // Req 13.3: queue for next sync cycle instead of throwing
                        logSyncError(
                            operation = "syncWatchlist: delete watchlist item id=${item.id} symbol=${item.symbol}",
                            error = e,
                            userFriendlyMessage = "Unable to sync watchlist changes. Will retry automatically."
                        )
                        syncQueue.enqueue(SyncOperation.DeleteWatchlist(item.id))
                        refreshPendingCount()
                    }
            } else {
                retryWithTokenRefresh { supabaseClient.upsertWatchlistItem(item) }
                    .onSuccess {
                        watchlistDao.updateWatchlistItem(
                            item.copy(syncedAt = System.currentTimeMillis())
                        )
                        Log.d(TAG, "syncWatchlist: uploaded item ${item.symbol}")
                    }.onFailure { e ->
                        // Req 13.3: queue for next sync cycle instead of throwing
                        logSyncError(
                            operation = "syncWatchlist: upsert watchlist item id=${item.id} symbol=${item.symbol}",
                            error = e,
                            userFriendlyMessage = "Unable to sync watchlist changes. Will retry automatically."
                        )
                        syncQueue.enqueue(SyncOperation.UpsertWatchlist(item.id))
                        refreshPendingCount()
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // SyncEngine – journal sync (tasks 8.4 + 8.5)
    // -------------------------------------------------------------------------

    /**
     * Bidirectional journal sync:
     *  1. Download remote changes and upsert into Room (task 8.5)
     *  2. Upload local unsynced / deleted entries to Supabase (task 8.4)
     */
    override suspend fun syncJournal(): Result<Unit> = runCatching {
        val userId = currentUserId() ?: return@runCatching

        // --- Download (task 8.5) ---
        supabaseClient.fetchJournalEntries(userId).onSuccess { remoteEntries ->
            val journalDao = database.journalDao()
            for (remote in remoteEntries) {
                if (remote.isDeleted) {
                    // Remote deletion: remove from local DB if present (deletion wins per Req 7.5)
                    val local = journalDao.getEntryById(remote.id)
                    if (local != null) {
                        // Check for deletion conflict: local was modified after last sync
                        val localModifiedAfterSync = local.syncedAt == null || local.modifiedAt > local.syncedAt
                        if (localModifiedAfterSync) {
                            Log.d(TAG, "DELETION CONFLICT: journal ${remote.id} - local was modified but remote deletion wins")
                        }
                        journalDao.deleteEntry(local)
                        Log.d(TAG, "syncJournal: deleted local entry id=${remote.id} (remote deletion)")
                    }
                } else {
                    val local = journalDao.getEntryById(remote.id)
                    if (local == null) {
                        // New remote entry – insert with syncedAt stamped
                        journalDao.insertEntry(remote.copy(syncedAt = System.currentTimeMillis()))
                        Log.d(TAG, "syncJournal: inserted remote entry id=${remote.id}")
                    } else {
                        // Conflict detection (task 9.1): check if both sides changed since last sync
                        val isConflict = detectConflict(local.modifiedAt, remote.modifiedAt, local.syncedAt)
                        // Last-write-wins resolution (task 9.2)
                        val winner = resolveConflict(local, remote)
                        if (winner.modifiedAt >= remote.modifiedAt && !isConflict) {
                            // Local is already up-to-date; nothing to do
                        } else {
                            val resolvedEntry = winner.copy(syncedAt = System.currentTimeMillis())
                            journalDao.updateEntry(resolvedEntry)
                            if (isConflict) {
                                val winnerLabel = if (winner.modifiedAt == local.modifiedAt) "local" else "remote"
                                Log.d(TAG, "CONFLICT RESOLVED: journal ${remote.id} | " +
                                        "local=${local.modifiedAt} remote=${remote.modifiedAt} " +
                                        "winner=$winnerLabel createdAt=${resolvedEntry.createdAt}")
                            } else {
                                Log.d(TAG, "syncJournal: resolved entry id=${remote.id} -> winner modifiedAt=${winner.modifiedAt}")
                            }
                        }
                    }
                }
            }
        }.onFailure { e ->
            logSyncError(
                operation = "syncJournal: download remote entries for userId=$userId",
                error = e,
                userFriendlyMessage = "Unable to download journal changes. Will retry automatically."
            )
            throw e
        }

        // --- Upload (task 8.4) ---
        val journalDao = database.journalDao()
        val unsynced = journalDao.getUnsyncedJournalEntries()
        for (entry in unsynced) {
            if (entry.isDeleted) {
                retryWithTokenRefresh { supabaseClient.deleteJournalEntry(entry.id) }
                    .onSuccess {
                        journalDao.deleteEntry(entry)
                        Log.d(TAG, "syncJournal: pushed deletion for entry id=${entry.id}")
                    }.onFailure { e ->
                        // Req 13.3: queue for next sync cycle instead of throwing
                        logSyncError(
                            operation = "syncJournal: delete journal entry id=${entry.id}",
                            error = e,
                            userFriendlyMessage = "Unable to sync journal changes. Will retry automatically."
                        )
                        syncQueue.enqueue(SyncOperation.DeleteJournal(entry.id))
                        refreshPendingCount()
                    }
            } else {
                retryWithTokenRefresh { supabaseClient.upsertJournalEntry(entry) }
                    .onSuccess {
                        journalDao.updateEntry(
                            entry.copy(syncedAt = System.currentTimeMillis())
                        )
                        Log.d(TAG, "syncJournal: uploaded entry id=${entry.id}")
                    }.onFailure { e ->
                        // Req 13.3: queue for next sync cycle instead of throwing
                        logSyncError(
                            operation = "syncJournal: upsert journal entry id=${entry.id}",
                            error = e,
                            userFriendlyMessage = "Unable to sync journal changes. Will retry automatically."
                        )
                        syncQueue.enqueue(SyncOperation.UpsertJournal(entry.id))
                        refreshPendingCount()
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // SyncEngine – full sync
    // -------------------------------------------------------------------------

    override suspend fun performFullSync(): Result<Unit> = runCatching {
        updateSyncState(SyncState.SYNCING)
        try {
            syncWatchlist().getOrThrow()
            syncJournal().getOrThrow()

            val now = System.currentTimeMillis()
            _lastSyncTimestamp.value = now
            _syncStatus.value = SyncStatus(
                state = SyncState.IDLE,
                pendingCount = syncQueue.size(),
                lastSync = now,
                error = null
            )
        } catch (e: Exception) {
            val count = syncQueue.size()
            logSyncError(
                operation = "performFullSync",
                error = e,
                userFriendlyMessage = "Sync failed. Your changes will be retried automatically."
            )
            _syncStatus.value = _syncStatus.value.copy(
                pendingCount = count,
                lastSync = _lastSyncTimestamp.value
            )
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // SyncEngine – migration (task 12.2)
    // -------------------------------------------------------------------------

    /**
     * Uploads all unsynced local records to Supabase as part of the initial migration.
     *
     * Steps:
     *  1. Set sync state to SYNCING.
     *  2. Upload all unsynced WatchlistItem records; stamp syncedAt on success.
     *  3. Upload all unsynced JournalEntry records; stamp syncedAt on success.
     *  4. Update _syncStatus with remaining pending count after each item.
     *  5. Set sync state back to IDLE on completion.
     *
     * Requirements: 11.1, 11.2, 11.5
     */
    override suspend fun performInitialMigration(): Result<Unit> = runCatching {
        Log.d(TAG, "performInitialMigration: starting initial data migration")
        updateSyncState(SyncState.SYNCING)

        val watchlistDao = database.watchlistDao()
        val journalDao = database.journalDao()

        val watchlistItems = watchlistDao.getUnsyncedWatchlistItems()
        val journalEntries = journalDao.getUnsyncedJournalEntries()

        var pending = watchlistItems.size + journalEntries.size
        _syncStatus.value = _syncStatus.value.copy(pendingCount = pending)
        Log.d(TAG, "performInitialMigration: $pending records to migrate (${watchlistItems.size} watchlist, ${journalEntries.size} journal)")

        val failedCount: Int
        run {
            var failures = 0

            // Upload watchlist items (Req 11.1)
            for (item in watchlistItems) {
                supabaseClient.upsertWatchlistItem(item).onSuccess { _ ->
                    watchlistDao.updateWatchlistItem(item.copy(syncedAt = System.currentTimeMillis()))
                    Log.d(TAG, "performInitialMigration: uploaded watchlist item ${item.symbol}")
                }.onFailure { e ->
                    logSyncError(
                        operation = "performInitialMigration: upsert watchlist item id=${item.id} symbol=${item.symbol}",
                        error = e,
                        userFriendlyMessage = "Some items could not be uploaded. They will be retried on next sync."
                    )
                    failures++
                }
                pending--
                _syncStatus.value = _syncStatus.value.copy(pendingCount = pending)
            }

            // Upload journal entries (Req 11.2)
            for (entry in journalEntries) {
                supabaseClient.upsertJournalEntry(entry).onSuccess { _ ->
                    journalDao.updateEntry(entry.copy(syncedAt = System.currentTimeMillis()))
                    Log.d(TAG, "performInitialMigration: uploaded journal entry id=${entry.id}")
                }.onFailure { e ->
                    logSyncError(
                        operation = "performInitialMigration: upsert journal entry id=${entry.id}",
                        error = e,
                        userFriendlyMessage = "Some journal entries could not be uploaded. They will be retried on next sync."
                    )
                    failures++
                }
                pending--
                _syncStatus.value = _syncStatus.value.copy(pendingCount = pending)
            }

            failedCount = failures
        }

        if (failedCount > 0) {
            // Partial failure: leave migration incomplete so failed records (syncedAt=null)
            // are retried on the next sync attempt (Req 11.4).
            Log.w(TAG, "performInitialMigration: $failedCount record(s) failed to upload; will retry on next sync")
            _syncStatus.value = _syncStatus.value.copy(
                state = SyncState.ERROR,
                error = "Some items could not be uploaded. They will be retried on next sync."
            )
            throw Exception("Partial migration failure: $failedCount record(s) failed to upload")
        }

        val now = System.currentTimeMillis()
        _lastSyncTimestamp.value = now
        _syncStatus.value = SyncStatus(
            state = SyncState.IDLE,
            pendingCount = 0,
            lastSync = now,
            error = null
        )
        encryptedStorage.storeMigrationComplete(true)
        Log.d(TAG, "performInitialMigration: migration complete")
    }

    // -------------------------------------------------------------------------
    // SyncEngine – account deletion (task 14.2, Requirements 14.2, 14.3)
    // -------------------------------------------------------------------------

    /**
     * Deletes all Watchlist and Journal data for [userId] from Supabase.
     *
     * This method only removes remote data. Local Room tables are NOT touched here so
     * that local data is preserved if any remote operation fails (Req 14.6).
     * Call [clearLocalUserData] only after all remote deletions succeed.
     *
     * Requirements: 14.2, 14.3
     */
    override suspend fun deleteUserData(userId: String): Result<Unit> = runCatching {
        Log.d(TAG, "deleteUserData: deleting all remote data for userId=$userId")

        // Delete all remote data via SupabaseClient (Req 14.2, 14.3)
        supabaseClient.deleteUserData(userId).getOrThrow()

        Log.d(TAG, "deleteUserData: remote data deleted for userId=$userId")
    }

    /**
     * Clears all local Room tables and the sync queue.
     *
     * Must only be called after all remote deletion operations have succeeded, so that
     * local data is preserved if any remote step fails (Req 14.6).
     *
     * Requirements: 14.4
     */
    override suspend fun clearLocalUserData() {
        Log.d(TAG, "clearLocalUserData: clearing local Room tables and sync queue")
        database.watchlistDao().deleteAllWatchlistItems()
        database.journalDao().deleteAllJournalEntries()
        syncQueue.clear()
        refreshPendingCount()
        Log.d(TAG, "clearLocalUserData: local data cleared")
    }

    // -------------------------------------------------------------------------
    // SyncEngine – realtime stubs (task 11)
    // -------------------------------------------------------------------------

    override suspend fun subscribeToRealtimeUpdates() {
        val userId = currentUserId() ?: run {
            Log.w(TAG, "subscribeToRealtimeUpdates: no authenticated user, skipping")
            return
        }

        Log.d(TAG, "subscribeToRealtimeUpdates: subscribing for userId=$userId")

        supabaseClient.subscribeToWatchlistChanges(userId) { item, changeType ->
            Log.d(TAG, "realtime watchlist event: $changeType symbol=${item.symbol}")
            scope.launch {
                val watchlistDao = database.watchlistDao()
                val now = System.currentTimeMillis()
                when (changeType) {
                    com.stockamp.data.supabase.ChangeType.INSERT -> {
                        watchlistDao.insertWatchlistItem(item.copy(syncedAt = now))
                        Log.d(TAG, "realtime: inserted watchlist item ${item.symbol}")
                    }
                    com.stockamp.data.supabase.ChangeType.UPDATE -> {
                        val local = watchlistDao.getWatchlistItemById(item.id)
                        if (local != null) {
                            watchlistDao.updateWatchlistItem(item.copy(id = local.id, syncedAt = now))
                        } else {
                            watchlistDao.insertWatchlistItem(item.copy(syncedAt = now))
                        }
                        Log.d(TAG, "realtime: updated watchlist item ${item.symbol}")
                    }
                    com.stockamp.data.supabase.ChangeType.DELETE -> {
                        val local = watchlistDao.getWatchlistItemById(item.id)
                        if (local != null) {
                            watchlistDao.deleteWatchlistItem(local)
                        }
                        Log.d(TAG, "realtime: deleted watchlist item ${item.symbol}")
                    }
                }
            }
        }

        supabaseClient.subscribeToJournalChanges(userId) { entry, changeType ->
            Log.d(TAG, "realtime journal event: $changeType id=${entry.id}")
            scope.launch {
                val journalDao = database.journalDao()
                val now = System.currentTimeMillis()
                when (changeType) {
                    com.stockamp.data.supabase.ChangeType.INSERT -> {
                        journalDao.insertEntry(entry.copy(syncedAt = now))
                        Log.d(TAG, "realtime: inserted journal entry id=${entry.id}")
                    }
                    com.stockamp.data.supabase.ChangeType.UPDATE -> {
                        val local = journalDao.getEntryById(entry.id)
                        if (local != null) {
                            journalDao.updateEntry(entry.copy(syncedAt = now))
                        } else {
                            journalDao.insertEntry(entry.copy(syncedAt = now))
                        }
                        Log.d(TAG, "realtime: updated journal entry id=${entry.id}")
                    }
                    com.stockamp.data.supabase.ChangeType.DELETE -> {
                        val local = journalDao.getEntryById(entry.id)
                        if (local != null) {
                            journalDao.deleteEntry(local)
                        }
                        Log.d(TAG, "realtime: deleted journal entry id=${entry.id}")
                    }
                }
            }
        }
    }

    override suspend fun unsubscribeFromRealtimeUpdates() {
        Log.d(TAG, "unsubscribeFromRealtimeUpdates: unsubscribing from all realtime channels")
        supabaseClient.unsubscribeAll()
    }


    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Processes a single [SyncOperation] from the queue by calling the appropriate
     * Supabase client method.  Offline-queued operations are dispatched here when
     * connectivity resumes.
     */
    private suspend fun processOperation(operation: SyncOperation) {
        val userId = currentUserId() ?: return
        when (operation) {
            is SyncOperation.UpsertWatchlist -> {
                val item = database.watchlistDao().getWatchlistItemById(operation.itemId) ?: return
                retryWithTokenRefresh { supabaseClient.upsertWatchlistItem(item) }
                    .onSuccess {
                        database.watchlistDao().updateWatchlistItem(
                            item.copy(syncedAt = System.currentTimeMillis())
                        )
                        Log.d(TAG, "processOperation: upserted watchlist item id=${operation.itemId}")
                    }.onFailure { e ->
                        logSyncError(
                            operation = "processOperation: upsert watchlist item id=${operation.itemId}",
                            error = e,
                            userFriendlyMessage = "Unable to sync watchlist changes. Will retry automatically."
                        )
                        throw e
                    }
            }
            is SyncOperation.DeleteWatchlist -> {
                retryWithTokenRefresh { supabaseClient.deleteWatchlistItem(operation.id) }
                    .onSuccess {
                        Log.d(TAG, "processOperation: deleted watchlist item id=${operation.id}")
                    }.onFailure { e ->
                        logSyncError(
                            operation = "processOperation: delete watchlist item id=${operation.id}",
                            error = e,
                            userFriendlyMessage = "Unable to sync watchlist changes. Will retry automatically."
                        )
                        throw e
                    }
            }
            is SyncOperation.UpsertJournal -> {
                val entry = database.journalDao().getEntryById(operation.entryId) ?: return
                retryWithTokenRefresh { supabaseClient.upsertJournalEntry(entry) }
                    .onSuccess {
                        database.journalDao().updateEntry(
                            entry.copy(syncedAt = System.currentTimeMillis())
                        )
                        Log.d(TAG, "processOperation: upserted journal entry id=${operation.entryId}")
                    }.onFailure { e ->
                        logSyncError(
                            operation = "processOperation: upsert journal entry id=${operation.entryId}",
                            error = e,
                            userFriendlyMessage = "Unable to sync journal changes. Will retry automatically."
                        )
                        throw e
                    }
            }
            is SyncOperation.DeleteJournal -> {
                retryWithTokenRefresh { supabaseClient.deleteJournalEntry(operation.id) }
                    .onSuccess {
                        Log.d(TAG, "processOperation: deleted journal entry id=${operation.id}")
                    }.onFailure { e ->
                        logSyncError(
                            operation = "processOperation: delete journal entry id=${operation.id}",
                            error = e,
                            userFriendlyMessage = "Unable to sync journal changes. Will retry automatically."
                        )
                        throw e
                    }
            }
        }
    }

    /**
     * Last-write-wins conflict resolution for [WatchlistItem].
     *
     * Rules (Requirements 7.1, 7.2, 7.3, 12.6):
     *  - The record with the higher [modifiedAt] wins.
     *  - On a tie, remote is preferred (deterministic tie-breaking).
     *  - The winner's [createdAt] is set to the minimum of both versions so the
     *    original creation timestamp is always preserved.
     *
     * @return A copy of the winning record with the preserved [createdAt].
     */
    private fun resolveConflict(local: WatchlistItem, remote: WatchlistItem): WatchlistItem {
        val winner = if (local.modifiedAt > remote.modifiedAt) local else remote
        val preservedCreatedAt = minOf(local.createdAt, remote.createdAt)
        return winner.copy(createdAt = preservedCreatedAt)
    }

    /**
     * Last-write-wins conflict resolution for [JournalEntry].
     *
     * Same rules as the [WatchlistItem] overload.
     */
    private fun resolveConflict(local: JournalEntry, remote: JournalEntry): JournalEntry {
        val winner = if (local.modifiedAt > remote.modifiedAt) local else remote
        val preservedCreatedAt = minOf(local.createdAt, remote.createdAt)
        return winner.copy(createdAt = preservedCreatedAt)
    }

    /**
     * Detects whether a true conflict exists between a local and remote record.
     *
     * A conflict is defined as both sides having been independently modified since
     * the last successful sync:
     *  - local was modified after [localSyncedAt]
     *  - remote was modified after [localSyncedAt]
     *
     * When [localSyncedAt] is null the record has never been synced, so any
     * difference between the two timestamps is treated as a conflict.
     *
     * Requirement 12.5: The Sync_Engine SHALL use modification timestamps for
     * conflict resolution decisions.
     *
     * @return `true` if both local and remote have changed since the last sync.
     */
    private fun detectConflict(
        localModifiedAt: Long,
        remoteModifiedAt: Long,
        localSyncedAt: Long?
    ): Boolean {
        if (localSyncedAt == null) {
            // Never synced – conflict only if both sides differ
            return localModifiedAt != remoteModifiedAt
        }
        val localChanged = localModifiedAt > localSyncedAt
        val remoteChanged = remoteModifiedAt > localSyncedAt
        return localChanged && remoteChanged
    }

    /**
     * Retries [block] up to [maxAttempts] times on network/IO errors using exponential backoff.
     *
     * Delays between attempts: 1 s, 2 s, 4 s (doubling each time).
     * Auth errors (indicated by [isAuthError]) are NOT retried – they propagate immediately
     * so that [retryWithTokenRefresh] can handle them at a higher level.
     *
     * Requirements: 13.1
     */
    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 3,
        block: suspend () -> Result<T>
    ): Result<T> {
        val backoffDelays = longArrayOf(1_000L, 2_000L, 4_000L)
        var lastResult: Result<T> = Result.failure(Exception("No attempts made"))
        for (attempt in 0 until maxAttempts) {
            lastResult = block()
            if (lastResult.isSuccess) return lastResult
            val error = lastResult.exceptionOrNull()
            // Do not retry auth errors – surface them immediately
            if (error != null && isAuthError(error)) return lastResult
            if (attempt < maxAttempts - 1) {
                val delayMs = backoffDelays.getOrElse(attempt) { backoffDelays.last() }
                Log.w(TAG, "retryWithBackoff: attempt ${attempt + 1} failed (${error?.message}), retrying in ${delayMs}ms")
                delay(delayMs)
            }
        }
        return lastResult
    }

    /**
     * Wraps [retryWithBackoff] with authentication-error recovery.
     *
     * If [retryWithBackoff] surfaces an auth error, this helper:
     *  1. Attempts a token refresh via [AuthManager.refreshToken].
     *  2. If the refresh succeeds, retries [block] once more with fresh credentials.
     *  3. If the refresh fails, returns the original auth error immediately.
     *
     * Requirements: 13.2
     */
    private suspend fun <T> retryWithTokenRefresh(
        maxAttempts: Int = 3,
        block: suspend () -> Result<T>
    ): Result<T> {
        val result = retryWithBackoff(maxAttempts, block)
        if (result.isSuccess) return result

        val error = result.exceptionOrNull()
        if (error == null || !isAuthError(error)) return result

        // Auth error detected – attempt token refresh then retry once
        Log.w(TAG, "retryWithTokenRefresh: auth error detected (${error.message}), attempting token refresh")
        val refreshResult = authManager.get().refreshToken()
        if (refreshResult.isFailure) {
            Log.e(TAG, "retryWithTokenRefresh: token refresh failed", refreshResult.exceptionOrNull())
            return result // Return original auth error
        }

        Log.d(TAG, "retryWithTokenRefresh: token refreshed successfully, retrying operation")
        return block()
    }

    /**
     * Returns true if [error] represents an authentication/authorization failure that
     * should not be retried with exponential backoff (handled separately in task 13.2).
     */
    private fun isAuthError(error: Throwable): Boolean {
        val msg = error.message?.lowercase() ?: return false
        return msg.contains("unauthorized") ||
               msg.contains("unauthenticated") ||
               msg.contains("jwt") ||
               msg.contains("auth") ||
               msg.contains("403") ||
               msg.contains("401")
    }

    /** Returns the current authenticated user's ID, or null if not signed in. */
    private fun currentUserId(): String? =
        supabaseClient.getCurrentSession()?.user?.id

    private suspend fun refreshPendingCount() {
        val count = syncQueue.size()
        _pendingCount.value = count
        _syncStatus.value = _syncStatus.value.copy(pendingCount = count)
    }

    private fun updateSyncState(newState: SyncState) {
        _syncStatus.value = _syncStatus.value.copy(state = newState)
    }

    /**
     * Records a structured error log entry and emits a log statement with full context.
     *
     * The raw [error] details are stored in [SyncErrorLog] for debugging, while
     * [userFriendlyMessage] is what gets surfaced in [SyncStatus.error] so that
     * technical details are never shown to the user (Requirement 13.5).
     *
     * @param operation          Human-readable description of the failing operation.
     * @param error              The original exception (logged with stack trace).
     * @param userFriendlyMessage A short, non-technical message safe to display to the user.
     */
    private fun logSyncError(
        operation: String,
        error: Throwable,
        userFriendlyMessage: String = "Sync failed. Your changes will be retried automatically."
    ) {
        Log.e(TAG, "Sync error | operation=$operation | error=${error.message}", error)
        errorLogs.add(
            SyncErrorLog(
                timestamp = System.currentTimeMillis(),
                operation = operation,
                errorMessage = error.message ?: error.toString(),
                isUserFriendly = false
            )
        )
        // Update SyncStatus with the user-friendly message (Requirement 13.5)
        _syncStatus.value = _syncStatus.value.copy(
            state = SyncState.ERROR,
            error = userFriendlyMessage
        )
    }
}
