@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicelog.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicelog.app.MainViewModel
import com.voicelog.app.data.LogEntry
import com.voicelog.app.data.Section
import com.voicelog.app.data.isNotes
import com.voicelog.app.util.Fmt
import com.voicelog.app.util.localDate
import java.time.LocalDate

/** Страница «Заметки»: все заметки из всех разделов-заметок, новые сверху, с фильтром по разделу. */
@Composable
fun NotesScreen(vm: MainViewModel) {
    val notes by vm.noteEntries.collectAsState()
    val sections by vm.sections.collectAsState()
    val noteSections = remember(sections) { sections.filter { it.isNotes } }
    val names = remember(sections) { sections.associate { it.id to it.name } }
    var filter by rememberSaveable { mutableStateOf<Long?>(null) }
    // Если выбранный раздел удалили — показываем все.
    val active = filter?.takeIf { id -> noteSections.any { it.id == id } }
    val shown = remember(notes, active) { if (active == null) notes else notes.filter { it.sectionId == active } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Заметки") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (noteSections.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = active == null, onClick = { filter = null }, label = { Text("Все") })
                    noteSections.forEach { sec ->
                        FilterChip(
                            selected = active == sec.id,
                            onClick = { filter = sec.id },
                            label = { Text(sec.name) }
                        )
                    }
                }
            }
            NoteList(
                vm = vm,
                notes = shown,
                sectionNames = names,
                emptyText = if (noteSections.isEmpty())
                    "Разделов заметок пока нет.\nСоздайте на вкладке «Записи» или скажите: «Создай раздел Рестораны заметки»."
                else "Заметок пока нет.\nСкажите на вкладке «Голос»: «Рагу — хороший ресторан. Заметка»."
            )
        }
    }
}

/** Экран одного раздела-заметок. */
@Composable
fun NotesSectionScreen(vm: MainViewModel, section: Section, entries: List<LogEntry>, onBack: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { SectionTopBar(section.name, onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Заметка") }
            )
        }
    ) { inner ->
        NoteList(
            vm = vm,
            notes = entries,
            sectionNames = null,
            emptyText = "В этом разделе заметок пока нет.",
            modifier = Modifier.padding(inner)
        )
    }
    if (adding) {
        NoteEditDialog(
            title = "Новая заметка",
            initial = "",
            onDismiss = { adding = false },
            onSave = {
                vm.addNote(section.id, it)
                adding = false
            }
        )
    }
}

@Composable
private fun NoteList(
    vm: MainViewModel,
    notes: List<LogEntry>,
    sectionNames: Map<Long, String>?,
    emptyText: String,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<LogEntry?>(null) }
    var deleting by remember { mutableStateOf<LogEntry?>(null) }

    val filtered = remember(notes, query, sectionNames) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) notes else notes.filter {
            it.description.lowercase().contains(q) ||
                (sectionNames?.get(it.sectionId)?.lowercase()?.contains(q) == true)
        }
    }
    val days = remember(filtered) {
        filtered.groupBy { it.localDate().toEpochDay() }.entries.sortedByDescending { it.key }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Поиск") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (days.isEmpty()) {
            Text(
                if (notes.isEmpty()) emptyText else "Ничего не найдено.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(days, key = { it.key }) { day ->
                    NoteDay(
                        date = LocalDate.ofEpochDay(day.key),
                        items = day.value,
                        sectionNames = sectionNames,
                        onEdit = { editing = it },
                        onDelete = { deleting = it }
                    )
                }
            }
        }
    }

    editing?.let { note ->
        NoteEditDialog(
            title = "Изменить заметку",
            initial = note.description,
            onDismiss = { editing = null },
            onSave = {
                vm.updateEntry(note.copy(description = it))
                editing = null
            }
        )
    }

    deleting?.let { note ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Удалить заметку?") },
            text = { Text(note.description) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteEntry(note)
                    deleting = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun NoteDay(
    date: LocalDate,
    items: List<LogEntry>,
    sectionNames: Map<Long, String>?,
    onEdit: (LogEntry) -> Unit,
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
                for (note in items) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(note) }
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val tag = sectionNames?.get(note.sectionId)
                            Text(
                                Fmt.time(note.timestamp) + if (tag != null) " · $tag" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(note.description, style = MaterialTheme.typography.bodyLarge)
                        }
                        IconButton(onClick = { onDelete(note) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Удалить заметку",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteEditDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Текст заметки") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
