import logging
import os
from contextlib import asynccontextmanager

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from dotenv import load_dotenv
from fastapi import FastAPI

from crawler.news_crawler import NewsCrawler
from processor.ai_processor import AIProcessor

load_dotenv()

logger = logging.getLogger(__name__)

SUPABASE_URL = os.getenv("SUPABASE_URL", "")
SUPABASE_SERVICE_KEY = os.getenv("SUPABASE_SERVICE_KEY", "")

scheduler = AsyncIOScheduler()


async def crawl_job():
    """News_Crawler job — runs every 15 minutes via APScheduler."""
    if not SUPABASE_URL or not SUPABASE_SERVICE_KEY:
        logger.warning("Supabase credentials not configured, skipping crawl.")
        return {"error": "Supabase credentials not configured"}
    crawler = NewsCrawler(SUPABASE_URL, SUPABASE_SERVICE_KEY)
    stats = await crawler.run()
    logger.info("Crawl complete: %s", stats)
    return stats


async def process_job():
    """AI_Processor job — runs every 15 minutes (offset 5 min after crawler)."""
    if not SUPABASE_URL or not SUPABASE_SERVICE_KEY:
        logger.warning("Supabase credentials not configured, skipping AI processing.")
        return {"error": "Supabase credentials not configured"}
    processor = AIProcessor(SUPABASE_URL, SUPABASE_SERVICE_KEY)
    stats = await processor.run()
    logger.info("AI processing complete: %s", stats)
    return stats


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Register scheduled jobs
    scheduler.add_job(crawl_job, "interval", minutes=15, id="news_crawler")
    scheduler.add_job(process_job, "interval", minutes=15, id="ai_processor",
                      seconds=300)  # offset 5 minutes after crawler

    scheduler.start()
    yield
    scheduler.shutdown()


app = FastAPI(title="Stock News Service", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/stats")
async def stats():
    """Xem số lượng bài hiện có trong DB."""
    if not SUPABASE_URL or not SUPABASE_SERVICE_KEY:
        return {"error": "Supabase credentials not configured"}
    from supabase import create_client
    client = create_client(SUPABASE_URL, SUPABASE_SERVICE_KEY)
    total = client.table("news_articles").select("id", count="exact").execute()
    analyzed = client.table("news_articles").select("id", count="exact").eq("status", "analyzed").execute()
    pending = client.table("news_articles").select("id", count="exact").eq("status", "pending_analysis").execute()
    return {
        "total": total.count,
        "analyzed": analyzed.count,
        "pending": pending.count,
    }


@app.post("/crawl")
async def trigger_crawl():
    """Trigger News_Crawler thủ công."""
    stats = await crawl_job()
    return {"status": "ok", "result": stats}


@app.post("/process")
async def trigger_process():
    """Trigger AI_Processor thủ công."""
    stats = await process_job()
    return {"status": "ok", "result": stats}
