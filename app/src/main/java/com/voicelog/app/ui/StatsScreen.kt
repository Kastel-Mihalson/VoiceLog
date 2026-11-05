@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicelog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicelog.app.MainViewModel
import com.voicelog.app.data.LogEntry
import com.voicelog.app.data.Section
import com.voicelog.app.data.isNotes
import com.voicelog.app.util.Fmt
import com.voicelog.app.util.localDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Период, за который показывается статистика. */
private enum class Period(val title: String) {
    DAY("День"), WEEK("Неделя"), MONTH("Месяц"), YEAR("Год")
}

private data class Bucket(val label: String, val value: Long)

private fun LocalDate.mondayOfWeek(): LocalDate =
    with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** Границы периода (включительно). */
private fun periodRange(p: Period, today: LocalDate): Pair<LocalDate, LocalDate> = when (p) {
    Period.DAY -> today to today
    Period.WEEK -> today.mondayOfWeek() to today
    Period.MONTH -> today.withDayOfMonth(1) to today
    Period.YEAR -> today.withDayOfYear(1) to today
}

/** Такой же по длине предыдущий период — для сравнения. */
private fun previousRange(p: Period, today: LocalDate): Pair<LocalDate, LocalDate> = when (p) {
    Period.DAY -> today.minusDays(1) to today.minusDays(1)
    Period.WEEK -> {
        val start = today.mondayOfWeek().minusWeeks(1)
        start to start.plusDays(6)
    }
    Period.MONTH -> {
        val start = today.withDayOfMonth(1).minusMonths(1)
        start to start.with(TemporalAdjusters.lastDayOfMonth())
    }
    Period.YEAR -> {
        val start = today.withDayOfYear(1).minusYears(1)
        start to start.with(TemporalAdjusters.lastDayOfYear())
    }
}

/** Столбцы графика: срезы внутри выбранного периода. */
private fun buckets(p: Period, today: LocalDate, sum: (LocalDate, LocalDate) -> Long): List<Bucket> =
    when (p) {
        Period.DAY -> (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            Bucket(d.dayOfMonth.toString(), sum(d, d))
        }
        Period.WEEK -> (5 downTo 0).map { back ->
            val start = today.mondayOfWeek().minusWeeks(back.toLong())
            Bucket(start.format(DateTimeFormatter.ofPattern("d.MM")), sum(start, start.plusDays(6)))
        }
        Period.MONTH -> (5 downTo 0).map { back ->
            val start = today.withDayOfMonth(1).minusMonths(back.toLong())
            val end = start.with(TemporalAdjusters.lastDayOfMonth())
            Bucket(start.format(DateTimeFormatter.ofPattern("LLL", Fmt.ru)), sum(start, end))
        }
        Period.YEAR -> (1..12).map { m ->
            val start = today.withDayOfMonth(1).withMonth(m)
            val end = start.with(TemporalAdjusters.lastDayOfMonth())
            Bucket(start.format(DateTimeFormatter.ofPattern("LLL", Fmt.ru)), sum(start, end))
        }
    }

private fun List<LogEntry>.sumIn(range: Pair<LocalDate, LocalDate>): Long =
    filter { !it.localDate().isBefore(range.first) && !it.localDate().isAfter(range.second) }
        .sumOf { it.valueCents }

/** Страница «Статистика»: итог за период, динамика и разбивка по разделам. */
@Composable
fun StatsScreen(vm: MainViewModel, onOpenSection: (Long) -> Unit) {
    val sections by vm.sections.collectAsState()
    val entries by vm.allEntries.collectAsState()
    val actions = rememberSectionActions(notes = false)
    val numeric = remember(sections) { sections.filter { !it.isNotes } }
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }

    val today = remember { LocalDate.now() }
    val units = remember(sections) { sections.associate { it.id to it.unit } }
    val range = remember(period) { periodRange(period, today) }
    val prevRange = remember(period) { previousRange(period, today) }

    // Итоги за период и за предыдущий период, в разрезе единиц измерения.
    val totals = remember(entries, period) {
        entries.filter { units.containsKey(it.sectionId) }
            .groupBy { units[it.sectionId] ?: "" }
            .mapValues { (_, list) -> list.sumIn(range) to list.sumIn(prevRange) }
    }
    // Для графика берём рубли, если они есть, иначе первую доступную единицу.
    val chartUnit = remember(totals) {
        totals.keys.firstOrNull { it == "₽" } ?: totals.keys.firstOrNull() ?: ""
    }
    val chartSource = remember(entries, chartUnit, period) {
        entries.filter { units[it.sectionId] == chartUnit }
    }
    val bars = remember(chartSource, period, chartUnit) {
        buckets(period, today) { from, to -> chartSource.sumIn(from to to) }
    }
    val periodTotal = totals[chartUnit]?.first ?: 0L
    val prevTotal = totals[chartUnit]?.second ?: 0L

    val sectionTotals = remember(entries, period) {
        entries.groupBy { it.sectionId }.mapValues { (_, list) -> list.sumIn(range) }
    }
    val maxSectionTotal = remember(sectionTotals) { sectionTotals.values.maxOrNull() ?: 0L }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Статистика") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions::create,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Новый раздел") }
            )
        }
    ) { inner ->
        if (numeric.isEmpty()) {
            Text(
                "Разделов статистики пока нет.\nНажмите «Новый раздел» или скажите: «Создай раздел Игры».",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(inner).padding(24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    PeriodSegmented(selected = period, onSelect = { period = it })
                }
                item {
                    TotalsCard(
                        period = period,
                        unit = chartUnit,
                        total = periodTotal,
                        previous = prevTotal,
                        otherUnits = totals.filterKeys { it != chartUnit && it.isNotBlank() }
                    )
                }
                if (bars.any { it.value != 0L }) {
                    item {
                        BarsChart(title = barsTitle(period), bars = bars, unit = chartUnit)
                    }
                }
                item {
                    Text(
                        "Разделы",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                items(numeric, key = { it.id }) { section ->
                    SectionBreakdown(
                        section = section,
                        total = sectionTotals[section.id] ?: 0L,
                        max = maxSectionTotal,
                        onClick = { onOpenSection(section.id) },
                        onEdit = { actions.edit(section) },
                        onDelete = { actions.delete(section) }
                    )
                }
            }
        }
    }
    SectionActionDialogs(vm, actions, sections)
}

private fun barsTitle(p: Period): String = when (p) {
    Period.DAY -> "Последние 7 дней"
    Period.WEEK -> "Последние 6 недель"
    Period.MONTH -> "Последние 6 месяцев"
    Period.YEAR -> "По месяцам"
}

@Composable
private fun PeriodSegmented(selected: Period, onSelect: (Period) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(scheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Period.entries.forEach { item ->
            val active = item == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (active) scheme.surface else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(enabled = !active) { onSelect(item) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TotalsCard(
    period: Period,
    unit: String,
    total: Long,
    previous: Long,
    otherUnits: Map<String, Pair<Long, Long>>
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                periodTitle(period),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                Fmt.value(total, unit),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface
            )
            if (previous > 0) {
                Spacer(Modifier.height(6.dp))
                Delta(previous = previous, current = total, unit = unit)
            }
            if (otherUnits.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    otherUnits.entries.take(3).forEach { (otherUnit, values) ->
                        Column {
                            Text(
                                Fmt.value(values.first, otherUnit),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface
                            )
                            Text(
                                "за период",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun periodTitle(p: Period): String = when (p) {
    Period.DAY -> "Сегодня"
    Period.WEEK -> "Эта неделя"
    Period.MONTH -> "Этот месяц"
    Period.YEAR -> "Этот год"
}

@Composable
private fun Delta(previous: Long, current: Long, unit: String) {
    val scheme = MaterialTheme.colorScheme
    if (previous == 0L) return
    val diff = current - previous
    val percent = diff * 100.0 / previous
    val up = diff > 0
    val same = diff == 0L
    val color = when {
        same -> scheme.onSurfaceVariant
        up -> scheme.error
        else -> scheme.primary
    }
    val arrow = when {
        same -> ""
        up -> "▲ "
        else -> "▼ "
    }
    val absolute = kotlin.math.abs(diff)
    val shownPercent = kotlin.math.abs(percent)
    Text(
        "$arrow${Fmt.value(absolute, unit)} · ${String.format(Fmt.ru, "%.1f", shownPercent)}% к прошлому",
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

@Composable
private fun BarsChart(title: String, bars: List<Bucket>, unit: String) {
    val scheme = MaterialTheme.colorScheme
    val max = bars.maxOfOrNull { it.value } ?: 0L
    val lastIndex = bars.lastIndex

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                bars.forEachIndexed { index, bar ->
                    val fraction = if (max <= 0L) 0f else bar.value.toFloat() / max
                    val height = (4f + fraction * 92f).dp
                    val highlight = index == lastIndex
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (highlight && bar.value != 0L) {
                            Text(
                                Fmt.value(bar.value, unit),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Visible
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(height)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (highlight) scheme.primary else scheme.primaryContainer)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            bar.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (highlight) scheme.primary else scheme.onSurfaceVariant,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionBreakdown(
    section: Section,
    total: Long,
    max: Long,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val fraction = if (max <= 0L) 0f else total.toFloat() / max
    SectionListCard(
        title = section.name,
        keywords = section.keywords,
        onClick = onClick,
        onEdit = onEdit,
        onDelete = onDelete
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    section.unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    Fmt.value(total, section.unit),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(scheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(5.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(scheme.primary)
                )
            }
        }
    }
}
