# Kế hoạch Triển khai: Stock News (Tin tức Chứng khoán)

## Tổng quan

Kế hoạch này chia chức năng Stock News thành các task coding rời rạc. Triển khai theo thứ tự: backend Python → Supabase schema → data layer Android → domain layer → presentation layer → tích hợp UI → tests.

## Tasks

- [x] 1. Thiết lập Supabase schema và cấu hình
  - Tạo bảng news_articles với đầy đủ cột theo design
  - Tạo index trên published_at và stock_symbols (GIN)
  - Cấu hình RLS: public read, service role write
  - Enable Realtime CDC cho bảng news_articles
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [x] 2. Thiết lập Python FastAPI Service
  - [x] 2.1 Khởi tạo project FastAPI với APScheduler và Supabase Python client
    - Tạo cấu trúc project: main.py, crawler/, processor/, models/
    - Cài đặt dependencies: fastapi, apscheduler, supabase-py, httpx, beautifulsoup4
    - Cấu hình biến môi trường: SUPABASE_URL, SUPABASE_SERVICE_KEY
    - _Requirements: 1.1, 1.2_

  - [x] 2.2 Implement News_Crawler worker
    - Tạo NewsSource dataclass với url, parser, timeout=10s
    - Implement fetch_source() với httpx async, timeout 10 giây
    - Implement parser cho RSS (CafeF, VnEconomy) và HTML scraper (Vietstock, Fireant)
    - Implement stock_symbol_extractor bằng regex cho mã VN30/HOSE
    - Implement save_articles() với upsert conflict on url
    - Đăng ký APScheduler job mỗi 15 phút
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [x] 2.3 Implement AI_Processor worker
    - Load PhoBERT hoặc FinBERT model khi khởi động service
    - Implement fetch_pending() lấy tối đa 100 bài pending_analysis
    - Implement analyze_batch() với batch_size=32
    - Implement update_articles() cập nhật sentiment_label, sentiment_score, status
    - Xử lý lỗi: đổi status thành analysis_failed khi model lỗi
    - Đăng ký APScheduler job mỗi 15 phút (offset 5 phút sau crawler)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

- [x] 3. Thiết lập dependencies và data models Android
  - Thêm Kotest property testing vào build.gradle
  - Thêm Paging 3 library dependency
  - Tạo domain models: NewsArticle data class, SentimentLabel enum, ArticleStatus enum
  - Tạo NewsUiState sealed class
  - _Requirements: 4.1, 11.4_

- [x] 4. Implement database layer (Room)
  - [x] 4.1 Tạo NewsArticleEntity và NewsConverters
    - Định nghĩa entity với tất cả các trường theo design
    - Implement TypeConverter cho stockSymbols (List<String> ↔ JSON string)
    - _Requirements: 9.6_

  - [x] 4.2 Tạo NewsDao với đầy đủ queries
    - Implement getAllNews() trả về PagingSource
    - Implement getNewsBySymbol(symbol, limit) trả về Flow
    - Implement getLatestNews(limit) trả về Flow
    - Implement insertAll(), deleteOldNews(), getCount()
    - _Requirements: 9.1, 9.5_

  - [x] 4.3 Thêm NewsArticleEntity vào StocKampDatabase
    - Thêm entity vào @Database annotation
    - Tạo migration từ version hiện tại
    - Thêm provideNewsDao() vào AppModule
    - _Requirements: 9.1_

  - [x] 4.4 Viết property test: Cache eviction giữ tối đa 200 bài
    - **Property 14: Cache eviction giữ tối đa 200 bài**
    - Dùng Room in-memory database
    - Generate danh sách 201-500 bài ngẫu nhiên, insert, gọi deleteOldNews(), kiểm tra count <= 200
    - **Validates: Requirements 9.5**

- [x] 5. Implement data mapping và parsing
  - [x] 5.1 Tạo NewsArticleDto và mapping functions
    - Định nghĩa @Serializable NewsArticleDto với @SerialName annotations
    - Implement NewsArticleDto.toDomain(): NewsArticle
    - Implement NewsArticle.toEntity(): NewsArticleEntity
    - Implement NewsArticleEntity.toDomain(): NewsArticle
    - _Requirements: 12.1_

  - [x] 5.2 Implement validation và timezone conversion
    - Validate các trường bắt buộc (id, title, url, published_at) không null/rỗng
    - Parse published_at từ ISO 8601 UTC, convert sang ZoneId("Asia/Ho_Chi_Minh")
    - Ghi log và return null cho bản ghi không hợp lệ
    - _Requirements: 12.2, 12.3, 12.4_

  - [x] 5.3 Viết property test: Round-trip JSON parse của NewsArticle
    - **Property 15: Round-trip JSON parse của NewsArticle**
    - Generate NewsArticle ngẫu nhiên, serialize → JSON → deserialize, kiểm tra equality
    - **Validates: Requirements 12.1, 12.5, 9.6**

  - [x] 5.4 Viết property test: Validation từ chối bản ghi thiếu trường bắt buộc
    - **Property 16: Validation từ chối bản ghi thiếu trường bắt buộc**
    - Generate DTO với các trường bắt buộc bị null/rỗng, kiểm tra parse trả về null/error
    - **Validates: Requirements 12.2, 12.3**

  - [x] 5.5 Viết property test: Chuyển đổi timezone UTC sang UTC+7
    - **Property 17: Chuyển đổi timezone UTC sang UTC+7**
    - Generate timestamp UTC ngẫu nhiên, kiểm tra kết quả chênh lệch đúng 7 giờ
    - **Validates: Requirements 12.4**

- [x] 6. Implement NewsRepository
  - [x] 6.1 Tạo NewsRepository interface và NewsRepositoryImpl
    - Implement getNewsStream() với Paging 3 RemoteMediator
    - Implement getNewsBySymbol() và getLatestNews() từ Room Flow
    - Implement refresh() fetch từ Supabase và update Room
    - Cache-first logic: kiểm tra cachedAt < 10 phút trước khi fetch
    - Gọi deleteOldNews() sau mỗi lần insert batch
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 11.1, 11.4_

  - [x] 6.2 Implement Supabase Realtime subscription
    - Implement subscribeRealtime() đăng ký channel news_articles
    - Lắng nghe INSERT events với status = analyzed
    - Cập nhật Room cache khi nhận event mới
    - Implement unsubscribeRealtime() khi app vào background
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

  - [x] 6.3 Viết property test: Filter theo symbol chỉ trả về bài có symbol đó
    - **Property 10: Filter theo symbol chỉ trả về bài có symbol đó**
    - Generate danh sách bài ngẫu nhiên và symbol ngẫu nhiên, kiểm tra tất cả kết quả có symbol đó
    - **Validates: Requirements 6.2, 8.1**

  - [x] 6.4 Viết property test: Thứ tự hiển thị giảm dần theo published_at
    - **Property 7: Thứ tự hiển thị giảm dần theo published_at**
    - Generate danh sách bài ngẫu nhiên, kiểm tra sorted list thỏa mãn bài[i].publishedAt >= bài[i+1].publishedAt
    - **Validates: Requirements 4.1**

  - [x] 6.5 Viết property test: Kích thước trang phân trang
    - **Property 18: Kích thước trang phân trang**
    - Kiểm tra mỗi trang (trừ trang cuối) có đúng 20 bài
    - **Validates: Requirements 11.1**

- [ ] 7. Implement NewsViewModel
  - Tạo NewsViewModel với Hilt injection
  - Expose newsPagingData: Flow<PagingData<NewsArticle>> từ repository
  - Expose uiState: StateFlow<NewsUiState>
  - Implement applyFilter(symbols), clearFilter(), refresh(), loadForSymbol(symbol)
  - Xử lý lifecycle: subscribeRealtime() khi active, unsubscribeRealtime() khi inactive
  - _Requirements: 4.2, 6.2, 6.4, 10.3, 11.3, 11.5_

- [x] 8. Implement UI Components
  - [x] 8.1 Tạo SentimentChip composable
    - Hiển thị label tiếng Việt: "Tích cực" / "Tiêu cực" / "Trung lập"
    - Áp dụng màu: POSITIVE→AccentGreen, NEGATIVE→AccentRed, NEUTRAL→onSurfaceVariant
    - Hiển thị score dạng phần trăm bên cạnh label
    - Hiển thị "Đang phân tích..." khi status = pending_analysis
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 8.2 Tạo NewsArticleCard composable
    - Hiển thị: tiêu đề, tên nguồn, thời gian (UTC+7), SentimentChip
    - Xử lý click → mở URL trong browser (Intent.ACTION_VIEW)
    - Skeleton loading placeholder khi isLoading = true
    - _Requirements: 4.3, 4.5, 4.6_

  - [x] 8.3 Tạo NewsSection composable (tái sử dụng)
    - Nhận articles: List<NewsArticle>, title, onArticleClick, onSeeAllClick, isLoading
    - Hiển thị header với nút "Xem tất cả" (nếu onSeeAllClick != null)
    - Hiển thị skeleton khi isLoading = true
    - Hiển thị empty state khi articles rỗng
    - _Requirements: 7.1, 7.4, 7.5, 8.5_

  - [x] 8.4 Viết property test: Màu sắc SentimentChip tương ứng đúng với label
    - **Property 8: Màu sắc SentimentChip tương ứng đúng với label**
    - Generate NewsArticle với label ngẫu nhiên, kiểm tra màu chip đúng với mapping
    - **Validates: Requirements 5.1, 5.2, 5.3, 7.4**

  - [x] 8.5 Viết property test: Định dạng phần trăm sentiment score
    - **Property 9: Định dạng phần trăm sentiment score**
    - Generate score trong [0.0, 1.0], kiểm tra chuỗi hiển thị là số nguyên phần trăm hợp lệ
    - **Validates: Requirements 5.4**

- [ ] 9. Implement NewsListScreen
  - Tạo NewsListScreen composable với LazyColumn + Paging 3
  - Implement filter bar: nhập/chọn Stock_Symbol, hiển thị filter chips với nút xóa
  - Hỗ trợ multi-symbol filter
  - Pull-to-refresh với SwipeRefresh
  - Hiển thị loading, error, empty states
  - _Requirements: 4.1, 4.2, 4.4, 4.6, 4.7, 6.1, 6.2, 6.3, 6.4, 6.5_

- [ ] 10. Tích hợp vào HomeScreen
  - Thêm NewsSection vào HomeScreen LazyColumn sau section "Danh sách theo dõi"
  - Kết nối với NewsViewModel.getLatestNews(5)
  - Xử lý onSeeAllClick → navigate đến NewsListScreen
  - Xử lý onArticleClick → mở URL trong browser
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [ ] 11. Tích hợp vào StockDetailScreen
  - Thêm NewsSection vào StockDetailScreen với title "Tin tức liên quan"
  - Kết nối với NewsViewModel.getNewsBySymbol(symbol, 10)
  - Xử lý onSeeAllClick → navigate đến NewsListScreen với filter sẵn cho symbol
  - Hiển thị "Chưa có tin tức liên quan" khi danh sách rỗng
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [ ] 12. Thêm navigation route cho NewsListScreen
  - Thêm route "news" vào NavGraph trong StocKampNavHost
  - Hỗ trợ optional argument: symbolFilter (String?)
  - Thêm entry point từ HomeScreen và StockDetailScreen
  - _Requirements: 7.3, 8.4_

- [ ] 13. Thiết lập Hilt DI cho News module
  - Tạo NewsModule với @InstallIn(SingletonComponent::class)
  - Provide NewsRepository, NewsDao bindings
  - Đảm bảo NewsViewModel được inject đúng
  - _Requirements: 4.2_

- [x] 14. Viết các property tests còn lại
  - [x] 14.1 Viết property test: Sentiment score nằm trong khoảng hợp lệ
    - **Property 4: Sentiment score nằm trong khoảng hợp lệ**
    - Generate NewsArticle ngẫu nhiên, kiểm tra sentimentScore trong [0.0, 1.0] hoặc null
    - **Validates: Requirements 2.2**

  - [x] 14.2 Viết property test: Nhất quán giữa sentiment_label và sentiment_score
    - **Property 5: Nhất quán giữa sentiment_label và sentiment_score**
    - Generate SentimentResult từ mock model, kiểm tra label tương ứng với class có score cao nhất
    - **Validates: Requirements 2.2, 2.3**

  - [x] 14.3 Viết property test: Multi-filter trả về bài có ít nhất một symbol khớp
    - **Property 11: Multi-filter trả về bài có ít nhất một symbol khớp**
    - Generate tập symbols và danh sách bài, kiểm tra mỗi kết quả có ít nhất một symbol trong tập filter
    - **Validates: Requirements 6.5**

  - [x] 14.4 Viết property test: Clear filter khôi phục danh sách đầy đủ
    - **Property 12: Clear filter khôi phục danh sách đầy đủ**
    - Apply filter rồi clear, kiểm tra kết quả bằng danh sách ban đầu
    - **Validates: Requirements 6.4**

  - [x] 14.5 Viết property test: HomeScreen giới hạn tối đa 5 bài
    - **Property 13: HomeScreen giới hạn tối đa 5 bài**
    - Generate danh sách bài ngẫu nhiên, kiểm tra getLatestNews(5) trả về <= 5 bài với status=analyzed
    - **Validates: Requirements 7.1**

- [x] 15. Viết unit tests và integration tests
  - Unit tests cho NewsViewModel: filter, refresh, StateFlow updates
  - Unit tests cho NewsRepository: cache-first logic, TTL 10 phút, offline fallback
  - Integration test: fetch → cache → display end-to-end flow
  - UI tests (Compose): NewsListScreen render, SentimentChip colors, filter interaction
  - _Requirements: 4.2, 4.6, 4.7, 9.2, 9.3, 9.4, 10.3_
