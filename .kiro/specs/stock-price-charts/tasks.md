# Implementation Plan: Stock Price Charts

## Overview

This implementation plan breaks down the Stock Price Charts feature into discrete coding tasks. The feature integrates the Vico charting library into the existing StocKamp Android application, following the MVVM architecture pattern. Implementation proceeds from data layer to domain layer to presentation layer, with incremental validation through property-based and unit tests.

## Tasks

- [x] 1. Set up project dependencies and data models
  - Add Vico charting library dependency to build.gradle
  - Add Kotest property testing library dependency
  - Create domain models: PriceDataPoint, Timeframe, ChartType, TechnicalIndicator enums
  - Create ChartUiState sealed class hierarchy
  - _Requirements: 7.4, 9.1_

- [x] 2. Implement database layer for chart data caching
  - [x] 2.1 Create ChartDataEntity Room entity with composite primary key
    - Define entity with symbol, timeframe, dataJson, cacheTimestamp, dataSize fields
    - Set up composite primary key on (symbol, timeframe)
    - _Requirements: 6.5, 7.3_
  
  - [x] 2.2 Create ChartDataDao with cache operations
    - Implement getChartData, insertChartData, deleteChartData methods
    - Implement getTotalCacheSize and deleteOldestEntry for LRU eviction
    - _Requirements: 6.1, 6.6_
  
  - [ ]* 2.3 Write property test for cache key isolation
    - **Property 12: Cache Key Isolation**
    - **Validates: Requirements 6.5**
  
  - [ ]* 2.4 Write unit tests for DAO operations
    - Test insert and retrieve operations
    - Test cache size calculation
    - Test oldest entry deletion
    - _Requirements: 6.1, 6.6_

- [x] 3. Implement data parsing and validation
  - [x] 3.1 Create PriceDataParser interface and implementation
    - Implement parse method to convert JSON to List<PriceDataPoint>
    - Implement validate method with OHLC relationship checks
    - Return descriptive errors for validation failures
    - _Requirements: 9.1, 9.2, 9.3_
  
  - [ ]* 3.2 Write property test for OHLC relationship validation
    - **Property 15: OHLC Relationship Validation**
    - **Validates: Requirements 9.2**
  
  - [ ]* 3.3 Write property test for JSON parsing validity
    - **Property 14: JSON Parsing Validity**
    - **Validates: Requirements 9.1**
  
  - [ ]* 3.4 Write property test for price data round-trip
    - **Property 19: Price Data Round-Trip**
    - **Validates: Requirements 9.6**
  
  - [ ]* 3.5 Write unit tests for parser edge cases
    - Test malformed JSON handling
    - Test missing required fields
    - Test invalid OHLC relationships
    - _Requirements: 9.2, 9.3_

- [x] 4. Implement data formatting utilities
  - [x] 4.1 Create PriceDataFormatter interface and implementation
    - Implement formatPrice with configurable precision
    - Implement formatTimestamp with timeframe-specific formatting
    - Implement formatVolume with K/M/B suffixes
    - _Requirements: 9.4, 9.5_
  
  - [ ]* 4.2 Write property test for price formatting precision
    - **Property 17: Price Formatting Precision**
    - **Validates: Requirements 9.4**
  
  - [ ]* 4.3 Write property test for timestamp formatting by timeframe
    - **Property 18: Timestamp Formatting by Timeframe**
    - **Validates: Requirements 9.5**
  
  - [ ]* 4.4 Write unit tests for formatter edge cases
    - Test very large volume numbers
    - Test boundary timestamp values
    - Test zero and negative values
    - _Requirements: 9.4, 9.5_

- [x] 5. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement technical indicator calculations
  - [x] 6.1 Create TechnicalIndicatorCalculator interface and implementation
    - Implement calculateMA method for moving averages
    - Implement calculateSMA helper method
    - Handle insufficient data by returning null values
    - _Requirements: 5.1, 5.2, 5.4_
  
  - [ ]* 6.2 Write property test for indicator data sufficiency
    - **Property 9: Indicator Data Sufficiency**
    - **Validates: Requirements 5.4**
  
  - [ ]* 6.3 Write unit tests for indicator calculations
    - Test MA20 and MA50 calculations with known data
    - Test behavior with insufficient data points
    - Test edge case of empty data list
    - _Requirements: 5.1, 5.2, 5.4_

- [x] 7. Implement remote and local data sources
  - [x] 7.1 Create RemoteDataSource interface and implementation
    - Wrap existing API client for chart data endpoints
    - Implement fetchChartData with error handling
    - Handle network timeouts and HTTP error codes
    - _Requirements: 6.4_
  
  - [x] 7.2 Create LocalDataSource implementation
    - Wrap ChartDataDao with serialization logic
    - Implement cache freshness checking (5-minute TTL)
    - Implement cache size monitoring and LRU eviction
    - _Requirements: 6.1, 6.2, 6.3, 6.6_
  
  - [ ]* 7.3 Write property test for cache LRU eviction
    - **Property 13: Cache LRU Eviction**
    - **Validates: Requirements 6.6**
  
  - [ ]* 7.4 Write unit tests for data source error handling
    - Test network timeout scenarios
    - Test HTTP error code handling
    - Test cache corruption recovery
    - _Requirements: 6.4_

- [x] 8. Implement PriceDataRepository with cache-first strategy
  - [x] 8.1 Create PriceDataRepository interface and implementation
    - Implement getChartData with cache-first logic
    - Check cache freshness based on timeframe-specific TTL
    - Coordinate between local and remote data sources
    - Implement clearCache method
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 7.2_
  
  - [ ]* 8.2 Write property test for cache-first data strategy
    - **Property 11: Cache-First Data Strategy**
    - **Validates: Requirements 6.1, 6.2, 6.3, 6.4**
  
  - [ ]* 8.3 Write unit tests for repository coordination
    - Test cache hit scenario
    - Test cache miss scenario
    - Test stale cache scenario
    - Test network failure with stale cache fallback
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [x] 9. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Implement ChartViewModel with state management
  - [x] 10.1 Create ChartViewModel with StateFlow
    - Set up Hilt injection with repository and formatter dependencies
    - Implement chartState StateFlow with Loading/Success/Error states
    - Implement loadChartData method with coroutine handling
    - Implement updateTimeframe, toggleChartType, toggleIndicator methods
    - Calculate moving averages using TechnicalIndicatorCalculator
    - _Requirements: 2.2, 5.5, 7.1, 7.2, 7.6_
  
  - [ ]* 10.2 Write property test for indicator visibility toggle
    - **Property 10: Indicator Visibility Toggle**
    - **Validates: Requirements 5.5**
  
  - [ ]* 10.3 Write unit tests for ViewModel state transitions
    - Test loading state on initial load
    - Test success state with valid data
    - Test error state on repository failure
    - Test state updates on timeframe changes
    - _Requirements: 2.2, 7.6_

- [x] 11. Implement ChartComponent composable structure
  - [x] 11.1 Create ChartComponent composable with layout structure
    - Set up composable function signature with state and callbacks
    - Create layout with TimeframeSelector, ChartTypeToggle, and chart display area
    - Implement loading and error state UI
    - Integrate into existing StockDetailScreen
    - _Requirements: 1.1, 1.6, 1.7, 7.4, 7.5_
  
  - [x] 11.2 Implement TimeframeSelector composable
    - Display all 6 timeframe options (1D, 1W, 1M, 3M, 6M, 1Y)
    - Highlight currently selected timeframe
    - Trigger callback on selection
    - _Requirements: 2.1, 2.4_
  
  - [ ]* 11.3 Write property test for selected timeframe visual indication
    - **Property 3: Selected Timeframe Visual Indication**
    - **Validates: Requirements 2.4**
  
  - [ ]* 11.4 Write property test for timeframe data correspondence
    - **Property 2: Timeframe Data Correspondence**
    - **Validates: Requirements 2.2**
  
  - [ ]* 11.5 Write UI tests for ChartComponent states
    - Test loading indicator displays
    - Test error message with retry button
    - Test timeframe selector interaction
    - _Requirements: 1.6, 1.7, 2.1_

- [x] 12. Implement Vico chart rendering for price data
  - [x] 12.1 Integrate Vico Chart composable for candlestick display
    - Configure Vico Chart with candlestick chart type
    - Map PriceDataPoint data to Vico data format
    - Apply color scheme for up/down candles
    - _Requirements: 1.2, 1.3_
  
  - [x] 12.2 Integrate Vico Chart composable for line chart display
    - Configure Vico Chart with line chart type
    - Map closing prices to Vico line data format
    - Apply line styling and colors
    - _Requirements: 1.2, 1.4_
  
  - [x] 12.3 Implement chart type toggle functionality
    - Add toggle UI control
    - Switch between candlestick and line chart rendering
    - Implement cross-fade animation (200ms)
    - _Requirements: 1.2, 8.2_
  
  - [ ]* 12.4 Write property test for chart data completeness
    - **Property 1: Chart Data Completeness**
    - **Validates: Requirements 1.3, 1.4**
  
  - [ ]* 12.5 Write unit tests for chart rendering
    - Test candlestick chart with sample data
    - Test line chart with sample data
    - Test chart type toggle behavior
    - _Requirements: 1.2, 1.3, 1.4_

- [x] 13. Implement volume chart display
  - [x] 13.1 Create VolumeChart composable using Vico
    - Configure Vico bar chart for volume data
    - Position below price chart
    - Implement proportional scaling to max volume
    - _Requirements: 1.5, 10.1, 10.4_
  
  - [x] 13.2 Implement volume bar color coding
    - Color bars green for positive price change (close > open)
    - Color bars red for negative price change (close < open)
    - Use default color for neutral (close == open)
    - _Requirements: 10.2, 10.3_
  
  - [ ]* 13.3 Write property test for volume bar rendering
    - **Property 20: Volume Bar Rendering**
    - **Validates: Requirements 10.1**
  
  - [ ]* 13.4 Write property test for volume bar color coding
    - **Property 21: Volume Bar Color Coding**
    - **Validates: Requirements 10.2, 10.3**
  
  - [ ]* 13.5 Write property test for volume bar scaling
    - **Property 22: Volume Bar Scaling**
    - **Validates: Requirements 10.4**
  
  - [ ]* 13.6 Write unit tests for volume chart
    - Test volume chart with sample data
    - Test color coding logic
    - Test scaling with various volume ranges
    - _Requirements: 10.1, 10.2, 10.3, 10.4_

- [x] 14. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 15. Implement moving average indicator overlays
  - [x] 15.1 Add MA20 and MA50 indicator lines to chart
    - Calculate indicator values using TechnicalIndicatorCalculator
    - Render as overlay lines on Vico chart
    - Use distinct colors from price chart
    - Hide indicators when insufficient data
    - _Requirements: 5.1, 5.2, 5.3, 5.4_
  
  - [x] 15.2 Implement indicator visibility toggle controls
    - Add toggle UI for MA20 and MA50
    - Show/hide indicators with fade animation (150ms)
    - Persist visibility state in ViewModel
    - _Requirements: 5.5, 8.5_
  
  - [ ]* 15.3 Write property test for indicator color distinction
    - **Property 8: Indicator Color Distinction**
    - **Validates: Requirements 5.3**
  
  - [ ]* 15.4 Write unit tests for indicator display
    - Test MA20 and MA50 rendering with sufficient data
    - Test indicator hiding with insufficient data
    - Test visibility toggle behavior
    - _Requirements: 5.1, 5.2, 5.4, 5.5_

- [x] 16. Implement zoom and pan gesture handling
  - [x] 16.1 Add pinch-to-zoom gesture detection
    - Implement zoom gesture handling using Compose gesture APIs
    - Update chart zoom level state
    - Constrain zoom between 1x and 10x
    - _Requirements: 3.1, 3.3_
  
  - [x] 16.2 Add drag-to-pan gesture detection
    - Implement pan gesture handling using Compose gesture APIs
    - Update chart pan offset state
    - Constrain panning to data boundaries
    - _Requirements: 3.2, 3.4_
  
  - [x] 16.3 Implement double-tap zoom reset
    - Detect double-tap gesture
    - Reset zoom to 1x with animation
    - _Requirements: 3.5_
  
  - [ ]* 16.4 Write property test for zoom level invariant
    - **Property 4: Zoom Level Invariant**
    - **Validates: Requirements 3.1, 3.3, 3.5**
  
  - [ ]* 16.5 Write property test for pan boundary invariant
    - **Property 5: Pan Boundary Invariant**
    - **Validates: Requirements 3.2, 3.4**
  
  - [ ]* 16.6 Write UI tests for gesture interactions
    - Test pinch zoom behavior
    - Test drag pan behavior
    - Test double-tap reset
    - Test zoom and pan constraints
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 17. Implement crosshair interaction
  - [x] 17.1 Add touch detection for crosshair activation
    - Detect touch down event on chart
    - Show crosshair at touch location
    - Update crosshair position on touch move
    - Hide crosshair 1000ms after touch up
    - _Requirements: 4.1, 4.2, 4.4_
  
  - [x] 17.2 Implement crosshair snapping to nearest data point
    - Calculate distance to nearest data point
    - Snap to data point if within 20dp
    - Otherwise position at exact touch location
    - _Requirements: 4.5_
  
  - [x] 17.3 Display price and OHLC information for crosshair point
    - Show price, timestamp, OHLC values in overlay
    - Format values using PriceDataFormatter
    - Display volume value for selected point
    - _Requirements: 4.3, 10.5_
  
  - [ ]* 17.4 Write property test for crosshair data display
    - **Property 6: Crosshair Data Display**
    - **Validates: Requirements 4.1, 4.2, 4.3**
  
  - [ ]* 17.5 Write property test for crosshair snapping behavior
    - **Property 7: Crosshair Snapping Behavior**
    - **Validates: Requirements 4.5**
  
  - [ ]* 17.6 Write property test for crosshair volume display
    - **Property 23: Crosshair Volume Display**
    - **Validates: Requirements 10.5**
  
  - [ ]* 17.7 Write UI tests for crosshair interaction
    - Test crosshair appears on touch
    - Test crosshair follows touch movement
    - Test crosshair disappears after delay
    - Test data display accuracy
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [x] 18. Implement chart animations
  - [x] 18.1 Add initial chart drawing animation
    - Animate chart appearance over 300ms on data load
    - Use Vico animation APIs
    - _Requirements: 8.1_
  
  - [x] 18.2 Add timeframe transition animation
    - Animate data transition over 250ms on timeframe change
    - Ensure smooth visual transition
    - _Requirements: 2.5, 8.3_
  
  - [x] 18.3 Optimize animation performance
    - Profile animation frame rate
    - Ensure 60fps during pan and zoom gestures
    - Optimize rendering if needed
    - _Requirements: 8.4_
  
  - [ ]* 18.4 Write performance tests for animations
    - Test frame rate during gestures
    - Test animation timing accuracy
    - _Requirements: 8.4_

- [x] 19. Final integration and wiring
  - [x] 19.1 Wire ChartViewModel into StockDetailScreen
    - Inject ChartViewModel using Hilt
    - Pass ViewModel state to ChartComponent
    - Connect user interactions to ViewModel methods
    - _Requirements: 7.1, 7.2, 7.5_
  
  - [x] 19.2 Configure Hilt dependency injection modules
    - Set up Hilt modules for repository, data sources, parsers, formatters
    - Ensure proper scoping and lifecycle management
    - _Requirements: 7.2_
  
  - [ ]* 19.3 Write integration tests for end-to-end flows
    - Test complete flow from screen load to chart display
    - Test timeframe switching end-to-end
    - Test chart type toggle end-to-end
    - Test error handling and retry flow
    - _Requirements: 1.1, 2.2, 7.5_

- [x] 20. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests validate universal correctness properties using Kotest with 100 iterations minimum
- Unit tests validate specific examples and edge cases
- Implementation uses Kotlin with Jetpack Compose and follows MVVM architecture
- Vico library is used for chart rendering
- All components use Hilt for dependency injection
