@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicelog.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.voicelog.app.EntryMode
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voicelog.app.HomeUi
import com.voicelog.app.MainViewModel
import com.voicelog.app.MessageKind
import com.voicelog.app.Status

private val ListeningColor = Color(0xFFE5484D)

@Composable
fun HomeScreen(vm: MainViewModel) {
    val ui by vm.home.collectAsState()
    val mode by vm.entryMode.collectAsState()
    val ctx = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.onMicClick()
        } else {
            vm.showError("Без доступа к микрофону голосовой ввод не работает. Разрешите его в настройках телефона.")
        }
    }

    val onMic: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.onMicClick() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    var typed by remember { mutableStateOf("") }
    val sendTyped: () -> Unit = {
        if (typed.isNotBlank()) {
            vm.submitText(typed.trim())
            typed = ""
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Голосовой дневник") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .imePadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Верхняя часть: что услышало приложение и что ответило.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InfoArea(ui, mode)
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MicButton(listening = ui.status == Status.LISTENING, onClick = onMic)
                ModeWheel(
                    selected = mode,
                    enabled = ui.status == Status.IDLE,
                    onSelect = vm::setEntryMode,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }

            Text(
                text = when (ui.status) {
                    Status.IDLE -> when (mode) {
                        EntryMode.TASK -> "Нажмите и скажите задачу"
                        EntryMode.NOTE -> "Нажмите и скажите заметку"
                        EntryMode.STATS -> "Нажмите и скажите запись со значением"
                    }
                    Status.LISTENING -> if (ui.partial.isBlank()) "Слушаю…" else ui.partial
                    Status.PROCESSING -> "Обрабатываю…"
                    Status.SPEAKING -> "Говорю… (нажмите, чтобы ответить сразу)"
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (ui.awaiting) {
                TextButton(onClick = vm::cancelDialog) { Text("Отменить диалог") }
            }

            // Поле ввода всегда внизу и поднимается над клавиатурой.
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 12.dp),
                maxLines = 4,
                placeholder = { Text("Или введите команду текстом") },
                trailingIcon = {
                    IconButton(onClick = sendTyped) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendTyped() })
            )
        }
    }
}

@Composable
private fun InfoArea(ui: HomeUi, mode: EntryMode) {
    if (ui.heard.isNotBlank()) {
        Text(
            text = "Вы сказали: «${ui.heard}»" + if (ui.source.isBlank()) "" else "\n(${ui.source})",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (ui.message.isNotBlank()) {
        val scheme = MaterialTheme.colorScheme
        val (container, content) = when (ui.kind) {
            MessageKind.SAVED -> scheme.primaryContainer to scheme.onPrimaryContainer
            MessageKind.QUESTION -> scheme.secondaryContainer to scheme.onSecondaryContainer
            MessageKind.ERROR -> scheme.errorContainer to scheme.onErrorContainer
            else -> scheme.surfaceVariant to scheme.onSurfaceVariant
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
        ) {
            Text(
                text = ui.message,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp)
            )
        }
    } else if (ui.heard.isBlank()) {
        HintCard(mode)
    }
}

@Composable
private fun HintCard(mode: EntryMode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Тип записи: ${mode.title}", style = MaterialTheme.typography.titleSmall)
            when (mode) {
                EntryMode.TASK -> {
                    Text("• «Позвонить в банк»", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Фраза целиком попадёт в список задач.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                EntryMode.NOTE -> {
                    Text(
                        "• «Рагу — хороший ресторан около Мадагаскара»",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Раздел заметок определится по названию или словам в тексте; если не ясно — приложение уточнит.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                EntryMode.STATS -> {
                    Text("• «Купил творог за 120 рублей»", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Раздел определится по словам, число — из фразы; если чего-то не хватит — приложение уточнит.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "Тип выбирается слайдером справа от кнопки. Чтобы прервать диалог — скажите «отмена».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Вертикальный «барабан» типов записи: листается по кругу свайпом, нажатием или стрелками. */
@Composable
private fun ModeWheel(
    selected: EntryMode,
    enabled: Boolean,
    onSelect: (EntryMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val all = EntryMode.entries
    val i = all.indexOf(selected)
    val prev = all[(i - 1 + all.size) % all.size]
    val next = all[(i + 1) % all.size]
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.toPx() }
    var drag by remember { mutableStateOf(0f) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.75f else 0.35f)

    Column(
        modifier = modifier
            .width(104.dp)
            .pointerInput(selected, enabled) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        if (!enabled) return@detectVerticalDragGestures
                        drag += amount
                        if (drag <= -stepPx) {
                            drag = 0f
                            onSelect(next)
                        } else if (drag >= stepPx) {
                            drag = 0f
                            onSelect(prev)
                        }
                    }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.KeyboardArrowUp,
            contentDescription = "Предыдущий тип",
            tint = dim,
            modifier = Modifier.size(22.dp).clickable(enabled = enabled) { onSelect(prev) }
        )
        Text(
            prev.title,
            style = MaterialTheme.typography.bodySmall,
            color = dim,
            maxLines = 1,
            modifier = Modifier.padding(vertical = 4.dp).clickable(enabled = enabled) { onSelect(prev) }
        )
        Text(
            selected.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        Text(
            next.title,
            style = MaterialTheme.typography.bodySmall,
            color = dim,
            maxLines = 1,
            modifier = Modifier.padding(vertical = 4.dp).clickable(enabled = enabled) { onSelect(next) }
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = "Следующий тип",
            tint = dim,
            modifier = Modifier.size(22.dp).clickable(enabled = enabled) { onSelect(next) }
        )
    }
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )
    val color = if (listening) ListeningColor else MaterialTheme.colorScheme.primary

    Box(modifier = Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        if (listening) {
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(ListeningColor.copy(alpha = pulseAlpha))
            )
        }
        Box(
            modifier = Modifier
                .size(128.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (listening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (listening) "Остановить запись" else "Записать голос",
                modifier = Modifier.size(56.dp),
                tint = Color.White
            )
        }
    }
}
