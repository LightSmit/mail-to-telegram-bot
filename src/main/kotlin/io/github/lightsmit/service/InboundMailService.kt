package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.config.MailAccountKeys
import io.github.lightsmit.mail.EmailSummary
import io.github.lightsmit.storage.InboundMessageRepository
import jakarta.mail.internet.InternetHeaders
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

enum class InboundResult { ACCEPTED, DUPLICATE, FILTERED, UNKNOWN_ADDRESS, TOO_LARGE, BAD_MESSAGE }

/**
 * Прием писем, пересланных на адрес вида token@INBOUND_DOMAIN (режим «без пароля»).
 * Письмо приходит из Cloudflare Email Worker на /api/inbound целиком, в формате MIME.
 * Исходник хранится KEEP дней: из него строятся текст и вложения по кнопкам карточки.
 */
class InboundMailService(
    val domain: String,
    private val findAccount: (token: String) -> MailAccountConfig?,
    private val store: InboundMessageRepository,
    private val parse: (raw: ByteArray, uid: Long) -> EmailSummary,
    private val deliver: suspend (MailAccountConfig, Long, EmailSummary) -> Boolean,
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(InboundMailService::class.java)

    /** Адрес для пересылки по токену ящика. */
    fun addressOf(token: String): String = "$token@$domain"

    /** Ящик по адресу получателя или null, если адрес не наш. */
    fun accountFor(recipient: String): MailAccountConfig? {
        val address = recipient.trim().trim('<', '>').lowercase()
        val at = address.lastIndexOf('@')
        if (at <= 0 || address.substring(at + 1) != domain.lowercase()) return null
        // Часть после «+» не учитываем: token+anything@domain тоже наш адрес
        val token = address.substring(0, at).substringBefore('+')
        if (!TOKEN_PATTERN.matches(token)) return null
        return findAccount(token)
    }

    suspend fun receive(recipient: String, raw: ByteArray): InboundResult {
        if (raw.size > MAX_MESSAGE_BYTES) return InboundResult.TOO_LARGE
        val account = accountFor(recipient) ?: return InboundResult.UNKNOWN_ADDRESS

        // Сначала проверяем, что письмо читается: битое не сохраняем
        try {
            parse(raw, 0)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Forwarded email for mailbox {} could not be parsed: {}", account.username, exception.javaClass.simpleName)
            return InboundResult.BAD_MESSAGE
        }

        val accountKey = MailAccountKeys.accountKey(account)
        val uid = store.insert(accountKey, messageKey(raw), raw, clock())
        if (uid == null) {
            logger.info("Forwarded email for mailbox {} already received", account.username)
            return InboundResult.DUPLICATE
        }

        val summary = parse(raw, uid)
        return if (deliver(account, uid, summary)) InboundResult.ACCEPTED else InboundResult.FILTERED
    }

    suspend fun forget(account: MailAccountConfig) {
        store.deleteAccount(MailAccountKeys.accountKey(account))
    }

    companion object {
        val KEEP: Duration = Duration.ofDays(7)
        const val MAX_MESSAGE_BYTES = 30 * 1024 * 1024
        const val TOKEN_LENGTH = 10
        private const val TOKEN_CHARS = "abcdefghijkmnpqrstuvwxyz23456789"
        private val TOKEN_PATTERN = Regex("[a-z0-9]{6,32}")
        private val random = SecureRandom()

        /** Случайный токен адреса: строчные буквы и цифры без похожих (l, o, 0, 1). */
        fun newToken(): String = buildString {
            repeat(TOKEN_LENGTH) { append(TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)]) }
        }

        // Одно письмо может прийти дважды (повтор доставки): узнаем его по Message-ID
        internal fun messageKey(raw: ByteArray): String {
            val messageId = runCatching {
                InternetHeaders(ByteArrayInputStream(raw)).getHeader("Message-ID", null)
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val source = messageId?.toByteArray() ?: raw
            val digest = MessageDigest.getInstance("SHA-256").digest(source)
            return (if (messageId != null) "id:" else "raw:") + digest.joinToString("") { "%02x".format(it) }
        }
    }
}
