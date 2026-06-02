Phần 1: Cấu trúc Đồ thị (Graph Schema) Chứng khoán VN
Bạn sẽ sử dụng cấu trúc này làm "kim chỉ nam" cho Database (khuyên dùng Neo4j).

1. Các Node (Đỉnh - Thực thể)
Company (Công ty Cổ phần)

Thuộc tính: ticker (VD: HPG, FPT), name, exchange (HOSE/HNX/UPCOM), market_cap, current_price.

Industry (Ngành nghề)

Thuộc tính: icb_code (Mã ngành ICB), name (VD: Bất động sản, Thép).

Person/Organization (Cá nhân/Tổ chức liên quan)

Thuộc tính: name (VD: Phạm Nhật Vượng, Dragon Capital), type (Individual, Fund, Enterprise).

MacroEvent (Sự kiện/Tin tức vĩ mô)

Thuộc tính: event_id, headline, date, sentiment_score (-1.0 đến 1.0).

2. Các Edge (Cạnh - Mối quan hệ)
(Company)-[:BELONGS_TO]->(Industry): Công ty thuộc ngành nào.

(Company)-[:COMPETES_WITH]->(Company): Cạnh tranh (Cùng ngành).

(Person/Organization)-[:OWNS {percent: float}]->(Company): Sở hữu cổ phần (Ban lãnh đạo, Quỹ đầu tư lớn).

(Company)-[:SUPPLIES_TO {weight: float}]->(Company): Nằm trong chuỗi cung ứng (VD: Khai thác than -> Sản xuất thép).

(Company)-[:PARTNERS_WITH]->(Company): Đối tác chiến lược/Ký kết hợp đồng.

(MacroEvent)-[:IMPACTS {impact_level: string}]->(Company/Industry): Tin tức tác động đến mã cổ phiếu hoặc toàn ngành.

Phần 2: Hướng dẫn Quy trình triển khai trên AI IDE
Bí quyết để dùng AI IDE hiệu quả là Context (Ngữ cảnh). Đừng bắt AI viết toàn bộ hệ thống trong một câu lệnh. Hãy tạo một file tên là .cursorrules (nếu dùng Cursor) hoặc một file CONTEXT.md trong thư mục gốc của project để AI luôn đọc nó trước khi code.

Bước 1: Thiết lập Ngữ cảnh Dự án (Context Setup)
Hãy tạo file CONTEXT.md và dán đoạn text sau vào. Đây sẽ là "bộ não" cho AI IDE của bạn:

Markdown
# Project Context: StocKamp Backend
- Role: Python Backend & AI Engine for a stock tracking mobile app.
- Tech Stack: FastAPI (Python), Neo4j (Graph Database), PyTorch Geometric (GNN), vnstock3 (Data crawler).
- Goal: Build a Market Knowledge Graph to analyze supply chains, relationships, and predict impacts on VN stock tickers (HOSE, HNX, UPCOM).
- Graph Schema:
  - Nodes: Company, Industry, Person, MacroEvent.
  - Edges: BELONGS_TO, COMPETES_WITH, OWNS, SUPPLIES_TO, PARTNERS_WITH, IMPACTS.
- Code Style: Clean code, modular, use async/await for FastAPI, provide inline comments.
Bước 2: Khởi tạo Database & Kết nối (Prompt cho AI IDE)
Mở cửa sổ Chat của AI IDE (nhấn Cmd/Ctrl + L hoặc mở Composer) và nhập prompt:

Prompt 1: "Đọc file CONTEXT.md. Hãy viết cho tôi file docker-compose.yml để chạy Neo4j database ở local. Sau đó, viết một module Python database/neo4j_client.py sử dụng thư viện neo4j chuẩn của Python để kết nối. Viết sẵn các hàm cơ bản: create_node, create_edge, và close_connection. Dùng class và Singleton pattern."

Bước 3: Viết Script Cào dữ liệu Cơ bản (Data Ingestion)
Sau khi có kết nối DB, bạn cần đổ data các mã cổ phiếu và ngành nghề vào trước. Thư viện vnstock3 cực kỳ mạnh cho việc này.

Prompt 2: "Tiếp tục project. Hãy tạo một file workers/ingest_companies.py. Sử dụng thư viện vnstock3 để lấy danh sách tất cả các mã cổ phiếu (ticker) trên HOSE, HNX, UPCOM kèm theo thông tin ngành nghề (ICB code) của chúng. Viết logic duyệt qua danh sách này, sử dụng neo4j_client.py vừa tạo để insert các node Company, node Industry và tạo luôn mối quan hệ BELONGS_TO giữa chúng."

Bước 4: Khởi tạo Thuật toán Thống kê (Tìm Quan Hệ Ngầm)
Để làm cho đồ thị có thêm mối quan hệ mà chưa cần đụng tới AI NLP đọc báo, hãy tạo quan hệ tương quan giá.

Prompt 3: "Tạo file workers/calc_correlation.py. Viết logic lấy lịch sử giá đóng cửa 6 tháng gần nhất của các mã trong rổ VN30 (có thể mock data hoặc dùng vnstock3). Sử dụng pandas và numpy để tính ma trận tương quan (Pearson Correlation). Nếu hệ số tương quan giữa 2 mã > 0.85, hãy tự động insert một mối quan hệ [:CORRELATED_WITH {score: float}] vào Neo4j giữa 2 node Company đó."

Bước 5: Xây dựng GNN Model bằng PyTorch Geometric (Trùm cuối)
Khi đồ thị đã có hình hài, đây là lúc bạn yêu cầu AI viết model PyTorch.

Prompt 4: "Bây giờ chuyển sang phần AI. Tạo thư mục ml_models/gnn/. Viết một script Python sử dụng thư viện torch_geometric. Viết một class StockGraphSAGE(torch.nn.Module) chứa 2 lớp SAGEConv.
Mục tiêu của model là Node Regression: Dự đoán % thay đổi giá (biến y) dựa trên các node features và cấu trúc đồ thị. Viết thêm một hàm load_data_from_neo4j() để query cấu trúc đồ thị hiện tại từ DB chuyển thành object Data (edge_index, x, y) của PyTorch Geometric."

Bước 6: Đưa thành API cho App Kotlin gọi
Cuối cùng, gói gọn mọi thứ lại bằng FastAPI.

Prompt 5: "Tạo file main.py dùng FastAPI. Cấu hình 1 endpoint GET /api/v1/graph/impact?ticker={ticker_name}. Khi User gọi API này, backend sẽ query Neo4j để lấy ra danh sách các công ty có liên kết (bán kính 2 hops) với ticker được truyền vào, trả về JSON format chuẩn để Mobile App có thể hiển thị dưới dạng danh sách cảnh báo chuỗi cung ứng."