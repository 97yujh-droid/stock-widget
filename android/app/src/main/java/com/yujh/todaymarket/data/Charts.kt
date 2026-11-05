package com.yujh.todaymarket.data

import org.json.JSONObject
import java.time.LocalDate

/** 그래프 점 하나: 날짜와 값 */
data class ChartPoint(val date: LocalDate, val value: Double)

/** 지표·종목별 최근 1년 숫자. 키 예) "vix", "stock:NVDA", "market_score" */
class Charts(val series: Map<String, List<ChartPoint>>)

fun parseCharts(text: String): Charts {
    val s = JSONObject(text).getJSONObject("series")
    val out = mutableMapOf<String, List<ChartPoint>>()
    for (k in s.keys()) {
        val arr = s.getJSONArray(k)
        val pts = ArrayList<ChartPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            val d = p.optString(0)
            if (d.length < 8) continue
            val v = p.optDouble(1)
            if (v.isNaN()) continue
            runCatching {
                pts.add(ChartPoint(LocalDate.of(d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt()), v))
            }
        }
        if (pts.isNotEmpty()) out[k] = pts.sortedBy { it.date }
    }
    return Charts(out)
}
