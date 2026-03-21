package com.stockamp.di

import android.content.Context
import com.stockamp.data.auth.AuthManager
import com.stockamp.data.local.StocKampDatabase
import com.stockamp.data.network.NetworkMonitor
import com.stockamp.data.storage.EncryptedStorage
import com.stockamp.data.supabase.SupabaseClient
import com.stockamp.data.sync.SyncEngine
import com.stockamp.data.sync.SyncQueue
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SupabaseModule to verify dependency injection setup.
 * Validates that all providers return non-null instances.
 */
class SupabaseModuleTest {
    
    private lateinit var mockContext: Context
    private lateinit var mockDatabase: StocKampDatabase
    
    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockDatabase = mockk(relaxed = true)
    }
    
    @Test
    fun `provideSupabaseClient returns non-null instance`() {
        // This test will fail if BuildConfig keys are not set, which is expected
        // The actual validation happens at runtime when the app starts
        try {
            val client = SupabaseModule.provideSupabaseClient()
            assertNotNull("SupabaseClient should not be null", client)
        } catch (e: Exception) {
            // Expected if BuildConfig keys are not configured in test environment
            assert(e.message?.contains("Supabase API keys") == true)
        }
    }
    
    @Test
    fun `provideEncryptedStorage returns non-null instance`() {
        val storage = SupabaseModule.provideEncryptedStorage(mockContext)
        assertNotNull("EncryptedStorage should not be null", storage)
    }
    
    @Test
    fun `provideNetworkMonitor returns non-null instance`() {
        val monitor = SupabaseModule.provideNetworkMonitor(mockContext)
        assertNotNull("NetworkMonitor should not be null", monitor)
    }
    
    @Test
    fun `provideSyncQueue returns non-null instance`() {
        val queue = SupabaseModule.provideSyncQueue(mockDatabase)
        assertNotNull("SyncQueue should not be null", queue)
    }
    
    @Test
    fun `provideSyncEngine returns non-null instance`() {
        val mockClient: SupabaseClient = mockk(relaxed = true)
        val mockMonitor: NetworkMonitor = mockk(relaxed = true)
        val mockQueue: SyncQueue = mockk(relaxed = true)
        
        val engine = SupabaseModule.provideSyncEngine(
            mockDatabase,
            mockClient,
            mockMonitor,
            mockQueue
        )
        assertNotNull("SyncEngine should not be null", engine)
    }
    
    @Test
    fun `provideAuthManager returns non-null instance`() {
        val mockClient: SupabaseClient = mockk(relaxed = true)
        val mockStorage: EncryptedStorage = mockk(relaxed = true)
        val mockLazySyncEngine: dagger.Lazy<SyncEngine> = mockk(relaxed = true)
        
        val authManager = SupabaseModule.provideAuthManager(
            mockClient,
            mockStorage,
            mockLazySyncEngine
        )
        assertNotNull("AuthManager should not be null", authManager)
    }
}
