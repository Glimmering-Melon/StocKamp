# StocKamp


#### A. Nhóm hiển thị & Tương tác (Kotlin Native)

* **Market overview + Charts + Heatmap:**
  * *Công nghệ:* Kotlin (Jetpack Compose).
  * *Thư viện Charts:* Dùng **MPAndroidChart** hoặc **Vico** (nhẹ, modern, hỗ trợ Compose).
  * *Heatmap:* Canvas vẽ tay hoặc dùng thư viện Webview nhúng nếu quá phức tạp.
* **Home Screen Widgets:**
  * *Công nghệ:*  **Jetpack Glance** . Đây là framework mới nhất của Google giúp viết Widget bằng style của Compose, cực kỳ đồng bộ với app chính.
* **Trading Journal / Watchlist:**
  * *Công nghệ:* Kotlin + Room Database (cache local để xem offline) + Supabase sync.

#### B. Nhóm Quản lý User & Cộng đồng (Supabase gánh tốt)

* **Auth, Watchlist, Trading Journal, Community Posting:**
  * *Công nghệ:*  **Supabase (PostgreSQL)** .
  * *Lý do:* Dữ liệu người dùng, bài đăng, comment là dạng quan hệ (Relational), Postgres xử lý cực tốt. Tính năng Realtime của Supabase giúp chat/comment nhảy ngay lập tức.

#### C. Nhóm AI & Xử lý dữ liệu phức tạp (Cần Python Backend)

*Đây là phần Supabase không làm được (hoặc làm rất yếu). Bạn cần dựng một server riêng (ví dụ dùng FastAPI hoặc Flask).*

* **News Aggregator + Sentiment Analysis:**
  * *Vấn đề:* Cần crawl tin tức từ nhiều nguồn, sau đó chạy model NLP (BERT, FinBERT) để phân tích cảm xúc (Tích cực/Tiêu cực).
  * *Giải pháp:* Python Service chạy background job để crawl tin -> đẩy vào model NLP -> lưu kết quả vào Database.
* **Predicting Stock Price (ML Models):**
  * *Công nghệ:* Python (Scikit-learn, TensorFlow/PyTorch, LSTM). Server cần GPU hoặc CPU mạnh để training/inference.
* **Smart Portfolio Clustering:**
  * *Công nghệ:* Python (Scikit-learn K-Means/DBSCAN).
* **Market Knowledge Graph & Supply Chain (GNN):**
  * *Công nghệ:* Đây là phần khó nhất. Bạn cần biểu diễn dữ liệu dạng đồ thị (Graph).
  * *Database:* PostgreSQL có thể dùng tạm (với Recursive query), nhưng tốt nhất nên dùng **Neo4j** hoặc **AWS Neptune** nếu dữ liệu lớn.
  * *AI:* Dùng **PyTorch Geometric** hoặc **DGL** để chạy các model GNN.

---

### 2. Kiến trúc Hệ thống Đề xuất (Hybrid Architecture)

Bạn sẽ không dùng mô hình Client-Server đơn thuần, mà là mô hình  **Microservices lai** :

1. **Mobile App (Kotlin):**
   * Gọi trực tiếp **Supabase** để: Đăng nhập, Lấy Watchlist, Post bài cộng đồng, Lưu Trading Journal.
   * Gọi sang **Python Server** để: Lấy dự báo giá, Xem phân tích Sentiment, Chạy giả lập What-if.
   * Gọi **Socket/Streaming** (từ Python hoặc 3rd Party Data Provider) để: Nhảy số giá chứng khoán Real-time.
2. **Supabase (User DB):**
   * Lưu: User Profile, Saved Watchlists, User Posts, Trading Logs.
   * Lưu: Kết quả phân tích (được Python Server ghi vào) để App đọc ra cho nhanh.
3. **Python AI Server (FastAPI - The "Brain"):**
   * **Worker 1 (Data Crawler):** Liên tục lấy giá, lấy tin tức -> Lưu vào DB.
   * **Worker 2 (AI Processor):** Định kỳ chạy model Sentiment, Update Knowledge Graph, Re-train model dự báo.
   * **API Layer:** Cung cấp API cho App để chạy tính năng "What-if Simulator" (tính toán tức thì).

---

### 3. Vấn đề "đau đầu" nhất cần lưu ý

Với danh sách tính năng này, thách thức lớn nhất không phải là code App, mà là  **DỮ LIỆU (Data)** .

* **Nguồn dữ liệu chứng khoán (Data Source):**
  * Bạn lấy giá ở đâu? (Fireant, Vietstock, hay API quốc tế như Alpha Vantage?).
  * Dữ liệu Supply Chain (Chuỗi cung ứng) lấy ở đâu để vẽ Graph? Đây là dữ liệu rất khó kiếm và đắt tiền.
* **Chi phí Server AI:**
  * Chạy model NLP và GNN tốn tài nguyên. Nếu deploy lên Cloud (AWS/GCP) sẽ khá tốn kém.
  * *Tip:* Giai đoạn đầu, có thể dùng các API có sẵn (OpenAI API, Hugging Face Inference API) thay vì tự host model để tiết kiệm công sức MLOps.

### 4. Lộ trình triển khai (Roadmap) gợi ý

Đừng làm hết cùng lúc. Hãy chia giai đoạn:

* **Giai đoạn 1 (MVP - Core):**
  * Kotlin + Supabase.
  * Tính năng: Market Overview, Watchlist, Charts, Trading Journal.
  * *Mục tiêu:* Có App chạy mượt, user dùng để theo dõi danh mục.
* **Giai đoạn 2 (Intelligence):**
  * Dựng Python Backend.
  * Tính năng: Price Alerts (Worker check giá), News + Basic Sentiment (dùng API có sẵn).
  * Widget đưa ra màn hình chính.
* **Giai đoạn 3 (Advanced AI):**
  * Tích hợp GNN, Supply Chain Graph, Smart Clustering.
  * Đây là lúc bạn cần đầu tư sâu vào Data Engineering.

**Kết luận:** Combo **Kotlin + Supabase + Python (FastAPI)** là "kiềng 3 chân" vững chắc nhất cho dự án này. Đừng cố nhồi nhét logic AI vào Supabase Edge Functions hay Mobile App, nó sẽ làm app bị chậm và khó bảo trì.

-
