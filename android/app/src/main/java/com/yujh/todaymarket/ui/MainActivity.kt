package com.yujh.todaymarket.ui

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.yujh.todaymarket.notify.Alerts
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yujh.todaymarket.data.AppSettings
import com.yujh.todaymarket.data.MarketData
import com.yujh.todaymarket.data.LiveQuotes
import com.yujh.todaymarket.data.WatchlistApi
import com.yujh.todaymarket.data.Repository
import com.yujh.todaymarket.data.SettingsStore
import com.yujh.todaymarket.widget.RefreshWorker
import com.yujh.todaymarket.widget.TodayWidget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MarketViewModel(app: Application) : AndroidViewModel(app) {
    var data by mutableStateOf<MarketData?>(Repository.loadCached(app))
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var settings by mutableStateOf(SettingsStore.load(app))
        private set
    var charts by mutableStateOf(Repository.loadCachedCharts(app))
        private set

    private var widgetJob: Job? = null

    init {
        refresh()
    }

    /** ↻ 상태 안내 (가격 받음 · 서버가 새로 만드는 중 등). 없으면 null */
    var refreshNote by mutableStateOf<String?>(null)
        private set
    private var serverJob: Job? = null

    /** 앱을 열 때: 서버 결과만 받는다 */
    fun refresh() = refreshInternal(manual = false)

    /** ↻ 를 눌렀을 때: 서버 결과 + 가격 바로 받기 + 서버에 새로 만들기 요청 */
    fun refreshNow() = refreshInternal(manual = true)

    private fun refreshInternal(manual: Boolean) {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                data = Repository.fetch(getApplication())
                error = null
                runCatching { TodayWidget().updateAll(getApplication()) }
                data?.let { d -> runCatching { Alerts.check(getApplication(), d, settings) } }
            } catch (e: Exception) {
                error = "새 데이터를 받지 못했어요. 마지막 값을 보여드려요. (${e.message ?: e.javaClass.simpleName})"
            }
            if (manual) {
                // 1) 가격은 휴대폰이 직접 바로
                val cur = data
                if (cur != null) {
                    refreshNote = try {
                        val (live, n) = LiveQuotes.apply(cur)
                        data = live
                        "가격 ${n}개를 방금 새로 받았어요."
                    } catch (e: Exception) {
                        "가격을 바로 받지 못했어요 (${e.message})."
                    }
                }
                loading = false
                // 2) AI 요약·점수는 서버에 부탁
                askServer()
            } else {
                loading = false
            }
            // 그래프 숫자는 따로 받는다 (실패해도 지난 그래프를 그대로 보여줌)
            runCatching { charts = Repository.fetchCharts(getApplication()) }
        }
    }

    private fun askServer() {
        val token = settings.githubToken
        val priceNote = refreshNote ?: ""
        if (token.isBlank()) {
            refreshNote = "$priceNote AI 요약·점수까지 바로 받으려면 설정에서 GitHub 열쇠를 연결해 주세요."
            return
        }
        serverJob?.cancel()
        serverJob = viewModelScope.launch {
            val before = data?.generatedAt
            try {
                WatchlistApi.runServer(token)
            } catch (e: Exception) {
                refreshNote = "$priceNote 서버 요청 실패: ${e.message}"
                return@launch
            }
            // 서버가 끝날 때까지 20초마다 확인 (최대 6분)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 6 * 60_000) {
                val sec = (System.currentTimeMillis() - start) / 1000
                refreshNote = "$priceNote 서버가 AI 요약·점수를 새로 만드는 중… (${sec}초, 보통 1~3분)"
                delay(20_000)
                val fresh = runCatching { Repository.fetch(getApplication()) }.getOrNull()
                if (fresh != null && fresh.generatedAt != before) {
                    data = fresh
                    error = null
                    runCatching { TodayWidget().updateAll(getApplication()) }
                    runCatching { Alerts.check(getApplication(), fresh, settings) }
                    runCatching { charts = Repository.fetchCharts(getApplication()) }
                    refreshNote = "모두 새로 받았어요 ✓ (${fmtTime(fresh.generatedAt)})"
                    delay(8_000)
                    refreshNote = null
                    return@launch
                }
            }
            refreshNote = "서버가 오래 걸려요. 조금 뒤 다시 ↻ 해 주세요."
        }
    }

    fun updateSettings(new: AppSettings) {
        settings = new
        SettingsStore.save(getApplication(), new)
        // 입력 중에 위젯을 너무 자주 다시 그리지 않도록 잠깐 기다렸다가 반영
        widgetJob?.cancel()
        widgetJob = viewModelScope.launch {
            delay(600)
            runCatching { TodayWidget().updateAll(getApplication()) }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RefreshWorker.schedule(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Palette.Bg, surface = Palette.Bg)) {
                val vm: MarketViewModel = viewModel()
                // 알림 권한 (안드로이드 13 이상은 한 번 물어봐야 함)
                val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(vm.settings.alertsOn) {
                    if (vm.settings.alertsOn && Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(this@MainActivity)) {
                        askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                var screen by remember { mutableStateOf("detail") }
                BackHandler(enabled = screen == "settings") { screen = "detail" }

                // 설정한 글씨 크기를 앱 전체에 적용
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, base.fontScale * vm.settings.fontScale),
                ) {
                    if (screen == "settings") {
                        SettingsScreen(
                            data = vm.data, settings = vm.settings,
                            onChange = vm::updateSettings, onBack = { screen = "detail" },
                        )
                    } else {
                        DetailScreen(
                            data = vm.data, loading = vm.loading, error = vm.error,
                            settings = vm.settings, charts = vm.charts,
                            onRefresh = vm::refreshNow, refreshNote = vm.refreshNote, onOpenSettings = { screen = "settings" },
                        )
                    }
                }
            }
        }
    }
}
