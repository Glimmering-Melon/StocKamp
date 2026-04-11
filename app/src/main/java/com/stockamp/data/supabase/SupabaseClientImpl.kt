@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.stockamp.data.supabase

import android.util.Log
import com.stockamp.BuildConfig
import com.stockamp.data.model.JournalEntry
import com.stockamp.data.model.NewsArticleDto
import com.stockamp.data.model.UserProfile
import com.stockamp.data.model.WatchlistItem
import io.github.jan.supabase.SupabaseClient as SupabaseSDKClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration error thrown when Supabase API keys are missing.
 */
class SupabaseConfigurationException(message: String) : Exception(message)

/**
 * Thrown when signup succeeds but email confirmation is required before a session is created.
 */
class EmailConfirmationRequiredException(message: String) : Exception(message)

/**
 * Implementation of SupabaseClient that wraps the Supabase Kotlin SDK.
 * Handles authentication, data operations, and real-time subscriptions.
 */
@Singleton
class SupabaseClientImpl @Inject constructor() : SupabaseClient {
    
    private val supabase: SupabaseSDKClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subscriptionJobs = mutableMapOf<String, Job>()
    
    init {
        // Validate API keys are present
        val supabaseUrl = BuildConfig.SUPABASE_URL
        val supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        
        if (supabaseUrl.isBlank() || supabaseKey.isBlank()) {
            throw SupabaseConfigurationException(
                "Supabase API keys are missing. Please configure SUPABASE_URL and SUPABASE_ANON_KEY in local.properties"
            )
        }
        
        // Initialize Supabase client
        supabase = createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseKey
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
    
    // ========== Authentication ==========
    
    override suspend fun signUp(email: String, password: String): Result<UserSession> {
        return try {
            Log.d(TAG, "Attempting signUp for: $email")
            supabase.auth.signUpWith(io.github.jan.supabase.gotrue.providers.builtin.Email) {
                this.email = email
                this.password = password
            }
            val session = supabase.auth.currentSessionOrNull()
            Log.d(TAG, "SignUp done. Session: ${if (session != null) "present" else "null (email confirmation required)"}")
            session?.let { Result.success(it) }
                ?: Result.failure(EmailConfirmationRequiredException("Email confirmation required"))
        } catch (e: Exception) {
            Log.e(TAG, "Sign up error: ${e.javaClass.simpleName} - ${e.message}", e)
            Result.failure(e)
        }
    }
    
    override suspend fun signIn(email: String, password: String): Result<UserSession> {
        return try {
            supabase.auth.signInWith(io.github.jan.supabase.gotrue.providers.builtin.Email) {
                this.email = email
                this.password = password
            }
            val session = supabase.auth.currentSessionOrNull()
            session?.let { Result.success(it) }
                ?: Result.failure(Exception("Sign in failed: No session returned"))
        } catch (e: Exception) {
            Log.e(TAG, "Sign in error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun signOut(): Result<Unit> {
        return try {
            supabase.auth.signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign out error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun refreshSession(): Result<UserSession> {
        return try {
            supabase.auth.refreshCurrentSession()
            val session = supabase.auth.currentSessionOrNull()
            session?.let { Result.success(it) }
                ?: Result.failure(Exception("Refresh session failed: No session available"))
        } catch (e: Exception) {
            Log.e(TAG, "Refresh session error", e)
            Result.failure(e)
        }
    }
    
    override fun getCurrentSession(): UserSession? {
        return supabase.auth.currentSessionOrNull()
    }
    
    // ========== Watchlist Operations ==========
    
    override suspend fun fetchWatchlist(userId: String): Result<List<WatchlistItem>> {
        return try {
            val items = supabase.from("watchlist_items")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("is_deleted", false)
                    }
                }
                .decodeList<WatchlistItemDTO>()
                .map { it.toWatchlistItem() }
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch watchlist error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun upsertWatchlistItem(item: WatchlistItem): Result<WatchlistItem> {
        return try {
            val dto = WatchlistItemDTO.fromWatchlistItem(item)
            supabase.from("watchlist_items").upsert(dto)
            Result.success(item)
        } catch (e: Exception) {
            Log.e(TAG, "Upsert watchlist item error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun deleteWatchlistItem(id: Long): Result<Unit> {
        return try {
            supabase.from("watchlist_items")
                .delete {
                    filter {
                        eq("id", id)
                    }
                }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Delete watchlist item error", e)
            Result.failure(e)
        }
    }
    
    // ========== Journal Operations ==========
    
    override suspend fun fetchJournalEntries(userId: String): Result<List<JournalEntry>> {
        return try {
            val entries = supabase.from("journal_entries")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("is_deleted", false)
                    }
                }
                .decodeList<JournalEntryDTO>()
                .map { it.toJournalEntry() }
            Result.success(entries)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch journal entries error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun upsertJournalEntry(entry: JournalEntry): Result<JournalEntry> {
        return try {
            val dto = JournalEntryDTO.fromJournalEntry(entry)
            Log.d(TAG, "upsertJournalEntry: id=${dto.id}, user_id='${dto.user_id}', symbol=${dto.symbol}, auth_uid=${supabase.auth.currentSessionOrNull()?.user?.id}")
            if (dto.id == null) {
                // Insert mới: lấy lại row vừa insert để có id từ Supabase
                val inserted = supabase.from("journal_entries")
                    .insert(dto) { select() }
                    .decodeList<JournalEntryDTO>()
                    .firstOrNull()
                val result = inserted?.toJournalEntry() ?: entry
                Result.success(result)
            } else {
                // Update: upsert bình thường
                supabase.from("journal_entries").upsert(dto)
                Result.success(entry)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upsert journal entry error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun deleteJournalEntry(id: Long): Result<Unit> {
        return try {
            supabase.from("journal_entries")
                .delete {
                    filter {
                        eq("id", id)
                    }
                }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Delete journal entry error", e)
            Result.failure(e)
        }
    }
    
    // ========== Profile Operations ==========
    
    override suspend fun fetchUserProfile(userId: String): Result<UserProfile> {
        return try {
            val profiles = supabase.from("user_profiles")
                .select {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeList<UserProfileDTO>()
            val profile = profiles.firstOrNull()
                ?: return Result.failure(Exception("Profile not found for user: $userId"))
            Result.success(profile.toUserProfile())
        } catch (e: Exception) {
            Log.e(TAG, "Fetch user profile error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun updateUserProfile(userId: String, displayName: String): Result<UserProfile> {
        return try {
            supabase.from("user_profiles")
                .update({
                    set("display_name", displayName)
                }) {
                    filter { eq("id", userId) }
                }
            Result.success(UserProfile(id = userId, displayName = displayName))
        } catch (e: Exception) {
            Log.e(TAG, "Update user profile error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun deleteUserData(userId: String): Result<Unit> {
        return try {
            // Delete watchlist items
            supabase.from("watchlist_items")
                .delete {
                    filter {
                        eq("user_id", userId)
                    }
                }
            
            // Delete journal entries
            supabase.from("journal_entries")
                .delete {
                    filter {
                        eq("user_id", userId)
                    }
                }
            
            // Delete user profile
            supabase.from("user_profiles")
                .delete {
                    filter {
                        eq("id", userId)
                    }
                }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Delete user data error", e)
            Result.failure(e)
        }
    }
    
    // ========== Realtime Subscriptions ==========

    override fun subscribeToWatchlistChanges(
        userId: String,
        onEvent: (WatchlistItem, ChangeType) -> Unit
    ) {
        val channelKey = "watchlist_$userId"
        // Cancel any existing subscription for this key
        subscriptionJobs[channelKey]?.cancel()

        val job = scope.launch {
            try {
                val channel = supabase.realtime.channel(channelKey)
                val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "watchlist_items"
                    filter = "user_id=eq.$userId"
                }

                channel.subscribe()

                changeFlow.collect { action ->
                    try {
                        when (action) {
                            is PostgresAction.Insert -> {
                                val item = action.record.toWatchlistItem()
                                onEvent(item, ChangeType.INSERT)
                            }
                            is PostgresAction.Update -> {
                                val item = action.record.toWatchlistItem()
                                onEvent(item, ChangeType.UPDATE)
                            }
                            is PostgresAction.Delete -> {
                                val item = action.oldRecord.toWatchlistItem()
                                onEvent(item, ChangeType.DELETE)
                            }
                            else -> Unit
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing watchlist realtime event", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Subscribe to watchlist changes error", e)
            }
        }
        subscriptionJobs[channelKey] = job
        Log.d(TAG, "Subscribed to watchlist changes for user: $userId")
    }

    override fun subscribeToJournalChanges(
        userId: String,
        onEvent: (JournalEntry, ChangeType) -> Unit
    ) {
        val channelKey = "journal_$userId"
        // Cancel any existing subscription for this key
        subscriptionJobs[channelKey]?.cancel()

        val job = scope.launch {
            try {
                val channel = supabase.realtime.channel(channelKey)
                val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "journal_entries"
                    filter = "user_id=eq.$userId"
                }

                channel.subscribe()

                changeFlow.collect { action ->
                    try {
                        when (action) {
                            is PostgresAction.Insert -> {
                                val entry = action.record.toJournalEntry()
                                onEvent(entry, ChangeType.INSERT)
                            }
                            is PostgresAction.Update -> {
                                val entry = action.record.toJournalEntry()
                                onEvent(entry, ChangeType.UPDATE)
                            }
                            is PostgresAction.Delete -> {
                                val entry = action.oldRecord.toJournalEntry()
                                onEvent(entry, ChangeType.DELETE)
                            }
                            else -> Unit
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing journal realtime event", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Subscribe to journal changes error", e)
            }
        }
        subscriptionJobs[channelKey] = job
        Log.d(TAG, "Subscribed to journal changes for user: $userId")
    }

    override fun unsubscribeAll() {
        // Cancel all active subscription coroutines
        subscriptionJobs.values.forEach { it.cancel() }
        subscriptionJobs.clear()

        scope.launch {
            try {
                supabase.realtime.removeAllChannels()
                Log.d(TAG, "Unsubscribed from all realtime channels")
            } catch (e: Exception) {
                Log.e(TAG, "Unsubscribe all error", e)
            }
        }
    }

    // ========== News Operations ==========

    override suspend fun fetchNewsArticles(offset: Int, limit: Int): Result<List<NewsArticleDto>> {
        return try {
            Log.d(TAG, "fetchNewsArticles: offset=$offset limit=$limit")
            val articles = supabase.from("news_articles")
                .select {
                    filter { eq("status", "analyzed") }
                    order("published_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    range(offset.toLong(), (offset + limit - 1).toLong())
                }
                .decodeList<NewsArticleDto>()
            Log.d(TAG, "fetchNewsArticles: got ${articles.size} articles")
            Result.success(articles)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch news articles error: ${e.javaClass.simpleName} - ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun subscribeToNewsInserts(onInsert: (NewsArticleDto) -> Unit): Result<Unit> {
        val channelKey = "news_articles_channel"
        subscriptionJobs[channelKey]?.cancel()

        val job = scope.launch {
            try {
                val channel = supabase.realtime.channel(channelKey)
                val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "news_articles"
                    filter = "status=eq.analyzed"
                }
                channel.subscribe()
                Log.d(TAG, "Subscribed to news_articles realtime inserts")

                changeFlow.collect { action ->
                    try {
                        val record = action.record
                        val dto = decodeNewsArticleDto(record)
                        if (dto != null) onInsert(dto)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing news realtime event", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "News realtime subscription error", e)
            }
        }
        subscriptionJobs[channelKey] = job
        return Result.success(Unit)
    }

    override fun unsubscribeFromNews() {
        val channelKey = "news_articles_channel"
        subscriptionJobs[channelKey]?.cancel()
        subscriptionJobs.remove(channelKey)
        scope.launch {
            try {
                supabase.realtime.removeChannel(supabase.realtime.channel(channelKey))
            } catch (e: Exception) {
                Log.e(TAG, "Unsubscribe from news error", e)
            }
        }
    }

    private fun decodeNewsArticleDto(
        record: Map<String, kotlinx.serialization.json.JsonElement>
    ): NewsArticleDto? {
        return try {
            val id = record["id"]?.jsonPrimitive?.content ?: return null
            val title = record["title"]?.jsonPrimitive?.content ?: return null
            val url = record["url"]?.jsonPrimitive?.content ?: return null
            val sourceName = record["source_name"]?.jsonPrimitive?.content ?: return null
            val publishedAt = record["published_at"]?.jsonPrimitive?.content ?: return null
            val status = record["status"]?.jsonPrimitive?.content ?: "analyzed"
            val summary = record["summary"]?.jsonPrimitive?.contentOrNull
            val sentimentLabel = record["sentiment_label"]?.jsonPrimitive?.contentOrNull
            val sentimentScore = record["sentiment_score"]?.jsonPrimitive?.content?.toFloatOrNull()

            val stockSymbolsElement = record["stock_symbols"]
            val stockSymbols: List<String> = if (stockSymbolsElement != null) {
                try {
                    val jsonStr = stockSymbolsElement.toString()
                    kotlinx.serialization.json.Json.decodeFromString(jsonStr)
                } catch (e: Exception) { emptyList() }
            } else emptyList()

            NewsArticleDto(
                id = id,
                title = title,
                url = url,
                summary = summary,
                sourceName = sourceName,
                publishedAt = publishedAt,
                stockSymbols = stockSymbols,
                sentimentLabel = sentimentLabel,
                sentimentScore = sentimentScore,
                status = status
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode news realtime record", e)
            null
        }
    }

    companion object {
        private const val TAG = "SupabaseClient"
    }
}

// ========== Data Transfer Objects ==========

@Serializable
private data class WatchlistItemDTO(
    @kotlinx.serialization.SerialName("id")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val id: Long? = null,
    val user_id: String = "",
    val symbol: String,
    val name: String,
    val added_at: Long,
    val created_at: Long? = null,
    val modified_at: Long? = null,
    val synced_at: Long? = null,
    val is_deleted: Boolean = false
) {
    fun toWatchlistItem() = WatchlistItem(
        id = id ?: 0L,
        userId = user_id,
        symbol = symbol,
        name = name,
        addedAt = added_at
    )
    
    companion object {
        fun fromWatchlistItem(item: WatchlistItem) = WatchlistItemDTO(
            id = if (item.id > 0) item.id else null,
            user_id = item.userId,
            symbol = item.symbol,
            name = item.name,
            added_at = item.addedAt
        )
    }
}

@Serializable
private data class JournalEntryDTO(
    @kotlinx.serialization.SerialName("id")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val id: Long? = null,
    val user_id: String = "",
    val symbol: String,
    val action: String,
    val quantity: Int,
    val price: Double,
    val total_value: Double,
    val notes: String = "",
    val emotion: String = "",
    val strategy: String = "",
    val created_at: Long,
    val modified_at: Long? = null,
    val synced_at: Long? = null,
    val is_deleted: Boolean = false
) {
    fun toJournalEntry() = JournalEntry(
        id = id ?: 0L,
        userId = user_id,
        symbol = symbol,
        action = action,
        quantity = quantity,
        price = price,
        totalValue = total_value,
        notes = notes,
        emotion = emotion,
        strategy = strategy,
        createdAt = created_at
    )
    
    companion object {
        fun fromJournalEntry(entry: JournalEntry) = JournalEntryDTO(
            // Chỉ gửi id nếu là update (id > 0), insert mới thì để null để Supabase tự sinh
            id = if (entry.id > 0) entry.id else null,
            user_id = entry.userId,
            symbol = entry.symbol,
            action = entry.action,
            quantity = entry.quantity,
            price = entry.price,
            total_value = entry.totalValue,
            notes = entry.notes,
            emotion = entry.emotion,
            strategy = entry.strategy,
            created_at = entry.createdAt
        )
    }
}

@Serializable
private data class UserProfileDTO(
    val id: String,
    val email: String,
    val display_name: String? = null,
    val created_at: String
) {
    fun toUserProfile() = UserProfile(
        id = id,
        email = email,
        displayName = display_name ?: "",
        createdAt = created_at
    )
}

// ========== Realtime Record Parsers ==========

private fun Map<String, kotlinx.serialization.json.JsonElement>.toWatchlistItem(): WatchlistItem {
    return WatchlistItem(
        id = this["id"]?.jsonPrimitive?.longOrNull ?: 0L,
        userId = this["user_id"]?.jsonPrimitive?.content ?: "",
        symbol = this["symbol"]?.jsonPrimitive?.content ?: "",
        name = this["name"]?.jsonPrimitive?.content ?: "",
        addedAt = this["added_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        createdAt = this["created_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        modifiedAt = this["modified_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        isDeleted = this["is_deleted"]?.jsonPrimitive?.booleanOrNull ?: false
    )
}

private fun Map<String, kotlinx.serialization.json.JsonElement>.toJournalEntry(): JournalEntry {
    val quantity = this["quantity"]?.jsonPrimitive?.intOrNull ?: 0
    val price = this["price"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    return JournalEntry(
        id = this["id"]?.jsonPrimitive?.longOrNull ?: 0L,
        userId = this["user_id"]?.jsonPrimitive?.content ?: "",
        symbol = this["symbol"]?.jsonPrimitive?.content ?: "",
        action = this["action"]?.jsonPrimitive?.content ?: "",
        quantity = quantity,
        price = price,
        totalValue = this["total_value"]?.jsonPrimitive?.doubleOrNull ?: (quantity * price),
        notes = this["notes"]?.jsonPrimitive?.content ?: "",
        emotion = this["emotion"]?.jsonPrimitive?.content ?: "",
        strategy = this["strategy"]?.jsonPrimitive?.content ?: "",
        createdAt = this["created_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        modifiedAt = this["modified_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        isDeleted = this["is_deleted"]?.jsonPrimitive?.booleanOrNull ?: false
    )
}
