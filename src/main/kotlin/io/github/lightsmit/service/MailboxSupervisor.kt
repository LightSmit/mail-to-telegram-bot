package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.mail.ImapIdleWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Запускает и останавливает слежение (IMAP IDLE + страховочный опрос) за ящиками
 * без перезапуска бота.
 */
class MailboxSupervisor(
    private val watcher: ImapIdleWatcher,
    private val registry: MailAccountRegistry,
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(MailboxSupervisor::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val running = mutableMapOf<Long, Pair<MailAccountConfig, Job>>()

    /** Начинает следить за ящиком; если он уже отслеживается, перезапускает с новыми данными. */
    suspend fun start(account: MailAccountConfig) {
        require(account.id > 0) { "Only saved mail accounts can be watched" }

        mutex.withLock {
            stopLocked(account.id)
            registry.put(account)
            val job = scope.launch { watcher.watch(account) }
            running[account.id] = account to job
        }

        logger.info("Started watching mailbox {} (id {}) for owner {}", account.username, account.id, account.ownerChatId)
    }

    /** Прекращает слежение. Возвращает ящик, если он отслеживался. */
    suspend fun stop(accountId: Long): MailAccountConfig? = mutex.withLock {
        stopLocked(accountId)
    }

    // Не ждем завершения: блокирующий IMAP IDLE может не реагировать на отмену
    // до таймаута сокета. Ящик сразу убирается из реестра, поэтому новые письма
    // по нему уже не доставляются.
    private fun stopLocked(accountId: Long): MailAccountConfig? {
        val (account, job) = running.remove(accountId) ?: return null
        job.cancel()
        registry.remove(account)
        logger.info("Stopped watching mailbox {} (id {})", account.username, account.id)
        return account
    }

    suspend fun count(): Int = mutex.withLock { running.size }

    override fun close() {
        scope.cancel()
    }
}
