"""3-1단계: 시세·지표·뉴스 수집.

출처마다 실패할 수 있으므로 항목별로 '주 출처 → 예비 출처' 순서로 시도한다.
모든 시각은 한국 시각(KST) ISO 문자열로 저장한다.
"""
import csv
import io
import json
import os
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from urllib.parse import quote

import requests

KST = timezone(timedelta(hours=9))
ROOT = Path(__file__).resolve().parent.parent
UA = ("Mozilla/5.0 (Linux; Android 14; SM-S928N) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36")
TIMEOUT = 20

S = requests.Session()
S.headers.update({"User-Agent": UA, "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.8"})


def now_kst():
    return datetime.now(KST)


def iso(dt):
    return dt.astimezone(KST).isoformat(timespec="minutes") if dt else None


def num(x):
    """'1,234.5' / '-2.80' / '+0.3%' 같은 문자열을 숫자로."""
    if x is None:
        return None
    if isinstance(x, (int, float)):
        return float(x)
    s = str(x).replace(",", "").replace("%", "").replace("+", "").strip()
    try:
        return float(s)
    except ValueError:
        return None


def get(url, **kw):
    kw.setdefault("timeout", TIMEOUT)
    r = S.get(url, **kw)
    r.raise_for_status()
    return r


def first_ok(*fns):
    """여러 출처를 차례로 시도해 처음 성공한 결과를 돌려준다."""
    errors = []
    for fn in fns:
        try:
            return fn()
        except Exception as e:
            errors.append(f"{getattr(fn, '__name__', 'src')}: {type(e).__name__} {str(e)[:120]}")
    return {"error": " | ".join(errors)}


# ---------------- 출처별 함수 ----------------

def naver_kr(code):
    j = get(f"https://m.stock.naver.com/api/stock/{code}/basic").json()
    return {
        "price": num(j.get("closePrice")),
        "change": num(j.get("compareToPreviousClosePrice")),
        "change_pct": num(j.get("fluctuationsRatio")),
        "as_of": iso(datetime.fromisoformat(j["localTradedAt"])) if j.get("localTradedAt") else None,
        "market_status": j.get("marketStatus"),
        "source": "네이버",
    }


def naver_world(code):
    j = get(f"https://api.stock.naver.com/stock/{code}/basic").json()
    return {
        "price": num(j.get("closePrice")),
        "change": num(j.get("compareToPreviousClosePrice")),
        "change_pct": num(j.get("fluctuationsRatio")),
        "as_of": iso(datetime.fromisoformat(j["localTradedAt"])) if j.get("localTradedAt") else None,
        "market_status": j.get("marketStatus"),
        "source": "네이버",
    }


_cnbc_cache = {}


def cnbc_all(symbols):
    """CNBC 시세를 한 번에 받아 둔다."""
    url = ("https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol"
           f"?symbols={quote('|'.join(symbols))}&requestMethod=itv&noform=1&partnerId=2"
           "&fund=1&exthrs=1&output=json")
    q = get(url).json()["FormattedQuoteResult"]["FormattedQuote"]
    for x in q:
        _cnbc_cache[x.get("symbol")] = x


def cnbc(symbol):
    x = _cnbc_cache.get(symbol)
    if not x or num(x.get("last")) is None:
        raise RuntimeError(f"CNBC에 {symbol} 없음")
    t = x.get("last_time")
    as_of = None
    if t:
        try:
            as_of = iso(datetime.strptime(t, "%Y-%m-%dT%H:%M:%S.%f%z"))
        except ValueError:
            as_of = t
    return {
        "price": num(x.get("last")),
        "change": num(x.get("change")),
        "change_pct": num(x.get("change_pct")),
        "as_of": as_of,
        "market_status": x.get("curmktstatus"),
        "source": "CNBC",
    }


def cboe_vix():
    rows = get("https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv").text.strip().splitlines()
    last, prev = rows[-1].split(","), rows[-2].split(",")
    close, pclose = float(last[4]), float(prev[4])
    d = datetime.strptime(last[0], "%m/%d/%Y")
    return {"price": close, "change": round(close - pclose, 2),
            "change_pct": round((close / pclose - 1) * 100, 2),
            "as_of": d.strftime("%Y-%m-%d") + " (미국 종가)", "source": "CBOE"}


def fred(series):
    key = os.environ.get("FRED_API_KEY")
    if not key:
        raise RuntimeError("FRED_API_KEY 없음")
    base = "https://api.stlouisfed.org/fred"
    try:
        info = get(f"{base}/series", params={"series_id": series, "api_key": key,
                                             "file_type": "json"}).json()["seriess"][0]
        obs = get(f"{base}/series/observations", params={
            "series_id": series, "api_key": key, "file_type": "json",
            "sort_order": "desc", "limit": 10}).json()["observations"]
    except Exception as e:
        raise RuntimeError(str(e).replace(key, "***")) from None
    vals = [o for o in obs if o["value"] != "."]
    v, pv = float(vals[0]["value"]), float(vals[1]["value"])
    # last_updated 예: "2026-10-29 09:27:15-05"
    lu = datetime.strptime(info["last_updated"] + "00", "%Y-%m-%d %H:%M:%S%z")
    return {"price": v, "change": round(v - pv, 3), "change_pct": round((v / pv - 1) * 100, 2),
            "as_of": vals[0]["date"] + " (미국 날짜)", "published": iso(lu), "source": "FRED"}


def cnn_fear_greed():
    r = get("https://production.dataviz.cnn.io/index/fearandgreed/graphdata",
            headers={"Referer": "https://edition.cnn.com/", "Origin": "https://edition.cnn.com"})
    fg = r.json()["fear_and_greed"]
    t = datetime.fromisoformat(fg["timestamp"].replace("Z", "+00:00"))
    rating = {"extreme fear": "극도의 공포", "fear": "공포", "neutral": "중립",
              "greed": "탐욕", "extreme greed": "극도의 탐욕"}.get(fg["rating"], fg["rating"])
    score, prev = round(fg["score"]), fg.get("previous_close")
    return {"price": score, "label": rating,
            "change": round(score - prev) if prev is not None else None,
            "as_of": iso(t), "source": "CNN"}


def margin_debt():
    m = json.loads((ROOT / "data/manual/margin_debt.json").read_text(encoding="utf-8"))
    if not m.get("balance") or not m.get("balance_year_ago"):
        return {"price": None, "need_input": True, "source": "직접 입력",
                "error": "아직 입력되지 않았어요"}
    yoy = (m["balance"] / m["balance_year_ago"] - 1) * 100
    return {"price": round(yoy, 1), "balance": m["balance"], "as_of": m["month"] + " 월말",
            "source": "FINRA (직접 입력)"}


def google_news(query, limit):
    url = f"https://news.google.com/rss/search?q={quote(query + ' when:1d')}&hl=ko&gl=KR&ceid=KR:ko"
    root = ET.fromstring(get(url).content)
    items = []
    for it in root.findall(".//item")[:limit]:
        title = it.findtext("title") or ""
        src = it.findtext("source") or ""
        if src and title.endswith(" - " + src):
            title = title[: -len(" - " + src)]
        pub = it.findtext("pubDate")
        items.append({"title": title, "source": src,
                      "time": iso(parsedate_to_datetime(pub)) if pub else None})
    return items


# ---------------- 전체 수집 ----------------

INDICATORS = [
    # (키, 이름, CNBC 기호, 예비 출처)
    ("usdkrw", "원/달러 환율", "KRW=", None),
    ("dxy", "달러지수", ".DXY", None),
    ("us10y", "미국 10년물 금리", "US10Y", lambda: fred("DGS10")),
    ("vix", "VIX", ".VIX", cboe_vix),
]


def collect(config):
    t0 = now_kst()
    stocks_cfg = config["stocks"]
    us_syms = [s["code"] for s in stocks_cfg if s["market"] == "US"]
    try:
        cnbc_all(us_syms + [i[2] for i in INDICATORS])
    except Exception as e:
        print("CNBC 묶음 요청 실패:", e)

    stocks = []
    for s in stocks_cfg:
        if s["market"] == "KR":
            q = first_ok(lambda c=s["code"]: naver_kr(c))
        else:
            fns = [lambda c=s["code"]: cnbc(c)]
            if s.get("naver"):
                fns.append(lambda c=s["naver"]: naver_world(c))
            q = first_ok(*fns)
        news = first_ok(lambda n=s.get("news_query", s["name"]): google_news(n, config.get("news_per_stock", 8)))
        stocks.append({**s, "quote": q, "news": news if isinstance(news, list) else [],
                       "news_error": news.get("error") if isinstance(news, dict) else None})

    ind = {}
    for key, name, sym, backup in INDICATORS:
        fns = [lambda s=sym: cnbc(s)] + ([backup] if backup else [])
        ind[key] = {"name": name, **first_ok(*fns)}
    ind["hy_spread"] = {"name": "하이일드 스프레드", **first_ok(lambda: fred("BAMLH0A0HYM2"))}
    ind["fear_greed"] = {"name": "공포탐욕지수", **first_ok(cnn_fear_greed)}
    ind["margin_debt"] = {"name": "Margin Debt (전년비 %)", **first_ok(margin_debt)}

    market_news = []
    for q in config.get("market_news_queries", []):
        r = first_ok(lambda q=q: google_news(q, 8))
        if isinstance(r, list):
            market_news += r

    return {"collected_at": iso(t0), "stocks": stocks, "indicators": ind, "market_news": market_news,
            "took_sec": round((now_kst() - t0).total_seconds(), 1)}


def check_gemini():
    """Gemini 열쇠가 맞는지만 확인(요약은 3-2단계)."""
    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        return "❌ GEMINI_API_KEY 없음"
    try:
        r = S.get("https://generativelanguage.googleapis.com/v1beta/models",
                  params={"key": key, "pageSize": 100}, timeout=TIMEOUT)
        r.raise_for_status()
        names = [m["name"].split("/")[-1] for m in r.json().get("models", [])
                 if "generateContent" in m.get("supportedGenerationMethods", [])]
        flash = [n for n in names if "flash" in n]
        return f"✅ 열쇠 정상. 쓸 수 있는 flash 모델: {', '.join(flash[:8])}"
    except Exception as e:
        return "❌ " + str(e).replace(key, "***")[:150]


def report(d):
    """사람이 보기 쉬운 표 (GitHub 실행 요약용)."""
    def fmt(q):
        if q.get("error"):
            return "❌", q["error"][:90]
        p = q.get("price")
        txt = f"{p:,.2f}" if isinstance(p, float) else str(p)
        if q.get("label"):
            txt += " " + q["label"]
        if q.get("change_pct") is not None:
            txt += f" ({q['change_pct']:+.2f}%)"
        txt += f" · 기준 {q.get('as_of')} · {q.get('source')}"
        return "✅", txt

    out = [f"## 3-1 수집 결과 ({d['collected_at']}, {d['took_sec']}초)", "",
           "| 종목 | 결과 | 값 | 뉴스 |", "|---|---|---|---|"]
    for s in d["stocks"]:
        ok, txt = fmt(s["quote"])
        n = f"{len(s['news'])}건: {s['news'][0]['title'][:40]}" if s["news"] else (s["news_error"] or "0건")
        out.append(f"| {s['name']} | {ok} | {txt} | {n.replace('|', '/')} |")
    out += ["", "| 지표 | 결과 | 값 |", "|---|---|---|"]
    for v in d["indicators"].values():
        ok, txt = fmt(v)
        out.append(f"| {v['name']} | {ok} | {txt} |")
    return "\n".join(out)


def main():
    config = json.loads((ROOT / "server/config.json").read_text(encoding="utf-8"))
    d = collect(config)
    (ROOT / "data").mkdir(exist_ok=True)
    (ROOT / "data/collected.json").write_text(json.dumps(d, ensure_ascii=False, indent=1), encoding="utf-8")
    table = report(d) + "\n\n**Gemini 열쇠 확인:** " + check_gemini()
    print(table)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(table + "\n")


if __name__ == "__main__":
    main()
