package com.voicelog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicelog.app.MainViewModel
import com.voicelog.app.data.ThemeMode

@Composable
fun VoiceLogApp(vm: MainViewModel = viewModel()) {
    val mode by vm.settings.themeMode.collectAsState()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    VoiceLogTheme(dark) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AppNav(vm)
        }
    }
}

/** Вкладки нижнего меню: «Голос» — в центре и открывается первой. */
private enum class Tab(val title: String, val icon: ImageVector) {
    RECORDS("Записи", Icons.AutoMirrored.Filled.List),
    VOICE("Голос", Icons.Default.Mic),
    SETTINGS("Настройки", Icons.Default.Settings)
}

private const val VOICE_PAGE = 1

@Composable
private fun AppNav(vm: MainViewModel) {
    // Внутри «Записей»: null — список разделов, иначе id открытого раздела.
    var sectionId by rememberSaveable { mutableStateOf<Long?>(null) }
    val pager = rememberPagerState(initialPage = VOICE_PAGE) { Tab.entries.size }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = pager.currentPage != VOICE_PAGE || sectionId != null) {
        when {
            pager.currentPage == 0 && sectionId != null -> sectionId = null
            else -> scope.launch { pager.animateScrollToPage(VOICE_PAGE) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                for ((index, tab) in Tab.entries.withIndex()) {
                    NavigationBarItem(
                        selected = pager.currentPage == index,
                        onClick = {
                            scope.launch { pager.animateScrollToPage(index) }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    ) { inner ->
        // Верхний отступ (строка состояния) обрабатывает панель каждого экрана,
        // а снизу экран заканчивается над нижним меню.
        Box(Modifier.fillMaxSize().padding(bottom = inner.calculateBottomPadding())) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = Tab.entries.size
            ) { page ->
                when (page) {
                    0 -> {
                        val id = sectionId
                        if (id == null) {
                            RecordsScreen(vm = vm, onOpenSection = { sectionId = it })
                        } else {
                            SectionScreen(vm = vm, id = id, onBack = { sectionId = null })
                        }
                    }

                    1 -> HomeScreen(vm = vm)
                    else -> SettingsScreen(vm = vm)
                }
            }
        }
    }
}
