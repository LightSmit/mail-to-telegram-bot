package io.github.lightsmit.service

import io.github.lightsmit.storage.BotUserRepository
import io.github.lightsmit.storage.MailAccountRepository
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailOutboxOperation
import io.github.lightsmit.storage.MailOutboxStatus
import io.github.lightsmit.telegram.TelegramApiException
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Команды администратора: статистика, рассылка, ручной бэкап. */
class AdminService(
    val adminChatIds: Set<Long>,
    private val botUsers: BotUserRepository,
    private val accounts: MailAccountRepository,
    private val outbox: MailNotificationOutboxRepository,
    private val healthMonitor: MailboxHealthMonitor,
    private val backupService: BackupService,
    private val telegramClient: TelegramClient,
    private val startedAt: Instant = Instant.now(),
) {
    private val logger = LoggerFactory.getLogger(AdminService::class.java)
    private val pendingBroadcasts = ConcurrentHashMap<Long, String>()
    private val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

    fun isAdmin(userId: Long): Boolean = userId in adminChatIds

    suspend fun statsText(): String {
        val now = Instant.now()
        val day = now.minus(Duration.ofDays(1))
        val week = now.minus(Duration.ofDays(7))

        val allAccounts = accounts.findAll()
        val statuses = allAccounts.groupingBy { account -> healthMonitor.health(account.id).status }.eachCount()

        val sentDay = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.SENT, day)
        val sentWeek = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.SENT, week)
        val deadWeek = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.DEAD, week)
        val queued = outbox.countByStatus(MailOutboxStatus.PENDING) + outbox.countByStatus(MailOutboxStatus.RETRY)

        val uptime = Duration.between(startedAt, now)
        val backup = backupService.lastBackup

        return buildString {
            appendLine("📊 Статистика")
            appendLine()
            appendLine("Пользователи")
            appendLine("всего: ${botUsers.count()}, с ящиками: ${accounts.countOwners()}")
            appendLine("новых за неделю: ${botUsers.countFirstSeenSince(week)}, активных за неделю: ${botUsers.countSeenSince(week)}")
            appendLine("заблокировали бота: ${botUsers.countBlocked()}")
            appendLine()
            appendLine("Ящики: ${allAccounts.size}")
            appendLine("🟢 работают: ${statuses[MailboxStatus.OK] ?: 0}")
            appendLine("🟡 подключаются: ${statuses[MailboxStatus.CONNECTING] ?: 0}")
            appendLine("🔴 с ошибкой: ${statuses[MailboxStatus.ERROR] ?: 0}")
            appendLine()
            appendLine("Уведомления о письмах")
            appendLine("за сутки: $sentDay, за неделю: $sentWeek")
            appendLine("не доставлено за неделю: $deadWeek, в очереди сейчас: $queued")
            appendLine()
            appendLine("Работает без перезапуска: ${uptime.toDays()} д ${uptime.toHoursPart()} ч ${uptime.toMinutesPart()} мин")
            append("Последний бэкап: ")
            append(backup?.let { "${dateTime.format(it.createdAt)}, ${it.sizeBytes / 1024} КБ" } ?: "еще не было с момента запуска")
            appendLine()
            appendLine()
            appendLine("Команды: /broadcast текст - рассылка всем, /backup - бэкап сейчас")
        }.trimEnd()
    }

    /** Первый шаг рассылки: запоминаем текст и показываем предпросмотр. */
    suspend fun prepareBroadcast(adminId: Long, text: String): String {
        if (text.isBlank()) {
            return "Напишите текст после команды: /broadcast Привет! У нас обновление..."
        }
        pendingBroadcasts[adminId] = text
        val recipients = botUsers.findReachableIds().size
        return buildString {
            appendLine("Предпросмотр рассылки для $recipients пользователей:")
            appendLine()
            appendLine(text)
            appendLine()
            append("Отправить: /broadcast_send, отменить: /broadcast_cancel")
        }
    }

    fun cancelBroadcast(adminId: Long): String =
        if (pendingBroadcasts.remove(adminId) != null) "Рассылка отменена." else "Нет подготовленной рассылки."

    /** Второй шаг: отправка. Вызывать в фоне: при многих пользователях идет долго. */
    suspend fun sendBroadcast(adminId: Long): String {
        val text = pendingBroadcasts.remove(adminId)
            ?: return "Нет подготовленной рассылки. Сначала: /broadcast текст"

        var delivered = 0
        var blocked = 0
        var failed = 0

        for (userId in botUsers.findReachableIds()) {
            try {
                telegramClient.sendMessage(chatId = userId, text = text)
                delivered++
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: TelegramApiException) {
                if (exception.errorCode == 403) {
                    botUsers.markBlocked(userId)
                    blocked++
                } else {
                    failed++
                }
            } catch (exception: Exception) {
                failed++
            }
            // Лимит Telegram - около 30 сообщений в секунду
            delay(BROADCAST_PAUSE_MILLIS)
        }

        logger.info("Broadcast by {}: delivered {}, blocked {}, failed {}", adminId, delivered, blocked, failed)
        return "Рассылка завершена: доставлено $delivered, заблокировали бота $blocked, ошибок $failed."
    }

    suspend fun backupText(): String {
        val result = backupService.backupNow(notifyAdmins = true)
        return "Бэкап готов: ${result.file.fileName}, ${result.sizeBytes / 1024} КБ. Файл отправлен администраторам."
    }

    private companion object {
        const val BROADCAST_PAUSE_MILLIS = 50L
    }
}
