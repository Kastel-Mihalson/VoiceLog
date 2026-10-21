package com.voicelog.app.voice

import java.math.BigDecimal
import java.math.RoundingMode

/** Раздел в том виде, в каком его видит разборщик (без зависимости от базы данных). */
data class SectionRef(
    val id: Long,
    val name: String,
    val unit: String,
    val keywords: String,
    val askValue: Boolean
)

sealed interface ParseResult {
    /** Всё понятно, можно сохранять. valueCents — сумма/количество в сотых долях. */
    data class Ready(val section: SectionRef, val description: String, val valueCents: Long) : ParseResult

    /**
     * Не удалось определить раздел. Если [candidates] не пуст — фраза одинаково подходит
     * к нескольким разделам (совпали слова распознавания), и нужно спросить, к какому.
     */
    data class NeedSection(
        val description: String,
        val valueCents: Long?,
        val candidates: List<SectionRef> = emptyList()
    ) : ParseResult

    /** Раздел понятен, но нет суммы/количества. */
    data class NeedValue(val section: SectionRef, val description: String) : ParseResult

    /** Пустая фраза. */
    data object Empty : ParseResult
}

/**
 * Разбор фразы вроде
 * «Добавь в раздел траты покупка чая стоимостью 254 рубля 55 копеек».
 *
 * Работает полностью офлайн, на правилах:
 *  1. числа словами превращаются в цифры;
 *  2. ищется сумма/количество;
 *  3. ищется раздел (по названию или ключевым словам, с учётом окончаний);
 *  4. всё, что осталось, — описание записи.
 */
object VoiceParser {

    private val NUM = """\d+(?:\.\d+)?"""
    private val HUNDRED = BigDecimal(100)

    // «254 рубля 55 копеек», «254 ₽», «254 р 55 к», «254 рубля 55» (в конце фразы)
    private val RUB = Regex(
        """($NUM)\s*(?:руб\p{L}*|₽|р(?=\s|\z))""" +
            """(?:\s+(?:и\s+)?(?:(\d{1,2})\s*(?:коп\p{L}*|к(?=\s|\z))|(\d{1,2})\s*\z))?"""
    )

    // «55 копеек»
    private val KOP = Regex("""($NUM)\s*коп\p{L}*""")

    // «за 300», «стоимостью 89.90», «на сумму 500»
    private val HINT = Regex(
        """(?<![\p{L}\p{N}])(?:за|на сумму|стоимостью|ценой|цена|стоил\p{L}*|сумма|сумму)\s+($NUM)"""
    )

    private val ANY = Regex(NUM)

    private val LEAD = setOf(
        "пожалуйста", "добавь", "добавить", "запиши", "записать", "внеси", "внести",
        "занеси", "отметь", "потратил", "потратила", "купил", "купила",
        "сделал", "сделала",
        "в", "во", "на", "за", "по", "и", "что", "это", "мне", "еще",
        "раздел", "раздела", "разделе"
    )

    private val TRAIL = setOf(
        "за", "на", "в", "по", "и", "стоимостью", "стоимость", "ценой", "цена", "цене",
        "сумму", "суммой", "сумма", "стоил", "стоила", "стоило", "обошлось", "обошелся",
        "раз", "штук", "штуки", "штука"
    )

    // ---------- публичный API ----------

    /** Приводит фразу к единому виду: нижний регистр, без знаков, числа цифрами. */
    fun normalize(raw: String): String {
        var s = raw.lowercase().replace('ё', 'е')
        s = s.replace("₽", " ₽ ")
        s = s.replace(Regex("(?<=\\d),(?=\\d)"), ".")
        s = s.replace(Regex("[^\\p{L}\\p{N}.₽ ]"), " ")
        s = s.replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        // «1 500» / «12 500 000» (пробел как разделитель тысяч) -> «1500» / «12500000»
        s = s.replace(Regex("(?<![\\d.])(\\d{1,3})((?: \\d{3})+)(?![\\d])")) {
            it.groupValues[1] + it.groupValues[2].replace(" ", "")
        }
        // «1.500» (точка как разделитель тысяч) -> «1500»
        s = s.replace(Regex("(?<![\\d.])(\\d{1,3})\\.(\\d{3})(?![\\d.])"), "$1$2")
        return RussianNumbers.replaceWords(s)
    }

    fun parse(raw: String, sections: List<SectionRef>): ParseResult {
        val text = normalize(raw)
        if (text.isBlank()) return ParseResult.Empty

        val amount = findAmount(text)
        val pick = pick(text, sections)

        val chars = StringBuilder(text)
        fun blank(r: IntRange?) {
            if (r != null) for (i in r) chars.setCharAt(i, ' ')
        }
        blank(amount?.range)
        if (pick is Pick.Found) blank(pick.removal)
        val description = clean(chars.toString())

        return when (pick) {
            is Pick.Found -> complete(pick.section, description, amount?.cents)
            is Pick.Ambiguous -> ParseResult.NeedSection(description, amount?.cents, pick.sections)
            else -> ParseResult.NeedSection(description, amount?.cents)
        }
    }

    /** Решает, готова ли запись, когда раздел уже известен. */
    fun complete(section: SectionRef, description: String, cents: Long?): ParseResult = when {
        cents != null -> ParseResult.Ready(section, description, cents)
        section.askValue -> ParseResult.NeedValue(section, description)
        else -> ParseResult.Ready(section, description, 100L) // «1» — просто факт
    }

    /** Ищет в короткой реплике название раздела (для ответа на уточняющий вопрос). */
    fun findSection(raw: String, sections: List<SectionRef>): SectionRef? {
        val p = pick(normalize(raw), sections)
        return (p as? Pick.Found)?.section
    }

    /** Ищет в короткой реплике число/сумму (для ответа на уточняющий вопрос). */
    fun extractValue(raw: String): Long? = findAmount(normalize(raw))?.cents

    // ---------- сумма ----------

    private class Amount(val cents: Long, val range: IntRange)

    private fun toCents(num: String): Long =
        BigDecimal(num).multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).toLong()

    private fun findAmount(s: String): Amount? {
        RUB.find(s)?.let { m ->
            val rub = BigDecimal(m.groupValues[1])
            val kopText = m.groupValues[2].ifEmpty { m.groupValues[3] }
            val kop = if (kopText.isEmpty()) BigDecimal.ZERO else BigDecimal(kopText)
            val cents = rub.multiply(HUNDRED).add(kop).setScale(0, RoundingMode.HALF_UP).toLong()
            return Amount(cents, m.range)
        }
        KOP.find(s)?.let { m ->
            val cents = BigDecimal(m.groupValues[1]).setScale(0, RoundingMode.HALF_UP).toLong()
            return Amount(cents, m.range)
        }
        HINT.find(s)?.let { m ->
            val g = m.groups[1]!!
            return Amount(toCents(g.value), g.range)
        }
        val last = ANY.findAll(s).lastOrNull() ?: return null
        return Amount(toCents(last.value), last.range)
    }

    // ---------- раздел ----------

    /**
     * score — «вес» совпадения: 100 — явное «в раздел X», 20 — упомянуто название раздела,
     * 10 — сработало слово-подсказка. len — длина слова (выбирает лучшее слово внутри
     * одного раздела, но НЕ участвует в сравнении разделов между собой).
     */
    private class Hit(val section: SectionRef, val score: Int, val len: Int, val removal: IntRange?)

    private sealed interface Pick {
        class Found(val section: SectionRef, val removal: IntRange?) : Pick
        data object None : Pick

        /** Несколько разделов подошли одинаково хорошо — нужно спросить. */
        class Ambiguous(val sections: List<SectionRef>) : Pick
    }

    private fun stem(word: String): String = when {
        word.length >= 7 -> word.dropLast(2)
        word.length >= 4 -> word.dropLast(1)
        else -> word
    }

    private fun normalizeAlias(raw: String): String =
        raw.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim()

    private fun aliasPattern(alias: String): String =
        alias.split(' ').filter { it.isNotBlank() }
            .joinToString("\\s+") { Regex.escape(stem(it)) + "\\p{L}*" }

    private fun pick(text: String, sections: List<SectionRef>): Pick {
        val hits = ArrayList<Hit>()
        for (sec in sections) {
            var best: Hit? = null
            val aliases = ArrayList<Pair<String, Boolean>>()
            aliases.add(sec.name to true)
            sec.keywords.split(',', ';').forEach { aliases.add(it to false) }

            for ((rawAlias, isName) in aliases) {
                val alias = normalizeAlias(rawAlias)
                if (alias.isBlank()) continue
                val body = aliasPattern(alias)

                val explicit = Regex(
                    "(?<![\\p{L}\\p{N}])(?:в|во)\\s+(?:(?:раздел|категори|список)\\p{L}*\\s+)?$body"
                ).find(text)
                val plain = Regex("(?<![\\p{L}\\p{N}])$body").find(text)

                val hit: Hit? = when {
                    explicit != null -> Hit(sec, 100, alias.length, explicit.range)
                    plain != null -> Hit(
                        sec,
                        if (isName) 20 else 10,
                        alias.length,
                        if (isName) plain.range else null
                    )
                    else -> null
                }
                if (hit != null && (best == null || hit.score > best.score ||
                        (hit.score == best.score && hit.len > best.len))
                ) {
                    best = hit
                }
            }
            if (best != null) hits.add(best)
        }
        if (hits.isEmpty()) return Pick.None
        val top = hits.maxOf { it.score }
        val leaders = hits.filter { it.score == top }
        // Два раздела подошли одинаково (например, одно и то же слово) — не угадываем, а спрашиваем.
        if (leaders.size > 1) return Pick.Ambiguous(leaders.map { it.section })
        return Pick.Found(leaders[0].section, leaders[0].removal)
    }

    // ---------- описание ----------

    private fun clean(s: String): String {
        val t = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (t.isNotEmpty() && t.first() in LEAD) t.removeAt(0)
        while (t.isNotEmpty() && t.last() in TRAIL) t.removeAt(t.lastIndex)
        return t.joinToString(" ").replaceFirstChar { it.uppercase() }
    }
}
