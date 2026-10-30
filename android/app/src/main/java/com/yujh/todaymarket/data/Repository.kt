package com.yujh.todaymarket.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 결과 파일을 인터넷에서 받아오고, 휴대폰에 마지막 값을 저장해 둔다.
 * 앱에는 AI 열쇠가 없다. 공개된 결과 파일만 읽는다.
 */
object Repository {
    const val DATA_URL = "https://97yujh-droid.github.io/stock-widget/latest.json"
    private const val CACHE_FILE = "latest.json"

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    /** 휴대폰에 저장된 마지막 결과(인터넷이 없어도 보여주기 위함). */
    fun loadCached(context: Context): MarketData? = try {
        val f = cacheFile(context)
        if (f.exists()) parseMarketData(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    /** 새 결과를 받아서 저장. 실패하면 예외. */
    suspend fun fetch(context: Context): MarketData = withContext(Dispatchers.IO) {
        // 주소 끝에 시각을 붙여 휴대폰·통신사 캐시를 피한다
        val url = URL("$DATA_URL?t=${System.currentTimeMillis() / 60000}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (conn.responseCode != 200) error("서버 응답 ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val data = parseMarketData(text) // 형식이 이상하면 여기서 실패 → 저장 안 함
            cacheFile(context).writeText(text)
            data
        } finally {
            conn.disconnect()
        }
    }
}
