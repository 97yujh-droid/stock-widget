package com.yujh.todaymarket.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 종목 검색 결과 (네이버 자동완성) */
data class SearchItem(
    val name: String,
    val code: String,
    val market: String,     // KR / US
    val reuters: String,    // 네이버 해외 종목 코드 (예: NVDA.O)
    val exchange: String,   // 코스피, 나스닥 증권거래소 …
)

/**
 * 관심 종목 추가·삭제.
 * 서버 설정 파일(server/config.json)을 GitHub 에서 직접 고친다.
 * 고치면 서버가 바로 한 번 돌아서 1~2분 뒤 새 종목의 가격·뉴스·점수가 나온다.
 * GitHub 열쇠는 이 휴대폰에만 저장된다.
 */
object WatchlistApi {
    private const val BASE = "https://api.github.com/repos/97yujh-droid/stock-widget/contents/"
    private const val API = BASE + "server/config.json"
    private const val MARGIN = "data/manual/margin_debt.json"

    // ---------------- 검색 ----------------

    suspend fun search(query: String): List<SearchItem> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val text = request("https://ac.stock.naver.com/ac?q=$q&target=stock", "GET", null, null)
        val items = JSONObject(text).optJSONArray("items") ?: JSONArray()
        (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val market = when (o.optString("nationCode")) {
                "KOR" -> "KR"
                "USA" -> "US"
                else -> return@mapNotNull null   // 한국·미국 종목만
            }
            SearchItem(
                name = o.optString("name"),
                code = o.optString("code"),
                market = market,
                reuters = o.optString("reutersCode"),
                exchange = o.optString("typeName"),
            )
        }.take(10)
    }

    // ---------------- GitHub ----------------

    /** 열쇠가 맞는지 확인. 문제 없으면 null, 있으면 안내 문구. */
    suspend fun verify(token: String): String? = withContext(Dispatchers.IO) {
        try {
            request("$API?ref=main", "GET", token, null)
            null
        } catch (e: Exception) {
            friendly(e)
        }
    }

    suspend fun add(token: String, item: SearchItem) = edit(token, "앱에서 관심 종목 추가: ${item.name}") { stocks ->
        for (i in 0 until stocks.length()) {
            if (stocks.getJSONObject(i).optString("code") == item.code) return@edit stocks // 이미 있음
        }
        stocks.put(JSONObject().apply {
            put("name", item.name)
            put("code", item.code)
            put("market", item.market)
            if (item.market == "US" && item.reuters.isNotBlank()) put("naver", item.reuters)
        })
        stocks
    }

    suspend fun remove(token: String, code: String, name: String) = edit(token, "앱에서 관심 종목 삭제: $name") { stocks ->
        val out = JSONArray()
        for (i in 0 until stocks.length()) {
            val o = stocks.getJSONObject(i)
            if (o.optString("code") != code) out.put(o)
        }
        if (out.length() == 0) error("종목이 하나는 있어야 해요")
        out
    }

    /** 설정 파일을 읽어 종목 목록을 바꾸고 다시 저장. */
    private suspend fun edit(token: String, message: String, change: (JSONArray) -> JSONArray) =
        editFile(token, "server/config.json", message) { config ->
            config.put("stocks", change(config.getJSONArray("stocks")))
        }

    // ---------------- Margin Debt 직접 입력 ----------------

    /** 저장된 월별 숫자 (예: "2026-09" → 1480054, 단위 백만 달러) */
    suspend fun marginHistory(token: String): Map<String, Long> = withContext(Dispatchers.IO) {
        try {
            val (json, _) = readFile(token, MARGIN)
            val h = json.optJSONObject("history") ?: JSONObject()
            h.keys().asSequence().associateWith { h.optLong(it) }.filterValues { it > 0 }.toSortedMap()
        } catch (e: Exception) {
            throw Exception(friendly(e))
        }
    }

    /** 한 달 또는 여러 달 숫자 저장 → 서버가 바로 다시 계산 */
    suspend fun saveMargin(token: String, values: Map<String, Long>) =
        editFile(token, MARGIN, "앱에서 Margin Debt 입력: ${values.keys.sorted().joinToString()}") { json ->
            val h = json.optJSONObject("history") ?: JSONObject()
            values.forEach { (k, v) -> h.put(k, v) }
            val sorted = JSONObject()
            h.keys().asSequence().sorted().forEach { sorted.put(it, h.get(it)) }
            json.put("history", sorted)
            json.remove("month"); json.remove("balance"); json.remove("balance_year_ago")
        }

    private fun readFile(token: String, path: String): Pair<JSONObject, String> {
        val file = JSONObject(request("$BASE$path?ref=main", "GET", token, null))
        val raw = String(Base64.decode(file.getString("content"), Base64.DEFAULT), Charsets.UTF_8)
        return JSONObject(raw) to file.getString("sha")
    }

    /** GitHub 의 JSON 파일을 읽어 고치고 다시 저장. 동시에 바뀌어 충돌하면 한 번 더 시도. */
    private suspend fun editFile(token: String, path: String, message: String, change: (JSONObject) -> Unit) =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            repeat(2) {
                try {
                    val (json, sha) = readFile(token, path)
                    change(json)
                    val newText = json.toString(2).replace("\\/", "/") + "\n"
                    val body = JSONObject().apply {
                        put("message", message)
                        put("content", Base64.encodeToString(newText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                        put("sha", sha)
                        put("branch", "main")
                    }
                    request(BASE + path, "PUT", token, body.toString())
                    return@withContext
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw Exception(friendly(lastError!!))
        }

    private fun friendly(e: Exception): String {
        val m = e.message ?: ""
        return when {
            "401" in m -> "열쇠가 맞지 않아요. 다시 붙여 넣어 주세요."
            "403" in m || "404" in m -> "열쇠에 권한이 없어요. stock-widget 저장소의 Contents 읽기·쓰기 권한을 확인해 주세요."
            else -> m.ifBlank { "알 수 없는 오류" }
        }
    }

    private fun request(url: String, method: String, token: String?, body: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            // GitHub 전용 머리글은 GitHub 에만 보낸다 (네이버에 보내면 404로 거절됨)
            val github = url.startsWith("https://api.github.com")
            setRequestProperty("Accept", if (github) "application/vnd.github+json" else "*/*")
            setRequestProperty("User-Agent", if (github) "TodayMarketApp" else "Mozilla/5.0 (Linux; Android 14) TodayMarketApp")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("서버 응답 $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
