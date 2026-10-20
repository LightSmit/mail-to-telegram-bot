package io.github.lightsmit.api

import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TelegramAuthValidator {

    private const val ALGORITHM = "HmacSHA256"

    /** Причина отказа. Нужна для диагностики: из одного лишь "401" не видно, что именно не так. */
    enum class Failure {
        EMPTY_INIT_DATA,
        MALFORMED,
        NO_HASH,
        NO_AUTH_DATE,
        EXPIRED,
        HASH_MISMATCH,
    }

    data class Result(
        val failure: Failure?,
        val authAgeSeconds: Long? = null,
    ) {
        val ok: Boolean get() = failure == null
    }

    /**
     * @param initData Строка, полученная из Telegram.WebApp.initData
     * @param botToken Токен бота от BotFather
     * @param maxAgeSeconds Максимальный возраст авторизации (защита от replay-атак)
     */
    fun validate(initData: String, botToken: String, maxAgeSeconds: Long = 86400): Boolean =
        inspect(initData, botToken, maxAgeSeconds).ok

    fun inspect(initData: String, botToken: String, maxAgeSeconds: Long = 86400): Result {
        if (initData.isBlank()) return Result(Failure.EMPTY_INIT_DATA)

        return try {
            // Парсим параметры из query-строки
            val params = initData.split("&").associate { pair ->
                val parts = pair.split("=", limit = 2)
                val key = parts[0]
                val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                key to value
            }

            val receivedHash = params["hash"] ?: return Result(Failure.NO_HASH)
            val authDate = params["auth_date"]?.toLongOrNull() ?: return Result(Failure.NO_AUTH_DATE)

            // Защита от старых токенов
            val age = System.currentTimeMillis() / 1000 - authDate
            if (age > maxAgeSeconds) return Result(Failure.EXPIRED, age)

            // Сортировка по алфавиту. В строку входят все поля, кроме hash
            // (поле signature для проверки по токену бота НЕ исключается).
            val dataCheckString = params.filterKeys { it != "hash" }
                .entries
                .sortedBy { it.key }
                .joinToString("\n") { "${it.key}=${it.value}" }

            // Secret Key: HMAC-SHA256(botToken, "WebAppData")
            val secretKeyMac = Mac.getInstance(ALGORITHM).apply {
                init(SecretKeySpec("WebAppData".toByteArray(), ALGORITHM))
            }
            val secretKey = secretKeyMac.doFinal(botToken.toByteArray())

            // Хэш: HMAC-SHA256(dataCheckString, secretKey)
            val hashMac = Mac.getInstance(ALGORITHM).apply {
                init(SecretKeySpec(secretKey, ALGORITHM))
            }
            val calculatedHash = hashMac.doFinal(dataCheckString.toByteArray())
                .joinToString("") { "%02x".format(it) }

            // Сравнение за постоянное время
            val equal = MessageDigest.isEqual(
                receivedHash.toByteArray(),
                calculatedHash.toByteArray(),
            )
            if (equal) Result(null, age) else Result(Failure.HASH_MISMATCH, age)
        } catch (e: Exception) {
            Result(Failure.MALFORMED)
        }
    }
}
