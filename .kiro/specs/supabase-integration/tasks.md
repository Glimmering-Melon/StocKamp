# Implementation Plan: Supabase Integration

## Overview

This implementation plan breaks down the Supabase integration feature into discrete coding tasks. The approach follows an incremental strategy: infrastructure setup → authentication → data sync → real-time updates → testing. Each task builds on previous work, with checkpoints to validate functionality before proceeding.

## Tasks

- [x] 1. Set up Supabase infrastructure and configuration
  - [x] 1.1 Add Supabase Kotlin SDK dependencies to build.gradle.kts
    - Add supabase-kt, ktor-client, and kotlinx-serialization dependencies
    - Configure BuildConfig feature for API key management
    - _Requirements: 9.1, 9.3_
  
  - [x] 1.2 Create BuildConfig setup for secure API key management
    - Configure build.gradle.kts to load from local.properties
    - Add SUPABASE_URL and SUPABASE_ANON_KEY build config fields
    - Update .gitignore to exclude local.properties
    - _Requirements: 9.2, 9.4, 9.5_
  
  - [x] 1.3 Create SupabaseClient interface and implementation
    - Define SupabaseClient interface with auth, data, and realtime methods
    - Implement SupabaseClientImpl wrapping Supabase SDK
    - Add error handling for missing API keys
    - _Requirements: 9.5_
  
  - [x] 1.4 Set up dependency injection module for Supabase components
    - Create SupabaseModule with Hilt annotations
    - Provide singleton instances for SupabaseClient, AuthManager, SyncEngine
    - Configure NetworkMonitor, EncryptedStorage, and SyncQueue providers
    - _Requirements: 9.1_

- [x] 2. Implement encrypted storage for authentication tokens
  - [x] 2.1 Create EncryptedStorage interface and implementation
    - Define interface for storing/retrieving access and refresh tokens
    - Implement using Android EncryptedSharedPreferences with AES256-GCM
    - Add methods for migration completion flag storage
    - _Requirements: 2.2, 3.1_
  
  - [x] 2.2 Write unit tests for EncryptedStorage
    - Test token storage and retrieval
    - Test token clearing on logout
    - Test migration flag persistence
    - _Requirements: 2.2, 3.1_

- [x] 3. Update Room database schema for sync support
  - [x] 3.1 Add sync fields to WatchlistItem and JournalEntry entities
    - Add createdAt, modifiedAt, syncedAt, isDeleted fields
    - Add userId field for multi-user support
    - Update entity annotations
    - _Requirements: 12.1, 12.2, 12.3, 12.4_
  
  - [x] 3.2 Create new entities for sync queue and metadata
    - Create SyncQueueItem entity with operation type and retry tracking
    - Create SyncMetadata entity for storing sync state
    - Define DAOs for both entities
    - _Requirements: 13.1, 13.3_
  
  - [x] 3.3 Create database migration from version 1 to 2
    - Write Migration object to add new columns to existing tables
    - Create sync_queue and sync_metadata tables
    - Set default values for new columns
    - _Requirements: 12.1, 12.2, 12.3, 12.4_
  
  - [x] 3.4 Write migration tests
    - Test migration from version 1 to 2 preserves existing data
    - Test new columns have correct default values
    - Test new tables are created successfully
    - _Requirements: 12.1, 12.2, 12.3, 12.4_

- [x] 4. Implement AuthManager for user authentication
  - [x] 4.1 Create AuthManager interface and implementation
    - Define interface with register, login, logout, session management methods
    - Implement AuthManagerImpl with Supabase GoTrue integration
    - Add profile management methods (get, update, delete)
    - _Requirements: 1.1, 2.1, 3.1, 4.1, 4.2_
  
  - [x] 4.2 Implement user registration flow
    - Validate email and password (minimum 8 characters)
    - Call Supabase signUp API
    - Store auth tokens in EncryptedStorage
    - Create user profile record in Supabase
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_
  
  - [x] 4.3 Write property test for successful registration
    - **Property 1: Successful Registration Creates Account and Stores Token**
    - **Validates: Requirements 1.1, 1.2, 1.3**
  
  - [x] 4.4 Write unit tests for registration error cases
    - Test duplicate email returns error
    - Test short password returns error
    - Test network failure returns error
    - _Requirements: 1.4, 1.5, 1.6_
  
  - [x] 4.5 Implement user login flow
    - Validate credentials format
    - Call Supabase signIn API
    - Store auth tokens securely
    - Trigger initial sync after successful login
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
  
  - [x] 4.6 Write property test for successful login
    - **Property 4: Successful Login Authenticates and Triggers Sync**
    - **Validates: Requirements 2.1, 2.2, 2.3**
  
  - [x] 4.7 Write unit tests for login error cases
    - Test invalid credentials return error
    - Test network failure returns error
    - _Requirements: 2.4, 2.5_
  
  - [x] 4.8 Implement session management
    - Add session restoration on app start
    - Implement automatic token refresh
    - Handle token expiration and re-authentication
    - Implement logout with token revocation
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_
  
  - [x] 4.9 Write property tests for session management
    - **Property 6: Valid Token Restores Session**
    - **Property 7: Logout Clears Session Data**
    - **Validates: Requirements 3.1, 3.2, 3.5**
  
  - [x] 4.10 Implement user profile management
    - Add getProfile method to fetch from Supabase
    - Add updateProfile method to persist changes
    - Add deleteAccount method to remove all user data
    - Cache profile locally after fetch
    - _Requirements: 4.1, 4.2, 4.3, 4.5, 14.1_
  
  - [x] 4.11 Write property tests for profile management
    - **Property 8: Profile Retrieval Returns User Data**
    - **Property 9: Profile Update Persists Changes**
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.5**

- [x] 5. Checkpoint - Ensure authentication works end-to-end
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement NetworkMonitor for connectivity tracking
  - [x] 6.1 Create NetworkMonitor interface and implementation
    - Define interface with connectivity status flow
    - Implement using ConnectivityManager.NetworkCallback
    - Emit connectivity state changes (AVAILABLE, UNAVAILABLE, LOSING, LOST)
    - _Requirements: 1.6, 2.5, 8.5, 8.6_
  
  - [x] 6.2 Write unit tests for NetworkMonitor
    - Test connectivity state transitions
    - Test flow emissions on network changes
    - _Requirements: 1.6, 2.5_

- [x] 7. Implement SyncQueue for operation queuing
  - [x] 7.1 Create SyncQueue interface and implementation
    - Define interface with enqueue, dequeue, peek, clear methods
    - Implement using Room database sync_queue table
    - Add serialization for SyncOperation types
    - _Requirements: 5.6, 6.6, 10.3, 13.3_
  
  - [x] 7.2 Write unit tests for SyncQueue
    - Test FIFO ordering of operations
    - Test queue persistence across app restarts
    - Test operation serialization/deserialization
    - _Requirements: 5.6, 6.6, 10.3_

- [x] 8. Implement core SyncEngine functionality
  - [x] 8.1 Create SyncEngine interface and implementation skeleton
    - Define interface with sync methods for watchlist and journal
    - Create SyncEngineImpl class structure
    - Add sync status flow and pending operations tracking
    - _Requirements: 5.1, 5.2, 5.3, 6.1, 6.2, 6.3_
  
  - [x] 8.2 Implement Watchlist sync upload logic
    - Detect local changes using Room observers
    - Upload new/modified items to Supabase within 5 seconds when online
    - Handle deletions by marking isDeleted and syncing
    - Queue operations when offline
    - _Requirements: 5.1, 5.2, 5.3, 5.6_
  
  - [x] 8.3 Implement Watchlist sync download logic
    - Fetch remote changes from Supabase on app start
    - Update Room database with remote changes
    - Handle remote deletions
    - _Requirements: 5.4, 5.5_
  
  - [x] 8.4 Implement Journal sync upload logic
    - Detect local changes using Room observers
    - Upload new/modified entries to Supabase within 5 seconds when online
    - Handle deletions by marking isDeleted and syncing
    - Queue operations when offline
    - _Requirements: 6.1, 6.2, 6.3, 6.6_
  
  - [x] 8.5 Implement Journal sync download logic
    - Fetch remote changes from Supabase on app start
    - Update Room database with remote changes
    - Handle remote deletions
    - _Requirements: 6.4, 6.5_
  
  - [x] 8.6 Write property test for remote changes updating local database
    - **Property 10: Remote Changes Update Local Database**
    - **Validates: Requirements 5.5, 6.5**
  
  - [x] 8.7 Write property test for offline change queuing
    - **Property 11: Offline Changes Are Queued**
    - **Validates: Requirements 5.6, 6.6, 10.3**

- [x] 9. Implement conflict resolution logic
  - [x] 9.1 Add conflict detection to SyncEngine
    - Compare modification timestamps between local and remote records
    - Identify conflicts when both versions have changed
    - _Requirements: 7.1, 7.2_
  
  - [x] 9.2 Implement last-write-wins strategy
    - Keep record with most recent modifiedAt timestamp
    - Update losing side with winning record
    - Preserve creation timestamps during resolution
    - _Requirements: 7.1, 7.2, 7.3, 12.6_
  
  - [x] 9.3 Add conflict resolution logging
    - Log all conflict resolutions with details
    - Include local and remote timestamps
    - Record which version won
    - _Requirements: 7.4_
  
  - [x] 9.4 Handle deletion conflicts
    - Preserve deletion when record deleted locally but modified remotely
    - _Requirements: 7.5_
  
  - [x] 9.5 Write property test for last-write-wins conflict resolution
    - **Property 12: Last-Write-Wins Conflict Resolution**
    - **Validates: Requirements 7.1, 7.2, 7.3**
  
  - [x] 9.6 Write property test for conflict logging
    - **Property 13: Conflict Resolutions Are Logged**
    - **Validates: Requirements 7.4**
  
  - [x] 9.7 Write property test for sync preserving creation timestamps
    - **Property 23: Sync Preserves Creation Timestamps**
    - **Validates: Requirements 12.6**

- [x] 10. Checkpoint - Ensure basic sync works bidirectionally
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Implement real-time synchronization
  - [x] 11.1 Add realtime subscription methods to SupabaseClient
    - Implement subscribeToWatchlistChanges using Supabase Realtime
    - Implement subscribeToJournalChanges using Supabase Realtime
    - Add unsubscribeAll method
    - _Requirements: 8.1, 8.2_
  
  - [x] 11.2 Integrate realtime subscriptions into SyncEngine
    - Subscribe to realtime channels when authenticated and online
    - Handle INSERT, UPDATE, DELETE events from realtime
    - Update Room database within 2 seconds of receiving events
    - _Requirements: 8.1, 8.2, 8.3, 8.4_
  
  - [x] 11.3 Implement realtime subscription lifecycle management
    - Unsubscribe when app goes offline
    - Resubscribe when app comes back online
    - Unsubscribe on logout
    - _Requirements: 8.5, 8.6_
  
  - [x] 11.4 Write property tests for realtime subscriptions
    - **Property 14: Realtime Subscriptions Active When Online**
    - **Property 15: Offline Transition Unsubscribes Realtime**
    - **Property 16: Online Transition Resubscribes Realtime**
    - **Validates: Requirements 8.1, 8.2, 8.5, 8.6**

- [x] 12. Implement initial data migration
  - [x] 12.1 Add migration detection logic
    - Check migration completion flag in EncryptedStorage
    - Trigger migration on first login only
    - _Requirements: 11.3_
  
  - [x] 12.2 Implement migration upload for existing data
    - Upload all existing WatchlistItem records from Room to Supabase
    - Upload all existing JournalEntry records from Room to Supabase
    - Display progress indicator during migration
    - _Requirements: 11.1, 11.2, 11.5_
  
  - [x] 12.3 Add migration completion tracking
    - Mark migration as complete after successful upload
    - Prevent duplicate uploads on subsequent logins
    - _Requirements: 11.3_
  
  - [x] 12.4 Implement partial migration failure handling
    - Track which records failed to upload
    - Retry failed records on next sync attempt
    - _Requirements: 11.4_
  
  - [x] 12.5 Write property test for migration completion
    - **Property 19: Migration Completes Only Once**
    - **Validates: Requirements 11.3**
  
  - [x] 12.6 Write property test for failed migration retry
    - **Property 20: Failed Migration Records Retry**
    - **Validates: Requirements 11.4**

- [x] 13. Implement error handling and retry logic
  - [x] 13.1 Add exponential backoff retry for network failures
    - Retry sync operations up to 3 times on network error
    - Use exponential backoff delays (1s, 2s, 4s)
    - _Requirements: 13.1_
  
  - [x] 13.2 Add authentication error handling with token refresh
    - Detect authentication errors during sync
    - Attempt token refresh before retrying operation
    - _Requirements: 13.2_
  
  - [x] 13.3 Implement operation queuing after exhausted retries
    - Add failed operations to sync queue after 3 failed attempts
    - Process queued operations in next sync cycle
    - _Requirements: 13.3_
  
  - [x] 13.4 Add comprehensive error logging
    - Log server errors with full context
    - Include operation details and error responses
    - Display user-friendly error messages without technical details
    - _Requirements: 13.4, 13.5_
  
  - [x] 13.5 Write property test for network failure retry
    - **Property 24: Network Failures Trigger Exponential Backoff Retry**
    - **Validates: Requirements 13.1**
  
  - [x] 13.6 Write property test for exhausted retries queuing
    - **Property 25: Exhausted Retries Queue Operation**
    - **Validates: Requirements 13.3**
  
  - [x] 13.7 Write property test for server error logging
    - **Property 26: Server Errors Are Logged With Context**
    - **Validates: Requirements 13.4**

- [x] 14. Implement account deletion functionality
  - [x] 14.1 Add deleteAccount method to AuthManager
    - Delete user profile from Supabase
    - Trigger deletion of all user data via SyncEngine
    - Clear all local data including Room database
    - Revoke all auth tokens
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5_
  
  - [x] 14.2 Add user data deletion to SyncEngine
    - Delete all WatchlistItem records for user from Supabase
    - Delete all JournalEntry records for user from Supabase
    - _Requirements: 14.2, 14.3_
  
  - [x] 14.3 Add error handling for failed deletion
    - Return error message on deletion failure
    - Preserve local data if deletion fails
    - _Requirements: 14.6_
  
  - [x] 14.4 Write property tests for account deletion
    - **Property 27: Account Deletion Removes All User Data**
    - **Property 28: Account Deletion Clears Local Data**
    - **Property 29: Failed Deletion Preserves Data**
    - **Validates: Requirements 14.1, 14.2, 14.3, 14.4, 14.5, 14.6**

- [x] 15. Checkpoint - Ensure error handling and edge cases work
  - Ensure all tests pass, ask the user if questions arise.

- [x] 16. Implement sync status visibility
  - [x] 16.1 Create SyncStatus data model and flow
    - Define SyncStatus with state, pending count, last sync timestamp, error
    - Expose sync status as Flow from SyncEngine
    - Update status on sync state changes
    - _Requirements: 10.5, 15.1_
  
  - [x] 16.2 Add pending operations count tracking
    - Count operations in sync queue
    - Expose as Flow from SyncEngine
    - Update on queue changes
    - _Requirements: 15.2_
  
  - [x] 16.3 Add last sync timestamp tracking
    - Store timestamp in SyncMetadata after successful sync
    - Expose as Flow from SyncEngine
    - _Requirements: 15.3_
  
  - [x] 16.4 Add sync error tracking
    - Store error details in SyncStatus
    - Provide method to retrieve detailed error logs
    - _Requirements: 15.4, 15.5_
  
  - [x] 16.5 Write property tests for sync status visibility
    - **Property 18: Sync Status Reflects Current State**
    - **Property 30: Sync Progress Shows Pending Count**
    - **Property 31: Successful Sync Updates Timestamp**
    - **Property 32: Sync Errors Display Indicator**
    - **Validates: Requirements 10.5, 15.1, 15.2, 15.3, 15.4**

- [x] 17. Implement offline-first data access guarantees
  - [x] 17.1 Ensure Room database serves as source of truth
    - Configure repositories to query Room first
    - Never block UI on network operations
    - _Requirements: 10.1_
  
  - [x] 17.2 Verify full offline read/write access
    - Test all CRUD operations work offline
    - Ensure no network dependency for local operations
    - _Requirements: 10.2_
  
  - [x] 17.3 Implement queued change processing on connectivity resume
    - Process sync queue within 10 seconds of coming online
    - _Requirements: 10.4_
  
  - [x] 17.4 Write property test for offline database access
    - **Property 17: Offline Database Access Remains Functional**
    - **Validates: Requirements 10.2**

- [x] 18. Add timestamp management for sync operations
  - [x] 18.1 Ensure creation timestamps are recorded
    - Set createdAt on WatchlistItem insert
    - Set createdAt on JournalEntry insert
    - Use UTC timezone
    - _Requirements: 12.1, 12.3_
  
  - [x] 18.2 Ensure modification timestamps are updated
    - Update modifiedAt on WatchlistItem update
    - Update modifiedAt on JournalEntry update
    - Use UTC timezone
    - _Requirements: 12.2, 12.4_
  
  - [x] 18.3 Use timestamps for conflict resolution
    - Compare modifiedAt timestamps in conflict detection
    - _Requirements: 12.5_
  
  - [x] 18.4 Write property tests for timestamp management
    - **Property 21: Creation Timestamps Are Recorded**
    - **Property 22: Modification Timestamps Are Updated**
    - **Validates: Requirements 12.1, 12.2, 12.3, 12.4**

- [x] 19. Create UI integration points for authentication
  - [x] 19.1 Update LoginScreen to use AuthManager
    - Call AuthManager.login() on form submission
    - Handle success and error states
    - Navigate to home on successful login
    - _Requirements: 2.1, 2.4_
  
  - [x] 19.2 Update RegisterScreen to use AuthManager
    - Call AuthManager.register() on form submission
    - Validate password length client-side
    - Handle success and error states
    - _Requirements: 1.1, 1.4, 1.5_
  
  - [x] 19.3 Add session restoration to app startup
    - Call AuthManager.restoreSession() in MainActivity onCreate
    - Navigate to home if session restored successfully
    - _Requirements: 3.1, 3.2_
  
  - [x] 19.4 Add logout functionality to settings/profile screen
    - Call AuthManager.logout() on logout button click
    - Navigate to login screen after logout
    - _Requirements: 3.5_

- [x] 20. Create UI components for sync status display
  - [x] 20.1 Create SyncStatusIndicator composable
    - Display online/offline/syncing/error states
    - Show pending operations count when syncing
    - Show last sync timestamp when idle
    - _Requirements: 10.5, 15.1, 15.2, 15.3_
  
  - [x] 20.2 Add sync status to main screens
    - Add SyncStatusIndicator to Watchlist screen
    - Add SyncStatusIndicator to Journal screen
    - _Requirements: 10.5, 15.1_
  
  - [x] 20.3 Create sync error details dialog
    - Show detailed error information on indicator tap
    - Display error logs and pending operations
    - _Requirements: 15.4, 15.5_

- [x] 21. Create UI for profile management
  - [x] 21.1 Create ProfileScreen composable
    - Display user email and display name
    - Add edit functionality for display name
    - Show account creation timestamp
    - _Requirements: 4.1, 4.5_
  
  - [x] 21.2 Implement profile update functionality
    - Call AuthManager.updateProfile() on save
    - Handle success and error states
    - Update UI with new profile data
    - _Requirements: 4.2, 4.3_
  
  - [x] 21.3 Add account deletion functionality
    - Add delete account button with confirmation dialog
    - Call AuthManager.deleteAccount() on confirmation
    - Navigate to login screen after deletion
    - _Requirements: 14.1, 14.4, 14.5_

- [x] 22. Final checkpoint - End-to-end integration testing
  - Ensure all tests pass, ask the user if questions arise.

- [x] 23. Wire all components together and verify integration
  - [x] 23.1 Verify dependency injection graph is complete
    - Ensure all components are properly provided
    - Test app builds and runs without DI errors
    - _Requirements: 9.1_
  
  - [x] 23.2 Test complete authentication flow
    - Register → Login → Session restore → Logout
    - Verify tokens stored and cleared correctly
    - _Requirements: 1.1, 2.1, 3.1, 3.5_
  
  - [x] 23.3 Test complete sync flow
    - Local change → Upload → Remote change → Download
    - Verify conflict resolution works
    - Test offline queuing and online processing
    - _Requirements: 5.1, 5.4, 6.1, 6.4, 7.1, 10.3_
  
  - [x] 23.4 Test realtime updates
    - Make change on one device, verify update on another
    - Test subscription lifecycle (online/offline transitions)
    - _Requirements: 8.1, 8.3, 8.5, 8.6_
  
  - [x] 23.5 Test initial migration
    - Create local data, login for first time, verify upload
    - Verify migration flag prevents duplicate uploads
    - _Requirements: 11.1, 11.2, 11.3_
  
  - [x] 23.6 Test error scenarios
    - Network failures, authentication errors, server errors
    - Verify retry logic and error messages
    - _Requirements: 13.1, 13.2, 13.4_
  
  - [x] 23.7 Test account deletion
    - Delete account, verify all data removed
    - Verify local data cleared
    - _Requirements: 14.1, 14.2, 14.3, 14.4_

## Notes

- Tasks marked with `*` are optional property-based and unit tests that can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation at key milestones
- Property tests validate universal correctness properties across many generated inputs
- Unit tests validate specific examples, error cases, and edge conditions
- The implementation follows offline-first architecture with Room as source of truth
- All sync operations happen asynchronously without blocking the UI
- Conflict resolution uses last-write-wins strategy based on modification timestamps
