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


TESTS = [
    ("국내 주식", "삼성전자 (네이버)", lambda: naver_stock("005930")),
    ("국내 주식", "SK하이닉스 (네이버)", lambda: naver_stock("000660")),
    ("국내 주식", "삼성전자 (야후, 예비)", lambda: yahoo("005930.KS")),
    ("미국 주식", "엔비디아 (야후)", lambda: yahoo("NVDA")),
    ("미국 주식", "TSMC (야후)", lambda: yahoo("TSM")),
    ("지표", "원/달러 환율 (야후)", lambda: yahoo("KRW=X")),
    ("지표", "달러지수 (야후)", lambda: yahoo("DX-Y.NYB")),
    ("지표", "미국 10년물 금리 (야후)", lambda: yahoo("^TNX")),
    ("지표", "미국 10년물 금리 (FRED, 예비)", lambda: fred_csv("DGS10")),
    ("지표", "VIX (야후)", lambda: yahoo("^VIX")),
    ("지표", "하이일드 스프레드 (FRED)", lambda: fred_csv("BAMLH0A0HYM2")),
    ("지표", "Margin Debt (FINRA)", finra_margin),
    ("지표", "공포탐욕지수 (CNN)", cnn_fg),
    ("뉴스", "삼성전자 뉴스 (구글)", lambda: gnews("삼성전자")),
    ("뉴스", "엔비디아 뉴스 (구글)", lambda: gnews("엔비디아")),
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
