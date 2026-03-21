# Supabase Client Implementation

## Overview

This package contains the SupabaseClient interface and implementation for interacting with Supabase backend services.

## Components

### SupabaseClient Interface

Defines the contract for all Supabase operations:

- **Authentication**: Sign up, sign in, sign out, session management
- **Watchlist Operations**: Fetch, upsert, and delete watchlist items
- **Journal Operations**: Fetch, upsert, and delete journal entries
- **Profile Operations**: Fetch, update, and delete user profiles
- **Realtime Subscriptions**: Subscribe to data changes (placeholder for task 11)

### SupabaseClientImpl

Implementation that wraps the Supabase Kotlin SDK:

- Initializes Supabase client with Auth, Postgrest, and Realtime modules
- Validates API keys at initialization (throws `SupabaseConfigurationException` if missing)
- Provides error handling with Result types for all operations
- Uses Data Transfer Objects (DTOs) for serialization between app models and Supabase tables

### Error Handling

**SupabaseConfigurationException**: Thrown when SUPABASE_URL or SUPABASE_ANON_KEY are missing from BuildConfig. This satisfies Requirement 9.5:

> "If API keys are missing at runtime, then the Supabase_Client SHALL throw a configuration error with a descriptive message"

All operations return `Result<T>` types to handle success and failure cases gracefully.

## Configuration

API keys are loaded from `local.properties`:

```properties
supabase.url=https://your-project.supabase.co
supabase.anon.key=your-anon-key
```

These are injected into BuildConfig at build time and validated during SupabaseClient initialization.

## Usage

The SupabaseClient is designed to be injected via Hilt (dependency injection setup in task 1.4):

```kotlin
@Inject
lateinit var supabaseClient: SupabaseClient

// Example: Sign in
val result = supabaseClient.signIn(email, password)
result.onSuccess { session ->
    // Handle successful sign in
}.onFailure { error ->
    // Handle error
}
```

## Implementation Notes

1. **Realtime subscriptions** (task 11): Currently implemented as placeholders that establish channels. Full realtime event handling will be completed in task 11.1-11.4.

2. **Data Transfer Objects**: Private DTOs handle serialization between app models and Supabase table schemas. This keeps the public API clean while supporting database field naming conventions (snake_case).

3. **Coroutine Scope**: Uses a SupervisorJob with IO dispatcher for realtime subscriptions to prevent cancellation propagation.

## Testing

Basic unit tests are provided in `SupabaseClientTest.kt` to verify:
- Exception handling for missing API keys
- ChangeType enum completeness

Full integration tests will be added in subsequent tasks as the authentication and sync components are implemented.

## Requirements Satisfied

- **Requirement 9.5**: Error handling for missing API keys ✓
- **Requirement 9.1**: Supabase SDK integration ✓
- **Requirement 9.3**: Environment-specific API keys via BuildConfig ✓

## Next Steps

- Task 1.4: Set up dependency injection module
- Task 4.x: Implement AuthManager using SupabaseClient
- Task 8.x: Implement SyncEngine using SupabaseClient
- Task 11.x: Complete realtime subscription implementation
