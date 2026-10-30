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


TESTS = [
    ("기준", "삼성전자 (네이버)", lambda: naver_stock("005930")),
    ("미국 주식", "엔비디아 (네이버)", lambda: naver_world("NVDA.O")),
    ("미국 주식", "TSMC (네이버)", lambda: naver_world("TSM.N")),
    ("지표", "원/달러 (네이버)", lambda: naver_index(
        "https://m.stock.naver.com/front-api/marketIndex/productDetail?category=exchange&reutersCode=FX_USDKRW")),
    ("묶음", "CNBC (10년물·VIX·달러지수·환율·엔비디아)", lambda: cnbc("US10Y|.VIX|.DXY|KRW=|NVDA")),
    ("미국 주식", "엔비디아 (stooq)", lambda: stooq("nvda.us")),
    ("지표", "달러지수 (stooq)", lambda: stooq("dx.f")),
    ("지표", "미국 10년물 (stooq)", lambda: stooq("10usy.b")),
    ("지표", "VIX (CBOE 공식)", cboe_vix),
    ("지표", "미국 10년물 (미 재무부)", treasury_10y),
    ("미국 주식", "엔비디아 (야후, 쿠키 방식)", lambda: yahoo_crumb("NVDA")),
    ("지표", "하이일드 (FRED, 60초 대기)", lambda: fred_slow("BAMLH0A0HYM2")),
    ("지표", "하이일드 (FRED 텍스트)", lambda: fred_txt("BAMLH0A0HYM2")),
    ("지표", "Margin Debt (FINRA 엑셀 직접)", finra_direct),
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
