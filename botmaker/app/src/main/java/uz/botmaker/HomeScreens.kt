package uz.botmaker

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- список ботов

@Composable
fun HomeScreen(st: AppState, onCreate: () -> Unit, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val d = st.data

    Screen(
        title = "Мои боты",
        actions = { TextButton(onClick = onSettings) { Text("Настройки") } },
        fab = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Создать бота") },
            )
        },
    ) {
        if (!d.canManage) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("У менеджер-бота не включён «Bot Management Mode», поэтому Telegram не примет ссылку создания.")
                    Text("Откройте @BotFather → мини-приложение → настройки менеджер-бота → включите режим и нажмите «Проверить снова».")
                    Button(onClick = {
                        scope.launch {
                            val r = Repo.recheckManager()
                            val ok = Repo.state.value.data.canManage
                            Repo.say(r ?: if (ok) "Режим включён" else "Режим всё ещё выключен")
                        }
                    }) { Text("Проверить снова") }
                }
            }
        }

        for (p in d.pending) {
            PendingCard(
                p = p,
                onOpen = {
                    val link = Repo.creationLink(d.managerUsername, p)
                    if (!openUrl(ctx, link)) Repo.say("Не удалось открыть Telegram")
                },
                onCancel = { Repo.cancelPending(p.username) },
            )
        }

        for (b in d.bots) {
            val note = st.botNotes[b.id]
            val status = when {
                !b.enabled -> "Остановлен"
                note != null -> note
                else -> "Работает"
            }
            BotCard(b = b, status = status, onOpen = { onOpen(b.id) })
        }

        if (d.bots.isEmpty() && d.pending.isEmpty()) {
            Text("Ботов пока нет. Нажмите «Создать бота» — приложение подготовит ссылку, а вы подтвердите создание в Telegram.")
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun PendingCard(p: Pending, onOpen: () -> Unit, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.config.name, style = MaterialTheme.typography.titleMedium)
            Text("@${p.username}")
            Text("Ждём подтверждения в Telegram…", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text("Открыть ссылку") }
                TextButton(onClick = onCancel) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun BotCard(b: BotRecord, status: String, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.config.name, style = MaterialTheme.typography.titleMedium)
                Text("@${b.username}")
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = b.enabled, onCheckedChange = { Repo.toggleBot(b.id, it) })
        }
    }
}

// ---------------------------------------------------------------- создание

@Composable
fun CreateScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var welcome by rememberSaveable { mutableStateOf("") }
    var faq by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val userBad = username.isNotEmpty() && !BotLogic.validUsername(username.removePrefix("@"))

    Screen(title = "Новый бот", onBack = onDone) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(64) },
            label = { Text("Название бота") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.filter { c -> !c.isWhitespace() }.take(33) },
            label = { Text("Username (оканчивается на «bot»)") },
            singleLine = true,
            isError = userBad,
            supportingText = {
                Text(if (userBad) "Латиница, цифры и «_», 5–32 символа, окончание «bot»" else "Например: my_shop_bot")
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = welcome,
            onValueChange = { welcome = it.take(1000) },
            label = { Text("Приветствие (ответ на /start)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = faq,
            onValueChange = { faq = it },
            label = { Text("Автоответы") },
            supportingText = {
                Text("По строке: слова через запятую | ответ. Пусто — бот будет повторять сообщения.")
            },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        val err = error
        if (err != null) {
            Text(err, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                val r = Repo.createPending(name, username, welcome, faq)
                val link = r.link
                if (r.error != null || link == null) {
                    error = r.error ?: "Не удалось подготовить ссылку"
                } else {
                    if (openUrl(ctx, link)) {
                        Repo.say("Подтвердите создание бота в Telegram и вернитесь сюда")
                    } else {
                        clipboard.setText(AnnotatedString(link))
                        Repo.say("Telegram не найден — ссылка скопирована")
                    }
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Создать в Telegram")
        }
        Text(
            "Откроется Telegram: нажмите «Создать» — это подтверждение вам нужно дать самому, так устроен Telegram. " +
                "Дальше приложение само получит бота, настроит его и запустит.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---------------------------------------------------------------- один бот

@Composable
fun BotScreen(bot: BotRecord, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var welcome by rememberSaveable(bot.id) { mutableStateOf(bot.config.welcome) }
    var faq by rememberSaveable(bot.id) { mutableStateOf(BotLogic.faqToText(bot.config.faq)) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    Screen(title = bot.config.name, onBack = onBack) {
        Text("@${bot.username}", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = { if (!openUrl(ctx, "https://t.me/${bot.username}")) Repo.say("Не удалось открыть Telegram") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Открыть бота в Telegram")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Работает", modifier = Modifier.weight(1f))
            Switch(checked = bot.enabled, onCheckedChange = { Repo.toggleBot(bot.id, it) })
        }
        OutlinedTextField(
            value = welcome,
            onValueChange = { welcome = it.take(1000) },
            label = { Text("Приветствие (ответ на /start)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = faq,
            onValueChange = { faq = it },
            label = { Text("Автоответы") },
            supportingText = { Text("По строке: слова через запятую | ответ") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        val err = error
        if (err != null) {
            Text(err, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                val r = Repo.updateConfig(bot.id, welcome, faq)
                error = r
                if (r == null) Repo.say("Сохранено")
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сохранить")
        }
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Убрать бота из приложения", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Убрать бота из приложения?") },
            text = {
                Text("Бот перестанет отвечать. Сам бот останется в вашем аккаунте Telegram — им можно управлять через @BotFather.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    Repo.removeBot(bot.id)
                    onBack()
                }) { Text("Убрать") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Отмена") }
            },
        )
    }
}

// ---------------------------------------------------------------- настройки

@Composable
fun SettingsScreen(st: AppState, onBack: () -> Unit, onChangeManager: () -> Unit) {
    val ctx = LocalContext.current
    val d = st.data
    var confirmLogout by remember { mutableStateOf(false) }

    Screen(title = "Настройки", onBack = onBack) {
        Text("Аккаунт", style = MaterialTheme.typography.titleMedium)
        Text("${st.name} · ${st.login ?: ""}")

        Text("Менеджер-бот", style = MaterialTheme.typography.titleMedium)
        Text(if (d.managerUsername.isNotEmpty()) "@${d.managerUsername}" else "не подключён")
        Text(if (d.canManage) "Bot Management Mode включён" else "Bot Management Mode выключен — создание ботов не заработает")
        OutlinedButton(onClick = onChangeManager, modifier = Modifier.fillMaxWidth()) {
            Text("Сменить токен менеджер-бота")
        }

        Text("Работа в фоне", style = MaterialTheme.typography.titleMedium)
        Text(
            "Боты отвечают, пока приложение работает в фоне (значок в шторке уведомлений). " +
                "Чтобы Android не усыплял его, отключите оптимизацию батареи для BotMaker. " +
                "Если выключить телефон или закрыть приложение принудительно, боты замолчат до следующего запуска.",
        )
        OutlinedButton(
            onClick = {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    Repo.say("Не удалось открыть настройки батареи")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Настройки батареи")
        }

        Button(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Выйти из аккаунта")
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Боты перестанут отвечать, пока вы снова не войдёте.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    Repo.logout()
                }) { Text("Выйти") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Отмена") }
            },
        )
    }
}
