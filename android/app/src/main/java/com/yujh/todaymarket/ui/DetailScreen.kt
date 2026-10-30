package com.yujh.todaymarket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yujh.todaymarket.data.AppSettings
import com.yujh.todaymarket.data.Indicator
import com.yujh.todaymarket.data.arrange
import com.yujh.todaymarket.data.returnOf
import com.yujh.todaymarket.widget.isOld
import com.yujh.todaymarket.data.MarketData
import com.yujh.todaymarket.data.ScoreItem
import com.yujh.todaymarket.data.Stock

@Composable
fun DetailScreen(
    data: MarketData?, loading: Boolean, error: String?, settings: AppSettings,
    onRefresh: () -> Unit, onOpenSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TopBar(data, loading, onRefresh, onOpenSettings) }
        if (error != null) item { Notice(error) }
        if (data == null) {
            item { Notice(if (loading) "데이터를 받아오는 중이에요…" else "아직 데이터가 없어요. ↻ 를 눌러 주세요.") }
            return@LazyColumn
        }
        if (settings.showMarketScore) item { MarketScoreCard(data) }
        if (settings.showSummary) item { SummaryCard(data) }
        item { SectionTitle(if (settings.showStockScore) "관심 종목 · 종목 점수" else "관심 종목") }
        items(settings.arrange(data.stocks), key = { it.code }) { StockCard(it, settings) }
        val scoreInds = data.indicators.filter { it.group == "score" }
        val monInds = data.indicators.filter { it.group == "monitor" }
        item { SectionTitle("점수 지표 (시장 점수에 반영)") }
        items(scoreInds, key = { "s_" + it.key }) { IndicatorCard(it) }
        if (monInds.isNotEmpty()) {
            item { SectionTitle("모니터링 지표 (점수에는 반영 안 함)") }
            items(monInds, key = { "m_" + it.key }) { IndicatorCard(it) }
        }
        if (data.calendar.isNotEmpty()) item { CalendarCard(data) }
        item {
            Text(
                "점수와 요약은 참고용이에요. 투자 결정은 직접 하셔야 해요.",
                color = Palette.Faint, fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        }
    }
}

@Composable
private fun TopBar(data: MarketData?, loading: Boolean, onRefresh: () -> Unit, onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(data?.title ?: "오늘의 시장", color = Palette.Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            if (loading) "받는 중…" else (if (isOld(data?.generatedAt)) "지난 데이터 · " else "업데이트 ") + fmtTime(data?.generatedAt),
            color = if (isOld(data?.generatedAt)) Palette.Stale else Palette.Sub, fontSize = 12.sp,
        )
        Text(
            "  ↻", color = Palette.Fg, fontSize = 22.sp,
            modifier = Modifier.clickable(enabled = !loading, onClick = onRefresh).padding(4.dp),
        )
        Text(
            " ⚙", color = Palette.Fg, fontSize = 20.sp,
            modifier = Modifier.clickable(onClick = onOpenSettings).padding(4.dp),
        )
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text, color = Palette.Sub, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Box).padding(12.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

// ---------------- 시장 점수 ----------------

@Composable
private fun MarketScoreCard(data: MarketData) {
    val ms = data.marketScore
    Card {
        Text("시장 점수", color = Palette.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ms.score?.toString() ?: "-", color = zoneColor(ms.score), fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("${ms.icon ?: ""} ${ms.zone ?: ""}", color = Palette.Fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val y = ms.yesterday
                if (y != null && ms.score != null) {
                    val d = (ms.score - y).toDouble()
                    Text("어제 $y → 오늘 ${ms.score} (${arrow(d)}${kotlin.math.abs(d).toInt()})",
                        color = changeColor(d), fontSize = 12.sp)
                }
            }
        }
        ZoneBar(ms.score)
        if (!ms.comment.isNullOrBlank()) AiBox(ms.comment)
        ms.items.forEach { ScoreRow(it) }
        Text("일부 항목은 하루 1번(추세·하이일드·금리차), 한 달 1번(Margin Debt) 바뀌어요. 빈 항목은 빼고 100점 만점으로 환산해요.",
            color = Palette.Sub, fontSize = 11.sp)
    }
}

@Composable
fun ZoneBar(score: Int?) {
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
            Row(Modifier.fillMaxWidth().height(6.dp).align(Alignment.Center).clip(RoundedCornerShape(3.dp))) {
                ZoneColors.forEach { Box(Modifier.weight(1f).height(6.dp).background(it)) }
            }
            if (score != null) {
                Box(
                    Modifier.offset(x = maxWidth * (score.coerceIn(0, 100) / 100f) - 2.dp)
                        .width(4.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(Color.White),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("⛈ 폭풍", "🌧 비", "☁️ 흐림", "🌤 구름조금", "☀️ 맑음").forEach {
                Text(it, color = Palette.Sub, fontSize = 10.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AiBox(text: String) {
    Text("🤖 $text", color = Palette.Fg, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Box).padding(10.dp))
}

@Composable
private fun ScoreRow(item: ScoreItem) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(item.name, color = Palette.Fg, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            Text("  가중치 ${item.weight}", color = Palette.Faint, fontSize = 11.sp, maxLines = 1)
        }
        Text(item.value ?: "-", color = Palette.Sub, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF262626))) {
                if (item.score != null) {
                    Box(Modifier.fillMaxWidth(item.score.coerceIn(0, 100) / 100f).height(6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(zoneColor(item.score)))
                }
            }
            Text(
                if (item.score == null) "  빠짐" else "  ${item.score}점 → +${String.format("%.1f", item.points ?: 0.0)}",
                color = Palette.Sub, fontSize = 11.sp,
            )
        }
    }
}

// ---------------- 시황 ----------------

@Composable
private fun SummaryCard(data: MarketData) {
    Card {
        Text("오늘 시황 (AI 요약)", color = Palette.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (data.summary.lines.isEmpty()) Text("아직 요약이 없어요.", color = Palette.Sub, fontSize = 13.sp)
        data.summary.lines.forEach { Text("• $it", color = Palette.Fg, fontSize = 14.sp) }
        Text("${fmtTime(data.summary.generatedAt)} 작성 · 하루 5번 새로 요약 (06·08·12·15:40·21시) · 경제 지표 발표 포함",
            color = Palette.Sub, fontSize = 11.sp)
    }
}

// ---------------- 종목 ----------------

@Composable
private fun StockCard(s: Stock, settings: AppSettings) {
    var open by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.clickable { open = !open }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, color = Palette.Fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.code, color = Palette.Faint, fontSize = 11.sp)
                        if (s.score != null && settings.showStockScore) {
                            Text("  ${s.score}${s.icon ?: ""}", color = zoneColor(s.score), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtPrice(s.price, s.currency), color = changeColor(s.changePct), fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${arrow(s.change)}${fmtChange(s.change, s.currency)}  ${fmtPct(s.changePct)}",
                        color = changeColor(s.changePct), fontSize = 12.sp, maxLines = 1)
                }
            }
            Text(s.issue ?: s.error ?: "-", color = Palette.Fg.copy(alpha = 0.85f), fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp))
            val ret = settings.returnOf(s)
            if (ret != null) {
                val h = settings.holdings[s.code]
                val money = if (s.currency == "KRW") fmtChange(ret.second, "KRW") + "원" else "$" + fmtChange(ret.second, "USD")
                Text(
                    "보유 ${h?.qty?.let { fmtNum(it, if (it % 1.0 == 0.0) 0 else 2) } ?: "-"}주 · 평단 ${fmtPrice(h?.avg, s.currency)} · 내 수익 " +
                        String.format("%+.1f%%", ret.first) + " (${if (ret.second >= 0) "+" else "-"}$money)",
                    color = changeColor(ret.first), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text("가격 기준 ${fmtTime(s.asOf)} · 이슈는 최근 24시간 뉴스 · 30분마다 갱신",
                color = Palette.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            Text(if (open) "접기 ▴" else if (settings.showStockScore) "종목 점수 ${s.score ?: "-"} · 항목별 보기 ▾" else "자세히 보기 ▾",
                color = Palette.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (open) {
            if (!s.comment.isNullOrBlank()) AiBox(s.comment)
            if (settings.showStockScore) s.scoreItems.forEach { ScoreRow(it) }
            if (s.news.isNotEmpty()) {
                Text("근거 뉴스", color = Palette.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                s.news.forEach { n ->
                    Text("· ${n.title} (${n.source ?: ""} ${fmtTime(n.time)})", color = Palette.Sub, fontSize = 12.sp)
                }
            }
        }
    }
}

// ---------------- 지표 ----------------

@Composable
private fun IndicatorCard(ind: Indicator) {
    val g = GUIDE[ind.key]
    val grey = ind.stale
    val dir = indicatorDirection(ind)
    Card {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(ind.name, color = if (grey) Palette.Stale else Palette.Fg, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtIndicatorValue(ind), color = if (grey) Palette.Stale else changeColor(dir), fontSize = 15.sp,
                    fontWeight = FontWeight.Bold)
                val c = fmtIndicatorChange(ind)
                if (c.isNotEmpty()) Text(c, color = if (grey) Palette.Stale else changeColor(dir), fontSize = 12.sp)
            }
        }
        if (g != null) {
            Text(g.what + " · " + g.meaning + if (grey) "  · 하루 넘게 지난 숫자" else "", color = Palette.Sub, fontSize = 12.sp)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Box).padding(8.dp)) {
            if (g != null) Meta("기준치", g.levels)
            Meta("기준 시각", fmtTime(ind.asOf) + (ind.published?.let { " (발표 ${fmtTime(it)})" } ?: ""))
            Meta("바뀌는 때", g?.changes ?: (ind.cycle ?: "-"))
            if (!ind.label.isNullOrBlank() && ind.key !in setOf("fear_greed")) Meta("참고", ind.label)
            Meta("출처", ind.source ?: "-")
        }
    }
}

@Composable
private fun CalendarCard(data: MarketData) {
    val now = java.time.OffsetDateTime.now()
    val events = data.calendar.mapNotNull { e ->
        val t = runCatching { java.time.OffsetDateTime.parse(e.time) }.getOrNull() ?: return@mapNotNull null
        if (t.isBefore(now.minusHours(36)) || t.isAfter(now.plusHours(72))) null else t to e
    }.sortedBy { it.first }
    if (events.isEmpty()) return
    Card {
        Text("경제 지표 일정 (미국·한국)", color = Palette.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        events.forEach { (t, e) ->
            val done = !t.isAfter(now)
            Column(Modifier.padding(vertical = 3.dp)) {
                Row {
                    Text(fmtTime(e.time), color = Palette.Sub, fontSize = 12.sp, modifier = Modifier.width(78.dp))
                    Text("${if (e.country == "USD") "🇺🇸" else "🇰🇷"} ${e.title}" + if (e.impact == "High") " ★" else "",
                        color = if (done) Palette.Fg else Palette.Fg.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                Text(
                    "실제 ${e.actual ?: if (done) "확인 중" else "-"} · 예상 ${e.forecast ?: "-"} · 이전 ${e.previous ?: "-"}",
                    color = Palette.Sub, fontSize = 11.sp, modifier = Modifier.padding(start = 78.dp),
                )
            }
        }
        Text("★ = 시장에 영향이 큰 지표 · 시각은 한국 기준", color = Palette.Faint, fontSize = 11.sp)
    }
}

@Composable
private fun Meta(k: String, v: String) {
    Row {
        Text(k, color = Palette.Faint, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        Text(v, color = Palette.Fg.copy(alpha = 0.8f), fontSize = 11.sp)
    }
}
