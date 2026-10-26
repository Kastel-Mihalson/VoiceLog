package com.voicelog.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * Чем распознаём речь:
 *  VOSK   — офлайн-модель на телефоне;
 *  GOOGLE — встроенный сервис телефона;
 *  GROQ   — облачный Whisper через интернет, при сбое автоматически Vosk, затем Google.
 */
enum class SpeechEngine { VOSK, GOOGLE, GROQ }

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

    // Ключ Groq хранится в закрытом хранилище приложения (другие приложения его не видят).
    private val _groqKey = MutableStateFlow(prefs.getString("groq_key", "") ?: "")
    val groqKey: StateFlow<String> = _groqKey

    fun setGroqKey(value: String) {
        val key = value.trim()
        _groqKey.value = key
        prefs.edit().putString("groq_key", key).apply()
    }

    private val _autoStart = MutableStateFlow(prefs.getBoolean("auto_start", true))
    val autoStartRecording: StateFlow<Boolean> = _autoStart

    fun setAutoStartRecording(value: Boolean) {
        _autoStart.value = value
        prefs.edit().putBoolean("auto_start", value).apply()
    }

    private val _engine = MutableStateFlow(
        runCatching { SpeechEngine.valueOf(prefs.getString("engine", null) ?: SpeechEngine.GOOGLE.name) }
            .getOrDefault(SpeechEngine.GOOGLE)
    )
    val engine: StateFlow<SpeechEngine> = _engine

    fun setEngine(value: SpeechEngine) {
        _engine.value = value
        prefs.edit().putString("engine", value.name).apply()
    }

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
