@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicelog.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.voicelog.app.MainViewModel
import com.voicelog.app.data.isNotes
import com.voicelog.app.data.SpeechEngine
import com.voicelog.app.data.ThemeMode
import com.voicelog.app.voice.ModelState

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val theme by vm.settings.themeMode.collectAsState()
    val speak by vm.settings.speakReplies.collectAsState()
    val auto by vm.settings.autoListen.collectAsState()
    val sections by vm.sections.collectAsState()
    val engine by vm.settings.engine.collectAsState()
    val model by vm.modelState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsCard("Оформление") {
                ThemeRow("Светлая", theme == ThemeMode.LIGHT) { vm.settings.setTheme(ThemeMode.LIGHT) }
                ThemeRow("Тёмная", theme == ThemeMode.DARK) { vm.settings.setTheme(ThemeMode.DARK) }
                ThemeRow("Как в системе", theme == ThemeMode.SYSTEM) { vm.settings.setTheme(ThemeMode.SYSTEM) }
            }

            SettingsCard("Распознавание речи") {
                ThemeRow(
                    "Vosk — офлайн, на телефоне",
                    engine == SpeechEngine.VOSK
                ) {
                    if (model is ModelState.Ready) vm.settings.setEngine(SpeechEngine.VOSK)
                }
                ThemeRow(
                    "Google — встроенный сервис",
                    engine == SpeechEngine.GOOGLE
                ) { vm.settings.setEngine(SpeechEngine.GOOGLE) }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                when (val m = model) {
                    is ModelState.Missing -> {
                        Text(
                            "Для Vosk нужно один раз скачать русскую модель (около 45 МБ, нужен интернет). " +
                                "После этого распознавание работает без интернета.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = vm::downloadModel) { Text("Скачать модель") }
                    }

                    is ModelState.Downloading -> {
                        Text(
                            "${m.phase}… ${m.percent}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(
                            progress = { m.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Не закрывайте приложение, пока идёт загрузка.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is ModelState.Ready -> {
                        Text("Модель установлена и готова к работе.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = vm::deleteModel) { Text("Удалить модель") }
                    }

                    is ModelState.Failed -> {
                        Text(
                            "Не удалось скачать модель: ${m.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = vm::downloadModel) { Text("Повторить") }
                    }
                }
                Text(
                    "Vosk выдаёт числа словами («двести пятьдесят четыре рубля») — приложение это понимает. " +
                        "Говорите чётко и без длинных пауз внутри фразы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsCard("Голос") {
                SwitchRow(
                    title = "Озвучивать ответы",
                    subtitle = "Приложение голосом подтверждает запись и задаёт вопросы",
                    checked = speak,
                    onChange = vm.settings::setSpeakReplies
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                SwitchRow(
                    title = "Слушать ответ на вопрос",
                    subtitle = "После уточняющего вопроса микрофон включается сам",
                    checked = auto,
                    onChange = vm.settings::setAutoListen
                )
            }

            SettingsCard("Как распознаются команды") {
                Text(
                    "Фраза разбирается прямо на телефоне, без интернета (интернет может " +
                        "понадобиться только самому распознаванию речи Google).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("1. Раздел", fontWeight = FontWeight.SemiBold)
                Text(
                    "Определяется по названию раздела или по его словам-подсказкам. " +
                        "Окончания не важны: «трата», «траты», «в траты» — один и тот же раздел.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("2. Сумма или количество", fontWeight = FontWeight.SemiBold)
                Text(
                    "«254 рубля 55 копеек», «254,55», «двести пятьдесят четыре рубля», «30 раз».",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("3. Описание", fontWeight = FontWeight.SemiBold)
                Text(
                    "Всё остальное попадает в описание записи. Дата и время ставятся автоматически.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("4. Уточнения", fontWeight = FontWeight.SemiBold)
                Text(
                    "Если не понятен раздел или число, приложение спросит. " +
                        "Если у двух разделов совпадают слова-подсказки, оно не угадывает, " +
                        "а спрашивает, в какой раздел записать. Чтобы не спрашивать, " +
                        "скажите «в раздел …» с названием. Скажите «отмена», чтобы прервать.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("5. Новый раздел голосом", fontWeight = FontWeight.SemiBold)
                Text(
                    "«Создай раздел Игры» — приложение создаст раздел и спросит единицу измерения. " +
                        "Можно сразу всё одной фразой: «Создай раздел Игры единица минуты слова играл партия». " +
                        "Единицы: рубли, раз, штуки, минуты, часы, дни, километры, граммы, литры и другие.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("6. Тип записи — слайдер на странице «Голос»", fontWeight = FontWeight.SemiBold)
                Text(
                    "Справа от кнопки записи — слайдер: Задача, Заметка, Статистика (листается по кругу). " +
                        "Задача: фраза целиком попадает в список задач. " +
                        "Заметка: текст дословно записывается в раздел заметок, найденный по названию или словам-подсказкам. " +
                        "Статистика: по словам определяется раздел, из фразы берётся сумма или число. " +
                        "Если раздел не понятен, приложение уточнит. " +
                        "Новый раздел заметок: «Создай раздел Рестораны заметки».",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            SettingsCard("Ваши разделы и слова-подсказки") {
                if (sections.isEmpty()) {
                    Text("Разделов пока нет.", style = MaterialTheme.typography.bodyMedium)
                }
                sections.forEachIndexed { index, s ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                    Text(s.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (s.isNotes) "Заметки" else
                            "Единица: ${s.unit.ifBlank { "—" }} · " +
                                if (s.askValue) "число уточняется" else "без числа = 1",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Слова: " + s.keywords.replace(",", ", ").ifBlank { "не заданы" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (s.isNotes) "Пример: «Заметка. ${s.name} — …»" else "Пример: «Добавь в раздел ${s.name.lowercase()} …»",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "Слова меняются на страницах «Заметки» и «Статистика»: ⋮ у раздела → Изменить.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsCard("О приложении") {
                Text("Голосовой дневник · версия 1.0", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Все данные хранятся только на этом телефоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun ThemeRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
