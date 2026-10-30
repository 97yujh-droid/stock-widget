"""3-4단계: 점수 계산에 필요한 과거 시세·가치 지표 수집."""
import re
from datetime import datetime, timedelta
from statistics import median
from urllib.parse import quote

from collect import KST, S, get, num, first_ok, now_kst
import os


# ---------------- 과거 종가 (오래된 것 → 최근 순서의 [날짜, 종가] 목록) ----------------

def naver_fchart(symbol, count=300):
    txt = get(f"https://fchart.stock.naver.com/sise.nhn?symbol={symbol}"
              f"&timeframe=day&count={count}&requestType=0").text
    rows = []
    for d in re.findall(r'data="([^"]+)"', txt):
        p = d.split("|")
        if len(p) >= 5 and p[4] not in ("", "0"):
            rows.append((p[0], float(p[4])))
    if len(rows) < 30:
        raise RuntimeError(f"{symbol} 일봉 {len(rows)}개뿐")
    return rows


def naver_chart_api(kind, code):
    start = (now_kst() - timedelta(days=450)).strftime("%Y%m%d0000")
    end = now_kst().strftime("%Y%m%d2359")
    j = get(f"https://api.stock.naver.com/chart/{kind}/item/{code}/day"
            f"?startDateTime={start}&endDateTime={end}").json()
    rows = j if isinstance(j, list) else j.get("priceInfos", [])
    out = [(r["localDate"], float(r["closePrice"])) for r in rows if r.get("closePrice")]
    if len(out) < 30:
        raise RuntimeError(f"{code} 일봉 {len(out)}개뿐")
    return out


def cnbc_bars(sym):
    start = (now_kst() - timedelta(days=450)).strftime("%Y%m%d000000")
    end = now_kst().strftime("%Y%m%d235959")
    j = get(f"https://ts-api.cnbc.com/harmony/app/bars/{quote(sym)}/1D/{start}/{end}"
            "/adjusted/EST5EDT.json").json()
    bars = j.get("barData", {}).get("priceBars", [])
    out = [(b["tradeTime"][:8], float(b["close"])) for b in bars if num(b.get("close"))]
    if len(out) < 30:
        raise RuntimeError(f"{sym} 일봉 {len(out)}개뿐")
    return out


def fred_series(series, days=450):
    key = os.environ.get("FRED_API_KEY")
    if not key:
        raise RuntimeError("FRED_API_KEY 없음")
    start = (now_kst() - timedelta(days=days)).strftime("%Y-%m-%d")
    try:
        obs = get("https://api.stlouisfed.org/fred/series/observations", params={
            "series_id": series, "api_key": key, "file_type": "json",
            "observation_start": start}).json()["observations"]
    except Exception as e:
        raise RuntimeError(str(e).replace(key, "***")) from None
    out = [(o["date"].replace("-", ""), float(o["value"])) for o in obs if o["value"] != "."]
    if not out:
        raise RuntimeError(f"{series} 비어 있음")
    return out


def history_for(stock):
    if stock["market"] == "KR":
        return first_ok(lambda: naver_fchart(stock["code"]),
                        lambda: naver_chart_api("domestic", stock["code"]))
    fns = [lambda: cnbc_bars(stock["code"])]
    if stock.get("naver"):
        fns.append(lambda: naver_chart_api("foreign", stock["naver"]))
    return first_ok(*fns)


# ---------------- 계산 도우미 ----------------

def closes(rows):
    return [c for _, c in rows]


def ma(rows, n):
    c = closes(rows)
    return sum(c[-n:]) / n if len(c) >= n else None


def ret(rows, days):
    """days 거래일 전 대비 수익률(%)."""
    c = closes(rows)
    return (c[-1] / c[-1 - days] - 1) * 100 if len(c) > days else None


def value_days_ago(rows, cal_days):
    """달력 기준 cal_days 일 전(또는 그 직전) 값."""
    target = (datetime.strptime(rows[-1][0], "%Y%m%d") - timedelta(days=cal_days)).strftime("%Y%m%d")
    past = [v for d, v in rows if d <= target]
    return past[-1] if past else None


def drawdown_52w(rows):
    c = closes(rows)[-250:]
    return (c[-1] / max(c) - 1) * 100


def volatility(rows, n=20):
    c = closes(rows)[-(n + 1):]
    r = [(c[i] / c[i - 1] - 1) for i in range(1, len(c))]
    if len(r) < 5:
        return None
    m = sum(r) / len(r)
    sd = (sum((x - m) ** 2 for x in r) / (len(r) - 1)) ** 0.5
    return sd * (252 ** 0.5) * 100  # 연율(%)


# ---------------- 가치 지표 (PER 등) ----------------

def _peer_pers(items, self_code):
    """업종 비교 목록에서 PER 숫자를 찾아낸다(필드 이름이 바뀌어도 'per' 를 찾음)."""
    pers, keys = [], set()
    for it in items or []:
        if not isinstance(it, dict):
            continue
        keys.update(it.keys())
        if str(it.get("itemCode") or it.get("reutersCode") or "") == self_code:
            continue
        for k, v in it.items():
            if k.lower() == "per":
                p = num(v.get("value") if isinstance(v, dict) else v)
                if p and 0 < p < 300:
                    pers.append(p)
    return pers, sorted(keys)


def valuation_kr(code):
    j = get(f"https://m.stock.naver.com/api/stock/{code}/integration").json()
    infos = {x.get("code"): x.get("value") for x in j.get("totalInfos", [])}
    per, fper, pbr = num(infos.get("per")), num(infos.get("cnsPer")), num(infos.get("pbr"))
    pers, keys = _peer_pers(j.get("industryCompareInfo"), code)
    return {"per": per, "fwd_per": fper, "pbr": pbr,
            "peer_per_median": median(pers) if pers else None,
            "peer_count": len(pers), "peer_keys": keys[:15], "source": "네이버"}


def valuation_us(stock):
    from collect import _cnbc_cache, cnbc_all
    if stock["code"] not in _cnbc_cache:
        cnbc_all([stock["code"]])
    q = _cnbc_cache.get(stock["code"], {})
    out = {"per": num(q.get("pe")), "fwd_per": num(q.get("fpe")), "pbr": None,
           "peer_per_median": None, "peer_count": 0, "peer_keys": [], "source": "CNBC"}
    if stock.get("naver"):
        try:
            j = get(f"https://api.stock.naver.com/stock/{stock['naver']}/integration").json()
            items = (j.get("industryCompareInfo") or {}).get("globalStocks", [])
            pers, keys = _peer_pers(items, stock["naver"])
            out.update(peer_per_median=median(pers) if pers else None,
                       peer_count=len(pers), peer_keys=keys[:15])
        except Exception:
            pass
    return out


def valuation_for(stock):
    if stock["market"] == "KR":
        return first_ok(lambda: valuation_kr(stock["code"]))
    return first_ok(lambda: valuation_us(stock))


# ---------------- 시장 점수용 시계열 ----------------

def market_series():
    return {
        "kospi": first_ok(lambda: naver_fchart("KOSPI")),
        "spx": first_ok(lambda: cnbc_bars(".SPX"), lambda: fred_series("SP500")),
        "dxy": first_ok(lambda: cnbc_bars(".DXY"), lambda: fred_series("DTWEXBGS")),
        "usdkrw": first_ok(lambda: cnbc_bars("KRW="), lambda: fred_series("DEXKOUS")),
        "us10y": first_ok(lambda: fred_series("DGS10")),
        "hy": first_ok(lambda: fred_series("BAMLH0A0HYM2")),
        "t10y2y": first_ok(lambda: fred_series("T10Y2Y")),
    }
