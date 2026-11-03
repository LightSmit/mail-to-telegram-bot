package io.github.lightsmit.service

import io.github.lightsmit.billing.SubscriptionService
import io.github.lightsmit.storage.BotUserRepository
import io.github.lightsmit.storage.UserSettingsRepository
import org.slf4j.LoggerFactory

/**
 * Удаление всех данных пользователя по его просьбе: ящики (с паролями),
 * позиции чтения, очередь уведомлений, настройки и запись о пользователе.
 * В ежедневных бэкапах данные остаются, пока бэкапы не удалятся по сроку (BACKUP_KEEP дней).
 */
class UserDataService(
    private val accountService: MailAccountService,
    private val settingsRepository: UserSettingsRepository,
    private val botUsers: BotUserRepository,
    val backupKeepDays: Int,
    // Действующая платная подписка сохраняется, чтобы человек не потерял оплаченный срок
    private val subscriptions: SubscriptionService? = null,
) {

    private val logger = LoggerFactory.getLogger(UserDataService::class.java)

    suspend fun mailboxCount(userId: Long): Int = accountService.list(userId).size

    /** Удаляет все данные пользователя. Возвращает число удаленных ящиков. */
    suspend fun deleteAll(userId: Long): Int {
        val mailboxes = accountService.deleteAllForOwner(userId)
        settingsRepository.delete(userId)
        botUsers.delete(userId)
        subscriptions?.forgetUnlessActive(userId)
        logger.info("All data of user {} deleted ({} mailboxes)", userId, mailboxes)
        return mailboxes
    }
}
