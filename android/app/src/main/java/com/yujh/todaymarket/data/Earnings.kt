package com.yujh.todaymarket.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 실적 표시 문구와 남은 날짜 계산 (앱·위젯·알림이 같이 씀) */
object EarningsText {
    private val md = DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREAN)
    private val ny = ZoneId.of("America/New_York")

    /** 미국 날짜 기준 남은 날 (0 = 오늘). 날짜가 없으면 null */
    fun daysLeft(e: Earnings?): Long? {
        val d = e?.nextDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return ChronoUnit.DAYS.between(LocalDate.now(ny), d)
    }

    /** 한국에서 결과를 보는 때 */
    private fun koreaWhen(d: LocalDate, timing: String?): String = when (timing) {
        "after" -> "한국 ${d.plusDays(1).format(md)} 아침"
        "before" -> "한국 ${d.format(md)} 밤"
        else -> ""
    }

    /** 예) "실적 D-12 · 11/17(화) 장 마감 후 (한국 11/18(수) 아침) · 예상 EPS $2.11" */
    fun upcoming(e: Earnings?): String? {
        val left = daysLeft(e) ?: return null
        if (left < 0) return null
        val d = LocalDate.parse(e!!.nextDate)
        val dd = if (left == 0L) "D-day" else "D-$left"
        val t = when (e.timing) { "after" -> " 장 마감 후"; "before" -> " 장 시작 전"; else -> "" }
        val k = koreaWhen(d, e.timing).let { if (it.isNotEmpty()) " ($it)" else "" }
        val eps = e.epsForecast?.let { " · 예상 EPS $" + String.format("%.2f", it) } ?: ""
        return "실적 $dd · ${d.format(md)}$t$k$eps"
    }

    /** 예) "지난 실적 8/27: EPS $1.05 (예상 $1.01, +4.0% 좋음)" */
    fun last(e: Earnings?): String? {
        val l = e?.last ?: return null
        if (l.eps == null) return null
        val d = l.date?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("M/d")) }.getOrNull() } ?: ""
        val sp = l.surprisePct
        val verdict = when {
            sp == null -> ""
            sp >= 2 -> String.format(", %+.1f%% 좋음", sp)
            sp <= -2 -> String.format(", %+.1f%% 나쁨", sp)
            else -> String.format(", %+.1f%% 비슷", sp)
        }
        val fc = l.forecast?.let { "예상 $" + String.format("%.2f", it) } ?: ""
        return "지난 실적 $d: EPS $" + String.format("%.2f", l.eps) + " ($fc$verdict)"
    }

    /** 발표 뒤 3일 안이면 true (눈에 띄게 보여주기) */
    fun justReported(e: Earnings?): Boolean {
        val d = e?.last?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        val ago = ChronoUnit.DAYS.between(d, LocalDate.now(ny))
        return ago in 0..3
    }
}
