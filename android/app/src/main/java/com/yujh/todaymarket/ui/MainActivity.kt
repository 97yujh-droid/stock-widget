package com.yujh.todaymarket.ui

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yujh.todaymarket.data.MarketData
import com.yujh.todaymarket.data.Repository
import kotlinx.coroutines.launch

class MarketViewModel(app: Application) : AndroidViewModel(app) {
    var data by mutableStateOf<MarketData?>(Repository.loadCached(app))
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

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
            } catch (e: Exception) {
                error = "새 데이터를 받지 못했어요. 마지막 값을 보여드려요. (${e.message ?: e.javaClass.simpleName})"
            } finally {
                loading = false
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Palette.Bg, surface = Palette.Bg)) {
                val vm: MarketViewModel = viewModel()
                DetailScreen(
                    data = vm.data,
                    loading = vm.loading,
                    error = vm.error,
                    onRefresh = vm::refresh,
                )
            }
        }
    }
}
