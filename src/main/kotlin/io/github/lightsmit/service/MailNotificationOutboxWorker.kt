package io.github.lightsmit.service

import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailOutboxItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

class MailNotificationOutboxWorker(
    private val repository: MailNotificationOutboxRepository,
    private val failureClassifier: TelegramDeliveryFailureClassifier,
    private val deliver: suspend (MailOutboxItem) -> Long,
    private val pollingInterval: Duration = Duration.ofSeconds(1),
    private val nowProvider: () -> Instant = Instant::now,
) {

    private val logger =
        LoggerFactory.getLogger(MailNotificationOutboxWorker::class.java)

    init {
        require(
            !pollingInterval.isZero &&
                    !pollingInterval.isNegative,
        ) {
            "Outbox polling interval must be positive"
        }
    }

    suspend fun run() {
        recoverInterrupted()

        logger.info("Mail notification outbox worker started")

        while (currentCoroutineContext().isActive) {
            try {
                val processed = processNextDue()

                if (!processed) {
                    delay(pollingInterval.toMillis())
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.error(
                    "Mail notification outbox worker iteration failed: {}",
                    exception.javaClass.simpleName,
                )

                delay(pollingInterval.toMillis())
            }
        }
    }

    fun recoverInterrupted(): Int {
        val recoveredCount = repository.recoverInterrupted(
            now = nowProvider(),
        )

        if (recoveredCount > 0) {
            logger.warn(
                "Recovered {} interrupted mail notification task(s)",
                recoveredCount,
            )
        }

        return recoveredCount
    }

    internal suspend fun processNextDue(): Boolean {
        val item = repository.claimNextDue(
            now = nowProvider(),
        ) ?: return false

        try {
            val telegramMessageId = deliver(item)

            repository.markSent(
                id = item.id,
                telegramMessageId = telegramMessageId,
                now = nowProvider(),
            )

            logger.info(
                "Outbox item {} sent for email UID {} from account {}",
                item.id,
                item.uid,
                item.accountKey,
            )
        } catch (exception: CancellationException) {

            throw exception
        } catch (exception: Exception) {
            handleDeliveryFailure(
                item = item,
                exception = exception,
            )
        }

        return true
    }

    private fun handleDeliveryFailure(
        item: MailOutboxItem,
        exception: Exception,
    ) {
        val failedAttempts = item.attempts + 1

        when (
            val decision = failureClassifier.classify(
                exception = exception,
                failedAttempts = failedAttempts,
            )
        ) {
            is DeliveryFailureDecision.Retry -> {
                val failedAt = nowProvider()
                val nextAttemptAt = failedAt.plus(decision.delay)

                repository.markRetry(
                    id = item.id,
                    nextAttemptAt = nextAttemptAt,
                    error = decision.reason,
                    now = failedAt,
                )

                logger.warn(
                    "Outbox item {} for email UID {} failed on attempt {}. " +
                            "Retry scheduled in {} seconds",
                    item.id,
                    item.uid,
                    failedAttempts,
                    decision.delay.seconds,
                )
            }

            is DeliveryFailureDecision.Dead -> {
                repository.markDead(
                    id = item.id,
                    error = decision.reason,
                    now = nowProvider(),
                )

                logger.error(
                    "Outbox item {} for email UID {} became DEAD after attempt {}",
                    item.id,
                    item.uid,
                    failedAttempts,
                )
            }
        }
    }
}