package com.yujh.todaymarket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * ↻ 를 누르면 가격만 휴대폰이 직접 바로 받아온다 (몇 초).
 * 서버와 같은 곳(CNBC·네이버·CoinGecko)에서 받는다. AI 열쇠는 필요 없다.
 * AI 요약·점수는 서버가 따로 새로 만든다.
 */
object LiveQuotes {
    /** 지표 키 → CNBC 기호 (서버 collect.py 와 같음) */
    private val IND = mapOf(
        "usdkrw" to "KRW=", "dxy" to ".DXY", "us10y" to "US10Y", "vix" to ".VIX", "move" to ".MOVE",
        "wti" to "@CL.1", "nq_fut" to "@ND.1", "sox" to ".SOX", "usdjpy" to "JPY=", "us2y" to "US2Y", "gold" to "@GC.1",
    )

    private fun num(s: String?): Double? =
        s?.replace(",", "")?.let { Regex("""[-+]?\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) TodayMarketApp")
            setRequestProperty("Accept", "*/*")
        }
        try {
            if (conn.responseCode != 200) error("응답 ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private data class Q(val price: Double?, val change: Double?, val pct: Double?)

    private fun cnbc(symbols: List<String>): Map<String, Q> {
        if (symbols.isEmpty()) return emptyMap()
        val url = "https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol" +
            "?symbols=${URLEncoder.encode(symbols.joinToString("|"), "UTF-8")}" +
            "&requestMethod=itv&noform=1&partnerId=2&fund=1&exthrs=1&output=json"
        val fq = JSONObject(get(url)).getJSONObject("FormattedQuoteResult")
        val arr = fq.optJSONArray("FormattedQuote") ?: return emptyMap()
        val out = mutableMapOf<String, Q>()
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            val p = num(x.optString("last")) ?: continue
            out[x.optString("symbol")] = Q(p, num(x.optString("change")), num(x.optString("change_pct")))
        }
        return out
    }

    private fun naverKr(code: String): Q {
        val j = JSONObject(get("https://m.stock.naver.com/api/stock/$code/basic"))
        return Q(num(j.optString("closePrice")), num(j.optString("compareToPreviousClosePrice")),
            num(j.optString("fluctuationsRatio")))
    }

    private fun crypto(): Map<String, Q> {
        val j = JSONObject(get("https://api.coingecko.com/api/v3/simple/price" +
            "?ids=bitcoin,ethereum&vs_currencies=usd&include_24hr_change=true"))
        return listOf("bitcoin", "ethereum").mapNotNull { k ->
            j.optJSONObject(k)?.let { o -> k to Q(o.optDouble("usd"), null, o.optDouble("usd_24h_change")) }
        }.toMap()
    }

    /** 받은 가격으로 바꾼 새 데이터. 하나도 못 받으면 예외. */
    suspend fun apply(data: MarketData): Pair<MarketData, Int> = withContext(Dispatchers.IO) {
        val now = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0).toString()
        val usSyms = data.stocks.filter { it.market == "US" }.map { it.code }
        coroutineScope {
            val cn = async { runCatching { cnbc(usSyms + IND.values) }.getOrDefault(emptyMap()) }
            val cr = async { runCatching { crypto() }.getOrDefault(emptyMap()) }
            val kr = data.stocks.filter { it.market == "KR" }.map { s ->
                async { s.code to runCatching { naverKr(s.code) }.getOrNull() }
            }
            val cnbcQ = cn.await()
            val cryptoQ = cr.await()
            val krQ = kr.awaitAll().toMap()
            var updated = 0

            val stocks = data.stocks.map { s ->
                val q = if (s.market == "KR") krQ[s.code] else cnbcQ[s.code]
                if (q?.price == null) s else {
                    updated++
                    s.copy(price = q.price, change = q.change ?: s.change, changePct = q.pct ?: s.changePct, asOf = now)
                }
            }
            val inds = data.indicators.map { ind ->
                val q = IND[ind.key]?.let { cnbcQ[it] } ?: cryptoQ[ind.key]
                if (q?.price == null || q.price.isNaN()) ind else {
                    updated++
                    ind.copy(value = q.price, change = q.change ?: ind.change,
                        changePct = q.pct?.takeIf { !it.isNaN() } ?: ind.changePct, asOf = now, stale = false)
                }
            }
            if (updated == 0) error("가격을 하나도 받지 못했어요")
            data.copy(stocks = stocks, indicators = inds) to updated
        }
    }
}
