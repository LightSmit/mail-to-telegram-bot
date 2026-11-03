package io.github.lightsmit.mail

import io.github.lightsmit.config.MailAccountConfig
import jakarta.mail.Folder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
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
    // Как часто проверять ящик командой NOOP по уже открытому IDLE-соединению (0 - не проверять)
    private val noopIntervalSeconds: Long = 5,
    // Сообщают монитору здоровья ящиков: подключение удалось / сорвалось
    private val onMailboxConnected: (MailAccountConfig) -> Unit = {},
    private val onMailboxFailure: (MailAccountConfig, Exception) -> Unit = { _, _ -> },
    // true - контрольная проверка идет по постоянному соединению (вход в ящик один раз),
    // false - как раньше, каждая проверка с новым входом
    private val reuseSafetyConnection: Boolean = true,
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

            coroutineScope {
                // Некоторые серверы (заметно у Gmail) сообщают о новом письме по IDLE
                // с опозданием на десятки секунд. Раз в несколько секунд из другого
                // потока обращаемся к папке: это прерывает IDLE и отправляет NOOP по
                // тому же соединению, без нового входа в ящик. После этого основной
                // цикл проверяет, нет ли новых писем, и снова уходит в IDLE.
                val noopJob = if (noopIntervalSeconds > 0) {
                    launch {
                        while (isActive) {
                            delay(noopIntervalSeconds * 1_000)
                            runInterruptible(Dispatchers.IO) {
                                openedInbox.messageCount
                            }
                        }
                    }
                } else {
                    null
                }

                try {
                    while (currentCoroutineContext().isActive) {
                        runInterruptible(Dispatchers.IO) {
                            openedInbox.idle(true)
                        }

                        if (!currentCoroutineContext().isActive) break

                        val timer = TimeSource.Monotonic.markNow()
                        onIdleMailboxChanged(account, openedInbox)
                        logger.debug(
                            "IMAP IDLE wake-up processed for {} in {} ms",
                            account.username,
                            timer.elapsedNow().inWholeMilliseconds,
                        )
                    }
                } finally {
                    noopJob?.cancel()
                }
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

    /**
     * Контрольная проверка раз в safetyPollSeconds: ловит письма, о которых сервер не сообщил по IDLE
     * (Gmail иногда опаздывает на минуты). Папка "Входящие" каждый раз открывается заново - это дает
     * свежий список писем, как при новом входе, но само соединение с ящиком держится постоянно.
     * Так почтовый сервис видит один вход в несколько часов, а не четыре входа в минуту.
     */
    private suspend fun runSafetyPollLoop(
        account: MailAccountConfig,
    ) {
        var consecutiveFailures = 0
        var store: IMAPStore? = null

        try {
            while (currentCoroutineContext().isActive) {
                // При ошибках опрашиваем реже, чтобы почтовик не принял нас за перебор паролей
                val extraDelay = if (consecutiveFailures == 0) 0L else backoffMillis(consecutiveFailures)
                delay(safetyPollSeconds * 1_000 + extraDelay)

                try {
                    if (reuseSafetyConnection) {
                        store = checkOverPersistentConnection(account, store)
                    } else {
                        onSafetyMailboxCheck(account)
                    }
                    consecutiveFailures = 0
                    onMailboxConnected(account)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    closeQuietly(store)
                    store = null
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
        } finally {
            closeQuietly(store)
        }
    }

    /**
     * Проверка по уже открытому соединению. Если сервер его тихо закрыл, сразу входим заново
     * и проверяем еще раз: письмо не ждет следующего круга. Возвращает рабочее соединение.
     */
    private suspend fun checkOverPersistentConnection(
        account: MailAccountConfig,
        current: IMAPStore?,
    ): IMAPStore {
        if (current != null && current.isConnected) {
            try {
                checkInbox(account, current)
                return current
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.debug(
                    "Safety connection for {} was lost ({}), reconnecting",
                    account.username,
                    exception.javaClass.simpleName,
                )
                closeQuietly(current)
            }
        } else {
            closeQuietly(current)
        }

        val fresh = connectStore(account, SAFETY_READ_TIMEOUT_MILLIS)
        try {
            checkInbox(account, fresh)
        } catch (exception: Exception) {
            closeQuietly(fresh)
            throw exception
        }
        logger.debug("Safety connection opened for {}", account.username)
        return fresh
    }

    // Открыть "Входящие" заново, забрать новые письма и закрыть папку (соединение остается)
    private suspend fun checkInbox(
        account: MailAccountConfig,
        store: IMAPStore,
    ) {
        val inbox = store.getFolder("INBOX") as? IMAPFolder
            ?: error("Mailbox ${account.name} does not provide an IMAP folder")
        runInterruptible(Dispatchers.IO) {
            inbox.open(Folder.READ_ONLY)
        }
        try {
            onIdleMailboxChanged(account, inbox)
        } finally {
            runCatching {
                if (inbox.isOpen) inbox.close(false)
            }
        }
    }

    private suspend fun connectStore(
        account: MailAccountConfig,
        readTimeoutMillis: Int,
    ): IMAPStore {
        val session = ImapSessionFactory.create(
            account = account,
            readTimeoutMillis = readTimeoutMillis,
        )
        val store = session.getStore("imaps") as? IMAPStore
            ?: error("The configured store is not an IMAPStore")
        runInterruptible(Dispatchers.IO) {
            store.connect(
                account.host,
                account.port,
                account.username,
                account.password,
            )
        }
        return store
    }

    private fun closeQuietly(store: IMAPStore?) {
        runCatching {
            if (store?.isConnected == true) store.close()
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
        // Проверка короткая: если сервер молчит минуту, соединение считаем потерянным
        const val SAFETY_READ_TIMEOUT_MILLIS = 60 * 1_000
        const val HEALTHY_SESSION_SECONDS = 120L
        const val MAX_BACKOFF_SECONDS = 600L
    }
}
