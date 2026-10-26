@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicelog.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.voicelog.app.MainViewModel
import com.voicelog.app.data.Task
import com.voicelog.app.util.Fmt
import java.time.LocalDate

private fun Task.doneDay(): LocalDate? = doneAt?.let { Fmt.localDate(it) }

/** Страница «Задачи»: список дел с галочками и подвкладка «История» с выполненным по дням. */
@Composable
fun TasksScreen(vm: MainViewModel) {
    val tasks by vm.tasks.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Задачи") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Список") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("История") })
            }
            if (tab == 0) TaskList(vm, tasks) else TaskHistory(tasks)
        }
    }
}

@Composable
private fun TaskList(vm: MainViewModel, tasks: List<Task>) {
    var text by remember { mutableStateOf("") }
    val add: () -> Unit = {
        if (text.isNotBlank()) {
            vm.addTask(text)
            text = ""
        }
    }
    val today = LocalDate.now()
    val open = tasks.filter { !it.done }
    val doneToday = tasks.filter { it.done && it.doneDay() == today }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item(key = "input") {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                singleLine = true,
                placeholder = { Text("Новая задача") },
                trailingIcon = {
                    IconButton(onClick = add) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Добавить задачу")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() })
            )
        }
        if (open.isEmpty() && doneToday.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Задач пока нет. Введите первую выше.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
        items(open, key = { "o${it.id}" }) { TaskRow(vm, it) }
        if (doneToday.isNotEmpty()) {
            item(key = "doneHeader") {
                Text(
                    "Выполнено сегодня",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp)
                )
            }
            items(doneToday, key = { "d${it.id}" }) { TaskRow(vm, it) }
        }
    }
}

@Composable
private fun TaskRow(vm: MainViewModel, task: Task) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.setTaskDone(task, !task.done) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = task.done, onCheckedChange = { vm.setTaskDone(task, it) })
        Text(
            task.text,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (task.done) TextDecoration.LineThrough else null,
            color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { vm.deleteTask(task) }) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Удалить задачу",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** История: выполненные задачи по дням. День раскрывается и показывает список. */
@Composable
private fun TaskHistory(tasks: List<Task>) {
    val days = remember(tasks) {
        tasks.filter { it.done && it.doneAt != null }
            .groupBy { it.doneDay()!!.toEpochDay() }
            .entries.sortedByDescending { it.key }
    }
    var expanded by remember { mutableStateOf(setOf<Long>()) }

    if (days.isEmpty()) {
        Text(
            "История пока пуста. Отметьте задачу галочкой — она появится здесь за этот день.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(days, key = { it.key }) { day ->
            val isOpen = day.key in expanded
            val list = day.value.sortedBy { it.doneAt }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = if (isOpen) expanded - day.key else expanded + day.key
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                Fmt.dayTitle(LocalDate.ofEpochDay(day.key)),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Выполнено: ${list.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isOpen) "Свернуть" else "Раскрыть"
                        )
                    }
                    if (isOpen) {
                        for (t in list) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 10.dp)
                                )
                                Text(t.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Text(
                                    Fmt.time(t.doneAt ?: 0L),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
