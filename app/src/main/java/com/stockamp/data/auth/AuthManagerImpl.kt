package com.stockamp.data.auth

import android.util.Log
import com.stockamp.data.model.UserProfile
import com.stockamp.data.storage.EncryptedStorage
import com.stockamp.data.supabase.EmailConfirmationRequiredException
import com.stockamp.data.supabase.SupabaseClient
import com.stockamp.data.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of AuthManager for user authentication and profile management.
 * Integrates with Supabase GoTrue for authentication and manages session persistence.
 */
@Singleton
class AuthManagerImpl @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val encryptedStorage: EncryptedStorage,
    private val syncEngine: dagger.Lazy<SyncEngine> // Lazy to break circular dependency
) : AuthManager {
    
    private val _currentUser = MutableStateFlow<UserProfile?>(null)
    private val TAG = "AuthManagerImpl"
    
    // Authentication
    
    override suspend fun register(email: String, password: String): Result<UserProfile> {
        return try {
            if (password.length < 8) {
                return Result.failure(IllegalArgumentException("Password must be at least 8 characters"))
            }
            
            val sessionResult = supabaseClient.signUp(email, password)
            if (sessionResult.isFailure) {
                val error = sessionResult.exceptionOrNull()

                // Email confirmation required — đây là thành công, không phải lỗi
                if (error is EmailConfirmationRequiredException) {
                    Log.d(TAG, "Registration successful, email confirmation required")
                    return Result.success(
                        UserProfile(id = "", email = email, displayName = email.substringBefore("@"))
                    )
                }

                Log.e(TAG, "Registration failed", error)
                val errorMessage = error?.message ?: ""
                if (errorMessage.contains("already registered", ignoreCase = true) ||
                    errorMessage.contains("already exists", ignoreCase = true)) {
                    return Result.failure(IllegalStateException("Email này đã được đăng ký"))
                }
                return Result.failure(error ?: Exception("Đăng ký thất bại"))
            }
            
            val session = sessionResult.getOrThrow()
            
            // Nếu không có session → Supabase đang chờ xác nhận email
            if (session.accessToken.isBlank() || session.user == null) {
                Log.d(TAG, "Registration successful, email confirmation required")
                // Trả về profile tạm với email để UI hiển thị thông báo
                return Result.success(
                    UserProfile(id = "", email = email, displayName = email.substringBefore("@"))
                )
            }
            
            // Có session ngay (email confirmation tắt)
            encryptedStorage.storeAccessToken(session.accessToken)
            session.refreshToken?.let { encryptedStorage.storeRefreshToken(it) }
            
            val userId = session.user!!.id
            val profileResult = supabaseClient.fetchUserProfile(userId)
            val profile = if (profileResult.isSuccess) {
                profileResult.getOrThrow()
            } else {
                val displayName = email.substringBefore("@")
                val createResult = supabaseClient.updateUserProfile(userId, displayName)
                if (createResult.isSuccess) createResult.getOrThrow()
                else UserProfile(id = userId, email = email, displayName = displayName)
            }
            
            _currentUser.value = profile
            Log.d(TAG, "Registration successful for user: ${profile.email}")
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Registration error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun login(email: String, password: String): Result<UserProfile> {
        return try {
            // Sign in with Supabase (Req 2.1)
            val sessionResult = supabaseClient.signIn(email, password)
            if (sessionResult.isFailure) {
                val error = sessionResult.exceptionOrNull()
                Log.e(TAG, "Login failed", error)

                // Check for network unavailability (Req 2.5)
                if (error is java.net.UnknownHostException ||
                    error is java.net.SocketTimeoutException ||
                    error is java.io.IOException) {
                    return Result.failure(
                        IllegalStateException("Unable to connect. Please check your internet connection")
                    )
                }

                // Check for invalid credentials error (Req 2.4)
                val errorMessage = error?.message ?: ""
                if (errorMessage.contains("invalid", ignoreCase = true) ||
                    errorMessage.contains("credentials", ignoreCase = true) ||
                    errorMessage.contains("unauthorized", ignoreCase = true) ||
                    errorMessage.contains("wrong", ignoreCase = true)) {
                    return Result.failure(IllegalArgumentException("Invalid email or password"))
                }

                return Result.failure(error ?: Exception("Login failed"))
            }

            val session = sessionResult.getOrThrow()

            // Store tokens securely (Req 2.2)
            encryptedStorage.storeAccessToken(session.accessToken)
            session.refreshToken?.let { encryptedStorage.storeRefreshToken(it) }

            // Fetch user profile
            val userId = session.user?.id ?: return Result.failure(Exception("User ID not found"))
            val profileResult = supabaseClient.fetchUserProfile(userId)

            if (profileResult.isFailure) {
                Log.e(TAG, "Failed to fetch profile", profileResult.exceptionOrNull())
                return Result.failure(profileResult.exceptionOrNull() ?: Exception("Failed to fetch profile"))
            }

            val profile = profileResult.getOrThrow()
            _currentUser.value = profile

            // Trigger initial sync after successful login (Req 2.3)
            try {
                syncEngine.get().performFullSync()
            } catch (e: Exception) {
                Log.w(TAG, "Initial sync failed, will retry later", e)
            }

            // Trigger initial migration on first login only (Req 11.3)
            if (!encryptedStorage.isMigrationComplete()) {
                Log.d(TAG, "Migration not complete, triggering initial migration")
                try {
                    syncEngine.get().performInitialMigration()
                } catch (e: Exception) {
                    Log.w(TAG, "Initial migration failed, will retry later", e)
                }
            }

            Log.d(TAG, "Login successful for user: ${profile.email}")
            Result.success(profile)
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Network unavailable during login", e)
            Result.failure(IllegalStateException("Unable to connect. Please check your internet connection"))
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Network timeout during login", e)
            Result.failure(IllegalStateException("Unable to connect. Please check your internet connection"))
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun logout(): Result<Unit> {
        return try {
            // Sign out from Supabase
            val signOutResult = supabaseClient.signOut()
            if (signOutResult.isFailure) {
                Log.w(TAG, "Supabase sign out failed", signOutResult.exceptionOrNull())
            }
            
            // Clear tokens and session data
            encryptedStorage.clearTokens()

            // Req 8.5: unsubscribe from realtime channels on logout
            try {
                syncEngine.get().unsubscribeFromRealtimeUpdates()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unsubscribe realtime on logout", e)
            }

            _currentUser.value = null
            
            Log.d(TAG, "Logout successful")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Logout error", e)
            Result.failure(e)
        }
    }
    
    // Session Management
    
    override suspend fun restoreSession(): Result<UserProfile?> {
        return try {
            val accessToken = encryptedStorage.getAccessToken()
            
            if (accessToken.isNullOrEmpty()) {
                Log.d(TAG, "No stored access token found")
                return Result.success(null)
            }
            
            // Check if current session is valid
            val currentSession = supabaseClient.getCurrentSession()
            if (currentSession == null) {
                // Try to refresh the session
                val refreshResult = refreshToken()
                if (refreshResult.isFailure) {
                    Log.w(TAG, "Session refresh failed, clearing tokens")
                    encryptedStorage.clearTokens()
                    return Result.success(null)
                }
            }
            
            // Fetch user profile
            val session = supabaseClient.getCurrentSession()
            val userId = session?.user?.id
            
            if (userId == null) {
                Log.w(TAG, "No user ID in session")
                encryptedStorage.clearTokens()
                return Result.success(null)
            }
            
            val profileResult = supabaseClient.fetchUserProfile(userId)
            if (profileResult.isFailure) {
                Log.w(TAG, "Failed to fetch profile during session restore")
                encryptedStorage.clearTokens()
                return Result.success(null)
            }
            
            val profile = profileResult.getOrThrow()
            _currentUser.value = profile
            
            Log.d(TAG, "Session restored for user: ${profile.email}")
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Session restore error", e)
            encryptedStorage.clearTokens()
            Result.success(null)
        }
    }
    
    override suspend fun refreshToken(): Result<Unit> {
        return try {
            val refreshToken = encryptedStorage.getRefreshToken()
            
            if (refreshToken.isNullOrEmpty()) {
                return Result.failure(Exception("No refresh token available"))
            }
            
            // Refresh session with Supabase
            val sessionResult = supabaseClient.refreshSession()
            if (sessionResult.isFailure) {
                Log.e(TAG, "Token refresh failed", sessionResult.exceptionOrNull())
                return Result.failure(sessionResult.exceptionOrNull() ?: Exception("Token refresh failed"))
            }
            
            val session = sessionResult.getOrThrow()
            
            // Store new tokens
            encryptedStorage.storeAccessToken(session.accessToken)
            session.refreshToken?.let { encryptedStorage.storeRefreshToken(it) }
            
            Log.d(TAG, "Token refresh successful")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            Result.failure(e)
        }
    }
    
    override fun getCurrentUser(): Flow<UserProfile?> {
        return _currentUser.asStateFlow()
    }
    
    override fun isAuthenticated(): Flow<Boolean> {
        return _currentUser.map { it != null }
    }
    
    // Profile Management
    
    override suspend fun getProfile(): Result<UserProfile> {
        return try {
            val session = supabaseClient.getCurrentSession()
            val userId = session?.user?.id
            
            if (userId == null) {
                return Result.failure(IllegalStateException("User not authenticated"))
            }
            
            // Return cached profile if available
            _currentUser.value?.let {
                if (it.id == userId) {
                    return Result.success(it)
                }
            }
            
            // Fetch from Supabase
            val profileResult = supabaseClient.fetchUserProfile(userId)
            if (profileResult.isFailure) {
                Log.e(TAG, "Failed to fetch profile", profileResult.exceptionOrNull())
                return Result.failure(profileResult.exceptionOrNull() ?: Exception("Failed to fetch profile"))
            }
            
            val profile = profileResult.getOrThrow()
            _currentUser.value = profile
            
            Log.d(TAG, "Profile fetched successfully")
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Get profile error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun updateProfile(displayName: String): Result<UserProfile> {
        return try {
            val session = supabaseClient.getCurrentSession()
            val userId = session?.user?.id
            
            if (userId == null) {
                return Result.failure(IllegalStateException("User not authenticated"))
            }
            
            // Update profile in Supabase
            val profileResult = supabaseClient.updateUserProfile(userId, displayName)
            if (profileResult.isFailure) {
                Log.e(TAG, "Failed to update profile", profileResult.exceptionOrNull())
                return Result.failure(profileResult.exceptionOrNull() ?: Exception("Failed to update profile"))
            }
            
            val updatedProfile = profileResult.getOrThrow()
            _currentUser.value = updatedProfile
            
            Log.d(TAG, "Profile updated successfully")
            Result.success(updatedProfile)
        } catch (e: Exception) {
            Log.e(TAG, "Update profile error", e)
            Result.failure(e)
        }
    }
    
    override suspend fun deleteAccount(): Result<Unit> {
        return try {
            val session = supabaseClient.getCurrentSession()
            val userId = session?.user?.id
            
            if (userId == null) {
                return Result.failure(IllegalStateException("User not authenticated"))
            }
            
            // Req 14.2, 14.3: Delete all remote Watchlist and Journal data via SyncEngine.
            // Local data is NOT cleared here so it is preserved if this step fails (Req 14.6).
            val deleteResult = syncEngine.get().deleteUserData(userId)
            if (deleteResult.isFailure) {
                Log.e(TAG, "Failed to delete user data", deleteResult.exceptionOrNull())
                return Result.failure(
                    IllegalStateException("Unable to delete account. Please try again later.")
                )
            }

            // Req 14.1: Delete user profile from Supabase.
            // If this fails, local data is still intact (Req 14.6).
            val profileDeleteResult = supabaseClient.deleteUserData(userId)
            if (profileDeleteResult.isFailure) {
                Log.e(TAG, "Failed to delete user profile", profileDeleteResult.exceptionOrNull())
                return Result.failure(
                    IllegalStateException("Unable to delete account. Please try again later.")
                )
            }

            // All remote deletions succeeded — now safe to clear local data (Req 14.4).
            syncEngine.get().clearLocalUserData()

            // Req 8.5: Unsubscribe from realtime channels before signing out
            try {
                syncEngine.get().unsubscribeFromRealtimeUpdates()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unsubscribe realtime during account deletion", e)
            }

            // Req 14.5: Revoke all auth tokens and sign out
            supabaseClient.signOut()
            encryptedStorage.clearTokens()
            _currentUser.value = null
            
            Log.d(TAG, "Account deleted successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Delete account error", e)
            Result.failure(IllegalStateException("Unable to delete account. Please try again later."))
        }
    }
}
