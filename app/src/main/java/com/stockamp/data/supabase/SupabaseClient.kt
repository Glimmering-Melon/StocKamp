package com.stockamp.data.supabase

import com.stockamp.data.model.JournalEntry
import com.stockamp.data.model.UserProfile
import com.stockamp.data.model.WatchlistItem
import io.github.jan.supabase.gotrue.user.UserSession

/**
 * Client interface for interacting with Supabase backend services.
 * Provides methods for authentication, data operations, and real-time subscriptions.
 */
interface SupabaseClient {
    // Auth
    suspend fun signUp(email: String, password: String): Result<UserSession>
    suspend fun signIn(email: String, password: String): Result<UserSession>
    suspend fun signOut(): Result<Unit>
    suspend fun refreshSession(): Result<UserSession>
    fun getCurrentSession(): UserSession?
    
    // Watchlist
    suspend fun fetchWatchlist(userId: String): Result<List<WatchlistItem>>
    suspend fun upsertWatchlistItem(item: WatchlistItem): Result<WatchlistItem>
    suspend fun deleteWatchlistItem(id: Long): Result<Unit>
    
    // Journal
    suspend fun fetchJournalEntries(userId: String): Result<List<JournalEntry>>
    suspend fun upsertJournalEntry(entry: JournalEntry): Result<JournalEntry>
    suspend fun deleteJournalEntry(id: Long): Result<Unit>
    
    // Profile
    suspend fun fetchUserProfile(userId: String): Result<UserProfile>
    suspend fun updateUserProfile(userId: String, displayName: String): Result<UserProfile>
    suspend fun deleteUserData(userId: String): Result<Unit>
    
    // Realtime
    fun subscribeToWatchlistChanges(userId: String, onEvent: (WatchlistItem, ChangeType) -> Unit)
    fun subscribeToJournalChanges(userId: String, onEvent: (JournalEntry, ChangeType) -> Unit)
    fun unsubscribeAll()
}

/**
 * Represents the type of change in a real-time event.
 */
enum class ChangeType {
    INSERT, UPDATE, DELETE
}
