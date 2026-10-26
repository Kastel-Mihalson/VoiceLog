@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicelog.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicelog.app.MainViewModel
import com.voicelog.app.data.Section
import com.voicelog.app.data.isNotes
import com.voicelog.app.util.Fmt
import com.voicelog.app.util.localDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private class Totals(val today: Long, val week: Long, val month: Long)

/** Страница «Статистика»: разделы с числами (траты и т. п.) и итоги за день, неделю, месяц. */
@Composable
fun StatsScreen(vm: MainViewModel, onOpenSection: (Long) -> Unit) {
    val sections by vm.sections.collectAsState()
    val entries by vm.allEntries.collectAsState()
    val numeric = remember(sections) { sections.filter { !it.isNotes } }

    val totals = remember(entries) {
        val today = LocalDate.now()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val monthStart = today.withDayOfMonth(1)
        entries.groupBy { it.sectionId }.mapValues { (_, list) ->
            Totals(
                today = list.filter { it.localDate() == today }.sumOf { it.valueCents },
                week = list.filter { !it.localDate().isBefore(weekStart) }.sumOf { it.valueCents },
                month = list.filter { !it.localDate().isBefore(monthStart) }.sumOf { it.valueCents }
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Статистика") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { inner ->
        if (numeric.isEmpty()) {
            Text(
                "Разделов статистики пока нет.\nСоздайте на вкладке «Записи» или скажите: «Создай раздел Игры».",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(inner).padding(24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(numeric, key = { it.id }) { section ->
                    StatsCard(section, totals[section.id], onClick = { onOpenSection(section.id) })
                }
            }
        }
    }
}

@Composable
private fun StatsCard(section: Section, t: Totals?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(section.name, style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Cell("Сегодня", Fmt.value(t?.today ?: 0, section.unit), Modifier.weight(1f))
                Cell("Неделя", Fmt.value(t?.week ?: 0, section.unit), Modifier.weight(1f))
                Cell("Месяц", Fmt.value(t?.month ?: 0, section.unit), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Cell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
