package io.github.lightsmit.service

import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.UserSettingsRepository
import org.slf4j.LoggerFactory
import java.time.Instant

/** Настройки уведомлений из приложения: сохраняет и сразу переносит отложенные письма. */
class NotificationSettingsService(
    private val repository: UserSettingsRepository,
    private val outboxRepository: MailNotificationOutboxRepository,
    private val clock: () -> Instant = Instant::now,
) {

    private val logger = LoggerFactory.getLogger(NotificationSettingsService::class.java)

    suspend fun get(userId: Long): NotificationSettings = repository.get(userId)

    suspend fun save(userId: Long, requested: NotificationSettings): NotificationSettings {
        val settings = NotificationSchedule.normalize(requested)
        repository.save(userId, settings)

        // Письма, ждущие сводки: при выключении сводки уходят сразу, при смене интервала - к новому сроку
        val now = clock()
        val deliverAt = NotificationSchedule.nextDigestAt(settings, now) ?: now
        val moved = outboxRepository.rescheduleOwnerPending(userId, deliverAt, now)

        logger.info("Notification settings of user {} updated: {} ({} pending moved)", userId, settings, moved)
        return settings
    }
}
