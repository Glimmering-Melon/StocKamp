# Tài liệu Yêu cầu

## Giới thiệu

Chức năng Tin tức Chứng khoán (Stock News) cung cấp cho người dùng StocKamp khả năng đọc tin tức tài chính Việt Nam được tổng hợp từ nhiều nguồn (CafeF, VnEconomy, Vietstock, Fireant), kèm theo phân tích cảm xúc (Sentiment Analysis) tự động bằng mô hình NLP (FinBERT/PhoBERT). Kiến trúc theo mô hình hybrid: Python FastAPI Service chịu trách nhiệm crawl và xử lý AI, Supabase làm trung gian lưu trữ, Android App đọc dữ liệu từ Supabase với cache offline qua Room Database và cập nhật realtime qua Supabase Realtime.

## Bảng thuật ngữ

- **StocKamp_App**: Ứng dụng Android theo dõi chứng khoán
- **News_Crawler**: Worker trong Python FastAPI Service chịu trách nhiệm crawl tin tức từ các nguồn bên ngoài
- **AI_Processor**: Worker trong Python FastAPI Service chịu trách nhiệm chạy mô hình Sentiment Analysis
- **Sentiment_Analyzer**: Thành phần AI sử dụng FinBERT hoặc PhoBERT để phân tích cảm xúc bài viết
- **News_Repository**: Thành phần Android quản lý việc lấy và cache tin tức
- **News_Cache**: Bảng Room Database lưu trữ tin tức offline trên thiết bị
- **News_ViewModel**: ViewModel quản lý trạng thái UI và luồng dữ liệu tin tức
- **News_List_Screen**: Màn hình hiển thị danh sách tin tức đầy đủ
- **Home_Screen**: Màn hình chính của ứng dụng, hiển thị section "Tin tức nổi bật"
- **Stock_Detail_Screen**: Màn hình chi tiết cổ phiếu, hiển thị tin tức liên quan đến mã đó
- **News_Article**: Một bài viết tin tức bao gồm tiêu đề, nội dung tóm tắt, nguồn, thời gian, danh sách mã cổ phiếu liên quan và điểm sentiment
- **Sentiment_Score**: Điểm phân tích cảm xúc của một bài viết, gồm nhãn (Tích cực/Tiêu cực/Trung lập) và giá trị xác suất từ 0.0 đến 1.0
- **Sentiment_Label**: Nhãn phân loại cảm xúc: POSITIVE (Tích cực), NEGATIVE (Tiêu cực), NEUTRAL (Trung lập)
- **Stock_Symbol**: Mã cổ phiếu niêm yết trên sàn chứng khoán Việt Nam (VD: VNM, HPG, VIC)
- **Supabase_Realtime**: Tính năng của Supabase cho phép nhận cập nhật dữ liệu theo thời gian thực qua WebSocket
- **News_Filter**: Bộ lọc tin tức theo mã cổ phiếu, nguồn, hoặc nhãn sentiment
- **news_articles**: Bảng trong Supabase PostgreSQL lưu trữ tất cả bài viết tin tức đã xử lý

## Yêu cầu

### Yêu cầu 1: Crawl tin tức từ nhiều nguồn

**User Story:** Là một nhà phát triển hệ thống, tôi muốn News_Crawler tự động thu thập tin tức từ CafeF, VnEconomy, Vietstock và Fireant, để người dùng luôn có tin tức tài chính cập nhật.

#### Tiêu chí chấp nhận

1. THE News_Crawler SHALL thu thập bài viết từ ít nhất 4 nguồn: CafeF, VnEconomy, Vietstock và Fireant
2. WHEN News_Crawler chạy theo lịch định kỳ, THE News_Crawler SHALL thu thập tin tức mới trong vòng 15 phút kể từ lần chạy trước
3. WHEN một bài viết mới được thu thập, THE News_Crawler SHALL trích xuất các trường: tiêu đề, URL gốc, tóm tắt nội dung, tên nguồn, thời gian xuất bản và danh sách Stock_Symbol liên quan
4. IF một URL bài viết đã tồn tại trong bảng news_articles, THEN THE News_Crawler SHALL bỏ qua bài viết đó để tránh trùng lặp
5. IF nguồn tin tức không phản hồi trong 10 giây, THEN THE News_Crawler SHALL ghi log lỗi và tiếp tục crawl các nguồn còn lại
6. THE News_Crawler SHALL lưu bài viết đã thu thập vào bảng news_articles trong Supabase với trạng thái `pending_analysis`

### Yêu cầu 2: Phân tích cảm xúc bài viết

**User Story:** Là người dùng, tôi muốn mỗi bài tin tức được gắn nhãn cảm xúc tự động, để tôi nhanh chóng đánh giá tác động của tin tức đến cổ phiếu.

#### Tiêu chí chấp nhận

1. WHEN một News_Article có trạng thái `pending_analysis` xuất hiện trong bảng news_articles, THE AI_Processor SHALL chạy Sentiment_Analyzer trên tiêu đề và tóm tắt nội dung của bài viết đó
2. THE Sentiment_Analyzer SHALL trả về Sentiment_Score gồm Sentiment_Label và giá trị xác suất trong khoảng [0.0, 1.0]
3. WHEN Sentiment_Analyzer hoàn thành, THE AI_Processor SHALL cập nhật bản ghi trong bảng news_articles với Sentiment_Score và đổi trạng thái thành `analyzed`
4. IF Sentiment_Analyzer gặp lỗi khi xử lý một bài viết, THEN THE AI_Processor SHALL đổi trạng thái bài viết thành `analysis_failed` và ghi log lỗi chi tiết
5. THE Sentiment_Analyzer SHALL xử lý văn bản tiếng Việt bằng mô hình PhoBERT hoặc FinBERT đã được fine-tune cho dữ liệu tài chính
6. THE AI_Processor SHALL xử lý tối đa 100 bài viết mỗi lần chạy để tránh quá tải tài nguyên

### Yêu cầu 3: Lưu trữ tin tức trong Supabase

**User Story:** Là nhà phát triển, tôi muốn tất cả tin tức và kết quả sentiment được lưu trong Supabase, để Android App có thể đọc dữ liệu một cách nhất quán.

#### Tiêu chí chấp nhận

1. THE bảng news_articles SHALL chứa các cột: id, title, url, summary, source_name, published_at, stock_symbols (array), sentiment_label, sentiment_score, status, created_at
2. THE News_Crawler SHALL ghi dữ liệu vào bảng news_articles sử dụng Supabase service role key
3. WHEN một bản ghi được chèn vào bảng news_articles, THE Supabase SHALL tự động gán giá trị created_at theo thời gian UTC hiện tại
4. THE bảng news_articles SHALL có chỉ mục (index) trên cột published_at và stock_symbols để tối ưu truy vấn
5. IF dữ liệu đầu vào vi phạm ràng buộc schema (thiếu title hoặc url), THEN THE Supabase SHALL từ chối bản ghi và trả về lỗi mô tả cụ thể

### Yêu cầu 4: Hiển thị danh sách tin tức trên Android

**User Story:** Là người dùng, tôi muốn xem danh sách tin tức tài chính trong ứng dụng, để theo dõi thông tin thị trường chứng khoán Việt Nam.

#### Tiêu chí chấp nhận

1. THE News_List_Screen SHALL hiển thị danh sách News_Article được sắp xếp theo published_at giảm dần
2. WHEN người dùng mở News_List_Screen, THE News_ViewModel SHALL tải danh sách tin tức từ News_Repository
3. THE News_List_Screen SHALL hiển thị cho mỗi News_Article: tiêu đề, tên nguồn, thời gian xuất bản, Sentiment_Label kèm màu sắc tương ứng
4. WHEN người dùng cuộn đến cuối danh sách, THE News_List_Screen SHALL tải thêm 20 bài viết tiếp theo (phân trang)
5. WHEN người dùng nhấn vào một News_Article, THE StocKamp_App SHALL mở URL gốc của bài viết trong trình duyệt hệ thống
6. IF danh sách tin tức đang tải, THEN THE News_List_Screen SHALL hiển thị skeleton loading placeholder
7. IF tải tin tức thất bại và không có dữ liệu cache, THEN THE News_List_Screen SHALL hiển thị thông báo lỗi kèm nút thử lại

### Yêu cầu 5: Hiển thị Sentiment Score với màu sắc

**User Story:** Là người dùng, tôi muốn nhìn thấy nhãn cảm xúc của tin tức được phân biệt bằng màu sắc, để nhanh chóng nhận biết tác động tích cực hay tiêu cực.

#### Tiêu chí chấp nhận

1. WHEN Sentiment_Label là POSITIVE, THE News_List_Screen SHALL hiển thị nhãn "Tích cực" với màu xanh lá (AccentGreen)
2. WHEN Sentiment_Label là NEGATIVE, THE News_List_Screen SHALL hiển thị nhãn "Tiêu cực" với màu đỏ (AccentRed)
3. WHEN Sentiment_Label là NEUTRAL, THE News_List_Screen SHALL hiển thị nhãn "Trung lập" với màu xám (onSurfaceVariant)
4. THE News_List_Screen SHALL hiển thị giá trị xác suất sentiment dưới dạng phần trăm (VD: "87%") bên cạnh Sentiment_Label
5. WHEN một News_Article chưa được phân tích (status = pending_analysis), THE News_List_Screen SHALL hiển thị placeholder "Đang phân tích..."

### Yêu cầu 6: Lọc tin tức theo mã cổ phiếu

**User Story:** Là người dùng, tôi muốn lọc tin tức theo mã cổ phiếu cụ thể, để chỉ xem tin tức liên quan đến cổ phiếu tôi quan tâm.

#### Tiêu chí chấp nhận

1. THE News_List_Screen SHALL cung cấp News_Filter cho phép người dùng nhập hoặc chọn Stock_Symbol
2. WHEN người dùng áp dụng News_Filter theo Stock_Symbol, THE News_ViewModel SHALL truy vấn News_Repository chỉ lấy các News_Article có Stock_Symbol đó trong danh sách stock_symbols
3. WHEN News_Filter đang hoạt động, THE News_List_Screen SHALL hiển thị chip lọc với tên Stock_Symbol và nút xóa bộ lọc
4. WHEN người dùng xóa News_Filter, THE News_List_Screen SHALL hiển thị lại toàn bộ danh sách tin tức
5. THE News_Filter SHALL hỗ trợ lọc đồng thời theo nhiều Stock_Symbol

### Yêu cầu 7: Tích hợp vào HomeScreen

**User Story:** Là người dùng, tôi muốn xem tin tức nổi bật ngay trên màn hình chính, để không bỏ lỡ thông tin quan trọng khi mở ứng dụng.

#### Tiêu chí chấp nhận

1. THE Home_Screen SHALL hiển thị section "Tin tức nổi bật" chứa tối đa 5 News_Article mới nhất đã được phân tích
2. WHEN người dùng nhấn vào một News_Article trong section "Tin tức nổi bật", THE StocKamp_App SHALL mở URL gốc trong trình duyệt hệ thống
3. WHEN người dùng nhấn "Xem tất cả" trong section "Tin tức nổi bật", THE StocKamp_App SHALL điều hướng đến News_List_Screen
4. THE Home_Screen SHALL hiển thị Sentiment_Label kèm màu sắc cho mỗi News_Article trong section "Tin tức nổi bật"
5. WHEN section "Tin tức nổi bật" đang tải, THE Home_Screen SHALL hiển thị skeleton placeholder thay vì vùng trống

### Yêu cầu 8: Tích hợp vào StockDetailScreen

**User Story:** Là người dùng, khi xem chi tiết một cổ phiếu, tôi muốn thấy tin tức liên quan đến mã đó, để đánh giá tác động của tin tức đến giá cổ phiếu.

#### Tiêu chí chấp nhận

1. THE Stock_Detail_Screen SHALL hiển thị section "Tin tức liên quan" chứa tối đa 10 News_Article có Stock_Symbol tương ứng với mã cổ phiếu đang xem
2. WHEN người dùng mở Stock_Detail_Screen cho một Stock_Symbol, THE News_ViewModel SHALL tự động lọc tin tức theo Stock_Symbol đó
3. WHEN người dùng nhấn vào một News_Article trong section "Tin tức liên quan", THE StocKamp_App SHALL mở URL gốc trong trình duyệt hệ thống
4. WHEN người dùng nhấn "Xem tất cả tin liên quan", THE StocKamp_App SHALL điều hướng đến News_List_Screen với News_Filter đã được áp dụng sẵn cho Stock_Symbol đó
5. IF không có News_Article nào liên quan đến Stock_Symbol, THE Stock_Detail_Screen SHALL hiển thị thông báo "Chưa có tin tức liên quan"

### Yêu cầu 9: Cache offline với Room Database

**User Story:** Là người dùng, tôi muốn đọc tin tức đã xem trước đó khi không có kết nối mạng, để ứng dụng vẫn hữu ích khi offline.

#### Tiêu chí chấp nhận

1. WHEN News_Repository tải tin tức từ Supabase thành công, THE News_Repository SHALL lưu kết quả vào News_Cache trong Room Database
2. WHEN người dùng yêu cầu danh sách tin tức, THE News_Repository SHALL ưu tiên trả về dữ liệu từ News_Cache trước
3. IF dữ liệu trong News_Cache cũ hơn 10 phút, THEN THE News_Repository SHALL tải dữ liệu mới từ Supabase và cập nhật News_Cache
4. WHILE StocKamp_App không có kết nối mạng, THE News_Repository SHALL trả về dữ liệu từ News_Cache mà không hiển thị lỗi mạng
5. THE News_Cache SHALL lưu tối đa 200 bài viết gần nhất; WHEN vượt quá giới hạn, THE News_Cache SHALL xóa các bài viết cũ nhất
6. THE News_Cache SHALL lưu trữ đầy đủ các trường của News_Article bao gồm Sentiment_Score để hiển thị offline

### Yêu cầu 10: Cập nhật realtime qua Supabase Realtime

**User Story:** Là người dùng, tôi muốn nhận tin tức mới ngay lập tức khi có bài viết mới được phân tích, để luôn cập nhật thông tin thị trường theo thời gian thực.

#### Tiêu chí chấp nhận

1. WHEN StocKamp_App đang chạy và có kết nối mạng, THE News_Repository SHALL đăng ký kênh Supabase_Realtime cho bảng news_articles
2. WHEN một News_Article mới với status = `analyzed` được chèn vào bảng news_articles, THE News_Repository SHALL nhận sự kiện realtime và cập nhật News_Cache trong vòng 3 giây
3. WHEN News_Cache được cập nhật qua Supabase_Realtime, THE News_ViewModel SHALL tự động làm mới danh sách hiển thị mà không cần người dùng thao tác
4. WHEN StocKamp_App chuyển sang nền (background), THE News_Repository SHALL hủy đăng ký kênh Supabase_Realtime
5. WHEN StocKamp_App quay lại foreground, THE News_Repository SHALL đăng ký lại kênh Supabase_Realtime và đồng bộ dữ liệu bị bỏ lỡ

### Yêu cầu 11: Phân trang và hiệu năng tải dữ liệu

**User Story:** Là người dùng, tôi muốn danh sách tin tức tải nhanh và mượt mà, để trải nghiệm đọc tin không bị gián đoạn.

#### Tiêu chí chấp nhận

1. THE News_Repository SHALL tải tin tức theo trang, mỗi trang 20 bài viết
2. WHEN News_List_Screen lần đầu hiển thị, THE News_ViewModel SHALL tải trang đầu tiên trong vòng 2 giây khi có kết nối mạng
3. THE News_ViewModel SHALL expose trạng thái UI qua StateFlow bao gồm: danh sách tin tức, trạng thái tải, lỗi, và trạng thái có thêm dữ liệu hay không
4. THE News_Repository SHALL sử dụng Paging 3 library để quản lý phân trang và cache hiệu quả
5. WHEN người dùng kéo xuống để làm mới (pull-to-refresh), THE News_ViewModel SHALL tải lại trang đầu tiên từ Supabase

### Yêu cầu 12: Phân tích dữ liệu và định dạng

**User Story:** Là nhà phát triển, tôi muốn dữ liệu tin tức được parse và validate chính xác, để tránh lỗi hiển thị và đảm bảo tính nhất quán.

#### Tiêu chí chấp nhận

1. WHEN dữ liệu News_Article được nhận từ Supabase, THE News_Repository SHALL parse JSON thành domain model NewsArticle
2. THE News_Repository SHALL validate rằng các trường bắt buộc (id, title, url, published_at) không null hoặc rỗng
3. IF dữ liệu JSON không hợp lệ hoặc thiếu trường bắt buộc, THEN THE News_Repository SHALL ghi log lỗi và bỏ qua bản ghi đó
4. THE News_Repository SHALL định dạng published_at từ ISO 8601 UTC sang thời gian địa phương Việt Nam (UTC+7) để hiển thị
5. FOR ALL bản ghi NewsArticle hợp lệ, việc parse từ JSON rồi serialize lại thành JSON rồi parse lại SHALL tạo ra đối tượng tương đương (round-trip property)
