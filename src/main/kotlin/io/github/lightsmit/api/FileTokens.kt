package io.github.lightsmit.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Что скачивают по ссылке: вложение index письма uid ящика accountId владельца ownerId. */
data class FileTicket(
    val ownerId: Long,
    val accountId: Long,
    val uidValidity: Long,
    val uid: Long,
    val index: Int,
    val expiresAt: Long,
)

/**
 * Короткоживущие ссылки на вложения. Telegram скачивает файл сам (WebApp.downloadFile),
 * без данных пользователя, поэтому право на файл зашито в подписанную ссылку.
 * Ключ создается при запуске: после перезапуска старые ссылки перестают работать.
 */
class FileTokens(
    private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    private val clock: () -> Instant = Instant::now,
    key: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
) {
    private val keySpec = SecretKeySpec(key, ALGORITHM)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun issue(ownerId: Long, accountId: Long, uidValidity: Long, uid: Long, index: Int): String {
        val expiresAt = clock().epochSecond + ttlSeconds
        val payload = listOf(ownerId, accountId, uidValidity, uid, index.toLong(), expiresAt).joinToString(".")
        val encoded = encoder.encodeToString(payload.toByteArray(Charsets.UTF_8))
        return encoded + "." + encoder.encodeToString(sign(encoded))
    }

    /** null - подпись не сходится, ссылка испорчена или устарела. */
    fun verify(token: String): FileTicket? {
        if (token.length > MAX_TOKEN_LENGTH) return null
        val encoded = token.substringBefore('.', missingDelimiterValue = "")
        val signature = token.substringAfter('.', missingDelimiterValue = "")
        if (encoded.isEmpty() || signature.isEmpty()) return null
        val expected = sign(encoded)
        val actual = runCatching { decoder.decode(signature) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(expected, actual)) return null

        val parts = runCatching { String(decoder.decode(encoded), Charsets.UTF_8) }.getOrNull()
            ?.split('.')
            ?.map(String::toLongOrNull)
            ?: return null
        if (parts.size != 6 || parts.any { value -> value == null }) return null
        val values = parts.map { value -> value!! }
        val ticket = FileTicket(
            ownerId = values[0],
            accountId = values[1],
            uidValidity = values[2],
            uid = values[3],
            index = values[4].toInt(),
            expiresAt = values[5],
        )
        return ticket.takeIf { value -> value.expiresAt >= clock().epochSecond && value.index >= 0 }
    }

    private fun sign(encoded: String): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(encoded.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val DEFAULT_TTL_SECONDS = 15 * 60L
        const val MAX_TOKEN_LENGTH = 512
    }
}
