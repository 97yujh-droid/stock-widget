package com.yujh.todaymarket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yujh.todaymarket.data.AppSettings
import com.yujh.todaymarket.data.MarketData
import com.yujh.todaymarket.data.RankItem
import com.yujh.todaymarket.data.RankingApi
import com.yujh.todaymarket.data.RankingApi.Sort
import com.yujh.todaymarket.data.SearchItem
import com.yujh.todaymarket.data.WatchlistApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val US_SORTS = listOf(Sort.CAP, Sort.VALUE, Sort.UP, Sort.DOWN)
private val KR_SORTS = listOf(Sort.CAP, Sort.VALUE, Sort.VOLUME, Sort.POPULAR, Sort.UP, Sort.DOWN)
private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 미국·국내 종목 순위 (실시간, 1분마다 자동 새로고침) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingScreen(data: MarketData?, settings: AppSettings, onChange: (AppSettings) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var us by remember { mutableStateOf(true) }
    var usMarket by remember { mutableStateOf(0) }
    var krMarket by remember { mutableStateOf(0) }
    var sort by remember { mutableStateOf(Sort.VALUE) }
    var hideTiny by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<RankItem>>(emptyList()) }
    var page by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf<LocalTime?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()

    val sorts = if (us) US_SORTS else KR_SORTS
    if (sort !in sorts) sort = Sort.VALUE
    val marketPath = if (us) RankingApi.US_MARKETS[usMarket].second else RankingApi.KR_MARKETS[krMarket].second
    val have = (data?.stocks?.map { it.code }?.toSet() ?: emptySet()) - settings.removed + added

    // pages 쪽 수만큼 처음부터 다시 받기 (새로고침·조건 변경)
    suspend fun load(pages: Int) {
        loading = true
        try {
            val out = mutableListOf<RankItem>()
            var t = 0
            for (p in 1..maxOf(1, pages)) {
                val r = RankingApi.fetch(us, marketPath, sort, p)
                out += r.items
                t = r.total
                if (r.items.isEmpty()) break
            }
            items = out
            total = t
            page = maxOf(1, pages)
            updatedAt = LocalTime.now()
            error = null
        } catch (e: Exception) {
            error = "순위를 받지 못했어요 (${e.message})"
        } finally {
            loading = false
        }
    }

    suspend fun more() {
        if (loading || items.size >= total) return
        loading = true
        try {
            val r = RankingApi.fetch(us, marketPath, sort, page + 1)
            items = items + r.items.filter { n -> items.none { it.code == n.code } }
            page += 1
        } catch (_: Exception) {
        } finally {
            loading = false
        }
    }

    val shown = if (hideTiny) items.filterNot { RankingApi.isTiny(it) } else items

    // 조건이 바뀌면 처음부터. 화면을 켜두면 1분마다 새로고침
    LaunchedEffect(us, marketPath, sort) {
        items = emptyList()
        listState.scrollToItem(0)
        load(1)
        while (true) {
            delay(60_000)
            load(page)
        }
    }
    // 초소형을 빼서 줄이 너무 적으면 다음 쪽을 더 받는다
    LaunchedEffect(shown.size, items.size, hideTiny) {
        if (items.isNotEmpty() && shown.size < 20 && items.size < total && page < 8) more()
    }
    // 끝까지 내리면 더 받기
    val nearEnd by remember { derivedStateOf {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        last >= listState.layoutInfo.totalItemsCount - 4
    } }
    LaunchedEffect(nearEnd) { if (nearEnd && items.isNotEmpty()) more() }

    Column(Modifier.fillMaxSize().background(Palette.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // 제목줄
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 종목 순위", color = Palette.Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp, top = 4.dp, bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            Text(if (loading) "받는 중…" else updatedAt?.let { "${it.format(hms)} 기준" } ?: "",
                color = Palette.Sub, fontSize = 12.sp)
            Text("  ↻", color = Palette.Fg, fontSize = 22.sp,
                modifier = Modifier.clickable { scope.launch { load(page) } }.padding(4.dp))
        }
        // 미국 / 국내
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf(true to "🇺🇸 미국 주식", false to "🇰🇷 국내 주식").forEach { (v, label) ->
                val on = us == v
                Text(label, color = if (on) Palette.Fg else Palette.Faint, fontSize = 17.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(end = 18.dp).clickable { us = v })
            }
        }
        // 거래소
        ChipRow((if (us) RankingApi.US_MARKETS else RankingApi.KR_MARKETS).map { it.first },
            if (us) usMarket else krMarket) { if (us) usMarket = it else krMarket = it }
        // 순위 종류
        ChipRow(sorts.map { it.label }, sorts.indexOf(sort), dark = true) { sort = sorts[it] }
        // 초소형 빼기
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (us) "1달러 미만·초소형 빼기" else "초소형(시가총액 500억 미만) 빼기",
                color = Palette.Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(hideTiny, { hideTiny = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Color.Black))
        }
        error?.let { Text(it, color = Color(0xFFE0883C), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        note?.let { Text(it, color = Palette.Sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        // 머리줄
        val third = when (sort) { Sort.CAP -> "시가총액"; Sort.VOLUME -> "거래량"; else -> "거래대금" }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("종목", color = Palette.Faint, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(start = 52.dp))
            Text("현재가 · 전일대비", color = Palette.Faint, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(120.dp))
            Text(third, color = Palette.Faint, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; load(page); refreshing = false } },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(shown, key = { _, r -> r.code }) { i, r ->
                    RankRow(i + 1, r, third, r.code in have) {
                        if (r.code in have) return@RankRow
                        if (settings.githubToken.isBlank()) {
                            note = "관심 종목에 넣으려면 설정에서 GitHub 열쇠를 먼저 연결해 주세요."
                            return@RankRow
                        }
                        note = "${r.name} 추가하는 중…"
                        scope.launch {
                            note = try {
                                WatchlistApi.add(settings.githubToken,
                                    SearchItem(r.name, r.code, r.market, r.reuters, r.exchange))
                                added = added + r.code
                                onChange(settings.copy(removed = settings.removed - r.code))
                                "★ ${r.name}을(를) 관심 종목에 넣었어요. 1~2분 뒤 시장 화면에 나와요."
                            } catch (e: Exception) {
                                "추가하지 못했어요: ${e.message}"
                            }
                        }
                    }
                }
                item {
                    Text(
                        if (items.size < total) "아래로 내리면 더 보여요" else "끝이에요",
                        color = Palette.Faint, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipRow(labels: List<String>, selected: Int, dark: Boolean = false, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Text(
                l, fontSize = 13.sp,
                color = if (on) (if (dark) Palette.Fg else Color.Black) else Palette.Sub,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (on) (if (dark) Color(0xFF3A3F45) else Palette.Accent) else Color(0xFF1C1C1C))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun RankRow(rank: Int, r: RankItem, third: String, starred: Boolean, onStar: () -> Unit) {
    val cur = if (r.market == "KR") "KRW" else "USD"
    val c = changeColor(r.changePct)
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (starred) "★" else "☆", color = if (starred) Color(0xFFF2C94C) else Palette.Faint, fontSize = 18.sp,
                modifier = Modifier.clickable(onClick = onStar).padding(end = 6.dp))
            Text("$rank", color = Palette.Sub, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.width(26.dp))
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(r.name, color = Palette.Fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(r.code, color = Palette.Faint, fontSize = 11.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(120.dp)) {
                Text((if (cur == "USD") "$" else "") + fmtPrice(r.price, cur), color = Palette.Fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("${arrow(r.change)}${fmtChange(r.change?.let { abs(it) }, cur)} (${fmtPct(r.changePct)})",
                    color = c, fontSize = 11.sp, maxLines = 1)
            }
            Text(
                when (third) {
                    "시가총액" -> r.marketCap
                    "거래량" -> r.volume?.let { v -> if (v >= 1e8) String.format("%.1f억주", v / 1e8) else if (v >= 1e4) String.format("%,.0f만주", v / 1e4) else String.format("%,.0f주", v) } ?: "-"
                    else -> r.tradingValue
                },
                color = Palette.Sub, fontSize = 11.sp, textAlign = TextAlign.End, maxLines = 1,
                modifier = Modifier.width(70.dp),
            )
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Color(0xFF1C1C1C)))
    }
}
