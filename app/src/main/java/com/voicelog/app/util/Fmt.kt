package com.voicelog.app.util

import com.voicelog.app.data.LogEntry
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Форматирование сумм, дат и времени. */
object Fmt {

    val ru: Locale = Locale.forLanguageTag("ru-RU")

    /** 25455 + «₽» -> «254,55 ₽»; 3000 + «раз» -> «30 раз». */
    fun value(cents: Long, unit: String): String {
        val pattern = if (unit == "₽") "#,##0.00" else "#,##0.##"
        val s = DecimalFormat(pattern, DecimalFormatSymbols(ru)).format(BigDecimal(cents).movePointLeft(2))
        return if (unit.isBlank()) s else "$s $unit"
    }

    fun plural(n: Long, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..19 -> many
            m10 == 1L -> one
            m10 in 2..4 -> few
            else -> many
        }
    }

    /** Вариант для озвучивания: «254 рубля 55 копеек». */
    fun spoken(cents: Long, unit: String): String {
        if (unit == "₽") {
            val rub = cents / 100
            val kop = cents % 100
            val r = "$rub ${plural(rub, "рубль", "рубля", "рублей")}"
            return if (kop == 0L) r else "$r $kop ${plural(kop, "копейка", "копейки", "копеек")}"
        }
        return value(cents, unit)
    }

    /** Разбор числа, введённого вручную: «254,55» -> 25455. */
    fun parseCents(text: String): Long? = runCatching {
        BigDecimal(text.trim().replace(',', '.'))
            .multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }.getOrNull()

    fun localDate(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

    fun time(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))

    fun dayTitle(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ru))
                .replaceFirstChar { it.uppercase() }
        }
    }
}

fun LogEntry.localDate(): LocalDate = Fmt.localDate(timestamp)
