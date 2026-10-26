package com.voicelog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
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
    NOTES("Заметки", Icons.Default.Edit),
    STATS("Статистика", Icons.Default.BarChart),
    VOICE("Голос", Icons.Default.Mic),
    RECORDS("Записи", Icons.AutoMirrored.Filled.List),
    SETTINGS("Настройки", Icons.Default.Settings)
}

private val VOICE_PAGE = Tab.VOICE.ordinal

@Composable
private fun AppNav(vm: MainViewModel) {
    // Открытый раздел внутри страниц «Статистика» и «Записи» (null — список).
    var statsId by rememberSaveable { mutableStateOf<Long?>(null) }
    var recordsId by rememberSaveable { mutableStateOf<Long?>(null) }
    val pager = rememberPagerState(initialPage = VOICE_PAGE) { Tab.entries.size }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = pager.currentPage != VOICE_PAGE || statsId != null || recordsId != null) {
        when {
            pager.currentPage == Tab.STATS.ordinal && statsId != null -> statsId = null
            pager.currentPage == Tab.RECORDS.ordinal && recordsId != null -> recordsId = null
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
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
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
        // Нижний отступ — высота меню. Его же «съедаем» у клавиатуры,
        // иначе поле ввода поднимется выше, чем нужно.
        val bottom = inner.calculateBottomPadding()
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = bottom)
                .consumeWindowInsets(PaddingValues(bottom = bottom))
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = Tab.entries.size
            ) { page ->
                when (Tab.entries[page]) {
                    Tab.NOTES -> NotesScreen(vm = vm)

                    Tab.STATS -> {
                        val id = statsId
                        if (id == null) {
                            StatsScreen(vm = vm, onOpenSection = { statsId = it })
                        } else {
                            SectionScreen(vm = vm, id = id, onBack = { statsId = null })
                        }
                    }

                    Tab.VOICE -> HomeScreen(vm = vm)

                    Tab.RECORDS -> {
                        val id = recordsId
                        if (id == null) {
                            RecordsScreen(vm = vm, onOpenSection = { recordsId = it })
                        } else {
                            SectionScreen(vm = vm, id = id, onBack = { recordsId = null })
                        }
                    }

                    Tab.SETTINGS -> SettingsScreen(vm = vm)
                }
            }
        }
    }
}
