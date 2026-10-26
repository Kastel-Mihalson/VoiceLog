package com.voicelog.app.voice

import kotlin.math.max

/**
 * Разбор голосовых заметок и определение режима по первому слову фразы:
 *  «Заметка. Рагу — хороший ресторан, около Мадагаскара» → заметка;
 *  «Статистика. Оплатил покупку телефона 22000 рублей» → запись с суммой.
 *
 * В заметке текст сохраняется дословно (числа и слова не преобразуются),
 * убираются только служебные слова в начале.
 */
object NoteParser {

    enum class Kind { NOTES, NUMBERS, NONE }

    /** rest — фраза без слова-префикса (в исходном написании). */
    data class Mode(val kind: Kind, val rest: String)

    sealed interface Result {
        data class Ready(val section: SectionRef, val text: String) : Result

        /**
         * Раздел не определён. ambiguous = true — подошло несколько разделов сразу,
         * false — раздел не назван вовсе. candidates — из каких спрашивать.
         */
        data class NeedSection(
            val text: String,
            val candidates: List<SectionRef>,
            val ambiguous: Boolean
        ) : Result

        /** Нет ни одного раздела-заметок. */
        data object NoSections : Result

        /** После служебных слов ничего не осталось. */
        data object Empty : Result
    }

    private const val SEP = "[\\s.,:;!?—–-]*"

    // «Записка» — только точные формы, чтобы не путать с командой «запиши».
    private val NOTES_PREFIX = Regex(
        "^\\s*(?:заметк\\p{L}*|записк(?:а|у|и|е|ой))(?![\\p{L}\\p{N}])$SEP"
    )
    private val NUMBERS_PREFIX = Regex("^\\s*статистик\\p{L}*(?![\\p{L}\\p{N}])$SEP")
    private val LEAD = Regex(
        "^$SEP(?:пожалуйста$SEP)?(?:(?:добавь|добавить|запиши|записать|внеси|внести|занеси|занести)\\s+)?"
    )

    /** Нижний регистр и «ё» → «е» без изменения длины строки (позиции совпадают с исходной). */
    private fun fold(s: String) = s.lowercase().replace('ё', 'е')

    fun detectMode(raw: String): Mode {
        val low = fold(raw)
        NOTES_PREFIX.find(low)?.let { return Mode(Kind.NOTES, raw.substring(it.range.last + 1)) }
        NUMBERS_PREFIX.find(low)?.let { return Mode(Kind.NUMBERS, raw.substring(it.range.last + 1)) }
        return Mode(Kind.NONE, raw)
    }

    /**
     * Разбирает заметку. notes — разделы-заметки. forced — раздел, уже определённый
     * по смыслу фразы (когда префикса «Заметка» не было).
     */
    fun parse(raw: String, notes: List<SectionRef>, forced: SectionRef? = null): Result {
        if (notes.isEmpty() && forced == null) return Result.NoSections

        var text = raw.trim()
        LEAD.find(fold(text))?.let { text = text.substring(it.range.last + 1) }

        var section: SectionRef? = forced
        leadingSection(text, if (forced != null) listOf(forced) else notes)?.let { (sec, end) ->
            section = sec
            text = text.substring(end)
        }
        val clean = tidy(text)
        if (clean.isBlank()) return Result.Empty

        section?.let { return Result.Ready(it, clean) }

        val found = mentioned(clean, notes)
        return when {
            found.size == 1 -> Result.Ready(found[0], clean)
            found.size > 1 -> Result.NeedSection(clean, found, ambiguous = true)
            notes.size == 1 -> Result.Ready(notes[0], clean)
            else -> Result.NeedSection(clean, notes, ambiguous = false)
        }
    }

    /** Убирает знаки в начале и делает первую букву заглавной. */
    private fun tidy(s: String): String {
        val t = s.trim().trimStart { it in ".,:;!?—–- " }.trim()
        return t.replaceFirstChar { it.uppercase() }
    }

    private fun aliasesOf(sec: SectionRef): List<Pair<String, Int>> =
        listOf(sec.name to 20) + sec.keywords.split(',', ';').map { it to 10 }

    /** «в рестораны …» / «в раздел рестораны …» в самом начале: раздел и позиция конца фразы. */
    private fun leadingSection(text: String, notes: List<SectionRef>): Pair<SectionRef, Int>? {
        val low = fold(text)
        for (sec in notes) {
            for ((rawAlias, _) in aliasesOf(sec)) {
                val alias = VoiceParser.normalizeAlias(rawAlias)
                if (alias.isBlank()) continue
                val re = Regex(
                    "^(?:в|во)\\s+(?:(?:раздел|категори|список)\\p{L}*\\s+)?" +
                        VoiceParser.aliasPattern(alias) + "(?![\\p{L}\\p{N}])$SEP"
                )
                re.find(low)?.let { return sec to it.range.last + 1 }
            }
        }
        return null
    }

    /** Разделы, названия или слова-подсказки которых встречаются в тексте (лучшие по весу). */
    private fun mentioned(text: String, notes: List<SectionRef>): List<SectionRef> {
        val low = fold(text)
        val hits = ArrayList<Pair<SectionRef, Int>>()
        for (sec in notes) {
            var score = 0
            for ((rawAlias, tier) in aliasesOf(sec)) {
                val alias = VoiceParser.normalizeAlias(rawAlias)
                if (alias.isBlank()) continue
                val re = Regex("(?<![\\p{L}\\p{N}])" + VoiceParser.aliasPattern(alias))
                if (re.containsMatchIn(low)) score = max(score, tier)
            }
            if (score > 0) hits.add(sec to score)
        }
        if (hits.isEmpty()) return emptyList()
        val top = hits.maxOf { it.second }
        return hits.filter { it.second == top }.map { it.first }
    }
}
