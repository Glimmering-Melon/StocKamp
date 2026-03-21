package com.stockamp.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for EncryptedStorageImpl.
 * Tests token storage, retrieval, clearing, and migration flag persistence.
 * 
 * Requirements: 2.2, 3.1
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EncryptedStorageImplTest {
    
    private lateinit var context: Context
    private lateinit var encryptedStorage: EncryptedStorageImpl
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        encryptedStorage = EncryptedStorageImpl(context)
    }
    
    @After
    fun tearDown() = runTest {
        // Clean up after each test
        encryptedStorage.clearTokens()
    }
    
    @Test
    fun `storeAccessToken and getAccessToken should store and retrieve token`() = runTest {
        // Arrange
        val testToken = "test_access_token_12345"
        
        // Act
        encryptedStorage.storeAccessToken(testToken)
        val retrievedToken = encryptedStorage.getAccessToken()
        
        // Assert
        assertEquals(testToken, retrievedToken)
    }
    
    @Test
    fun `getAccessToken should return null when no token stored`() = runTest {
        // Act
        val retrievedToken = encryptedStorage.getAccessToken()
        
        // Assert
        assertNull(retrievedToken)
    }
    
    @Test
    fun `storeRefreshToken and getRefreshToken should store and retrieve token`() = runTest {
        // Arrange
        val testToken = "test_refresh_token_67890"
        
        // Act
        encryptedStorage.storeRefreshToken(testToken)
        val retrievedToken = encryptedStorage.getRefreshToken()
        
        // Assert
        assertEquals(testToken, retrievedToken)
    }
    
    @Test
    fun `getRefreshToken should return null when no token stored`() = runTest {
        // Act
        val retrievedToken = encryptedStorage.getRefreshToken()
        
        // Assert
        assertNull(retrievedToken)
    }
    
    @Test
    fun `clearTokens should remove both access and refresh tokens`() = runTest {
        // Arrange
        encryptedStorage.storeAccessToken("access_token")
        encryptedStorage.storeRefreshToken("refresh_token")
        
        // Act
        encryptedStorage.clearTokens()
        
        // Assert
        assertNull(encryptedStorage.getAccessToken())
        assertNull(encryptedStorage.getRefreshToken())
    }
    
    @Test
    fun `storeMigrationComplete and isMigrationComplete should store and retrieve flag`() = runTest {
        // Arrange - initially should be false
        assertFalse(encryptedStorage.isMigrationComplete())
        
        // Act
        encryptedStorage.storeMigrationComplete(true)
        
        // Assert
        assertTrue(encryptedStorage.isMigrationComplete())
    }
    
    @Test
    fun `isMigrationComplete should return false by default`() = runTest {
        // Act
        val isComplete = encryptedStorage.isMigrationComplete()
        
        // Assert
        assertFalse(isComplete)
    }
    
    @Test
    fun `storeMigrationComplete should allow setting to false`() = runTest {
        // Arrange
        encryptedStorage.storeMigrationComplete(true)
        assertTrue(encryptedStorage.isMigrationComplete())
        
        // Act
        encryptedStorage.storeMigrationComplete(false)
        
        // Assert
        assertFalse(encryptedStorage.isMigrationComplete())
    }
    
    @Test
    fun `tokens should persist across multiple storage instances`() = runTest {
        // Arrange
        val accessToken = "persistent_access_token"
        val refreshToken = "persistent_refresh_token"
        encryptedStorage.storeAccessToken(accessToken)
        encryptedStorage.storeRefreshToken(refreshToken)
        
        // Act - Create new instance
        val newStorage = EncryptedStorageImpl(context)
        
        // Assert
        assertEquals(accessToken, newStorage.getAccessToken())
        assertEquals(refreshToken, newStorage.getRefreshToken())
    }
    
    @Test
    fun `migration flag should persist across multiple storage instances`() = runTest {
        // Arrange
        encryptedStorage.storeMigrationComplete(true)
        
        // Act - Create new instance
        val newStorage = EncryptedStorageImpl(context)
        
        // Assert
        assertTrue(newStorage.isMigrationComplete())
    }
    
    @Test
    fun `clearTokens should not affect migration flag`() = runTest {
        // Arrange
        encryptedStorage.storeAccessToken("access_token")
        encryptedStorage.storeRefreshToken("refresh_token")
        encryptedStorage.storeMigrationComplete(true)
        
        // Act
        encryptedStorage.clearTokens()
        
        // Assert
        assertNull(encryptedStorage.getAccessToken())
        assertNull(encryptedStorage.getRefreshToken())
        assertTrue(encryptedStorage.isMigrationComplete())
    }
}
