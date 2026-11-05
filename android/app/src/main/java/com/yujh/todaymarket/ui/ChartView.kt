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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
 * 카드 안 그래프: 평소엔 작은 선 그래프, 누르면 크게 + 기간 고르기 + 손가락으로 날짜별 값 보기.
 * note: 그래프 아래에 덧붙일 설명 (예: Margin Debt 는 잔고 그래프)
 */
@Composable
fun ChartBlock(key: String, note: String? = null) {
    val all = LocalCharts.current?.series?.get(key) ?: return
    if (all.size < 2) {
        Text("그래프: 기록이 쌓이는 중이에요 (하루 1개씩)", color = Palette.Faint, fontSize = 11.sp)
        return
    }
    var open by remember(key) { mutableStateOf(false) }
    var period by remember(key) { mutableStateOf(defaultPeriod(key)) }
    val pts = cut(all, period).ifEmpty { all }
    val first = pts.first().value
    val last = pts.last().value
    val pct = if (first != 0.0) (last / first - 1) * 100 else 0.0
    val lineColor = changeColor(last - first)

    if (!open) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = true }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineChart(pts, null, lineColor, Modifier.weight(1f).height(36.dp), small = true)
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(period.label, color = Palette.Faint, fontSize = 10.sp)
                Text(String.format("%+.1f%%", pct), color = lineColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("크게 ▸", color = Palette.Sub, fontSize = 10.sp)
            }
        }
        return
    }

    var touch by remember(key, period) { mutableStateOf<Int?>(null) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Box).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            periodsFor(key).forEach { p ->
                val on = p == period
                Text(
                    p.label, color = if (on) Palette.Bg else Palette.Sub, fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) Palette.Accent else Color(0xFF262626))
                        .clickable { period = p; touch = null }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text("접기 ▴", color = Palette.Sub, fontSize = 12.sp,
                modifier = Modifier.clickable { open = false }.padding(4.dp))
        }
        val t = touch?.coerceIn(0, pts.size - 1)
        val shown = if (t != null) pts[t] else pts.last()
        Text(
            (if (t != null) shown.date.format(ymd) else "지금") + "  " + fmtV(shown.value) +
                "   ·   ${period.label} 동안 " + String.format("%+.1f%%", pct),
            color = Palette.Fg, fontSize = 12.sp,
        )
        LineChart(pts, BANDS[key], lineColor, Modifier.fillMaxWidth().height(170.dp), small = false,
            touch = t, onTouch = { touch = it })
        Row {
            Text(pts.first().date.format(ymd), color = Palette.Faint, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("최저 ${fmtV(pts.minOf { it.value })} · 최고 ${fmtV(pts.maxOf { it.value })}",
                color = Palette.Faint, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(pts.last().date.format(ymd), color = Palette.Faint, fontSize = 10.sp)
        }
        Text(
            (if (BANDS[key] != null) "옅은 색 띠 = 기준치 구간 (초록 좋음 · 노랑 보통 · 주황 주의 · 보라 위험) · " else "") +
                "그래프를 눌러 끌면 날짜별 값이 보여요" + (note?.let { " · $it" } ?: ""),
            color = Palette.Faint, fontSize = 10.sp,
        )
    }
}

@Composable
private fun LineChart(
    pts: List<ChartPoint>, bands: List<Band>?, lineColor: Color, modifier: Modifier, small: Boolean,
    touch: Int? = null, onTouch: (Int?) -> Unit = {},
) {
    val vals = pts.map { it.value }
    var lo = vals.min()
    var hi = vals.max()
    if (hi - lo < 1e-9) { hi += 1.0; lo -= 1.0 }
    val pad = (hi - lo) * 0.08
    lo -= pad; hi += pad
    val n = pts.size

    val m = if (small) modifier else modifier.pointerInput(n) {
        fun idx(x: Float) = ((x / size.width) * (n - 1)).toInt().coerceIn(0, n - 1)
        detectTapGestures { onTouch(idx(it.x)) }
    }.pointerInput(n, "drag") {
        fun idx(x: Float) = ((x / size.width) * (n - 1)).toInt().coerceIn(0, n - 1)
        detectHorizontalDragGestures(onDragStart = { onTouch(idx(it.x)) }) { change, _ -> onTouch(idx(change.position.x)) }
    }

    Canvas(m) {
        val w = size.width
        val h = size.height
        fun y(v: Double) = (h - ((v - lo) / (hi - lo) * h)).toFloat()
        fun x(i: Int) = if (n == 1) w / 2 else w * i / (n - 1)

        // 기준치 띠
        bands?.forEach { b ->
            val top = y(minOf(b.to, hi)).coerceIn(0f, h)
            val bot = y(maxOf(b.from, lo)).coerceIn(0f, h)
            if (bot - top > 0.5f) drawRect(b.color.copy(alpha = 0.13f), Offset(0f, top), Size(w, bot - top))
        }
        // 가로 보조선 (큰 그래프만)
        if (!small) {
            for (k in 1..3) {
                val yy = h * k / 4
                drawLine(Color(0xFF2A2A2A), Offset(0f, yy), Offset(w, yy), 1f)
            }
        }
        // 선
        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(i), y(p.value)) else path.lineTo(x(i), y(p.value)) }
        drawPath(path, lineColor, style = Stroke(width = if (small) 3f else 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // 시작값 점선
        if (!small) {
            drawLine(Palette.Sub.copy(alpha = 0.5f), Offset(0f, y(pts.first().value)), Offset(w, y(pts.first().value)), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        // 손가락 위치
        if (touch != null) {
            val tx = x(touch)
            drawLine(Color.White.copy(alpha = 0.6f), Offset(tx, 0f), Offset(tx, h), 2f)
            drawCircle(lineColor, 7f, Offset(tx, y(pts[touch].value)))
            drawCircle(Color.White, 3f, Offset(tx, y(pts[touch].value)))
        } else {
            drawCircle(lineColor, if (small) 4f else 6f, Offset(x(n - 1), y(pts.last().value)))
        }
    }
}
