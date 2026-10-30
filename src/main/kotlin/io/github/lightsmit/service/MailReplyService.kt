package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.config.MailAccountKeys
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.mail.OutgoingReply
import io.github.lightsmit.mail.ReplyContext
import io.github.lightsmit.mail.ReplyFormat
import io.github.lightsmit.mail.SmtpEndpoints
import io.github.lightsmit.mail.SmtpMailSender
import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Ответ на письмо из Telegram. Кнопка "Ответить" запоминает, на какое письмо
 * отвечаем; следующий текст пользователя уходит ответом с его ящика по SMTP.
 * Ожидание ответа хранится в памяти: после перезапуска бота кнопку нужно нажать снова.
 */
class MailReplyService(
    private val registry: MailAccountRegistry,
    private val imapClient: ImapMailClient,
    private val smtpSender: SmtpMailSender,
    private val clock: () -> Instant = Instant::now,
) {

    private val logger = LoggerFactory.getLogger(MailReplyService::class.java)

    data class PendingReply(
        val accountKey: String,
        val context: ReplyContext,
        val createdAt: Instant,
    )

    sealed interface StartResult {
        data class Ready(val subject: String, val recipients: String) : StartResult
        data object NotFound : StartResult
        data object NoRecipient : StartResult
        data object Unsupported : StartResult
    }

    sealed interface SendResult {
        data class Sent(val recipients: String) : SendResult
        data object NoPending : SendResult
        data object TooLong : SendResult
        data object MailboxGone : SendResult
        data object AuthFailed : SendResult
        data object Failed : SendResult
    }

    private val pending = ConcurrentHashMap<Long, PendingReply>()

    /** Начинает ответ на письмо: проверяет, что оно есть, и ждет текст от владельца ящика. */
    suspend fun start(account: MailAccountConfig, uidValidity: Long, uid: Long): StartResult {
        if (SmtpEndpoints.forImapHost(account.host) == null) return StartResult.Unsupported

        val context = imapClient.fetchReplyContext(account, uidValidity, uid)
            ?: return StartResult.NotFound
        if (context.recipients.isEmpty()) return StartResult.NoRecipient

        pending[account.ownerChatId] = PendingReply(MailAccountKeys.accountKey(account), context, clock())
        logger.info("Reply started for email UID {} from mailbox {}", uid, account.username)

        return StartResult.Ready(
            subject = context.subject.ifBlank { "(без темы)" },
            recipients = ReplyFormat.recipientsText(context),
        )
    }

    /** Ждет ли бот текст ответа от этого пользователя. */
    fun isWaiting(chatId: Long): Boolean = current(chatId) != null

    fun cancel(chatId: Long): Boolean = pending.remove(chatId) != null

    /** Отправляет текст как ответ на запомненное письмо. */
    suspend fun send(chatId: Long, body: String): SendResult {
        if (body.length > MAX_BODY_LENGTH) return SendResult.TooLong

        val reply = current(chatId) ?: return SendResult.NoPending
        // Забираем ожидание сразу: два быстрых сообщения не уйдут двумя письмами
        if (!pending.remove(chatId, reply)) return SendResult.NoPending

        val account = registry.findByKey(reply.accountKey) ?: return SendResult.MailboxGone
        val endpoint = SmtpEndpoints.forImapHost(account.host) ?: return SendResult.Failed
        val recipients = ReplyFormat.recipientsText(reply.context)

        return try {
            smtpSender.send(
                account = account,
                endpoint = endpoint,
                reply = OutgoingReply(
                    to = reply.context.recipients,
                    subject = ReplyFormat.subject(reply.context.subject),
                    body = body,
                    inReplyTo = reply.context.messageId,
                    references = ReplyFormat.references(reply.context),
                ),
            )
            logger.info("Reply sent from mailbox {} via {}", account.username, endpoint.host)
            SendResult.Sent(recipients)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: AuthenticationFailedException) {
            logger.warn("Reply from mailbox {} rejected by {}: authentication failed", account.username, endpoint.host)
            SendResult.AuthFailed
        } catch (exception: Exception) {
            logger.warn("Reply from mailbox {} via {} failed: {}", account.username, endpoint.host, exception.javaClass.simpleName)
            // Сбой сети или сервера: даем отправить тот же текст еще раз
            pending.putIfAbsent(chatId, reply)
            SendResult.Failed
        }
    }

    private fun current(chatId: Long): PendingReply? {
        val reply = pending[chatId] ?: return null
        if (Duration.between(reply.createdAt, clock()) > PENDING_TTL) {
            pending.remove(chatId, reply)
            return null
        }
        return reply
    }

    companion object {
        val PENDING_TTL: Duration = Duration.ofMinutes(30)
        // Лимит сообщения Telegram - 4096 символов, с запасом
        const val MAX_BODY_LENGTH = 10_000
    }
}
