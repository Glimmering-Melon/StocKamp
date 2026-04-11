package com.stockamp.di

import android.content.Context
import com.stockamp.data.auth.AuthManager
import com.stockamp.data.auth.AuthManagerImpl
import com.stockamp.data.local.StocKampDatabase
import com.stockamp.data.network.NetworkMonitor
import com.stockamp.data.network.NetworkMonitorImpl
import com.stockamp.data.storage.EncryptedStorage
import com.stockamp.data.storage.EncryptedStorageImpl
import com.stockamp.data.supabase.SupabaseClient
import com.stockamp.data.supabase.SupabaseClientImpl
import com.stockamp.data.sync.SyncEngine
import com.stockamp.data.sync.SyncEngineImpl
import com.stockamp.data.sync.SyncQueue
import com.stockamp.data.sync.SyncQueueImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Supabase-related dependencies.
 * 
 * This module provides singleton instances for:
 * - SupabaseClient: Wrapper around Supabase SDK for backend communication
 * - AuthManager: Handles user authentication and session management
 * - SyncEngine: Coordinates bidirectional data synchronization
 * - NetworkMonitor: Monitors network connectivity status
 * - EncryptedStorage: Securely stores authentication tokens
 * - SyncQueue: Manages queued sync operations with retry logic
 * 
 * Requirements: 9.1 - The Supabase_Client SHALL load API keys from BuildConfig at runtime
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {
    
    /**
     * Provides a singleton instance of SupabaseClient.
     * The client loads API keys from BuildConfig at runtime (Requirement 9.1).
     */
    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient {
        return SupabaseClientImpl()
    }
    
    /**
     * Provides a singleton instance of EncryptedStorage.
     * Used for securely storing authentication tokens.
     */
    @Provides
    @Singleton
    fun provideEncryptedStorage(
        @ApplicationContext context: Context
    ): EncryptedStorage {
        return EncryptedStorageImpl(context)
    }
    
    /**
     * Provides a singleton instance of NetworkMonitor.
     * Monitors network connectivity to trigger sync operations.
     */
    @Provides
    @Singleton
    fun provideNetworkMonitor(
        @ApplicationContext context: Context
    ): NetworkMonitor {
        return NetworkMonitorImpl(context)
    }
    
    /**
     * Provides a singleton instance of SyncQueue.
     * Manages persistent queue for sync operations with retry logic.
     */
    @Provides
    @Singleton
    fun provideSyncQueue(
        database: StocKampDatabase
    ): SyncQueue {
        return SyncQueueImpl(database)
    }
    
    /**
     * Provides a singleton instance of SyncEngine.
     * Coordinates bidirectional synchronization between Room and Supabase.
     */
    @Provides
    @Singleton
    fun provideSyncEngine(
        database: StocKampDatabase,
        supabaseClient: SupabaseClient,
        networkMonitor: NetworkMonitor,
        syncQueue: SyncQueue,
        encryptedStorage: EncryptedStorage,
        authManager: dagger.Lazy<AuthManager> // Lazy to break circular dependency
    ): SyncEngine {
        return SyncEngineImpl(database, supabaseClient, networkMonitor, syncQueue, encryptedStorage, authManager)
    }
    
    /**
     * Provides a singleton instance of AuthManager.
     * Manages user authentication, session persistence, and profile operations.
     */
    @Provides
    @Singleton
    fun provideAuthManager(
        supabaseClient: SupabaseClient,
        encryptedStorage: EncryptedStorage,
        syncEngine: dagger.Lazy<SyncEngine> // Lazy to break circular dependency
    ): AuthManager {
        return AuthManagerImpl(supabaseClient, encryptedStorage, syncEngine)
    }
}
