package com.voicelog.app.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val percent: Int, val phase: String) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Скачивает (один раз) и хранит русскую модель Vosk во внутренней памяти приложения.
 * Модель small-ru занимает около 45 МБ в архиве.
 */
class VoskModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val root = appContext.filesDir
    private val modelDir = File(root, MODEL_NAME)
    private val marker = File(modelDir, ".complete")

    private val _state = MutableStateFlow<ModelState>(
        if (marker.exists()) ModelState.Ready else ModelState.Missing
    )
    val state: StateFlow<ModelState> = _state.asStateFlow()

    val path: String get() = modelDir.absolutePath

    suspend fun download() {
        withContext(Dispatchers.IO) {
            if (_state.value is ModelState.Downloading) return@withContext
            _state.value = ModelState.Downloading(0, "Скачивание")
            val zip = File(appContext.cacheDir, "$MODEL_NAME.zip")
            try {
                downloadFile(zip)
                _state.value = ModelState.Downloading(100, "Распаковка")
                modelDir.deleteRecursively()
                unzip(zip, root)
                if (!File(modelDir, "am").isDirectory) {
                    throw IOException("в архиве нет файлов модели")
                }
                marker.writeText("ok")
                _state.value = ModelState.Ready
            } catch (e: Exception) {
                modelDir.deleteRecursively()
                _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                zip.delete()
            }
        }
    }

    /** Удалить модель (например, чтобы скачать заново). */
    fun delete() {
        modelDir.deleteRecursively()
        _state.value = ModelState.Missing
    }

    private fun downloadFile(target: File) {
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("сервер ответил ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 99 / total).toInt()
                            if (p != lastPercent) {
                                lastPercent = p
                                _state.value = ModelState.Downloading(p, "Скачивание")
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun unzip(zip: File, dest: File) {
        val base = dest.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(base)) {
                    throw IOException("недопустимый путь в архиве")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        const val MODEL_NAME = "vosk-model-small-ru-0.22"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    }
}
