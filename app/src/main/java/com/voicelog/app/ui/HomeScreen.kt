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
            Box(modifier = Modifier.weight(1.1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InfoArea(ui)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Кнопка записи — чуть ниже центра экрана.
            MicButton(listening = ui.status == Status.LISTENING, onClick = onMic)

            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when (ui.status) {
                        Status.IDLE -> "Нажмите на кнопку и скажите команду"
                        Status.LISTENING -> if (ui.partial.isBlank()) "Слушаю…" else ui.partial
                        Status.PROCESSING -> "Обрабатываю…"
                        Status.SPEAKING -> "Говорю… (нажмите, чтобы ответить сразу)"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ui.awaiting) {
                    TextButton(onClick = vm::cancelDialog) { Text("Отменить диалог") }
                }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Или введите команду текстом") },
                    trailingIcon = {
                        IconButton(onClick = sendTyped) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendTyped() })
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun InfoArea(ui: HomeUi) {
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
        HintCard()
    }
}

@Composable
private fun HintCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Скажите, например:", style = MaterialTheme.typography.titleSmall)
            Text(
                "• «Добавь в раздел траты покупка чая стоимостью 254 рубля 55 копеек»",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "• «Заметка. Рагу — хороший ресторан, около Мадагаскара»",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Первое слово «Заметка» или «Статистика» задаёт режим.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Если чего-то не хватит, приложение уточнит. Чтобы прервать — скажите «отмена».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
