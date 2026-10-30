"""3-5단계: 앱이 읽을 결과 파일(latest.json) 만들기.

- 시세·지표·AI 요약·점수를 한 파일에 담는다.
- 지난번 파일을 읽어서 '어제 점수'와 '시황(하루 5번만 새로)'을 이어 쓴다.
- 보유 수량·평단 같은 개인 정보는 서버에 없다(휴대폰에만 저장).
"""
import json
import os
from datetime import datetime, timedelta

from collect import KST, ROOT, S, iso, now_kst

VERSION = 1

# 지표별 갱신 주기(화면 표시용)
CYCLE = {
    "usdkrw": "30분", "dxy": "30분", "us10y": "30분", "vix": "美장중",
    "hy_spread": "매일 23시", "fear_greed": "美장중", "margin_debt": "월 1회",
}


def load_previous(config):
    """지난번 결과: 공개 주소 → 로컬 파일 순서로 찾는다."""
    url = config.get("publish_url")
    if url:
        try:
            r = S.get(url, timeout=15)
            if r.status_code == 200:
                return r.json()
        except Exception:
            pass
    p = ROOT / "data/latest.json"
    if p.exists():
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {}


def is_summary_time(config, now=None):
    """시황은 정해진 시각(06·08·12·15:40·21시) 직후 30분 안에만 새로 만든다."""
    now = now or now_kst()
    for t in config.get("summary_times_kst", []):
        h, m = map(int, t.split(":"))
        start = now.replace(hour=h, minute=m, second=0, microsecond=0)
        if start <= now < start + timedelta(minutes=30):
            return True
    return False


def parse_when(as_of):
    """'2026-10-30T16:54+09:00' 또는 '2026-10-28 (미국 날짜)' → datetime(KST)."""
    if not as_of:
        return None
    s = str(as_of)
    try:
        return datetime.fromisoformat(s.split(" ")[0]).astimezone(KST) if "T" in s else \
            datetime.strptime(s[:10], "%Y-%m-%d").replace(tzinfo=KST)
    except ValueError:
        return None


def is_stale(as_of, now, date_only=False):
    """하루 넘게 지난 숫자인가? (날짜만 있는 값은 이틀 이상 지나면)"""
    t = parse_when(as_of)
    if not t:
        return False
    if date_only or "T" not in str(as_of):
        return (now.date() - t.date()).days >= 2
    return now - t > timedelta(hours=24)


def score_history(prev, score, now):
    """하루 한 번(그날 마지막 값) 점수 기록 → 어제 점수 비교용."""
    hist = [h for h in prev.get("market_score", {}).get("history", []) if h.get("date")]
    today = now.strftime("%Y-%m-%d")
    hist = [h for h in hist if h["date"] != today] + [{"date": today, "score": score}]
    return hist[-30:]


def build(config, data, issues, market_lines, mscore, mcomment, sscores, scomments, prev, meta):
    now = now_kst()

    # 시황: 정해진 시각이 아니면 지난번 것을 그대로
    prev_summary = prev.get("market_summary") or {}
    if market_lines:
        summary = {"lines": market_lines, "generated_at": iso(now)}
    else:
        summary = prev_summary or {"lines": [], "generated_at": None}

    hist = score_history(prev, mscore.get("score"), now) if mscore.get("score") is not None else \
        prev.get("market_score", {}).get("history", [])
    yesterday = next((h["score"] for h in reversed(hist) if h["date"] < now.strftime("%Y-%m-%d")), None)

    stocks = []
    for s in data["stocks"]:
        q = s.get("quote") or {}
        sc = sscores.get(s["name"], {})
        iss = issues.get(s["name"], {})
        stocks.append({
            "name": s["name"], "code": s["code"], "market": s["market"],
            "currency": "KRW" if s["market"] == "KR" else "USD",
            "price": q.get("price"), "change": q.get("change"), "change_pct": q.get("change_pct"),
            "as_of": q.get("as_of"), "market_status": q.get("market_status"), "source": q.get("source"),
            "error": q.get("error"),
            "issue": iss.get("issue"), "sentiment": iss.get("sentiment"), "news": iss.get("news", []),
            "issue_basis": f"{iso(now)}까지 최근 24시간 뉴스",
            "score": sc.get("score"), "zone": sc.get("name"), "icon": sc.get("icon"),
            "score_items": sc.get("items", []), "comment": scomments.get(s["name"]),
        })

    indicators = []
    for key, v in data["indicators"].items():
        date_only = key == "hy_spread"
        indicators.append({
            "key": key, "name": v.get("name"), "value": v.get("price"), "label": v.get("label"),
            "change": v.get("change"), "change_pct": v.get("change_pct"),
            "as_of": v.get("as_of"), "published": v.get("published"), "source": v.get("source"),
            "cycle": CYCLE.get(key), "stale": bool(v.get("need_input")) or key == "margin_debt" or is_stale(v.get("as_of"), now, date_only),
            "need_input": bool(v.get("need_input")), "error": None if v.get("price") is not None else v.get("error"),
        })

    return {
        "version": VERSION,
        "title": "오늘의 시장",
        "generated_at": iso(now),
        "market_score": {
            "score": mscore.get("score"), "zone": mscore.get("name"), "icon": mscore.get("icon"),
            "yesterday": yesterday, "comment": mcomment, "items": mscore.get("items", []),
            "history": hist,
        },
        "market_summary": summary,
        "markets": {k: {"name": v.get("name"), "value": v.get("price"), "change_pct": v.get("change_pct"),
                        "as_of": v.get("as_of")} for k, v in data.get("markets", {}).items()},
        "stocks": stocks,
        "indicators": indicators,
        "meta": meta,
    }


def save(result):
    out = ROOT / "public"
    out.mkdir(exist_ok=True)
    text = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
    (out / "latest.json").write_text(text, encoding="utf-8")
    (ROOT / "data").mkdir(exist_ok=True)
    (ROOT / "data/latest.json").write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    return len(text.encode("utf-8"))
