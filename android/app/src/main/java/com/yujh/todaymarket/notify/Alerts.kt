package com.yujh.todaymarket.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yujh.todaymarket.R
import com.yujh.todaymarket.data.AppSettings
import com.yujh.todaymarket.data.EarningsText
import com.yujh.todaymarket.data.MarketData
import com.yujh.todaymarket.ui.MainActivity
import kotlin.math.abs
import kotlin.math.floor

/**
 * 알림: 새 데이터를 받을 때마다(30분마다 + 앱에서 새로고침할 때) 조건을 확인한다.
 * 같은 알림을 두 번 보내지 않도록 보낸 기록을 휴대폰에 남긴다.
 */
object Alerts {
    private const val CHANNEL = "market-alerts"
    private const val PREFS = "alerts"

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "시장 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "관심 종목 급등락, VIX, 마켓 날씨, 실적 발표"
                    },
                )
            }
        }
    }

    fun send(context: Context, key: String, title: String, text: String) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(key.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    fun test(context: Context) =
        send(context, "test", "오늘의 시장 · 시험 알림", "알림이 잘 와요 ✓ 조건이 맞으면 이렇게 알려드릴게요.")

    /** 새 데이터로 조건 확인 → 필요한 알림 보내기 */
    fun check(context: Context, data: MarketData, st: AppSettings) {
        if (!st.alertsOn) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = (p.getStringSet("sent", emptySet()) ?: emptySet()).toMutableSet()
        val edit = p.edit()
        fun once(key: String, title: String, text: String) {
            if (key in sent) return
            sent += key
            send(context, key, title, text)
        }
        val stocks = data.stocks.filter { it.code !in st.removed && it.code !in st.hidden }

        // 1) 관심 종목 급등락: 기준의 1배, 2배를 넘을 때마다 한 번씩
        if (st.alertMovePct > 0) {
            stocks.forEach { s ->
                val pct = s.changePct ?: return@forEach
                val level = floor(abs(pct) / st.alertMovePct).toInt()
                if (level < 1) return@forEach
                val day = s.asOf?.take(10) ?: return@forEach
                val dir = if (pct > 0) "급등" else "급락"
                for (lv in 1..minOf(level, 3)) {
                    val key = "move:${s.code}:$day:${if (pct > 0) "+" else "-"}$lv"
                    if (lv == minOf(level, 3)) {
                        once(key, "${s.name} $dir ${String.format("%+.1f%%", pct)}",
                            (s.issue ?: "") + if (s.issue == null) "앱에서 자세히 보기" else "")
                    } else {
                        sent += key   // 한 번에 크게 움직였으면 낮은 단계는 건너뜀
                    }
                }
            }
        }

        // 2) VIX 25·30
        if (st.alertVix) {
            data.indicators.firstOrNull { it.key == "vix" }?.value?.let { v ->
                val bucket = when { v >= 30 -> 2; v >= 25 -> 1; else -> 0 }
                val last = p.getInt("vixBucket", -1)
                if (last >= 0 && bucket != last) {
                    val title = when (bucket) {
                        2 -> "⚠️ VIX ${String.format("%.1f", v)} · 30 넘음 (공포)"
                        1 -> if (last < 1) "VIX ${String.format("%.1f", v)} · 25 넘음 (불안 커짐)" else "VIX ${String.format("%.1f", v)} · 30 아래로"
                        else -> "VIX ${String.format("%.1f", v)} · 25 아래로 (불안 줄어듦)"
                    }
                    send(context, "vix", title, "VIX는 앞으로 주가가 얼마나 흔들릴지 보여주는 불안 지수예요.")
                }
                edit.putInt("vixBucket", bucket)
            }
        }

        // 3) 마켓 날씨 바뀜
        if (st.alertZone) {
            val ms = data.marketScore
            if (ms.zone != null && ms.score != null) {
                val lastZone = p.getString("zone", null)
                val lastScore = p.getInt("zoneScore", -1)
                val lastIcon = p.getString("zoneIcon", "") ?: ""
                if (lastZone != null && lastZone != ms.zone) {
                    send(context, "zone", "마켓 날씨 $lastIcon$lastZone → ${ms.icon ?: ""}${ms.zone}",
                        "시장 점수 $lastScore → ${ms.score}점. " + (ms.comment ?: ""))
                }
                edit.putString("zone", ms.zone).putInt("zoneScore", ms.score).putString("zoneIcon", ms.icon ?: "")
            }
        }

        // 4) 실적: 하루 전 · 당일 · 발표 뒤 결과
        if (st.alertEarnings) {
            stocks.forEach { s ->
                val e = s.earnings ?: return@forEach
                val left = EarningsText.daysLeft(e)
                if (left != null && left in 0..1) {
                    once("earn:${s.code}:${e.nextDate}:$left", "${s.name} 실적 ${if (left == 0L) "오늘" else "내일"} 발표",
                        EarningsText.upcoming(e) ?: "")
                }
                if (EarningsText.justReported(e)) {
                    val l = e.last
                    once("earnres:${s.code}:${l?.date}", "${s.name} 실적 발표됨",
                        (EarningsText.last(e) ?: "").replace("지난 실적", "실적"))
                }
            }
        }

        // 기록이 너무 많아지면 오래된 것부터 정리 (날짜가 들어간 키 기준)
        val trimmed = if (sent.size > 400) sent.sorted().takeLast(200).toSet() else sent
        edit.putStringSet("sent", trimmed).apply()
    }
}
