@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicelog.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voicelog.app.MainViewModel
import com.voicelog.app.data.LogEntry
import com.voicelog.app.data.Section
import com.voicelog.app.util.Fmt
import com.voicelog.app.util.localDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val MODES = listOf("День", "Неделя", "Месяц", "Всё")

@Composable
fun SectionScreen(vm: MainViewModel, id: Long, onBack: () -> Unit) {
    val section by remember(id) { vm.sectionFlow(id) }.collectAsState(initial = null)
    val entries by remember(id) { vm.entriesFlow(id) }.collectAsState(initial = emptyList())

    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 день, 1 неделя, 2 месяц, 3 всё
    var shift by rememberSaveable { mutableIntStateOf(0) } // 0 — текущий период, -1 — предыдущий…
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LogEntry?>(null) }

    val sec = section
    if (sec == null) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { SectionTopBar("Раздел", onBack) }
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("Раздел не найден", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val range = remember(mode, shift) { periodRange(mode, shift) }
    val filtered = remember(entries, range) {
        if (range == null) {
            entries
        } else {
            entries.filter {
                val d = it.localDate()
                !d.isBefore(range.first) && !d.isAfter(range.second)
            }
        }
    }
    val periodTotal = filtered.sumOf { it.valueCents }
    val days = remember(filtered) {
        filtered.groupBy { it.localDate().toEpochDay() }
            .entries
            .sortedByDescending { it.key }
    }
    val stats = remember(entries) {
        val today = LocalDate.now()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val monthStart = today.withDayOfMonth(1)
        Triple(
            entries.filter { it.localDate() == today }.sumOf { it.valueCents },
            entries.filter { !it.localDate().isBefore(weekStart) }.sumOf { it.valueCents },
            entries.filter { !it.localDate().isBefore(monthStart) }.sumOf { it.valueCents }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { SectionTopBar(sec.name, onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Запись") }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Итоги: сегодня / неделя / месяц
            item(key = "stats") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(Modifier.padding(vertical = 14.dp)) {
                        StatCell("Сегодня", Fmt.value(stats.first, sec.unit), Modifier.weight(1f))
                        StatCell("Неделя", Fmt.value(stats.second, sec.unit), Modifier.weight(1f))
                        StatCell("Месяц", Fmt.value(stats.third, sec.unit), Modifier.weight(1f))
                    }
                }
            }

            // Фильтр по периоду
            item(key = "filter") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MODES.forEachIndexed { index, title ->
                            FilterChip(
                                selected = mode == index,
                                onClick = {
                                    mode = index
                                    shift = 0
                                },
                                label = { Text(title) }
                            )
                        }
                    }
                    if (mode != 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { shift -= 1 }) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Назад")
                            }
                            Text(
                                text = periodLabel(mode, shift, range),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { shift += 1 }, enabled = shift < 0) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Вперёд")
                            }
                        }
                    }
                }
            }

            // Итог за выбранный период
            item(key = "total") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Итого за период", style = MaterialTheme.typography.labelLarge)
                        Text(
                            Fmt.value(periodTotal, sec.unit),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Записей: ${filtered.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (days.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "За этот период записей нет.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }

            // Записи по дням: дата, под ней записи, внизу итог за день
            for (day in days) {
                item(key = "day${day.key}") {
                    DayBlock(
                        date = LocalDate.ofEpochDay(day.key),
                        items = day.value,
                        section = sec,
                        onDelete = { deleting = it }
                    )
                }
            }
        }
    }

    if (adding) {
        AddEntryDialog(
            unit = sec.unit,
            onDismiss = { adding = false },
            onAdd = { description, cents ->
                vm.addEntry(sec.id, description, cents)
                adding = false
            }
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Удалить запись?") },
            text = {
                Text(
                    "${Fmt.time(entry.timestamp)} · " +
                        "${entry.description.ifBlank { sec.name }} · " +
                        Fmt.value(entry.valueCents, sec.unit)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteEntry(entry)
                    deleting = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun SectionTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DayBlock(
    date: LocalDate,
    items: List<LogEntry>,
    section: Section,
    onDelete: (LogEntry) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            Fmt.dayTitle(date),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                for (entry in items) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            Fmt.time(entry.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(48.dp)
                        )
                        Text(
                            entry.description.ifBlank { section.name },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            Fmt.value(entry.valueCents, section.unit),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(onClick = { onDelete(entry) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Удалить запись",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        "Итого за день",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        Fmt.value(items.sumOf { it.valueCents }, section.unit),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun AddEntryDialog(unit: String, onDismiss: () -> Unit, onAdd: (String, Long) -> Unit) {
    var description by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val cents = Fmt.parseCents(value)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Новая запись") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(if (unit.isBlank()) "Количество" else "Количество / сумма, $unit") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = value.isNotBlank() && cents == null,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Дата и время проставятся автоматически.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = cents != null,
                onClick = { onAdd(description.trim(), cents ?: 0L) }
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/** Границы периода: null — «Всё». */
private fun periodRange(mode: Int, shift: Int): Pair<LocalDate, LocalDate>? {
    val today = LocalDate.now()
    return when (mode) {
        0 -> {
            val d = today.plusDays(shift.toLong())
            d to d
        }
        1 -> {
            val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusWeeks(shift.toLong())
            start to start.plusDays(6)
        }
        2 -> {
            val m = YearMonth.from(today).plusMonths(shift.toLong())
            m.atDay(1) to m.atEndOfMonth()
        }
        else -> null
    }
}

private fun periodLabel(mode: Int, shift: Int, range: Pair<LocalDate, LocalDate>?): String {
    if (range == null) return "Все записи"
    return when (mode) {
        0 -> when (shift) {
            0 -> "Сегодня"
            -1 -> "Вчера"
            else -> range.first.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Fmt.ru))
        }
        1 -> {
            val f = DateTimeFormatter.ofPattern("d MMM", Fmt.ru)
            "${range.first.format(f)} – ${range.second.format(f)}"
        }
        else -> range.first.format(DateTimeFormatter.ofPattern("LLLL yyyy", Fmt.ru))
            .replaceFirstChar { it.uppercase() }
    }
}
