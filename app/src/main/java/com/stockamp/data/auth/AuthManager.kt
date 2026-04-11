package com.stockamp.data.auth

import com.stockamp.data.model.UserProfile
import kotlinx.coroutines.flow.Flow

/**
 * Manages user authentication, session persistence, and profile operations.
 * This interface will be fully implemented in task 4.
 */
interface AuthManager {
    // Authentication
    suspend fun register(email: String, password: String): Result<UserProfile>
    suspend fun login(email: String, password: String): Result<UserProfile>
    suspend fun logout(): Result<Unit>
    
    // Session Management
    suspend fun restoreSession(): Result<UserProfile?>
    suspend fun refreshToken(): Result<Unit>
    fun getCurrentUser(): Flow<UserProfile?>
    fun isAuthenticated(): Flow<Boolean>
    
    // Profile Management
    suspend fun getProfile(): Result<UserProfile>
    suspend fun updateProfile(displayName: String): Result<UserProfile>
    suspend fun deleteAccount(): Result<Unit>
}
