package com.voicelog.app.voice

/**
 * Превращает числа, записанные словами, в цифры:
 * «двести пятьдесят четыре рубля» -> «254 рубля», «две тысячи пятьсот» -> «2500».
 */
object RussianNumbers {

    private val small: Map<String, Int> = mapOf(
        "ноль" to 0, "нуль" to 0,
        "один" to 1, "одна" to 1, "одно" to 1, "одну" to 1,
        "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14,
        "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
        "девятнадцать" to 19,
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90,
        "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400, "пятьсот" to 500,
        "шестьсот" to 600, "семьсот" to 700, "восемьсот" to 800, "девятьсот" to 900
    )

    /** Ожидает уже нормализованный текст: нижний регистр, слова через один пробел. */
    fun replaceWords(text: String): String {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var total = 0L
        var current = 0L
        var active = false

        fun flush() {
            if (active) {
                out.add((total + current).toString())
                total = 0
                current = 0
                active = false
            }
        }

        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val n = small[t]
            when {
                n != null -> {
                    current += n
                    active = true
                }
                t.startsWith("тысяч") -> {
                    total += (if (current == 0L) 1L else current) * 1000L
                    current = 0
                    active = true
                }
                t.startsWith("миллион") -> {
                    total += (if (current == 0L) 1L else current) * 1_000_000L
                    current = 0
                    active = true
                }
                t.length <= 12 && t.all { it.isDigit() } &&
                    i + 1 < tokens.size && tokens[i + 1].startsWith("тысяч") -> {
                    // «5 тысяч» -> 5000
                    flush()
                    out.add((t.toLong() * 1000L).toString())
                    i++
                }
                else -> {
                    flush()
                    out.add(t)
                }
            }
            i++
        }
        flush()
        return out.joinToString(" ")
    }
}
