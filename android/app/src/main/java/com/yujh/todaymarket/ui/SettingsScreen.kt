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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

    LazyColumn(
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

        item {
            Column {
                Text("관심 종목 (순서 바꾸기·숨기기)", color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("종목 추가와 삭제는 서버 설정에서 해요. Claude에게 말씀해 주세요.", color = Palette.Sub, fontSize = 12.sp)
            }
        }
        if (stocks.isEmpty()) item { Text("데이터를 받은 뒤에 종목이 나와요.", color = Palette.Sub, fontSize = 13.sp) }
        items(stocks, key = { it.code }) { s ->
            StockSettingRow(
                s = s, settings = settings, codes = stocks.map { it.code }, onChange = onChange,
            )
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
private fun StockSettingRow(s: Stock, settings: AppSettings, codes: List<String>, onChange: (AppSettings) -> Unit) {
    val hidden = s.code in settings.hidden
    fun move(delta: Int) {
        val list = codes.toMutableList()
        val i = list.indexOf(s.code)
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        list[i] = list[j].also { list[j] = list[i] }
        onChange(settings.copy(order = list))
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, color = if (hidden) Palette.Stale else Palette.Fg, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SmallButton("▲") { move(-1) }
            SmallButton("▼") { move(1) }
            SmallButton(if (hidden) "보이기" else "숨기기") {
                onChange(settings.copy(hidden = if (hidden) settings.hidden - s.code else settings.hidden + s.code))
            }
        }
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
