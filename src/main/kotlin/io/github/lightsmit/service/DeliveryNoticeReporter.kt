package io.github.lightsmit.service

import io.github.lightsmit.i18n.Texts
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailOutboxItem
import io.github.lightsmit.telegram.TelegramTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import java.time.Duration

/** Куда и на каком языке отправить сводку, и сама отправка. */
interface DeliveryNoticeSender {
    suspend fun texts(ownerChatId: Long): Texts
    suspend fun send(ownerChatId: Long, text: String, buttonText: String)
}

/**
 * Когда связь с Telegram возвращается, рассказывает владельцам о письмах, чьи карточки
 * могли не дойти: бот не повторяет такие отправки, чтобы не было дублей, а эта сводка
 * гарантирует, что письмо не потеряется незамеченным. Одно сообщение на владельца.
 */
class DeliveryNoticeReporter(
    private val repository: MailNotificationOutboxRepository,
    private val ownerOf: (accountKey: String) -> MailAccountOwner?,
    private val sender: DeliveryNoticeSender,
    private val healthy: () -> Boolean = { BotHealth.healthy() },
    private val interval: Duration = Duration.ofMinutes(1),
) {
    private val logger = LoggerFactory.getLogger(DeliveryNoticeReporter::class.java)

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            delay(interval.toMillis())
            try {
                reportPending()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn("Delivery notice check failed: {}", exception.javaClass.simpleName)
            }
        }
    }

    internal suspend fun reportPending() {
        // Пока Telegram недоступен, сводка тоже не дойдет: ждем восстановления
        if (!healthy()) return
        val pending = repository.pendingNotices()
        if (pending.isEmpty()) return

        val byOwner = pending.groupBy { item -> ownerOf(item.accountKey) }
        // Ящик уже удален: рассказывать некому
        byOwner[null]?.let { items -> repository.clearNotices(items.map(MailOutboxItem::id)) }

        byOwner.forEach { (owner, items) ->
            if (owner == null) return@forEach
            val reported = try {
                val t = sender.texts(owner.chatId)
                val lines = items.take(MAX_LINES).map { item -> describe(item, owner) }
                sender.send(owner.chatId, t.deliveryNotice(lines, more = items.size - lines.size), t.openApp)
                true
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: TelegramTransportException) {
                // Не удалось подключиться - попробуем через минуту. Ответа не было - сводку не повторяем
                exception.maybeDelivered
            } catch (exception: Exception) {
                // Бот заблокирован или другая постоянная ошибка
                logger.warn("Delivery notice for {} not sent: {}", owner.chatId, exception.javaClass.simpleName)
                true
            }
            if (reported) {
                repository.clearNotices(items.map(MailOutboxItem::id))
                logger.info("Delivery notice about {} email(s) sent to {}", items.size, owner.chatId)
            }
        }
    }

    private fun describe(item: MailOutboxItem, owner: MailAccountOwner): String {
        val metadata = item.emailMetadata ?: return owner.mailbox
        val from = metadata.from.take(60)
        val subject = metadata.subject.take(80)
        return "${owner.mailbox}: $from — $subject"
    }

    private companion object {
        const val MAX_LINES = 15
    }
}

/** Владелец ящика и как этот ящик показывать в сводке. */
data class MailAccountOwner(val chatId: Long, val mailbox: String)
