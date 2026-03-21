package com.stockamp.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Test database migration from version 1 to version 2.
 * 
 * Validates: Requirements 12.1, 12.2, 12.3, 12.4
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StocKampDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate1To2() {
        // Create database with version 1 schema
        helper.createDatabase(TEST_DB, 1).apply {
            // Insert test data into version 1 schema
            execSQL(
                """
                INSERT INTO watchlist (id, symbol, name, addedAt) 
                VALUES (1, 'AAPL', 'Apple Inc.', 1234567890000)
                """.trimIndent()
            )
            
            execSQL(
                """
                INSERT INTO journal_entries (id, symbol, action, quantity, price, totalValue, notes, emotion, strategy, createdAt) 
                VALUES (1, 'AAPL', 'BUY', 10, 150.0, 1500.0, 'Test note', 'confident', 'Test strategy', 1234567890000)
                """.trimIndent()
            )
            
            close()
        }

        // Run migration to version 2
        helper.runMigrationsAndValidate(TEST_DB, 2, true, StocKampDatabase.MIGRATION_1_2).apply {
            // Verify watchlist table has new columns
            query("SELECT userId, createdAt, modifiedAt, syncedAt, isDeleted FROM watchlist WHERE id = 1").use { cursor ->
                assert(cursor.moveToFirst())
                assert(cursor.getColumnIndex("userId") >= 0)
                assert(cursor.getColumnIndex("createdAt") >= 0)
                assert(cursor.getColumnIndex("modifiedAt") >= 0)
                assert(cursor.getColumnIndex("syncedAt") >= 0)
                assert(cursor.getColumnIndex("isDeleted") >= 0)
                
                // Verify default values were set
                val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("createdAt"))
                val modifiedAt = cursor.getLong(cursor.getColumnIndexOrThrow("modifiedAt"))
                assert(createdAt > 0) { "createdAt should be set to addedAt value" }
                assert(modifiedAt > 0) { "modifiedAt should be set to addedAt value" }
            }
            
            // Verify journal_entries table has new columns
            query("SELECT userId, modifiedAt, syncedAt, isDeleted FROM journal_entries WHERE id = 1").use { cursor ->
                assert(cursor.moveToFirst())
                assert(cursor.getColumnIndex("userId") >= 0)
                assert(cursor.getColumnIndex("modifiedAt") >= 0)
                assert(cursor.getColumnIndex("syncedAt") >= 0)
                assert(cursor.getColumnIndex("isDeleted") >= 0)
                
                // Verify default values were set
                val modifiedAt = cursor.getLong(cursor.getColumnIndexOrThrow("modifiedAt"))
                assert(modifiedAt > 0) { "modifiedAt should be set to createdAt value" }
            }
            
            // Verify sync_queue table was created
            query("SELECT * FROM sync_queue").use { cursor ->
                assert(cursor.columnCount > 0) { "sync_queue table should exist" }
                assert(cursor.getColumnIndex("id") >= 0)
                assert(cursor.getColumnIndex("operationType") >= 0)
                assert(cursor.getColumnIndex("entityId") >= 0)
                assert(cursor.getColumnIndex("entityData") >= 0)
                assert(cursor.getColumnIndex("createdAt") >= 0)
                assert(cursor.getColumnIndex("retryCount") >= 0)
                assert(cursor.getColumnIndex("lastAttempt") >= 0)
            }
            
            // Verify sync_metadata table was created
            query("SELECT * FROM sync_metadata").use { cursor ->
                assert(cursor.columnCount > 0) { "sync_metadata table should exist" }
                assert(cursor.getColumnIndex("key") >= 0)
                assert(cursor.getColumnIndex("value") >= 0)
                assert(cursor.getColumnIndex("updatedAt") >= 0)
            }
            
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun testAllMigrations() {
        // Create database with version 1
        helper.createDatabase(TEST_DB, 1).apply {
            close()
        }

        // Open database with version 2 and provide all migrations
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            StocKampDatabase::class.java,
            TEST_DB
        )
            .addMigrations(StocKampDatabase.MIGRATION_1_2)
            .build()
            .apply {
                openHelper.writableDatabase.close()
            }
    }
}
