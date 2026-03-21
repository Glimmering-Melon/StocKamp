package com.stockamp.data.storage

/**
 * Securely stores authentication tokens and sensitive data.
 * This interface will be fully implemented in task 2.
 */
interface EncryptedStorage {
    suspend fun storeAccessToken(token: String)
    suspend fun getAccessToken(): String?
    suspend fun storeRefreshToken(token: String)
    suspend fun getRefreshToken(): String?
    suspend fun clearTokens()
    suspend fun storeMigrationComplete(complete: Boolean)
    suspend fun isMigrationComplete(): Boolean
}
