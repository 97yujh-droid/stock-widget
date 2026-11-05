"""관심 종목 실적 발표일·지난 실적 (미국: Nasdaq, 한국: 네이버 증권사 리포트 제목).

같은 날 여러 번 묻지 않도록 6시간 동안은 지난번 결과를 다시 쓴다.
"""
import re
from datetime import datetime, timedelta

from collect import UA, get, iso, now_kst
from publish import parse_when

NAS_H = {"User-Agent": UA, "Accept": "application/json, text/plain, */*",
         "Origin": "https://www.nasdaq.com", "Referer": "https://www.nasdaq.com/"}
REUSE_HOURS = 6


def _num(s):
    if s is None:
        return None
    m = re.search(r"-?\d+(?:\.\d+)?", str(s).replace(",", "").replace("$", ""))
    return float(m.group()) if m else None


def nasdaq_next(sym):
    d = get(f"https://api.nasdaq.com/api/analyst/{sym}/earnings-date", headers=NAS_H).json().get("data") or {}
    text = d.get("reportText") or ""
    m = re.search(r"(\d{1,2})/(\d{1,2})/(\d{4})", text)
    if not m:
        return {"next_date": None, "note": "회사가 아직 날짜를 안 정했어요"}
    mo, dd, yy = map(int, m.groups())
    timing = None
    low = text.lower()
    if "after market close" in low:
        timing = "after"
    elif "before market open" in low:
        timing = "before"
    eps = re.search(r"consensus EPS forecast for the quarter is \$(-?\d+(?:\.\d+)?)", text)
    return {"next_date": f"{yy:04d}-{mo:02d}-{dd:02d}", "timing": timing,
            "eps_forecast": float(eps.group(1)) if eps else None,
            "estimated": "expected*" in low or "*" in text}


def nasdaq_last(sym):
    d = get(f"https://api.nasdaq.com/api/company/{sym}/earnings-surprise", headers=NAS_H).json().get("data") or {}
    rows = ((d.get("earningsSurpriseTable") or {}).get("rows")) or []
    if not rows:
        return None
    r = rows[0]
    date = None
    m = re.search(r"(\d{1,2})/(\d{1,2})/(\d{4})", r.get("dateReported") or "")
    if m:
        mo, dd, yy = map(int, m.groups())
        date = f"{yy:04d}-{mo:02d}-{dd:02d}"
    return {"date": date, "quarter": r.get("fiscalQtrEnd"), "eps": _num(r.get("eps")),
            "forecast": _num(r.get("consensusForecast")), "surprise_pct": _num(r.get("percentageSurprise"))}


def naver_reports(code):
    """국내 종목: 최근 증권사 리포트 제목 2개 (실적 발표일 대신)"""
    j = get(f"https://m.stock.naver.com/api/stock/{code}/integration").json()
    out = []
    for r in (j.get("researches") or [])[:2]:
        title = r.get("tit") or r.get("title") or max((v for v in r.values() if isinstance(v, str)), key=len, default="")
        broker = r.get("bnm") or r.get("brokerName") or ""
        date = r.get("wdt") or r.get("writeDate") or ""
        date = str(date)
        if re.fullmatch(r"\d{8}", date):
            date = f"{date[4:6]}/{date[6:8]}"
        out.append({"title": title, "broker": broker, "date": date[:10]})
    return out


def get_earnings(config, prev):
    now = now_kst()
    prev_e = {s.get("code"): s.get("earnings") for s in prev.get("stocks", []) if s.get("earnings")}
    out, errors = {}, []
    for s in config["stocks"]:
        code = s["code"]
        old = prev_e.get(code)
        t = parse_when(old.get("fetched_at")) if old else None
        if old and t and now - t < timedelta(hours=REUSE_HOURS):
            out[code] = old
            continue
        try:
            if s["market"] == "US":
                e = nasdaq_next(code)
                try:
                    e["last"] = nasdaq_last(code)
                except Exception as ex:
                    e["last"] = None
                    errors.append(f"{s['name']} 지난 실적: {str(ex)[:80]}")
            else:
                e = {"reports": naver_reports(code)}
            e["fetched_at"] = iso(now)
            out[code] = e
        except Exception as ex:
            errors.append(f"{s['name']}: {type(ex).__name__} {str(ex)[:80]}")
            if old:
                out[code] = old
    return out, errors


def earnings_lines(config, earnings):
    """AI 시황용: 7일 안에 발표 예정 / 3일 안에 발표된 관심 종목"""
    today = now_kst().date()
    lines = []
    for s in config["stocks"]:
        e = earnings.get(s["code"]) or {}
        nd = e.get("next_date")
        if nd:
            d = datetime.strptime(nd, "%Y-%m-%d").date()
            if 0 <= (d - today).days <= 7:
                when = {"after": "미국 장 마감 후", "before": "미국 장 시작 전"}.get(e.get("timing"), "")
                lines.append(f"- [예정] {s['name']} {d:%m/%d} {when} 실적 발표 (예상 EPS {e.get('eps_forecast') or '-'})")
        last = e.get("last") or {}
        if last.get("date"):
            d = datetime.strptime(last["date"], "%Y-%m-%d").date()
            if 0 <= (today - d).days <= 3:
                lines.append(f"- [발표됨] {s['name']} {d:%m/%d} 실적: EPS {last.get('eps')} "
                             f"(예상 {last.get('forecast')}, 차이 {last.get('surprise_pct')}%)")
    return lines
