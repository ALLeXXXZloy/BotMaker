package uz.botmaker

import org.json.JSONArray
import org.json.JSONObject

/** Автоответ: если сообщение содержит любое из ключевых слов — бот отвечает [answer]. */
data class Faq(val keys: List<String>, val answer: String)

data class BotConfig(val name: String, val welcome: String, val faq: List<Faq>)

/** Созданный и подтверждённый в Telegram бот. */
data class BotRecord(
    val id: Long,
    val username: String,
    val token: String,
    val config: BotConfig,
    val enabled: Boolean = true,
    val created: Long = 0L,
)

/** Заявка на создание: ссылка выдана, ждём подтверждения человеком в Telegram. */
data class Pending(val username: String, val config: BotConfig, val ts: Long)

data class UserData(
    val managerToken: String = "",
    val managerUsername: String = "",
    val canManage: Boolean = false,
    val bots: List<BotRecord> = emptyList(),
    val pending: List<Pending> = emptyList(),
)

data class Account(val name: String, val salt: String, val hash: String, val iter: Int)

// ---------------------------------------------------------------- JSON

fun BotConfig.toJson(): JSONObject {
    val arr = JSONArray()
    for (f in faq) {
        arr.put(JSONObject().put("keys", JSONArray(f.keys)).put("answer", f.answer))
    }
    return JSONObject().put("name", name).put("welcome", welcome).put("faq", arr)
}

fun configFromJson(o: JSONObject): BotConfig {
    val arr = o.optJSONArray("faq") ?: JSONArray()
    val faq = ArrayList<Faq>()
    for (i in 0 until arr.length()) {
        val f = arr.getJSONObject(i)
        val ks = f.getJSONArray("keys")
        val keys = ArrayList<String>()
        for (k in 0 until ks.length()) keys.add(ks.getString(k))
        faq.add(Faq(keys, f.getString("answer")))
    }
    return BotConfig(o.optString("name"), o.optString("welcome"), faq)
}

fun UserData.toJson(): JSONObject {
    val b = JSONArray()
    for (bot in bots) {
        b.put(
            JSONObject()
                .put("id", bot.id)
                .put("username", bot.username)
                .put("token", bot.token)
                .put("config", bot.config.toJson())
                .put("enabled", bot.enabled)
                .put("created", bot.created)
        )
    }
    val p = JSONArray()
    for (x in pending) {
        p.put(JSONObject().put("username", x.username).put("config", x.config.toJson()).put("ts", x.ts))
    }
    return JSONObject()
        .put("managerToken", managerToken)
        .put("managerUsername", managerUsername)
        .put("canManage", canManage)
        .put("bots", b)
        .put("pending", p)
}

fun userDataFromJson(o: JSONObject): UserData {
    val bots = ArrayList<BotRecord>()
    val b = o.optJSONArray("bots") ?: JSONArray()
    for (i in 0 until b.length()) {
        val x = b.getJSONObject(i)
        bots.add(
            BotRecord(
                id = x.getLong("id"),
                username = x.getString("username"),
                token = x.getString("token"),
                config = configFromJson(x.getJSONObject("config")),
                enabled = x.optBoolean("enabled", true),
                created = x.optLong("created", 0L),
            )
        )
    }
    val pending = ArrayList<Pending>()
    val p = o.optJSONArray("pending") ?: JSONArray()
    for (i in 0 until p.length()) {
        val x = p.getJSONObject(i)
        pending.add(Pending(x.getString("username"), configFromJson(x.getJSONObject("config")), x.optLong("ts", 0L)))
    }
    return UserData(
        managerToken = o.optString("managerToken"),
        managerUsername = o.optString("managerUsername"),
        canManage = o.optBoolean("canManage", false),
        bots = bots,
        pending = pending,
    )
}
