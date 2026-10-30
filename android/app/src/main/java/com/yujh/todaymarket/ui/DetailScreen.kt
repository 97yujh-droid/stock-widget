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
        item { SectionTitle("시장 지표") }
        items(data.indicators, key = { it.key }) { IndicatorCard(it) }
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
        Text("${fmtTime(data.summary.generatedAt)} 작성 · 하루 5번 새로 요약 (06·08·12·15:40·21시)",
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

private data class IndInfo(val desc: String, val changes: String, val app: String)

private val INFO = mapOf(
    "usdkrw" to IndInfo("오르면 원화 가치가 떨어진 것", "평일 거의 하루 종일 거래돼서 계속 바뀌어요", "30분마다"),
    "dxy" to IndInfo("세계 주요 통화와 비교한 달러의 힘", "평일 24시간 거래돼서 계속 바뀌어요", "30분마다"),
    "us10y" to IndInfo("오르면 주식에 부담이 되는 경우가 많음", "평일 채권 시장이 열려 있는 동안 바뀌어요", "30분마다"),
    "vix" to IndInfo("시장의 불안 정도. 20 아래면 안정, 30 위면 불안", "미국 장중에만 바뀌어요. 낮에는 전날 마감 숫자예요", "미국 장중 30분마다"),
    "hy_spread" to IndInfo("오르면 기업 부도 걱정이 커진 것", "하루 1번, 한국 밤 11시쯤 전날 숫자가 올라와요", "매일 밤 첫 수집 때"),
    "fear_greed" to IndInfo("0 극도의 공포 ~ 100 극도의 탐욕", "미국 장중에 수시로 바뀌어요", "미국 장중 30분마다"),
    "margin_debt" to IndInfo("빚내서 투자한 돈이 1년 전보다 얼마나 늘었는지", "한 달에 한 번, 다음 달 중순 발표", "입력하면 반영"),
)

@Composable
private fun IndicatorCard(ind: Indicator) {
    val info = INFO[ind.key]
    val grey = ind.stale
    val v: Double? = ind.value
    val valueText = when {
        ind.needInput -> "입력 필요"
        v == null -> "-"
        ind.key == "fear_greed" -> "${v.toInt()} ${ind.label ?: ""}"
        ind.key == "margin_debt" -> String.format("%+.1f%%", v)
        ind.key == "us10y" -> String.format("%.3f%%", v)
        ind.key == "hy_spread" -> String.format("%.2f%%p", v)
        else -> fmtNum(v)
    }
    val chg = if (ind.change != null && ind.key != "margin_debt") " ${arrow(ind.change)}${fmtNum(kotlin.math.abs(ind.change), if (ind.key == "fear_greed") 0 else 2)}" else ""
    Card {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(ind.name, color = if (grey) Palette.Stale else Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(valueText + chg, color = if (grey) Palette.Stale else changeColor(ind.change), fontSize = 15.sp)
        }
        if (info != null) Text(info.desc + if (grey) "  · 하루 넘게 지난 숫자" else "", color = Palette.Sub, fontSize = 12.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Box).padding(8.dp)) {
            Meta("기준", fmtTime(ind.asOf) + (ind.published?.let { " (발표 ${fmtTime(it)})" } ?: ""))
            if (info != null) {
                Meta("바뀌는 때", info.changes)
                Meta("앱 반영", info.app)
            }
            Meta("출처", ind.source ?: "-")
        }
    }
}

@Composable
private fun Meta(k: String, v: String) {
    Row {
        Text(k, color = Palette.Faint, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        Text(v, color = Palette.Fg.copy(alpha = 0.8f), fontSize = 11.sp)
    }
}
