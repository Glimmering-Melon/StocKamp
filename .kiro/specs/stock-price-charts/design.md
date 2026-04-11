# Design Document: Stock Price Charts

## Overview

The Stock Price Charts feature adds interactive charting capabilities to the StocKamp Android application, enabling users to visualize historical stock price data through candlestick and line charts. The feature integrates the Vico charting library with the existing MVVM architecture, providing gesture-based interactions, multiple timeframes, technical indicators, and local data caching.

The design extends the existing StockDetailScreen with a new Chart_Component that displays price and volume data. The Chart_ViewModel manages state and coordinates data loading through the Price_Data_Repository, which implements a cache-first strategy using Room Database. The feature supports zoom, pan, and crosshair interactions while maintaining 60fps performance.

Key technical decisions:
- Use Vico library for chart rendering (mature, Compose-native, supports required chart types)
- Implement cache-first data loading with 5-minute TTL to balance freshness and performance
- Store chart data separately per symbol-timeframe combination for efficient retrieval
- Use StateFlow for reactive UI updates consistent with existing app patterns

## Architecture

### Component Structure

```
┌─────────────────────────────────────────────────────────┐
│                  StockDetailScreen                       │
│  ┌───────────────────────────────────────────────────┐  │
│  │           ChartComponent (Composable)             │  │
│  │  ┌─────────────────┐  ┌────────────────────────┐ │  │
│  │  │ TimeframeSelector│  │   Chart Display Area   │ │  │
│  │  └─────────────────┘  │  - Candlestick/Line    │ │  │
│  │                        │  - Moving Averages     │ │  │
│  │  ┌─────────────────┐  │  - Crosshair Overlay   │ │  │
│  │  │ Chart Type Toggle│  │  - Volume Chart        │ │  │
│  │  └─────────────────┘  └────────────────────────┘ │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
              ┌───────────────────────┐
              │   ChartViewModel      │
              │  - chartState: Flow   │
              │  - loadChartData()    │
              │  - updateTimeframe()  │
              │  - toggleChartType()  │
              └───────────────────────┘
                          │
                          ▼
              ┌───────────────────────┐
              │ PriceDataRepository   │
              │  - getChartData()     │
              │  - cacheData()        │
              └───────────────────────┘
                    │            │
            ┌───────┘            └────────┐
            ▼                              ▼
    ┌──────────────┐              ┌──────────────┐
    │ RemoteDataSrc│              │ LocalDataSrc │
    │ (API Client) │              │ (Room DAO)   │
    └──────────────┘              └──────────────┘
```

### Data Flow

1. User navigates to StockDetailScreen → ChartViewModel initializes with default timeframe
2. ChartViewModel calls PriceDataRepository.getChartData(symbol, timeframe)
3. Repository checks LocalDataSource for cached data with valid TTL
4. If cache miss or stale, Repository fetches from RemoteDataSource
5. Fresh data is cached in LocalDataSource and returned to ViewModel
6. ViewModel updates chartState StateFlow with loaded data
7. ChartComponent observes chartState and renders chart using Vico
8. User interactions (zoom, pan, crosshair) update local component state
9. Timeframe/chart type changes trigger new data load cycle

### Threading Model

- UI rendering: Main thread (Compose)
- Data fetching: IO dispatcher (Coroutines)
- Database operations: IO dispatcher (Room)
- State updates: Main thread (StateFlow)
- Chart calculations (MA indicators): Default dispatcher

## Components and Interfaces

### ChartComponent

Jetpack Compose composable that renders the chart UI using Vico library.

```kotlin
@Composable
fun ChartComponent(
    chartState: ChartUiState,
    onTimeframeSelected: (Timeframe) -> Unit,
    onChartTypeToggled: (ChartType) -> Unit,
    modifier: Modifier = Modifier
)
```

Responsibilities:
- Render price chart (candlestick or line) using Vico Chart composable
- Display volume chart below price chart
- Handle gesture input (zoom, pan, touch for crosshair)
- Show timeframe selector and chart type toggle
- Display loading/error states
- Animate transitions between states

Internal state:
- Zoom level (1x-10x)
- Pan offset
- Crosshair position and visibility
- Animation progress

### ChartViewModel

ViewModel managing chart state and coordinating data operations.

```kotlin
class ChartViewModel @Inject constructor(
    private val repository: PriceDataRepository,
    private val priceDataFormatter: PriceDataFormatter
) : ViewModel() {
    
    val chartState: StateFlow<ChartUiState>
    
    fun loadChartData(symbol: String, timeframe: Timeframe)
    fun updateTimeframe(timeframe: Timeframe)
    fun toggleChartType(chartType: ChartType)
    fun toggleIndicator(indicator: TechnicalIndicator)
    fun retryLoad()
}
```

State management:
- Maintains current symbol, timeframe, chart type
- Exposes ChartUiState via StateFlow
- Handles loading, success, and error states
- Calculates moving averages from price data
- Formats data for display using PriceDataFormatter

### PriceDataRepository

Repository implementing cache-first data loading strategy.

```kotlin
interface PriceDataRepository {
    suspend fun getChartData(
        symbol: String,
        timeframe: Timeframe
    ): Result<List<PriceDataPoint>>
    
    suspend fun clearCache(symbol: String)
}

class PriceDataRepositoryImpl @Inject constructor(
    private val remoteDataSource: RemoteDataSource,
    private val localDataSource: LocalDataSource,
    private val priceDataParser: PriceDataParser
) : PriceDataRepository
```

Logic:
1. Check cache for symbol-timeframe combination
2. If cached and fresh (< 5 min), return cached data
3. If stale or missing, fetch from remote
4. Parse and validate remote data
5. Cache valid data
6. Return result

Cache eviction:
- LRU strategy when storage exceeds 50MB
- Remove oldest entries by timestamp

### LocalDataSource (Room DAO)

```kotlin
@Dao
interface ChartDataDao {
    @Query("SELECT * FROM chart_data WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun getChartData(symbol: String, timeframe: String): ChartDataEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChartData(data: ChartDataEntity)
    
    @Query("DELETE FROM chart_data WHERE symbol = :symbol")
    suspend fun deleteChartData(symbol: String)
    
    @Query("SELECT SUM(data_size) FROM chart_data")
    suspend fun getTotalCacheSize(): Long
    
    @Query("DELETE FROM chart_data WHERE cache_timestamp = (SELECT MIN(cache_timestamp) FROM chart_data)")
    suspend fun deleteOldestEntry()
}
```

### RemoteDataSource

```kotlin
interface RemoteDataSource {
    suspend fun fetchChartData(
        symbol: String,
        timeframe: Timeframe
    ): Result<ChartDataResponse>
}
```

Wraps existing API client, handles network errors, returns structured results.

### PriceDataParser

```kotlin
interface PriceDataParser {
    fun parse(json: String): Result<List<PriceDataPoint>>
    fun validate(dataPoint: PriceDataPoint): Boolean
}
```

Validation rules:
- High >= Low
- High >= Open
- High >= Close
- Low <= Open
- Low <= Close
- Volume >= 0
- Timestamp is valid

### PriceDataFormatter

```kotlin
interface PriceDataFormatter {
    fun formatPrice(price: Double, precision: Int = 2): String
    fun formatTimestamp(timestamp: Long, timeframe: Timeframe): String
    fun formatVolume(volume: Long): String
}
```

Formatting rules:
- Prices: 2 decimal places for stocks, 4 for crypto
- Timestamps: "HH:mm" for 1D, "MMM dd" for 1W-1M, "MMM yyyy" for 3M+
- Volume: K/M/B suffixes for readability

### TechnicalIndicatorCalculator

```kotlin
interface TechnicalIndicatorCalculator {
    fun calculateMA(data: List<PriceDataPoint>, period: Int): List<Double?>
    fun calculateSMA(values: List<Double>, period: Int): List<Double?>
}
```

Calculates moving averages for overlay display. Returns null for periods with insufficient data.

## Data Models

### Domain Models

```kotlin
data class PriceDataPoint(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

enum class Timeframe(val apiValue: String, val cacheDuration: Duration) {
    ONE_DAY("1D", Duration.ofMinutes(1)),
    ONE_WEEK("1W", Duration.ofMinutes(5)),
    ONE_MONTH("1M", Duration.ofMinutes(15)),
    THREE_MONTHS("3M", Duration.ofHours(1)),
    SIX_MONTHS("6M", Duration.ofHours(4)),
    ONE_YEAR("1Y", Duration.ofHours(24))
}

enum class ChartType {
    CANDLESTICK,
    LINE
}

enum class TechnicalIndicator {
    MA20,
    MA50
}

sealed class ChartUiState {
    object Loading : ChartUiState()
    data class Success(
        val priceData: List<PriceDataPoint>,
        val chartType: ChartType,
        val timeframe: Timeframe,
        val indicators: Map<TechnicalIndicator, List<Double?>>,
        val visibleIndicators: Set<TechnicalIndicator>
    ) : ChartUiState()
    data class Error(val message: String) : ChartUiState()
}
```

### Database Entities

```kotlin
@Entity(
    tableName = "chart_data",
    primaryKeys = ["symbol", "timeframe"]
)
data class ChartDataEntity(
    val symbol: String,
    val timeframe: String,
    val dataJson: String, // Serialized List<PriceDataPoint>
    val cacheTimestamp: Long,
    val dataSize: Int // Size in bytes for cache management
)
```

### API Response Models

```kotlin
data class ChartDataResponse(
    val symbol: String,
    val prices: List<PricePointResponse>
)

data class PricePointResponse(
    val t: Long, // timestamp
    val o: Double, // open
    val h: Double, // high
    val l: Double, // low
    val c: Double, // close
    val v: Long // volume
)
```

## Correctness Properties


*A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Chart Data Completeness

*For any* list of price data points, when rendered in either candlestick or line chart mode, all OHLC values (for candlestick) or all closing prices (for line) from the input data should be present in the rendered output.

**Validates: Requirements 1.3, 1.4**

### Property 2: Timeframe Data Correspondence

*For any* selected timeframe, the loaded price data should correspond to the time period specified by that timeframe (1D, 1W, 1M, 3M, 6M, or 1Y).

**Validates: Requirements 2.2**

### Property 3: Selected Timeframe Visual Indication

*For any* timeframe selection, the UI should visually indicate that timeframe as currently selected.

**Validates: Requirements 2.4**

### Property 4: Zoom Level Invariant

*For any* zoom operation (pinch gesture or double-tap), the resulting zoom level should always be constrained between 1x and 10x magnification inclusive.

**Validates: Requirements 3.1, 3.3, 3.5**

### Property 5: Pan Boundary Invariant

*For any* pan operation (drag gesture), the visible data range should never extend beyond the available data boundaries.

**Validates: Requirements 3.2, 3.4**

### Property 6: Crosshair Data Display

*For any* data point selected by crosshair touch, the displayed information should include the price, timestamp, and OHLC values for that specific point.

**Validates: Requirements 4.1, 4.2, 4.3**

### Property 7: Crosshair Snapping Behavior

*For any* touch location on the chart, if a data point exists within 20dp, the crosshair should snap to that nearest data point; otherwise, it should position at the exact touch location.

**Validates: Requirements 4.5**

### Property 8: Indicator Color Distinction

*For any* enabled moving average indicator, its display color should be distinct from the price chart colors (candlestick or line colors).

**Validates: Requirements 5.3**

### Property 9: Indicator Data Sufficiency

*For any* N-period moving average indicator, if the available data contains fewer than N points, the indicator should not be displayed.

**Validates: Requirements 5.4**

### Property 10: Indicator Visibility Toggle

*For any* technical indicator, toggling its visibility should result in the indicator being shown if previously hidden, or hidden if previously shown.

**Validates: Requirements 5.5**

### Property 11: Cache-First Data Strategy

*For any* chart data request with a given symbol and timeframe, if cached data exists and its age is less than 5 minutes, that cached data should be returned without a network call; if cached data is stale or missing, fresh data should be fetched from the network and cached.

**Validates: Requirements 6.1, 6.2, 6.3, 6.4**

### Property 12: Cache Key Isolation

*For any* two different combinations of (symbol, timeframe), their cached data should be stored separately such that retrieving data for one combination does not return data for the other.

**Validates: Requirements 6.5**

### Property 13: Cache LRU Eviction

*For any* cache state where total storage exceeds 50MB, adding new data should trigger removal of the entry with the oldest cache timestamp before storing the new data.

**Validates: Requirements 6.6**

### Property 14: JSON Parsing Validity

*For any* valid JSON response from the API conforming to the ChartDataResponse schema, the parser should successfully convert it to a list of PriceDataPoint domain models.

**Validates: Requirements 9.1**

### Property 15: OHLC Relationship Validation

*For any* PriceDataPoint, the parser should validate that High >= Low, High >= Open, High >= Close, Low <= Open, and Low <= Close; if any relationship is violated, validation should fail.

**Validates: Requirements 9.2**

### Property 16: Invalid Data Error Reporting

*For any* OHLC data that fails validation, the parser should return a descriptive error indicating which relationship constraint was violated.

**Validates: Requirements 9.3**

### Property 17: Price Formatting Precision

*For any* price value, the formatter should display it with the appropriate decimal precision (2 places for stocks, 4 for crypto).

**Validates: Requirements 9.4**

### Property 18: Timestamp Formatting by Timeframe

*For any* timestamp and selected timeframe, the formatter should apply the correct format: "HH:mm" for 1D, "MMM dd" for 1W-1M, "MMM yyyy" for 3M-1Y.

**Validates: Requirements 9.5**

### Property 19: Price Data Round-Trip

*For any* valid PriceDataPoint, serializing it to JSON then parsing it back should produce an equivalent PriceDataPoint with the same OHLC values, timestamp, and volume.

**Validates: Requirements 9.6**

### Property 20: Volume Bar Rendering

*For any* list of volume data, each volume value should be rendered as a vertical bar in the volume chart.

**Validates: Requirements 10.1**

### Property 21: Volume Bar Color Coding

*For any* price period, if close > open (positive change), the volume bar should be green; if close < open (negative change), the volume bar should be red; if close == open, a default color should be used.

**Validates: Requirements 10.2, 10.3**

### Property 22: Volume Bar Scaling

*For any* set of visible volume data, each bar's height should be scaled proportionally such that the bar representing the maximum volume reaches 100% height and all other bars scale relative to that maximum.

**Validates: Requirements 10.4**

### Property 23: Crosshair Volume Display

*For any* data point selected by the crosshair, the exact volume value for that point should be displayed alongside the price information.

**Validates: Requirements 10.5**

## Error Handling

### Network Errors

- **Connection Failures**: When network requests fail, display user-friendly error message with retry button
- **Timeout Handling**: Set 30-second timeout for API requests; on timeout, treat as network error
- **HTTP Error Codes**: 
  - 404: "Stock data not found"
  - 429: "Rate limit exceeded, please try again later"
  - 500-599: "Server error, please try again"
  - Other: "Unable to load chart data"

### Data Validation Errors

- **Invalid OHLC Relationships**: Log validation error with details, display generic error to user
- **Missing Required Fields**: Treat as parse error, display "Invalid data format" message
- **Malformed JSON**: Catch parse exceptions, display "Unable to process data" message

### Cache Errors

- **Database Write Failures**: Log error but continue with in-memory data, don't block user
- **Cache Corruption**: Clear corrupted entry, fetch fresh data from network
- **Storage Full**: Trigger aggressive LRU eviction, remove multiple old entries if needed

### UI State Errors

- **Empty Data Sets**: Display "No data available for this timeframe" message
- **Insufficient Data for Indicators**: Hide indicator without error (expected behavior)
- **Gesture Conflicts**: Prioritize most recent gesture, cancel previous gesture handling

### Recovery Strategies

- **Automatic Retry**: For transient network errors, retry once after 2-second delay
- **Fallback to Cache**: If network fails but stale cache exists, offer to show stale data
- **Graceful Degradation**: If indicators fail to calculate, show chart without indicators
- **State Preservation**: Maintain user's selected timeframe and chart type across errors

## Testing Strategy

### Dual Testing Approach

This feature requires both unit testing and property-based testing for comprehensive coverage:

- **Unit tests** verify specific examples, edge cases, and error conditions
- **Property tests** verify universal properties across all inputs through randomization
- Both approaches are complementary and necessary

### Unit Testing

Unit tests should focus on:

1. **Specific Examples**
   - Chart component displays with sample stock data
   - Timeframe selector shows all 6 options
   - Loading and error states render correctly
   - MA20 and MA50 indicators display when enabled

2. **Edge Cases**
   - Empty data sets
   - Single data point
   - Data with identical OHLC values (flat price)
   - Very large volume numbers
   - Timestamps at boundary conditions

3. **Integration Points**
   - ViewModel correctly updates StateFlow on data load
   - Repository coordinates between local and remote sources
   - Cache DAO operations execute correctly
   - Hilt dependency injection wiring

4. **Error Conditions**
   - Network timeout handling
   - Malformed JSON responses
   - Database write failures
   - Invalid OHLC relationships

### Property-Based Testing

Property-based testing should be implemented using **Kotest Property Testing** library for Kotlin. Each property test must:

- Run minimum **100 iterations** to ensure comprehensive input coverage
- Reference its corresponding design document property in a comment tag
- Use appropriate generators for test data

**Tag Format**: `// Feature: stock-price-charts, Property {number}: {property_text}`

**Property Test Configuration**:

```kotlin
class ChartPropertyTests : StringSpec({
    "Property 1: Chart Data Completeness" {
        checkAll(100, Arb.list(Arb.priceDataPoint(), 1..1000)) { priceData ->
            // Feature: stock-price-charts, Property 1: Chart Data Completeness
            val candlestickOutput = renderCandlestick(priceData)
            priceData.forEach { point ->
                candlestickOutput shouldContain point.open
                candlestickOutput shouldContain point.high
                candlestickOutput shouldContain point.low
                candlestickOutput shouldContain point.close
            }
            
            val lineOutput = renderLine(priceData)
            priceData.forEach { point ->
                lineOutput shouldContain point.close
            }
        }
    }
    
    "Property 4: Zoom Level Invariant" {
        checkAll(100, Arb.zoomGesture()) { gesture ->
            // Feature: stock-price-charts, Property 4: Zoom Level Invariant
            val initialZoom = Arb.float(1f, 10f).next()
            val resultZoom = applyZoomGesture(initialZoom, gesture)
            resultZoom shouldBeInRange 1f..10f
        }
    }
    
    "Property 19: Price Data Round-Trip" {
        checkAll(100, Arb.priceDataPoint()) { dataPoint ->
            // Feature: stock-price-charts, Property 19: Price Data Round-Trip
            val json = serialize(dataPoint)
            val parsed = parse(json)
            parsed shouldBe dataPoint
        }
    }
})
```

**Custom Generators Needed**:

```kotlin
fun Arb.Companion.priceDataPoint(): Arb<PriceDataPoint> = arbitrary {
    val low = Arb.double(1.0, 1000.0).bind()
    val high = Arb.double(low, low * 1.5).bind()
    val open = Arb.double(low, high).bind()
    val close = Arb.double(low, high).bind()
    PriceDataPoint(
        timestamp = Arb.long(1000000000L, 2000000000L).bind(),
        open = open,
        high = high,
        low = low,
        close = close,
        volume = Arb.long(0L, 1000000000L).bind()
    )
}

fun Arb.Companion.timeframe(): Arb<Timeframe> = 
    Arb.enum<Timeframe>()

fun Arb.Companion.zoomGesture(): Arb<ZoomGesture> = arbitrary {
    ZoomGesture(
        scaleFactor = Arb.float(0.1f, 5f).bind(),
        focusX = Arb.float(0f, 1000f).bind()
    )
}
```

**Properties to Test**:

All 23 properties listed in the Correctness Properties section should have corresponding property-based tests. Priority order:

1. **Critical Path** (Properties 1, 2, 11, 14, 15, 19): Core data flow and validation
2. **User Interactions** (Properties 4, 5, 6, 7, 10): Gesture and UI behavior
3. **Data Formatting** (Properties 17, 18, 20, 21, 22): Display correctness
4. **Cache Behavior** (Properties 12, 13): Storage management
5. **Edge Cases** (Properties 8, 9, 16, 23): Boundary conditions

### Test Coverage Goals

- **Unit Test Coverage**: Minimum 80% line coverage for ViewModels, Repositories, Parsers, Formatters
- **Property Test Coverage**: All 23 correctness properties implemented as property tests
- **Integration Test Coverage**: End-to-end flows for each timeframe and chart type
- **UI Test Coverage**: Compose UI tests for user interactions (zoom, pan, crosshair, timeframe selection)

### Testing Tools

- **Unit Testing**: JUnit 5, MockK for mocking
- **Property Testing**: Kotest Property Testing
- **UI Testing**: Compose Testing library
- **Integration Testing**: Hilt testing utilities, Room in-memory database
- **Network Testing**: MockWebServer for API response simulation

