package com.voicelog.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicelog.app.data.AppDatabase
import com.voicelog.app.data.LogEntry
import com.voicelog.app.data.Section
import com.voicelog.app.data.SectionType
import com.voicelog.app.data.SettingsStore
import com.voicelog.app.data.isNotes
import com.voicelog.app.data.SpeechEngine
import com.voicelog.app.util.Fmt
import com.voicelog.app.voice.AudioCapture
import com.voicelog.app.voice.GroqClient
import com.voicelog.app.voice.ModelState
import com.voicelog.app.voice.NoteParser
import com.voicelog.app.voice.ParseResult
import com.voicelog.app.voice.SectionCommand
import com.voicelog.app.voice.SectionRef
import com.voicelog.app.voice.VoiceParser
import com.voicelog.app.voice.VoskModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class Status { IDLE, LISTENING, PROCESSING, SPEAKING }

enum class MessageKind { NONE, SAVED, QUESTION, INFO, ERROR }

/** Всё, что показывает главный экран. */
data class HomeUi(
    val status: Status = Status.IDLE,
    /** Промежуточный текст, пока человек говорит. */
    val partial: String = "",
    /** Последняя распознанная фраза. */
    val heard: String = "",
    /** Ответ или вопрос приложения. */
    val message: String = "",
    val kind: MessageKind = MessageKind.NONE,
    /** Приложение ждёт ответа на уточняющий вопрос. */
    val awaiting: Boolean = false,
    /** Чем распознана последняя фраза (Groq, Vosk, Google) и почему именно так. */
    val source: String = ""
)

internal data class Reply(
    val text: String,
    val kind: MessageKind,
    val spoken: String = text,
    val listenNext: Boolean = false
)

internal sealed interface Pending {
    /** Просили назвать новый раздел. */
    data object NeedSectionName : Pending

    /** Раздел создан, ждём единицу измерения. */
    data class NeedUnit(val sectionId: Long, val name: String) : Pending

    /** raw — исходная фраза: если выберут раздел-заметки, сохраняем её дословно. */
    data class NeedSection(val description: String, val cents: Long?, val raw: String = "") : Pending
    /** Заметка есть, но непонятно, в какой раздел-заметки её положить. */
    data class NeedNoteSection(val text: String, val candidates: List<SectionRef>) : Pending
    /** Раздел назван, но не сказано, какого он типа. */
    data class NeedType(val cmd: SectionCommand.Create) : Pending
    data class NeedValue(val section: SectionRef, val description: String) : Pending
}

private fun Section.toRef() = SectionRef(id, name, unit, keywords, askValue, isNotes)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).dao()
    val settings = SettingsStore(app)

    val sections: StateFlow<List<Section>> =
        dao.sections().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allEntries: StateFlow<List<LogEntry>> =
        dao.allEntries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Заметки из всех разделов-заметок (общая лента). */
    val noteEntries: StateFlow<List<LogEntry>> =
        dao.noteEntries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _home = MutableStateFlow(HomeUi())
    val home: StateFlow<HomeUi> = _home.asStateFlow()

    private var pending: Pending? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechDone: (() -> Unit)? = null

    // --- Vosk (офлайн-распознавание) ---
    private val models = VoskModelManager(app)
    val modelState: StateFlow<ModelState> = models.state
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var voskService: SpeechService? = null
    private var voskDone = false
    private val voskBuffer = StringBuilder()
    private var voskFinishJob: Job? = null

    // --- Groq (облачный Whisper) ---
    private var capture: AudioCapture? = null
    private var captureJob: Job? = null

    /** Результат проверки ключа Groq для экрана настроек. */
    private val _groqStatus = MutableStateFlow("")
    val groqStatus: StateFlow<String> = _groqStatus.asStateFlow()

    init {
        if (models.state.value is ModelState.Ready) preloadModel()
        tts = TextToSpeech(app) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val r = engine.setLanguage(Locale.forLanguageTag("ru-RU"))
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finishSpeech()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finishSpeech()
                })
            }
        }
    }

    // ------------------------------------------------------------------
    // Разделы и записи (экраны «Записи» и «Раздел»)
    // ------------------------------------------------------------------

    fun sectionFlow(id: Long) = dao.section(id)

    fun entriesFlow(id: Long) = dao.entries(id)

    fun addSection(name: String, unit: String, keywords: String, askValue: Boolean, notes: Boolean = false) {
        viewModelScope.launch {
            dao.insertSection(
                Section(
                    name = name,
                    unit = if (notes) "" else unit,
                    keywords = keywords,
                    askValue = if (notes) false else askValue,
                    type = (if (notes) SectionType.NOTES else SectionType.NUMBERS).name
                )
            )
        }
    }

    fun updateSection(section: Section) {
        viewModelScope.launch { dao.updateSection(section) }
    }

    fun deleteSection(section: Section) {
        viewModelScope.launch { dao.deleteSection(section) }
    }

    fun addEntry(sectionId: Long, description: String, cents: Long) {
        viewModelScope.launch {
            dao.insertEntry(
                LogEntry(
                    sectionId = sectionId,
                    description = description,
                    valueCents = cents,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun addNote(sectionId: Long, text: String) {
        viewModelScope.launch {
            dao.insertEntry(
                LogEntry(
                    sectionId = sectionId,
                    description = text,
                    valueCents = 0,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateEntry(entry: LogEntry) {
        viewModelScope.launch { dao.updateEntry(entry) }
    }

    fun deleteEntry(entry: LogEntry) {
        viewModelScope.launch { dao.deleteEntry(entry) }
    }

    // ------------------------------------------------------------------
    // Голосовой ввод
    // ------------------------------------------------------------------

    /** Скачать модель Vosk; после успешной загрузки включаем её как основной движок. */
    fun downloadModel() {
        viewModelScope.launch {
            models.download()
            if (models.state.value is ModelState.Ready) {
                // Если выбран Groq, оставляем его: Vosk станет запасным вариантом.
                if (settings.engine.value != SpeechEngine.GROQ) settings.setEngine(SpeechEngine.VOSK)
                preloadModel()
            }
        }
    }

    fun deleteModel() {
        stopVosk()
        voskModel?.let { runCatching { it.close() } }
        voskModel = null
        models.delete()
        if (settings.engine.value == SpeechEngine.VOSK) settings.setEngine(SpeechEngine.GOOGLE)
    }

    private fun preloadModel() {
        if (voskModel != null) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Model(models.path) } }
                .onSuccess { if (voskModel == null) voskModel = it else it.close() }
        }
    }

    /** Нажатие на кнопку микрофона. Разрешение на запись проверяет экран. */
    fun onMicClick() {
        when (_home.value.status) {
            Status.IDLE -> startListening()
            Status.LISTENING -> {
                recognizer?.stopListening()
                voskService?.stop() // результат придёт в onFinalResult
                capture?.requestStop() // Groq: запись завершится, и речь уйдёт на распознавание
            }
            Status.SPEAKING -> {
                speechDone = null
                tts?.stop()
                startListening()
            }
            Status.PROCESSING -> Unit
        }
    }

    /** Ввод команды текстом (удобно для проверки без микрофона). */
    fun submitText(text: String) {
        if (_home.value.status == Status.PROCESSING) return
        speechDone = null
        tts?.stop()
        recognizer?.cancel()
        voskDone = true
        stopVosk()
        stopCapture()
        handleText(text)
    }

    /** Прервать уточняющий диалог. */
    fun cancelDialog() {
        pending = null
        speechDone = null
        tts?.stop()
        recognizer?.cancel()
        voskDone = true
        stopVosk()
        stopCapture()
        _home.value = HomeUi()
    }

    fun showError(message: String) {
        _home.update { it.copy(status = Status.IDLE, message = message, kind = MessageKind.ERROR) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            _home.update { it.copy(partial = text) }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isBlank()) onNothingHeard() else handleText(text, "Google")
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onNothingHeard()

                SpeechRecognizer.ERROR_CLIENT -> {
                    // Возникает при отмене — ничего показывать не нужно.
                    _home.update { if (it.status == Status.LISTENING) it.copy(status = Status.IDLE) else it }
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    showError("Нет доступа к микрофону. Разрешите его в настройках телефона.")

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER ->
                    showError(
                        "Служба распознавания недоступна. Проверьте интернет " +
                            "или скачайте русский офлайн-пакет в настройках голосового ввода Google."
                    )

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.destroy()
                    recognizer = null
                    showError("Распознавание занято. Нажмите на кнопку ещё раз.")
                }

                else -> showError("Ошибка распознавания речи (код $error).")
            }
        }
    }

    private fun startListening() {
        when (settings.engine.value) {
            SpeechEngine.GROQ -> startGroq()
            SpeechEngine.VOSK -> startVosk()
            SpeechEngine.GOOGLE -> startGoogle()
        }
    }

    // ------------------------------------------------------------------
    // Groq (облако) с автоматическим переходом на запасные движки
    // ------------------------------------------------------------------

    private fun stopCapture() {
        capture?.requestStop()
        captureJob?.cancel()
        capture = null
        captureJob = null
    }

    /** Офлайн-вариант, если облако невозможно использовать: Vosk, а без него — Google. */
    private fun startOffline() {
        if (models.state.value is ModelState.Ready) startVosk() else startGoogle()
    }

    private fun startGroq() {
        val key = settings.groqKey.value
        if (key.isBlank()) {
            startOffline()
            return
        }
        stopVosk()
        recognizer?.cancel()
        stopCapture()

        val cap = AudioCapture()
        capture = cap
        _home.update { it.copy(status = Status.LISTENING, partial = "") }

        captureJob = viewModelScope.launch {
            val pcm = try {
                withContext(Dispatchers.IO) { cap.record() }
            } catch (e: Exception) {
                ensureActive()
                capture = null
                showError("Ошибка записи звука: ${e.message}")
                return@launch
            }
            ensureActive()
            capture = null
            if (pcm == null) {
                onNothingHeard()
                return@launch
            }

            _home.update { it.copy(status = Status.PROCESSING, partial = "") }
            val result = transcribe(key, pcm)
            ensureActive()
            when {
                result == null -> {
                    // Ни облако, ни офлайн-модель не сработали: включаем встроенный сервис Google.
                    _home.update {
                        it.copy(
                            message = "Облако и офлайн-модель недоступны. Скажите ещё раз — " +
                                "включаю встроенное распознавание.",
                            kind = MessageKind.INFO
                        )
                    }
                    startGoogle()
                }
                result.text.isBlank() -> onNothingHeard()
                else -> handleText(result.text, result.source)
            }
        }
    }

    private class Transcript(val text: String, val source: String)

    /** Подсказка для Whisper: названия разделов, чтобы они распознавались точнее. */
    private fun groqHint(): String {
        val names = sections.value.joinToString(", ") { it.name.lowercase() }.take(150)
        return if (names.isBlank()) "Суммы в рублях и копейках."
        else "Разделы: $names. Суммы в рублях и копейках."
    }

    /** Цепочка: Groq → Vosk (на той же записи). null — не сработало ничего. */
    private suspend fun transcribe(key: String, pcm: ByteArray): Transcript? {
        val hint = groqHint()
        val reason: String
        when (val r = withContext(Dispatchers.IO) { GroqClient.transcribe(key, pcm, hint) }) {
            is GroqClient.Result.Ok ->
                return Transcript(if (isHallucination(r.text)) "" else r.text, "Groq")
            is GroqClient.Result.Fail -> reason = when (r.reason) {
                GroqClient.Reason.NETWORK -> "нет сети"
                GroqClient.Reason.RATE_LIMIT -> "дневной лимит исчерпан"
                GroqClient.Reason.AUTH -> "ключ не принят"
                GroqClient.Reason.BLOCKED -> "доступ запрещён"
                GroqClient.Reason.OTHER -> "ошибка сервера"
            }
        }
        if (models.state.value is ModelState.Ready) {
            val text = runCatching { voskTranscribe(pcm) }.getOrNull()
            if (text != null) return Transcript(text, "Vosk · Groq недоступен: $reason")
        }
        return null
    }

    /** Распознаёт готовую запись моделью Vosk. */
    private suspend fun voskTranscribe(pcm: ByteArray): String = withContext(Dispatchers.Default) {
        val model = voskModel ?: Model(models.path).also { voskModel = it }
        val rec = Recognizer(model, 16000.0f)
        try {
            val sb = StringBuilder()
            var i = 0
            while (i < pcm.size) {
                val n = minOf(4000, pcm.size - i)
                if (rec.acceptWaveForm(pcm.copyOfRange(i, i + n), n)) {
                    sb.append(jsonField(rec.result, "text")).append(' ')
                }
                i += n
            }
            sb.append(jsonField(rec.finalResult, "text"))
            sb.toString().trim()
        } finally {
            runCatching { rec.close() }
        }
    }

    /** Whisper на тишине и шуме иногда «выдумывает» типовые фразы из субтитров. */
    private fun isHallucination(text: String): Boolean {
        val t = text.lowercase()
        return t.isBlank() || listOf(
            "субтитры", "продолжение следует", "спасибо за просмотр",
            "редактор субтитров", "подписывайтесь на канал"
        ).any { t.contains(it) }
    }

    /** Кнопка «Проверить» в настройках: ключ рабочий и сервис доступен. */
    fun testGroq() {
        val key = settings.groqKey.value
        if (key.isBlank()) {
            _groqStatus.value = "Сначала введите ключ."
            return
        }
        _groqStatus.value = "Проверяю…"
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { GroqClient.check(key) }
            _groqStatus.value = when (r) {
                is GroqClient.Result.Ok -> "Работает: ключ принят, сервис доступен."
                is GroqClient.Result.Fail -> when (r.reason) {
                    GroqClient.Reason.AUTH -> "Ключ не принят. Проверьте, что скопировали его целиком."
                    GroqClient.Reason.NETWORK -> "Нет связи с сервисом (${r.message}). Проверьте интернет."
                    GroqClient.Reason.BLOCKED -> "Доступ запрещён (${r.message}). Возможно, сервис недоступен в вашем регионе."
                    GroqClient.Reason.RATE_LIMIT -> "Лимит исчерпан (${r.message})."
                    GroqClient.Reason.OTHER -> "Ошибка (${r.message})."
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Vosk
    // ------------------------------------------------------------------

    private fun jsonField(json: String?, key: String): String =
        runCatching { JSONObject(json ?: "{}").optString(key) }.getOrDefault("")

    private val voskListener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (voskDone) return
            val part = jsonField(hypothesis, "partial")
            if (part.isNotBlank()) {
                voskFinishJob?.cancel() // человек продолжает говорить
                _home.update { it.copy(partial = (voskBuffer.toString() + part).trim()) }
            }
        }

        override fun onResult(hypothesis: String?) {
            if (voskDone) return
            val text = jsonField(hypothesis, "text")
            if (text.isBlank()) return
            voskBuffer.append(text).append(' ')
            _home.update { it.copy(partial = voskBuffer.toString().trim()) }
            // Ждём секунду: вдруг фраза продолжится после паузы («254 рубля … 55 копеек»).
            voskFinishJob?.cancel()
            voskFinishJob = viewModelScope.launch {
                delay(1000)
                finishVosk(null)
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            finishVosk(jsonField(hypothesis, "text"))
        }

        override fun onError(exception: Exception?) {
            if (voskDone) return
            voskDone = true
            voskFinishJob?.cancel()
            stopVosk()
            showError("Ошибка распознавания: ${exception?.message ?: "неизвестная"}")
        }

        override fun onTimeout() {
            finishVosk(null)
        }
    }

    /** Завершает запись и передаёт собранный текст на разбор. */
    private fun finishVosk(lastText: String?) {
        if (voskDone) return
        voskDone = true
        voskFinishJob?.cancel()
        if (!lastText.isNullOrBlank()) voskBuffer.append(lastText).append(' ')
        val text = voskBuffer.toString().trim()
        stopVosk()
        if (text.isBlank()) onNothingHeard() else handleText(text, "Vosk")
    }

    private fun stopVosk() {
        voskFinishJob?.cancel()
        voskService?.let { runCatching { it.cancel() }; runCatching { it.shutdown() } }
        voskService = null
        voskRecognizer?.let { runCatching { it.close() } }
        voskRecognizer = null
    }

    private fun startVosk() {
        if (models.state.value !is ModelState.Ready) {
            showError(
                "Модель распознавания не скачана. Откройте «Настройки» → «Распознавание речи» " +
                    "и нажмите «Скачать модель»."
            )
            return
        }
        val model = voskModel
        if (model != null) {
            beginVosk(model)
            return
        }
        _home.update {
            it.copy(
                status = Status.PROCESSING,
                partial = "",
                message = "Загружаю модель распознавания…",
                kind = MessageKind.INFO
            )
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Model(models.path) } }
                .onSuccess {
                    voskModel = it
                    beginVosk(it)
                }
                .onFailure {
                    showError(
                        "Не удалось загрузить модель: ${it.message}. " +
                            "Удалите её в настройках и скачайте заново."
                    )
                }
        }
    }

    private fun beginVosk(model: Model) {
        stopVosk()
        voskDone = false
        voskBuffer.setLength(0)
        try {
            val rec = Recognizer(model, 16000.0f)
            voskRecognizer = rec
            val svc = SpeechService(rec, 16000.0f)
            voskService = svc
            _home.update { it.copy(status = Status.LISTENING, partial = "") }
            if (!svc.startListening(voskListener, 20000)) {
                stopVosk()
                showError("Не удалось запустить запись. Попробуйте ещё раз.")
            }
        } catch (e: Exception) {
            stopVosk()
            showError("Ошибка запуска распознавания: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Google (встроенный SpeechRecognizer)
    // ------------------------------------------------------------------

    private fun startGoogle() {
        val ctx = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            showError(
                "На этом устройстве нет службы распознавания речи. " +
                    "Установите/включите «Голосовой ввод Google» или введите команду текстом."
            )
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        _home.update { it.copy(status = Status.LISTENING, partial = "") }
        r.startListening(intent)
    }

    private fun onNothingHeard() {
        _home.update {
            it.copy(
                status = Status.IDLE,
                partial = "",
                message = "Не расслышал. Нажмите на кнопку и повторите.",
                kind = MessageKind.INFO
            )
        }
    }

    private fun handleText(text: String, source: String = "") {
        _home.update { it.copy(status = Status.PROCESSING, heard = text, partial = "", source = source) }
        viewModelScope.launch {
            val reply = process(text)
            deliver(reply)
        }
    }

    private fun deliver(reply: Reply) {
        _home.update {
            it.copy(message = reply.text, kind = reply.kind, awaiting = pending != null)
        }
        val continueListening = reply.listenNext && settings.autoListen.value
        speak(reply.spoken, if (continueListening) 300L else 0L) {
            if (continueListening) {
                startListening()
            } else {
                _home.update { it.copy(status = Status.IDLE) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Озвучивание ответов
    // ------------------------------------------------------------------

    private fun speak(text: String, pauseMs: Long, onDone: () -> Unit) {
        val engine = tts
        if (!settings.speakReplies.value || engine == null || !ttsReady) {
            viewModelScope.launch {
                if (pauseMs > 0) delay(pauseMs)
                onDone()
            }
            return
        }
        _home.update { it.copy(status = Status.SPEAKING) }
        speechDone = onDone
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voicelog")
        if (result == TextToSpeech.ERROR) finishSpeech()
    }

    private fun finishSpeech() {
        // Вызывается из потока синтезатора — переходим в главный поток.
        viewModelScope.launch {
            val callback = speechDone
            speechDone = null
            callback?.invoke()
        }
    }

    // ------------------------------------------------------------------
    // Разбор фразы и диалог уточнений
    // ------------------------------------------------------------------

    private fun isCancel(text: String): Boolean {
        val words = VoiceParser.normalize(text).split(' ')
        return words.size <= 3 && words.any {
            it in setOf("отмена", "отмени", "отменить", "стоп", "хватит")
        }
    }

    private fun sectionNames(list: List<Section>) = list.joinToString(", ") { it.name.lowercase() }

    private fun valueQuestion(s: SectionRef): String =
        if (s.unit == "₽") "Какую сумму записать в «${s.name}»?"
        else "Сколько записать в «${s.name}»?"

    private suspend fun process(raw: String): Reply {
        val text = raw.trim()
        if (isCancel(text)) {
            pending = null
            return Reply("Отменено.", MessageKind.INFO)
        }
        val list = dao.sectionsOnce()
        val refs = list.map { it.toRef() }

        // Команда «Создай раздел …» работает всегда и прерывает любой уточняющий вопрос.
        val awaitingName = pending is Pending.NeedSectionName
        val create = SectionCommand.parse(if (awaitingName) "создай раздел $text" else text)
        if (create != null) return handleCreate(create, list, refs)

        if (list.isEmpty()) {
            return Reply(
                "Пока нет ни одного раздела. Скажите «Создай раздел …» или создайте его на вкладке «Записи».",
                MessageKind.ERROR
            )
        }

        return when (val p = pending) {
            is Pending.NeedSectionName -> {
                // Ответ не распознан как название — спросим ещё раз.
                Reply("Как назвать раздел? Скажите только название.", MessageKind.QUESTION, listenNext = true)
            }

            is Pending.NeedUnit -> {
                val unit = SectionCommand.mapUnit(text)
                val section = list.firstOrNull { it.id == p.sectionId }
                when {
                    section == null -> {
                        pending = null
                        Reply("Раздел «${p.name}» не найден.", MessageKind.ERROR)
                    }
                    unit == null -> Reply(
                        "Не понял единицу. Назовите: рубли, раз, штуки, минуты, часы, дни, километры " +
                            "или «без единицы».",
                        MessageKind.QUESTION,
                        listenNext = true
                    )
                    else -> {
                        dao.updateSection(section.copy(unit = unit))
                        pending = null
                        val shown = if (unit.isBlank()) "без единицы" else unit
                        Reply("Готово: в разделе «${section.name}» единица — $shown.", MessageKind.SAVED)
                    }
                }
            }

            null -> route(text, refs, list)

            is Pending.NeedType -> {
                val notes = SectionCommand.parseType(text)
                if (notes == null) {
                    Reply(
                        "Не понял. Раздел для чисел (суммы, количество) или для заметок (текст)?",
                        MessageKind.QUESTION,
                        listenNext = true
                    )
                } else {
                    handleCreate(p.cmd.copy(notes = notes), list, refs)
                }
            }

            is Pending.NeedNoteSection -> {
                val section = VoiceParser.findSection(text, p.candidates)
                if (section != null) {
                    saveNote(section, p.text)
                } else {
                    Reply(
                        "Не понял, в какой раздел. Доступны: ${p.candidates.joinToString(", ") { it.name.lowercase() }}.",
                        MessageKind.QUESTION,
                        listenNext = true
                    )
                }
            }

            is Pending.NeedSection -> {
                val section = VoiceParser.findSection(text, refs)
                if (section != null && section.isNotes) {
                    saveNote(section, p.raw.ifBlank { p.description })
                } else if (section != null) {
                    handleParse(VoiceParser.complete(section, p.description, p.cents), list)
                } else {
                    // Возможно, человек повторил команду целиком.
                    val full = VoiceParser.parse(text, refs)
                    if (full is ParseResult.Ready || full is ParseResult.NeedValue) {
                        handleParse(full, list)
                    } else {
                        Reply(
                            "Не понял, в какой раздел добавить. Доступны: ${sectionNames(list)}. Назовите раздел.",
                            MessageKind.QUESTION,
                            listenNext = true
                        )
                    }
                }
            }

            is Pending.NeedValue -> {
                val cents = VoiceParser.extractValue(text)
                if (cents == null) {
                    Reply("Не расслышал число. ${valueQuestion(p.section)}", MessageKind.QUESTION, listenNext = true)
                } else {
                    handleParse(ParseResult.Ready(p.section, p.description, cents), list)
                }
            }
        }
    }

    /** Определяет режим по первому слову («Заметка…», «Статистика…») и разбирает фразу. */
    private suspend fun route(text: String, refs: List<SectionRef>, list: List<Section>): Reply {
        val mode = NoteParser.detectMode(text)
        val noteRefs = refs.filter { it.isNotes }
        val numRefs = refs.filter { !it.isNotes }
        return when (mode.kind) {
            NoteParser.Kind.NOTES -> handleNote(NoteParser.parse(mode.rest, noteRefs))
            NoteParser.Kind.NUMBERS -> {
                if (numRefs.isEmpty()) {
                    Reply("Нет разделов для чисел. Скажите «Создай раздел …».", MessageKind.ERROR)
                } else {
                    numbers(mode.rest, numRefs, list)
                }
            }
            NoteParser.Kind.NONE -> {
                // Без префикса: если названа явно раздел-заметки — это заметка, иначе числа.
                val named = VoiceParser.findSection(text, refs)
                if (named != null && named.isNotes) {
                    handleNote(NoteParser.parse(text, noteRefs, forced = named))
                } else {
                    numbers(text, refs.filter { !it.isNotes }.ifEmpty { refs }, list)
                }
            }
        }
    }

    private suspend fun numbers(text: String, numRefs: List<SectionRef>, list: List<Section>): Reply {
        var result = VoiceParser.parse(text, numRefs)
        // Единственный раздел с числами — раздел можно не называть.
        if (result is ParseResult.NeedSection && result.candidates.isEmpty() && numRefs.size == 1) {
            result = VoiceParser.complete(numRefs[0], result.description, result.valueCents)
        }
        return handleParse(result, list, raw = text)
    }

    private suspend fun handleNote(r: NoteParser.Result): Reply = when (r) {
        is NoteParser.Result.Ready -> saveNote(r.section, r.text)
        NoteParser.Result.Empty -> Reply("Не расслышал текст заметки. Повторите.", MessageKind.INFO)
        NoteParser.Result.NoSections -> Reply(
            "Нет разделов для заметок. Скажите «Создай раздел Рестораны заметки».",
            MessageKind.ERROR
        )
        is NoteParser.Result.NeedSection -> {
            pending = Pending.NeedNoteSection(r.text, r.candidates)
            val names = r.candidates.joinToString(if (r.ambiguous) " или " else ", ") { "«${it.name}»" }
            Reply(
                if (r.ambiguous) "Заметка подходит к нескольким разделам: $names. В какой записать?"
                else "В какой раздел записать заметку? Доступны: $names.",
                MessageKind.QUESTION,
                listenNext = true
            )
        }
    }

    private suspend fun saveNote(section: SectionRef, text: String): Reply {
        pending = null
        dao.insertEntry(
            LogEntry(
                sectionId = section.id,
                description = text,
                valueCents = 0,
                timestamp = System.currentTimeMillis()
            )
        )
        return Reply(
            "Записал в «${section.name}»: $text",
            MessageKind.SAVED,
            spoken = "Записал заметку в ${section.name}"
        )
    }

    /** Создание раздела по голосовой команде. */
    private suspend fun handleCreate(cmd: SectionCommand.Create, list: List<Section>, refs: List<SectionRef>): Reply {
        pending = null
        if (cmd.name.isBlank()) {
            pending = Pending.NeedSectionName
            return Reply("Как назвать раздел?", MessageKind.QUESTION, listenNext = true)
        }
        list.firstOrNull { SectionCommand.sameName(it.name, cmd.name) }?.let {
            return Reply("Раздел «${it.name}» уже есть.", MessageKind.INFO)
        }
        if (cmd.notes == null) {
            pending = Pending.NeedType(cmd)
            return Reply(
                "Раздел «${cmd.name}» — для чисел (суммы, количество) или для заметок (текст)?",
                MessageKind.QUESTION,
                listenNext = true
            )
        }
        val notes = cmd.notes

        val id = dao.insertSection(
            Section(
                name = cmd.name,
                unit = if (notes) "" else cmd.unit ?: "",
                keywords = cmd.keywords.joinToString(","),
                askValue = !notes,
                type = (if (notes) SectionType.NOTES else SectionType.NUMBERS).name
            )
        )

        // Предупреждаем, если слова уже используются в другом разделе.
        val conflicts = SectionCommand.conflicts(cmd.name, cmd.keywords, refs)
        val warning = if (conflicts.isEmpty()) "" else
            "\nВнимание: " + conflicts.joinToString("; ") { (word, other) ->
                "«$word» уже есть в разделе «$other»"
            } + ". Если фраза подойдёт обоим, приложение спросит, куда записать."

        val wordsInfo = if (cmd.keywords.isEmpty()) "" else " Слова: ${cmd.keywords.joinToString(", ")}."
        if (notes) {
            return Reply(
                "Создан раздел заметок «${cmd.name}».$wordsInfo$warning\n" +
                    "Скажите: «Заметка. …» и название раздела в тексте.",
                MessageKind.SAVED,
                spoken = "Создал раздел заметок ${cmd.name}"
            )
        }
        return if (cmd.unit != null) {
            val shown = if (cmd.unit.isBlank()) "без единицы" else cmd.unit
            Reply(
                "Создан раздел «${cmd.name}», единица: $shown.$wordsInfo$warning",
                MessageKind.SAVED,
                spoken = "Создал раздел ${cmd.name}"
            )
        } else {
            pending = Pending.NeedUnit(id, cmd.name)
            Reply(
                "Создан раздел «${cmd.name}».$wordsInfo$warning\n" +
                    "В чём считаем: рубли, раз, штуки, минуты, часы, дни? Или скажите «без единицы».",
                MessageKind.QUESTION,
                spoken = "Создал раздел ${cmd.name}. В чём считаем: рубли, раз, штуки, минуты, часы, дни?",
                listenNext = true
            )
        }
    }

    private suspend fun handleParse(result: ParseResult, list: List<Section>, raw: String = ""): Reply = when (result) {
        is ParseResult.Empty ->
            Reply("Не расслышал команду. Попробуйте ещё раз.", MessageKind.INFO)

        is ParseResult.NeedSection -> {
            pending = Pending.NeedSection(result.description, result.valueCents, raw)
            val what = if (result.description.isBlank()) "" else " «${result.description}»"
            if (result.candidates.isNotEmpty()) {
                // Одинаковые слова распознавания у нескольких разделов — не угадываем.
                val names = result.candidates.joinToString(" или ") { "«${it.name}»" }
                Reply(
                    "Фраза подходит к нескольким разделам: $names. В какой добавить$what?",
                    MessageKind.QUESTION,
                    spoken = "Фраза подходит к нескольким разделам: " +
                        result.candidates.joinToString(" или ") { it.name } + ". В какой добавить?",
                    listenNext = true
                )
            } else {
                Reply(
                    "В какой раздел добавить$what? Доступны: ${sectionNames(list)}.",
                    MessageKind.QUESTION,
                    listenNext = true
                )
            }
        }

        is ParseResult.NeedValue -> {
            pending = Pending.NeedValue(result.section, result.description)
            Reply(valueQuestion(result.section), MessageKind.QUESTION, listenNext = true)
        }

        is ParseResult.Ready -> {
            pending = null
            dao.insertEntry(
                LogEntry(
                    sectionId = result.section.id,
                    description = result.description,
                    valueCents = result.valueCents,
                    timestamp = System.currentTimeMillis()
                )
            )
            val value = Fmt.value(result.valueCents, result.section.unit)
            val what = if (result.description.isBlank()) "" else "${result.description} — "
            Reply(
                text = "Добавлено в «${result.section.name}»: $what$value",
                kind = MessageKind.SAVED,
                spoken = "Добавил в ${result.section.name}. " +
                    (if (result.description.isBlank()) "" else "${result.description}, ") +
                    Fmt.spoken(result.valueCents, result.section.unit)
            )
        }
    }

    override fun onCleared() {
        stopCapture()
        stopVosk()
        voskModel?.let { runCatching { it.close() } }
        voskModel = null
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onCleared()
    }
}
