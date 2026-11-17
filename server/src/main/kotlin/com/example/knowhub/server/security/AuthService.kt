package com.example.knowhub.server.security

import com.example.knowhub.server.config.AppConfig
import com.example.knowhub.server.store.DataStore
import com.example.knowhub.server.store.UserAccount
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 密码哈希（PBKDF2-HMAC-SHA256）与无状态 HMAC 令牌。
 * 令牌格式：base64url(payload) + "." + base64url(hmac(payload))，payload = userId|expiresAt。
 */
class AuthService(
    private val config: AppConfig,
    private val store: DataStore,
) {
    private val random = SecureRandom()
    private val secret: ByteArray = loadSecret(config)
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun hashPassword(password: String): Pair<String, String> {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        return Base64.getEncoder().encodeToString(pbkdf2(password, salt)) to Base64.getEncoder().encodeToString(salt)
    }

    fun verifyPassword(password: String, saltBase64: String, expectedHash: String): Boolean {
        val salt = runCatching { Base64.getDecoder().decode(saltBase64) }.getOrNull() ?: return false
        val actual = pbkdf2(password, salt)
        val expected = runCatching { Base64.getDecoder().decode(expectedHash) }.getOrNull() ?: return false
        return MessageDigest.isEqual(actual, expected)
    }

    fun issueToken(user: UserAccount, now: Long = System.currentTimeMillis()): Pair<String, Long> {
        val expiresAt = now + TOKEN_TTL_MILLIS
        val payload = "${user.userId}|$expiresAt"
        val signature = hmac(payload.toByteArray(Charsets.UTF_8))
        val token = encoder.encodeToString(payload.toByteArray(Charsets.UTF_8)) + "." + encoder.encodeToString(signature)
        return token to expiresAt
    }

    /** 返回 null 表示令牌无效/过期/账号停用。 */
    fun authenticate(token: String, now: Long = System.currentTimeMillis()): UserAccount? {
        val parts = token.split('.')
        if (parts.size != 2) return null
        val payload = runCatching { String(decoder.decode(parts[0]), Charsets.UTF_8) }.getOrNull() ?: return null
        val signature = runCatching { decoder.decode(parts[1]) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(hmac(payload.toByteArray(Charsets.UTF_8)), signature)) return null

        val fields = payload.split('|')
        if (fields.size != 2) return null
        val expiresAt = fields[1].toLongOrNull() ?: return null
        if (expiresAt < now) return null

        val user = store.snapshot().userById(fields[0]) ?: return null
        return if (user.active) user else null
    }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun hmac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(data)
    }

    companion object {
        private const val ITERATIONS = 60_000
        private const val TOKEN_TTL_MILLIS = 12L * 60 * 60 * 1000

        private fun loadSecret(config: AppConfig): ByteArray {
            config.tokenSecret?.let { return it.toByteArray(Charsets.UTF_8) }
            val file = File(config.dataDirectory(), "token.secret")
            if (file.exists()) {
                val text = file.readText(Charsets.UTF_8).trim()
                if (text.isNotEmpty()) return Base64.getDecoder().decode(text)
            }
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            file.writeText(Base64.getEncoder().encodeToString(secret), Charsets.UTF_8)
            return secret
        }
    }
}
