# Tài liệu Thiết kế: Stock News (Tin tức Chứng khoán)

## Tổng quan

Chức năng Stock News cung cấp tin tức tài chính Việt Nam được tổng hợp tự động từ CafeF, VnEconomy, Vietstock và Fireant, kèm phân tích cảm xúc (Sentiment Analysis) bằng PhoBERT/FinBERT. Kiến trúc hybrid gồm ba lớp:

1. **Python FastAPI Service** — crawl RSS/HTML và chạy AI inference, lưu kết quả vào Supabase
2. **Supabase** — trung gian lưu trữ, cung cấp REST API và Realtime WebSocket cho Android
3. **Android App** — đọc dữ liệu từ Supabase, cache offline qua Room, cập nhật realtime

Quyết định thiết kế chính:
- Dùng APScheduler trong FastAPI để chạy crawler mỗi 15 phút, tách biệt với AI processor
- Dùng Paging 3 library trên Android để quản lý phân trang và cache hiệu quả
- Dùng Supabase Realtime (PostgreSQL CDC) để push tin tức mới về app mà không cần polling
- Room Database giới hạn 200 bài viết gần nhất, xóa bài cũ theo LRU khi vượt giới hạn
- Sentiment label và score được lưu cùng bài viết để hiển thị offline không cần tính lại

## Kiến trúc

### Sơ đồ tổng thể

```
┌─────────────────────────────────────────────────────────────────┐
│                    Python FastAPI Service                        │
│                                                                  │
│  ┌──────────────────┐    ┌──────────────────────────────────┐   │
│  │  News_Crawler    │    │        AI_Processor              │   │
│  │  (APScheduler    │    │  (APScheduler 15 phút)           │   │
│  │   15 phút)       │    │  - PhoBERT / FinBERT             │   │
│  │  - CafeF RSS     │    │  - Xử lý pending_analysis        │   │
│  │  - VnEconomy RSS │    │  - Cập nhật sentiment_score      │   │
│  │  - Vietstock HTML│    └──────────────┬───────────────────┘   │
│  │  - Fireant API   │                   │                        │
│  └────────┬─────────┘                   │                        │
│           │ INSERT (pending_analysis)   │ UPDATE (analyzed)      │
└───────────┼─────────────────────────────┼────────────────────────┘
            │                             │
            ▼                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                         Supabase                                 │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  news_articles (PostgreSQL)                              │   │
│  │  - RLS: public read, service role write                  │   │
│  │  - Index: published_at, stock_symbols (GIN)              │   │
│  │  - Realtime CDC enabled                                  │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
            │ REST API + Realtime WebSocket
            ▼
┌─────────────────────────────────────────────────────────────────┐
│                       Android App                                │
│                                                                  │
│  ┌──────────────┐    ┌──────────────────┐    ┌───────────────┐  │
│  │ NewsViewModel│◄───│ NewsRepository   │◄───│ Supabase      │  │
│  │ StateFlow    │    │ (cache-first)    │    │ RemoteSource  │  │
│  │ Paging 3     │    └────────┬─────────┘    └───────────────┘  │
│  └──────┬───────┘             │                                  │
│         │                     ▼                                  │
│  ┌──────▼───────┐    ┌──────────────────┐                       │
│  │ NewsListScreen│   │  Room Database   │                       │
│  │ HomeScreen   │   │  (NewsArticleDao) │                       │
│  │ StockDetail  │   │  max 200 bài      │                       │
│  └──────────────┘   └──────────────────┘                       │
└─────────────────────────────────────────────────────────────────┘
```

### Luồng dữ liệu

**Luồng crawl và phân tích (Backend):**
1. APScheduler kích hoạt News_Crawler mỗi 15 phút
2. Crawler fetch RSS/HTML từ 4 nguồn, trích xuất bài viết mới
3. Kiểm tra URL trùng lặp qua Supabase upsert (conflict on url)
4. Lưu bài mới với status = `pending_analysis`
5. APScheduler kích hoạt AI_Processor mỗi 15 phút (offset 5 phút)
6. AI_Processor lấy tối đa 100 bài `pending_analysis`, chạy PhoBERT/FinBERT
7. Cập nhật sentiment_label, sentiment_score, status = `analyzed`

**Luồng đọc trên Android:**
1. NewsRepository kiểm tra Room cache (< 10 phút → trả về cache)
2. Nếu cache cũ hoặc trống → fetch từ Supabase REST API
3. Lưu kết quả vào Room, xóa bài cũ nếu vượt 200 bài
4. Supabase Realtime push bài mới → NewsRepository cập nhật Room
5. NewsViewModel nhận Flow từ Room → cập nhật StateFlow → UI re-render

### Mô hình luồng (Threading)

- UI rendering: Main thread (Compose)
- Supabase API calls: IO dispatcher
- Room operations: IO dispatcher
- Sentiment chip rendering: Main thread
- Realtime subscription: IO dispatcher → emit vào StateFlow trên Main

## Thành phần và Giao diện

### Backend: News_Crawler

Worker Python chạy theo lịch APScheduler.

```python
class NewsCrawler:
    sources: list[NewsSource]  # CafeF, VnEconomy, Vietstock, Fireant
    
    async def run(self) -> CrawlResult
    async def fetch_source(self, source: NewsSource) -> list[RawArticle]
    async def extract_article(self, raw: RawArticle) -> ParsedArticle
    async def save_articles(self, articles: list[ParsedArticle]) -> int
```

Mỗi `NewsSource` định nghĩa:
- `url`: RSS feed URL hoặc HTML endpoint
- `parser`: hàm parse tương ứng (RSS vs HTML scraper)
- `timeout`: 10 giây
- `stock_symbol_extractor`: regex/NLP để trích xuất mã cổ phiếu từ tiêu đề/nội dung

### Backend: AI_Processor

```python
class AIProcessor:
    model: SentimentModel  # PhoBERT hoặc FinBERT
    batch_size: int = 32
    
    async def run(self) -> ProcessResult
    async def fetch_pending(self, limit: int = 100) -> list[PendingArticle]
    async def analyze_batch(self, articles: list[PendingArticle]) -> list[SentimentResult]
    async def update_articles(self, results: list[SentimentResult]) -> None
```

`SentimentResult`:
```python
@dataclass
class SentimentResult:
    article_id: str
    label: Literal["POSITIVE", "NEGATIVE", "NEUTRAL"]
    score: float  # [0.0, 1.0]
    status: Literal["analyzed", "analysis_failed"]
```

### Android: NewsRepository

```kotlin
interface NewsRepository {
    fun getNewsStream(pageSize: Int = 20): Flow<PagingData<NewsArticle>>
    fun getNewsBySymbol(symbol: String, limit: Int = 10): Flow<List<NewsArticle>>
    fun getLatestNews(limit: Int = 5): Flow<List<NewsArticle>>
    suspend fun refresh(): Result<Unit>
    suspend fun subscribeRealtime()
    suspend fun unsubscribeRealtime()
}
```

### Android: NewsDao

```kotlin
@Dao
interface NewsDao {
    @Query("SELECT * FROM news_articles ORDER BY published_at DESC")
    fun getAllNews(): PagingSource<Int, NewsArticleEntity>

    @Query("""
        SELECT * FROM news_articles 
        WHERE :symbol = ANY(stock_symbols) 
        ORDER BY published_at DESC LIMIT :limit
    """)
    fun getNewsBySymbol(symbol: String, limit: Int): Flow<List<NewsArticleEntity>>

    @Query("SELECT * FROM news_articles ORDER BY published_at DESC LIMIT :limit")
    fun getLatestNews(limit: Int): Flow<List<NewsArticleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(articles: List<NewsArticleEntity>)

    @Query("DELETE FROM news_articles WHERE id NOT IN (SELECT id FROM news_articles ORDER BY published_at DESC LIMIT 200)")
    suspend fun deleteOldNews()

    @Query("SELECT COUNT(*) FROM news_articles")
    suspend fun getCount(): Int
}
```

### Android: NewsViewModel

```kotlin
class NewsViewModel @Inject constructor(
    private val repository: NewsRepository
) : ViewModel() {

    val uiState: StateFlow<NewsUiState>
    val newsPagingData: Flow<PagingData<NewsArticle>>

    fun applyFilter(symbols: List<String>)
    fun clearFilter()
    fun refresh()
    fun loadForSymbol(symbol: String)
}

sealed class NewsUiState {
    object Loading : NewsUiState()
    data class Success(
        val latestNews: List<NewsArticle>,
        val activeFilters: List<String>,
        val isRefreshing: Boolean
    ) : NewsUiState()
    data class Error(val message: String, val hasCachedData: Boolean) : NewsUiState()
}
```

### Android: UI Components

**NewsListScreen** — màn hình đầy đủ danh sách tin tức:
```kotlin
@Composable
fun NewsListScreen(
    initialSymbolFilter: String? = null,
    viewModel: NewsViewModel = hiltViewModel(),
    onArticleClick: (String) -> Unit  // mở URL trong browser
)
```

**NewsSection** — composable tái sử dụng cho HomeScreen và StockDetailScreen:
```kotlin
@Composable
fun NewsSection(
    articles: List<NewsArticle>,
    title: String,
    onArticleClick: (String) -> Unit,
    onSeeAllClick: (() -> Unit)? = null,
    isLoading: Boolean = false
)
```

**SentimentChip** — hiển thị nhãn cảm xúc với màu sắc:
```kotlin
@Composable
fun SentimentChip(
    label: SentimentLabel,
    score: Float?,
    modifier: Modifier = Modifier
)
```

Màu sắc theo theme hiện tại:
- POSITIVE → `AccentGreen`
- NEGATIVE → `AccentRed`
- NEUTRAL → `onSurfaceVariant`

## Mô hình Dữ liệu

### Supabase Schema

```sql
CREATE TABLE news_articles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title           TEXT NOT NULL,
    url             TEXT NOT NULL UNIQUE,
    summary         TEXT,
    source_name     TEXT NOT NULL,
    published_at    TIMESTAMPTZ NOT NULL,
    stock_symbols   TEXT[] DEFAULT '{}',
    sentiment_label TEXT CHECK (sentiment_label IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL')),
    sentiment_score FLOAT CHECK (sentiment_score >= 0.0 AND sentiment_score <= 1.0),
    status          TEXT NOT NULL DEFAULT 'pending_analysis'
                    CHECK (status IN ('pending_analysis', 'analyzed', 'analysis_failed')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index tối ưu truy vấn
CREATE INDEX idx_news_published_at ON news_articles (published_at DESC);
CREATE INDEX idx_news_stock_symbols ON news_articles USING GIN (stock_symbols);
CREATE INDEX idx_news_status ON news_articles (status) WHERE status = 'pending_analysis';

-- RLS
ALTER TABLE news_articles ENABLE ROW LEVEL SECURITY;
CREATE POLICY "public_read" ON news_articles FOR SELECT USING (true);
CREATE POLICY "service_write" ON news_articles FOR ALL USING (auth.role() = 'service_role');
```

### Domain Model (Android)

```kotlin
data class NewsArticle(
    val id: String,
    val title: String,
    val url: String,
    val summary: String?,
    val sourceName: String,
    val publishedAt: Instant,
    val stockSymbols: List<String>,
    val sentimentLabel: SentimentLabel?,
    val sentimentScore: Float?,
    val status: ArticleStatus
)

enum class SentimentLabel { POSITIVE, NEGATIVE, NEUTRAL }

enum class ArticleStatus { PENDING_ANALYSIS, ANALYZED, ANALYSIS_FAILED }
```

### Room Entity

```kotlin
@Entity(tableName = "news_articles")
data class NewsArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val summary: String?,
    val sourceName: String,
    val publishedAt: Long,           // epoch millis
    val stockSymbols: String,        // JSON array string
    val sentimentLabel: String?,
    val sentimentScore: Float?,
    val status: String,
    val cachedAt: Long = System.currentTimeMillis()
)
```

### Converters

```kotlin
class NewsConverters {
    @TypeConverter fun fromStockSymbols(value: String): List<String> = Json.decodeFromString(value)
    @TypeConverter fun toStockSymbols(list: List<String>): String = Json.encodeToString(list)
}
```

### Supabase Response DTO

```kotlin
@Serializable
data class NewsArticleDto(
    val id: String,
    val title: String,
    val url: String,
    val summary: String? = null,
    @SerialName("source_name") val sourceName: String,
    @SerialName("published_at") val publishedAt: String,  // ISO 8601
    @SerialName("stock_symbols") val stockSymbols: List<String> = emptyList(),
    @SerialName("sentiment_label") val sentimentLabel: String? = null,
    @SerialName("sentiment_score") val sentimentScore: Float? = null,
    val status: String
)
```

### Mapping

```kotlin
fun NewsArticleDto.toDomain(): NewsArticle
fun NewsArticle.toEntity(): NewsArticleEntity
fun NewsArticleEntity.toDomain(): NewsArticle
```


## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Trích xuất đầy đủ trường bài viết

*For any* bài viết raw được crawl từ bất kỳ nguồn nào (CafeF, VnEconomy, Vietstock, Fireant), kết quả parse phải chứa đầy đủ các trường bắt buộc: title, url, source_name, published_at. Các trường này không được null hoặc rỗng.

**Validates: Requirements 1.3, 12.2**

### Property 2: Idempotence khi crawl URL trùng lặp

*For any* tập bài viết đã tồn tại trong database, chạy crawler lần thứ hai với cùng nguồn dữ liệu không được tạo thêm bản ghi mới có URL trùng. Số lượng bản ghi trong DB sau lần crawl thứ hai phải bằng sau lần thứ nhất.

**Validates: Requirements 1.4**

### Property 3: Bài viết mới luôn có status pending_analysis

*For any* bài viết được News_Crawler lưu vào bảng news_articles, trường status tại thời điểm insert phải là `pending_analysis`.

**Validates: Requirements 1.6**

### Property 4: Sentiment score nằm trong khoảng hợp lệ

*For any* văn bản đầu vào (tiêu đề + tóm tắt) được Sentiment_Analyzer xử lý, giá trị sentiment_score trả về phải nằm trong khoảng [0.0, 1.0] và sentiment_label phải là một trong ba giá trị: POSITIVE, NEGATIVE, NEUTRAL.

**Validates: Requirements 2.2**

### Property 5: Nhất quán giữa sentiment_label và sentiment_score

*For any* kết quả phân tích cảm xúc, nếu sentiment_label là POSITIVE thì sentiment_score phải phản ánh xác suất tích cực (không phải xác suất của label khác). Tổng quát: label phải tương ứng với class có score cao nhất trong output của model.

**Validates: Requirements 2.2, 2.3**

### Property 6: Từ chối bản ghi vi phạm schema

*For any* bản ghi thiếu trường title hoặc url, hoặc có url trùng lặp, Supabase phải từ chối insert và trả về lỗi mô tả cụ thể. Bảng news_articles không được chứa bản ghi vi phạm ràng buộc NOT NULL hoặc UNIQUE.

**Validates: Requirements 3.5**

### Property 7: Thứ tự hiển thị giảm dần theo published_at

*For any* danh sách NewsArticle được trả về bởi NewsDao.getAllNews() hoặc NewsRepository, các bài viết phải được sắp xếp theo published_at giảm dần: bài[i].publishedAt >= bài[i+1].publishedAt với mọi i.

**Validates: Requirements 4.1**

### Property 8: Màu sắc SentimentChip tương ứng đúng với label

*For any* NewsArticle với sentiment_label bất kỳ, màu sắc của SentimentChip phải tương ứng: POSITIVE → AccentGreen, NEGATIVE → AccentRed, NEUTRAL → onSurfaceVariant. Không có label nào được hiển thị sai màu.

**Validates: Requirements 5.1, 5.2, 5.3, 7.4**

### Property 9: Định dạng phần trăm sentiment score

*For any* sentiment_score trong [0.0, 1.0], chuỗi hiển thị phải là số nguyên phần trăm (ví dụ: score=0.87 → "87%", score=0.0 → "0%", score=1.0 → "100%"). Không có giá trị nào nằm ngoài khoảng "0%" đến "100%".

**Validates: Requirements 5.4**

### Property 10: Filter theo symbol chỉ trả về bài có symbol đó

*For any* Stock_Symbol được dùng làm filter, tất cả NewsArticle trong kết quả phải có symbol đó trong danh sách stock_symbols. Không có bài nào trong kết quả được thiếu symbol đã filter.

**Validates: Requirements 6.2, 8.1**

### Property 11: Multi-filter trả về bài có ít nhất một symbol khớp

*For any* tập nhiều Stock_Symbol được filter đồng thời, mỗi NewsArticle trong kết quả phải có ít nhất một symbol trong tập filter xuất hiện trong stock_symbols của bài đó.

**Validates: Requirements 6.5**

### Property 12: Clear filter khôi phục danh sách đầy đủ

*For any* trạng thái filter đang hoạt động, sau khi clear filter, danh sách trả về phải bằng danh sách khi không có filter nào được áp dụng (round-trip property).

**Validates: Requirements 6.4**

### Property 13: HomeScreen giới hạn tối đa 5 bài

*For any* tập NewsArticle trong cache, số bài hiển thị trong section "Tin tức nổi bật" trên HomeScreen không được vượt quá 5, và phải là 5 bài có published_at lớn nhất với status = `analyzed`.

**Validates: Requirements 7.1**

### Property 14: Cache eviction giữ tối đa 200 bài

*For any* số lượng bài viết được insert vào Room Database, sau khi gọi deleteOldNews(), số lượng bản ghi trong bảng news_articles không được vượt quá 200. Các bài bị xóa phải là những bài có published_at nhỏ nhất.

**Validates: Requirements 9.5**

### Property 15: Round-trip JSON parse của NewsArticle

*For any* NewsArticle hợp lệ, serialize thành JSON (NewsArticleDto) rồi deserialize lại phải tạo ra đối tượng tương đương với đối tượng ban đầu — tất cả các trường phải bằng nhau, bao gồm cả sentiment_label, sentiment_score, và danh sách stock_symbols.

**Validates: Requirements 12.1, 12.5, 9.6**

### Property 16: Validation từ chối bản ghi thiếu trường bắt buộc

*For any* NewsArticleDto thiếu ít nhất một trong các trường bắt buộc (id, title, url, published_at), quá trình parse/validate phải thất bại và không tạo ra NewsArticle domain object. Không có NewsArticle nào với trường bắt buộc null hoặc rỗng được đưa vào cache.

**Validates: Requirements 12.2, 12.3**

### Property 17: Chuyển đổi timezone UTC sang UTC+7

*For any* chuỗi published_at dạng ISO 8601 UTC, sau khi chuyển đổi sang múi giờ Việt Nam (UTC+7), giá trị hiển thị phải chênh lệch đúng 7 giờ so với UTC. Ví dụ: "2024-01-15T10:00:00Z" → "2024-01-15 17:00" (UTC+7).

**Validates: Requirements 12.4**

### Property 18: Kích thước trang phân trang

*For any* trang dữ liệu được tải bởi NewsRepository (trừ trang cuối), số lượng bài viết trong trang phải đúng bằng pageSize (20 bài). Trang cuối có thể ít hơn nhưng không được nhiều hơn.

**Validates: Requirements 11.1**

## Xử lý Lỗi

### Lỗi mạng (Android)

- **Không có kết nối**: NewsRepository trả về dữ liệu từ Room cache, không hiển thị lỗi mạng nếu có cache
- **Timeout API**: Sau 30 giây không phản hồi, trả về `Result.Failure` với thông báo "Không thể kết nối đến máy chủ"
- **HTTP 4xx/5xx**: Map sang thông báo thân thiện, hiển thị nút "Thử lại"
- **Lỗi Realtime**: Tự động reconnect sau 5 giây, tối đa 3 lần; sau đó fallback về polling

### Lỗi parse dữ liệu (Android)

- **JSON không hợp lệ**: Ghi log chi tiết, bỏ qua bản ghi lỗi, tiếp tục xử lý các bản ghi còn lại
- **Thiếu trường bắt buộc**: Bỏ qua bản ghi, không crash app
- **Timestamp không hợp lệ**: Dùng `created_at` làm fallback, ghi log warning

### Lỗi cache (Android)

- **Room write failure**: Ghi log, tiếp tục hiển thị dữ liệu in-memory
- **Cache corruption**: Xóa toàn bộ bảng news_articles trong Room, fetch lại từ Supabase
- **Vượt giới hạn 200 bài**: Tự động gọi `deleteOldNews()` trước khi insert batch mới

### Lỗi backend (Python)

- **Nguồn không phản hồi (timeout 10s)**: Ghi log lỗi với source name, tiếp tục crawl nguồn khác
- **Parse HTML/RSS thất bại**: Ghi log với URL, bỏ qua bài viết đó
- **AI model lỗi**: Đổi status bài viết thành `analysis_failed`, ghi log stack trace
- **Supabase write lỗi**: Retry 3 lần với exponential backoff (1s, 2s, 4s), sau đó ghi log và bỏ qua

### Trạng thái UI lỗi

- **Danh sách trống + không có cache**: Hiển thị empty state với thông báo và nút thử lại
- **Đang tải**: Skeleton loading placeholder (không hiển thị vùng trống)
- **Lỗi tải + có cache**: Hiển thị dữ liệu cache kèm banner "Đang hiển thị dữ liệu đã lưu"

## Chiến lược Kiểm thử

### Phương pháp kiểm thử kép

Chức năng này yêu cầu cả unit testing và property-based testing để đảm bảo độ bao phủ toàn diện:

- **Unit tests**: Kiểm tra các ví dụ cụ thể, edge case, và điều kiện lỗi
- **Property tests**: Kiểm tra các thuộc tính phổ quát trên nhiều đầu vào ngẫu nhiên
- Hai phương pháp bổ sung cho nhau và đều cần thiết

### Unit Testing

Tập trung vào:

1. **Ví dụ cụ thể**
   - NewsListScreen hiển thị đúng với sample data
   - SentimentChip render đúng màu cho từng label
   - HomeScreen section hiển thị đúng 5 bài mới nhất
   - StockDetailScreen section hiển thị đúng 10 bài theo symbol

2. **Edge Cases**
   - Danh sách tin tức rỗng
   - Bài viết chưa có sentiment (pending_analysis)
   - stock_symbols là mảng rỗng
   - published_at ở các múi giờ khác nhau
   - Tên nguồn có ký tự đặc biệt

3. **Integration Points**
   - NewsViewModel cập nhật StateFlow khi repository emit
   - NewsRepository phối hợp đúng giữa Room và Supabase
   - Realtime subscription cập nhật cache đúng cách
   - Hilt DI wiring cho NewsModule

4. **Điều kiện lỗi**
   - Supabase trả về lỗi 500
   - Room database write failure
   - JSON thiếu trường bắt buộc
   - Realtime connection drop

### Property-Based Testing

Sử dụng **Kotest Property Testing** library cho Android/Kotlin. Mỗi property test phải:

- Chạy tối thiểu **100 iterations**
- Có comment tag tham chiếu property trong design document
- Dùng custom Arb generators cho NewsArticle

**Tag Format**: `// Feature: stock-news, Property {number}: {property_text}`

**Cấu hình Property Tests**:

```kotlin
class NewsPropertyTests : StringSpec({

    "Property 7: Thứ tự hiển thị giảm dần theo published_at" {
        checkAll(100, Arb.list(Arb.newsArticle(), 1..50)) { articles ->
            // Feature: stock-news, Property 7: Sort order descending by published_at
            val sorted = articles.sortedByDescending { it.publishedAt }
            sorted.zipWithNext().forEach { (a, b) ->
                a.publishedAt shouldBeGreaterThanOrEqualTo b.publishedAt
            }
        }
    }

    "Property 8: Màu sắc SentimentChip tương ứng đúng với label" {
        checkAll(100, Arb.newsArticle()) { article ->
            // Feature: stock-news, Property 8: Sentiment chip color mapping
            val expectedColor = when (article.sentimentLabel) {
                SentimentLabel.POSITIVE -> AccentGreen
                SentimentLabel.NEGATIVE -> AccentRed
                SentimentLabel.NEUTRAL -> onSurfaceVariantColor
                null -> null
            }
            val chip = renderSentimentChip(article.sentimentLabel, article.sentimentScore)
            chip.color shouldBe expectedColor
        }
    }

    "Property 14: Cache eviction giữ tối đa 200 bài" {
        checkAll(100, Arb.list(Arb.newsArticle(), 201..500)) { articles ->
            // Feature: stock-news, Property 14: Cache eviction max 200 articles
            val db = createInMemoryDatabase()
            db.newsDao().insertAll(articles.map { it.toEntity() })
            db.newsDao().deleteOldNews()
            db.newsDao().getCount() shouldBeLessThanOrEqualTo 200
        }
    }

    "Property 15: Round-trip JSON parse của NewsArticle" {
        checkAll(100, Arb.newsArticle()) { article ->
            // Feature: stock-news, Property 15: JSON round-trip serialization
            val dto = article.toDto()
            val json = Json.encodeToString(dto)
            val parsed = Json.decodeFromString<NewsArticleDto>(json)
            parsed.toDomain() shouldBe article
        }
    }

    "Property 10: Filter theo symbol chỉ trả về bài có symbol đó" {
        checkAll(100, Arb.string(3..5, Codepoint.alphanumeric()), Arb.list(Arb.newsArticle(), 0..30)) { symbol, articles ->
            // Feature: stock-news, Property 10: Symbol filter correctness
            val filtered = articles.filter { symbol in it.stockSymbols }
            filtered.forEach { article ->
                article.stockSymbols shouldContain symbol
            }
        }
    }

    "Property 4: Sentiment score nằm trong khoảng hợp lệ" {
        checkAll(100, Arb.newsArticle()) { article ->
            // Feature: stock-news, Property 4: Sentiment score range [0.0, 1.0]
            article.sentimentScore?.let { score ->
                score shouldBeGreaterThanOrEqualTo 0.0f
                score shouldBeLessThanOrEqualTo 1.0f
            }
        }
    }
})
```

**Custom Generators**:

```kotlin
fun Arb.Companion.newsArticle(): Arb<NewsArticle> = arbitrary {
    val labels = listOf(SentimentLabel.POSITIVE, SentimentLabel.NEGATIVE, SentimentLabel.NEUTRAL, null)
    val label = Arb.element(labels).bind()
    val score = if (label != null) Arb.float(0f, 1f).bind() else null
    NewsArticle(
        id = Arb.uuid().bind().toString(),
        title = Arb.string(10..100).bind(),
        url = "https://example.com/${Arb.string(5..20).bind()}",
        summary = Arb.orNull(Arb.string(20..200)).bind(),
        sourceName = Arb.element(listOf("CafeF", "VnEconomy", "Vietstock", "Fireant")).bind(),
        publishedAt = Instant.ofEpochMilli(Arb.long(1_600_000_000_000L, 1_800_000_000_000L).bind()),
        stockSymbols = Arb.list(Arb.string(3..5, Codepoint.alphanumeric()), 0..5).bind(),
        sentimentLabel = label,
        sentimentScore = score,
        status = Arb.enum<ArticleStatus>().bind()
    )
}
```

**Danh sách Properties cần implement**:

| Property | Mức độ ưu tiên | Loại test |
|----------|---------------|-----------|
| P15: Round-trip JSON | Critical | Property |
| P7: Sort order | Critical | Property |
| P10: Filter correctness | Critical | Property |
| P14: Cache eviction | Critical | Property |
| P4: Score range | High | Property |
| P8: Sentiment color | High | Property |
| P16: Validation reject | High | Property |
| P17: Timezone UTC+7 | High | Property |
| P2: Crawl idempotence | High | Property |
| P6: Schema constraint | Medium | Property |
| P9: Percent format | Medium | Property |
| P11: Multi-filter | Medium | Property |
| P12: Clear filter | Medium | Property |
| P13: HomeScreen limit | Medium | Property |
| P18: Page size | Medium | Property |
| P3: Status pending | Medium | Property |
| P5: Label-score consistency | Medium | Property |
| P1: Field extraction | Medium | Property |

### Công cụ kiểm thử

- **Unit Testing**: JUnit 5, MockK
- **Property Testing**: Kotest Property Testing
- **UI Testing**: Compose Testing library
- **Integration Testing**: Hilt testing, Room in-memory database
- **Network Testing**: MockWebServer (OkHttp) cho Supabase REST mock
- **Backend Testing**: pytest + hypothesis (Python property testing cho crawler/AI processor)

### Mục tiêu độ bao phủ

- **Unit Test Coverage**: Tối thiểu 80% line coverage cho ViewModel, Repository, Mappers
- **Property Test Coverage**: Tất cả 18 properties được implement
- **Integration Test**: End-to-end flow cho fetch → cache → display
- **UI Test**: Compose UI tests cho NewsListScreen, SentimentChip, filter interaction
