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
    private const val API = "https://api.github.com/repos/97yujh-droid/stock-widget/contents/server/config.json"

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

    /** 설정 파일을 읽어 종목 목록을 바꾸고 다시 저장. 동시에 바뀌어 충돌하면 한 번 더 시도. */
    private suspend fun edit(token: String, message: String, change: (JSONArray) -> JSONArray) =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            repeat(2) {
                try {
                    val file = JSONObject(request("$API?ref=main", "GET", token, null))
                    val sha = file.getString("sha")
                    val raw = String(Base64.decode(file.getString("content"), Base64.DEFAULT), Charsets.UTF_8)
                    val config = JSONObject(raw)
                    config.put("stocks", change(config.getJSONArray("stocks")))
                    val newText = config.toString(2).replace("\\/", "/") + "\n"
                    val body = JSONObject().apply {
                        put("message", message)
                        put("content", Base64.encodeToString(newText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                        put("sha", sha)
                        put("branch", "main")
                    }
                    request(API, "PUT", token, body.toString())
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
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TodayMarketApp")
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
