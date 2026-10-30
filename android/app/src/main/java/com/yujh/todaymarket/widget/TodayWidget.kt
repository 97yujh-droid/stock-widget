package com.yujh.todaymarket.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.yujh.todaymarket.data.Indicator
import com.yujh.todaymarket.data.MarketData
import com.yujh.todaymarket.data.Repository
import com.yujh.todaymarket.data.Stock
import com.yujh.todaymarket.ui.MainActivity
import com.yujh.todaymarket.ui.Palette
import com.yujh.todaymarket.ui.arrow
import com.yujh.todaymarket.ui.changeColor
import com.yujh.todaymarket.ui.fmtChange
import com.yujh.todaymarket.ui.fmtNum
import com.yujh.todaymarket.ui.fmtPct
import com.yujh.todaymarket.ui.fmtPrice
import com.yujh.todaymarket.ui.fmtTime
import com.yujh.todaymarket.ui.zoneColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 홈 화면 위젯 (나무 스타일). 데이터는 앱과 같은 결과 파일을 쓴다. */
class TodayWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = withContext(Dispatchers.IO) {
            // 마지막으로 받은 지 25분이 넘었으면 새로 받아 본다 (실패하면 저장된 값 사용)
            val cache = File(context.filesDir, "latest.json")
            val old = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > 25 * 60 * 1000
            if (old) runCatching { Repository.fetch(context) }
            Repository.loadCached(context)
        }
        provideContent { WidgetBody(data) }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

/** ↻ 버튼: 새로 받고 위젯 다시 그리기 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runCatching { Repository.fetch(context) }
        TodayWidget().update(context, glanceId)
    }
}

// ---------------- 화면 ----------------

private fun style(color: Color, size: TextUnit, bold: Boolean = false) =
    TextStyle(color = ColorProvider(color), fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

private val openApp get() = actionStartActivity<MainActivity>()

@Composable
private fun WidgetBody(data: MarketData?) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(Palette.Bg).cornerRadius(26.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp).clickable(openApp),
    ) {
        Header(data)
        if (data == null) {
            Text("데이터를 받는 중이에요. 잠시 후 ↻ 를 눌러 주세요.", style = style(Palette.Sub, 13.sp),
                modifier = GlanceModifier.padding(top = 16.dp))
            return@Column
        }
        ScoreBand(data)
        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            if (data.summary.lines.isNotEmpty()) {
                item { Section("오늘 시황", "AI · ${fmtTime(data.summary.generatedAt)} 작성") }
                items(data.summary.lines) { line ->
                    Text("• $line", style = style(Color(0xFFD5DAE0), 12.sp),
                        modifier = GlanceModifier.padding(bottom = 1.dp).clickable(openApp))
                }
            }
            item { Section("관심 종목", "이슈: 최근 24시간 뉴스") }
            items(data.stocks, itemId = { it.code.hashCode().toLong() }) { StockRow(it) }
            item { Section("시장 지표", "회색 = 하루 넘게 지난 숫자") }
            items(data.indicators, itemId = { it.key.hashCode().toLong() }) { IndicatorRow(it) }
            item {
                Text("누르면 자세히 보기", style = style(Palette.Faint, 10.sp),
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(openApp))
            }
        }
    }
}

@Composable
private fun Header(data: MarketData?) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("오늘의 시장", style = style(Palette.Fg, 17.sp, bold = true), modifier = GlanceModifier.defaultWeight())
        Text(fmtTime(data?.generatedAt), style = style(Color(0xFFA8ADB3), 13.sp))
        Text("  ↻", style = style(Palette.Fg, 18.sp), modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()))
    }
}

@Composable
private fun ScoreBand(data: MarketData) {
    val ms = data.marketScore
    val score = ms.score
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp).background(Color(0xFF121212))
        .cornerRadius(10.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Text("시장 점수 ", style = style(Color(0xFF9AA0A8), 12.sp))
            Text(score?.toString() ?: "-", style = style(zoneColor(score), 22.sp, bold = true))
            Text("  ${ms.icon ?: ""} ${ms.zone ?: ""}", style = style(Palette.Fg, 14.sp, bold = true),
                modifier = GlanceModifier.defaultWeight())
            val y = ms.yesterday
            if (score != null && y != null) {
                val d = (score - y).toDouble()
                Text("어제보다 ${arrow(d)}${kotlin.math.abs(d).toInt()}", style = style(changeColor(d), 11.sp))
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        LinearProgressIndicator(
            progress = (score ?: 0) / 100f,
            modifier = GlanceModifier.fillMaxWidth().height(5.dp),
            color = ColorProvider(zoneColor(score)),
            backgroundColor = ColorProvider(Color(0xFF2A2A2A)),
        )
    }
}

@Composable
private fun Section(title: String, note: String) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 10.dp, bottom = 3.dp).clickable(openApp),
        verticalAlignment = Alignment.Bottom) {
        Text(title, style = style(Palette.Accent, 14.sp, bold = true), modifier = GlanceModifier.defaultWeight())
        Text(note, style = style(Palette.Faint, 10.sp))
    }
}

@Composable
private fun StockRow(s: Stock) {
    val c = changeColor(s.changePct)
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(openApp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(s.name, style = style(Palette.Fg, 14.sp, bold = true), maxLines = 1)
                Text(s.code, style = style(Color(0xFF9AA0A8), 10.sp), maxLines = 1)
            }
            Text(fmtPrice(s.price, s.currency) + if (s.market == "US") " ${arrow(s.changePct)}" else "",
                style = style(c, 13.sp))
            Text(fmtChange(s.change, s.currency), style = style(c, 13.sp),
                modifier = GlanceModifier.width(62.dp).padding(start = 6.dp))
            Text(fmtPct(s.changePct), style = style(c, 13.sp), modifier = GlanceModifier.width(66.dp).padding(start = 6.dp))
        }
        val head = if (s.score != null) "${s.score}${s.icon ?: ""} · " else ""
        Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp)) {
            if (head.isNotEmpty()) Text(head, style = style(zoneColor(s.score), 11.sp, bold = true))
            Text(s.issue ?: s.error ?: "-", style = style(Color(0xFFC3C9CF), 11.sp), maxLines = 2)
        }
    }
}

@Composable
private fun IndicatorRow(ind: Indicator) {
    val grey = ind.stale
    val v = ind.value
    val valueText = when {
        ind.needInput -> "입력 필요"
        v == null -> "-"
        ind.key == "fear_greed" -> "${v.toInt()} ${ind.label ?: ""}"
        ind.key == "margin_debt" -> String.format("%+.1f%%", v)
        ind.key == "us10y" -> String.format("%.3f%%", v)
        ind.key == "hy_spread" -> String.format("%.2f", v)
        else -> fmtNum(v)
    }
    val col = if (grey) Palette.Stale else changeColor(ind.change)
    val chg = ind.change
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(openApp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(ind.name, style = style(if (grey) Palette.Stale else Palette.Fg, 13.sp, bold = true), maxLines = 1)
            Text("${fmtTime(ind.asOf)} 기준 · ${ind.cycle ?: ""}", style = style(if (grey) Color(0xFF4E545A) else Color(0xFF6F9BD1), 10.sp), maxLines = 1)
        }
        Text(valueText, style = style(col, 13.sp))
        Text(
            if (chg == null || ind.key == "margin_debt") "" else "${arrow(chg)}${fmtNum(kotlin.math.abs(chg), if (ind.key == "fear_greed") 0 else 2)}",
            style = style(col, 12.sp), modifier = GlanceModifier.width(66.dp).padding(start = 6.dp),
        )
    }
}
