"""주요 일정: 오늘부터 7일 (한국 날짜 기준)

- 📈 경제지표: ForexFactory (이번 주 + 다음 주), 한국 시각·예상치·실제치
- 💼 실적: Nasdaq 실적 달력에서 시가총액 큰 회사 + 내 관심 종목
- 🏛 국채 입찰: TreasuryDirect
- 📰 그 밖의 이벤트: 뉴스 제목에서 AI 가 뽑음 (6시간마다)
"""
import re
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from collect import KST, get, google_news, iso, now_kst
from earnings import NAS_H
from publish import parse_when

NY = ZoneInfo("America/New_York")
DAYS = 7
BIG_CAP = 50e9          # 이 이상 시가총액 회사만 실적 일정에 (관심 종목은 항상)
PER_DAY_EARN = 6
AI_REUSE_HOURS = 6

# 자주 나오는 경제지표 이름 (나머지는 AI 가 번역)
KO = {
    "CPI m/m": "소비자물가(CPI) 전월비", "CPI y/y": "소비자물가(CPI) 전년비", "Core CPI m/m": "근원 CPI 전월비",
    "PPI m/m": "생산자물가(PPI) 전월비", "Core PPI m/m": "근원 PPI 전월비",
    "Non-Farm Employment Change": "비농업 고용", "Unemployment Rate": "실업률",
    "Unemployment Claims": "신규 실업수당 청구", "Average Hourly Earnings m/m": "평균 시간당 임금",
    "Federal Funds Rate": "FOMC 기준금리 결정", "FOMC Statement": "FOMC 성명", "FOMC Press Conference": "FOMC 기자회견",
    "FOMC Meeting Minutes": "FOMC 의사록", "Retail Sales m/m": "소매판매 전월비", "Core Retail Sales m/m": "근원 소매판매",
    "ISM Manufacturing PMI": "ISM 제조업 지수", "ISM Services PMI": "ISM 서비스업 지수",
    "Prelim GDP q/q": "GDP 속보치", "Advance GDP q/q": "GDP 속보치", "Final GDP q/q": "GDP 확정치",
    "Core PCE Price Index m/m": "근원 PCE 물가", "JOLTS Job Openings": "JOLTS 구인건수",
    "Prelim UoM Consumer Sentiment": "미시간대 소비자심리", "CB Consumer Confidence": "컨퍼런스보드 소비자신뢰",
    "ADP Non-Farm Employment Change": "ADP 민간고용", "Empire State Manufacturing Index": "뉴욕 제조업 지수",
    "Philly Fed Manufacturing Index": "필라델피아 제조업 지수", "Building Permits": "건축허가",
    "Existing Home Sales": "기존주택 판매", "New Home Sales": "신규주택 판매", "Durable Goods Orders m/m": "내구재 주문",
    "Fed Chair Powell Speaks": "연준 의장 연설", "Crude Oil Inventories": "원유 재고",
}


def _econ():
    out = []
    for name in ("thisweek", "nextweek"):
        try:
            j = get(f"https://nfs.faireconomy.media/ff_calendar_{name}.json").json()
        except Exception:
            continue
        for e in j:
            if e.get("country") not in ("USD", "KRW") or e.get("impact") not in ("High", "Medium"):
                continue
            try:
                t = datetime.fromisoformat(e["date"]).astimezone(KST)
            except Exception:
                continue
            out.append({"t": t, "type": "econ", "country": e["country"], "en": e.get("title") or "",
                        "impact": e.get("impact"), "forecast": e.get("forecast") or None,
                        "previous": e.get("previous") or None, "actual": e.get("actual") or None})
    return out


def _cap(s):
    try:
        return float(re.sub(r"[^0-9.]", "", s or "") or 0)
    except ValueError:
        return 0.0


def _earnings(config):
    mine = {s["code"]: s["name"] for s in config["stocks"] if s["market"] == "US"}
    out = []
    today = now_kst().astimezone(NY).date()
    for i in range(-1, DAYS + 1):
        d = today + timedelta(days=i)
        if d.weekday() >= 5:
            continue
        try:
            rows = (get(f"https://api.nasdaq.com/api/calendar/earnings?date={d:%Y-%m-%d}", headers=NAS_H)
                    .json().get("data") or {}).get("rows") or []
        except Exception:
            continue
        rows = sorted(rows, key=lambda r: -_cap(r.get("marketCap")))
        picked = [r for r in rows if r.get("symbol") in mine]
        picked += [r for r in rows if _cap(r.get("marketCap")) >= BIG_CAP and r.get("symbol") not in mine][:PER_DAY_EARN]
        for r in picked:
            tm = r.get("time") or ""
            timing = "after" if "after" in tm else "before" if "pre" in tm else None
            # 한국에서 보는 때: 장 마감 후 → 다음 날 아침 6시쯤, 장 시작 전 → 그날 밤 9시쯤
            ny_t = datetime(d.year, d.month, d.day, 16, 5 if timing == "after" else 0, tzinfo=NY) if timing == "after" \
                else datetime(d.year, d.month, d.day, 7, 30, tzinfo=NY) if timing == "before" \
                else datetime(d.year, d.month, d.day, 12, 0, tzinfo=NY)
            out.append({"t": ny_t.astimezone(KST), "type": "earnings", "symbol": r.get("symbol"),
                        "en": r.get("name") or "", "mine": r.get("symbol") in mine, "kname": mine.get(r.get("symbol")),
                        "timing": timing, "eps": r.get("epsForecast") or None, "cap": _cap(r.get("marketCap"))})
    return out


def _auctions():
    out = []
    try:
        j = get("https://www.treasurydirect.gov/TA_WS/securities/upcoming?format=json").json()
    except Exception:
        return out
    for x in j:
        term, typ = x.get("securityTerm") or "", x.get("securityType") or ""
        if typ not in ("Note", "Bond") and "TIPS" not in typ:
            continue  # 단기 국채(Bill)는 너무 많아서 뺌
        date = x.get("auctionDate") or x.get("auctionDateYear") or ""
        m = re.match(r"(\d{4}-\d{2}-\d{2})", str(date))
        if not m:
            continue
        d = datetime.fromisoformat(m.group(1))
        t = datetime(d.year, d.month, d.day, 13, 0, tzinfo=NY).astimezone(KST)
        out.append({"t": t, "type": "auction", "en": f"{term} {typ}"})
    return out


EVENT_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "events": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
            "date": {"type": "STRING"}, "title": {"type": "STRING"}, "detail": {"type": "STRING"},
            "emoji": {"type": "STRING"}, "confirmed": {"type": "BOOLEAN"}},
            "required": ["date", "title", "detail", "emoji", "confirmed"]}},
        "names": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
            "en": {"type": "STRING"}, "ko": {"type": "STRING"}}, "required": ["en", "ko"]}},
    },
    "required": ["events", "names"],
}


def _ai_events(config, need_names):
    from summarize import call_ai
    today = now_kst().date()
    heads = []
    for q in ("이번주 증시 주요 일정", "다음주 증시 주요 일정", "미국 증시 이번주 일정", "IPO 상장 예정 미국",
              "미국 정치 일정 이번주", "빅테크 행사 컨퍼런스 일정"):
        try:
            heads += [f"- {n['title']} ({n['source']}, {n['time']})" for n in google_news(q, 10)]
        except Exception:
            pass
    prompt = f"""오늘은 한국 날짜 {today:%Y-%m-%d}이야. 아래 뉴스 제목에서 오늘부터 {DAYS}일 안에 있을
미국·한국 증시에 영향을 줄 '일정'만 뽑아 줘 (IPO·상장, 기업 행사·컨퍼런스, 정치·선거·표결, 정상회담·협상, 정책 발표 등).

규칙:
- 경제지표(CPI·PPI·고용·FOMC 등)와 기업 실적 발표는 빼 (따로 넣어).
- 제목에 날짜가 있거나 날짜를 확실히 알 수 있는 것만. 추측해서 날짜를 만들지 마.
- date는 YYYY-MM-DD (한국 날짜). 여러 날 이어지면 시작일, detail에 "11/10~12" 처럼 기간.
- title 20자 안, detail 40자 안, 쉬운 한국어. emoji는 내용에 맞는 이모지 1개.
- confirmed: 확정된 일정이면 true, "예정·전망·유력"이면 false.
- 같은 일정은 한 번만. 최대 10개.
- names: 아래 영어 이름을 짧은 한국어로 바꿔 줘 (회사명은 흔히 쓰는 한국어 이름, 경제지표는 쉬운 말).

[바꿀 이름]
{chr(10).join(need_names) or "- 없음"}

[뉴스 제목]
{chr(10).join(heads[:60])}
"""
    out, _ = call_ai(config, prompt, EVENT_SCHEMA)
    lo, hi = today, today + timedelta(days=DAYS)
    events = []
    for e in out.get("events", []):
        try:
            d = datetime.strptime(e["date"], "%Y-%m-%d").date()
        except Exception:
            continue
        if lo <= d < hi:
            events.append({"date": e["date"], "title": e["title"], "detail": e["detail"],
                           "emoji": e.get("emoji") or "📰", "confirmed": bool(e.get("confirmed"))})
    names = {n["en"]: n["ko"] for n in out.get("names", []) if n.get("en") and n.get("ko")}
    return events, names


def build_schedule(config, prev):
    """→ ([{date, label, items:[...]}], 오류 목록)"""
    errors = []
    now = now_kst()
    econ, earn, auc = [], [], []
    try:
        econ = _econ()
    except Exception as e:
        errors.append(f"경제지표: {e}")
    try:
        earn = _earnings(config)
    except Exception as e:
        errors.append(f"실적: {e}")
    try:
        auc = _auctions()
    except Exception as e:
        errors.append(f"국채 입찰: {e}")

    # AI: 뉴스 이벤트 + 이름 번역 (6시간마다, 아니면 지난 것 재사용)
    old = prev.get("schedule_meta") or {}
    names = dict(old.get("names") or {})
    events = old.get("events") or []
    t_old = parse_when(old.get("ai_at"))
    need = sorted({x["en"] for x in econ if x["en"] not in KO and x["en"] not in names} |
                  {x["en"] for x in earn if not x.get("kname") and x["en"] not in names})
    if not t_old or now - t_old > timedelta(hours=AI_REUSE_HOURS) or len(need) > 3:
        try:
            ev, nm = _ai_events(config, need[:60])
            events, names = ev, {**names, **nm}
            old = {"ai_at": iso(now)}
        except Exception as e:
            errors.append(f"AI 일정: {str(e)[:120]}")
    meta = {"ai_at": old.get("ai_at") if "ai_at" in old else (prev.get("schedule_meta") or {}).get("ai_at"),
            "events": events, "names": dict(list(names.items())[-400:])}

    today = now.date()
    days = {today + timedelta(days=i): [] for i in range(DAYS)}

    def add(t, item):
        d = t.date() if isinstance(t, datetime) else t
        if d in days:
            days[d].append(item)

    for x in econ:
        title = KO.get(x["en"]) or names.get(x["en"]) or x["en"]
        flag = "🇺🇸" if x["country"] == "USD" else "🇰🇷"
        parts = []
        if x["actual"]:
            parts.append(f"실제 {x['actual']}")
        if x["forecast"]:
            parts.append(f"예상 {x['forecast']}")
        if x["previous"]:
            parts.append(f"이전 {x['previous']}")
        add(x["t"], {"time": f"{x['t']:%H:%M}", "sort": iso(x["t"]), "type": "econ",
                     "emoji": "📈", "title": f"{flag} {title}", "detail": " · ".join(parts) or None,
                     "star": x["impact"] == "High", "done": x["t"] <= now, "source": "확정"})
    for x in earn:
        nm = x.get("kname") or names.get(x["en"]) or x["en"]
        when = {"after": "장 마감 후", "before": "장 시작 전"}.get(x["timing"], "시간 미정")
        cap = f"시총 {x['cap'] / 1e12:.2f}조$" if x["cap"] >= 1e12 else f"시총 {x['cap'] / 1e8:,.0f}억$"
        ny_d = x["t"].astimezone(NY)
        detail = f"미국 {ny_d:%m/%d} {when}" + (f" · 예상 EPS {x['eps']}" if x["eps"] else "") + f" · {cap}"
        add(x["t"], {"time": f"{x['t']:%H:%M}" if x["timing"] else None, "sort": iso(x["t"]), "type": "earnings",
                     "emoji": "⭐" if x["mine"] else "💼", "title": f"{nm} ({x['symbol']}) 실적",
                     "detail": detail, "star": x["mine"], "done": x["t"] <= now, "source": "확정"})
    for x in auc:
        add(x["t"], {"time": f"{x['t']:%H:%M}", "sort": iso(x["t"]), "type": "auction", "emoji": "🏛",
                     "title": f"미 국채 {x['en'].replace('-Year', '년물').replace(' Note', '').replace(' Bond', '')} 입찰",
                     "detail": "응찰이 약하면 금리가 오를 수 있어요", "star": "10" in x["en"] or "30" in x["en"],
                     "done": x["t"] <= now, "source": "확정"})
    for e in events:
        d = datetime.strptime(e["date"], "%Y-%m-%d").date()
        add(d, {"time": None, "sort": f"{e['date']}T23:59", "type": "event", "emoji": e["emoji"],
                "title": e["title"], "detail": e["detail"], "star": False, "done": False,
                "source": "확정" if e["confirmed"] else "뉴스 기준"})

    wk = "월화수목금토일"
    out = []
    for d, items in days.items():
        items.sort(key=lambda i: (i["sort"], not i["star"]))
        label = f"{d:%m/%d}({wk[d.weekday()]})" + (" 오늘" if d == today else " 내일" if d == today + timedelta(days=1) else "")
        out.append({"date": f"{d:%Y-%m-%d}", "label": label, "items": items})
    return out, meta, errors
