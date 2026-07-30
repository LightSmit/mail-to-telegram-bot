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
        while (currentCoroutineContext().isActive) {
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
                logger.warn(
                    "Mailbox monitoring failed for {}: {}. Reconnecting in {} seconds",
                    account.username,
                    exception.javaClass.simpleName,
                    reconnectDelaySeconds,
                )
                delay(reconnectDelaySeconds * 1_000)
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
        while (currentCoroutineContext().isActive) {
            delay(safetyPollSeconds * 1_000)

            try {
                onSafetyMailboxCheck(account)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.debug(
                    "Safety mail check failed for {}: {}",
                    account.username,
                    exception.javaClass.simpleName,
                )
            }
        }
    }

    private companion object {
        const val IDLE_READ_TIMEOUT_MILLIS = 25 * 60 * 1_000
    }
}
