@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicelog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicelog.app.MainViewModel
import com.voicelog.app.data.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun VoiceLogApp(vm: MainViewModel = viewModel()) {
    val mode by vm.settings.themeMode.collectAsState()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    VoiceLogTheme(dark) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AppNav(vm, dark)
        }
    }
}

@Composable
private fun AppNav(vm: MainViewModel, dark: Boolean) {
    // Маршруты: "home", "records", "section/<id>", "settings"
    var route by rememberSaveable { mutableStateOf("home") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val inDetail = route.startsWith("section/")

    BackHandler(enabled = drawerState.isOpen || route != "home") {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            inDetail -> route = "records"
            else -> route = "home"
        }
    }

    fun go(target: String) {
        route = target
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !inDetail,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
                DrawerHeader()
                HorizontalDivider()
                Column(Modifier.padding(top = 12.dp)) {
                    DrawerItem("Главная", Icons.Default.Home, route == "home") { go("home") }
                    DrawerItem(
                        "Записи",
                        Icons.AutoMirrored.Filled.List,
                        route == "records" || inDetail
                    ) { go("records") }
                    DrawerItem("Настройки", Icons.Default.Settings, route == "settings") { go("settings") }
                }
                Spacer(Modifier.weight(1f))
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DarkMode, contentDescription = null)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Тёмная тема",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = dark,
                        onCheckedChange = {
                            vm.settings.setTheme(if (it) ThemeMode.DARK else ThemeMode.LIGHT)
                        }
                    )
                }
            }
        }
    ) {
        val openMenu: () -> Unit = { scope.launch { drawerState.open() } }
        when {
            route == "records" -> RecordsScreen(
                vm = vm,
                onMenu = openMenu,
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

            route == "settings" -> SettingsScreen(vm = vm, onMenu = openMenu)
            else -> HomeScreen(vm = vm, onMenu = openMenu)
        }
    }
}

@Composable
private fun DrawerHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
        Column {
            Text("Голосовой дневник", style = MaterialTheme.typography.titleLarge)
            Text(
                "Говорите — приложение запишет",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}
