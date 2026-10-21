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
import com.voicelog.app.data.SettingsStore
import com.voicelog.app.data.SpeechEngine
import com.voicelog.app.util.Fmt
import com.voicelog.app.voice.ModelState
import com.voicelog.app.voice.ParseResult
import com.voicelog.app.voice.SectionRef
import com.voicelog.app.voice.VoiceParser
import com.voicelog.app.voice.VoskModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val awaiting: Boolean = false
)

internal data class Reply(
    val text: String,
    val kind: MessageKind,
    val spoken: String = text,
    val listenNext: Boolean = false
)

internal sealed interface Pending {
    data class NeedSection(val description: String, val cents: Long?) : Pending
    data class NeedValue(val section: SectionRef, val description: String) : Pending
}

private fun Section.toRef() = SectionRef(id, name, unit, keywords, askValue)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).dao()
    val settings = SettingsStore(app)

    val sections: StateFlow<List<Section>> =
        dao.sections().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allEntries: StateFlow<List<LogEntry>> =
        dao.allEntries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    fun addSection(name: String, unit: String, keywords: String, askValue: Boolean) {
        viewModelScope.launch {
            dao.insertSection(Section(name = name, unit = unit, keywords = keywords, askValue = askValue))
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
                settings.setEngine(SpeechEngine.VOSK)
                preloadModel()
            }
        }
    }

    fun deleteModel() {
        stopVosk()
        voskModel?.let { runCatching { it.close() } }
        voskModel = null
        models.delete()
        settings.setEngine(SpeechEngine.GOOGLE)
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
            if (text.isBlank()) onNothingHeard() else handleText(text)
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
        if (settings.engine.value == SpeechEngine.VOSK) startVosk() else startGoogle()
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
        if (text.isBlank()) onNothingHeard() else handleText(text)
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

    private fun handleText(text: String) {
        _home.update { it.copy(status = Status.PROCESSING, heard = text, partial = "") }
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
        if (list.isEmpty()) {
            return Reply("Пока нет ни одного раздела. Создайте его в меню «Записи».", MessageKind.ERROR)
        }
        val refs = list.map { it.toRef() }

        return when (val p = pending) {
            null -> handleParse(VoiceParser.parse(text, refs), list)

            is Pending.NeedSection -> {
                val section = VoiceParser.findSection(text, refs)
                if (section != null) {
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

    private suspend fun handleParse(result: ParseResult, list: List<Section>): Reply = when (result) {
        is ParseResult.Empty ->
            Reply("Не расслышал команду. Попробуйте ещё раз.", MessageKind.INFO)

        is ParseResult.NeedSection -> {
            pending = Pending.NeedSection(result.description, result.valueCents)
            val what = if (result.description.isBlank()) "" else " «${result.description}»"
            Reply(
                "В какой раздел добавить$what? Доступны: ${sectionNames(list)}.",
                MessageKind.QUESTION,
                listenNext = true
            )
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
