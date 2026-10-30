package com.yujh.todaymarket.ui

import com.yujh.todaymarket.data.Indicator
import kotlin.math.abs

/** 지표 설명: 무엇인지 · 의미 · 기준치 · 바뀌는 때 */
data class IndGuide(
    val what: String,
    val meaning: String,
    val levels: String,
    val changes: String,
)

val GUIDE: Map<String, IndGuide> = mapOf(
    // ---------- 점수 지표 ----------
    "vix" to IndGuide("미국 주식 불안 지수", "앞으로 주가가 얼마나 흔들릴지",
        "15↓ 안정 · 15~20 보통 · 20~30 불안 · 30↑ 공포", "미국 장중에만 바뀌어요"),
    "hy_spread" to IndGuide("신용 낮은 회사채 금리 − 국채 금리", "기업 부도 걱정 정도",
        "3.5%p↓ 안정 · 3.5~5 주의 · 5↑ 위험 (코로나 때 약 10)", "하루 1번, 한국 밤 11시쯤 전날 숫자"),
    "us10y" to IndGuide("미국 10년 국채 금리", "높을수록 주식(특히 기술주)에 부담",
        "4.0%↓ 편함 · 4.0~4.5 보통 · 4.5~5.0 부담 · 5↑ 큰 부담 · 1달 +0.3%p↑ 주의", "평일 채권 시장 시간에 계속"),
    "fear_greed" to IndGuide("CNN 투자 심리 점수", "너무 겁먹었는지, 너무 들떴는지",
        "0~25 극도의 공포 · 25~45 공포 · 45~65 건강 · 65~80 탐욕 · 80↑ 과열", "미국 장중에 수시로"),
    "move" to IndGuide("채권 시장 불안 지수 (채권의 VIX)", "금리가 얼마나 요동칠지",
        "80↓ 안정 · 80~100 보통 · 100~120 긴장 · 120↑ 불안 (2022년 약 160)", "미국 장중에 계속"),
    "dxy" to IndGuide("주요 6개 통화 대비 달러 힘", "달러가 강해지면 주식·신흥국에 부담",
        "점수는 1달 변화로: -1%↓ 유리 · +2%↑ 급등 주의", "평일 24시간"),
    "usdkrw" to IndGuide("1달러가 몇 원인지", "오르면(원화 약세) 외국인이 한국 주식을 팔기 쉬움",
        "점수는 1달 변화로: 마이너스 유리 · +2%↑ 주의", "평일 거의 하루 종일"),
    "margin_debt" to IndGuide("빚내서 투자한 돈의 1년 증가율", "너무 늘면 과열, 급감하면 강제 매도",
        "0~15% 보통 · +30%↑ 과열 · -20%↓ 급한 정리", "한 달 1번, 다음 달 중순 발표 (직접 입력)"),
    // ---------- 모니터링 지표 ----------
    "wti" to IndGuide("미국 원유 가격 (배럴당 달러)", "오르면 물가와 금리에 부담",
        "60~80 보통 · 90↑ 물가 부담 · 60↓ 경기 둔화 신호 · 하루 ±3% 큰 움직임", "거의 24시간"),
    "nq_fut" to IndGuide("미국장 밖에서도 거래되는 나스닥100", "미국장 분위기를 미리 알려줘요",
        "장 전 ±0.5% 방향 신호 · ±1%↑ 큰 움직임", "거의 24시간"),
    "sox" to IndGuide("미국 반도체 30개 회사 지수", "삼성전자·하이닉스·엔비디아·TSMC 업종 흐름",
        "하루 ±2%↑면 반도체주에 큰 영향", "미국 장중"),
    "usdjpy" to IndGuide("1달러가 몇 엔인지", "엔화가 갑자기 강해지면 엔캐리 자금이 빠지며 증시 급락 위험",
        "하루 -1.5%↓(엔 강세)면 경고", "평일 24시간"),
    "us2y" to IndGuide("미국 2년 국채 금리", "연준 금리 인하·인상 기대를 가장 빨리 반영",
        "기준금리보다 많이 낮으면 인하 기대 · 하루 ±0.1%p면 큰 변화", "평일 채권 시장 시간"),
    "gold" to IndGuide("금 가격 (온스당 달러)", "불안할 때 몰리는 안전자산",
        "하루 +2%↑ 또는 최고가 경신이면 불안 심리", "거의 24시간"),
    "bei10" to IndGuide("시장이 예상하는 10년 평균 물가", "CPI 전후 금리 방향을 이해하는 데 도움",
        "2.0~2.5% 안정 (연준 목표 2%) · 2.7%↑ 물가 걱정", "하루 1번 (1일 늦게)"),
    "kospi_foreign" to IndGuide("외국인이 산 금액 − 판 금액 (코스피)", "한국 대형주 방향에 가장 큰 영향",
        "+5천억↑ 강한 매수 · -5천억↓ 강한 매도 · 며칠 연속인지도 중요", "국내 장중 (오늘 누적)"),
    "bitcoin" to IndGuide("비트코인 가격 (달러)", "위험을 감수하려는 심리를 보여줘요",
        "참고용 (하루 ±5%는 흔해요)", "24시간"),
    "ethereum" to IndGuide("이더리움 가격 (달러)", "비트코인과 함께 위험 선호를 보여줘요",
        "참고용 (하루 ±5%는 흔해요)", "24시간"),
)

/** 시장 점수 항목 설명 (지표 카드가 없는 항목 포함) */
val SCORE_GUIDE: Map<String, IndGuide> = GUIDE + mapOf(
    "trend" to IndGuide("S&P500·나스닥이 200일 평균보다 위인지", "큰 흐름이 상승장인지 하락장인지",
        "평균 위 = 상승장 · +3%↑ 튼튼 · -3%↓ 주의", "하루 1번 (종가 기준)"),
    "curve" to IndGuide("미국 10년물 금리 − 2년물 금리", "마이너스(역전)는 경기침체 신호로 봐요",
        "+0.5%p↑ 정상 · 0 근처 주의 · 마이너스 = 경고", "하루 1번"),
    "hy" to GUIDE.getValue("hy_spread"),
)

/** 종목 점수 항목 설명 */
val STOCK_GUIDE: Map<String, IndGuide> = mapOf(
    "trend" to IndGuide("주가가 20·60·200일 평균보다 위인지", "평균보다 위면 오르는 흐름이에요",
        "세 평균 모두 위 = 튼튼 · 모두 아래 = 약함 · 평균에서 ±10% 넘게 벌어지면 크게 기운 것", "하루 1번 (종가 기준)"),
    "rs" to IndGuide("최근 1·3개월 수익률을 시장(코스피·S&P500)과 비교", "시장보다 강하면 돈이 몰리는 종목이에요",
        "시장 대비 +5%p↑ 강함 · ±5%p 보통 · -5%p↓ 약함", "하루 1번"),
    "news" to IndGuide("AI가 판단한 회사만의 뉴스 분위기 (-2 ~ +2)", "시장 전체 소식은 빼고 회사 소식만 봐요",
        "+2 큰 호재 · +1 호재 · 0 보통 · -1 악재 · -2 큰 악재", "30분마다"),
    "risk" to IndGuide("1년 최고가 대비 하락폭 + 최근 출렁임", "많이 빠졌거나 많이 출렁이면 위험이 커요",
        "고점 대비 -10% 이내·출렁임 30%↓ = 안정 · -30%↓ 또는 출렁임 60%↑ = 위험", "하루 1번"),
    "value" to IndGuide("PER(주가 ÷ 이익)을 내년 예상·업종 평균과 비교", "같은 이익인데 싸게 거래되면 점수가 높아요",
        "업종보다 PER 낮음 = 쌈 · 내년 PER이 더 낮음 = 이익 증가 기대 · PER 40↑ = 비싼 편", "하루 1번"),
)

/** 지표 값을 보기 좋게. 예) 104.19달러, 4.619%, -1조 6,149억 */
fun fmtIndicatorValue(ind: Indicator): String {
    val v = ind.value ?: return if (ind.needInput) "입력 필요" else "-"
    return when (ind.key) {
        "fear_greed" -> "${v.toInt()} ${ind.label ?: ""}"
        "margin_debt" -> String.format("%+.1f%%", v)
        "us10y", "us2y" -> String.format("%.3f%%", v)
        "bei10" -> String.format("%.2f%%", v)
        "hy_spread" -> String.format("%.2f%%p", v)
        "wti", "gold", "bitcoin", "ethereum" -> "$" + fmtNum(v, if (v >= 1000) 0 else 2)
        "kospi_foreign" -> fmtEok(v)
        else -> fmtNum(v)
    }
}

/** 억원 단위 숫자 → "-1조 6,149억" */
fun fmtEok(v: Double): String {
    val sign = if (v < 0) "-" else "+"
    val a = abs(v).toLong()
    val jo = a / 10000
    val eok = a % 10000
    return sign + (if (jo > 0) "${jo}조 " else "") + String.format("%,d억", eok)
}

/** 변화 표시. 퍼센트가 있으면 퍼센트, 없으면 변화량 */
fun fmtIndicatorChange(ind: Indicator): String {
    if (ind.key in setOf("margin_debt", "kospi_foreign")) return ""
    ind.changePct?.let { if (ind.key !in setOf("us10y", "us2y", "hy_spread", "fear_greed")) return fmtPct(it) }
    val c = ind.change ?: return ""
    val digits = when (ind.key) { "fear_greed" -> 0; "us10y", "us2y" -> 3; else -> 2 }
    return "${arrow(c)}${fmtNum(abs(c), digits)}"
}

/** 색 판단용 방향 값 */
fun indicatorDirection(ind: Indicator): Double? = when (ind.key) {
    "kospi_foreign" -> ind.value
    else -> ind.changePct ?: ind.change
}
