# Dependency Injection Module

This directory contains Hilt modules for dependency injection configuration.

## SupabaseModule

The `SupabaseModule` provides singleton instances for all Supabase-related components:

### Provided Dependencies

1. **SupabaseClient** - Wrapper around Supabase SDK for backend communication
   - Loads API keys from BuildConfig at runtime (Requirement 9.1)
   - Handles authentication, data operations, and real-time subscriptions

2. **AuthManager** - Manages user authentication and session management
   - Handles registration, login, logout
   - Session persistence and token refresh
   - User profile management

3. **SyncEngine** - Coordinates bidirectional data synchronization
   - Syncs data between Room database and Supabase
   - Handles conflict resolution
   - Manages real-time subscriptions

4. **NetworkMonitor** - Monitors network connectivity status
   - Provides connectivity state as Flow
   - Triggers sync operations when network becomes available

5. **EncryptedStorage** - Securely stores authentication tokens
   - Uses Android EncryptedSharedPreferences
   - Stores access tokens, refresh tokens, and migration flags

6. **SyncQueue** - Manages queued sync operations with retry logic
   - Persists operations in Room database
   - Implements exponential backoff retry strategy

### Circular Dependency Handling

The module uses `dagger.Lazy<SyncEngine>` in `AuthManager` to break the circular dependency between `AuthManager` and `SyncEngine`. This allows `AuthManager` to trigger sync operations after successful login without creating a dependency cycle.

### Implementation Status

**Task 1.4 (Current)**: Module structure and placeholder implementations created
- All interfaces defined
- Placeholder implementations provided
- Dependency injection graph configured

**Future Tasks**: Full implementations will be completed in subsequent tasks:
- Task 2: EncryptedStorage implementation
- Task 4: AuthManager implementation
- Task 6: NetworkMonitor implementation
- Task 7: SyncQueue implementation
- Task 8: SyncEngine implementation

### Usage

Components are automatically injected by Hilt. To use in a ViewModel or other component:

```kotlin
@HiltViewModel
class MyViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val syncEngine: SyncEngine
) : ViewModel() {
    // Use injected dependencies
}
```

### Testing

Unit tests for the module are located in `app/src/test/java/com/stockamp/di/SupabaseModuleTest.kt`.
