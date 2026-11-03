package uz.botmaker

import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TgError(val code: Int, message: String, val retryAfter: Int? = null) : Exception(message)

/** Минимальный клиент Telegram Bot API (без сторонних библиотек). Токены в логи не попадают. */
object Tg {
    private const val BASE = "https://api.telegram.org"

    /** Возвращает поле `result` ответа (JSONObject, JSONArray, String, Boolean…). Блокирующий вызов. */
    fun call(token: String, method: String, params: JSONObject = JSONObject(), readTimeoutMs: Int = 15000): Any? {
        val conn = URL("$BASE/bot$token/$method").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(params.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val body = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw TgError(code, "HTTP $code")
            }
            if (!body.optBoolean("ok", false)) {
                val retry = body.optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0
                throw TgError(
                    body.optInt("error_code", code),
                    body.optString("description", "Ошибка Telegram"),
                    if (retry > 0) retry else null,
                )
            }
            return body.opt("result")
        } finally {
            conn.disconnect()
        }
    }
}
