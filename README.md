# 오늘의 시장 (stock-widget)

안드로이드 홈 화면 위젯으로 보는 주식·시장 요약 앱입니다.

- **서버**: GitHub Actions 가 30분마다 시세·지표·뉴스를 모으고, Gemini 로 요약하고,
  시장 점수와 종목 점수를 계산해 `latest.json` 을 GitHub Pages 에 올립니다.
- **결과 파일**: https://97yujh-droid.github.io/stock-widget/latest.json
- **시안**: https://97yujh-droid.github.io/stock-widget/mockup.html
- **앱/위젯**: 결과 파일만 읽어서 보여줍니다. AI 열쇠는 앱에 없습니다.
  보유 수량·평단은 휴대폰에만 저장됩니다.

## 폴더
| 위치 | 내용 |
|---|---|
| `server/config.json` | 관심 종목, AI 모델, 시황 시각 |
| `server/run.py` | 전체 실행 (수집 → AI → 점수 → 결과 파일) |
| `data/manual/margin_debt.json` | Margin Debt 월간 값 (매달 직접 입력) |
| `docs/widget-mockup.html` | 위젯 시안 |

## 필요한 금고(Secrets)
`FRED_API_KEY`, `GEMINI_API_KEY`

참고: 점수는 투자 판단을 돕는 참고용입니다.
