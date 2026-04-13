"""Test various data sources for VN stocks — run this before committing to a source."""
import requests
import time
from datetime import date, timedelta

END = date.today()
START = END - timedelta(days=365)
SYMBOLS = ['ACB', 'VCB', 'BID', 'CTG', 'MBB', 'TCB', 'VPB', 'HPG', 'VNM', 'FPT',
           'SSI', 'HDB', 'STB', 'EIB', 'SHB', 'MSN', 'VIC', 'VHM', 'GAS', 'PLX']

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'Accept': 'application/json',
}

def test_source(name, fetch_fn):
    print(f"\n{'='*50}")
    print(f"Testing: {name}")
    ok, fail = 0, 0
    t0 = time.time()
    for sym in SYMBOLS[:5]:  # test 5 symbols first
        try:
            rows = fetch_fn(sym)
            if rows and len(rows) > 0:
                print(f"  OK {sym}: {len(rows)} rows")
                ok += 1
            else:
                print(f"  EMPTY {sym}")
                fail += 1
        except Exception as e:
            print(f"  FAIL {sym}: {str(e)[:80]}")
            fail += 1
        time.sleep(1)
    elapsed = time.time() - t0
    print(f"Result: {ok}/5 ok in {elapsed:.1f}s")
    return ok == 5

# ── Source 1: TCBS direct API ──────────────────────────────────────────────
def fetch_tcbs(symbol):
    url = f"https://apipubaws.tcbs.com.vn/stock-insight/v2/stock/bars-long-term"
    params = {
        "ticker": symbol,
        "type": "stock",
        "resolution": "D",
        "from": int(time.mktime(START.timetuple())),
        "to": int(time.mktime(END.timetuple())),
    }
    r = requests.get(url, params=params, headers=HEADERS, timeout=15)
    r.raise_for_status()
    data = r.json()
    return data.get("data", [])

# ── Source 2: SSI iBoard ───────────────────────────────────────────────────
def fetch_ssi(symbol):
    url = "https://iboard-query.ssi.com.vn/v2/stock/bars"
    params = {
        "symbol": symbol,
        "resolution": "D",
        "from": int(time.mktime(START.timetuple())),
        "to": int(time.mktime(END.timetuple())),
    }
    r = requests.get(url, params=params, headers={**HEADERS, "Referer": "https://iboard.ssi.com.vn"}, timeout=15)
    r.raise_for_status()
    data = r.json()
    return data.get("data", data.get("t", []))

# ── Source 3: VietStock ────────────────────────────────────────────────────
def fetch_vietstock(symbol):
    url = "https://finance.vietstock.vn/data/gethistoricaldata"
    payload = {
        "Symbol": symbol,
        "StartDate": str(START),
        "EndDate": str(END),
        "Resolution": "D",
    }
    r = requests.post(url, json=payload, headers={**HEADERS, "Referer": "https://finance.vietstock.vn"}, timeout=15)
    r.raise_for_status()
    return r.json()

# ── Source 4: Wifeed / Entrade ─────────────────────────────────────────────
def fetch_entrade(symbol):
    url = f"https://services.entrade.com.vn/chart-api/v2/ohlcs/stock"
    params = {
        "from": START.strftime("%Y-%m-%d"),
        "to": END.strftime("%Y-%m-%d"),
        "symbol": symbol,
        "resolution": "D",
    }
    r = requests.get(url, params=params, headers=HEADERS, timeout=15)
    r.raise_for_status()
    data = r.json()
    return data.get("t", [])

# ── Source 5: DNSE (Pinetree) ──────────────────────────────────────────────
def fetch_dnse(symbol):
    url = f"https://api.dnse.com.vn/chart-api/v2/ohlcs/stock"
    params = {
        "from": START.strftime("%Y-%m-%d"),
        "to": END.strftime("%Y-%m-%d"),
        "symbol": symbol,
        "resolution": "D",
    }
    r = requests.get(url, params=params, headers=HEADERS, timeout=15)
    r.raise_for_status()
    data = r.json()
    return data.get("t", [])

# ── Source 6: BSC (BIDV Securities) ───────────────────────────────────────
def fetch_bsc(symbol):
    url = "https://online.bsc.com.vn/api/chart/history"
    params = {
        "symbol": symbol,
        "resolution": "D",
        "from": int(time.mktime(START.timetuple())),
        "to": int(time.mktime(END.timetuple())),
    }
    r = requests.get(url, params=params, headers=HEADERS, timeout=15)
    r.raise_for_status()
    data = r.json()
    return data.get("t", [])

if __name__ == "__main__":
    results = {}
    for name, fn in [
        ("TCBS direct", fetch_tcbs),
        ("SSI iBoard", fetch_ssi),
        ("VietStock", fetch_vietstock),
        ("Entrade/Wifeed", fetch_entrade),
        ("DNSE/Pinetree", fetch_dnse),
        ("BSC", fetch_bsc),
    ]:
        try:
            results[name] = test_source(name, fn)
        except Exception as e:
            print(f"  Source crashed: {e}")
            results[name] = False

    print("\n" + "="*50)
    print("SUMMARY:")
    for name, ok in results.items():
        print(f"  {'✓' if ok else '✗'} {name}")
