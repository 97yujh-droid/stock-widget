package com.yujh.todaymarket.data

import android.content.Context
import org.json.JSONObject

/** 보유 정보. 휴대폰에만 저장하고 서버로 보내지 않는다. */
data class Holding(val avg: Double?, val qty: Double?)

data class AppSettings(
    val fontScale: Float = 1f,          // 글씨 크기 0.8 ~ 1.4
    val split: Boolean = false,         // 위젯: 종목/지표 나누어 보기
    val widgetTab: String = "stocks",   // 나누어 볼 때 지금 보이는 쪽
    val showSummary: Boolean = true,
    val showMarketScore: Boolean = true,
    val showStockScore: Boolean = true,
    val showReturns: Boolean = false,   // 내 수익률 (기본 꺼짐)
    val order: List<String> = emptyList(),   // 종목 코드 순서
    val hidden: Set<String> = emptySet(),    // 숨긴 종목 코드
    val holdings: Map<String, Holding> = emptyMap(),
    val githubToken: String = "",            // 관심 종목 추가·삭제용 (휴대폰에만 저장)
    val removed: Set<String> = emptySet(),   // 삭제했지만 아직 서버 결과에 남아 있는 종목
)

object SettingsStore {
    private const val PREFS = "settings"

    fun load(context: Context): AppSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val holdings = mutableMapOf<String, Holding>()
        runCatching {
            val j = JSONObject(p.getString("holdings", "{}") ?: "{}")
            j.keys().forEach { k ->
                val o = j.getJSONObject(k)
                holdings[k] = Holding(
                    o.optDouble("avg").takeIf { !it.isNaN() },
                    o.optDouble("qty").takeIf { !it.isNaN() },
                )
            }
        }
        return AppSettings(
            fontScale = p.getFloat("fontScale", 1f),
            split = p.getBoolean("split", false),
            widgetTab = p.getString("widgetTab", "stocks") ?: "stocks",
            showSummary = p.getBoolean("showSummary", true),
            showMarketScore = p.getBoolean("showMarketScore", true),
            showStockScore = p.getBoolean("showStockScore", true),
            showReturns = p.getBoolean("showReturns", false),
            order = (p.getString("order", "") ?: "").split(",").filter { it.isNotBlank() },
            hidden = (p.getString("hidden", "") ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            holdings = holdings,
            githubToken = p.getString("githubToken", "") ?: "",
            removed = (p.getString("removed", "") ?: "").split(",").filter { it.isNotBlank() }.toSet(),
        )
    }

    fun save(context: Context, s: AppSettings) {
        val h = JSONObject()
        s.holdings.forEach { (k, v) ->
            if (v.avg != null || v.qty != null) {
                h.put(k, JSONObject().apply {
                    v.avg?.let { put("avg", it) }
                    v.qty?.let { put("qty", it) }
                })
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("fontScale", s.fontScale)
            .putBoolean("split", s.split)
            .putString("widgetTab", s.widgetTab)
            .putBoolean("showSummary", s.showSummary)
            .putBoolean("showMarketScore", s.showMarketScore)
            .putBoolean("showStockScore", s.showStockScore)
            .putBoolean("showReturns", s.showReturns)
            .putString("order", s.order.joinToString(","))
            .putString("hidden", s.hidden.joinToString(","))
            .putString("holdings", h.toString())
            .putString("githubToken", s.githubToken)
            .putString("removed", s.removed.joinToString(","))
            .apply()
    }
}

/** 설정한 순서대로 정렬하고 숨긴 종목은 뺀다. 새로 생긴 종목은 맨 뒤에. */
fun AppSettings.arrange(stocks: List<Stock>, includeHidden: Boolean = false): List<Stock> {
    val idx = order.withIndex().associate { it.value to it.index }
    return stocks.withIndex()
        .sortedBy { (i, s) -> idx[s.code] ?: (10000 + i) }
        .map { it.value }
        .filter { it.code !in removed && (includeHidden || it.code !in hidden) }
}

/** 내 수익률(%)과 손익 금액. 수익률 보기가 꺼져 있거나 값이 없으면 null. */
fun AppSettings.returnOf(s: Stock): Pair<Double, Double>? {
    if (!showReturns) return null
    val h = holdings[s.code] ?: return null
    val avg = h.avg ?: return null
    val price = s.price ?: return null
    if (avg <= 0) return null
    val pct = (price / avg - 1) * 100
    val amount = (price - avg) * (h.qty ?: 0.0)
    return pct to amount
}
