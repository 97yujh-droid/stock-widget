package com.yujh.todaymarket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yujh.todaymarket.data.ScheduleDay
import com.yujh.todaymarket.data.TableRow
import com.yujh.todaymarket.data.Tables
import kotlin.math.abs

@Composable
private fun Board(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

// ---------------- 주요 일정 ----------------

/** 오늘부터 7일 주요 일정 (날짜별) */
@Composable
fun ScheduleCard(days: List<ScheduleDay>) {
    var all by remember { mutableStateOf(false) }
    val nonEmpty = days.filter { it.items.isNotEmpty() }
    if (nonEmpty.isEmpty()) return
    val shown = if (all) nonEmpty else nonEmpty.take(2)
    Board {
        Text("🗓 주요 일정", color = Palette.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        shown.forEach { d ->
            Text(d.label, color = Color(0xFF7FA8FF), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp))
            d.items.forEach { i ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (i.star) Color(0xFF1E1E1E) else Color(0xFF181818))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(i.emoji, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
                        Text(
                            i.title, color = if (i.done) Palette.Sub else Palette.Fg, fontSize = 14.sp,
                            fontWeight = if (i.star) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (i.time != null) {
                            Text(i.time, color = Palette.Sub, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    val tags = listOfNotNull(
                        if (i.done) "지남" else null,
                        if (i.source != "확정") i.source else null,
                    )
                    val detail = listOfNotNull(i.detail?.takeIf { it.isNotBlank() }) + tags
                    if (detail.isNotEmpty()) {
                        Text(detail.joinToString(" · "), color = Palette.Sub, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 22.dp, top = 2.dp))
                    }
                }
            }
        }
        if (nonEmpty.size > 2) {
            Text(if (all) "접기 ▴" else "이번 주 일정 모두 보기 (${nonEmpty.size}일) ▾", color = Palette.Sub, fontSize = 12.sp,
                modifier = Modifier.clickable { all = !all }.padding(vertical = 4.dp))
        }
        Text("시각은 한국 기준 · ⭐ 내 종목 · 경제지표·실적·국채 입찰은 확정 일정, '뉴스 기준'은 뉴스에서 AI가 뽑은 일정이에요",
            color = Palette.Faint, fontSize = 11.sp)
    }
}

// ---------------- 표 공통 ----------------

/** 숫자 칸 안에 막대 (오르면 빨강, 내리면 파랑) */
@Composable
private fun BarCell(v: Double?, max: Double, width: Dp, bold: Boolean = false) {
    Box(Modifier.width(width).height(24.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.CenterEnd) {
        if (v != null && max > 0) {
            val f = (abs(v) / max).coerceIn(0.0, 1.0).toFloat()
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                    .background((if (v >= 0) Palette.Up else Palette.Down).copy(alpha = 0.28f)),
            )
        }
        Text(
            if (v == null) "-" else String.format("%+.1f%%", v), color = if (v == null) Palette.Faint else changeColor(v),
            fontSize = 12.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.End, modifier = Modifier.padding(end = 4.dp),
        )
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp? = null, modifier: Modifier = Modifier) {
    Text(text, color = Palette.Faint, fontSize = 11.sp, textAlign = if (width != null) TextAlign.End else TextAlign.Start,
        modifier = if (width != null) modifier.width(width).padding(end = 6.dp) else modifier)
}

// ---------------- 주요 ETF ----------------

@Composable
fun EtfCard(t: Tables) {
    var all by remember { mutableStateOf(false) }
    val rows = if (all) t.etf else t.etfUp + t.etfDown
    val maxW = rows.maxOfOrNull { abs(it.week ?: 0.0) } ?: 1.0
    val maxY = rows.maxOfOrNull { abs(it.ytd ?: 0.0) } ?: 1.0
    Board {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📊 주요 ETF 주간", color = Palette.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${t.asOf ?: ""} 기준", color = Palette.Faint, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            HeaderCell("ETF · 특징", modifier = Modifier.weight(1f))
            HeaderCell("주간", 78.dp)
            HeaderCell("올해", 78.dp)
        }
        rows.forEachIndexed { idx, r ->
            if (!all && idx == t.etfUp.size && t.etfDown.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(Color(0xFF333333)))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if ((r.week ?: 0.0) >= 0) "▲" else "▼", color = changeColor(r.week), fontSize = 11.sp,
                    modifier = Modifier.width(16.dp))
                Text(r.ticker, color = Palette.Fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
                Text(r.name, color = Palette.Sub, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                BarCell(r.week, maxW, 78.dp, bold = true)
                BarCell(r.ytd, maxY, 78.dp)
            }
        }
        Text(if (all) "접기 ▴" else "ETF ${t.etf.size}개 모두 보기 (주간 순) ▾", color = Palette.Sub, fontSize = 12.sp,
            modifier = Modifier.clickable { all = !all }.padding(vertical = 4.dp))
        Text("주간 = 최근 5거래일, 올해 = 작년 마지막 날 종가 대비 · 1시간마다 새로", color = Palette.Faint, fontSize = 11.sp)
    }
}

// ---------------- 빅테크 ----------------

@Composable
fun BigTechCard(t: Tables) {
    val rows = t.bigtech
    if (rows.isEmpty()) return
    val maxY = rows.maxOfOrNull { abs(it.ytd ?: 0.0) } ?: 1.0
    val maxH = rows.maxOfOrNull { abs(it.fromHigh ?: 0.0) } ?: 1.0
    val maxW = rows.maxOfOrNull { abs(it.week ?: 0.0) } ?: 1.0
    Board {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💻 주요 빅테크 현황", color = Palette.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${t.asOf ?: ""} 기준", color = Palette.Faint, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            HeaderCell("종목", modifier = Modifier.weight(1f))
            HeaderCell("올해", 70.dp)
            HeaderCell("고점대비", 70.dp)
            HeaderCell("주간", 66.dp)
        }
        rows.forEach { r: TableRow ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = Palette.Fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(r.ticker + (r.price?.let { "  $" + String.format("%,.2f", it) } ?: ""), color = Palette.Faint, fontSize = 10.sp, maxLines = 1)
                }
                BarCell(r.ytd, maxY, 70.dp)
                BarCell(r.fromHigh, maxH, 70.dp)
                BarCell(r.week, maxW, 66.dp, bold = true)
            }
        }
        Text("고점대비 = 최근 1년 가장 높았던 종가에서 얼마나 내려왔는지 · 상장 1년 안 된 종목은 올해 '-'",
            color = Palette.Faint, fontSize = 11.sp)
    }
}
