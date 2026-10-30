"""3-4단계: 시장 점수(9개 항목)와 종목 점수(5개 항목) 계산.

각 항목은 0~100점. 값이 없는 항목은 빼고 남은 가중치로 다시 100점 만점 환산.
점수 기준표(POINTS)는 '값 → 점수' 꺾은선이며, 사이 값은 비례해서 계산한다.
"""

from history import (closes, drawdown_52w, ma, ret, value_days_ago, volatility)

ZONES = [(80, "맑음", "☀️"), (60, "구름 조금", "🌤"), (40, "흐림", "☁️"), (20, "비", "🌧"), (0, "폭풍", "⛈")]


def zone(score):
    for lo, name, icon in ZONES:
        if score >= lo:
            return {"name": name, "icon": icon}
    return {"name": "폭풍", "icon": "⛈"}


def interp(x, points):
    """꺾은선 점수표. points = [(값, 점수), ...] 값 오름차순."""
    if x is None:
        return None
    if x <= points[0][0]:
        return float(points[0][1])
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if x <= x1:
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    return float(points[-1][1])


def avg(*vals, weights=None):
    pairs = [(v, w) for v, w in zip(vals, weights or [1] * len(vals)) if v is not None]
    if not pairs:
        return None
    return sum(v * w for v, w in pairs) / sum(w for _, w in pairs)


def combine(items):
    """items: [{'key','name','weight','score',...}] → 종합 점수(빈 항목 제외 후 환산)."""
    used = [i for i in items if i["score"] is not None]
    wsum = sum(i["weight"] for i in used)
    if not wsum:
        return None, items
    for i in items:
        i["score"] = None if i["score"] is None else round(i["score"])
        i["points"] = None if i["score"] is None else round(i["weight"] * i["score"] / wsum, 1)
    total = round(sum(i["points"] for i in used))
    return total, items


def ok(rows):
    return rows if isinstance(rows, list) else None


def pct_vs_ma(rows, n):
    m = ma(rows, n) if rows else None
    return (closes(rows)[-1] / m - 1) * 100 if m else None


def chg_1m(rows, pct=True):
    if not rows:
        return None
    past = value_days_ago(rows, 30)
    if past is None:
        return None
    return (rows[-1][1] / past - 1) * 100 if pct else rows[-1][1] - past


# ---------------- 시장 점수 ----------------

TREND_PTS = [(-10, 0), (-3, 25), (0, 50), (3, 75), (8, 100)]


def market_score(data, series):
    ind = data["indicators"]
    sp, nq = ok(series["spx"]), ok(series.get("nasdaq"))
    dxy, krw = ok(series["dxy"]), ok(series["usdkrw"])
    t10, hy, curve = ok(series["us10y"]), ok(series["hy"]), ok(series["t10y2y"])

    sp_d, nq_d = pct_vs_ma(sp, 200), pct_vs_ma(nq, 200)
    vix = ind["vix"].get("price")
    hy_now = ind["hy_spread"].get("price") or (hy[-1][1] if hy else None)
    hy_chg = chg_1m(hy, pct=False)
    y10 = ind["us10y"].get("price") or (t10[-1][1] if t10 else None)
    y10_chg = chg_1m(t10, pct=False)
    fg = ind["fear_greed"].get("price")
    dxy_c, krw_c = chg_1m(dxy), chg_1m(krw)
    curve_now = curve[-1][1] if curve else None
    md = ind["margin_debt"].get("price")

    hy_score = interp(hy_now, [(2.5, 100), (3.0, 90), (3.5, 75), (4.5, 50), (5.5, 25), (7, 0)])
    if hy_score is not None and hy_chg is not None:
        hy_score -= interp(hy_chg, [(0.0, 0), (0.3, 10), (0.6, 20)])

    def f(v, fmt):
        return None if v is None else fmt.format(v)

    items = [
        {"key": "trend", "name": "추세 (S&P500·나스닥 200일 평균)", "weight": 25,
         "score": avg(interp(sp_d, TREND_PTS), interp(nq_d, TREND_PTS)),
         "value": f"S&P {f(sp_d, '{:+.1f}%') or '-'}, 나스닥 {f(nq_d, '{:+.1f}%') or '-'} (200일 평균 대비)"},
        {"key": "vix", "name": "VIX", "weight": 15,
         "score": interp(vix, [(12, 100), (15, 90), (20, 65), (25, 40), (30, 20), (40, 0)]),
         "value": f(vix, "{:.2f}")},
        {"key": "hy", "name": "하이일드 스프레드", "weight": 15, "score": hy_score,
         "value": f"{f(hy_now, '{:.2f}%p') or '-'}, 1달 {f(hy_chg, '{:+.2f}%p') or '-'}"},
        {"key": "us10y", "name": "미국 10년물 금리", "weight": 10,
         "score": avg(interp(y10, [(3.0, 100), (3.5, 85), (4.0, 70), (4.5, 50), (5.0, 30), (5.5, 10)]),
                      interp(y10_chg, [(-0.4, 100), (0, 60), (0.3, 30), (0.6, 0)]), weights=[0.6, 0.4]),
         "value": f"{f(y10, '{:.2f}%') or '-'}, 1달 {f(y10_chg, '{:+.2f}%p') or '-'}"},
        {"key": "fear_greed", "name": "공포탐욕지수", "weight": 10,
         "score": interp(fg, [(0, 30), (20, 40), (45, 100), (65, 100), (80, 50), (100, 20)]),
         "value": f"{fg} {ind['fear_greed'].get('label', '')}" if fg is not None else None},
        {"key": "move", "name": "MOVE (채권 불안)", "weight": 5,
         "score": interp(ind.get("move", {}).get("price"), [(70, 100), (80, 90), (100, 60), (120, 30), (150, 0)]),
         "value": f(ind.get("move", {}).get("price"), "{:.1f}")},
        {"key": "dxy", "name": "달러지수 1달 변화", "weight": 5,
         "score": interp(dxy_c, [(-3, 100), (-1, 75), (0, 60), (1, 45), (3, 15)]),
         "value": f(dxy_c, "{:+.1f}%")},
        {"key": "usdkrw", "name": "원/달러 1달 변화", "weight": 5,
         "score": interp(krw_c, [(-3, 100), (-1, 75), (0, 60), (1, 45), (3, 15)]),
         "value": None if krw_c is None else f"{krw_c:+.1f}% ({'원화 강세' if krw_c < 0 else '원화 약세'})"},
        {"key": "curve", "name": "장단기 금리차", "weight": 5,
         "score": interp(curve_now, [(-1, 0), (-0.5, 20), (0, 45), (0.5, 75), (1.0, 90), (1.5, 100)]),
         "value": f(curve_now, "{:+.2f}%p")},
        {"key": "margin_debt", "name": "Margin Debt 전년비", "weight": 5,
         "score": interp(md, [(-30, 30), (-10, 60), (0, 75), (10, 80), (20, 65), (30, 40), (50, 10)]),
         "value": f(md, "{:+.1f}%") or "입력 필요"},
    ]
    total, items = combine(items)
    return {"score": total, **(zone(total) if total is not None else {}), "items": items}


# ---------------- 종목 점수 ----------------

SENTI = {-2: 10, -1: 30, 0: 50, 1: 75, 2: 95}


def stock_score(stock, rows, bench, val, sentiment):
    rows = ok(rows)
    bench = ok(bench)
    v = val if isinstance(val, dict) and not val.get("error") else {}

    # 1) 추세: 20·60·200일 평균 대비 위치
    d20, d60, d200 = (pct_vs_ma(rows, n) if rows else None for n in (20, 60, 200))
    trend_pts = [(-10, 0), (0, 50), (10, 100)]
    trend = avg(interp(d20, trend_pts), interp(d60, trend_pts), interp(d200, trend_pts),
                weights=[0.25, 0.35, 0.4])

    # 2) 상대 강도: 1·3개월 수익률 - 시장 수익률
    rs = None
    if rows and bench:
        r1, r3 = ret(rows, 21), ret(rows, 63)
        b1, b3 = ret(bench, 21), ret(bench, 63)
        if None not in (r1, b1, r3, b3):
            rs = 0.4 * (r1 - b1) + 0.6 * (r3 - b3)
    rs_score = interp(rs, [(-20, 0), (-5, 30), (0, 50), (5, 70), (20, 100)])

    # 3) 뉴스 분위기
    news = SENTI.get(sentiment) if sentiment is not None else None

    # 4) 위험도: 1년 고점 대비 하락폭 + 최근 출렁임
    dd = drawdown_52w(rows) if rows else None
    vol = volatility(rows) if rows else None
    risk = avg(interp(dd, [(-40, 0), (-25, 25), (-10, 60), (-3, 85), (0, 90)]),
               interp(vol, [(15, 100), (30, 70), (50, 40), (80, 10)]))

    # 5) 가격 수준: 내년 예상 PER / 올해 PER, 업종 대비 PER
    per, fper, peer = v.get("per"), v.get("fwd_per"), v.get("peer_per_median")
    fwd_ratio = fper / per if per and fper and per > 0 and fper > 0 else None
    peer_ratio = per / peer if per and peer and per > 0 else None
    level = interp(per, [(8, 90), (15, 75), (25, 55), (40, 35), (80, 15), (150, 0)]) if per and per > 0 else None
    value = avg(interp(fwd_ratio, [(0.6, 100), (0.8, 80), (1.0, 55), (1.2, 30), (1.5, 10)]),
                interp(peer_ratio, [(0.5, 100), (0.8, 75), (1.0, 55), (1.3, 30), (2.0, 5)]),
                level, weights=[0.3, 0.4, 0.3])

    def f(x, fmt):
        return None if x is None else fmt.format(x)

    items = [
        {"key": "trend", "name": "추세", "weight": 30, "score": trend,
         "value": f"20일 {f(d20, '{:+.1f}%') or '-'} · 60일 {f(d60, '{:+.1f}%') or '-'} · 200일 {f(d200, '{:+.1f}%') or '-'}"},
        {"key": "rs", "name": "상대 강도", "weight": 20, "score": rs_score,
         "value": f"시장 대비 {f(rs, '{:+.1f}%p') or '-'}"},
        {"key": "news", "name": "뉴스 분위기", "weight": 20, "score": news,
         "value": f"{sentiment:+d}" if sentiment is not None else None},
        {"key": "risk", "name": "위험도", "weight": 15, "score": risk,
         "value": f"고점 대비 {f(dd, '{:.1f}%') or '-'} · 출렁임 {f(vol, '{:.0f}%') or '-'}"},
        {"key": "value", "name": "가격 수준", "weight": 15, "score": value,
         "value": f"PER {f(per, '{:.1f}') or '-'} · 내년 {f(fper, '{:.1f}') or '-'} · 업종 {f(peer, '{:.1f}') or '-'}"},
    ]
    total, items = combine(items)
    return {"score": total, **(zone(total) if total is not None else {}), "items": items}
