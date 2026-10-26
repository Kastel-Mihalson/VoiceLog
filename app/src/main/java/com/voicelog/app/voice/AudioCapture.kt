package com.voicelog.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Записывает речь с микрофона (PCM 16 бит, 16 кГц, моно) и сам определяет,
 * когда человек начал и закончил говорить. Запись целиком возвращается одним массивом,
 * поэтому её можно отправить в облако, а при сбое — повторно распознать офлайн.
 *
 * Метод [record] блокирующий: вызывать из фонового потока.
 */
class AudioCapture {

    @Volatile
    private var stopRequested = false

    /** Остановить запись (кнопка «стоп»). Если речь уже была, она будет возвращена. */
    fun requestStop() {
        stopRequested = true
    }

    /**
     * Возвращает PCM-байты речи (little-endian) или null, если речи не было.
     * Бросает IOException, если микрофон недоступен.
     */
    @SuppressLint("MissingPermission")
    fun record(): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IOException("микрофон недоступен")

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, RATE * 2)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("не удалось открыть микрофон")
        }

        val frames = ArrayList<ShortArray>()
        var firstSpeech = -1
        var lastSpeech = -1
        var speechRun = 0
        var speechFrames = 0
        var silentFrames = 0
        var noiseSum = 0.0
        var threshold = MIN_THRESHOLD

        try {
            rec.startRecording()
            while (!stopRequested) {
                val buf = ShortArray(FRAME)
                var read = 0
                while (read < FRAME && !stopRequested) {
                    val n = rec.read(buf, read, FRAME - read)
                    if (n < 0) throw IOException("ошибка чтения с микрофона ($n)")
                    read += n
                }
                if (read < FRAME) break
                frames.add(buf)
                val idx = frames.lastIndex
                val level = rms(buf)

                // Первые 300 мс оцениваем фоновый шум (но не больше разумного предела,
                // чтобы не принять за шум речь, начатую сразу после включения).
                if (idx < CALIBRATION_FRAMES) {
                    noiseSum += level
                    if (idx == CALIBRATION_FRAMES - 1) {
                        val noise = min(noiseSum / CALIBRATION_FRAMES, MAX_NOISE)
                        threshold = max(MIN_THRESHOLD, noise * 2.5)
                    }
                    continue
                }

                if (level > threshold) {
                    speechRun++
                    silentFrames = 0
                    speechFrames++
                    if (firstSpeech < 0 && speechRun >= 3) firstSpeech = idx - 2
                    if (firstSpeech >= 0) lastSpeech = idx
                } else {
                    speechRun = 0
                    if (firstSpeech >= 0) silentFrames++
                }

                if (firstSpeech >= 0 && silentFrames >= END_SILENCE_FRAMES) break
                if (firstSpeech < 0 && idx >= NO_SPEECH_FRAMES) break
                if (idx >= MAX_FRAMES) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }

        if (firstSpeech < 0 || speechFrames < MIN_SPEECH_FRAMES) return null

        val from = max(0, firstSpeech - PREROLL_FRAMES)
        val to = min(frames.size, lastSpeech + 1 + POSTROLL_FRAMES)
        val out = ByteBuffer.allocate((to - from) * FRAME * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from until to) for (s in frames[i]) out.putShort(s)
        return out.array()
    }

    private fun rms(buf: ShortArray): Double {
        var sum = 0.0
        for (s in buf) sum += s.toDouble() * s
        return sqrt(sum / buf.size)
    }

    companion object {
        const val RATE = 16000
        private const val FRAME = 320                 // 20 мс
        private const val CALIBRATION_FRAMES = 15     // 300 мс на оценку шума
        private const val END_SILENCE_FRAMES = 70     // 1,4 с тишины после речи — конец фразы
        private const val NO_SPEECH_FRAMES = 400      // 8 с без речи — ждать бесполезно
        private const val MAX_FRAMES = 1250           // не дольше 25 с
        private const val PREROLL_FRAMES = 15         // 300 мс до начала речи
        private const val POSTROLL_FRAMES = 10        // 200 мс после конца речи
        private const val MIN_SPEECH_FRAMES = 10      // меньше 200 мс речи — это щелчок, а не слова
        private const val MIN_THRESHOLD = 450.0
        private const val MAX_NOISE = 400.0
    }
}
