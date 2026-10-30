package com.yujh.todaymarket.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
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
import androidx.glance.appwidget.updateAll
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
import com.yujh.todaymarket.data.AppSettings
import com.yujh.todaymarket.data.Indicator
import com.yujh.todaymarket.data.MarketData
import com.yujh.todaymarket.data.Repository
import com.yujh.todaymarket.data.SettingsStore
import com.yujh.todaymarket.data.Stock
import com.yujh.todaymarket.data.arrange
import com.yujh.todaymarket.data.returnOf
import com.yujh.todaymarket.ui.MainActivity
import com.yujh.todaymarket.ui.Palette
import com.yujh.todaymarket.ui.arrow
import com.yujh.todaymarket.ui.changeColor
import com.yujh.todaymarket.ui.fmtChange
import com.yujh.todaymarket.ui.fmtNum
import com.yujh.todaymarket.ui.fmtPct
import com.yujh.todaymarket.ui.fmtPrice
import com.yujh.todaymarket.ui.fmtTime
import com.yujh.todaymarket.ui.fmtIndicatorChange
import com.yujh.todaymarket.ui.fmtIndicatorValue
import com.yujh.todaymarket.ui.indicatorDirection
import com.yujh.todaymarket.ui.zoneColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 홈 화면 위젯 (나무 스타일). 데이터와 설정은 앱과 같은 것을 쓴다. */
class TodayWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (data, settings) = withContext(Dispatchers.IO) {
            // 마지막으로 받은 지 25분이 넘었으면 새로 받아 본다 (실패하면 저장된 값 사용)
            val cache = File(context.filesDir, "latest.json")
            val old = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > 25 * 60 * 1000
            if (old) runCatching { Repository.fetch(context) }
            Repository.loadCached(context) to SettingsStore.load(context)
        }
        provideContent { WidgetBody(data, settings) }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.schedule(context)   // 첫 위젯을 놓으면 30분 자동 새로고침 시작
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RefreshWorker.cancel(context)     // 마지막 위젯을 지우면 멈춤
    }
}

/** 결과 파일이 2시간 넘게 지났으면 true (서버나 인터넷 문제로 못 받은 경우) */
fun isOld(generatedAt: String?): Boolean = try {
    val t = java.time.OffsetDateTime.parse(generatedAt)
    java.time.Duration.between(t, java.time.OffsetDateTime.now()).toMinutes() > 120
} catch (e: Exception) {
    false
}

/** ↻ 버튼: 새로 받고 위젯 다시 그리기 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runCatching { Repository.fetch(context) }
        TodayWidget().update(context, glanceId)
    }
}

private val TabKey = ActionParameters.Key<String>("tab")

/** 나누어 보기에서 [종목]/[지표] 버튼 */
class TabAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tab = parameters[TabKey] ?: "stocks"
        SettingsStore.save(context, SettingsStore.load(context).copy(widgetTab = tab))
        TodayWidget().updateAll(context)
    }
}

// ---------------- 화면 ----------------

/** 글씨 크기 설정을 곱한 글자 모양 */
private class Fonts(val scale: Float) {
    fun s(color: Color, size: Float, bold: Boolean = false) = TextStyle(
        color = ColorProvider(color), fontSize = (size * scale).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    )
}

private val openApp get() = actionStartActivity<MainActivity>()

@Composable
private fun WidgetBody(data: MarketData?, st: AppSettings) {
    val f = Fonts(st.fontScale)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(Palette.Bg).cornerRadius(26.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp).clickable(openApp),
    ) {
        Header(data, f)
        if (data == null) {
            Text("데이터를 받는 중이에요. 잠시 후 ↻ 를 눌러 주세요.", style = f.s(Palette.Sub, 13f),
                modifier = GlanceModifier.padding(top = 16.dp))
            return@Column
        }
        if (st.showMarketScore) ScoreBand(data, f)
        if (st.split) Tabs(st.widgetTab, f)
        val showStocks = !st.split || st.widgetTab == "stocks"
        val showInds = !st.split || st.widgetTab == "indicators"
        val stocks = st.arrange(data.stocks)
        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            if (showStocks && st.showSummary && data.summary.lines.isNotEmpty()) {
                item { Section("오늘 시황", "AI · ${fmtTime(data.summary.generatedAt)} 작성", f) }
                items(data.summary.lines) { line ->
                    Text("• $line", style = f.s(Color(0xFFD5DAE0), 12f),
                        modifier = GlanceModifier.padding(bottom = 1.dp).clickable(openApp))
                }
            }
            if (showStocks) {
                item { Section("관심 종목", "이슈: 최근 24시간 뉴스", f) }
                items(stocks, itemId = { it.code.hashCode().toLong() }) { StockRow(it, st, f) }
            }
            if (showInds) {
                val scoreInds = data.indicators.filter { it.group == "score" }
                val monInds = data.indicators.filter { it.group == "monitor" && it.key in st.widgetMonitors }
                item { Section("점수 지표", "회색 = 하루 넘게 지난 숫자", f) }
                items(scoreInds, itemId = { ("s" + it.key).hashCode().toLong() }) { IndicatorRow(it, f) }
                if (monInds.isNotEmpty()) {
                    item { Section("모니터링 지표", "점수 제외", f) }
                    items(monInds, itemId = { ("m" + it.key).hashCode().toLong() }) { IndicatorRow(it, f) }
                }
            }
            item {
                Text("누르면 자세히 보기", style = f.s(Palette.Faint, 10f),
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(openApp))
            }
        }
    }
}

@Composable
private fun Header(data: MarketData?, f: Fonts) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("오늘의 시장", style = f.s(Palette.Fg, 17f, bold = true), modifier = GlanceModifier.defaultWeight())
        val old = data != null && isOld(data.generatedAt)
        if (old) Text("지난 데이터 ", style = f.s(Palette.Stale, 11f, bold = true))
        Text(fmtTime(data?.generatedAt), style = f.s(if (old) Palette.Stale else Color(0xFFA8ADB3), 13f))
        Text("  ↻", style = f.s(Palette.Fg, 18f), modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()))
    }
}

@Composable
private fun Tabs(current: String, f: Fonts) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp)) {
        listOf("stocks" to "종목", "indicators" to "지표").forEach { (key, label) ->
            val on = key == current
            Text(
                label, style = f.s(if (on) Color.Black else Palette.Sub, 13f, bold = on),
                modifier = GlanceModifier.defaultWeight().background(if (on) Palette.Fg else Color(0xFF1B1B1B))
                    .cornerRadius(8.dp).padding(vertical = 6.dp, horizontal = 10.dp)
                    .clickable(actionRunCallback<TabAction>(actionParametersOf(TabKey to key))),
            )
            if (key == "stocks") Spacer(GlanceModifier.width(6.dp))
        }
    }
}

@Composable
private fun ScoreBand(data: MarketData, f: Fonts) {
    val ms = data.marketScore
    val score = ms.score
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp).background(Color(0xFF121212))
        .cornerRadius(10.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Text("시장 점수 ", style = f.s(Color(0xFF9AA0A8), 12f))
            Text(score?.toString() ?: "-", style = f.s(zoneColor(score), 22f, bold = true))
            Text("  ${ms.icon ?: ""} ${ms.zone ?: ""}", style = f.s(Palette.Fg, 14f, bold = true),
                modifier = GlanceModifier.defaultWeight())
            val y = ms.yesterday
            if (score != null && y != null) {
                val d = (score - y).toDouble()
                Text("어제보다 ${arrow(d)}${kotlin.math.abs(d).toInt()}", style = f.s(changeColor(d), 11f))
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
private fun Section(title: String, note: String, f: Fonts) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 10.dp, bottom = 3.dp).clickable(openApp),
        verticalAlignment = Alignment.Bottom) {
        Text(title, style = f.s(Palette.Accent, 14f, bold = true), modifier = GlanceModifier.defaultWeight())
        Text(note, style = f.s(Palette.Faint, 10f))
    }
}

@Composable
private fun StockRow(s: Stock, st: AppSettings, f: Fonts) {
    val c = changeColor(s.changePct)
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(openApp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(s.name, style = f.s(Palette.Fg, 14f, bold = true), maxLines = 1)
                Text(s.code, style = f.s(Color(0xFF9AA0A8), 10f), maxLines = 1)
            }
            Text(fmtPrice(s.price, s.currency) + if (s.market == "US") " ${arrow(s.changePct)}" else "",
                style = f.s(c, 13f))
            Text(fmtChange(s.change, s.currency), style = f.s(c, 13f),
                modifier = GlanceModifier.width((62 * st.fontScale).dp).padding(start = 6.dp))
            Text(fmtPct(s.changePct), style = f.s(c, 13f),
                modifier = GlanceModifier.width((66 * st.fontScale).dp).padding(start = 6.dp))
        }
        Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp)) {
            if (st.showStockScore && s.score != null) {
                Text("${s.score}${s.icon ?: ""} · ", style = f.s(zoneColor(s.score), 11f, bold = true))
            }
            val ret = st.returnOf(s)
            if (ret != null) {
                Text("내 수익 ${String.format("%+.1f%%", ret.first)} · ", style = f.s(changeColor(ret.first), 11f, bold = true))
            }
            Text(s.issue ?: s.error ?: "-", style = f.s(Color(0xFFC3C9CF), 11f), maxLines = 2)
        }
    }
}

@Composable
private fun IndicatorRow(ind: Indicator, f: Fonts) {
    val grey = ind.stale
    val col = if (grey) Palette.Stale else changeColor(indicatorDirection(ind))
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(openApp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(ind.name, style = f.s(if (grey) Palette.Stale else Palette.Fg, 13f, bold = true), maxLines = 1)
            Text("${fmtTime(ind.asOf)} 기준 · ${ind.cycle ?: ""}",
                style = f.s(if (grey) Color(0xFF4E545A) else Color(0xFF6F9BD1), 10f), maxLines = 1)
        }
        Text(fmtIndicatorValue(ind), style = f.s(col, 13f))
        Text(fmtIndicatorChange(ind), style = f.s(col, 12f),
            modifier = GlanceModifier.width((70 * f.scale).dp).padding(start = 6.dp))
    }
}
