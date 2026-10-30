package com.yujh.todaymarket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.yujh.todaymarket.data.SearchItem
import com.yujh.todaymarket.data.WatchlistApi
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yujh.todaymarket.data.AppSettings
import com.yujh.todaymarket.data.Holding
import com.yujh.todaymarket.data.MarketData
import com.yujh.todaymarket.data.Stock
import com.yujh.todaymarket.data.arrange
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    data: MarketData?,
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val stocks = data?.let { settings.arrange(it.stocks, includeHidden = true) } ?: emptyList()
    val byCode = stocks.associateBy { it.code }
    val latestSettings by rememberUpdatedState(settings)
    val latestOnChange by rememberUpdatedState(onChange)
    val haptic = LocalHapticFeedback.current
    var order by remember(stocks.map { it.code }) { mutableStateOf(stocks.map { it.code }) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val a = order.indexOf(from.key)
        val b = order.indexOf(to.key)
        if (a >= 0 && b >= 0) order = order.toMutableList().apply { add(b, removeAt(a)) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Palette.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ 설정", color = Palette.Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp, top = 4.dp, bottom = 4.dp))
            }
        }

        item {
            Group("보기 설정") {
                Text("글씨 크기  ${(settings.fontScale * 100).roundToInt()}%", color = Palette.Fg, fontSize = 14.sp)
                Slider(
                    value = settings.fontScale, onValueChange = { onChange(settings.copy(fontScale = (it * 20).roundToInt() / 20f)) },
                    valueRange = 0.8f..1.4f, steps = 11,
                    colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent),
                )
                Text("위젯 구성", color = Palette.Fg, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("한 화면에 모두", !settings.split) { onChange(settings.copy(split = false)) }
                    Choice("종목 / 지표 나누기", settings.split) { onChange(settings.copy(split = true)) }
                }
                Text("나누기를 고르면 위젯 위쪽 버튼으로 종목과 지표를 번갈아 볼 수 있어요. 위젯 크기는 홈 화면에서 위젯을 길게 눌러 조절해요.",
                    color = Palette.Sub, fontSize = 12.sp)
                Toggle("시황 요약 보이기", settings.showSummary) { onChange(settings.copy(showSummary = it)) }
                Toggle("시장 점수 보이기", settings.showMarketScore) { onChange(settings.copy(showMarketScore = it)) }
                Toggle("종목 점수 보이기", settings.showStockScore) { onChange(settings.copy(showStockScore = it)) }
                Toggle("내 수익률 보이기", settings.showReturns) { onChange(settings.copy(showReturns = it)) }
                Text("켜면 아래 종목마다 평단·수량 입력칸이 나와요. 입력한 값은 이 휴대폰에만 저장되고 서버로 보내지 않아요.",
                    color = Palette.Sub, fontSize = 12.sp)
            }
        }

        item { MonitorPicker(data, settings, onChange) }
        item { WatchlistEditor(stocks, settings, onChange) }
        item { MarginDebtEditor(settings) }
        item {
            Column {
                Text("관심 종목 (순서·숨기기·삭제)", color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("종목을 꾹 누른 채 위아래로 끌면 순서가 바뀌어요. 숨기기는 이 휴대폰에서만 안 보이게, 삭제는 서버 목록에서 빼요.",
                    color = Palette.Sub, fontSize = 12.sp)
            }
        }
        if (stocks.isEmpty()) item { Text("데이터를 받은 뒤에 종목이 나와요.", color = Palette.Sub, fontSize = 13.sp) }
        items(order, key = { it }) { code ->
            val st = byCode[code] ?: return@items
            ReorderableItem(reorderState, key = code) { isDragging ->
                Box(
                    Modifier.longPressDraggableHandle(
                        onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragStopped = { latestOnChange(latestSettings.copy(order = order)) },
                    ),
                ) {
                    StockSettingRow(st, settings, isDragging, onChange)
                }
            }
        }

        item {
            Group("데이터") {
                Text("새 데이터: 서버가 30분마다 만들고, 위젯은 25분이 지나면 새로 받아요.", color = Palette.Sub, fontSize = 12.sp)
                Text("시황 요약: 하루 5번 (06·08·12·15:40·21시)", color = Palette.Sub, fontSize = 12.sp)
                Text("상승 색: 빨강 (한국식) · 하락 색: 파랑", color = Palette.Sub, fontSize = 12.sp)
                Text("앱 버전: ${com.yujh.todaymarket.BuildConfig.VERSION_NAME}", color = Palette.Sub, fontSize = 12.sp)
            }
        }
        item { Spacer(Modifier.padding(bottom = 30.dp)) }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Color.Black),
        )
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp,
        color = if (selected) Color.Black else Palette.Sub,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) Palette.Fg else Palette.Box)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun StockSettingRow(s: Stock, settings: AppSettings, dragging: Boolean, onChange: (AppSettings) -> Unit) {
    val hidden = s.code in settings.hidden
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (dragging) Color(0xFF2A2F25) else Palette.Card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("☰  ", color = Palette.Faint, fontSize = 16.sp)
            Text(s.name, color = if (hidden) Palette.Stale else Palette.Fg, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SmallButton(if (hidden) "보이기" else "숨기기") {
                onChange(settings.copy(hidden = if (hidden) settings.hidden - s.code else settings.hidden + s.code))
            }
            if (settings.githubToken.isNotBlank()) {
                SmallButton(if (confirm) "정말 삭제?" else "삭제") {
                    if (!confirm) { confirm = true; return@SmallButton }
                    confirm = false
                    status = "삭제하는 중…"
                    scope.launch {
                        status = try {
                            WatchlistApi.remove(settings.githubToken, s.code, s.name)
                            onChange(settings.copy(removed = settings.removed + s.code))
                            null
                        } catch (e: Exception) {
                            "삭제하지 못했어요: ${e.message}"
                        }
                    }
                }
            }
        }
        status?.let { Text(it, color = Palette.Sub, fontSize = 12.sp) }
        if (settings.showReturns) {
            val h = settings.holdings[s.code] ?: Holding(null, null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("평단 (${if (s.currency == "KRW") "원" else "$"})", h.avg, Modifier.weight(1f)) {
                    onChange(settings.copy(holdings = settings.holdings + (s.code to h.copy(avg = it))))
                }
                NumberField("수량 (주)", h.qty, Modifier.weight(1f)) {
                    onChange(settings.copy(holdings = settings.holdings + (s.code to h.copy(qty = it))))
                }
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Text(text, color = Palette.Sub, fontSize = 12.sp,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Box)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun NumberField(label: String, value: Double?, modifier: Modifier, onValue: (Double?) -> Unit) {
    var text by remember(value == null) {
        mutableStateOf(value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "")
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            val clean = t.filter { it.isDigit() || it == '.' }
            text = clean
            onValue(clean.toDoubleOrNull())
        },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}


// ---------------- 관심 종목 추가 (GitHub 연결) ----------------

@Composable
private fun WatchlistEditor(stocks: List<Stock>, settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val scope = rememberCoroutineScope()
    var tokenInput by remember { mutableStateOf("") }
    var editingToken by remember { mutableStateOf(settings.githubToken.isBlank()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val have = stocks.map { it.code }.toSet()

    Group("관심 종목 추가") {
        if (editingToken) {
            Text("종목을 추가·삭제하려면 GitHub 열쇠를 한 번 넣어 주세요. 열쇠는 이 휴대폰에만 저장돼요.",
                color = Palette.Sub, fontSize = 12.sp)
            OutlinedTextField(
                value = tokenInput, onValueChange = { tokenInput = it.trim() },
                label = { Text("GitHub 열쇠 (github_pat_로 시작)", fontSize = 12.sp) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            SmallButton(if (busy) "확인 중…" else "연결하기") {
                if (busy || tokenInput.isBlank()) return@SmallButton
                busy = true
                scope.launch {
                    val err = WatchlistApi.verify(tokenInput)
                    busy = false
                    if (err == null) {
                        onChange(settings.copy(githubToken = tokenInput))
                        editingToken = false
                        tokenInput = ""
                        status = "연결됐어요 ✓"
                    } else {
                        status = err
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GitHub 연결됨 ✓", color = Palette.Accent, fontSize = 13.sp, modifier = Modifier.weight(1f))
                SmallButton("열쇠 바꾸기") { editingToken = true }
                SmallButton("연결 끊기") { onChange(settings.copy(githubToken = "")); editingToken = true }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("종목 이름이나 코드 (예: 엔비디아, 005930)", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                SmallButton("검색") {
                    if (query.isBlank()) return@SmallButton
                    status = "찾는 중…"
                    scope.launch {
                        try {
                            results = WatchlistApi.search(query)
                            status = if (results.isEmpty()) "찾는 종목이 없어요. 한국·미국 종목만 돼요." else null
                        } catch (e: Exception) {
                            status = "검색하지 못했어요: ${e.message}"
                        }
                    }
                }
            }
            results.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = Palette.Fg, fontSize = 14.sp)
                        Text("${r.code} · ${r.exchange}", color = Palette.Sub, fontSize = 11.sp)
                    }
                    if (r.code in have && r.code !in settings.removed) {
                        Text("추가됨", color = Palette.Faint, fontSize = 12.sp)
                    } else {
                        SmallButton("추가") {
                            status = "${r.name} 추가하는 중…"
                            scope.launch {
                                status = try {
                                    WatchlistApi.add(settings.githubToken, r)
                                    onChange(settings.copy(removed = settings.removed - r.code))
                                    results = emptyList()
                                    query = ""
                                    "${r.name}을(를) 추가했어요. 서버가 바로 새로 만들어서 1~2분 뒤 ↻ 하면 나와요."
                                } catch (e: Exception) {
                                    "추가하지 못했어요: ${e.message}"
                                }
                            }
                        }
                    }
                }
            }
        }
        status?.let { Text(it, color = Palette.Sub, fontSize = 12.sp) }
    }
}


// ---------------- Margin Debt 직접 입력 ----------------

private fun shiftMonth(ym: String, months: Int): String {
    val y = ym.take(4).toInt()
    val m = ym.takeLast(2).toInt()
    val t = y * 12 + (m - 1) + months
    return String.format("%04d-%02d", t / 12, t % 12 + 1)
}

private fun parseAmount(s: String): Long? = s.replace(",", "").replace(" ", "").toLongOrNull()?.takeIf { it > 0 }

@Composable
private fun MarginDebtEditor(settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<Map<String, Long>?>(null) }
    var month by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var agoAmount by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val token = settings.githubToken

    LaunchedEffect(token) {
        if (token.isBlank()) return@LaunchedEffect
        try {
            val h = WatchlistApi.marginHistory(token)
            history = h
            if (month.isBlank()) month = h.keys.maxOrNull()?.let { shiftMonth(it, 1) } ?: ""
        } catch (e: Exception) {
            status = "불러오지 못했어요: ${e.message}"
        }
    }

    Group("Margin Debt 입력 (한 달에 한 번)") {
        Text("FINRA 누리집(finra.org → Margin Statistics) 표의 맨 위 줄 \"Debit Balances\" 숫자를 그대로 넣어 주세요. 단위는 백만 달러예요. 보통 매달 셋째 주에 지난달 숫자가 나와요.",
            color = Palette.Sub, fontSize = 12.sp)
        if (token.isBlank()) {
            Text("먼저 위 \"관심 종목 추가\"에서 GitHub 열쇠를 연결해 주세요.", color = Palette.Faint, fontSize = 12.sp)
            return@Group
        }
        val h = history
        if (h == null) {
            Text("저장된 숫자를 불러오는 중…", color = Palette.Faint, fontSize = 12.sp)
        } else {
            h.entries.sortedByDescending { it.key }.take(3).forEach { (k, v) ->
                Text("$k   ${String.format("%,d", v)}", color = Palette.Fg.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            OutlinedTextField(
                value = month, onValueChange = { month = it.trim() },
                label = { Text("몇 월 숫자인지 (예: 2026-10)", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = amount, onValueChange = { amount = it },
                label = { Text("Debit Balances (예: 1,480,054)", fontSize = 12.sp) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            val validMonth = Regex("""\d{4}-(0[1-9]|1[0-2])""").matches(month)
            val agoMonth = if (validMonth) shiftMonth(month, -12) else null
            val agoKnown = agoMonth?.let { h[it] }
            if (agoMonth != null && agoKnown == null) {
                OutlinedTextField(
                    value = agoAmount, onValueChange = { agoAmount = it },
                    label = { Text("1년 전($agoMonth) 숫자도 필요해요", fontSize = 12.sp) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val now = parseAmount(amount)
            val ago = agoKnown ?: parseAmount(agoAmount)
            if (now != null && ago != null) {
                Text("1년 전($agoMonth)보다 ${String.format("%+.1f", (now.toDouble() / ago - 1) * 100)}%",
                    color = Palette.Accent, fontSize = 12.sp)
            }
            SmallButton(if (busy) "저장 중…" else "저장하기") {
                if (busy) return@SmallButton
                if (!validMonth) { status = "월은 2026-10 처럼 써 주세요."; return@SmallButton }
                if (now == null) { status = "숫자를 확인해 주세요."; return@SmallButton }
                val values = mutableMapOf(month to now)
                if (agoKnown == null) {
                    val a = parseAmount(agoAmount) ?: run { status = "1년 전($agoMonth) 숫자도 넣어 주세요."; return@SmallButton }
                    values[agoMonth!!] = a
                }
                busy = true
                status = "저장하는 중…"
                scope.launch {
                    status = try {
                        WatchlistApi.saveMargin(token, values)
                        history = h + values
                        amount = ""; agoAmount = ""
                        month = shiftMonth(month, 1)
                        "저장했어요 ✓ 서버가 바로 다시 계산해서 2~3분 뒤 ↻ 하면 나와요."
                    } catch (e: Exception) {
                        "저장하지 못했어요: ${e.message}"
                    }
                    busy = false
                }
            }
        }
        status?.let { Text(it, color = Palette.Sub, fontSize = 12.sp) }
    }
}

// ---------------- 위젯에 보일 모니터링 지표 ----------------

@Composable
private fun MonitorPicker(data: MarketData?, settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val mons = data?.indicators?.filter { it.group == "monitor" } ?: emptyList()
    Group("위젯에 보일 모니터링 지표") {
        Text("점수 지표는 항상 보여요. 모니터링 지표는 고른 것만 위젯에 나와요. 앱 화면에는 모두 나와요.",
            color = Palette.Sub, fontSize = 12.sp)
        if (mons.isEmpty()) Text("데이터를 받은 뒤에 고를 수 있어요.", color = Palette.Sub, fontSize = 12.sp)
        mons.forEach { m ->
            Toggle(m.name, m.key in settings.widgetMonitors) { on ->
                onChange(settings.copy(widgetMonitors = if (on) settings.widgetMonitors + m.key else settings.widgetMonitors - m.key))
            }
        }
    }
}
