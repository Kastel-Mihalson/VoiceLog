package com.voicelog.app.voice

/**
 * Команда «Создай раздел …» и вспомогательные функции для разделов:
 * разбор единицы измерения и поиск пересечений слов распознавания.
 *
 * Примеры:
 *  «Создай раздел Игры»
 *  «Создай раздел Игры единица минуты слова играл партия»
 *  «Новый раздел Спорт, единица дни»
 */
object SectionCommand {

    /** Разобранная команда создания. unit == null — единица не названа (или не поняли). */
    data class Create(val name: String, val unit: String?, val keywords: List<String>)

    private val TRIGGER = Regex(
        "(?<![\\p{L}\\p{N}])" +
            "(?:(?:создай|создать|добавь|добавить|заведи|завести|сделай|сделать)\\s+(?:новый\\s+)?|новый\\s+)" +
            "раздел(?![\\p{L}\\p{N}])\\s*" +
            "(?:(?:под\\s+названием|с\\s+названием|называется|назови)\\s+)?(.*)$"
    )
    private val UNIT_MARK = Regex(
        "(?<![\\p{L}\\p{N}])единиц\\p{L}*(?:\\s+измерения)?(?![\\p{L}\\p{N}])\\s*"
    )
    private val WORDS_MARK = Regex(
        "(?<![\\p{L}\\p{N}])(?:ключев\\p{L}*\\s+)?слов\\p{L}*(?![\\p{L}\\p{N}])\\s*"
    )

    private val NAME_FILLER = setOf("пожалуйста", "и", "тоже")

    /** Возвращает null, если фраза — не команда создания раздела. */
    fun parse(raw: String): Create? {
        val text = VoiceParser.normalize(raw)
        val rest = TRIGGER.find(text)?.groupValues?.get(1) ?: return null

        val um = UNIT_MARK.find(rest)
        val wm = WORDS_MARK.find(rest)

        val cut = listOfNotNull(um?.range?.first, wm?.range?.first).minOrNull() ?: rest.length
        val nameWords = rest.substring(0, cut).trim().split(' ')
            .filter { it.isNotBlank() && it !in NAME_FILLER }
        val name = nameWords.joinToString(" ").take(40).trim()
            .replaceFirstChar { it.uppercase() }

        var unit: String? = null
        if (um != null) {
            val start = um.range.last + 1
            val end = if (wm != null && wm.range.first > um.range.first) wm.range.first else rest.length
            if (start <= end) unit = mapUnit(rest.substring(start, end))
        }

        var keywords = emptyList<String>()
        if (wm != null) {
            val start = wm.range.last + 1
            val end = if (um != null && um.range.first > wm.range.first) um.range.first else rest.length
            if (start <= end) {
                keywords = rest.substring(start, end).trim().split(' ')
                    .filter { it.length >= 3 && it !in NAME_FILLER }
                    .distinct()
            }
        }
        return Create(name, unit, keywords)
    }

    /**
     * Единица измерения из слов: «рубли» → «₽», «минуты» → «мин», «дни» → «дн.».
     * Пустая строка — «без единицы». null — не поняли.
     */
    fun mapUnit(raw: String): String? {
        if (raw.contains('₽')) return "₽"
        val t = norm(raw)
        if (t.isBlank()) return null
        val words = t.split(' ')
        fun has(vararg prefixes: String) = words.any { w -> prefixes.any { w.startsWith(it) } }
        return when {
            has("без", "никак") -> ""
            has("рубл", "руб", "деньг") -> "₽"
            has("миллилитр", "мл") -> "мл"
            has("килограмм", "кг") -> "кг"
            has("грамм") -> "г"
            has("километр", "км") -> "км"
            has("метр") -> "м"
            has("литр") -> "л"
            has("штук", "шт") -> "шт"
            has("раз") -> "раз"
            has("минут", "мин") -> "мин"
            has("час") -> "ч"
            has("секунд", "сек") -> "сек"
            has("день", "дня", "дни", "дней", "дн", "сутк") -> "дн."
            has("страниц", "стр") -> "стр"
            has("порци") -> "порц."
            has("калори", "ккал") -> "ккал"
            has("шаг") -> "шагов"
            has("подход") -> "подх."
            has("стакан") -> "стак."
            else -> null
        }
    }

    // ---------- пересечение слов распознавания между разделами ----------

    private fun norm(s: String): String =
        s.lowercase().replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun stem(w: String): String = when {
        w.length >= 7 -> w.dropLast(2)
        w.length >= 4 -> w.dropLast(1)
        else -> w
    }

    private fun key(phrase: String): String = norm(phrase).split(' ').filter { it.isNotBlank() }
        .joinToString(" ") { stem(it) }

    /** Совпадают ли два названия/слова (с учётом окончаний): «траты» = «трата». */
    fun sameName(a: String, b: String): Boolean {
        val x = key(a)
        return x.isNotEmpty() && x == key(b)
    }

    /**
     * Какие слова (название раздела или слова-подсказки) уже используются в других разделах.
     * Возвращает пары «слово → название раздела». Пустой список — конфликтов нет.
     */
    fun conflicts(name: String, keywords: List<String>, others: List<SectionRef>): List<Pair<String, String>> {
        val mine = (listOf(name) + keywords).map { it.trim() }.filter { it.isNotBlank() }
        val result = LinkedHashMap<String, Pair<String, String>>()
        for (other in others) {
            val theirs = listOf(other.name) + other.keywords.split(',', ';')
            for (m in mine) {
                if (theirs.any { it.isNotBlank() && sameName(m, it) }) {
                    result[key(m) + "|" + other.id] = m to other.name
                }
            }
        }
        return result.values.toList()
    }
}
