"""2단계: 무료 데이터 출처 시험.

항목마다 실제로 숫자를 가져와 보고 결과표를 만든다.
GitHub Actions 에서 실행되며, 결과는 실행 요약(Summary)과 로그에 표로 나온다.
"""
import csv
import io
import json
import os
import re
import sys
import time
import traceback
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta
from urllib.parse import quote

import requests

KST = timezone(timedelta(hours=9))
UA = ("Mozilla/5.0 (Linux; Android 14; SM-S928N) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36")
S = requests.Session()
S.headers.update({"User-Agent": UA, "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.8"})
TIMEOUT = 20


def get(url, **kw):
    r = S.get(url, timeout=TIMEOUT, **kw)
    r.raise_for_status()
    return r


def ts(sec):
    return datetime.fromtimestamp(sec, KST).strftime("%m/%d %H:%M")


# ---------- 국내 주식: 네이버 ----------
def naver_stock(code):
    j = get(f"https://m.stock.naver.com/api/stock/{code}/basic").json()
    return (f"{j['stockName']} {j['closePrice']}원 "
            f"{j['fluctuationsRatio']}% ({j.get('localTradedAt', '')[:16]})")


# ---------- 야후 파이낸스 ----------
def yahoo(symbol):
    url = (f"https://query1.finance.yahoo.com/v8/finance/chart/{quote(symbol)}"
           "?range=5d&interval=1d")
    m = get(url).json()["chart"]["result"][0]["meta"]
    price = m["regularMarketPrice"]
    prev = m.get("chartPreviousClose") or m.get("previousClose")
    pct = (price - prev) / prev * 100 if prev else 0
    return f"{price:,.3f} ({pct:+.2f}%) 기준 {ts(m['regularMarketTime'])} KST"


# ---------- FRED (가입 없이 CSV) ----------
def fred_csv(series):
    txt = get(f"https://fred.stlouisfed.org/graph/fredgraph.csv?id={series}").text
    rows = [r for r in csv.reader(io.StringIO(txt)) if len(r) == 2 and r[1] not in ("", ".")]
    d, v = rows[-1]
    return f"{v} (날짜 {d}, 미국)"


# ---------- FINRA Margin Debt ----------
def finra_margin():
    page = get("https://www.finra.org/finra-data/browse-catalog/industry-margin-statistics").text
    links = re.findall(r'href="([^"]+\.xlsx)"', page)
    if not links:
        raise RuntimeError("페이지에서 엑셀 링크를 못 찾음")
    url = links[0] if links[0].startswith("http") else "https://www.finra.org" + links[0]
    import openpyxl
    wb = openpyxl.load_workbook(io.BytesIO(get(url).content), read_only=True, data_only=True)
    ws = wb.worksheets[0]
    rows = [r for r in ws.iter_rows(values_only=True) if r and r[0] and r[1]]
    data = [r for r in rows if isinstance(r[1], (int, float))]
    latest = data[0] if str(data[0][0]) > str(data[-1][0]) else data[-1]
    # 1년 전 같은 달 찾기
    def key(r):
        return str(r[0])[:7]
    lk = key(latest)
    yago = f"{int(lk[:4]) - 1}{lk[4:]}"
    prev = next((r for r in data if key(r) == yago), None)
    yoy = f", 전년비 {(latest[1] / prev[1] - 1) * 100:+.1f}%" if prev else ""
    return f"{lk} 잔고 {latest[1]:,.0f}백만$ {yoy} (파일: {url.rsplit('/', 1)[-1]})"


# ---------- CNN 공포탐욕 ----------
def cnn_fg():
    r = S.get("https://production.dataviz.cnn.io/index/fearandgreed/graphdata",
              headers={"Referer": "https://edition.cnn.com/", "Origin": "https://edition.cnn.com"},
              timeout=TIMEOUT)
    r.raise_for_status()
    fg = r.json()["fear_and_greed"]
    t = datetime.fromisoformat(fg["timestamp"].replace("Z", "+00:00")).astimezone(KST)
    return f"{fg['score']:.0f} {fg['rating']} (기준 {t:%m/%d %H:%M} KST)"


# ---------- 구글 뉴스 RSS ----------
def gnews(q):
    url = f"https://news.google.com/rss/search?q={quote(q + ' when:1d')}&hl=ko&gl=KR&ceid=KR:ko"
    root = ET.fromstring(get(url).content)
    items = root.findall(".//item")
    if not items:
        raise RuntimeError("최근 24시간 뉴스 0건")
    return f"{len(items)}건. 예: {items[0].findtext('title')[:60]}"


# ---------- 2차 시험: 막힌 항목의 다른 출처 ----------
def short(txt, n=160):
    return " ".join(str(txt).split())[:n]


def naver_world(code):
    j = get(f"https://api.stock.naver.com/stock/{code}/basic").json()
    return f"{j.get('stockName')} {j.get('closePrice')} {j.get('fluctuationsRatio')}% ({str(j.get('localTradedAt'))[:16]})"


def naver_index(url):
    return short(get(url).text, 220)


def cnbc(symbols):
    url = ("https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol"
           f"?symbols={quote(symbols)}&requestMethod=itv&noform=1&partnerId=2&fund=1&exthrs=1&output=json")
    q = get(url).json()["FormattedQuoteResult"]["FormattedQuote"]
    return " / ".join(f"{x.get('symbol')} {x.get('last')} {x.get('change_pct')} @{x.get('last_time', '')[:16]}" for x in q)


def stooq(sym):
    return short(get(f"https://stooq.com/q/l/?s={sym}&f=sd2t2c&h&e=csv").text)


def cboe_vix():
    rows = get("https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv").text.strip().splitlines()
    return f"마지막 줄: {rows[-1]}"


def yahoo_crumb(symbol):
    S.get("https://fc.yahoo.com", timeout=TIMEOUT)
    crumb = get("https://query2.finance.yahoo.com/v1/test/getcrumb").text
    url = f"https://query2.finance.yahoo.com/v8/finance/chart/{quote(symbol)}?range=5d&interval=1d&crumb={quote(crumb)}"
    m = get(url).json()["chart"]["result"][0]["meta"]
    return f"{m['regularMarketPrice']} 기준 {ts(m['regularMarketTime'])} KST"


def fred_slow(series):
    r = S.get(f"https://fred.stlouisfed.org/graph/fredgraph.csv?id={series}", timeout=60)
    r.raise_for_status()
    return short(r.text.strip().splitlines()[-1])


def fred_txt(series):
    r = S.get(f"https://fred.stlouisfed.org/data/{series}.txt", timeout=60)
    r.raise_for_status()
    return short(r.text.strip().splitlines()[-1])


def finra_direct():
    h = {"Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
         "Referer": "https://www.finra.org/"}
    r = S.get("https://www.finra.org/sites/default/files/2021-03/margin-statistics.xlsx", headers=h, timeout=TIMEOUT)
    r.raise_for_status()
    return f"{len(r.content):,} 바이트, 종류 {r.headers.get('content-type')}"


def treasury_10y():
    y = datetime.now(KST).year
    url = ("https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/"
           f"{y}/all?type=daily_treasury_yield_curve&field_tdr_date_value={y}&page&_format=csv")
    lines = get(url).text.strip().splitlines()
    head = lines[0].split(",")
    row = lines[1].split(",")
    return f"{row[0]} 10년 {row[head.index('10 Yr')]}"


# ---------- 3차 시험: FRED 공식 통로 + Margin Debt 다른 출처 ----------
FRED_KEY = os.environ.get("FRED_API_KEY", "")


def hide_key(msg):
    return str(msg).replace(FRED_KEY, "***") if FRED_KEY else str(msg)


def fred_api(series):
    if not FRED_KEY:
        raise RuntimeError("FRED_API_KEY 가 비어 있음")
    base = "https://api.stlouisfed.org/fred"
    try:
        info = get(f"{base}/series", params={"series_id": series, "api_key": FRED_KEY,
                                             "file_type": "json"}).json()["seriess"][0]
        obs = get(f"{base}/series/observations",
                  params={"series_id": series, "api_key": FRED_KEY, "file_type": "json",
                          "sort_order": "desc", "limit": 5}).json()["observations"]
    except Exception as e:
        raise RuntimeError(hide_key(e)) from None
    o = next(x for x in obs if x["value"] != ".")
    return f"{o['value']} (날짜 {o['date']}, 미국) / FRED 마지막 갱신: {info['last_updated']}"


def try_page(url, must):
    r = S.get(url, timeout=TIMEOUT, headers={
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"})
    r.raise_for_status()
    txt = r.text
    hit = must.lower() in txt.lower()
    links = re.findall(r'href="([^"]+\.xlsx?)"', txt)[:2]
    return f"{r.status_code}, {len(txt):,}자, '{must}' {'있음' if hit else '없음'}, 엑셀링크 {links}"


# ---------- 4차 시험(3-3단계): 과거 주가·PER/PBR·시장 점수용 시계열 ----------
def peek_json(url, n=260):
    j = get(url).json()
    keys = list(j.keys())[:12] if isinstance(j, dict) else f"list[{len(j)}]"
    return f"키 {keys} / {short(json.dumps(j, ensure_ascii=False), n)}"


def naver_fchart(symbol, count=250):
    txt = get(f"https://fchart.stock.naver.com/sise.nhn?symbol={symbol}&timeframe=day&count={count}&requestType=0").text
    rows = re.findall(r'data="([^"]+)"', txt)
    return f"{len(rows)}일치, 첫날 {rows[0] if rows else '-'} / 마지막 {rows[-1] if rows else '-'}"


def naver_chart_api(kind, code):
    url = (f"https://api.stock.naver.com/chart/{kind}/item/{code}/day"
           "?startDateTime=202501010000&endDateTime=202612310000")
    j = get(url).json()
    rows = j if isinstance(j, list) else j.get("priceInfos", j)
    return f"{len(rows)}일치, 마지막 {short(json.dumps(rows[-1], ensure_ascii=False), 160) if rows else '-'}"


def cnbc_bars(sym):
    url = f"https://ts-api.cnbc.com/harmony/app/bars/{quote(sym)}/1D/20250101000000/20261231000000/adjusted/EST5EDT.json"
    j = get(url).json()
    bars = j.get("barData", {}).get("priceBars", [])
    return f"{len(bars)}일치, 마지막 {short(json.dumps(bars[-1]), 160) if bars else short(json.dumps(j), 160)}"


def naver_integration(path):
    j = get(f"https://m.stock.naver.com/api/stock/{path}/integration").json()
    infos = {x.get("code") or x.get("key"): x.get("value") for x in j.get("totalInfos", [])}
    pick = {k: v for k, v in infos.items() if k and any(t in k.lower() for t in ("per", "pbr", "eps", "bps"))}
    ind = j.get("industryCompareInfo")
    return f"PER/PBR관련 {pick} / 업종비교 {'있음' if ind else '없음'} {short(json.dumps(ind, ensure_ascii=False), 120) if ind else ''}"


def naver_world_integration(code):
    return peek_json(f"https://api.stock.naver.com/stock/{code}/integration", 300)


def cnbc_fund(sym):
    url = ("https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol"
           f"?symbols={sym}&requestMethod=itv&noform=1&partnerId=2&fund=1&exthrs=1&output=json")
    q = get(url).json()["FormattedQuoteResult"]["FormattedQuote"][0]
    return {k: q.get(k) for k in ("pe", "fpe", "pb", "pbr", "eps", "yrhiprice", "yrloprice", "beta") if k in q} or short(list(q.keys()), 300)


def fred_hist(series):
    if not FRED_KEY:
        raise RuntimeError("FRED_API_KEY 가 비어 있음")
    try:
        obs = get("https://api.stlouisfed.org/fred/series/observations", params={
            "series_id": series, "api_key": FRED_KEY, "file_type": "json",
            "observation_start": "2025-06-01"}).json()["observations"]
    except Exception as e:
        raise RuntimeError(hide_key(e)) from None
    vals = [o for o in obs if o["value"] != "."]
    return f"{len(vals)}개, 마지막 {vals[-1]['date']} = {vals[-1]['value']}"


# ---------- 5차 시험: 앱 종목 검색(네이버 자동완성) ----------
def naver_ac(q):
    j = get(f"https://ac.stock.naver.com/ac?q={quote(q)}&target=stock,index").json()
    items = j.get("items", j)
    return short(json.dumps(items[:3] if isinstance(items, list) else j, ensure_ascii=False), 600)


def ac_raw(target, ua, accept):
    r = requests.get(f"https://ac.stock.naver.com/ac?q={quote('마이크로소프트')}&target={target}",
                     headers={"User-Agent": ua, "Accept": accept}, timeout=TIMEOUT)
    return f"HTTP {r.status_code} / {short(r.text, 200)}"


APP_UA = "Dalvik/2.1.0 (Linux; U; Android 14; SM-S928N Build/UP1A)"
GH = "application/vnd.github+json"
# ---------- 6차 시험: 추가 지표 + 경제 일정 ----------
def cnbc_each(syms):
    url = ("https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol"
           f"?symbols={quote('|'.join(syms))}&requestMethod=itv&noform=1&partnerId=2&fund=1&exthrs=1&output=json")
    q = get(url).json()["FormattedQuoteResult"]["FormattedQuote"]
    return " / ".join(f"{x.get('symbol')}={x.get('last')} ({x.get('change_pct')}) {x.get('name','')[:20]} @{str(x.get('last_time',''))[:16]}" for x in q)


def ff_calendar():
    j = get("https://nfs.faireconomy.media/ff_calendar_thisweek.json").json()
    us = [e for e in j if e.get("country") in ("USD", "KRW") and e.get("impact") in ("High", "Medium")]
    return f"{len(j)}건 중 미국·한국 주요 {len(us)}건. 예: " + short(json.dumps(us[:3], ensure_ascii=False), 400)


def naver_kospi_investors():
    out = []
    for u in ("https://m.stock.naver.com/api/index/KOSPI/integration",
              "https://m.stock.naver.com/api/index/KOSPI/trend"):
        try:
            j = get(u).json()
            keys = list(j.keys())[:15] if isinstance(j, dict) else f"list{len(j)}"
            out.append(f"{u.rsplit('/',1)[-1]}: 키 {keys} / {short(json.dumps(j, ensure_ascii=False), 300)}")
        except Exception as e:
            out.append(f"{u.rsplit('/',1)[-1]}: {type(e).__name__} {str(e)[:80]}")
    return " || ".join(out)


NAS_H = {"User-Agent": UA, "Accept": "application/json, text/plain, */*",
         "Origin": "https://www.nasdaq.com", "Referer": "https://www.nasdaq.com/"}


def charts_check():
    j = get("https://97yujh-droid.github.io/stock-widget/charts.json").json()["series"]
    ms = j.get("market_score", [])
    return (f"{len(j)}개, market_score {len(ms)}점 {ms[:2]} ... {ms[-2:]}, "
            f"spx_ma200 {len(j.get('spx_ma200', []))}점, vix {len(j.get('vix', []))}, fg {len(j.get('fear_greed', []))}")


def nasdaq_date(sym):
    j = get(f"https://api.nasdaq.com/api/analyst/{sym}/earnings-date", headers=NAS_H).json()
    return json.dumps(j.get("data"), ensure_ascii=False)[:300]


def nasdaq_surprise(sym):
    j = get(f"https://api.nasdaq.com/api/company/{sym}/earnings-surprise", headers=NAS_H).json()
    return json.dumps(j.get("data"), ensure_ascii=False)[:300]


def nasdaq_calendar():
    d = (datetime.now(KST) + timedelta(days=1)).strftime("%Y-%m-%d")
    j = get(f"https://api.nasdaq.com/api/calendar/earnings?date={d}", headers=NAS_H).json()
    rows = (j.get("data") or {}).get("rows") or []
    return f"{d} {len(rows)}개: " + json.dumps(rows[:1], ensure_ascii=False)[:250]


def cnbc_earn_fields(sym):
    url = ("https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol"
           f"?symbols={sym}&requestMethod=extended&noform=1&partnerId=2&fund=1&exthrs=1&output=json")
    q = get(url).json()["ExtendedQuoteResult"]["ExtendedQuote"][0]
    flat = json.dumps(q, ensure_ascii=False)
    keys = re.findall(r'"([^"]*(?:arn|EPS|eps)[^"]*)":\s*"?([^",}]*)', flat)
    return str(keys[:12])[:300]


def naver_world_integration(code):
    j = get(f"https://api.stock.naver.com/stock/{code}/integration").json()
    flat = json.dumps(j, ensure_ascii=False)
    hits = [m for m in re.findall(r'"([^"]*(?:arn|ettle|실적|발표)[^"]*)"', flat)][:15]
    return f"keys={list(j.keys())[:12]} hits={hits}"[:300]


def naver_kr_earn(code):
    j = get(f"https://m.stock.naver.com/api/stock/{code}/integration").json()
    flat = json.dumps(j, ensure_ascii=False)
    hits = re.findall(r'"([^"]*(?:실적|발표|arn)[^"]*)"', flat)[:15]
    return f"keys={list(j.keys())[:14]} hits={hits}"[:300]


def earnings_check():
    j = get("https://97yujh-droid.github.io/stock-widget/latest.json").json()
    out = []
    for st in j["stocks"]:
        e = st.get("earnings") or {}
        if st["market"] == "US":
            l = e.get("last") or {}
            out.append(f"{st['name']}: {e.get('next_date')} {e.get('timing')} eps{e.get('eps_forecast')} "
                       f"last {l.get('date')} {l.get('eps')}/{l.get('forecast')} {l.get('surprise_pct')}% {e.get('note') or ''}")
        else:
            r = (e.get("reports") or [{}])[0]
            out.append(f"{st['name']}: 리포트 {r.get('title', '')[:30]} / {r.get('broker')} / {r.get('date')}")
    return " ‖ ".join(out)[:2500]


def nasdaq_surprise_row():
    j = get("https://api.nasdaq.com/api/company/NVDA/earnings-surprise", headers=NAS_H).json()
    return json.dumps(j["data"]["earningsSurpriseTable"]["rows"][:1], ensure_ascii=False)


def naver_research_keys():
    j = get("https://m.stock.naver.com/api/stock/005930/integration").json()
    return json.dumps((j.get("researches") or [])[:1], ensure_ascii=False)[:400]


def nv(path):
    r = S.get("https://api.stock.naver.com/stock/" + path, timeout=TIMEOUT,
              headers={"Referer": "https://m.stock.naver.com/", "Accept": "application/json"})
    if r.status_code != 200:
        return f"HTTP {r.status_code}"
    j = r.json()
    items = j.get("stocks") if isinstance(j, dict) else j
    if isinstance(j, dict) and items is None:
        return f"keys={list(j.keys())[:10]}"
    if not items:
        return "빈 목록"
    x = items[0]
    keys = list(x.keys())
    pick = {k: x.get(k) for k in ("stockName", "symbolCode", "reutersCode", "closePrice", "fluctuationsRatio",
                                   "compareToPreviousClosePrice", "accumulatedTradingValue", "accumulatedTradingVolume",
                                   "marketValue", "stockExchangeType") if k in x}
    total = j.get("totalCount") if isinstance(j, dict) else None
    return f"{len(items)}개 total={total} 첫={pick} keys={keys[:30]}"[:700]


CANDS = [
    "nation/USA/priceTop?page=1&pageSize=20",
    "nation/USA/up?page=1&pageSize=20",
    "nation/USA/down?page=1&pageSize=20",
    "nation/USA/quantTop?page=1&pageSize=20",
    "exchange/NASDAQ/quantTop?page=1&pageSize=20",
    "exchange/NASDAQ/volume?page=1&pageSize=20",
    "exchange/NASDAQ/tradeVolume?page=1&pageSize=20",
    "exchange/NASDAQ/volumeTop?page=1&pageSize=20&sort=volume",
    "exchange/NASDAQ/dealVolume?page=1&pageSize=20",
    "exchange/NASDAQ/tradeQuantity?page=1&pageSize=20",
    "exchange/NASDAQ/quant?page=1&pageSize=20",
    "nation/USA/popular?page=1&pageSize=20",
    "nation/USA/popularSearch?page=1&pageSize=20",
    "nation/USA/searchTop?page=1&pageSize=20",
    "nation/USA/popularStocks?page=1&pageSize=20",
]


def other(url):
    r = S.get(url, timeout=TIMEOUT, headers={"Referer": "https://m.stock.naver.com/"})
    return f"HTTP {r.status_code} {r.text[:400]}"


def front_api():
    r = S.get("https://m.stock.naver.com/worldstock/home/USA/marketValue/NASDAQ", timeout=TIMEOUT)
    paths = sorted(set(re.findall(r'api\.stock\.naver\.com/[A-Za-z0-9_/{}.$-]+', r.text)))
    return f"HTTP {r.status_code} {paths[:30]}"[:900]


def raw(url, **kw):
    r = S.get(url, timeout=TIMEOUT, **kw)
    return f"HTTP {r.status_code} {r.text[:350]}"


def cboe_daily():
    out = []
    for back in range(1, 5):
        d = (datetime.now(KST) - timedelta(days=back)).strftime("%Y-%m-%d")
        r = S.get(f"https://cdn.cboe.com/data/us/options/market_statistics/daily/{d}_daily_options", timeout=TIMEOUT)
        out.append(f"{d}:{r.status_code}")
        if r.status_code == 200:
            j = r.json()
            return f"{out} keys={list(j.keys())[:10]} ratios={json.dumps(j.get('ratios'), ensure_ascii=False)[:400]}"
    return str(out)


def cnbc_try(syms):
    url = ("https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol"
           f"?symbols={quote('|'.join(syms))}&requestMethod=itv&noform=1&partnerId=2&fund=1&exthrs=1&output=json")
    q = get(url).json()["FormattedQuoteResult"]["FormattedQuote"]
    return " / ".join(f"{x.get('symbol')}={x.get('last')}({x.get('name', '')[:20]})" for x in q)


def nasdaq_cal(days):
    d = (datetime.now(KST) + timedelta(days=days)).strftime("%Y-%m-%d")
    j = get(f"https://api.nasdaq.com/api/calendar/earnings?date={d}", headers=NAS_H).json()
    rows = (j.get("data") or {}).get("rows") or []
    big = sorted(rows, key=lambda r: -float(re.sub(r"[^0-9.]", "", r.get("marketCap") or "0") or 0))[:5]
    return f"{d} {len(rows)}개 keys={list(rows[0].keys()) if rows else []} 상위={[(r.get('symbol'), r.get('marketCap'), r.get('time')) for r in big]}"[:600]


def treasury_auctions():
    j = get("https://www.treasurydirect.gov/TA_WS/securities/upcoming?format=json").json()
    return f"{len(j)}개 {[(x.get('securityTerm'), x.get('auctionDate'), x.get('securityType')) for x in j[:6]]}"


def gnews_sched(q):
    root = ET.fromstring(get(f"https://news.google.com/rss/search?q={quote(q + ' when:7d')}&hl=ko&gl=KR&ceid=KR:ko").content)
    return " | ".join((it.findtext("title") or "")[:60] for it in root.findall(".//item")[:6])


TESTS = [
    ("풋콜", "CBOE 일별 옵션 통계", cboe_daily),
    ("풋콜", "CNBC 풋콜 기호", lambda: cnbc_try([".CPCE", "CPCE", ".PCCE", ".CPC", "CPC", ".PCRATIO"])),
    ("풋콜", "FRED 검색 없음 대체: stooq", lambda: raw("https://stooq.com/q/d/l/?s=^cpce&i=d")),
    ("ETF", "CNBC ETF 시세", lambda: cnbc_try(["GDX", "TAN", "SOXX", "KWEB", "DRAM", "LYTE", "NCLD", "SPCX", "USO", "XOP"])),
    ("일정", "Nasdaq 실적 달력 +1일", lambda: nasdaq_cal(1)),
    ("일정", "Nasdaq 실적 달력 +2일", lambda: nasdaq_cal(2)),
    ("일정", "미 국채 입찰 일정", treasury_auctions),
    ("일정", "뉴스: 이번주 증시 일정", lambda: gnews_sched("이번주 증시 주요 일정")),
]


def main():
    results = []
    for group, name, fn in TESTS:
        t0 = time.time()
        try:
            val = fn()
            ok = "OK"
        except Exception as e:  # 실패 이유를 그대로 남긴다
            val = f"{type(e).__name__}: {str(e)[:150]}"
            ok = "FAIL"
            traceback.print_exc()
        results.append({"group": group, "name": name, "result": ok,
                        "value": val, "sec": round(time.time() - t0, 1)})
        print(f"[{ok}] {name}: {val}", flush=True)

    now = datetime.now(KST).strftime("%Y-%m-%d %H:%M KST")
    lines = [f"## 데이터 출처 시험 결과 ({now})", "",
             "| 구분 | 항목 | 결과 | 가져온 값 | 걸린 시간 |", "|---|---|---|---|---|"]
    for r in results:
        mark = "✅" if r["result"] == "OK" else "❌"
        lines.append(f"| {r['group']} | {r['name']} | {mark} | {r['value'].replace('|', '/')} | {r['sec']}s |")
    table = "\n".join(lines)
    print("\n" + table)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(table + "\n")
    with open("datatest/results.json", "w", encoding="utf-8") as f:
        json.dump({"time": now, "results": results}, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    sys.exit(main())
