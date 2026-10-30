package com.yujh.todaymarket.ui

import androidx.compose.ui.graphics.Color
import java.text.DecimalFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// ---------- 색 (나무 스타일, 한국식: 빨강=상승 / 파랑=하락) ----------
object Palette {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF141414)
    val Line = Color(0xFF222222)
    val Fg = Color(0xFFEEF1F4)
    val Sub = Color(0xFF8B95A1)
    val Faint = Color(0xFF5B636D)
    val Up = Color(0xFFFF5A5A)
    val Down = Color(0xFF4D8DFF)
    val Flat = Color(0xFF4FAE86)
    val Stale = Color(0xFF62686F)
    val Accent = Color(0xFF9BD36A)
    val Box = Color(0xFF1B1B1B)
}

/** 점수 구간 색: 폭풍(보라) · 비(주황) · 흐림(노랑) · 구름 조금(연두) · 맑음(초록) */
fun zoneColor(score: Int?): Color = when {
    score == null -> Palette.Sub
    score >= 80 -> Color(0xFF39C27A)
    score >= 60 -> Color(0xFF9BD36A)
    score >= 40 -> Color(0xFFC2B25A)
    score >= 20 -> Color(0xFFE0883C)
    else -> Color(0xFFA46CF0)
}

val ZoneColors = listOf(
    Color(0xFFA46CF0), Color(0xFFE0883C), Color(0xFFC2B25A), Color(0xFF9BD36A), Color(0xFF39C27A),
)

fun changeColor(v: Double?): Color = when {
    v == null -> Palette.Sub
    v > 0 -> Palette.Up
    v < 0 -> Palette.Down
    else -> Palette.Flat
}

fun arrow(v: Double?): String = when {
    v == null -> ""
    v > 0 -> "▲"
    v < 0 -> "▼"
    else -> ""
}

private val krw = DecimalFormat("#,##0")
private val two = DecimalFormat("#,##0.00")

fun fmtPrice(v: Double?, currency: String): String =
    if (v == null) "-" else if (currency == "KRW") krw.format(v) else two.format(v)

fun fmtChange(v: Double?, currency: String): String =
    if (v == null) "-" else if (currency == "KRW") krw.format(abs(v)) else two.format(abs(v))

fun fmtPct(v: Double?): String = if (v == null) "-" else String.format("%+.2f%%", v)

fun fmtNum(v: Double?, digits: Int = 2): String =
    if (v == null) "-" else String.format("%,.${digits}f", v)

private val shortFmt = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private val seoul = ZoneId.of("Asia/Seoul")

/** "2026-10-30T17:54+09:00" → "10/30 17:54". 날짜만 있는 값은 그대로 둔다. */
fun fmtTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    return try {
        OffsetDateTime.parse(iso).atZoneSameInstant(seoul).format(shortFmt)
    } catch (e: Exception) {
        iso
    }
}
