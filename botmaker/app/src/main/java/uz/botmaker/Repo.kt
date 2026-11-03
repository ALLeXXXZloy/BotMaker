package uz.botmaker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class AppState(
    val ready: Boolean = false,
    val storageError: String? = null,
    val login: String? = null,
    val name: String = "",
    val data: UserData = UserData(),
    /** Временные пометки о ботах (ошибки), не сохраняются. */
    val botNotes: Map<Long, String> = emptyMap(),
)

data class CreateResult(val error: String?, val link: String?)

object Repo {
    private const val TAG = "Repo"
    private const val MAX_BOTS = 20
    private val TOKEN_RE = Regex("\\d{5,}:[A-Za-z0-9_-]{30,}")

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    /** Короткие сообщения для Snackbar. */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var app: Context
    private val lock = Any()
    private var failCount = 0
    private var lockedUntil = 0L

    // ------------------------------------------------------------------ запуск

    fun attach(ctx: Context) {
        app = ctx.applicationContext
        try {
            Storage.open(app)
        } catch (e: Exception) {
            Log.e(TAG, "storage open failed", e)
            _state.value = AppState(ready = true, storageError = "Не удалось открыть защищённое хранилище.")
            return
        }
        try {
            openSession()
        } catch (e: Exception) {
            Log.e(TAG, "session open failed", e)
            _state.value = AppState(ready = true, storageError = "Не удалось прочитать данные приложения.")
        }
    }

    fun resetStorage() {
        try {
            Storage.reset(app)
            _state.value = AppState(ready = true)
        } catch (e: Exception) {
            _state.value = AppState(ready = true, storageError = "Сброс не удался: ${e.message}")
        }
    }

    private fun openSession() {
        val login = Storage.session()
        if (login != null && Storage.account(login) != null) {
            load(login)
        } else {
            _state.value = AppState(ready = true)
        }
    }

    private fun load(login: String) {
        val acc = Storage.account(login)
        val data = try {
            Storage.data(login)
        } catch (e: Exception) {
            Log.e(TAG, "data read failed", e)
            UserData()
        }
        _state.value = AppState(ready = true, login = login, name = acc?.name ?: "", data = data)
        ensureHost()
    }

    // ------------------------------------------------------------------ аккаунт

    suspend fun register(name: String, loginRaw: String, password: String, repeat: String): String? {
        val login = Auth.normLogin(loginRaw)
        if (name.isBlank()) return "Введите имя"
        if (!Auth.EMAIL.matches(login)) return "Введите корректный e-mail"
        if (password.length < 8) return "Пароль — минимум 8 символов"
        if (password != repeat) return "Пароли не совпадают"
        if (Storage.account(login) != null) return "Аккаунт с таким e-mail уже есть на этом телефоне"
        val salt = Auth.newSalt()
        val hash = withContext(Dispatchers.Default) { Auth.hash(password, salt) }
        Storage.saveAccount(login, Account(name.trim(), Auth.b64(salt), Auth.b64(hash), Auth.ITERATIONS))
        Storage.saveData(login, UserData())
        Storage.setSession(login)
        load(login)
        return null
    }

    suspend fun login(loginRaw: String, password: String): String? {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) return "Слишком много попыток. Подождите ${(lockedUntil - now) / 1000 + 1} с"
        val login = Auth.normLogin(loginRaw)
        val acc = Storage.account(login)
        val ok = if (acc == null) {
            // выравниваем время ответа, чтобы не выдавать, существует ли аккаунт
            withContext(Dispatchers.Default) { Auth.hash(password, ByteArray(16)) }
            false
        } else {
            val h = withContext(Dispatchers.Default) { Auth.hash(password, Auth.unb64(acc.salt), acc.iter) }
            MessageDigest.isEqual(h, Auth.unb64(acc.hash))
        }
        if (!ok) {
            failCount++
            if (failCount >= 5) {
                lockedUntil = SystemClock.elapsedRealtime() + 30_000
                failCount = 0
            }
            return "Неверный e-mail или пароль"
        }
        failCount = 0
        Storage.setSession(login)
        load(login)
        return null
    }

    fun logout() {
        try {
            Storage.setSession(null)
        } catch (e: Exception) {
            Log.w(TAG, "logout: ${e.message}")
        }
        _state.value = AppState(ready = true) // HostService увидит пустое состояние и остановится
    }

    // ------------------------------------------------------------------ менеджер-бот

    /** Проверяет токен менеджера через getMe и сохраняет его. Возвращает текст ошибки или null. */
    suspend fun saveManager(tokenRaw: String): String? {
        val token = tokenRaw.trim()
        if (!TOKEN_RE.matches(token)) return "Токен выглядит неверно. Он имеет вид 123456789:AAE…"
        val me: JSONObject = try {
            withContext(Dispatchers.IO) { Tg.call(token, "getMe") as JSONObject }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TgError) {
            return if (e.code == 401) "Telegram не принял токен. Проверьте, что он скопирован полностью." else "Ошибка Telegram: ${e.message}"
        } catch (e: Exception) {
            return "Нет связи с Telegram. Проверьте интернет."
        }
        if (!me.optBoolean("is_bot", false)) return "Это не токен бота."
        val username = me.optString("username", "")
        val can = me.optBoolean("can_manage_bots", false)
        mutate { it.copy(managerToken = token, managerUsername = username, canManage = can) }
        return null
    }

    suspend fun recheckManager(): String? {
        val t = _state.value.data.managerToken
        return if (t.isEmpty()) "Токен не задан" else saveManager(t)
    }

    // ------------------------------------------------------------------ создание ботов

    fun createPending(nameRaw: String, usernameRaw: String, welcomeRaw: String, faqText: String): CreateResult {
        val d = _state.value.data
        val name = nameRaw.trim()
        val username = usernameRaw.trim().removePrefix("@")
        if (d.managerUsername.isEmpty()) return CreateResult("Сначала подключите менеджер-бота в настройках", null)
        if (name.isEmpty() || name.length > 64) return CreateResult("Имя бота — от 1 до 64 символов", null)
        if (!BotLogic.validUsername(username)) {
            return CreateResult("Username: латиница, цифры и «_», 5–32 символа, должен оканчиваться на «bot»", null)
        }
        val taken = d.bots.any { it.username.equals(username, true) } || d.pending.any { it.username.equals(username, true) }
        if (taken) return CreateResult("Такой username уже есть в вашем списке", null)
        if (d.bots.size + d.pending.size >= MAX_BOTS) return CreateResult("Достигнут лимит: $MAX_BOTS ботов", null)
        val faq = BotLogic.parseFaq(faqText)
        if (faqText.isNotBlank() && faq.isEmpty()) {
            return CreateResult("Автоответы: нужны строки вида «слово, слово | ответ»", null)
        }
        val welcome = welcomeRaw.trim().take(1000)
        val p = Pending(username, BotConfig(name, welcome, faq), System.currentTimeMillis())
        mutate { it.copy(pending = it.pending + p) }
        return CreateResult(null, creationLink(d.managerUsername, p))
    }

    fun creationLink(manager: String, p: Pending): String =
        "https://t.me/newbot/$manager/${p.username}?name=${Uri.encode(p.config.name)}"

    fun cancelPending(username: String) {
        mutate { d -> d.copy(pending = d.pending.filter { !it.username.equals(username, true) }) }
    }

    /** Вызывается HostService, когда Telegram сообщил о подтверждённом боте (update managed_bot). */
    fun onManagedBot(login: String, id: Long, username: String, token: String): BotRecord? {
        var created: BotRecord? = null
        mutate(login) { d ->
            val existing = d.bots.firstOrNull { it.id == id }
            if (existing != null) {
                // токен заменён — просто обновляем
                d.copy(bots = d.bots.map { if (it.id == id) it.copy(token = token) else it })
            } else {
                val p = d.pending.firstOrNull { it.username.equals(username, true) }
                if (p == null) {
                    d // бот не из наших заявок — игнорируем
                } else {
                    val rec = BotRecord(id, username, token, p.config, true, System.currentTimeMillis())
                    created = rec
                    d.copy(bots = d.bots + rec, pending = d.pending - p)
                }
            }
        }
        val rec = created
        if (rec != null) events.tryEmit("Бот @${rec.username} создан и запущен")
        return rec
    }

    /** Описание, краткое описание и команды нового бота. Блокирующий вызов — только из IO. */
    fun configureChild(bot: BotRecord) {
        val commands = JSONArray()
            .put(JSONObject().put("command", "start").put("description", "Начать"))
            .put(JSONObject().put("command", "help").put("description", "Помощь"))
        val steps = listOf(
            "setMyDescription" to JSONObject().put("description", bot.config.welcome.take(512)),
            "setMyShortDescription" to JSONObject().put("short_description", bot.config.name.take(120)),
            "setMyCommands" to JSONObject().put("commands", commands),
        )
        for ((method, params) in steps) {
            try {
                Tg.call(bot.token, method, params)
            } catch (e: Exception) {
                Log.w(TAG, "$method для @${bot.username}: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ управление ботами

    fun toggleBot(id: Long, enabled: Boolean) {
        mutate { d -> d.copy(bots = d.bots.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
        if (enabled) clearNote(id)
    }

    fun removeBot(id: Long) {
        mutate { d -> d.copy(bots = d.bots.filter { it.id != id }) }
    }

    fun updateConfig(id: Long, welcomeRaw: String, faqText: String): String? {
        val faq = BotLogic.parseFaq(faqText)
        if (faqText.isNotBlank() && faq.isEmpty()) return "Автоответы: нужны строки вида «слово, слово | ответ»"
        val welcome = welcomeRaw.trim().take(1000)
        var updated: BotRecord? = null
        mutate { d ->
            d.copy(bots = d.bots.map {
                if (it.id == id) {
                    val n = it.copy(config = it.config.copy(welcome = welcome, faq = faq))
                    updated = n
                    n
                } else it
            })
        }
        val u = updated
        if (u != null) {
            scope.launch {
                try {
                    Tg.call(u.token, "setMyDescription", JSONObject().put("description", welcome.take(512)))
                } catch (e: Exception) {
                    Log.w(TAG, "setMyDescription: ${e.message}")
                }
            }
        }
        return null
    }

    fun setNote(id: Long, note: String) {
        synchronized(lock) { _state.value = _state.value.let { it.copy(botNotes = it.botNotes + (id to note)) } }
    }

    private fun clearNote(id: Long) = clearNoteIfAny(id)

    /** Убирает пометку об ошибке, только если она есть (чтобы не рассылать лишние обновления состояния). */
    fun clearNoteIfAny(id: Long) {
        synchronized(lock) {
            val s = _state.value
            if (s.botNotes.containsKey(id)) _state.value = s.copy(botNotes = s.botNotes - id)
        }
    }

    fun say(message: String) {
        events.tryEmit(message)
    }

    // ------------------------------------------------------------------ внутреннее

    /** Меняет данные текущего аккаунта и сразу сохраняет. [login] — защита от гонки при выходе из аккаунта. */
    private fun mutate(login: String? = null, f: (UserData) -> UserData): Boolean {
        synchronized(lock) {
            val s = _state.value
            val current = s.login ?: return false
            if (login != null && login != current) return false
            val nd = f(s.data)
            _state.value = s.copy(data = nd)
            try {
                Storage.saveData(current, nd)
            } catch (e: Exception) {
                Log.e(TAG, "save failed", e)
            }
        }
        ensureHost()
        return true
    }

    private fun ensureHost() {
        val s = _state.value
        val need = s.login != null && s.data.managerToken.isNotEmpty() &&
            (s.data.pending.isNotEmpty() || s.data.bots.any { it.enabled })
        if (!need || HostService.running) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, HostService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "не удалось запустить службу: ${e.message}")
        }
    }
}
