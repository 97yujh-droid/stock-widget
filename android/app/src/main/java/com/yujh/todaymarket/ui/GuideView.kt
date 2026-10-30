package com.yujh.todaymarket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 기준치 한 구간: 범위(예: 15~20) + 판단(예: 보통) + 덧붙임 */
data class LevelPart(val range: String, val label: String, val note: String)

private fun hasNum(w: String) = w.any { it.isDigit() || it in "↑↓±" }

/** "15↓ 안정 · 15~20 보통 · 30↑ 공포 (코로나 때 약 10)" → 구간 목록 */
fun splitLevels(levels: String): Pair<String?, List<LevelPart>> {
    var header: String? = null
    val parts = levels.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, raw ->
        var seg = raw
        if (i == 0 && ": " in seg) {
            header = seg.substringBefore(": ")
            seg = seg.substringAfter(": ")
        }
        var note = ""
        if (seg.endsWith(")") && " (" in seg) {
            note = seg.substring(seg.lastIndexOf(" (") + 1)
            seg = seg.substring(0, seg.lastIndexOf(" (")).trim()
        }
        if (" = " in seg) {
            LevelPart(seg.substringBefore(" = "), seg.substringAfter(" = "), note)
        } else {
            val words = seg.split(" ")
            val firstNum = words.indexOfFirst { hasNum(it) }
            val labelAt = if (firstNum < 0) -1
                else (firstNum + 1 until words.size).firstOrNull { !hasNum(words[it]) } ?: -1
            if (labelAt < 0) LevelPart("", seg, note)
            else LevelPart(words.take(labelAt).joinToString(" "), words.drop(labelAt).joinToString(" "), note)
        }
    }
    return header to parts
}

private val GOOD = Color(0xFF39C27A)
private val OK = Color(0xFFC2B25A)
private val WARN = Color(0xFFE0883C)
private val BAD = Color(0xFFA46CF0)

/** 판단 글자에 맞는 색 (마켓 날씨 색과 같은 규칙) */
fun levelColor(label: String): Color {
    val bad = listOf("큰 부담", "극도", "과열", "위험", "불안", "큰 악재", "경고", "급한 정리", "강한 매도", "비싼")
    val warn = listOf("주의", "부담", "긴장", "공포", "탐욕", "약함", "악재", "급등", "걱정", "기운", "둔화", "큰 움직임", "큰 변화", "큰 영향")
    val good = listOf("안정", "편함", "건강", "정상", "유리", "강함", "쌈", "튼튼", "호재", "상승장", "강한 매수", "이익 증가", "인하 기대")
    return when {
        bad.any { it in label } -> BAD
        warn.any { it in label } -> WARN
        good.any { it in label } -> GOOD
        "보통" in label -> OK
        else -> Palette.Fg.copy(alpha = 0.85f)
    }
}

/** 지표 설명 묶음: 무엇인지 / 의미 / 기준치(구간마다 한 줄, 색 점) */
@Composable
fun GuideBlock(g: IndGuide, extra: String = "") {
    Column(Modifier.padding(top = 2.dp)) {
        Row {
            Text("무엇", color = Palette.Faint, fontSize = 11.sp, modifier = Modifier.width(34.dp))
            Text(g.what + extra, color = Palette.Sub, fontSize = 11.sp)
        }
        Row {
            Text("의미", color = Palette.Faint, fontSize = 11.sp, modifier = Modifier.width(34.dp))
            Text(g.meaning, color = Palette.Fg.copy(alpha = 0.85f), fontSize = 11.sp)
        }
        LevelsBox(g.levels)
    }
}

@Composable
fun LevelsBox(levels: String) {
    val (header, parts) = splitLevels(levels)
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF202020)).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text("기준치" + (header?.let { "  ·  $it" } ?: ""), color = Palette.Faint, fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 2.dp))
        parts.forEach { p ->
            val c = levelColor(p.label)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c)) { append("●  ") }
                    if (p.range.isNotEmpty()) {
                        withStyle(SpanStyle(color = Palette.Fg)) { append(p.range) }
                        withStyle(SpanStyle(color = Palette.Faint)) { append("  →  ") }
                    }
                    withStyle(SpanStyle(color = c, fontWeight = FontWeight.Bold)) { append(p.label) }
                    if (p.note.isNotEmpty()) withStyle(SpanStyle(color = Palette.Faint)) { append("  " + p.note) }
                },
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}
