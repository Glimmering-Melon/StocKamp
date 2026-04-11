package com.stockamp.data.auth

import com.stockamp.data.model.UserProfile
import com.stockamp.data.storage.EncryptedStorage
import com.stockamp.data.supabase.SupabaseClient
import com.stockamp.data.sync.SyncEngine
import io.github.jan.supabase.gotrue.user.UserInfo
import io.github.jan.supabase.gotrue.user.UserSession
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.email
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Property-based tests for AuthManagerImpl.
 *
 * Feature: supabase-integration
 */
class AuthManagerPropertyTest {

    private lateinit var authManager: AuthManagerImpl
    private lateinit var mockSupabaseClient: SupabaseClient
    private lateinit var mockEncryptedStorage: EncryptedStorage
    private lateinit var mockSyncEngine: SyncEngine
    private lateinit var mockLazySyncEngine: dagger.Lazy<SyncEngine>

    // Tracks the last stored access token so assertions can verify it was set
    private var storedAccessToken: String? = null

    @Before
    fun setup() {
        mockSupabaseClient = mockk(relaxed = true)
        mockEncryptedStorage = mockk(relaxed = true)
        mockSyncEngine = mockk(relaxed = true)
        mockLazySyncEngine = mockk {
            every { get() } returns mockSyncEngine
        }

        storedAccessToken = null

        coEvery { mockEncryptedStorage.storeAccessToken(any()) } answers {
            storedAccessToken = firstArg()
        }
        coEvery { mockEncryptedStorage.getAccessToken() } answers { storedAccessToken }
        coEvery { mockEncryptedStorage.storeRefreshToken(any()) } just Runs
        coEvery { mockEncryptedStorage.clearTokens() } answers { storedAccessToken = null }
        coEvery { mockSyncEngine.performFullSync() } returns Result.success(Unit)

        authManager = AuthManagerImpl(
            mockSupabaseClient,
            mockEncryptedStorage,
            mockLazySyncEngine
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    /**
     * Property 4: Successful Login Authenticates and Triggers Sync
     *
     * For any valid credentials, login should succeed, store an auth token in encrypted
     * storage, and trigger the sync engine to synchronize data.
     *
     * Validates: Requirements 2.1, 2.2, 2.3
     *
     * Feature: supabase-integration, Property 4: Successful Login Authenticates and Triggers Sync
     */
    @Test
    fun `property 4 - successful login authenticates and triggers sync`() = runTest {
        checkAll(100, Arb.email(), Arb.string(8..20)) { email, password ->
            // Arrange: reset state between iterations
            storedAccessToken = null
            authManager.logout()

            val userId = "user-${email.hashCode()}"
            val accessToken = "token-${email.hashCode()}"
            val refreshToken = "refresh-${email.hashCode()}"

            val mockUserInfo = mockk<UserInfo> {
                every { id } returns userId
            }
            val mockSession = mockk<UserSession> {
                every { this@mockk.accessToken } returns accessToken
                every { this@mockk.refreshToken } returns refreshToken
                every { user } returns mockUserInfo
            }
            val mockProfile = UserProfile(
                id = userId,
                email = email,
                displayName = email.substringBefore("@"),
                createdAt = System.currentTimeMillis().toString()
            )

            coEvery { mockSupabaseClient.signIn(email, password) } returns Result.success(mockSession)
            coEvery { mockSupabaseClient.fetchUserProfile(userId) } returns Result.success(mockProfile)
            every { mockSupabaseClient.getCurrentSession() } returns mockSession

            // Act: Login
            val result = authManager.login(email, password)

            // Assert: Authenticated (Req 2.1), token stored (Req 2.2), sync triggered (Req 2.3)
            result.isSuccess shouldBe true
            storedAccessToken shouldNotBe null
            coVerify(atLeast = 1) { mockSyncEngine.performFullSync() }
        }
    }

    /**
     * Property 6: Valid Token Restores Session
     *
     * For any valid auth token stored in encrypted storage, when the app starts,
     * the session should be automatically restored without requiring re-authentication.
     *
     * Validates: Requirements 3.1, 3.2
     *
     * Feature: supabase-integration, Property 6: Valid Token Restores Session
     */
    @Test
    fun `property 6 - valid token restores session`() = runTest {
        checkAll(100, Arb.string(20..40)) { token ->
            // Arrange: token in storage and a valid current session
            storedAccessToken = token
            coEvery { mockEncryptedStorage.getAccessToken() } returns token

            val userId = "user-${token.hashCode()}"
            val email = "user${token.hashCode()}@example.com"

            val mockUserInfo = mockk<UserInfo> {
                every { id } returns userId
            }
            val mockSession = mockk<UserSession> {
                every { accessToken } returns token
                every { refreshToken } returns "refresh-$token"
                every { user } returns mockUserInfo
            }
            val mockProfile = UserProfile(
                id = userId,
                email = email,
                displayName = email.substringBefore("@"),
                createdAt = System.currentTimeMillis().toString()
            )

            every { mockSupabaseClient.getCurrentSession() } returns mockSession
            coEvery { mockSupabaseClient.fetchUserProfile(userId) } returns Result.success(mockProfile)

            // Act: restore session (simulates app start)
            val result = authManager.restoreSession()

            // Assert: session restored successfully with a non-null profile (Req 3.1, 3.2)
            result.isSuccess shouldBe true
            result.getOrNull() shouldNotBe null
            result.getOrNull()?.email shouldBe email
        }
    }

    /**
     * Property 7: Logout Clears Session Data
     *
     * For any authenticated session, when logout is performed, the auth token should
     * be revoked and all local session data should be cleared from encrypted storage.
     *
     * Validates: Requirements 3.5
     *
     * Feature: supabase-integration, Property 7: Logout Clears Session Data
     */
    @Test
    fun `property 7 - logout clears session data`() = runTest {
        checkAll(100, Arb.email(), Arb.string(8..20)) { email, password ->
            // Arrange: login first to establish a session
            storedAccessToken = null

            val userId = "user-${email.hashCode()}"
            val accessToken = "token-${email.hashCode()}"

            val mockUserInfo = mockk<UserInfo> {
                every { id } returns userId
            }
            val mockSession = mockk<UserSession> {
                every { this@mockk.accessToken } returns accessToken
                every { this@mockk.refreshToken } returns "refresh-$accessToken"
                every { user } returns mockUserInfo
            }
            val mockProfile = UserProfile(
                id = userId,
                email = email,
                displayName = email.substringBefore("@"),
                createdAt = System.currentTimeMillis().toString()
            )

            coEvery { mockSupabaseClient.signIn(email, password) } returns Result.success(mockSession)
            coEvery { mockSupabaseClient.fetchUserProfile(userId) } returns Result.success(mockProfile)
            every { mockSupabaseClient.getCurrentSession() } returns mockSession

            authManager.login(email, password)

            // Act: logout
            val logoutResult = authManager.logout()

            // Assert: logout succeeded, tokens cleared (Req 3.5)
            logoutResult.isSuccess shouldBe true
            authManager.isAuthenticated().first() shouldBe false
            coVerify(atLeast = 1) { mockEncryptedStorage.clearTokens() }
        }
    }

    /**
     * Property 8: Profile Retrieval Returns User Data
     *
     * For any authenticated user, requesting profile data should successfully retrieve
     * a UserProfile containing email, display name, and account creation timestamp.
     *
     * Validates: Requirements 4.1, 4.5
     *
     * Feature: supabase-integration, Property 8: Profile Retrieval Returns User Data
     */
    @Test
    fun `property 8 - profile retrieval returns user data`() = runTest {
        checkAll(100, Arb.email(), Arb.string(5..20)) { email, displayName ->
            // Arrange: authenticated session with a known profile
            val userId = "user-${email.hashCode()}"
            val createdAt = System.currentTimeMillis().toString()

            val mockUserInfo = mockk<UserInfo> {
                every { id } returns userId
            }
            val mockSession = mockk<UserSession> {
                every { accessToken } returns "token-$userId"
                every { refreshToken } returns "refresh-$userId"
                every { user } returns mockUserInfo
            }
            val mockProfile = UserProfile(
                id = userId,
                email = email,
                displayName = displayName,
                createdAt = createdAt
            )

            every { mockSupabaseClient.getCurrentSession() } returns mockSession
            coEvery { mockSupabaseClient.fetchUserProfile(userId) } returns Result.success(mockProfile)

            // Reset cached user so getProfile fetches from Supabase
            authManager.logout()

            // Act: retrieve profile
            val result = authManager.getProfile()

            // Assert: success with all required fields populated (Req 4.1, 4.5)
            result.isSuccess shouldBe true
            val profile = result.getOrNull()
            profile shouldNotBe null
            profile?.email shouldBe email
            profile?.displayName shouldBe displayName
            profile?.createdAt shouldNotBe null
        }
    }

    /**
     * Property 9: Profile Update Persists Changes
     *
     * For any authenticated user and any valid profile update, the changes should be
     * persisted to Supabase and the local cached profile should reflect the updated values.
     *
     * Validates: Requirements 4.2, 4.3
     *
     * Feature: supabase-integration, Property 9: Profile Update Persists Changes
     */
    @Test
    fun `property 9 - profile update persists changes`() = runTest {
        checkAll(100, Arb.email(), Arb.string(3..30)) { email, newDisplayName ->
            // Arrange: authenticated session
            val userId = "user-${email.hashCode()}"

            val mockUserInfo = mockk<UserInfo> {
                every { id } returns userId
            }
            val mockSession = mockk<UserSession> {
                every { accessToken } returns "token-$userId"
                every { refreshToken } returns "refresh-$userId"
                every { user } returns mockUserInfo
            }
            val updatedProfile = UserProfile(
                id = userId,
                email = email,
                displayName = newDisplayName,
                createdAt = System.currentTimeMillis().toString()
            )

            every { mockSupabaseClient.getCurrentSession() } returns mockSession
            coEvery { mockSupabaseClient.updateUserProfile(userId, newDisplayName) } returns Result.success(updatedProfile)

            // Act: update profile
            val result = authManager.updateProfile(newDisplayName)

            // Assert: update succeeded with updated displayName (Req 4.2)
            result.isSuccess shouldBe true
            result.getOrNull()?.displayName shouldBe newDisplayName

            // Assert: local cached profile reflects updated values (Req 4.3)
            val currentUser = authManager.getCurrentUser().first()
            currentUser shouldNotBe null
            currentUser?.displayName shouldBe newDisplayName
        }
    }

    /**
     * Property 1: Successful Registration Creates Account and Stores Token
     *
     * For any valid email and password combination, when registration succeeds,
     * the system should create a Supabase account, store an auth token in encrypted
     * storage, and create a user profile record that can be retrieved.
     *
     * Validates: Requirements 1.1, 1.2, 1.3
     *
     * Feature: supabase-integration, Property 1: Successful Registration Creates Account and Stores Token
     */
    @Test
    fun `property 1 - successful registration creates account and stores token`() = runTest {
        checkAll(100, Arb.email(), Arb.string(8..20)) { email, password ->
            // Arrange: reset state between iterations
            storedAccessToken = null
            authManager.logout()

            val userId = "user-${email.hashCode()}"
            val accessToken = "token-${email.hashCode()}"
            val refreshToken = "refresh-${email.hashCode()}"

            val mockUserInfo = mockk<UserInfo> {
                every { id } returns userId
            }
            val mockSession = mockk<UserSession> {
                every { this@mockk.accessToken } returns accessToken
                every { this@mockk.refreshToken } returns refreshToken
                every { user } returns mockUserInfo
            }
            val mockProfile = UserProfile(
                id = userId,
                email = email,
                displayName = email.substringBefore("@"),
                createdAt = System.currentTimeMillis().toString()
            )

            coEvery { mockSupabaseClient.signUp(email, password) } returns Result.success(mockSession)
            coEvery { mockSupabaseClient.fetchUserProfile(userId) } returns Result.success(mockProfile)
            every { mockSupabaseClient.getCurrentSession() } returns mockSession

            // Act: Register
            val result = authManager.register(email, password)

            // Assert: Account created (Req 1.1), token stored (Req 1.2), profile exists (Req 1.3)
            result.isSuccess shouldBe true
            storedAccessToken shouldNotBe null
            val profile = authManager.getProfile()
            profile.isSuccess shouldBe true
            profile.getOrNull()?.email shouldBe email
        }
    }

    // -------------------------------------------------------------------------
    // Account Deletion Properties (Requirements 14.1 – 14.6)
    // -------------------------------------------------------------------------

    /**
     * Property 27: Account Deletion Removes All User Data
     *
     * For any user account deletion request, the auth manager should delete the user
     * profile, and the sync engine should delete all associated watchlist items and
     * journal entries from Supabase.
     *
     * Validates: Requirements 14.1, 14.2, 14.3
     *
     * Feature: supabase-integration, Property 27: Account Deletion Removes All User Data
     */
    @Test
    fun `property 27 - account deletion removes all user data`() = runTest {
        checkAll(100, Arb.string(5..20)) { userIdSuffix ->
            // Arrange: authenticated session
            val userId = "user-$userIdSuffix"

            val mockUserInfo = mockk<UserInfo> {
                every { id } returns userId
            }
            val mockSession = mockk<UserSession> {
                every { accessToken } returns "token-$userId"
                every { refreshToken } returns "refresh-$userId"
                every { user } returns mockUserInfo
            }

            every { mockSupabaseClient.getCurrentSession() } returns mockSession
            coEvery { mockSyncEngine.deleteUserData(userId) } returns Result.success(Unit)
            coEvery { mockSupabaseClient.deleteUserData(userId) } returns Result.success(Unit)
            coEvery { mockSyncEngine.clearLocalUserData() } just Runs

            // Act: delete account
            val result = authManager.deleteAccount()

            // Assert: deletion succeeded (Req 14.1, 14.2, 14.3)
            result.isSuccess shouldBe true
            // syncEngine.deleteUserData called to remove remote watchlist + journal (Req 14.2, 14.3)
            coVerify(atLeast = 1) { mockSyncEngine.deleteUserData(userId) }
            // supabaseClient.deleteUserData called to remove user profile (Req 14.1)
            coVerify(atLeast = 1) { mockSupabaseClient.deleteUserData(userId) }
        }
    }

    /**
     * Property 28: Account Deletion Clears Local Data
     *
     * For any completed account deletion, the auth manager should clear all local data
     * including Room database records and revoke all auth tokens.
     *
     * Validates: Requirements 14.4, 14.5
     *
     * Feature: supabase-integration, Property 28: Account Deletion Clears Local Data
     */
    @Test
    fun `property 28 - account deletion clears local data`() = runTest {
        checkAll(100, Arb.string(5..20)) { userIdSuffix ->
            // Arrange: authenticated session with a stored token
            val userId = "user-$userIdSuffix"
            storedAccessToken = "token-$userId"

            val mockUserInfo = mockk<UserInfo> {
                every { id } returns userId
            }
            val mockSession = mockk<UserSession> {
                every { accessToken } returns "token-$userId"
                every { refreshToken } returns "refresh-$userId"
                every { user } returns mockUserInfo
            }

            every { mockSupabaseClient.getCurrentSession() } returns mockSession
            coEvery { mockSyncEngine.deleteUserData(userId) } returns Result.success(Unit)
            coEvery { mockSupabaseClient.deleteUserData(userId) } returns Result.success(Unit)
            coEvery { mockSyncEngine.clearLocalUserData() } just Runs

            // Act: delete account
            val result = authManager.deleteAccount()

            // Assert: local data cleared (Req 14.4) and tokens revoked (Req 14.5)
            result.isSuccess shouldBe true
            // clearLocalUserData called only after remote deletions succeed (Req 14.4)
            coVerify(atLeast = 1) { mockSyncEngine.clearLocalUserData() }
            // tokens cleared (Req 14.5)
            coVerify(atLeast = 1) { mockEncryptedStorage.clearTokens() }
            storedAccessToken shouldBe null
        }
    }

    /**
     * Property 29: Failed Deletion Preserves Data
     *
     * For any account deletion request that fails (remote deletion fails), the auth
     * manager should return an error and preserve all local data in the Room database
     * unchanged — i.e., clearLocalUserData() must NOT be called.
     *
     * Validates: Requirements 14.6
     *
     * Feature: supabase-integration, Property 29: Failed Deletion Preserves Data
     */
    @Test
    fun `property 29 - failed deletion preserves data`() = runTest {
        checkAll(100, Arb.string(5..20)) { userIdSuffix ->
            // Arrange: authenticated session where remote deletion fails
            val userId = "user-$userIdSuffix"
            storedAccessToken = "token-$userId"

            val mockUserInfo = mockk<UserInfo> {
                every { id } returns userId
            }
            val mockSession = mockk<UserSession> {
                every { accessToken } returns "token-$userId"
                every { refreshToken } returns "refresh-$userId"
                every { user } returns mockUserInfo
            }

            every { mockSupabaseClient.getCurrentSession() } returns mockSession
            // Remote deletion fails
            coEvery { mockSyncEngine.deleteUserData(userId) } returns Result.failure(Exception("Network error"))

            // Act: attempt account deletion
            val result = authManager.deleteAccount()

            // Assert: error returned (Req 14.6) and local data preserved
            result.isFailure shouldBe true
            // clearLocalUserData must NOT be called when remote deletion fails (Req 14.6)
            coVerify(exactly = 0) { mockSyncEngine.clearLocalUserData() }
            // tokens must NOT be cleared (local data preserved)
            coVerify(exactly = 0) { mockEncryptedStorage.clearTokens() }
        }
    }
}