package com.voicelog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
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

/** Вкладки нижнего меню. */
private enum class Tab(val route: String, val title: String, val icon: ImageVector) {
    HOME("home", "Главная", Icons.Default.Home),
    RECORDS("records", "Записи", Icons.AutoMirrored.Filled.List),
    SETTINGS("settings", "Настройки", Icons.Default.Settings)
}

@Composable
private fun AppNav(vm: MainViewModel) {
    // Маршруты: "home", "records", "section/<id>", "settings"
    var route by rememberSaveable { mutableStateOf("home") }
    val inDetail = route.startsWith("section/")

    BackHandler(enabled = route != "home") {
        route = if (inDetail) "records" else "home"
    }

    fun selected(tab: Tab) = when (tab) {
        Tab.RECORDS -> route == "records" || inDetail
        else -> route == tab.route
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                for (tab in Tab.entries) {
                    NavigationBarItem(
                        selected = selected(tab),
                        onClick = { route = tab.route },
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
            when {
                route == "records" -> RecordsScreen(
                    vm = vm,
                    onOpenSection = { route = "section/$it" }
                )

                inDetail -> {
                    val id = route.removePrefix("section/").toLongOrNull()
                    if (id == null) {
                        route = "records"
                    } else {
                        SectionScreen(vm = vm, id = id, onBack = { route = "records" })
                    }
                }

                route == "settings" -> SettingsScreen(vm = vm)
                else -> HomeScreen(vm = vm)
            }
        }
    }
}
