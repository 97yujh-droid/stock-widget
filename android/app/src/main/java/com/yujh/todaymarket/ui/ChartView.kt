package com.yujh.todaymarket.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yujh.todaymarket.data.ChartPoint
import com.yujh.todaymarket.data.Charts
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** 화면 어디서나 그래프 숫자를 꺼내 쓰도록 */
val LocalCharts = compositionLocalOf<Charts?> { null }

/** 기준치 구간 색 띠 */
data class Band(val from: Double, val to: Double, val color: Color)

private val G = Color(0xFF39C27A)
private val Y = Color(0xFFC2B25A)
private val O = Color(0xFFE0883C)
private val P = Color(0xFFA46CF0)
private const val INF = 1e12

/** 지표별 기준치 띠 (IndicatorGuide 의 기준치와 같은 숫자) */
val BANDS: Map<String, List<Band>> = mapOf(
    "vix" to listOf(Band(-INF, 15.0, G), Band(15.0, 20.0, Y), Band(20.0, 30.0, O), Band(30.0, INF, P)),
    "hy_spread" to listOf(Band(-INF, 3.5, G), Band(3.5, 5.0, O), Band(5.0, INF, P)),
    "us10y" to listOf(Band(-INF, 4.0, G), Band(4.0, 4.5, Y), Band(4.5, 5.0, O), Band(5.0, INF, P)),
    "fear_greed" to listOf(Band(0.0, 25.0, P), Band(25.0, 45.0, O), Band(45.0, 65.0, G), Band(65.0, 80.0, O), Band(80.0, 100.0, P)),
    "move" to listOf(Band(-INF, 80.0, G), Band(80.0, 100.0, Y), Band(100.0, 120.0, O), Band(120.0, INF, P)),
    "curve" to listOf(Band(-INF, 0.0, P), Band(0.0, 0.5, O), Band(0.5, INF, G)),
    "wti" to listOf(Band(-INF, 60.0, O), Band(60.0, 80.0, Y), Band(90.0, INF, O)),
    "put_call" to listOf(Band(-INF, 0.5, O), Band(0.5, 0.7, G), Band(0.7, 0.8, Y), Band(0.8, INF, P)),
    "rsi_ndx" to listOf(Band(-INF, 30.0, P), Band(30.0, 70.0, G), Band(70.0, INF, O)),
    "rsi_spx" to listOf(Band(-INF, 30.0, P), Band(30.0, 70.0, G), Band(70.0, INF, O)),
    "bei10" to listOf(Band(2.0, 2.5, G), Band(2.7, INF, O)),
    "market_score" to listOf(Band(0.0, 20.0, P), Band(20.0, 40.0, O), Band(40.0, 60.0, Y), Band(60.0, 80.0, Color(0xFF9BD36A)), Band(80.0, 100.0, G)),
)

/** 그래프 기간 */
enum class Period(val label: String, val days: Long) { M1("1개월", 31), M3("3개월", 92), Y1("1년", 366) }

private fun periodsFor(key: String) = when (key) {
    "margin_debt" -> listOf(Period.Y1)
    "market_score" -> listOf(Period.M1, Period.M3, Period.Y1)
    else -> listOf(Period.M1, Period.M3, Period.Y1)
}

private fun defaultPeriod(key: String) = when (key) {
    "margin_debt" -> Period.Y1
    "market_score" -> Period.M1
    else -> Period.M3
}

private fun cut(pts: List<ChartPoint>, p: Period): List<ChartPoint> {
    if (pts.isEmpty()) return pts
    val from = pts.last().date.minusDays(p.days)
    return pts.filter { !it.date.isBefore(from) }
}

private fun fmtV(v: Double): String {
    val a = abs(v)
    return fmtNum(v, when { a >= 1000 -> 0; a >= 100 -> 1; else -> 2 })
}

private val ymd = DateTimeFormatter.ofPattern("yy.M.d")

/**
 * 카드 안 그래프. 항상 보이고, 기간 버튼으로 1개월/3개월/1년.
 * 손가락으로 좌우로 끌면 날짜별 값, "크게"를 누르면 높이가 커진다.
 * overlayKey: 같이 그릴 보조선 (예: S&P500 의 200일 평균)
 */
@Composable
fun ChartBlock(
    key: String, note: String? = null, overlayKey: String? = null, overlayName: String? = null,
    height: Dp = 96.dp, title: String? = null,
) {
    val charts = LocalCharts.current
    val all = charts?.series?.get(key) ?: return
    if (all.size < 2) {
        Text("그래프: 기록이 쌓이는 중이에요 (하루 1개씩)", color = Palette.Faint, fontSize = 11.sp)
        return
    }
    var big by remember(key) { mutableStateOf(false) }
    var period by remember(key) { mutableStateOf(defaultPeriod(key)) }
    var touch by remember(key, period) { mutableStateOf<Int?>(null) }
    val pts = cut(all, period).ifEmpty { all }
    val overlay = overlayKey?.let { charts.series[it] }?.let { o ->
        val from = pts.first().date
        o.filter { !it.date.isBefore(from) }
    }
    val first = pts.first().value
    val last = pts.last().value
    val pct = if (first != 0.0) (last / first - 1) * 100 else 0.0
    val lineColor = changeColor(last - first)
    val t = touch?.coerceIn(0, pts.size - 1)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF181818))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (title != null) {
                Text(title, color = Palette.Sub, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
            }
            periodsFor(key).forEach { p ->
                val on = p == period
                Text(
                    p.label, color = if (on) Palette.Bg else Palette.Sub, fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) Palette.Accent else Color(0xFF262626))
                        .clickable { period = p; touch = null }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (t != null) {
                Text("${pts[t].date.format(ymd)}  ${fmtV(pts[t].value)}", color = Palette.Fg, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold)
            } else {
                Text(String.format("%+.1f%%", pct), color = lineColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (big) "  작게" else "  크게", color = Palette.Sub, fontSize = 11.sp,
                modifier = Modifier.clickable { big = !big }.padding(start = 2.dp))
        }
        LineChart(
            pts, overlay, BANDS[key], lineColor,
            Modifier.fillMaxWidth().height(if (big) 210.dp else height), big = big,
            touch = t, onTouch = { touch = it },
        )
        if (big || overlay != null || note != null) {
            val legend = buildList {
                if (overlay != null) add("점선 = ${overlayName ?: "보조선"}")
                if (big && BANDS[key] != null) add("색 띠 = 기준치 (초록 좋음 · 노랑 보통 · 주황 주의 · 보라 위험)")
                if (big) add("좌우로 끌면 날짜별 값")
                if (note != null) add(note)
            }
            if (legend.isNotEmpty()) Text(legend.joinToString(" · "), color = Palette.Faint, fontSize = 10.sp)
        }
    }
}

private val monthFmt = DateTimeFormatter.ofPattern("M월")

@Composable
private fun LineChart(
    pts: List<ChartPoint>, overlay: List<ChartPoint>?, bands: List<Band>?, lineColor: Color,
    modifier: Modifier, big: Boolean, touch: Int?, onTouch: (Int?) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Palette.Faint, fontSize = 9.sp)
    val vals = pts.map { it.value } + (overlay?.map { it.value } ?: emptyList())
    var lo = vals.min()
    var hi = vals.max()
    if (hi - lo < 1e-9) { hi += 1.0; lo -= 1.0 }
    val pad = (hi - lo) * 0.08
    lo -= pad; hi += pad
    val n = pts.size
    val index = pts.withIndex().associate { it.value.date to it.index }

    val gutterDp = 40.dp
    Canvas(
        modifier.pointerInput(n, big) {
            val gw = gutterDp.toPx()
            fun idx(x: Float) = ((x / (size.width - gw)) * (n - 1)).toInt().coerceIn(0, n - 1)
            detectTapGestures(onTap = { onTouch(idx(it.x)) }, onDoubleTap = { onTouch(null) })
        }.pointerInput(n, big, "drag") {
            val gw = gutterDp.toPx()
            fun idx(x: Float) = ((x / (size.width - gw)) * (n - 1)).toInt().coerceIn(0, n - 1)
            detectHorizontalDragGestures(onDragStart = { onTouch(idx(it.x)) }) { change, _ ->
                onTouch(idx(change.position.x))
            }
        },
    ) {
        val gutter = gutterDp.toPx()
        val bottomPad = if (big) 14.dp.toPx() else 0f
        val w = size.width - gutter
        val h = size.height - bottomPad
        fun y(v: Double) = (h - ((v - lo) / (hi - lo) * h)).toFloat()
        fun x(i: Int) = if (n == 1) w / 2 else w * i / (n - 1)

        // 기준치 띠
        bands?.forEach { b ->
            val top = y(minOf(b.to, hi)).coerceIn(0f, h)
            val bot = y(maxOf(b.from, lo)).coerceIn(0f, h)
            if (bot - top > 0.5f) drawRect(b.color.copy(alpha = 0.12f), Offset(0f, top), Size(w, bot - top))
        }
        // 가로 눈금선 + 오른쪽 숫자
        val ticks = if (big) 4 else 2
        for (k in 0..ticks) {
            val v = lo + pad + (hi - lo - 2 * pad) * k / ticks
            val yy = y(v)
            drawLine(Color(0xFF2A2A2A), Offset(0f, yy), Offset(w, yy), 1f)
            val tl = measurer.measure(fmtV(v), labelStyle)
            drawText(tl, topLeft = Offset(w + 4f, (yy - tl.size.height / 2f).coerceIn(0f, h - tl.size.height)))
        }
        // 아래 월 표시 (크게 볼 때)
        if (big) {
            var lastMonth = -1
            pts.forEachIndexed { i, p ->
                if (p.date.monthValue != lastMonth) {
                    if (lastMonth != -1 || i == 0) {
                        val tl = measurer.measure(p.date.format(monthFmt), labelStyle)
                        val xx = x(i)
                        if (xx + tl.size.width < w) drawText(tl, topLeft = Offset(xx, h + 2f))
                    }
                    lastMonth = p.date.monthValue
                }
            }
        }
        // 선 아래 채우기 + 선
        val line = Path()
        pts.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(i), y(p.value)) else line.lineTo(x(i), y(p.value)) }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(n - 1), h)
            lineTo(x(0), h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.30f), lineColor.copy(alpha = 0.02f)), 0f, h))
        // 보조선 (예: 200일 평균)
        if (overlay != null && overlay.size > 1) {
            val op = Path()
            var started = false
            overlay.forEach { p ->
                val i = index[p.date] ?: return@forEach
                if (!started) { op.moveTo(x(i), y(p.value)); started = true } else op.lineTo(x(i), y(p.value))
            }
            drawPath(op, Color(0xFFB8C0CC), style = Stroke(width = 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        }
        drawPath(line, lineColor, style = Stroke(width = if (big) 4f else 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // 손가락 위치 / 마지막 점
        if (touch != null) {
            val tx = x(touch)
            drawLine(Color.White.copy(alpha = 0.55f), Offset(tx, 0f), Offset(tx, h), 2f)
            drawCircle(lineColor, 8f, Offset(tx, y(pts[touch].value)))
            drawCircle(Color.White, 3.5f, Offset(tx, y(pts[touch].value)))
        } else {
            drawCircle(lineColor.copy(alpha = 0.25f), 12f, Offset(x(n - 1), y(pts.last().value)))
            drawCircle(lineColor, 5f, Offset(x(n - 1), y(pts.last().value)))
        }
    }
}
