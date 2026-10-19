package com.voicelog.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Простое хранилище настроек в SharedPreferences. */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("voicelog_settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: ThemeMode.LIGHT.name) }
            .getOrDefault(ThemeMode.LIGHT)
    )
    val themeMode: StateFlow<ThemeMode> = _theme

    private val _speak = MutableStateFlow(prefs.getBoolean("speak", true))
    val speakReplies: StateFlow<Boolean> = _speak

    private val _auto = MutableStateFlow(prefs.getBoolean("auto_listen", true))
    val autoListen: StateFlow<Boolean> = _auto

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun setSpeakReplies(value: Boolean) {
        _speak.value = value
        prefs.edit().putBoolean("speak", value).apply()
    }

    fun setAutoListen(value: Boolean) {
        _auto.value = value
        prefs.edit().putBoolean("auto_listen", value).apply()
    }
}
