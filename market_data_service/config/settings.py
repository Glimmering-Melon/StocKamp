import os
from dotenv import load_dotenv

load_dotenv()

_REQUIRED_VARS = ["SUPABASE_URL", "SUPABASE_SERVICE_KEY"]

_missing = [var for var in _REQUIRED_VARS if not os.getenv(var)]
if _missing:
    print(f"Missing required environment variables: {', '.join(_missing)}")
    raise SystemExit(1)

SUPABASE_URL: str = os.environ["SUPABASE_URL"]
SUPABASE_SERVICE_KEY: str = os.environ["SUPABASE_SERVICE_KEY"]

# Optional — community API key increases rate limit from 20 to 60 req/min
VNSTOCK_API_KEY: str | None = os.getenv("VNSTOCK_API_KEY") or None
