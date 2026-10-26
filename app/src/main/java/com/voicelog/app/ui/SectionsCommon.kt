@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicelog.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicelog.app.MainViewModel
import com.voicelog.app.data.Section
import com.voicelog.app.data.isNotes
import com.voicelog.app.voice.SectionCommand
import com.voicelog.app.voice.SectionRef

/**
 * Состояние диалогов «создать / изменить / удалить раздел» для одной страницы.
 * notes — какие разделы создаёт эта страница: заметки (true) или статистику (false).
 */
class SectionActions internal constructor(private val notes: Boolean) {
    internal var creating by mutableStateOf(false)
    internal var editing by mutableStateOf<Section?>(null)
    internal var deleting by mutableStateOf<Section?>(null)
    internal val isNotes get() = notes

    fun create() { creating = true }
    fun edit(section: Section) { editing = section }
    fun delete(section: Section) { deleting = section }
}

@Composable
fun rememberSectionActions(notes: Boolean) = remember(notes) { SectionActions(notes) }

/** Показывает диалоги, которые открыл [actions]. Слова проверяются на совпадения со всеми разделами. */
@Composable
fun SectionActionDialogs(vm: MainViewModel, actions: SectionActions, all: List<Section>) {
    val notes = actions.isNotes
    if (actions.creating) {
        SectionDialog(
            initial = null,
            notes = notes,
            others = all.map { it.toRef() },
            onDismiss = { actions.creating = false },
            onSave = { name, unit, keywords, ask ->
                vm.addSection(name, unit, keywords, ask, notes)
                actions.creating = false
            }
        )
    }
    actions.editing?.let { section ->
        SectionDialog(
            initial = section,
            notes = section.isNotes,
            others = all.filter { it.id != section.id }.map { it.toRef() },
            onDismiss = { actions.editing = null },
            onSave = { name, unit, keywords, ask ->
                vm.updateSection(section.copy(name = name, unit = unit, keywords = keywords, askValue = ask))
                actions.editing = null
            }
        )
    }
    actions.deleting?.let { section ->
        AlertDialog(
            onDismissRequest = { actions.deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Удалить раздел?") },
            text = { Text("Раздел «${section.name}» и все его записи будут удалены без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSection(section)
                    actions.deleting = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { actions.deleting = null }) { Text("Отмена") } }
        )
    }
}

private fun Section.toRef() = SectionRef(id, name, unit, keywords, askValue, isNotes)

/** Карточка раздела со своим меню «Изменить / Удалить». content — строки под названием. */
@Composable
fun SectionListCard(
    title: String,
    keywords: String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
                if (keywords.isNotBlank()) {
                    Text(
                        "Слова: ${keywords.replace(",", ", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Действия")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Изменить") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun SectionDialog(
    initial: Section?,
    notes: Boolean,
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
        title = {
            Text(
                (if (initial == null) "Новый раздел" else "Изменить раздел") +
                    if (notes) " заметок" else " статистики"
            )
        },
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
                if (!notes) OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("Единица измерения") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!notes) Row(
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
                if (!notes) Row(verticalAlignment = Alignment.CenterVertically) {
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
