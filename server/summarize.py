"""3-2단계: Gemini 로 종목 이슈·뉴스 분위기·시황 요약.

- 종목 이슈: 종목마다 한 줄 + 뉴스 분위기(-2~+2). 관련 없는 기사는 AI가 걸러낸다.
- 시황: 3~4줄. (자동 실행 때는 하루 5번만 만들고, 나머지는 직전 요약을 그대로 쓴다)
AI 회사를 바꾸기 쉽도록 call_ai() 한 곳에서만 AI를 부른다.
"""
import json
import os
import time

from datetime import datetime, timedelta

from collect import ROOT, S, collect, now_kst, iso

GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


class AIError(Exception):
    pass


def call_gemini(model, prompt, schema):
    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        raise AIError("GEMINI_API_KEY 없음")
    body = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.3, "responseMimeType": "application/json",
                             "responseSchema": schema},
    }
    # 열쇠는 주소가 아니라 머리글(header)에 넣어 기록에 남지 않게 한다
    r = S.post(GEMINI_URL.format(model=model), json=body, timeout=60,
               headers={"x-goog-api-key": key})
    if r.status_code != 200:
        raise AIError(f"{model} {r.status_code}: {r.text[:200]}")
    j = r.json()
    try:
        text = j["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError):
        raise AIError(f"{model} 빈 응답: {str(j)[:200]}")
    usage = j.get("usageMetadata", {})
    return json.loads(text), usage


def call_ai(config, prompt, schema):
    """설정된 모델 → 예비 모델 순서로 시도."""
    ai = config["ai"]
    models = [ai["model"]] + ai.get("fallback_models", [])
    errors = []
    for m in models:
        for attempt in range(2):
            try:
                out, usage = call_gemini(m, prompt, schema)
                return out, {"model": m, "usage": usage, "skipped": errors}
            except AIError as e:
                errors.append(str(e))
                if " 429" in str(e) or " 5" in str(e)[:len(m) + 3]:
                    time.sleep(5)
                    continue
                break
            except Exception as e:  # 네트워크·JSON 오류
                errors.append(f"{m} {type(e).__name__}: {e}")
                time.sleep(3)
    raise AIError(" | ".join(errors)[:600])


# ---------------- 종목 이슈 ----------------

ISSUE_SCHEMA = {
    "type": "OBJECT",
    "properties": {"stocks": {"type": "ARRAY", "items": {
        "type": "OBJECT",
        "properties": {
            "name": {"type": "STRING"},
            "issue": {"type": "STRING"},
            "sentiment": {"type": "INTEGER"},
            "used_news": {"type": "ARRAY", "items": {"type": "INTEGER"}},
            "explains": {"type": "STRING", "enum": ["UP", "DOWN", "NONE"]},
        },
        "required": ["name", "issue", "sentiment", "used_news", "explains"],
    }}},
    "required": ["stocks"],
}


def fmt_quote(q):
    if not q or q.get("error") or q.get("change_pct") is None:
        return "시세 없음"
    return f"{q['change_pct']:+.2f}% (기준 {q.get('as_of')})"


def market_context(data):
    out = []
    for v in data.get("markets", {}).values():
        if v.get("change_pct") is not None:
            out.append(f"- {v['name']} {v['change_pct']:+.2f}% (기준 {v.get('as_of')})")
    out.append("[시장 뉴스]")
    out += [f"- {n['title']} ({n['source']})" for n in data.get("market_news", [])[:12]]
    return "\n".join(out)


def issue_prompt(data):
    lines = []
    for s in data["stocks"]:
        lines.append(f"\n## {s['name']} ({s['code']}) 등락률 {fmt_quote(s['quote'])}")
        if not s["news"]:
            lines.append("- (뉴스 없음)")
        for i, n in enumerate(s["news"]):
            lines.append(f"[{i}] {n['title']} ({n['source']}, {n['time']})")
    return f"""너는 한국 개인투자자를 위한 주식 위젯의 편집자야.
아래는 종목별 오늘 등락률과 최근 24시간 뉴스 제목이야. 종목마다 다음을 만들어 줘.

1. issue: 오늘 주가가 **왜 그 방향으로** 움직였는지 한 문장, 35자 이내로.
   - 주가가 내렸으면 내린 이유를, 올랐으면 오른 이유를 써. 방향과 반대되는 뉴스(내렸는데 호재)는 이유로 쓰지 마.
   - 시장 전체(코스피·나스닥 등)와 같은 방향으로 움직였고 그 회사만의 이유가 안 보이면
     "코스피 급락에 동반 하락", "반도체주 약세 영향"처럼 시장 흐름을 이유로 써.
   - 회사만의 큰 뉴스가 있고 시장도 같이 움직였다면 둘을 짧게 함께 써도 돼.
   - 등락률이 ±1% 안쪽이고 관련 뉴스도 없으면 "특별한 소식 없음".
   - 미국 주식은 가장 최근 미국 장 기준이야.
   - 초등학생도 알 수 있게 쉬운 말로, 끝은 명사형이나 "~기대", "~우려"처럼 짧게.
   - 반드시 아래 뉴스 제목과 시장 흐름에 근거해서만 써. 없는 사실을 지어내지 마.
   - 그 회사가 주인공이 아닌 기사(다른 회사 이야기에 이름만 나온 기사, 'NAVER'로 검색된 부동산 기사 등)는 무시해.
2. sentiment: **그 회사만의 뉴스** 분위기를 -2(큰 악재) ~ +2(큰 호재) 정수로.
   시장 전체가 내리거나 오른 것은 넣지 마. 회사만의 뉴스가 없으면 0.
3. used_news: 근거로 쓴 기사 번호 목록. 없으면 빈 목록.
4. explains: 네가 쓴 issue 가 설명하는 방향. 오른 이유면 UP, 내린 이유면 DOWN, "특별한 소식 없음"이면 NONE.

종목 이름(name)은 아래 제목의 이름을 그대로 써.

[오늘 시장 흐름]
{market_context(data)}

[종목별 자료]
{''.join(chr(10) + l for l in lines)}
"""


def wrong_direction(s, x):
    """등락률이 ±1% 넘는데 반대 방향 이유를 쓴 경우."""
    c = (s.get("quote") or {}).get("change_pct")
    if c is None or not x:
        return False
    e, senti = x.get("explains"), x.get("sentiment", 0)
    if c >= 1:
        return e == "DOWN" or senti < 0 or (c >= 2 and e != "UP")
    if c <= -1:
        return e == "UP" or senti > 0 and e != "DOWN" or (c <= -2 and e != "DOWN")
    return False


def summarize_issues(config, data):
    out, meta = call_ai(config, issue_prompt(data), ISSUE_SCHEMA)
    by_name = {x["name"]: x for x in out.get("stocks", [])}
    # 방향이 반대인 종목만 한 번 더 묻는다
    bad = [s for s in data["stocks"] if wrong_direction(s, by_name.get(s["name"]))]
    meta["rechecked"] = [s["name"] for s in bad]
    if bad:
        sub = {**data, "stocks": bad}
        note = ("\n\n[다시 확인] 아래 종목은 주가 방향과 반대되는 이유를 썼어. "
                "오른 종목은 오른 이유를, 내린 종목은 내린 이유를 다시 써 줘. "
                "그런 뉴스가 없으면 시장 흐름을 이유로 쓰거나 \"특별한 소식 없음\"이라고 써.")
        try:
            out2, _ = call_ai(config, issue_prompt(sub) + note, ISSUE_SCHEMA)
            for x in out2.get("stocks", []):
                if x.get("name") in by_name:
                    by_name[x["name"]] = x
        except Exception as e:
            meta["recheck_error"] = str(e)[:200]
    result = {}
    for s in data["stocks"]:
        x = by_name.get(s["name"], {})
        used = [s["news"][i] for i in x.get("used_news", []) if 0 <= i < len(s["news"])]
        result[s["name"]] = {
            "issue": x.get("issue") or "특별한 소식 없음",
            "sentiment": max(-2, min(2, int(x.get("sentiment", 0)))),
            "news": used[:3],
        }
    return result, meta


# ---------------- 시황 ----------------

MARKET_SCHEMA = {
    "type": "OBJECT",
    "properties": {"lines": {"type": "ARRAY", "items": {"type": "STRING"}}},
    "required": ["lines"],
}


def _val(v):
    if v.get("price") is None:
        return None
    p = v["price"]
    if v.get("unit") == "억원":
        a = abs(int(p))
        txt = ("-" if p < 0 else "+") + (f"{a // 10000}조 " if a >= 10000 else "") + f"{a % 10000:,}억원"
    else:
        txt = f"{p:,.2f}" if isinstance(p, float) else str(p)
    if v.get("label"):
        txt += f" ({v['label']})"
    if v.get("change_pct") is not None:
        txt += f", 전일 대비 {v['change_pct']:+.2f}%"
    elif v.get("change") is not None:
        txt += f", 변화 {v['change']:+}"
    return f"{txt}, 기준 {v.get('as_of')}"


def market_prompt(data, issues):
    ind = [f"- {v['name']}: {_val(v)}" for v in data["indicators"].values() if _val(v)]
    mon = [f"- {v['name']}: {_val(v)}" for v in data.get("monitors", {}).values() if _val(v)]
    st = [f"- {s['name']}: {fmt_quote(s['quote'])}, 이슈: {issues.get(s['name'], {}).get('issue', '')}"
          for s in data["stocks"]]
    news = [f"- {n['title']} ({n['source']}, {n['time']})" for n in data.get("market_news", [])[:16]]
    enews = [f"- {n['title']} ({n['source']}, {n['time']})" for n in data.get("econ_news", [])[:15]]
    now = now_kst()
    cal = []
    for e in data.get("calendar", []):
        try:
            t = datetime.fromisoformat(e["time"])
        except Exception:
            continue
        if now - timedelta(hours=36) <= t <= now + timedelta(hours=30):
            when = "발표됨" if t <= now else "예정"
            cal.append(f"- [{when}] {t:%m/%d %H:%M} {e['country']} {e['title']} "
                       f"(예상 {e.get('forecast') or '-'}, 이전 {e.get('previous') or '-'}, 실제 {e.get('actual') or '미확인'})")
    return f"""너는 한국 개인투자자를 위한 주식 위젯의 편집자야. 지금은 한국 시각 {now:%m월 %d일 %H:%M}이야.
아래 자료로 '오늘 시황'을 4~5줄로 요약해 줘. 사용자는 미국 주식을 주로 해.

줄마다 다루는 내용 (해당 내용이 없으면 그 줄은 빼):
1. 미국장: 지수 등락률(숫자)과 가장 큰 이유
2. 국내장: 코스피·코스닥 등락률과 이유, 외국인 순매수 금액
3. 경제 지표: 그날 발표된 CPI·PPI·고용·FOMC 같은 지표를 '실제치(예상치)'로 인용하고 시장 반응을 써.
   발표된 지표가 없으면 금리·유가·VIX·MOVE 같은 시장 지표를 숫자로 인용해.
4. 주요 종목 이벤트: 관심 종목의 실적·계약·인사 같은 큰 소식 1~2개
5. 다음 24시간 안에 '예정'된 중요한 지표가 있으면 "오늘 밤 ○○ 발표 예정(예상 ○○)" 처럼 한 줄.

쓰는 방법 (가장 중요):
- 숫자를 늘어놓지 말고, 이야기하듯 풀어서 써. 한 줄에 숫자는 1~2개만 넣고 나머지는 말로 설명해.
- "무슨 일이 있었고 → 그래서 시장이 어떻게 됐는지" 순서로, 원인과 결과가 이어지게 써.
- 지표 이름 옆에 무슨 뜻인지 짧게 붙여 줘. 예: "VIX(불안 지수)", "MOVE(채권 불안 지수)", "외국인 매도"
- 숫자가 높은지 낮은지도 말로 알려 줘. 예: "평소보다 높은 편이에요", "크게 빠졌어요", "조금 올랐어요"
- 나쁜 예: "VIX는 19.70으로 14.53% 올랐고, WTI 유가는 104.12로 2.08% 상승했어요."
- 좋은 예: "불안 지수(VIX)가 19.7로 하루 만에 크게 뛰어, 투자자들이 겁을 먹기 시작했어요."
- 좋은 예: "유가가 배럴당 104달러로 또 올라, 물가와 금리 부담이 커질 수 있어요."

규칙:
- 한 줄에 70자 이내, 쉬운 말, "~했어요/~이에요" 말투. 숫자는 자료 그대로 쓰되 소수점은 1자리까지만.
- 자료에 없는 숫자나 사실은 절대 쓰지 마. 지표의 실제치가 '미확인'이면 뉴스 제목에서 찾고, 없으면 실제치를 쓰지 마.
- 사라/팔라는 말은 하지 마.

[주요 지수]
{market_context(data).split("[시장 뉴스]")[0]}
[점수 지표]
{chr(10).join(ind)}

[모니터링 지표]
{chr(10).join(mon)}

[경제 지표 일정 (지난 36시간 ~ 앞으로 30시간, 한국 시각)]
{chr(10).join(cal) or "- 없음"}

[경제 지표 뉴스]
{chr(10).join(enews) or "- 없음"}

[관심 종목]
{chr(10).join(st)}

[시장 뉴스 제목]
{chr(10).join(news)}
"""


def summarize_market(config, data, issues):
    out, meta = call_ai(config, market_prompt(data, issues), MARKET_SCHEMA)
    return [l.strip() for l in out.get("lines", []) if l.strip()][:5], meta


# ---------------- 점수 설명 ----------------

COMMENT_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "market": {"type": "STRING"},
        "stocks": {"type": "ARRAY", "items": {
            "type": "OBJECT",
            "properties": {"name": {"type": "STRING"}, "comment": {"type": "STRING"}},
            "required": ["name", "comment"]}},
    },
    "required": ["market", "stocks"],
}


def items_txt(items):
    return ", ".join(f"{i['name']} {i['score'] if i['score'] is not None else '없음'}점({i.get('value') or '-'})"
                     for i in items)


def comment_prompt(mscore, sscores):
    st = "\n".join(f"- {n}: 종합 {v['score']}점 / {items_txt(v['items'])}" for n, v in sscores.items()
                   if v.get("score") is not None)
    return f"""너는 한국 개인투자자를 위한 주식 위젯의 편집자야.
아래는 0~100점 점수와 항목별 점수야(50점이 보통). 점수를 쉬운 말로 설명해 줘.

규칙:
- market: 시장 점수를 2문장 이내, 60자 이내로. 점수를 올린 항목과 깎은 항목을 하나씩 짚어 줘.
- stocks: 종목마다 comment 를 1~2문장, 50자 이내로. 가장 좋은 점과 가장 아쉬운 점을 짚어 줘.
- 항목 점수 60점 이상은 좋은 점, 40점 미만은 아쉬운 점이야. 좋은 점을 아쉽다고 하거나 그 반대로 쓰지 마.
- 초등학생도 알 수 있는 쉬운 말, "~해요/~이에요" 말투. 'PER' 같은 말은 '가격 수준'처럼 풀어서 써.
- 사라/팔라는 말은 하지 마. 자료에 없는 사실은 쓰지 마.

[시장 점수] 종합 {mscore['score']}점 / {items_txt(mscore['items'])}

[종목 점수]
{st}
"""


def explain_scores(config, mscore, sscores):
    out, meta = call_ai(config, comment_prompt(mscore, sscores), COMMENT_SCHEMA)
    return out.get("market", ""), {x["name"]: x["comment"] for x in out.get("stocks", [])}, meta


# ---------------- 실행 ----------------

def main():
    config = json.loads((ROOT / "server/config.json").read_text(encoding="utf-8"))
    data = collect(config)
    t0 = time.time()
    report = [f"## 3-2 AI 요약 결과 ({iso(now_kst())})", ""]
    try:
        issues, m1 = summarize_issues(config, data)
        if m1["skipped"]:
            report.append("건너뛴 모델: " + " / ".join(e[:120] for e in m1["skipped"]))
        report += [f"**종목 이슈** (모델 {m1['model']}, 토큰 {m1['usage'].get('totalTokenCount')})", "",
                   "| 종목 | 등락률 | AI 이슈 | 분위기 |", "|---|---|---|---|"]
        for s in data["stocks"]:
            x = issues[s["name"]]
            report.append(f"| {s['name']} | {fmt_quote(s['quote']).split(' (')[0]} | {x['issue']} | {x['sentiment']:+d} |")
    except Exception as e:
        issues = {}
        report.append(f"❌ 종목 이슈 실패: {e}")
    try:
        lines, m2 = summarize_market(config, data, issues)
        report += ["", f"**오늘 시황** (모델 {m2['model']}, 토큰 {m2['usage'].get('totalTokenCount')})", ""]
        report += [f"- {l}" for l in lines]
    except Exception as e:
        lines = []
        report.append(f"❌ 시황 실패: {e}")
    report.append(f"\nAI 요약에 걸린 시간: {time.time() - t0:.1f}초")

    (ROOT / "data").mkdir(exist_ok=True)
    (ROOT / "data/summary.json").write_text(json.dumps(
        {"generated_at": iso(now_kst()), "issues": issues, "market_summary": lines},
        ensure_ascii=False, indent=1), encoding="utf-8")
    text = "\n".join(report)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main()
