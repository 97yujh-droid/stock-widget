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


TESTS = [
    ("과거주가", "삼성전자 일봉 (네이버 fchart)", lambda: naver_fchart("005930")),
    ("과거주가", "삼성전자 일봉 (네이버 chart API)", lambda: naver_chart_api("domestic", "005930")),
    ("과거주가", "코스피 일봉 (네이버 fchart)", lambda: naver_fchart("KOSPI")),
    ("과거주가", "엔비디아 일봉 (네이버 chart API)", lambda: naver_chart_api("foreign", "NVDA.O")),
    ("과거주가", "엔비디아 일봉 (CNBC)", lambda: cnbc_bars("NVDA")),
    ("과거주가", "TSMC 일봉 (CNBC)", lambda: cnbc_bars("TSM")),
    ("과거주가", "S&P500 일봉 (CNBC)", lambda: cnbc_bars(".SPX")),
    ("과거주가", "S&P500 (FRED)", lambda: fred_hist("SP500")),
    ("가치", "삼성전자 PER/PBR (네이버)", lambda: naver_integration("005930")),
    ("가치", "엔비디아 (네이버 해외)", lambda: naver_world_integration("NVDA.O")),
    ("가치", "엔비디아·TSMC (CNBC 재무)", lambda: f"NVDA {cnbc_fund('NVDA')} / TSM {cnbc_fund('TSM')}"),
    ("시장점수", "장단기 금리차 (FRED T10Y2Y)", lambda: fred_hist("T10Y2Y")),
    ("시장점수", "달러 지수 넓은 범위 (FRED DTWEXBGS)", lambda: fred_hist("DTWEXBGS")),
    ("시장점수", "원/달러 (FRED DEXKOUS)", lambda: fred_hist("DEXKOUS")),
    ("시장점수", "하이일드 1년치 (FRED)", lambda: fred_hist("BAMLH0A0HYM2")),
    ("시장점수", "미국 10년물 1년치 (FRED)", lambda: fred_hist("DGS10")),
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
