package io.github.lightsmit.mail

import io.github.lightsmit.config.MailAccountConfig
import jakarta.mail.Folder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.slf4j.LoggerFactory
import kotlin.time.TimeSource

class ImapIdleWatcher(
    private val onIdleMailboxChanged:
    suspend (
        MailAccountConfig,
        IMAPFolder,
    ) -> Unit,

    private val onFallbackMailboxChanged:
    suspend (MailAccountConfig) -> Unit,

    private val reconnectDelaySeconds: Long,
    private val fallbackPollSeconds: Long,
) {

    private val logger =
        LoggerFactory.getLogger(ImapIdleWatcher::class.java)

    init {
        require(reconnectDelaySeconds > 0) {
            "Reconnect delay must be greater than zero"
        }

        require(fallbackPollSeconds > 0) {
            "Fallback polling interval must be greater than zero"
        }
    }

    suspend fun watch(
        account: MailAccountConfig,
    ) {
        while (currentCoroutineContext().isActive) {
            try {
                val idleSupported =
                    watchUsingIdle(account)

                if (!idleSupported) {
                    pollWithoutIdle(account)
                    return
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn(
                    "IMAP IDLE connection failed for {}. " +
                            "Reconnecting in {} seconds",
                    account.username,
                    reconnectDelaySeconds,
                    exception,
                )

                delay(
                    reconnectDelaySeconds * 1_000,
                )
            }
        }
    }

    private suspend fun watchUsingIdle(
        account: MailAccountConfig,
    ): Boolean {
        val session = ImapSessionFactory.create(
            account = account,
            readTimeoutMillis =
                IDLE_READ_TIMEOUT_MILLIS,
        )

        val store = session.getStore("imaps")
                as? IMAPStore
            ?: error(
                "The configured store is not an IMAPStore",
            )

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

            if (!store.hasCapability("IDLE")) {
                logger.warn(
                    "Mailbox {} does not support IMAP IDLE",
                    account.username,
                )

                return false
            }

            val openedInbox = store.getFolder("INBOX")
                    as? IMAPFolder
                ?: error(
                    "Mailbox ${account.name} " +
                            "does not provide an IMAP folder",
                )

            inbox = openedInbox

            runInterruptible(Dispatchers.IO) {
                openedInbox.open(Folder.READ_ONLY)
            }

            logger.info(
                "IMAP IDLE monitoring started for {}",
                account.username,
            )

            /*
             * Проверяем пропущенные письма через то же
             * уже открытое соединение.
             */
            onIdleMailboxChanged(
                account,
                openedInbox,
            )

            while (currentCoroutineContext().isActive) {
                runInterruptible(Dispatchers.IO) {
                    openedInbox.idle(true)
                }

                if (!currentCoroutineContext().isActive) {
                    break
                }

                logger.info(
                    "IMAP IDLE event received for {}",
                    account.username,
                )

                val timer =
                    TimeSource.Monotonic.markNow()

                /*
                 * Не открываем второе IMAP-соединение.
                 * Читаем письмо через openedInbox.
                 */
                onIdleMailboxChanged(
                    account,
                    openedInbox,
                )

                logger.info(
                    "IMAP IDLE event processed for {} in {} ms",
                    account.username,
                    timer.elapsedNow()
                        .inWholeMilliseconds,
                )
            }

            return true
        } finally {
            runCatching {
                if (inbox?.isOpen == true) {
                    inbox.close(false)
                }
            }

            runCatching {
                if (store.isConnected) {
                    store.close()
                }
            }
        }
    }

    private suspend fun pollWithoutIdle(
        account: MailAccountConfig,
    ) {
        logger.warn(
            "Using {}-second fallback polling for {}",
            fallbackPollSeconds,
            account.username,
        )

        while (currentCoroutineContext().isActive) {
            try {
                onFallbackMailboxChanged(account)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.error(
                    "Fallback mail check failed for {}",
                    account.username,
                    exception,
                )
            }

            delay(
                fallbackPollSeconds * 1_000,
            )
        }
    }

    private companion object {
        const val IDLE_READ_TIMEOUT_MILLIS =
            25 * 60 * 1_000
    }
}