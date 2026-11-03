package uz.botmaker

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Пароли не хранятся: только соль + PBKDF2-HMAC-SHA256 (120 000 итераций). */
object Auth {
    const val ITERATIONS = 120_000
    private val rnd = SecureRandom()
    val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun newSalt(): ByteArray {
        val b = ByteArray(16)
        rnd.nextBytes(b)
        return b
    }

    fun hash(password: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    fun normLogin(s: String): String = s.trim().lowercase()
}
