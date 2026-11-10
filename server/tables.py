"""주요 ETF 주간 표 + 빅테크 현황 표 (CNBC 일봉으로 계산, 1시간마다 새로)."""
from datetime import timedelta

from collect import first_ok, iso, now_kst
from history import cnbc_bars
from publish import parse_when

ETFS = [
    ("SPY", "S&P500"), ("QQQ", "나스닥100"), ("DIA", "다우"), ("IWM", "미국 소형주"),
    ("SOXX", "반도체"), ("SMH", "반도체(대형)"), ("XLK", "기술"), ("XLF", "금융"), ("XLE", "에너지"),
    ("XLV", "헬스케어"), ("XLI", "산업재"), ("XLY", "경기소비재"), ("XLP", "필수소비재"), ("XLU", "유틸리티"),
    ("XLB", "소재"), ("XLRE", "리츠"), ("XLC", "통신"), ("ITA", "방산"), ("XBI", "바이오"),
    ("GLD", "금"), ("GDX", "금 채굴"), ("SLV", "은"), ("SIL", "은 채굴"), ("URA", "우라늄"),
    ("USO", "유가"), ("XOP", "가스/에너지"), ("TAN", "태양광"), ("ICLN", "신재생"),
    ("KWEB", "중국IT"), ("FXI", "중국 대형주"), ("EWY", "한국"), ("EWJ", "일본"), ("JETS", "항공주"),
    ("IBIT", "비트코인"), ("TLT", "미국 장기채"), ("HYG", "하이일드채"), ("ARKK", "혁신성장"),
    ("DRAM", "메모리"), ("LYTE", "광통신"), ("NCLD", "데이터센터"),
]

BIGTECH = [
    ("AAPL", "애플"), ("NVDA", "엔비디아"), ("MSFT", "마이크로소프트"), ("GOOGL", "알파벳"), ("AMZN", "아마존"),
    ("SPCX", "스페이스X"), ("META", "메타"), ("TSLA", "테슬라"), ("AVGO", "브로드컴"),
]
REUSE_MIN = 55


def _stats(rows):
    """[(YYYYMMDD, 종가)] → 오늘·주간(5거래일)·올해·1년 고점 대비"""
    c = [v for _, v in rows]
    last_d, last = rows[-1]
    year = last_d[:4]
    prev_year = [v for d, v in rows if d[:4] < year]
    base_y = prev_year[-1] if prev_year else None
    hi = max(c[-252:])
    return {
        "price": round(last, 2),
        "day": round((last / c[-2] - 1) * 100, 2) if len(c) > 1 else None,
        "week": round((last / c[-6] - 1) * 100, 1) if len(c) > 5 else None,
        "ytd": round((last / base_y - 1) * 100, 1) if base_y else None,
        "from_high": round((last / hi - 1) * 100, 1),
        "as_of": f"{last_d[:4]}-{last_d[4:6]}-{last_d[6:8]}",
    }


def build_tables(prev):
    old = prev.get("tables") or {}
    t = parse_when(old.get("generated_at"))
    if t and now_kst() - t < timedelta(minutes=REUSE_MIN) and old.get("etf"):
        return old, []
    errors = []

    def rows_of(sym):
        r = first_ok(lambda: cnbc_bars(sym, 420))
        if not isinstance(r, list):
            errors.append(f"{sym}: {r.get('error', '')[:80]}")
            return None
        return r

    etf = []
    for sym, desc in ETFS:
        r = rows_of(sym)
        if r:
            etf.append({"ticker": sym, "desc": desc, **_stats(r)})
    big = []
    for sym, name in BIGTECH:
        r = rows_of(sym)
        if r:
            big.append({"ticker": sym, "name": name, **_stats(r)})
    if not etf and old.get("etf"):
        return old, errors
    ranked = sorted([e for e in etf if e["week"] is not None], key=lambda e: -e["week"])
    return {
        "generated_at": iso(now_kst()),
        "as_of": max((e["as_of"] for e in etf), default=None),
        "etf_up": ranked[:6],
        "etf_down": list(reversed(ranked[-6:])) if len(ranked) > 6 else [],
        "etf": ranked,
        "bigtech": big,
    }, errors
