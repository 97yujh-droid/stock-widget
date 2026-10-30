package com.yujh.todaymarket.data

import org.json.JSONArray
import org.json.JSONObject

/** 서버 결과 파일(latest.json)의 내용. 서버 server/publish.py 와 짝을 이룬다. */
data class MarketData(
    val generatedAt: String?,
    val title: String,
    val marketScore: MarketScore,
    val summary: Summary,
    val stocks: List<Stock>,
    val indicators: List<Indicator>,
    val calendar: List<EconEvent> = emptyList(),
)

/** 경제 지표 일정 (CPI, PPI, 고용 등) */
data class EconEvent(
    val title: String,
    val country: String,
    val time: String?,
    val impact: String?,
    val forecast: String?,
    val previous: String?,
    val actual: String?,
)

data class ScoreItem(
    val key: String,
    val name: String,
    val weight: Int,
    val score: Int?,
    val points: Double?,
    val value: String?,
)

data class MarketScore(
    val score: Int?,
    val zone: String?,
    val icon: String?,
    val yesterday: Int?,
    val comment: String?,
    val items: List<ScoreItem>,
)

data class Summary(val lines: List<String>, val generatedAt: String?)

data class News(val title: String, val source: String?, val time: String?)

data class Stock(
    val name: String,
    val code: String,
    val market: String,
    val currency: String,
    val price: Double?,
    val change: Double?,
    val changePct: Double?,
    val asOf: String?,
    val issue: String?,
    val news: List<News>,
    val score: Int?,
    val zone: String?,
    val icon: String?,
    val scoreItems: List<ScoreItem>,
    val comment: String?,
    val error: String?,
)

data class Indicator(
    val key: String,
    val group: String,      // score = 점수 지표, monitor = 모니터링 지표
    val name: String,
    val value: Double?,
    val label: String?,
    val change: Double?,
    val changePct: Double?,
    val asOf: String?,
    val published: String?,
    val source: String?,
    val cycle: String?,
    val stale: Boolean,
    val needInput: Boolean,
)

// ---------- JSON 읽기 도우미 (값이 null 이어도 안전하게) ----------

private fun JSONObject.str(k: String): String? =
    if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

private fun JSONObject.dbl(k: String): Double? =
    if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

private fun JSONObject.int(k: String): Int? = dbl(k)?.let { Math.round(it).toInt() }

private fun JSONObject.bool(k: String): Boolean = has(k) && !isNull(k) && optBoolean(k)

private fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(f) }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }
}

private fun scoreItem(o: JSONObject) = ScoreItem(
    key = o.str("key") ?: "",
    name = o.str("name") ?: "",
    weight = o.int("weight") ?: 0,
    score = o.int("score"),
    points = o.dbl("points"),
    value = o.str("value"),
)

fun parseMarketData(text: String): MarketData {
    val j = JSONObject(text)
    val ms = j.optJSONObject("market_score") ?: JSONObject()
    val sm = j.optJSONObject("market_summary") ?: JSONObject()
    return MarketData(
        generatedAt = j.str("generated_at"),
        title = j.str("title") ?: "오늘의 시장",
        marketScore = MarketScore(
            score = ms.int("score"),
            zone = ms.str("zone"),
            icon = ms.str("icon"),
            yesterday = ms.int("yesterday"),
            comment = ms.str("comment"),
            items = ms.optJSONArray("items").mapObjects(::scoreItem),
        ),
        summary = Summary(sm.optJSONArray("lines").strings(), sm.str("generated_at")),
        stocks = j.optJSONArray("stocks").mapObjects { s ->
            Stock(
                name = s.str("name") ?: "",
                code = s.str("code") ?: "",
                market = s.str("market") ?: "KR",
                currency = s.str("currency") ?: "KRW",
                price = s.dbl("price"),
                change = s.dbl("change"),
                changePct = s.dbl("change_pct"),
                asOf = s.str("as_of"),
                issue = s.str("issue"),
                news = s.optJSONArray("news").mapObjects { n ->
                    News(n.str("title") ?: "", n.str("source"), n.str("time"))
                },
                score = s.int("score"),
                zone = s.str("zone"),
                icon = s.str("icon"),
                scoreItems = s.optJSONArray("score_items").mapObjects(::scoreItem),
                comment = s.str("comment"),
                error = s.str("error"),
            )
        },
        indicators = j.optJSONArray("indicators").mapObjects { o ->
            Indicator(
                key = o.str("key") ?: "",
                group = o.str("group") ?: "score",
                name = o.str("name") ?: "",
                value = o.dbl("value"),
                label = o.str("label"),
                change = o.dbl("change"),
                changePct = o.dbl("change_pct"),
                asOf = o.str("as_of"),
                published = o.str("published"),
                source = o.str("source"),
                cycle = o.str("cycle"),
                stale = o.bool("stale"),
                needInput = o.bool("need_input"),
            )
        },
        calendar = j.optJSONArray("calendar").mapObjects { e ->
            EconEvent(
                title = e.str("title") ?: "",
                country = e.str("country") ?: "",
                time = e.str("time"),
                impact = e.str("impact"),
                forecast = e.str("forecast"),
                previous = e.str("previous"),
                actual = e.str("actual"),
            )
        },
    )
}
