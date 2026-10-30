package com.yujh.todaymarket.ui

import android.app.Application
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

    private var widgetJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                data = Repository.fetch(getApplication())
                error = null
                // 앱에서 새로 받으면 홈 화면 위젯도 바로 다시 그린다
                runCatching { TodayWidget().updateAll(getApplication()) }
            } catch (e: Exception) {
                error = "새 데이터를 받지 못했어요. 마지막 값을 보여드려요. (${e.message ?: e.javaClass.simpleName})"
            } finally {
                loading = false
            }
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
                            settings = vm.settings,
                            onRefresh = vm::refresh, onOpenSettings = { screen = "settings" },
                        )
                    }
                }
            }
        }
    }
}
