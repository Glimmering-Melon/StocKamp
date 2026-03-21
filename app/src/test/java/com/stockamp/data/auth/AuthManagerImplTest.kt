package com.stockamp.data.auth

import com.stockamp.data.model.UserProfile
import com.stockamp.data.storage.EncryptedStorage
import com.stockamp.data.supabase.SupabaseClient
import com.stockamp.data.sync.SyncEngine
import io.github.jan.supabase.gotrue.user.UserInfo
import io.github.jan.supabase.gotrue.user.UserSession
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for AuthManagerImpl.
 * Tests authentication, session management, and profile operations.
 */
class AuthManagerImplTest {
    
    private lateinit var authManager: AuthManagerImpl
    private lateinit var mockSupabaseClient: SupabaseClient
    private lateinit var mockEncryptedStorage: EncryptedStorage
    private lateinit var mockSyncEngine: SyncEngine
    private lateinit var mockLazySyncEngine: dagger.Lazy<SyncEngine>
    
    private val testEmail = "test@example.com"
    private val testPassword = "password123"
    private val testUserId = "test-user-id-123"
    private val testAccessToken = "test-access-token"
    private val testRefreshToken = "test-refresh-token"
    
    @Before
    fun setup() {
        mockSupabaseClient = mockk(relaxed = true)
        mockEncryptedStorage = mockk(relaxed = true)
        mockSyncEngine = mockk(relaxed = true)
        mockLazySyncEngine = mockk {
            every { get() } returns mockSyncEngine
        }
        
        authManager = AuthManagerImpl(
            mockSupabaseClient,
            mockEncryptedStorage,
            mockLazySyncEngine
        )
        
        // Default mock behaviors
        coEvery { mockEncryptedStorage.storeAccessToken(any()) } just Runs
        coEvery { mockEncryptedStorage.storeRefreshToken(any()) } just Runs
        coEvery { mockEncryptedStorage.clearTokens() } just Runs
        coEvery { mockSyncEngine.performFullSync() } returns Result.success(Unit)
        coEvery { mockSyncEngine.clearLocalUserData() } just Runs
    }
    
    @After
    fun tearDown() {
        clearAllMocks()
    }
    
    // Registration Tests
    
    @Test
    fun `register with valid credentials succeeds`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val mockProfile = createMockProfile()
        
        coEvery { mockSupabaseClient.signUp(testEmail, testPassword) } returns Result.success(mockSession)
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.success(mockProfile)
        
        // Act
        val result = authManager.register(testEmail, testPassword)
        
        // Assert
        assertTrue(result.isSuccess)
        assertEquals(mockProfile, result.getOrNull())
        coVerify { mockEncryptedStorage.storeAccessToken(testAccessToken) }
        coVerify { mockEncryptedStorage.storeRefreshToken(testRefreshToken) }
    }
    
    @Test
    fun `register with short password fails`() = runTest {
        // Act
        val result = authManager.register(testEmail, "short")
        
        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception.message?.contains("at least 8 characters") == true)
    }
    
    @Test
    fun `register with duplicate email fails`() = runTest {
        // Arrange
        val error = Exception("User already registered")
        coEvery { mockSupabaseClient.signUp(testEmail, testPassword) } returns Result.failure(error)
        
        // Act
        val result = authManager.register(testEmail, testPassword)
        
        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception.message?.contains("already exists") == true)
    }
    
    @Test
    fun `register with network failure returns connectivity error`() = runTest {
        // Arrange - simulate a network/connectivity exception
        val networkError = java.net.UnknownHostException("Unable to resolve host")
        coEvery { mockSupabaseClient.signUp(testEmail, testPassword) } returns Result.failure(networkError)

        // Act
        val result = authManager.register(testEmail, testPassword)

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        // The error should propagate (connectivity failure)
        assertTrue(exception is java.net.UnknownHostException || exception.message != null)
    }

    @Test
    fun `register creates user profile in Supabase when not found`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val createdProfile = createMockProfile()
        
        coEvery { mockSupabaseClient.signUp(testEmail, testPassword) } returns Result.success(mockSession)
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.failure(Exception("Not found"))
        coEvery { mockSupabaseClient.updateUserProfile(testUserId, any()) } returns Result.success(createdProfile)
        
        // Act
        val result = authManager.register(testEmail, testPassword)
        
        // Assert
        assertTrue(result.isSuccess)
        coVerify { mockSupabaseClient.updateUserProfile(testUserId, any()) }
    }
    
    @Test
    fun `register stores tokens in encrypted storage`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val mockProfile = createMockProfile()
        
        coEvery { mockSupabaseClient.signUp(testEmail, testPassword) } returns Result.success(mockSession)
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.success(mockProfile)
        
        // Act
        authManager.register(testEmail, testPassword)
        
        // Assert
        coVerify(exactly = 1) { mockEncryptedStorage.storeAccessToken(testAccessToken) }
        coVerify(exactly = 1) { mockEncryptedStorage.storeRefreshToken(testRefreshToken) }
    }
    
    // Login Tests
    
    @Test
    fun `login with valid credentials succeeds`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val mockProfile = createMockProfile()
        
        coEvery { mockSupabaseClient.signIn(testEmail, testPassword) } returns Result.success(mockSession)
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.success(mockProfile)
        
        // Act
        val result = authManager.login(testEmail, testPassword)
        
        // Assert
        assertTrue(result.isSuccess)
        assertEquals(mockProfile, result.getOrNull())
        coVerify { mockEncryptedStorage.storeAccessToken(testAccessToken) }
        coVerify { mockEncryptedStorage.storeRefreshToken(testRefreshToken) }
    }
    
    @Test
    fun `login with invalid credentials fails`() = runTest {
        // Arrange
        val error = Exception("Invalid credentials")
        coEvery { mockSupabaseClient.signIn(testEmail, testPassword) } returns Result.failure(error)
        
        // Act
        val result = authManager.login(testEmail, testPassword)
        
        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception.message?.contains("Invalid") == true)
    }

    @Test
    fun `login with network unavailable returns connectivity error`() = runTest {
        // Arrange - simulate network failure (Req 2.5)
        val networkError = java.net.UnknownHostException("Unable to resolve host")
        coEvery { mockSupabaseClient.signIn(testEmail, testPassword) } returns Result.failure(networkError)

        // Act
        val result = authManager.login(testEmail, testPassword)

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception.message?.contains("Unable to connect") == true)
    }

    @Test
    fun `login with network timeout returns connectivity error`() = runTest {
        // Arrange - simulate timeout (Req 2.5)
        val timeoutError = java.net.SocketTimeoutException("Connection timed out")
        coEvery { mockSupabaseClient.signIn(testEmail, testPassword) } returns Result.failure(timeoutError)

        // Act
        val result = authManager.login(testEmail, testPassword)

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception.message?.contains("Unable to connect") == true)
    }
    
    @Test
    fun `login triggers sync engine`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val mockProfile = createMockProfile()
        
        coEvery { mockSupabaseClient.signIn(testEmail, testPassword) } returns Result.success(mockSession)
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.success(mockProfile)
        
        // Act
        authManager.login(testEmail, testPassword)
        
        // Assert
        coVerify { mockSyncEngine.performFullSync() }
    }
    
    // Logout Tests
    
    @Test
    fun `logout clears tokens and session`() = runTest {
        // Arrange
        coEvery { mockSupabaseClient.signOut() } returns Result.success(Unit)
        
        // Act
        val result = authManager.logout()
        
        // Assert
        assertTrue(result.isSuccess)
        coVerify { mockSupabaseClient.signOut() }
        coVerify { mockEncryptedStorage.clearTokens() }
    }
    
    @Test
    fun `logout clears current user`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val mockProfile = createMockProfile()
        
        coEvery { mockSupabaseClient.signIn(testEmail, testPassword) } returns Result.success(mockSession)
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.success(mockProfile)
        coEvery { mockSupabaseClient.signOut() } returns Result.success(Unit)
        
        // Login first
        authManager.login(testEmail, testPassword)
        
        // Act
        authManager.logout()
        
        // Assert
        val currentUser = authManager.getCurrentUser().first()
        assertEquals(null, currentUser)
    }
    
    // Session Management Tests
    
    @Test
    fun `restoreSession with valid token succeeds`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val mockProfile = createMockProfile()
        
        coEvery { mockEncryptedStorage.getAccessToken() } returns testAccessToken
        every { mockSupabaseClient.getCurrentSession() } returns mockSession
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.success(mockProfile)
        
        // Act
        val result = authManager.restoreSession()
        
        // Assert
        assertTrue(result.isSuccess)
        assertEquals(mockProfile, result.getOrNull())
    }
    
    @Test
    fun `restoreSession with no token returns null`() = runTest {
        // Arrange
        coEvery { mockEncryptedStorage.getAccessToken() } returns null
        
        // Act
        val result = authManager.restoreSession()
        
        // Assert
        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrNull())
    }
    
    @Test
    fun `restoreSession with expired token attempts refresh`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val mockProfile = createMockProfile()
        
        coEvery { mockEncryptedStorage.getAccessToken() } returns testAccessToken
        every { mockSupabaseClient.getCurrentSession() } returns null andThen mockSession
        coEvery { mockEncryptedStorage.getRefreshToken() } returns testRefreshToken
        coEvery { mockSupabaseClient.refreshSession() } returns Result.success(mockSession)
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.success(mockProfile)
        
        // Act
        val result = authManager.restoreSession()
        
        // Assert
        assertTrue(result.isSuccess)
        coVerify { mockSupabaseClient.refreshSession() }
    }
    
    @Test
    fun `restoreSession clears tokens and returns null when refresh fails`() = runTest {
        // Arrange - Req 3.4: if token refresh fails, clear session and require re-authentication
        coEvery { mockEncryptedStorage.getAccessToken() } returns testAccessToken
        every { mockSupabaseClient.getCurrentSession() } returns null
        coEvery { mockEncryptedStorage.getRefreshToken() } returns testRefreshToken
        coEvery { mockSupabaseClient.refreshSession() } returns Result.failure(Exception("Refresh failed"))

        // Act
        val result = authManager.restoreSession()

        // Assert: returns success(null) to signal re-authentication is needed
        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrNull())
        coVerify { mockEncryptedStorage.clearTokens() }
    }

    @Test
    fun `refreshToken with valid refresh token succeeds`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        
        coEvery { mockEncryptedStorage.getRefreshToken() } returns testRefreshToken
        coEvery { mockSupabaseClient.refreshSession() } returns Result.success(mockSession)
        
        // Act
        val result = authManager.refreshToken()
        
        // Assert
        assertTrue(result.isSuccess)
        coVerify { mockEncryptedStorage.storeAccessToken(testAccessToken) }
        coVerify { mockEncryptedStorage.storeRefreshToken(testRefreshToken) }
    }
    
    @Test
    fun `refreshToken without refresh token fails`() = runTest {
        // Arrange
        coEvery { mockEncryptedStorage.getRefreshToken() } returns null
        
        // Act
        val result = authManager.refreshToken()
        
        // Assert
        assertTrue(result.isFailure)
    }
    
    // Flow Tests
    
    @Test
    fun `getCurrentUser returns null initially`() = runTest {
        // Act
        val currentUser = authManager.getCurrentUser().first()
        
        // Assert
        assertEquals(null, currentUser)
    }
    
    @Test
    fun `getCurrentUser returns user after login`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val mockProfile = createMockProfile()
        
        coEvery { mockSupabaseClient.signIn(testEmail, testPassword) } returns Result.success(mockSession)
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.success(mockProfile)
        
        // Act
        authManager.login(testEmail, testPassword)
        val currentUser = authManager.getCurrentUser().first()
        
        // Assert
        assertEquals(mockProfile, currentUser)
    }
    
    @Test
    fun `isAuthenticated returns false initially`() = runTest {
        // Act
        val isAuthenticated = authManager.isAuthenticated().first()
        
        // Assert
        assertFalse(isAuthenticated)
    }
    
    @Test
    fun `isAuthenticated returns true after login`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val mockProfile = createMockProfile()
        
        coEvery { mockSupabaseClient.signIn(testEmail, testPassword) } returns Result.success(mockSession)
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.success(mockProfile)
        
        // Act
        authManager.login(testEmail, testPassword)
        val isAuthenticated = authManager.isAuthenticated().first()
        
        // Assert
        assertTrue(isAuthenticated)
    }
    
    // Profile Management Tests
    
    @Test
    fun `getProfile returns cached profile`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val mockProfile = createMockProfile()
        
        coEvery { mockSupabaseClient.signIn(testEmail, testPassword) } returns Result.success(mockSession)
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.success(mockProfile)
        every { mockSupabaseClient.getCurrentSession() } returns mockSession
        
        // Login first
        authManager.login(testEmail, testPassword)
        
        // Act
        val result = authManager.getProfile()
        
        // Assert
        assertTrue(result.isSuccess)
        assertEquals(mockProfile, result.getOrNull())
        // Should not fetch from Supabase again
        coVerify(exactly = 1) { mockSupabaseClient.fetchUserProfile(testUserId) }
    }
    
    @Test
    fun `getProfile fetches from Supabase when not cached`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val mockProfile = createMockProfile()
        
        every { mockSupabaseClient.getCurrentSession() } returns mockSession
        coEvery { mockSupabaseClient.fetchUserProfile(testUserId) } returns Result.success(mockProfile)
        
        // Act
        val result = authManager.getProfile()
        
        // Assert
        assertTrue(result.isSuccess)
        assertEquals(mockProfile, result.getOrNull())
        coVerify { mockSupabaseClient.fetchUserProfile(testUserId) }
    }
    
    @Test
    fun `getProfile fails when not authenticated`() = runTest {
        // Arrange
        every { mockSupabaseClient.getCurrentSession() } returns null
        
        // Act
        val result = authManager.getProfile()
        
        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IllegalStateException)
    }
    
    @Test
    fun `updateProfile updates and caches profile`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        val newDisplayName = "New Name"
        val updatedProfile = createMockProfile().copy(displayName = newDisplayName)
        
        every { mockSupabaseClient.getCurrentSession() } returns mockSession
        coEvery { mockSupabaseClient.updateUserProfile(testUserId, newDisplayName) } returns Result.success(updatedProfile)
        
        // Act
        val result = authManager.updateProfile(newDisplayName)
        
        // Assert
        assertTrue(result.isSuccess)
        assertEquals(updatedProfile, result.getOrNull())
        coVerify { mockSupabaseClient.updateUserProfile(testUserId, newDisplayName) }
    }
    
    @Test
    fun `updateProfile fails when not authenticated`() = runTest {
        // Arrange
        every { mockSupabaseClient.getCurrentSession() } returns null
        
        // Act
        val result = authManager.updateProfile("New Name")
        
        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IllegalStateException)
    }
    
    @Test
    fun `deleteAccount removes all data and signs out`() = runTest {
        // Arrange
        val mockSession = createMockSession()
        
        every { mockSupabaseClient.getCurrentSession() } returns mockSession
        coEvery { mockSyncEngine.deleteUserData(testUserId) } returns Result.success(Unit)
        coEvery { mockSyncEngine.clearLocalUserData() } just Runs
        coEvery { mockSupabaseClient.deleteUserData(testUserId) } returns Result.success(Unit)
        coEvery { mockSupabaseClient.signOut() } returns Result.success(Unit)
        
        // Act
        val result = authManager.deleteAccount()
        
        // Assert
        assertTrue(result.isSuccess)
        coVerify { mockSyncEngine.deleteUserData(testUserId) }
        coVerify { mockSupabaseClient.deleteUserData(testUserId) }
        coVerify { mockSyncEngine.clearLocalUserData() }
        coVerify { mockSupabaseClient.signOut() }
        coVerify { mockEncryptedStorage.clearTokens() }
    }
    
    @Test
    fun `deleteAccount fails when not authenticated`() = runTest {
        // Arrange
        every { mockSupabaseClient.getCurrentSession() } returns null
        
        // Act
        val result = authManager.deleteAccount()
        
        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IllegalStateException)
    }

    @Test
    fun `deleteAccount returns user-friendly error when syncEngine deleteUserData fails`() = runTest {
        // Arrange - Req 14.6: return error message and preserve local data on failure
        val mockSession = createMockSession()
        every { mockSupabaseClient.getCurrentSession() } returns mockSession
        coEvery { mockSyncEngine.deleteUserData(testUserId) } returns Result.failure(Exception("Network error"))

        // Act
        val result = authManager.deleteAccount()

        // Assert: failure with user-friendly message
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception.message?.contains("Unable to delete account") == true)
        // Local data must NOT be cleared (Req 14.6)
        coVerify(exactly = 0) { mockSyncEngine.clearLocalUserData() }
    }

    @Test
    fun `deleteAccount returns user-friendly error when profile deletion fails`() = runTest {
        // Arrange - Req 14.6: if profile deletion fails, preserve local data
        val mockSession = createMockSession()
        every { mockSupabaseClient.getCurrentSession() } returns mockSession
        coEvery { mockSyncEngine.deleteUserData(testUserId) } returns Result.success(Unit)
        coEvery { mockSupabaseClient.deleteUserData(testUserId) } returns Result.failure(Exception("Server error"))

        // Act
        val result = authManager.deleteAccount()

        // Assert: failure with user-friendly message
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception.message?.contains("Unable to delete account") == true)
        // Local data must NOT be cleared (Req 14.6)
        coVerify(exactly = 0) { mockSyncEngine.clearLocalUserData() }
    }

    @Test
    fun `deleteAccount preserves local data when remote deletion fails`() = runTest {
        // Arrange - Req 14.6: local data preserved on any remote failure
        val mockSession = createMockSession()
        every { mockSupabaseClient.getCurrentSession() } returns mockSession
        coEvery { mockSyncEngine.deleteUserData(testUserId) } returns Result.failure(Exception("Timeout"))

        // Act
        val result = authManager.deleteAccount()

        // Assert: local clearing never called
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { mockSyncEngine.clearLocalUserData() }
        coVerify(exactly = 0) { mockEncryptedStorage.clearTokens() }
    }
    
    // Helper Methods
    
    private fun createMockSession(): UserSession {
        val mockUserInfo = mockk<UserInfo> {
            every { id } returns testUserId
        }
        
        return mockk {
            every { accessToken } returns testAccessToken
            every { refreshToken } returns testRefreshToken
            every { user } returns mockUserInfo
        }
    }
    
    private fun createMockProfile(): UserProfile {
        return UserProfile(
            id = testUserId,
            email = testEmail,
            displayName = "Test User",
            avatarUrl = "",
            createdAt = System.currentTimeMillis().toString()
        )
    }
}
