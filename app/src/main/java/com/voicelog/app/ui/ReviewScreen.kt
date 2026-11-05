package com.voicelog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voicelog.app.ReviewDraft
import com.voicelog.app.data.Section
import com.voicelog.app.data.isNotes
import com.voicelog.app.util.Fmt
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val AUTO_SAVE_SECONDS = 5

/** Экран проверки распознанной записи перед сохранением в раздел. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    draft: ReviewDraft,
    sections: List<Section>,
    onConfirm: () -> Unit,
    onUpdate: (String, Long, Section) -> Unit,
    onRetry: () -> Unit
) {
    var editing by remember(draft.timestamp) { mutableStateOf(false) }
    var paused by remember(draft.timestamp) { mutableStateOf(false) }
    var left by remember(draft.timestamp) { mutableIntStateOf(AUTO_SAVE_SECONDS) }

    LaunchedEffect(draft.timestamp, paused) {
        while (!paused && left > 0) {
            delay(1000)
            left--
        }
        if (!paused && left == 0) onConfirm()
    }

    BackHandler(onBack = onRetry)

    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Проверьте запись") },
                navigationIcon = {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background)
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeardCard(draft.heard)
            ParsedCard(draft)

            FieldRow(
                label = "Сумма",
                value = Fmt.value(draft.valueCents, draft.unit),
                onClick = {
                    paused = true
                    editing = true
                }
            )
            FieldRow(
                label = "Раздел",
                value = draft.sectionName,
                onClick = {
                    paused = true
                    editing = true
                }
            )
            FieldRow(label = "Дата", value = draft.dateLabel(), onClick = null)

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(percent = 50)
            ) {
                Text("Сохранить", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        paused = true
                        editing = true
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(percent = 50)
                ) { Text("Изменить") }
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(percent = 50)
                ) { Text("Ещё раз") }
            }

            Text(
                text = if (paused) {
                    "Автосохранение отключено — сохраните вручную"
                } else {
                    "Сохранится автоматически через $left сек"
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (editing) {
        EditDraftDialog(
            draft = draft,
            sections = sections,
            onDismiss = {
                editing = false
            },
            onSave = { description, cents, section ->
                editing = false
                onUpdate(description, cents, section)
            }
        )
    }
}

@Composable
private fun HeardCard(heard: String) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Я УСЛЫШАЛ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "«$heard»",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ParsedCard(draft: ReviewDraft) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(scheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    draft.sectionName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    draft.sectionName,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    Fmt.value(draft.valueCents, draft.unit),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
                Text(
                    draft.description.ifBlank { "Без описания" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (onClick != null) scheme.primary else scheme.onSurface
            )
        }
    }
}

@Composable
private fun EditDraftDialog(
    draft: ReviewDraft,
    sections: List<Section>,
    onDismiss: () -> Unit,
    onSave: (String, Long, Section) -> Unit
) {
    val numeric = sections.filter { !it.isNotes }
    var description by remember { mutableStateOf(draft.description) }
    var valueText by remember {
        mutableStateOf(BigDecimal.valueOf(draft.valueCents, 2).stripTrailingZeros().toPlainString().replace('.', ','))
    }
    var sectionId by remember { mutableLongStateOf(draft.sectionId) }
    val section = numeric.firstOrNull { it.id == sectionId }
    val cents = Fmt.parseCents(valueText)
    val valid = section != null && cents != null && cents > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить запись") },
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
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text("Сумма или количество") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Раздел", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    numeric.forEach { item ->
                        FilterChip(
                            selected = item.id == sectionId,
                            onClick = { sectionId = item.id },
                            label = { Text(item.name) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(description.trim(), cents!!, section!!) }
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

private fun ReviewDraft.dateLabel(): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMMM, HH:mm", Fmt.ru))
