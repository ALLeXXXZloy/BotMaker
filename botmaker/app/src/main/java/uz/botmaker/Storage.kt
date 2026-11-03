package uz.botmaker

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/** Всё (аккаунты, хэши паролей, токены ботов) лежит в зашифрованных SharedPreferences. */
object Storage {
    private const val FILE = "botmaker_secure"
    private var prefs: SharedPreferences? = null

    fun open(ctx: Context) {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        prefs = EncryptedSharedPreferences.create(
            ctx,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Полный сброс — на случай, когда хранилище не открывается (например, после смены ключей Android). */
    fun reset(ctx: Context) {
        prefs = null
        ctx.deleteSharedPreferences(FILE)
        open(ctx)
    }

    private fun p(): SharedPreferences = prefs ?: throw IllegalStateException("Хранилище не открыто")

    fun session(): String? = p().getString("session", null)

    fun setSession(login: String?) {
        val ed = p().edit()
        if (login == null) ed.remove("session") else ed.putString("session", login)
        ed.apply()
    }

    fun account(login: String): Account? {
        val s = p().getString("acc:$login", null) ?: return null
        val o = JSONObject(s)
        return Account(o.getString("name"), o.getString("salt"), o.getString("hash"), o.getInt("iter"))
    }

    fun saveAccount(login: String, a: Account) {
        val o = JSONObject().put("name", a.name).put("salt", a.salt).put("hash", a.hash).put("iter", a.iter)
        p().edit().putString("acc:$login", o.toString()).apply()
    }

    fun data(login: String): UserData {
        val s = p().getString("data:$login", null) ?: return UserData()
        return userDataFromJson(JSONObject(s))
    }

    fun saveData(login: String, d: UserData) {
        p().edit().putString("data:$login", d.toJson().toString()).apply()
    }
}
