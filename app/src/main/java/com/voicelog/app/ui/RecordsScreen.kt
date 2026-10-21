@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicelog.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicelog.app.MainViewModel
import com.voicelog.app.data.Section
import com.voicelog.app.util.Fmt
import com.voicelog.app.util.localDate
import com.voicelog.app.voice.SectionCommand
import com.voicelog.app.voice.SectionRef
import java.time.LocalDate

@Composable
fun RecordsScreen(vm: MainViewModel, onOpenSection: (Long) -> Unit) {
    val sections by vm.sections.collectAsState()
    val entries by vm.allEntries.collectAsState()

    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Section?>(null) }
    var deleting by remember { mutableStateOf<Section?>(null) }

    val todayTotals = remember(entries) {
        val today = LocalDate.now()
        entries.filter { it.localDate() == today }
            .groupBy { it.sectionId }
            .mapValues { (_, list) -> list.sumOf { it.valueCents } }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Записи") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Новый раздел") }
            )
        }
    ) { inner ->
        if (sections.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Разделов пока нет.\nНажмите «Новый раздел» или скажите на главной: «Создай раздел …».",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sections, key = { it.id }) { section ->
                    SectionCard(
                        section = section,
                        todayCents = todayTotals[section.id] ?: 0L,
                        onClick = { onOpenSection(section.id) },
                        onEdit = { editing = section },
                        onDelete = { deleting = section }
                    )
                }
            }
        }
    }

    if (creating) {
        SectionDialog(
            initial = null,
            others = sections.map { SectionRef(it.id, it.name, it.unit, it.keywords, it.askValue) },
            onDismiss = { creating = false },
            onSave = { name, unit, keywords, ask ->
                vm.addSection(name, unit, keywords, ask)
                creating = false
            }
        )
    }

    editing?.let { section ->
        SectionDialog(
            initial = section,
            others = sections.filter { it.id != section.id }
                .map { SectionRef(it.id, it.name, it.unit, it.keywords, it.askValue) },
            onDismiss = { editing = null },
            onSave = { name, unit, keywords, ask ->
                vm.updateSection(section.copy(name = name, unit = unit, keywords = keywords, askValue = ask))
                editing = null
            }
        )
    }

    deleting?.let { section ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Удалить раздел?") },
            text = { Text("Раздел «${section.name}» и все его записи будут удалены без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSection(section)
                    deleting = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun SectionCard(
    section: Section,
    todayCents: Long,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(section.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Сегодня: ${Fmt.value(todayCents, section.unit)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (section.keywords.isNotBlank()) {
                    Text(
                        "Слова: ${section.keywords.replace(",", ", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Действия")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Изменить") },
                        onClick = {
                            menu = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        onClick = {
                            menu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionDialog(
    initial: Section?,
    others: List<SectionRef>,
    onDismiss: () -> Unit,
    onSave: (name: String, unit: String, keywords: String, askValue: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var unit by remember { mutableStateOf(initial?.unit ?: "₽") }
    var keywords by remember { mutableStateOf(initial?.keywords ?: "") }
    var ask by remember { mutableStateOf(initial?.askValue ?: true) }

    // Слова и название, которые уже используются в других разделах.
    val conflicts = remember(name, keywords, others) {
        SectionCommand.conflicts(
            name,
            keywords.split(',', ';').map { it.trim() }.filter { it.isNotBlank() },
            others
        )
    }
    val sameNameExists = remember(name, others) {
        name.isNotBlank() && others.any { SectionCommand.sameName(it.name, name) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (initial == null) "Новый раздел" else "Изменить раздел") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    placeholder = { Text("Например, Траты") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("Единица измерения") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("₽", "раз", "шт", "мин", "ч", "дн.", "км", "мл").forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = { unit = u },
                            label = { Text(u) }
                        )
                    }
                }
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("Слова для распознавания") },
                    supportingText = {
                        if (conflicts.isEmpty()) {
                            Text("Через запятую, например: потратил, купил, расход")
                        } else {
                            Text(
                                "Совпадения: " + conflicts.joinToString("; ") { (w, s) ->
                                    "«$w» уже есть в разделе «$s»"
                                } + ". Если фраза подойдёт обоим, приложение спросит, куда записать.",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (sameNameExists) {
                    Text(
                        "Раздел с таким названием уже есть.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Уточнять число", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Если число не названо — переспросить. Выключено: запись считается за 1.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = ask, onCheckedChange = { ask = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), unit.trim(), keywords.trim(), ask) }
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
