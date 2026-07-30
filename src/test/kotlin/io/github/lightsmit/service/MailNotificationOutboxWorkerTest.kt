package io.github.lightsmit.service

import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailOutboxStatus
import io.github.lightsmit.telegram.TelegramApiException
import io.github.lightsmit.telegram.TelegramTransportException
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.time.Instant

class MailNotificationOutboxWorkerTest {

    @Test
    fun `successful delivery marks item as sent`() = runBlocking {
        val repository = createRepository()
        val now = Instant.parse("2026-07-30T15:00:00Z")

        enqueueTestItem(
            repository = repository,
            now = now,
        )

        val worker = MailNotificationOutboxWorker(
            repository = repository,
            failureClassifier =
                TelegramDeliveryFailureClassifier(),
            deliver = {
                12345L
            },
            nowProvider = {
                now.plusSeconds(1)
            },
        )

        assertTrue(worker.processNextDue())

        val item = assertNotNull(findTestItem(repository))

        assertEquals(
            MailOutboxStatus.SENT,
            item.status,
        )
        assertEquals(
            12345L,
            item.telegramMessageId,
        )
        assertEquals(
            0,
            item.attempts,
        )
    }

    @Test
    fun `transport failure schedules retry`() = runBlocking {
        val repository = createRepository()
        val now = Instant.parse("2026-07-30T15:00:00Z")

        enqueueTestItem(
            repository = repository,
            now = now,
        )

        val worker = MailNotificationOutboxWorker(
            repository = repository,
            failureClassifier =
                TelegramDeliveryFailureClassifier(),
            deliver = {
                throw TelegramTransportException(
                    operation = "sendMessage",
                    causeType = "ConnectTimeoutException",
                )
            },
            nowProvider = {
                now
            },
        )

        assertTrue(worker.processNextDue())

        val item = assertNotNull(findTestItem(repository))

        assertEquals(
            MailOutboxStatus.RETRY,
            item.status,
        )
        assertEquals(
            1,
            item.attempts,
        )
        assertEquals(
            now.plusSeconds(5),
            item.nextAttemptAt,
        )
    }

    @Test
    fun `permanent API failure marks item as dead`() = runBlocking {
        val repository = createRepository()
        val now = Instant.parse("2026-07-30T15:00:00Z")

        enqueueTestItem(
            repository = repository,
            now = now,
        )

        val worker = MailNotificationOutboxWorker(
            repository = repository,
            failureClassifier =
                TelegramDeliveryFailureClassifier(),
            deliver = {
                throw TelegramApiException(
                    description = "Unauthorized",
                    errorCode = 401,
                )
            },
            nowProvider = {
                now
            },
        )

        assertTrue(worker.processNextDue())

        val item = assertNotNull(findTestItem(repository))

        assertEquals(
            MailOutboxStatus.DEAD,
            item.status,
        )
        assertEquals(
            1,
            item.attempts,
        )
        assertEquals(
            "Telegram API error 401: Unauthorized",
            item.lastError,
        )
    }

    @Test
    fun `worker recovers interrupted processing item`() {
        val repository = createRepository()
        val now = Instant.parse("2026-07-30T15:00:00Z")

        enqueueTestItem(
            repository = repository,
            now = now,
        )

        assertNotNull(
            repository.claimNextDue(now),
        )

        val worker = MailNotificationOutboxWorker(
            repository = repository,
            failureClassifier =
                TelegramDeliveryFailureClassifier(),
            deliver = {
                error("Delivery must not be called")
            },
            nowProvider = {
                now.plusSeconds(30)
            },
        )

        assertEquals(
            1,
            worker.recoverInterrupted(),
        )

        val item = assertNotNull(findTestItem(repository))

        assertEquals(
            MailOutboxStatus.RETRY,
            item.status,
        )
        assertEquals(
            now.plusSeconds(30),
            item.nextAttemptAt,
        )
    }

    private fun enqueueTestItem(
        repository: MailNotificationOutboxRepository,
        now: Instant,
    ) {
        repository.enqueue(
            accountKey = ACCOUNT_KEY,
            accountCode = ACCOUNT_CODE,
            uidValidity = UID_VALIDITY,
            uid = UID,
            now = now,
        )
    }

    private fun findTestItem(
        repository: MailNotificationOutboxRepository,
    ) = repository.find(
        accountKey = ACCOUNT_KEY,
        uidValidity = UID_VALIDITY,
        uid = UID,
    )

    private fun createRepository(): MailNotificationOutboxRepository {
        val directory =
            createTempDirectory("mail-outbox-worker-test-")

        return MailNotificationOutboxRepository(
            databasePath = directory.resolve("mail-bot.db"),
        )
    }

    private companion object {
        const val ACCOUNT_KEY =
            "imap.mail.ru:user@example.com"

        const val ACCOUNT_CODE =
            "abcdef123456"

        const val UID_VALIDITY = 100L
        const val UID = 200L
    }
}