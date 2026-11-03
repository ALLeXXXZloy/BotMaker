package uz.botmaker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch

@Composable
fun Root() {
    val st by Repo.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        Repo.events.collect { snack.showSnackbar(it) }
    }
    Box(Modifier.fillMaxSize()) {
        when {
            !st.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            st.storageError != null -> StorageErrorScreen(st.storageError ?: "")
            st.login == null -> AuthScreen()
            st.data.managerToken.isEmpty() -> SetupScreen(first = true, onDone = {})
            else -> MainNav(st)
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun StorageErrorScreen(message: String) {
    Screen(title = "Хранилище недоступно") {
        Text(message)
        Text(
            "Так бывает, если Android сменил ключи шифрования (например, после восстановления телефона). " +
                "Можно сбросить хранилище — но аккаунты и боты, созданные на этом телефоне, будут удалены. " +
                "Сами боты в Telegram останутся.",
        )
        Button(onClick = { Repo.resetStorage() }, modifier = Modifier.fillMaxWidth()) {
            Text("Сбросить хранилище")
        }
    }
}

@Composable
fun AuthScreen() {
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var login by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var pass2 by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Screen(title = if (register) "Регистрация" else "Вход") {
        Text("BotMaker создаёт Telegram-ботов и запускает их прямо с вашего телефона.")
        if (register) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("Имя") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = login,
            onValueChange = { login = it.trim() },
            label = { Text("E-mail") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(pass, { pass = it }, if (register) "Пароль (минимум 8 символов)" else "Пароль")
        if (register) {
            PasswordField(pass2, { pass2 = it }, "Повторите пароль")
        }
        val err = error
        if (err != null) {
            Text(err, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    error = if (register) Repo.register(name, login, pass, pass2) else Repo.login(login, pass)
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Подождите…" else if (register) "Зарегистрироваться" else "Войти")
        }
        TextButton(onClick = { register = !register; error = null }) {
            Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться")
        }
        Text(
            "Аккаунт хранится только на этом телефоне (в зашифрованном виде). " +
                "Если удалить приложение или его данные, аккаунт и список ботов пропадут.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun SetupScreen(first: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var token by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Screen(title = "Менеджер-бот", onBack = if (first) null else onDone) {
        Text(
            "Telegram разрешает автоматически создавать ботов только через «менеджер-бота» — " +
                "вашего собственного бота с особым режимом. Это нужно сделать один раз:",
        )
        Text(
            "1. Откройте @BotFather и создайте обычного бота командой /newbot.\n" +
                "2. В мини-приложении @BotFather откройте настройки этого бота и включите «Bot Management Mode».\n" +
                "3. Скопируйте токен бота и вставьте его ниже.",
        )
        OutlinedButton(onClick = { openUrl(ctx, "https://t.me/BotFather") }) {
            Text("Открыть @BotFather")
        }
        PasswordField(token, { token = it.trim() }, "Токен менеджер-бота")
        val err = error
        if (err != null) {
            Text(err, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    val r = Repo.saveManager(token)
                    busy = false
                    if (r == null) onDone() else error = r
                }
            },
            enabled = !busy && token.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Проверяю…" else "Проверить и сохранить")
        }
        Text(
            "Токен хранится только на этом телефоне в зашифрованном виде.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun MainNav(st: AppState) {
    var route by rememberSaveable { mutableStateOf("home") }
    BackHandler(enabled = route != "home") {
        route = if (route == "manager") "settings" else "home"
    }
    when {
        route == "home" -> HomeScreen(
            st = st,
            onCreate = { route = "create" },
            onOpen = { id -> route = "bot:$id" },
            onSettings = { route = "settings" },
        )
        route == "create" -> CreateScreen(onDone = { route = "home" })
        route == "settings" -> SettingsScreen(
            st = st,
            onBack = { route = "home" },
            onChangeManager = { route = "manager" },
        )
        route == "manager" -> SetupScreen(first = false, onDone = { route = "settings" })
        route.startsWith("bot:") -> {
            val id = route.removePrefix("bot:").toLongOrNull()
            val bot = st.data.bots.firstOrNull { it.id == id }
            if (bot == null) {
                LaunchedEffect(route) { route = "home" }
            } else {
                BotScreen(bot = bot, onBack = { route = "home" })
            }
        }
        else -> LaunchedEffect(route) { route = "home" }
    }
}
