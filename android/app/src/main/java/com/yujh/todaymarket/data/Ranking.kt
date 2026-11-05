package com.yujh.todaymarket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 순위 한 줄 */
data class RankItem(
    val name: String,
    val code: String,          // 미국: 티커(NVDA), 국내: 종목코드(005930)
    val reuters: String,       // 미국 네이버 코드(NVDA.O)
    val exchange: String,      // 나스닥 증권거래소 / 코스피 …
    val market: String,        // US / KR
    val price: Double?,
    val change: Double?,
    val changePct: Double?,
    val tradingValue: String,  // 보기 좋게 바꾼 거래대금
    val volume: Double?,
    val marketCap: String,     // 보기 좋게 바꾼 시가총액
    val marketCapRaw: Double?, // 미국: 천 달러, 국내: 억원
)

data class RankPage(val items: List<RankItem>, val total: Int)

/**
 * 미국·국내 종목 순위. 네이버 증권에서 휴대폰이 직접 받아온다 (실시간).
 * 미국: 전체/뉴욕/나스닥/아멕스 × 시가총액/거래대금/상승/하락
 * 국내: 코스피/코스닥 × 시가총액/거래대금/거래량/인기/상승/하락
 */
object RankingApi {
    enum class Sort(val label: String, val us: String?, val kr: String) {
        CAP("시가총액", "marketValue", "marketValue"),
        VALUE("거래대금", "priceTop", "priceTop"),
        VOLUME("거래량", null, "quantTop"),
        POPULAR("인기", null, "searchTop"),
        UP("상승", "up", "up"),
        DOWN("하락", "down", "down"),
    }

    val US_MARKETS = listOf("전체" to "nation/USA", "뉴욕" to "exchange/NYSE", "나스닥" to "exchange/NASDAQ", "아멕스" to "exchange/AMEX")
    val KR_MARKETS = listOf("코스피" to "KOSPI", "코스닥" to "KOSDAQ")

    private fun num(s: String?): Double? =
        s?.replace(",", "")?.let { Regex("""[-+]?\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) TodayMarketApp")
            setRequestProperty("Referer", "https://m.stock.naver.com/")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) error("서버 응답 ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 국내 거래대금(백만원) → "5조 123억" */
    private fun krwFromMillion(v: Double?): String {
        if (v == null) return "-"
        val eok = v / 100
        return when {
            eok >= 10000 -> String.format("%.1f조", eok / 10000)
            eok >= 1 -> String.format("%,.0f억", eok)
            else -> String.format("%,.0f만", v * 100)
        }
    }

    /** 국내 시가총액(억원) → "1,178조" */
    private fun krwCap(eok: Double?): String = when {
        eok == null -> "-"
        eok >= 10000 -> String.format("%,.1f조", eok / 10000)
        else -> String.format("%,.0f억", eok)
    }

    /** 미국 시가총액(천 달러) → "5.00조$" / "1,831억$" */
    private fun usdCap(k: Double?): String = when {
        k == null -> "-"
        k >= 1e9 -> String.format("%.2f조$", k / 1e9)
        k >= 1e5 -> String.format("%,.0f억$", k / 1e5)
        else -> String.format("%,.0f만$", k / 10)
    }

    suspend fun fetch(us: Boolean, marketPath: String, sort: Sort, page: Int, size: Int = 20): RankPage =
        withContext(Dispatchers.IO) {
            val url = if (us) {
                "https://api.stock.naver.com/stock/$marketPath/${sort.us}?page=$page&pageSize=$size"
            } else {
                "https://m.stock.naver.com/api/stocks/${sort.kr}/$marketPath?page=$page&pageSize=$size"
            }
            val j = JSONObject(get(url))
            val arr = j.optJSONArray("stocks")
            val items = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val x = arr!!.optJSONObject(i) ?: return@mapNotNull null
                val capRaw = num(x.optString("marketValue"))
                if (us) {
                    RankItem(
                        name = x.optString("stockName"), code = x.optString("symbolCode"),
                        reuters = x.optString("reutersCode"),
                        exchange = x.optJSONObject("stockExchangeType")?.optString("nameKor") ?: "",
                        market = "US",
                        price = num(x.optString("closePrice")), change = num(x.optString("compareToPreviousClosePrice")),
                        changePct = num(x.optString("fluctuationsRatio")),
                        tradingValue = x.optString("accumulatedTradingValue").replace(" USD", "$").ifBlank { "-" },
                        volume = num(x.optString("accumulatedTradingVolume")),
                        marketCap = usdCap(capRaw), marketCapRaw = capRaw,
                    )
                } else {
                    RankItem(
                        name = x.optString("stockName"), code = x.optString("itemCode"),
                        reuters = x.optString("reutersCode"),
                        exchange = if (marketPath == "KOSPI") "코스피" else "코스닥",
                        market = "KR",
                        price = num(x.optString("closePrice")), change = num(x.optString("compareToPreviousClosePrice")),
                        changePct = num(x.optString("fluctuationsRatio")),
                        tradingValue = krwFromMillion(num(x.optString("accumulatedTradingValue"))),
                        volume = num(x.optString("accumulatedTradingVolume")),
                        marketCap = krwCap(capRaw), marketCapRaw = capRaw,
                    )
                }
            }
            RankPage(items, j.optInt("totalCount", items.size))
        }

    /** 초소형 빼기: 미국 1달러 미만·시가총액 없음, 국내 시가총액 500억 미만 */
    fun isTiny(r: RankItem): Boolean = if (r.market == "US") {
        (r.price ?: 0.0) < 1.0 || r.marketCapRaw == null
    } else {
        (r.marketCapRaw ?: 0.0) < 500
    }
}
