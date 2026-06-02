import logging
import os
import sys
import threading
from contextlib import asynccontextmanager
from datetime import date, timedelta

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from dotenv import load_dotenv
from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from crawler.news_crawler import NewsCrawler
from processor.ai_processor import AIProcessor

load_dotenv()

# Allow importing market_data_service from parent directory
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

logger = logging.getLogger(__name__)

SUPABASE_URL = os.getenv("SUPABASE_URL", "")
SUPABASE_SERVICE_KEY = os.getenv("SUPABASE_SERVICE_KEY", "")

scheduler = AsyncIOScheduler()

# Market crawl background state
_market_state = {"running": False, "progress": "", "records_upserted": 0}


def _play_cat_animation():
    """Play ASCII cat animation on startup using cat.py logic."""
    try:
        # Look for cat.mp4 relative to this file or in parent directory
        base_dir = os.path.dirname(os.path.abspath(__file__))
        video_path = os.path.join(base_dir, "..", "cat.mp4")
        cat_py = os.path.join(base_dir, "..", "cat.py")

        if not os.path.exists(video_path):
            return

        # Dynamically load VideoToAscii from cat.py
        import importlib.util
        spec = importlib.util.spec_from_file_location("cat", cat_py)
        cat_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cat_module)

        converter = cat_module.VideoToAscii(video_path, width=100)
        converter.display_video_ascii()
    except Exception:
        pass  # Animation is optional — never block server startup


# ── Scheduled jobs ────────────────────────────────────────────────────────

async def crawl_job():
    if not SUPABASE_URL or not SUPABASE_SERVICE_KEY:
        return {"error": "Supabase credentials not configured"}
    crawler = NewsCrawler(SUPABASE_URL, SUPABASE_SERVICE_KEY)
    stats = await crawler.run()
    logger.info("News crawl complete: %s", stats)
    return stats


async def process_job():
    if not SUPABASE_URL or not SUPABASE_SERVICE_KEY:
        return {"error": "Supabase credentials not configured"}
    processor = AIProcessor(SUPABASE_URL, SUPABASE_SERVICE_KEY)
    stats = await processor.run()
    logger.info("AI processing complete: %s", stats)
    return stats


def _run_market_crawl(backfill: bool):
    """Run market data crawl in a background thread."""
    _market_state["running"] = True
    _market_state["progress"] = "Đang khởi động..."
    _market_state["records_upserted"] = 0
    try:
        from market_data_service.config.logging_config import setup_logging
        from market_data_service.config import settings
        from market_data_service.crawler.vnstock_crawler import VnstockCrawler
        from market_data_service.writer.supabase_writer import SupabaseWriter
        from market_data_service.models.ohlcv import RunStats
        from datetime import datetime, timezone

        setup_logging()
        crawler = VnstockCrawler()
        writer = SupabaseWriter(
            supabase_url=settings.SUPABASE_URL,
            supabase_key=settings.SUPABASE_SERVICE_KEY,
        )

        today = date.today()
        start_date = today - timedelta(days=365 if backfill else 7)

        _market_state["progress"] = "Đang lấy danh sách symbols..."
        symbols = crawler.fetch_symbols()
        writer.upsert_symbols(symbols)

        _market_state["progress"] = f"Đang crawl {len(symbols)} symbols..."
        start_time = datetime.now(timezone.utc)

        records, ok, fail = crawler.fetch_ohlcv_bulk(
            symbols, ["1D", "1W", "1M"], start_date, today
        )

        _market_state["progress"] = f"Đang upsert {len(records):,} records..."
        count = writer.upsert_ohlcv(records) if records else 0

        end_time = datetime.now(timezone.utc)
        writer.update_crawl_metadata(RunStats(
            start_time=start_time, end_time=end_time,
            symbols_attempted=len(symbols), symbols_succeeded=ok,
            symbols_failed=fail, records_upserted=count,
        ))

        _market_state["records_upserted"] = count
        _market_state["progress"] = f"Hoàn thành! {count:,} records"
        logger.info("Market crawl done: %d records", count)
    except Exception as e:
        _market_state["progress"] = f"Lỗi: {e}"
        logger.error("Market crawl failed: %s", e)
    finally:
        _market_state["running"] = False


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Play ASCII cat animation in a foreground thread before starting services
    cat_thread = threading.Thread(target=_play_cat_animation, daemon=True)
    cat_thread.start()
    cat_thread.join()  # Wait for animation to finish before starting scheduler

    scheduler.add_job(crawl_job, "interval", minutes=15, id="news_crawler")
    scheduler.add_job(process_job, "interval", minutes=15, id="ai_processor", seconds=300)
    scheduler.start()
    yield
    scheduler.shutdown()


app = FastAPI(title="StocKamp Dashboard", lifespan=lifespan)

# Serve static files (dashboard HTML)
_static_dir = os.path.join(os.path.dirname(__file__), "static")
if os.path.isdir(_static_dir):
    app.mount("/static", StaticFiles(directory=_static_dir), name="static")


# ── Routes ────────────────────────────────────────────────────────────────

@app.get("/", include_in_schema=False)
async def dashboard():
    return FileResponse(os.path.join(_static_dir, "index.html"))


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/stats")
async def stats():
    if not SUPABASE_URL or not SUPABASE_SERVICE_KEY:
        return {"error": "Supabase credentials not configured"}
    from supabase import create_client
    client = create_client(SUPABASE_URL, SUPABASE_SERVICE_KEY)
    total = client.table("news_articles").select("id", count="exact").execute()
    analyzed = client.table("news_articles").select("id", count="exact").eq("status", "analyzed").execute()
    pending = client.table("news_articles").select("id", count="exact").eq("status", "pending_analysis").execute()
    return {"total": total.count, "analyzed": analyzed.count, "pending": pending.count}


@app.post("/crawl")
async def trigger_crawl():
    result = await crawl_job()
    return {"status": "ok", "result": result}


@app.post("/process")
async def trigger_process():
    result = await process_job()
    return {"status": "ok", "result": result}


# ── Market Data Routes ────────────────────────────────────────────────────

@app.get("/market/stats")
async def market_stats():
    if not SUPABASE_URL or not SUPABASE_SERVICE_KEY:
        return {"error": "Supabase credentials not configured"}
    from supabase import create_client
    client = create_client(SUPABASE_URL, SUPABASE_SERVICE_KEY)
    symbols = client.table("stock_symbols").select("symbol", count="exact").execute()
    records = client.table("stock_prices").select("symbol", count="exact").execute()
    meta = client.table("crawl_metadata").select("value").eq("key", "last_crawled_at").execute()
    last_crawled = meta.data[0]["value"] if meta.data else None
    return {
        "symbols": symbols.count,
        "records": records.count,
        "last_crawled_at": last_crawled,
    }


@app.post("/market/crawl")
async def trigger_market_crawl(backfill: bool = False):
    if _market_state["running"]:
        return {"status": "already_running", "progress": _market_state["progress"]}
    thread = threading.Thread(target=_run_market_crawl, args=(backfill,), daemon=True)
    thread.start()
    return {"status": "started", "backfill": backfill}


@app.get("/market/status")
async def market_status():
    return {
        "running": _market_state["running"],
        "progress": _market_state["progress"],
        "records_upserted": _market_state["records_upserted"],
    }
