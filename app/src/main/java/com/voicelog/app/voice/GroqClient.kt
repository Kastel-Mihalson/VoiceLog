package com.voicelog.app.voice

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Распознавание речи через Groq (Whisper large-v3).
 * Бесплатный тариф: лимиты в сутки, после их исчерпания сервер отвечает кодом 429.
 * Все методы блокирующие: вызывать из фонового потока.
 */
object GroqClient {

    private const val BASE = "https://api.groq.com/openai/v1"
    private const val MODEL = "whisper-large-v3"
    private const val USER_AGENT = "VoiceLog/1.0"

    enum class Reason { NETWORK, RATE_LIMIT, AUTH, BLOCKED, OTHER }

    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Fail(val reason: Reason, val message: String) : Result
    }

    /** Распознать PCM (16 кГц, 16 бит, моно). hint — подсказка со словарём для Whisper. */
    fun transcribe(apiKey: String, pcm: ByteArray, hint: String): Result {
        val boundary = "----voicelog" + System.currentTimeMillis()
        val body = ByteArrayOutputStream()

        fun field(name: String, value: String) {
            body.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                    .toByteArray(Charsets.UTF_8)
            )
        }
        field("model", MODEL)
        field("language", "ru")
        field("response_format", "json")
        field("temperature", "0")
        if (hint.isNotBlank()) field("prompt", hint)
        body.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        body.write(wav(pcm))
        body.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        val bytes = body.toByteArray()

        return call("$BASE/audio/transcriptions", apiKey, "POST", bytes, "multipart/form-data; boundary=$boundary") { text ->
            JSONObject(text).optString("text").trim()
        }
    }

    /** Проверка ключа и доступности сервиса: запрос списка моделей. */
    fun check(apiKey: String): Result =
        call("$BASE/models", apiKey, "GET", null, null) { "ok" }

    private fun call(
        url: String,
        apiKey: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        parse: (String) -> String
    ): Result {
        var conn: HttpURLConnection? = null
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 4000
                readTimeout = 12000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("User-Agent", USER_AGENT)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType)
                    setFixedLengthStreamingMode(body.size)
                }
            }
            conn = c
            if (body != null) c.outputStream.use { it.write(body) }

            val code = c.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return Result.Ok(parse(text))
            }
            val err = runCatching {
                c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            val serverMessage = runCatching {
                JSONObject(err).optJSONObject("error")?.optString("message").orEmpty()
            }.getOrDefault("").ifBlank { err.take(120) }
            val reason = when (code) {
                401 -> Reason.AUTH
                429 -> Reason.RATE_LIMIT
                403 -> Reason.BLOCKED
                else -> Reason.OTHER
            }
            return Result.Fail(reason, "код $code" + if (serverMessage.isBlank()) "" else ": $serverMessage")
        } catch (e: IOException) {
            return Result.Fail(Reason.NETWORK, e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            return Result.Fail(Reason.OTHER, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** Оборачивает «сырой» PCM в WAV-файл. */
    private fun wav(pcm: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(36 + pcm.size)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1)                          // PCM
        b.putShort(1)                          // моно
        b.putInt(AudioCapture.RATE)
        b.putInt(AudioCapture.RATE * 2)        // байт в секунду
        b.putShort(2)                          // байт на отсчёт
        b.putShort(16)                         // бит на отсчёт
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(pcm.size)
        b.put(pcm)
        return b.array()
    }
}
