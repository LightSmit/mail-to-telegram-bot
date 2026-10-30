package io.github.lightsmit.mail

import io.github.lightsmit.config.MailAccountConfig
import jakarta.mail.Folder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.slf4j.LoggerFactory
import kotlin.time.TimeSource

class ImapIdleWatcher(
    private val onIdleMailboxChanged:
        suspend (MailAccountConfig, IMAPFolder) -> Unit,
    private val onSafetyMailboxCheck:
        suspend (MailAccountConfig) -> Unit,
    private val reconnectDelaySeconds: Long,
    private val safetyPollSeconds: Long,
    // Сообщают монитору здоровья ящиков: подключение удалось / сорвалось
    private val onMailboxConnected: (MailAccountConfig) -> Unit = {},
    private val onMailboxFailure: (MailAccountConfig, Exception) -> Unit = { _, _ -> },
) {

    private val logger =
        LoggerFactory.getLogger(ImapIdleWatcher::class.java)

    init {
        require(reconnectDelaySeconds > 0)
        require(safetyPollSeconds > 0)
    }

    suspend fun watch(account: MailAccountConfig) = supervisorScope {
        val idleJob = launch {
            runIdleReconnectLoop(account)
        }
        val safetyJob = launch {
            runSafetyPollLoop(account)
        }
        joinAll(idleJob, safetyJob)
    }

    private suspend fun runIdleReconnectLoop(
        account: MailAccountConfig,
    ) {
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            val startedAt = TimeSource.Monotonic.markNow()
            try {
                val supported = watchUsingIdle(account)
                if (!supported) {
                    logger.warn(
                        "Mailbox {} does not support IMAP IDLE; safety polling remains active",
                        account.username,
                    )
                    return
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // Соединение продержалось долго - значит, вход работает, это обычный обрыв
                if (startedAt.elapsedNow().inWholeSeconds >= HEALTHY_SESSION_SECONDS) {
                    consecutiveFailures = 0
                }
                consecutiveFailures++
                onMailboxFailure(account, exception)

                val delayMillis = backoffMillis(consecutiveFailures)
                logger.warn(
                    "Mailbox monitoring failed for {}: {} ({}). Attempt {}, reconnecting in {} seconds",
                    account.username,
                    exception.javaClass.simpleName,
                    exception.message,
                    consecutiveFailures,
                    delayMillis / 1_000,
                )
                delay(delayMillis)
            }
        }
    }

    private suspend fun watchUsingIdle(
        account: MailAccountConfig,
    ): Boolean {
        val session = ImapSessionFactory.create(
            account = account,
            readTimeoutMillis = IDLE_READ_TIMEOUT_MILLIS,
        )
        val store = session.getStore("imaps") as? IMAPStore
            ?: error("The configured store is not an IMAPStore")
        var inbox: IMAPFolder? = null

        try {
            runInterruptible(Dispatchers.IO) {
                store.connect(
                    account.host,
                    account.port,
                    account.username,
                    account.password,
                )
            }

            if (!store.hasCapability("IDLE")) return false

            val openedInbox = store.getFolder("INBOX") as? IMAPFolder
                ?: error("Mailbox ${account.name} does not provide an IMAP folder")
            inbox = openedInbox

            runInterruptible(Dispatchers.IO) {
                openedInbox.open(Folder.READ_ONLY)
            }

            logger.info(
                "IMAP IDLE monitoring started for {}",
                account.username,
            )
            onMailboxConnected(account)

            onIdleMailboxChanged(account, openedInbox)

            while (currentCoroutineContext().isActive) {
                runInterruptible(Dispatchers.IO) {
                    openedInbox.idle(true)
                }

                if (!currentCoroutineContext().isActive) break

                logger.info(
                    "IMAP IDLE event received for {}",
                    account.username,
                )
                val timer = TimeSource.Monotonic.markNow()
                onIdleMailboxChanged(account, openedInbox)
                logger.info(
                    "IMAP IDLE event processed for {} in {} ms",
                    account.username,
                    timer.elapsedNow().inWholeMilliseconds,
                )
            }

            return true
        } finally {
            runCatching {
                if (inbox?.isOpen == true) inbox.close(false)
            }
            runCatching {
                if (store.isConnected) store.close()
            }
        }
    }

    private suspend fun runSafetyPollLoop(
        account: MailAccountConfig,
    ) {
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            // При ошибках опрашиваем реже, чтобы почтовик не принял нас за перебор паролей
            val extraDelay = if (consecutiveFailures == 0) 0L else backoffMillis(consecutiveFailures)
            delay(safetyPollSeconds * 1_000 + extraDelay)

            try {
                onSafetyMailboxCheck(account)
                consecutiveFailures = 0
                onMailboxConnected(account)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                consecutiveFailures++
                onMailboxFailure(account, exception)
                logger.debug(
                    "Safety mail check failed for {}: {} ({})",
                    account.username,
                    exception.javaClass.simpleName,
                    exception.message,
                )
            }
        }
    }

    // 5 с, 10 с, 20 с ... но не больше 10 минут
    private fun backoffMillis(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 16)
        val delaySeconds = (reconnectDelaySeconds shl exponent).coerceAtMost(MAX_BACKOFF_SECONDS)
        return delaySeconds * 1_000
    }

    private companion object {
        const val IDLE_READ_TIMEOUT_MILLIS = 25 * 60 * 1_000
        const val HEALTHY_SESSION_SECONDS = 120L
        const val MAX_BACKOFF_SECONDS = 600L
    }
}
