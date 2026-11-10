"""역발상·과열 신호: 주식 풋/콜 비율(CBOE) + RSI(14일).

- 풋/콜: 풋(하락 베팅) ÷ 콜(상승 베팅). 높을수록 겁먹은 상태 → 0.8~1 근처는 역발상 매수 신호로 본다.
- RSI: 최근 14일 오른 폭과 내린 폭의 비율. 30 아래 과매도, 70 위 과열.
"""
from datetime import datetime, timedelta

from collect import KST, S, first_ok, iso, now_kst
from history import cnbc_bars

CBOE = "https://cdn.cboe.com/data/us/options/market_statistics/daily/{d}_daily_options"


_ratio_cache = {}
NAMES = {"equity": "EQUITY PUT/CALL RATIO", "total": "TOTAL PUT/CALL RATIO"}


def cboe_ratios(d):
    """d(YYYY-MM-DD) 하루의 풋/콜 비율들 {equity, total}. 자료가 없으면 {}"""
    if d in _ratio_cache:
        return _ratio_cache[d]
    r = S.get(CBOE.format(d=d), timeout=15)
    out = {}
    if r.status_code == 200:
        for x in r.json().get("ratios", []):
            for k, nm in NAMES.items():
                if x.get("name") == nm:
                    try:
                        out[k] = float(x["value"])
                    except (TypeError, ValueError):
                        pass
    _ratio_cache[d] = out
    return out


def cboe_equity_pc(d):
    return cboe_ratios(d).get("equity")


def put_call_latest(kind="equity"):
    """가장 최근 거래일의 풋/콜 비율 + 그 전 거래일과의 차이"""
    found = []
    for back in range(0, 10):
        d = (now_kst() - timedelta(days=back)).strftime("%Y-%m-%d")
        v = cboe_ratios(d).get(kind)
        if v is not None:
            found.append((d, v))
            if len(found) == 2:
                break
    if not found:
        raise RuntimeError("CBOE 최근 10일 자료 없음")
    (d, v) = found[0]
    prev = found[1][1] if len(found) > 1 else None
    hi, mid, lo = (0.8, 0.7, 0.5) if kind == "equity" else (1.0, 0.9, 0.7)
    label = "공포 (역발상 매수 신호)" if v >= hi else "불안" if v >= mid else "보통" if v >= lo else "지나친 낙관"
    return {"price": v, "change": round(v - prev, 2) if prev is not None else None, "label": label,
            "as_of": f"{d} (미국 장 마감)", "source": "CBOE"}


def rsi_series(rows, n=14):
    """[(날짜, 종가)] → [(날짜, RSI)] (와일더 방식)"""
    if not isinstance(rows, list) or len(rows) < n + 2:
        return []
    closes = [c for _, c in rows]
    gains = [max(closes[i] - closes[i - 1], 0) for i in range(1, len(closes))]
    losses = [max(closes[i - 1] - closes[i], 0) for i in range(1, len(closes))]
    ag = sum(gains[:n]) / n
    al = sum(losses[:n]) / n
    out = []
    for i in range(n, len(gains)):
        ag = (ag * (n - 1) + gains[i]) / n
        al = (al * (n - 1) + losses[i]) / n
        rsi = 100.0 if al == 0 else 100 - 100 / (1 + ag / al)
        out.append((rows[i + 1][0], round(rsi, 1)))
    return out


def rsi_indicator(rows, name_src):
    s = rsi_series(rows)
    if not s:
        raise RuntimeError(f"{name_src} 과거 가격 부족")
    d, v = s[-1]
    prev = s[-2][1] if len(s) > 1 else None
    label = "과매도 (반등 가능)" if v <= 30 else "과열" if v >= 70 else "보통"
    return {"price": v, "change": round(v - prev, 1) if prev is not None else None, "label": label,
            "as_of": f"{d[:4]}-{d[4:6]}-{d[6:8]} 종가 기준", "source": f"{name_src} 종가로 계산"}


def add_signals(data, series):
    """data['monitors'] 에 풋/콜·RSI 추가. series: market_series() 결과"""
    mon = data.setdefault("monitors", {})
    mon["put_call"] = {"name": "주식 풋/콜 비율", **first_ok(lambda: put_call_latest("equity"))}
    mon["put_call_total"] = {"name": "전체 풋/콜 비율", **first_ok(lambda: put_call_latest("total"))}
    ndx = first_ok(lambda: cnbc_bars("@ND.1"), lambda: cnbc_bars(".NDX"))
    data["_ndx_rows"] = ndx if isinstance(ndx, list) else None
    mon["rsi_ndx"] = {"name": "RSI 나스닥100 (14일)", **first_ok(lambda: rsi_indicator(ndx, "나스닥100 선물"))}
    spx = series.get("spx")
    mon["rsi_spx"] = {"name": "RSI S&P500 (14일)", **first_ok(lambda: rsi_indicator(spx, "S&P500"))}


def put_call_history(prev_points, kind="equity", max_fetch=130):
    """그래프용 풋/콜 기록: 지난 기록 + 빠진 날만 새로 받기 (한 번에 최대 max_fetch 일)"""
    have = {p[0]: p[1] for p in (prev_points or [])}
    today = now_kst().date()
    fetched = 0
    for back in range(0, 370):
        day = today - timedelta(days=back)
        if day.weekday() >= 5:
            continue
        key = day.strftime("%Y%m%d")
        if key in have:
            continue
        ds = day.strftime("%Y-%m-%d")
        if ds not in _ratio_cache:
            if fetched >= max_fetch:
                break
            fetched += 1
        try:
            v = cboe_ratios(ds).get(kind)
        except Exception:
            v = None
        if v is not None:
            have[key] = v
    cut = (today - timedelta(days=370)).strftime("%Y%m%d")
    return [[k, have[k]] for k in sorted(have) if k >= cut]
