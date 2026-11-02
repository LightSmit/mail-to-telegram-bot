package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.config.MailAccountKeys
import io.github.lightsmit.config.MailFilters
import io.github.lightsmit.i18n.Lang
import io.github.lightsmit.i18n.Texts
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.storage.MailAccountRepository
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailStateRepository
import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.UnknownHostException

data class NewMailAccount(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

sealed interface UpdatePasswordResult {
    data class Updated(val account: MailAccountConfig) : UpdatePasswordResult
    data class Rejected(val message: String) : UpdatePasswordResult
    data object NotFound : UpdatePasswordResult
}

sealed interface AddMailAccountResult {
    data class Added(val account: MailAccountConfig) : AddMailAccountResult
    data class Rejected(val message: String) : AddMailAccountResult
    data class LimitReached(val limit: Int) : AddMailAccountResult
}

/**
 * Управление ящиками пользователей: проверка и добавление из Mini App,
 * обновление пароля и фильтров, удаление, запуск слежения при старте.
 */
class MailAccountService(
    private val repository: MailAccountRepository,
    private val supervisor: MailboxSupervisor,
    private val stateRepository: MailStateRepository,
    private val outboxRepository: MailNotificationOutboxRepository,
    private val imapClient: ImapMailClient,
    private val maxAccountsPerOwner: Int,
    private val healthMonitor: MailboxHealthMonitor,
) {

    private val logger = LoggerFactory.getLogger(MailAccountService::class.java)

    suspend fun list(ownerChatId: Long): List<MailAccountConfig> =
        repository.findAllByOwnerId(ownerChatId)

    fun health(accountId: Long): MailboxHealth =
        healthMonitor.health(accountId)

    /** Сохраняет фильтры ящика и сразу применяет их. null - ящик не найден или чужой. */
    suspend fun updateFilters(ownerChatId: Long, accountId: Long, filters: MailFilters): MailAccountConfig? {
        val normalized = MailFilters(
            paused = filters.paused,
            skipBulk = filters.skipBulk,
            blockedSenders = MailFilterEngine.normalizeSenders(filters.blockedSenders),
            blockedSubjectWords = MailFilterEngine.normalizeWords(filters.blockedSubjectWords),
            allowedSenders = MailFilterEngine.normalizeSenders(filters.allowedSenders),
        )
        val updated = repository.updateFilters(accountId, ownerChatId, normalized) ?: return null
        supervisor.refresh(updated)
        logger.info("Filters of mailbox {} (id {}) updated by owner {}: {}", updated.username, accountId, ownerChatId, normalized)
        return updated
    }

    suspend fun add(ownerChatId: Long, request: NewMailAccount, lang: Lang = Lang.RU): AddMailAccountResult {
        val t = lang.texts
        val username = request.username.trim().let { value ->
            if ('@' in value) value.lowercase() else value
        }
        if (username.isEmpty() || username.length > 254) {
            return AddMailAccountResult.Rejected(t.enterAddress)
        }

        val password = request.password.trim()
        if (password.isEmpty() || password.length > 256) {
            return AddMailAccountResult.Rejected(t.enterPassword)
        }

        if (request.port != MailHostPolicy.IMAPS_PORT) {
            return AddMailAccountResult.Rejected(t.onlyImaps)
        }

        val host = MailHostPolicy.normalizeHostname(request.host)
            ?: return AddMailAccountResult.Rejected(t.badHost)

        resolveHostProblem(host, t)?.let { problem ->
            return AddMailAccountResult.Rejected(problem)
        }

        val alreadyExists = repository.existsForOwner(ownerChatId, username)
        if (!alreadyExists && repository.countByOwnerId(ownerChatId) >= maxAccountsPerOwner) {
            return AddMailAccountResult.LimitReached(maxAccountsPerOwner)
        }

        val name = request.name.trim().ifEmpty { username }.take(64)

        val candidate = MailAccountConfig(
            name = name,
            host = host,
            port = request.port,
            username = username,
            password = password,
            ownerChatId = ownerChatId,
        )

        verifyLogin(candidate, t)?.let { problem ->
            return AddMailAccountResult.Rejected(problem)
        }

        val saved = repository.upsert(ownerChatId, candidate)
        healthMonitor.forget(saved.id)
        supervisor.start(saved)

        logger.info("Mailbox {} (id {}) added by owner {}", saved.username, saved.id, ownerChatId)
        return AddMailAccountResult.Added(saved)
    }

    /**
     * Новый пароль приложения для подключенного ящика: сначала проверяем вход с ним,
     * потом сохраняем и перезапускаем слежение. Фильтры и позиция чтения сохраняются.
     */
    suspend fun updatePassword(
        ownerChatId: Long,
        accountId: Long,
        rawPassword: String,
        lang: Lang = Lang.RU,
    ): UpdatePasswordResult {
        val t = lang.texts
        val password = rawPassword.trim()
        if (password.isEmpty() || password.length > 256) {
            return UpdatePasswordResult.Rejected(t.enterPassword)
        }

        val existing = repository.findByIdAndOwner(accountId, ownerChatId)
            ?: return UpdatePasswordResult.NotFound

        verifyLogin(existing.copy(password = password), t)?.let { problem ->
            return UpdatePasswordResult.Rejected(problem)
        }

        val updated = repository.updatePassword(accountId, ownerChatId, password)
            ?: return UpdatePasswordResult.NotFound

        healthMonitor.forget(accountId)
        supervisor.start(updated)
        logger.info("Password of mailbox {} (id {}) updated by owner {}", updated.username, accountId, ownerChatId)
        return UpdatePasswordResult.Updated(updated)
    }

    /** Удаляет ящик владельца. false - ящика нет или он чужой. */
    suspend fun delete(ownerChatId: Long, accountId: Long): Boolean {
        val deleted = repository.delete(accountId, ownerChatId) ?: return false
        supervisor.stop(accountId)
        healthMonitor.forget(accountId)
        stateRepository.delete(MailAccountKeys.accountKey(deleted))
        logger.info("Mailbox {} (id {}) deleted by owner {}", deleted.username, deleted.id, ownerChatId)
        return true
    }

    /** Удаляет все ящики пользователя и связанные с ними уведомления. Возвращает число ящиков. */
    suspend fun deleteAllForOwner(ownerChatId: Long): Int {
        val accounts = repository.findAllByOwnerId(ownerChatId)
        accounts.forEach { account -> delete(ownerChatId, account.id) }
        outboxRepository.deleteOwner(ownerChatId)
        return accounts.size
    }

    /** Запускает слежение за всеми ящиками из базы. Вызывается один раз при старте. */
    suspend fun startAll(): Int {
        val accounts = repository.findAll()
        accounts.forEach { account -> supervisor.start(account) }
        return accounts.size
    }

    private suspend fun resolveHostProblem(host: String, t: Texts): String? = withContext(Dispatchers.IO) {
        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (exception: UnknownHostException) {
            return@withContext t.hostNotFound(host)
        }

        if (addresses.isEmpty() || addresses.any { address -> !MailHostPolicy.isPublicAddress(address) }) {
            t.hostNotAllowed(host)
        } else {
            null
        }
    }

    private suspend fun verifyLogin(account: MailAccountConfig, t: Texts): String? {
        return try {
            val connected = withTimeoutOrNull(LOGIN_TIMEOUT_MILLIS) {
                imapClient.fetchCursor(account)
            }

            if (connected == null) {
                t.serverTimeout(account.host)
            } else {
                null
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: AuthenticationFailedException) {
            logger.info("Login check failed for {}: authentication rejected", account.username)
            t.loginRejected
        } catch (exception: Exception) {
            logger.info("Login check failed for {}: {} ({})", account.username, exception.javaClass.simpleName, exception.message)
            t.cannotConnect(account.host)
        }
    }

    private companion object {
        const val LOGIN_TIMEOUT_MILLIS = 40_000L
    }
}
