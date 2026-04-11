package com.stockamp.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of EncryptedStorage using Android EncryptedSharedPreferences with AES256-GCM encryption.
 * Securely stores authentication tokens and migration flags.
 */
@Singleton
class EncryptedStorageImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : EncryptedStorage {
    
    private val sharedPreferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    override suspend fun storeAccessToken(token: String) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().putString(KEY_ACCESS_TOKEN, token).apply()
    }
    
    override suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        sharedPreferences.getString(KEY_ACCESS_TOKEN, null)
    }
    
    override suspend fun storeRefreshToken(token: String) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }
    
    override suspend fun getRefreshToken(): String? = withContext(Dispatchers.IO) {
        sharedPreferences.getString(KEY_REFRESH_TOKEN, null)
    }
    
    override suspend fun clearTokens() = withContext(Dispatchers.IO) {
        sharedPreferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }
    
    override suspend fun storeMigrationComplete(complete: Boolean) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().putBoolean(KEY_MIGRATION_COMPLETE, complete).apply()
    }
    
    override suspend fun isMigrationComplete(): Boolean = withContext(Dispatchers.IO) {
        sharedPreferences.getBoolean(KEY_MIGRATION_COMPLETE, false)
    }
    
    companion object {
        private const val PREFS_FILE_NAME = "stockamp_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_MIGRATION_COMPLETE = "migration_complete"
    }
}
