package io.github.lightsmit.api

import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TelegramAuthValidator {

    private const val ALGORITHM = "HmacSHA256"

    /**
     * @param initData Строка, полученная из Telegram.WebApp.initData
     * @param botToken Токен бота от BotFather
     * @param maxAgeSeconds Максимальный возраст авторизации (защита от replay-атак)
     */
    fun validate(initData: String, botToken: String, maxAgeSeconds: Long = 86400): Boolean {
        try {
            // Парсим параметры из query-строки
            val params = initData.split("&").associate { pair ->
                val parts = pair.split("=", limit = 2)
                val key = parts[0]
                val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                key to value
            }

            val receivedHash = params["hash"] ?: return false
            val authDateStr = params["auth_date"] ?: return false

            // Защита от старых токенов
            val authDate = authDateStr.toLongOrNull() ?: return false
            val now = System.currentTimeMillis() / 1000
            if (now - authDate > maxAgeSeconds) {
                return false // Токен протух (старше 24 часов)
            }

            // Сортировка по алфавиту
            val dataCheckString = params.filterKeys { it != "hash" }
                .entries
                .sortedBy { it.key }
                .joinToString("\n") { "${it.key}=${it.value}" }

            // Генерируем Secret Key: HMAC-SHA256(botToken, "WebAppData")
            val secretKeyMac = Mac.getInstance(ALGORITHM).apply {
                init(SecretKeySpec("WebAppData".toByteArray(), ALGORITHM))
            }
            val secretKey = secretKeyMac.doFinal(botToken.toByteArray())

            // Вычисляем хэш: HMAC-SHA256(dataCheckString, secretKey)
            val hashMac = Mac.getInstance(ALGORITHM).apply {
                init(SecretKeySpec(secretKey, ALGORITHM))
            }
            val calculatedHashBytes = hashMac.doFinal(dataCheckString.toByteArray())
            val calculatedHash = calculatedHashBytes.joinToString("") { "%02x".format(it) }

            // Сравниваем хэши безопасным от тайминг-атак способом
            return MessageDigest.isEqual(
                receivedHash.toByteArray(),
                calculatedHash.toByteArray()
            )
        } catch (e: Exception) {
            return false
        }
    }
}