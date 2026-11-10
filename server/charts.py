"""그래프용 과거 숫자 모음 → public/charts.json

앱이 지표·종목 카드에서 1달 / 3달 / 1년 그래프를 그릴 때 쓴다.
한 출처가 실패하면 예비 출처, 그것도 실패하면 지난번 파일의 숫자를 그대로 쓴다.
"""
import json
from datetime import datetime, timedelta, timezone

from collect import KST, ROOT, S, first_ok, get, iso, now_kst
from bisect import bisect_right

from history import cnbc_bars, fred_series

KEEP_DAYS = 370   # 1년 + 여유


def _trim(rows):
    """[(YYYYMMDD, 값)] → 최근 1년, 날짜 순, 숫자 4자리 유효."""
    cut = (now_kst() - timedelta(days=KEEP_DAYS)).strftime("%Y%m%d")
    out, seen = [], set()
    for d, v in sorted(rows):
        d = str(d).replace("-", "")[:8]
        if d < cut or d in seen or v is None:
            continue
        seen.add(d)
        out.append([d, float(f"{v:.6g}")])
    return out


def fear_greed_hist():
    j = get("https://production.dataviz.cnn.io/index/fearandgreed/graphdata",
            headers={"Referer": "https://edition.cnn.com/", "Origin": "https://edition.cnn.com"}).json()
    data = j["fear_and_greed_historical"]["data"]
    rows = [(datetime.fromtimestamp(p["x"] / 1000, tz=timezone.utc).astimezone(KST).strftime("%Y%m%d"),
             round(p["y"], 1)) for p in data]
    if len(rows) < 30:
        raise RuntimeError(f"공포탐욕 {len(rows)}개뿐")
    return rows


def coingecko_hist(coin):
    j = get(f"https://api.coingecko.com/api/v3/coins/{coin}/market_chart",
            params={"vs_currency": "usd", "days": "365", "interval": "daily"}).json()
    rows = [(datetime.fromtimestamp(t / 1000, tz=timezone.utc).strftime("%Y%m%d"), v) for t, v in j["prices"]]
    if len(rows) < 30:
        raise RuntimeError(f"{coin} {len(rows)}개뿐")
    return rows


def kraken_hist(pair):
    since = int((datetime.now(timezone.utc) - timedelta(days=KEEP_DAYS)).timestamp())
    j = get("https://api.kraken.com/0/public/OHLC", params={"pair": pair, "interval": 1440, "since": since}).json()
    res = next(v for k, v in j["result"].items() if k != "last")
    return [(datetime.fromtimestamp(r[0], tz=timezone.utc).strftime("%Y%m%d"), float(r[4])) for r in res]


def margin_hist():
    """잔고 자체(조 달러). 증가율은 1년 전 숫자가 있어야 해서 점이 너무 적다."""
    m = json.loads((ROOT / "data/manual/margin_debt.json").read_text(encoding="utf-8"))
    hist = {k: v for k, v in (m.get("history") or {}).items() if v}
    return [[k.replace("-", "") + "28", round(hist[k] / 1e6, 3)] for k in sorted(hist)]


def _upto(rows, d):
    """날짜 d(YYYYMMDD)까지의 행만"""
    if not isinstance(rows, list):
        return rows
    i = bisect_right([r[0] for r in rows], d)
    return rows[:i] if i >= 30 else {"error": "과거 부족"}


def _at(rows, d):
    """d 이전(포함) 마지막 값"""
    if not isinstance(rows, list) or not rows:
        return None
    i = bisect_right([str(r[0]) for r in rows], d)
    return rows[i - 1][1] if i else None


def backfill_scores(series, vix, move, fg):
    """과거 숫자로 지난 1년 날씨 점수를 거꾸로 계산 → [[날짜, 점수]]"""
    from scoring import market_score
    spx = series.get("spx")
    if not isinstance(spx, list):
        return []
    m = json.loads((ROOT / "data/manual/margin_debt.json").read_text(encoding="utf-8"))
    mh = {k.replace("-", ""): v for k, v in (m.get("history") or {}).items() if v}
    cut = (now_kst() - timedelta(days=KEEP_DAYS)).strftime("%Y%m%d")
    tup = {k: [tuple(r) for r in v] if isinstance(v, list) else v for k, v in series.items()}
    out = []
    for d, _ in spx:
        if d < cut:
            continue
        months = [k for k in mh if k + "28" <= d]
        md = None
        if months:
            k = max(months)
            ago = mh.get(f"{int(k[:4]) - 1}{k[4:]}")
            md = (mh[k] / ago - 1) * 100 if ago else None
        data = {"indicators": {
            "vix": {"price": _at(vix, d)}, "move": {"price": _at(move, d)},
            "fear_greed": {"price": _at(fg, d)}, "margin_debt": {"price": md},
            "hy_spread": {"price": None}, "us10y": {"price": None},
        }}
        ser = {k: _upto(v, d) for k, v in tup.items()}
        try:
            sc = market_score(data, ser)["score"]
        except Exception:
            sc = None
        if sc is not None:
            out.append([d, sc])
    return out


def ma_line(rows, n=200):
    """n일 이동평균 선 → [(날짜, 값)]"""
    if not isinstance(rows, list) or len(rows) < n:
        return {"error": "기간 부족"}
    c = [v for _, v in rows]
    return [(rows[i][0], sum(c[i - n + 1:i + 1]) / n) for i in range(n - 1, len(rows))]


def load_previous_charts(config):
    url = (config.get("publish_url") or "").rsplit("/", 1)[0] + "/charts.json"
    try:
        r = S.get(url, timeout=15)
        if r.status_code == 200:
            return r.json().get("series", {})
    except Exception:
        pass
    return {}


def build_charts(config, series, stock_rows, score_hist, ndx_rows=None):
    """series: market_series() 결과, stock_rows: {종목코드: [(날짜, 종가)]}"""
    prev = load_previous_charts(config)
    out, errors = {}, []

    def put(key, rows_or_err, min_points=5):
        if isinstance(rows_or_err, list) and rows_or_err:
            pts = _trim(rows_or_err) if rows_or_err and isinstance(rows_or_err[0], tuple) else rows_or_err
            if len(pts) >= min_points:
                out[key] = pts
                return
        if key in prev:
            out[key] = prev[key]
        err = rows_or_err.get("error") if isinstance(rows_or_err, dict) else "비어 있음"
        errors.append(f"{key}: {str(err)[:100]}")

    def s(name):
        v = series.get(name)
        return v if isinstance(v, list) else {"error": "시장 시계열 실패"}

    # 시장
    put("kospi", s("kospi"))
    put("spx", s("spx"))
    put("nasdaq", s("nasdaq"))
    # 점수 지표
    put("usdkrw", s("usdkrw"))
    put("dxy", s("dxy"))
    put("us10y", s("us10y"))
    put("hy_spread", s("hy"))
    put("curve", s("t10y2y"))
    put("vix", first_ok(lambda: cnbc_bars(".VIX"), lambda: fred_series("VIXCLS")))
    put("move", first_ok(lambda: cnbc_bars(".MOVE")))
    put("fear_greed", first_ok(fear_greed_hist))
    put("margin_debt", first_ok(margin_hist), 2)
    # 모니터링 지표
    put("wti", first_ok(lambda: cnbc_bars("@CL.1"), lambda: fred_series("DCOILWTICO")))
    put("nq_fut", first_ok(lambda: cnbc_bars("@ND.1"), lambda: cnbc_bars(".NDX")))
    put("sox", first_ok(lambda: cnbc_bars(".SOX")))
    put("usdjpy", first_ok(lambda: cnbc_bars("JPY="), lambda: fred_series("DEXJPUS")))
    put("us2y", first_ok(lambda: cnbc_bars("US2Y"), lambda: fred_series("DGS2")))
    put("gold", first_ok(lambda: cnbc_bars("@GC.1")))
    put("bei10", first_ok(lambda: fred_series("T10YIE")))
    # 역발상·과열 신호
    from signals import put_call_history, rsi_series
    try:
        put("put_call", put_call_history(prev.get("put_call")), 1)
    except Exception as e:
        errors.append(f"풋콜 기록 실패: {e}")
    put("rsi_ndx", rsi_series(ndx_rows) or {"error": "나스닥100 가격 없음"})
    put("bitcoin", first_ok(lambda: coingecko_hist("bitcoin"), lambda: kraken_hist("XBTUSD")))
    put("ethereum", first_ok(lambda: coingecko_hist("ethereum"), lambda: kraken_hist("ETHUSD")))
    # 마켓 날씨 점수 (하루 1개씩 쌓인 기록)
    real = [[h["date"].replace("-", ""), h["score"]] for h in score_hist if h.get("score") is not None]
    # 200일 평균은 1년 전에도 계산되도록 S&P·나스닥은 2년 치를 따로 받는다
    long = dict(series)
    for k, sym, fk in (("spx", ".SPX", "SP500"), ("nasdaq", ".IXIC", "NASDAQCOM")):
        r = first_ok(lambda s_=sym: cnbc_bars(s_, 760), lambda f_=fk: fred_series(f_, 760))
        if isinstance(r, list) and len(r) > 300:
            long[k] = r
    try:
        back = backfill_scores(long, out.get("vix"), out.get("move"), out.get("fear_greed"))
    except Exception as e:
        back = []
        errors.append(f"점수 거꾸로 계산 실패: {e}")
    first_real = real[0][0] if real else "99999999"
    put("market_score", [p for p in back if p[0] < first_real] + real, 1)
    put("spx_ma200", ma_line(long.get("spx")))
    put("rsi_spx", rsi_series(long.get("spx")) or {"error": "S&P500 가격 없음"})
    # 종목
    for code, rows in stock_rows.items():
        put("stock:" + code, rows if isinstance(rows, list) else {"error": rows.get("error", "")})

    return {"generated_at": iso(now_kst()), "series": out}, errors


def save_charts(charts):
    out = ROOT / "public"
    out.mkdir(exist_ok=True)
    text = json.dumps(charts, ensure_ascii=False, separators=(",", ":"))
    (out / "charts.json").write_text(text, encoding="utf-8")
    return len(text.encode("utf-8"))
