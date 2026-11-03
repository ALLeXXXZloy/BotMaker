package uz.botmaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Фоновая служба. Пока есть заявки на создание или включённые боты:
 *  - опрашивает менеджер-бота (ждёт update `managed_bot` после подтверждения в Telegram),
 *  - для каждого включённого бота держит свой long polling и отвечает по его настройкам.
 */
class HostService : Service() {

    companion object {
        @Volatile
        var running = false
        private const val CHANNEL = "host"
        private const val NOTIF_ID = 1
        private const val TAG = "HostService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runners = HashMap<Long, Pair<String, Job>>() // id бота -> (токен, задача)
    private var managerJob: Job? = null
    private var managerKey: String? = null
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val s = Repo.state.value
            val bots = s.data.bots.count { it.enabled }
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(bots, s.data.pending.size), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, buildNotification(bots, s.data.pending.size))
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (observeJob == null) {
            observeJob = scope.launch {
                Repo.state.collect { reconcile(it) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ согласование задач с состоянием

    private fun reconcile(st: AppState) {
        val login = st.login
        val d = st.data
        val want: Map<Long, BotRecord> =
            if (login != null) d.bots.filter { it.enabled }.associateBy { it.id } else emptyMap()

        // останавливаем удалённые, выключенные и боты с новым токеном
        val iter = runners.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val bot = want[e.key]
            if (bot == null || bot.token != e.value.first) {
                e.value.second.cancel()
                iter.remove()
            }
        }
        // запускаем недостающих
        if (login != null) {
            for ((id, bot) in want) {
                if (!runners.containsKey(id)) {
                    runners[id] = bot.token to scope.launch { runBot(login, bot) }
                }
            }
        }

        // менеджер-бот нужен, пока есть что ждать или что обслуживать
        val needManager = login != null && d.managerToken.isNotEmpty() && (d.pending.isNotEmpty() || want.isNotEmpty())
        val key = if (needManager) "$login:${d.managerToken}" else null
        if (key != managerKey) {
            managerJob?.cancel()
            managerJob = null
            managerKey = key
            if (key != null && login != null) {
                val token = d.managerToken
                managerJob = scope.launch { managerLoop(login, token) }
            }
        }

        if (want.isEmpty() && d.pending.isEmpty()) {
            running = false // чтобы Repo мог сразу запустить службу заново
            stopSelf()
            return
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(want.size, d.pending.size))
    }

    // ------------------------------------------------------------------ менеджер-бот

    private suspend fun managerLoop(login: String, token: String) {
        var offset: Long? = null
        var backoff = 1000L
        while (currentCoroutineContext().isActive) {
            try {
                val params = JSONObject()
                    .put("timeout", 20)
                    .put("allowed_updates", JSONArray().put("managed_bot"))
                if (offset != null) params.put("offset", offset)
                val arr = Tg.call(token, "getUpdates", params, 35000) as JSONArray
                backoff = 1000L
                for (i in 0 until arr.length()) {
                    val u = arr.getJSONObject(i)
                    handleManaged(login, token, u.optJSONObject("managed_bot"))
                    offset = u.getLong("update_id") + 1 // подтверждаем только после успешной обработки
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TgError) {
                if (e.code == 401) {
                    Repo.say("Токен менеджер-бота недействителен. Задайте его заново в настройках.")
                    return
                }
                delay(maxOf(backoff, (e.retryAfter ?: 0) * 1000L))
                backoff = minOf(backoff * 2, 30000L)
            } catch (e: Exception) {
                Log.w(TAG, "manager: ${e.javaClass.simpleName}")
                delay(backoff)
                backoff = minOf(backoff * 2, 30000L)
            }
        }
    }

    private fun handleManaged(login: String, managerToken: String, mb: JSONObject?) {
        val bot = mb?.optJSONObject("bot") ?: return
        val id = bot.optLong("id", 0L)
        if (id == 0L) return
        val username = bot.optString("username", "")
        val result: Any? = try {
            Tg.call(managerToken, "getManagedBotToken", JSONObject().put("user_id", id))
        } catch (e: TgError) {
            Repo.say("Не удалось получить токен @$username: ${e.message}")
            return
        }
        val token = when (result) {
            is String -> result
            is JSONObject -> result.optString("token", "")
            else -> ""
        }
        if (token.isBlank()) {
            Repo.say("Telegram вернул пустой токен для @$username")
            return
        }
        val rec = Repo.onManagedBot(login, id, username, token)
        if (rec != null) Repo.configureChild(rec)
    }

    // ------------------------------------------------------------------ созданные боты

    private suspend fun runBot(login: String, bot: BotRecord) {
        var offset: Long? = null
        var backoff = 1000L
        while (currentCoroutineContext().isActive) {
            try {
                val params = JSONObject()
                    .put("timeout", 20)
                    .put("allowed_updates", JSONArray().put("message"))
                if (offset != null) params.put("offset", offset)
                val arr = Tg.call(bot.token, "getUpdates", params, 35000) as JSONArray
                backoff = 1000L
                Repo.clearNoteIfAny(bot.id)
                for (i in 0 until arr.length()) {
                    if (!currentCoroutineContext().isActive) return
                    val u = arr.getJSONObject(i)
                    answer(bot, u)
                    offset = u.getLong("update_id") + 1
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TgError) {
                if (e.code == 401) {
                    Repo.setNote(bot.id, "Токен недействителен")
                    return
                }
                if (e.code == 409) Repo.setNote(bot.id, "Бот уже опрашивается где-то ещё")
                delay(maxOf(backoff, (e.retryAfter ?: 0) * 1000L))
                backoff = minOf(backoff * 2, 30000L)
            } catch (e: Exception) {
                Repo.setNote(bot.id, "Нет связи с Telegram")
                delay(backoff)
                backoff = minOf(backoff * 2, 30000L)
            }
        }
    }

    /** Ошибки API при ответе (например, пользователь заблокировал бота) пропускаем; сетевые — повторяем. */
    private fun answer(bot: BotRecord, update: JSONObject) {
        val m = update.optJSONObject("message") ?: return
        val text = m.optString("text", "")
        if (text.isEmpty()) return
        val chatId = m.getJSONObject("chat").getLong("id")
        val reply = BotLogic.reply(bot.config, text).take(4096)
        if (reply.isBlank()) return
        try {
            Tg.call(bot.token, "sendMessage", JSONObject().put("chat_id", chatId).put("text", reply))
        } catch (e: TgError) {
            Log.w(TAG, "sendMessage @${bot.username}: ${e.code}")
        }
    }

    // ------------------------------------------------------------------ уведомление

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Работа ботов", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(bots: Int, pending: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (pending > 0) "Ботов в работе: $bots · ждём подтверждения в Telegram: $pending" else "Ботов в работе: $bots"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setContentTitle("BotMaker")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
