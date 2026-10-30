"""서버 전체 실행: 수집 → AI 이슈·시황 → 점수 → AI 점수 설명 → 보고.

(3-5단계에서 앱이 읽을 결과 파일 저장을 붙인다)
"""
import json
import os
import time

from collect import ROOT, collect, iso, now_kst
from history import history_for, market_series, valuation_for
from scoring import market_score, stock_score
from summarize import explain_scores, summarize_issues, summarize_market


def main():
    config = json.loads((ROOT / "server/config.json").read_text(encoding="utf-8"))
    t0 = time.time()
    data = collect(config)
    rep = [f"## 3-4 점수 계산 결과 ({iso(now_kst())})", ""]

    # AI 이슈·시황
    try:
        issues, m1 = summarize_issues(config, data)
        if m1.get("rechecked"):
            rep.append(f"🔁 방향이 반대라 다시 물은 종목: {', '.join(m1['rechecked'])}")
    except Exception as e:
        issues = {}
        rep.append(f"❌ 종목 이슈 실패: {e}")
    try:
        market_lines, _ = summarize_market(config, data, issues)
    except Exception as e:
        market_lines = []
        rep.append(f"❌ 시황 실패: {e}")

    # 점수
    series = market_series()
    bench = {"KR": series["kospi"], "US": series["spx"]}
    mscore = market_score(data, series)
    sscores, vals = {}, {}
    for s in config["stocks"]:
        rows = history_for(s)
        val = valuation_for(s)
        vals[s["name"]] = val
        senti = issues.get(s["name"], {}).get("sentiment")
        sscores[s["name"]] = stock_score(s, rows, bench[s["market"]], val, senti)
        if isinstance(rows, dict):
            rep.append(f"⚠️ {s['name']} 과거 주가 실패: {rows['error'][:120]}")

    try:
        mcomment, scomments, _ = explain_scores(config, mscore, sscores)
    except Exception as e:
        mcomment, scomments = "", {}
        rep.append(f"❌ 점수 설명 실패: {e}")

    # ---- 보고서 ----
    rep += ["", f"### 시장 점수: **{mscore['score']}점 {mscore.get('icon', '')} {mscore.get('name', '')}**",
            f"🤖 {mcomment}", "", "| 항목 | 가중치 | 현재 값 | 점수 | 기여 |", "|---|---|---|---|---|"]
    for i in mscore["items"]:
        rep.append(f"| {i['name']} | {i['weight']} | {i.get('value') or '-'} | "
                   f"{i['score'] if i['score'] is not None else '빠짐'} | {i.get('points') or '-'} |")
    rep += ["", "### 종목 점수", "",
            "| 종목 | 등락률 | 점수 | 추세 | 상대강도 | 뉴스 | 위험도 | 가격 | AI 이슈 | AI 점수 설명 |",
            "|---|---|---|---|---|---|---|---|---|---|"]
    for s in data["stocks"]:
        sc = sscores[s["name"]]
        q = s["quote"]
        chg = f"{q['change_pct']:+.2f}%" if q.get("change_pct") is not None else "-"
        parts = {i["key"]: (i["score"] if i["score"] is not None else "-") for i in sc["items"]}
        rep.append(f"| {s['name']} | {chg} | **{sc['score']}** {sc.get('icon', '')} | {parts['trend']} | "
                   f"{parts['rs']} | {parts['news']} | {parts['risk']} | {parts['value']} | "
                   f"{issues.get(s['name'], {}).get('issue', '-')} | {scomments.get(s['name'], '-')} |")
    rep += ["", "<details><summary>종목별 원재료 값</summary>", ""]
    for n, sc in sscores.items():
        rep.append(f"- **{n}**: " + " / ".join(f"{i['name']}: {i.get('value')}" for i in sc["items"]))
        v = vals[n]
        if isinstance(v, dict) and not v.get("error"):
            rep.append(f"  - 업종 비교 {v.get('peer_count')}개사, 필드: {v.get('peer_keys')}")
        else:
            rep.append(f"  - 가치 지표 실패: {v.get('error', '')[:120]}")
    rep += ["</details>", "", "**오늘 시황**"] + [f"- {l}" for l in market_lines]
    rep.append(f"\n전체 걸린 시간: {time.time() - t0:.1f}초")

    text = "\n".join(rep)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main()
