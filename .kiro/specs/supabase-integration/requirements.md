# Requirements Document

## Introduction

This document specifies requirements for integrating Supabase backend services into the StocKamp Android application. The integration enables user authentication, cloud synchronization of Watchlist and Trading Journal data, and user profile management while maintaining an offline-first architecture with Room Database as the source of truth.

## Glossary

- **StocKamp_App**: The Android stock market tracking application
- **Supabase_Client**: The Supabase Kotlin SDK client managing backend communication
- **Auth_Manager**: Component responsible for user authentication and session management
- **Sync_Engine**: Component responsible for bidirectional data synchronization between Room and Supabase
- **Room_Database**: Local SQLite database serving as the source of truth for offline-first architecture
- **Watchlist_Item**: A stock symbol tracked by the user with associated metadata
- **Journal_Entry**: A trading journal record containing trade details and notes
- **User_Profile**: User account information including email and preferences
- **Sync_Conflict**: Situation where local and remote data versions differ
- **Auth_Token**: JWT token used for authenticated API requests
- **Session**: Active authenticated user connection with valid tokens

## Requirements

### Requirement 1: User Registration

**User Story:** As a new user, I want to create an account with email and password, so that I can access cloud features and sync my data across devices.

#### Acceptance Criteria

1. WHEN a user provides valid email and password, THE Auth_Manager SHALL create a new account in Supabase
2. WHEN account creation succeeds, THE Auth_Manager SHALL store the Auth_Token locally
3. WHEN account creation succeeds, THE Auth_Manager SHALL create a User_Profile record in Supabase
4. IF the email is already registered, THEN THE Auth_Manager SHALL return an error message indicating duplicate account
5. IF the password is less than 8 characters, THEN THE Auth_Manager SHALL return an error message indicating insufficient password strength
6. IF network is unavailable during registration, THEN THE Auth_Manager SHALL return an error message indicating connectivity failure

### Requirement 2: User Login

**User Story:** As a returning user, I want to log in with my email and password, so that I can access my synced data.

#### Acceptance Criteria

1. WHEN a user provides valid credentials, THE Auth_Manager SHALL authenticate with Supabase and retrieve an Auth_Token
2. WHEN authentication succeeds, THE Auth_Manager SHALL store the Auth_Token securely in encrypted storage
3. WHEN authentication succeeds, THE Auth_Manager SHALL trigger the Sync_Engine to synchronize data
4. IF credentials are invalid, THEN THE Auth_Manager SHALL return an error message indicating authentication failure
5. IF network is unavailable during login, THEN THE Auth_Manager SHALL return an error message indicating connectivity failure

### Requirement 3: Session Management

**User Story:** As a logged-in user, I want my session to persist across app restarts, so that I don't have to log in repeatedly.

#### Acceptance Criteria

1. WHEN the StocKamp_App starts, THE Auth_Manager SHALL check for a valid Auth_Token in encrypted storage
2. WHEN a valid Auth_Token exists, THE Auth_Manager SHALL restore the Session automatically
3. WHEN the Auth_Token expires, THE Auth_Manager SHALL refresh it using the refresh token
4. IF token refresh fails, THEN THE Auth_Manager SHALL clear the Session and require re-authentication
5. WHEN a user logs out, THE Auth_Manager SHALL revoke the Auth_Token and clear local session data

### Requirement 4: User Profile Management

**User Story:** As a user, I want to view and update my profile information, so that I can manage my account details.

#### Acceptance Criteria

1. WHEN a user requests profile data, THE Auth_Manager SHALL retrieve the User_Profile from Supabase
2. WHEN a user updates profile fields, THE Auth_Manager SHALL persist changes to Supabase
3. WHEN profile update succeeds, THE Auth_Manager SHALL update the local cached User_Profile
4. IF network is unavailable during profile update, THEN THE Auth_Manager SHALL queue the update for later synchronization
5. THE User_Profile SHALL include email, display name, and account creation timestamp

### Requirement 5: Watchlist Cloud Sync

**User Story:** As a user, I want my Watchlist synced to the cloud, so that I can access it from multiple devices.

#### Acceptance Criteria

1. WHEN a user adds a Watchlist_Item locally, THE Sync_Engine SHALL upload it to Supabase within 5 seconds when online
2. WHEN a user deletes a Watchlist_Item locally, THE Sync_Engine SHALL delete it from Supabase within 5 seconds when online
3. WHEN a user modifies a Watchlist_Item locally, THE Sync_Engine SHALL update it in Supabase within 5 seconds when online
4. WHEN the StocKamp_App comes online, THE Sync_Engine SHALL download all Watchlist_Item changes from Supabase
5. WHEN remote Watchlist_Item changes are detected, THE Sync_Engine SHALL update the Room_Database
6. WHILE offline, THE Sync_Engine SHALL queue all Watchlist_Item changes for synchronization when connectivity resumes

### Requirement 6: Trading Journal Cloud Sync

**User Story:** As a user, I want my Trading Journal synced to the cloud, so that I can preserve my trading history and access it from multiple devices.

#### Acceptance Criteria

1. WHEN a user creates a Journal_Entry locally, THE Sync_Engine SHALL upload it to Supabase within 5 seconds when online
2. WHEN a user deletes a Journal_Entry locally, THE Sync_Engine SHALL delete it from Supabase within 5 seconds when online
3. WHEN a user modifies a Journal_Entry locally, THE Sync_Engine SHALL update it in Supabase within 5 seconds when online
4. WHEN the StocKamp_App comes online, THE Sync_Engine SHALL download all Journal_Entry changes from Supabase
5. WHEN remote Journal_Entry changes are detected, THE Sync_Engine SHALL update the Room_Database
6. WHILE offline, THE Sync_Engine SHALL queue all Journal_Entry changes for synchronization when connectivity resumes

### Requirement 7: Conflict Resolution

**User Story:** As a user who edits data on multiple devices, I want conflicts resolved automatically, so that I don't lose data when syncing.

#### Acceptance Criteria

1. WHEN a Sync_Conflict is detected for a Watchlist_Item, THE Sync_Engine SHALL apply last-write-wins strategy based on modification timestamp
2. WHEN a Sync_Conflict is detected for a Journal_Entry, THE Sync_Engine SHALL apply last-write-wins strategy based on modification timestamp
3. WHEN applying conflict resolution, THE Sync_Engine SHALL preserve the most recent modification timestamp
4. THE Sync_Engine SHALL log all Sync_Conflict resolutions for debugging purposes
5. WHEN a record is deleted locally but modified remotely, THE Sync_Engine SHALL preserve the deletion

### Requirement 8: Real-time Sync

**User Story:** As a user with multiple devices, I want changes to sync in real-time, so that all my devices stay up to date.

#### Acceptance Criteria

1. WHEN the StocKamp_App is online and authenticated, THE Sync_Engine SHALL subscribe to Supabase realtime channels for Watchlist_Item changes
2. WHEN the StocKamp_App is online and authenticated, THE Sync_Engine SHALL subscribe to Supabase realtime channels for Journal_Entry changes
3. WHEN a realtime update is received for a Watchlist_Item, THE Sync_Engine SHALL update the Room_Database within 2 seconds
4. WHEN a realtime update is received for a Journal_Entry, THE Sync_Engine SHALL update the Room_Database within 2 seconds
5. WHEN the StocKamp_App goes offline, THE Sync_Engine SHALL unsubscribe from realtime channels
6. WHEN the StocKamp_App comes back online, THE Sync_Engine SHALL resubscribe to realtime channels

### Requirement 9: Secure API Key Management

**User Story:** As a developer, I want API keys stored securely, so that the application remains secure and keys are not exposed.

#### Acceptance Criteria

1. THE Supabase_Client SHALL load API keys from BuildConfig at runtime
2. THE StocKamp_App SHALL exclude API keys from version control using gitignore
3. THE Supabase_Client SHALL use environment-specific API keys for debug and release builds
4. THE StocKamp_App SHALL store the Supabase URL and anonymous key in local.properties during development
5. IF API keys are missing at runtime, THEN THE Supabase_Client SHALL throw a configuration error with a descriptive message

### Requirement 10: Offline-First Data Access

**User Story:** As a user, I want to access my Watchlist and Trading Journal offline, so that I can use the app without internet connectivity.

#### Acceptance Criteria

1. THE Room_Database SHALL serve as the source of truth for all Watchlist_Item and Journal_Entry queries
2. WHEN the StocKamp_App is offline, THE StocKamp_App SHALL provide full read and write access to the Room_Database
3. WHEN the StocKamp_App is offline, THE Sync_Engine SHALL queue all changes for synchronization when connectivity resumes
4. WHEN connectivity resumes, THE Sync_Engine SHALL process the queued changes within 10 seconds
5. THE StocKamp_App SHALL display sync status indicators showing online, offline, or syncing states

### Requirement 11: Initial Data Migration

**User Story:** As an existing user, I want my local data migrated to the cloud on first login, so that I don't lose my existing Watchlist and Trading Journal.

#### Acceptance Criteria

1. WHEN a user logs in for the first time, THE Sync_Engine SHALL upload all existing Watchlist_Item records from Room_Database to Supabase
2. WHEN a user logs in for the first time, THE Sync_Engine SHALL upload all existing Journal_Entry records from Room_Database to Supabase
3. WHEN initial migration completes, THE Sync_Engine SHALL mark the migration as complete to prevent duplicate uploads
4. IF migration fails partially, THEN THE Sync_Engine SHALL retry failed records on next sync attempt
5. WHILE migration is in progress, THE StocKamp_App SHALL display a progress indicator to the user

### Requirement 12: Data Synchronization Timestamps

**User Story:** As a developer, I want accurate timestamps on all synced data, so that conflict resolution works correctly.

#### Acceptance Criteria

1. WHEN a Watchlist_Item is created, THE Room_Database SHALL record a creation timestamp in UTC
2. WHEN a Watchlist_Item is modified, THE Room_Database SHALL update the modification timestamp in UTC
3. WHEN a Journal_Entry is created, THE Room_Database SHALL record a creation timestamp in UTC
4. WHEN a Journal_Entry is modified, THE Room_Database SHALL update the modification timestamp in UTC
5. THE Sync_Engine SHALL use modification timestamps for conflict resolution decisions
6. THE Sync_Engine SHALL preserve original creation timestamps during synchronization

### Requirement 13: Error Handling and Retry Logic

**User Story:** As a user, I want sync operations to retry automatically on failure, so that temporary network issues don't cause data loss.

#### Acceptance Criteria

1. WHEN a sync operation fails due to network error, THE Sync_Engine SHALL retry with exponential backoff up to 3 attempts
2. WHEN a sync operation fails due to authentication error, THE Sync_Engine SHALL attempt token refresh before retrying
3. IF all retry attempts fail, THEN THE Sync_Engine SHALL queue the operation for the next sync cycle
4. WHEN a sync operation fails due to server error, THE Sync_Engine SHALL log the error with full context for debugging
5. THE Sync_Engine SHALL display user-friendly error messages for sync failures without exposing technical details

### Requirement 14: User Data Deletion

**User Story:** As a user, I want to delete my account and all associated data, so that I can remove my information from the service.

#### Acceptance Criteria

1. WHEN a user requests account deletion, THE Auth_Manager SHALL delete the User_Profile from Supabase
2. WHEN account deletion is initiated, THE Sync_Engine SHALL delete all Watchlist_Item records associated with the user from Supabase
3. WHEN account deletion is initiated, THE Sync_Engine SHALL delete all Journal_Entry records associated with the user from Supabase
4. WHEN account deletion completes, THE Auth_Manager SHALL clear all local data including Room_Database records
5. WHEN account deletion completes, THE Auth_Manager SHALL revoke all Auth_Token instances
6. IF account deletion fails, THEN THE Auth_Manager SHALL return an error message and preserve local data

### Requirement 15: Sync Status Visibility

**User Story:** As a user, I want to see the sync status of my data, so that I know when my changes are safely backed up.

#### Acceptance Criteria

1. THE StocKamp_App SHALL display a sync status indicator showing online, offline, syncing, or error states
2. WHEN sync is in progress, THE StocKamp_App SHALL display the number of pending changes
3. WHEN sync completes successfully, THE StocKamp_App SHALL display a timestamp of the last successful sync
4. WHEN sync errors occur, THE StocKamp_App SHALL display an error indicator with option to view details
5. WHEN the user taps the sync status indicator, THE StocKamp_App SHALL display detailed sync information including pending operations and error logs
