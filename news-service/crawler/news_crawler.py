"""News_Crawler worker — crawls RSS and HTML sources, extracts stock symbols, saves to Supabase."""

import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Literal, Optional
from xml.etree import ElementTree

import httpx
from bs4 import BeautifulSoup
from supabase import create_client, Client

from models.article import ParsedArticle, RawArticle

logger = logging.getLogger(__name__)

# Regex for Vietnamese stock symbols: 2-5 uppercase letters (VN30/HOSE listed)
_SYMBOL_RE = re.compile(r"\b([A-Z]{2,5})\b")

# Common Vietnamese words that look like symbols but aren't — basic exclusion list
_EXCLUDED_TOKENS = {
    "VN", "HN", "TP", "HCM", "USD", "VND", "GDP", "CPI", "IPO", "ETF",
    "CEO", "CFO", "COO", "BOD", "AGM", "EPS", "PE", "PB", "ROE", "ROA",
    "HOSE", "HNX", "UPCOM", "VN30", "VN100", "VNINDEX",
    # English common words
    "US", "UK", "EU", "FED", "SEC", "NYSE", "NASDAQ", "SP", "DOW",
    "AI", "IT", "HR", "PR", "IR", "QA", "API", "SaaS", "B2B", "B2C",
    "Q1", "Q2", "Q3", "Q4", "YOY", "QOQ", "MOM", "YTD",
}


@dataclass
class NewsSource:
    url: str
    source_name: str
    parser_type: Literal["rss", "html"]
    timeout: int = 10
    # Optional CSS selectors for HTML scraping
    article_selector: str = ""
    title_selector: str = ""
    summary_selector: str = ""
    link_selector: str = ""
    date_selector: str = ""


# Default sources
DEFAULT_SOURCES: list[NewsSource] = [
    # ===== Nguồn Việt Nam =====
    NewsSource(url="https://vneconomy.vn/chung-khoan.rss", source_name="VnEconomy", parser_type="rss"),
    NewsSource(url="https://vneconomy.vn/tai-chinh.rss", source_name="VnEconomy-TaiChinh", parser_type="rss"),
    NewsSource(url="https://vneconomy.vn/doanh-nghiep.rss", source_name="VnEconomy-DoanhNghiep", parser_type="rss"),
    NewsSource(url="https://baodautu.vn/chung-khoan.rss", source_name="BaoDauTu", parser_type="rss"),
    NewsSource(url="https://baodautu.vn/tai-chinh.rss", source_name="BaoDauTu-TaiChinh", parser_type="rss"),
    NewsSource(url="https://baodautu.vn/doanh-nghiep.rss", source_name="BaoDauTu-DoanhNghiep", parser_type="rss"),
    NewsSource(
        url="https://vietstock.vn/736/chung-khoan.htm",
        source_name="Vietstock",
        parser_type="html",
        article_selector="div.news-item, article.news-item, div.item-news",
        title_selector="h3 a, h4 a, .title a",
        summary_selector="p.sapo, p.summary, .sapo",
        link_selector="h3 a, h4 a, .title a",
        date_selector=".time, .date, time",
    ),
    # ===== Nguồn Quốc tế =====
    NewsSource(url="https://feeds.marketwatch.com/marketwatch/topstories/", source_name="MarketWatch", parser_type="rss"),
    NewsSource(url="https://www.cnbc.com/id/10000664/device/rss/rss.html", source_name="CNBC-Markets", parser_type="rss"),
    NewsSource(url="https://www.cnbc.com/id/10001147/device/rss/rss.html", source_name="CNBC-Finance", parser_type="rss"),
    NewsSource(url="https://feeds.bloomberg.com/markets/news.rss", source_name="Bloomberg-Markets", parser_type="rss"),
]


class NewsCrawler:
    def __init__(self, supabase_url: str, supabase_key: str,
                 sources: Optional[list[NewsSource]] = None):
        self.sources: list[NewsSource] = sources or DEFAULT_SOURCES
        self._supabase: Client = create_client(supabase_url, supabase_key)

    # Public entry point

    async def run(self) -> dict:
        """Crawl all sources and save new articles. Returns crawl stats."""
        total_fetched = 0
        total_saved = 0
        errors: list[str] = []

        async with httpx.AsyncClient() as client:
            for source in self.sources:
                try:
                    articles = await self.fetch_source(source, client)
                    total_fetched += len(articles)
                    saved = await self.save_articles(articles)
                    total_saved += saved
                    logger.info("Source %s: fetched=%d saved=%d",
                                source.source_name, len(articles), saved)
                except Exception as exc:
                    msg = f"{source.source_name}: {exc}"
                    logger.error("Crawler error — %s", msg)
                    errors.append(msg)

        return {
            "total_fetched": total_fetched,
            "total_saved": total_saved,
            "errors": errors,
        }

    # Fetch

    async def fetch_source(self, source: NewsSource,
                           client: Optional[httpx.AsyncClient] = None) -> list[ParsedArticle]:
        """Fetch a single source and return parsed articles."""
        own_client = client is None
        if own_client:
            client = httpx.AsyncClient()

        try:
            response = await client.get(
                source.url,
                timeout=source.timeout,
                follow_redirects=True,
                headers={"User-Agent": "StocKamp-NewsCrawler/1.0"},
            )
            response.raise_for_status()
            content = response.text

            if source.parser_type == "rss":
                return self._parse_rss(source, content)
            else:
                return self._parse_html(source, content)

        except httpx.TimeoutException:
            logger.error("Timeout fetching source %s (url=%s)", source.source_name, source.url)
            return []
        except httpx.HTTPStatusError as exc:
            logger.error("HTTP %d for source %s", exc.response.status_code, source.source_name)
            return []
        except Exception as exc:
            logger.error("Unexpected error fetching %s: %s", source.source_name, exc)
            return []
        finally:
            if own_client:
                await client.aclose()

    # Parsers

    def _parse_rss(self, source: NewsSource, content: str) -> list[ParsedArticle]:
        """Parse RSS/Atom feed using xml.etree (no external feedparser needed)."""
        articles: list[ParsedArticle] = []
        try:
            # Strip BOM and leading whitespace that can break XML parser
            content = content.lstrip('\ufeff').strip()
            root = ElementTree.fromstring(content)
        except ElementTree.ParseError as exc:
            logger.error("RSS parse error for %s: %s", source.source_name, exc)
            return articles

        # RSS 2.0 & Atom
        ns = {"atom": "http://www.w3.org/2005/Atom"}
        items = root.findall(".//item") or root.findall(".//atom:entry", ns)

        for item in items:
            try:
                title = _text(item, ["title", "atom:title"], ns)
                link = _text(item, ["link", "atom:link", "guid"], ns)
                # Atom <link>=attribute
                if not link:
                    link_el = item.find("atom:link", ns)
                    if link_el is not None:
                        link = link_el.get("href", "")
                summary = _text(item, ["description", "summary", "atom:summary"], ns)
                pub_date = _text(item, ["pubDate", "published", "atom:published",
                                        "updated", "atom:updated"], ns)

                if not title or not link:
                    continue

                published_at = _parse_date(pub_date)
                symbols = self._extract_stock_symbols(f"{title} {summary or ''}")

                articles.append(ParsedArticle(
                    title=title.strip(),
                    url=link.strip(),
                    summary=summary.strip() if summary else None,
                    source_name=source.source_name,
                    published_at=published_at,
                    stock_symbols=symbols,
                ))
            except Exception as exc:
                logger.warning("Skipping RSS item from %s: %s", source.source_name, exc)

        return articles

    def _parse_html(self, source: NewsSource, content: str) -> list[ParsedArticle]:
        """Scrape HTML page using BeautifulSoup."""
        articles: list[ParsedArticle] = []
        try:
            soup = BeautifulSoup(content, "lxml")
        except Exception:
            soup = BeautifulSoup(content, "html.parser")

        # Try selector 
        containers = []
        for sel in source.article_selector.split(","):
            sel = sel.strip()
            if sel:
                containers = soup.select(sel)
                if containers:
                    break

        if not containers:
            # Fallback
            containers = soup.find_all("article") or soup.select("div[class*=news]")

        for container in containers[:100]:  # cap 100 
            try:
                title_el = _select_first(container, source.title_selector)
                link_el = _select_first(container, source.link_selector) or title_el
                summary_el = _select_first(container, source.summary_selector)
                date_el = _select_first(container, source.date_selector)

                title = title_el.get_text(strip=True) if title_el else ""
                link = ""
                if link_el:
                    link = link_el.get("href", "") or link_el.get_text(strip=True)
                summary = summary_el.get_text(strip=True) if summary_el else None
                date_str = date_el.get_text(strip=True) if date_el else None

                if not title or not link:
                    continue

                if link.startswith("/"):
                    from urllib.parse import urlparse
                    parsed = urlparse(source.url)
                    link = f"{parsed.scheme}://{parsed.netloc}{link}"

                published_at = _parse_date(date_str)
                symbols = self._extract_stock_symbols(f"{title} {summary or ''}")

                articles.append(ParsedArticle(
                    title=title,
                    url=link,
                    summary=summary,
                    source_name=source.source_name,
                    published_at=published_at,
                    stock_symbols=symbols,
                ))
            except Exception as exc:
                logger.warning("Skipping HTML item from %s: %s", source.source_name, exc)

        return articles

    # Stock symbol extraction

    def _extract_stock_symbols(self, text: str) -> list[str]:
        """Extract VN30/HOSE stock symbols (2-5 uppercase letters) from text."""
        if not text:
            return []
        candidates = _SYMBOL_RE.findall(text)
        seen: set[str] = set()
        result: list[str] = []
        for sym in candidates:
            if sym not in _EXCLUDED_TOKENS and sym not in seen:
                seen.add(sym)
                result.append(sym)
        return result

    # Persistence

    async def save_articles(self, articles: list[ParsedArticle]) -> int:
        """Upsert articles to Supabase. Returns number of new rows inserted."""
        if not articles:
            return 0

        rows = [
            {
                "title": a.title,
                "url": a.url,
                "summary": a.summary,
                "source_name": a.source_name,
                "published_at": a.published_at,
                "stock_symbols": a.stock_symbols,
                "status": "pending_analysis",
            }
            for a in articles
        ]

        try:
            response = (
                self._supabase.table("news_articles")
                .upsert(rows, on_conflict="url", ignore_duplicates=True)
                .execute()
            )
            saved = len(response.data) if response.data else 0
            return saved
        except Exception as exc:
            logger.error("Supabase upsert error: %s", exc)
            return 0


# Helpers

def _text(element: ElementTree.Element, tags: list[str],
          ns: dict) -> Optional[str]:
    """Return text of the first matching tag."""
    for tag in tags:
        el = element.find(tag, ns)
        if el is not None and el.text:
            return el.text
    return None


def _select_first(container, selector: str):
    """Try each comma-separated CSS selector and return first match."""
    if not selector:
        return None
    for sel in selector.split(","):
        sel = sel.strip()
        if sel:
            el = container.select_one(sel)
            if el:
                return el
    return None


def _parse_date(date_str: Optional[str]) -> str:
    """Parse various date formats and return ISO 8601 UTC string.
    Falls back to current UTC time if parsing fails."""
    if not date_str:
        return datetime.now(timezone.utc).isoformat()

    formats = [
        "%a, %d %b %Y %H:%M:%S %z",  
        "%a, %d %b %Y %H:%M:%S GMT",
        "%Y-%m-%dT%H:%M:%S%z",         
        "%Y-%m-%dT%H:%M:%SZ",
        "%Y-%m-%d %H:%M:%S",
        "%d/%m/%Y %H:%M",
        "%d-%m-%Y %H:%M",
    ]
    for fmt in formats:
        try:
            dt = datetime.strptime(date_str.strip(), fmt)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt.astimezone(timezone.utc).isoformat()
        except ValueError:
            continue

    logger.debug("Could not parse date '%s', using current time", date_str)
    return datetime.now(timezone.utc).isoformat()
