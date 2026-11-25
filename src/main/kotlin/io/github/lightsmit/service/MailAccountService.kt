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
import io.github.lightsmit.storage.StarredRepository
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
    // reason - для воронки: input, host, auth, timeout, connect
    data class Rejected(val message: String, val reason: String = REASON_INPUT) : AddMailAccountResult

    companion object {
        const val REASON_INPUT = "input"
        const val REASON_HOST = "host"
        const val REASON_AUTH = "auth"
        const val REASON_TIMEOUT = "timeout"
        const val REASON_CONNECT = "connect"
        const val REASON_LIMIT = "limit"
    }
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
    // Сколько ящиков можно подключить: зависит от тарифа пользователя
    private val mailboxLimit: suspend (Long) -> Int,
    private val healthMonitor: MailboxHealthMonitor,
    // Новый ящик подключен (для приглашений: друг засчитывается после первого ящика)
    private val onMailboxAdded: suspend (ownerChatId: Long) -> Unit = {},
    // Письма ящиков-пересылок (null - режим пересылки выключен)
    private val inbound: InboundMailService? = null,
    // Звездочки писем: удаляются вместе с ящиком
    private val starred: StarredRepository? = null,
) {

    /** Режим «Пересылка без пароля» включен на сервере. */
    val forwardingEnabled: Boolean get() = inbound != null

    /** Адрес, на который нужно пересылать письма ящика, или null для обычного ящика. */
    fun forwardAddress(account: MailAccountConfig): String? =
        if (account.isForward) inbound?.addressOf(account.password) else null

    /** На адрес ящика-пересылки уже приходило хотя бы одно письмо. */
    suspend fun forwardReceived(account: MailAccountConfig): Boolean =
        account.isForward && stateRepository.find(MailAccountKeys.accountKey(account)) != null

    private val logger = LoggerFactory.getLogger(MailAccountService::class.java)

    suspend fun list(ownerChatId: Long): List<MailAccountConfig> =
        repository.findAllByOwnerId(ownerChatId)

    fun health(accountId: Long): MailboxHealth =
        healthMonitor.health(accountId)

    /** Ящик владельца; null - нет или чужой. */
    suspend fun find(ownerChatId: Long, accountId: Long): MailAccountConfig? =
        repository.findByIdAndOwner(accountId, ownerChatId)

    /** Сохраняет фильтры ящика и сразу применяет их. null - ящик не найден или чужой. */
    suspend fun updateFilters(ownerChatId: Long, accountId: Long, filters: MailFilters): MailAccountConfig? {
        val normalized = MailFilters(
            paused = filters.paused,
            // Срок паузы имеет смысл, только пока пауза включена
            pausedUntil = filters.pausedUntil?.takeIf { filters.paused },
            silent = filters.silent,
            skipBulk = filters.skipBulk,
            blockedSenders = MailFilterEngine.normalizeSenders(filters.blockedSenders),
            blockedSubjectWords = MailFilterEngine.normalizeWords(filters.blockedSubjectWords),
            allowedSenders = MailFilterEngine.normalizeSenders(filters.allowedSenders),
            vipSenders = MailFilterEngine.normalizeSenders(filters.vipSenders),
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

        val password = normalizePassword(request.host, request.password)
        if (password.isEmpty() || password.length > 256) {
            return AddMailAccountResult.Rejected(t.enterPassword)
        }

        if (request.port != MailHostPolicy.IMAPS_PORT) {
            return AddMailAccountResult.Rejected(t.onlyImaps)
        }

        val host = MailHostPolicy.normalizeHostname(request.host)
            ?: return AddMailAccountResult.Rejected(t.badHost, AddMailAccountResult.REASON_HOST)

        resolveHostProblem(host, t)?.let { problem ->
            return AddMailAccountResult.Rejected(problem, AddMailAccountResult.REASON_HOST)
        }

        val alreadyExists = repository.existsForOwner(ownerChatId, username)
        val limit = mailboxLimit(ownerChatId)
        if (!alreadyExists && repository.countByOwnerId(ownerChatId) >= limit) {
            return AddMailAccountResult.LimitReached(limit)
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
            return AddMailAccountResult.Rejected(problem.message, problem.reason)
        }

        val saved = repository.upsert(ownerChatId, candidate)
        healthMonitor.forget(saved.id)
        supervisor.start(saved)

        logger.info("Mailbox {} (id {}) added by owner {}", saved.username, saved.id, ownerChatId)
        if (!alreadyExists) {
            try {
                onMailboxAdded(ownerChatId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn("Mailbox added hook failed for {}: {}", ownerChatId, exception.javaClass.simpleName)
            }
        }
        return AddMailAccountResult.Added(saved)
    }

    /**
     * Ящик без пароля: бот выдает адрес, пользователь включает пересылку на него в своей почте.
     * Вход в почту не проверяется, письма приходят через /api/inbound.
     */
    suspend fun addForward(ownerChatId: Long, email: String, name: String, lang: Lang = Lang.RU): AddMailAccountResult {
        val t = lang.texts
        if (inbound == null) return AddMailAccountResult.Rejected(t.forwardDisabled)

        val username = email.trim().lowercase()
        if (username.length > 254 || !EMAIL_PATTERN.matches(username)) {
            return AddMailAccountResult.Rejected(t.enterAddress)
        }

        val existing = repository.findAllByOwnerId(ownerChatId).firstOrNull { account -> account.username == username }
        if (existing != null) {
            // Повторное нажатие: показываем тот же адрес. Ящик с паролем не переделываем молча
            return if (existing.isForward) {
                AddMailAccountResult.Added(existing)
            } else {
                AddMailAccountResult.Rejected(t.forwardAlreadyConnected(username))
            }
        }

        val limit = mailboxLimit(ownerChatId)
        if (repository.countByOwnerId(ownerChatId) >= limit) {
            return AddMailAccountResult.LimitReached(limit)
        }

        val candidate = MailAccountConfig(
            name = name.trim().ifEmpty { username }.take(64),
            host = MailAccountConfig.FORWARD_HOST,
            port = MailHostPolicy.IMAPS_PORT,
            username = username,
            password = InboundMailService.newToken(),
            ownerChatId = ownerChatId,
        )

        val saved = repository.upsert(ownerChatId, candidate)
        healthMonitor.forget(saved.id)
        supervisor.start(saved)
        healthMonitor.onConnected(saved)

        logger.info("Forward mailbox {} (id {}) added by owner {}", saved.username, saved.id, ownerChatId)
        try {
            onMailboxAdded(ownerChatId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Mailbox added hook failed for {}: {}", ownerChatId, exception.javaClass.simpleName)
        }
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
        val existing = repository.findByIdAndOwner(accountId, ownerChatId)
            ?: return UpdatePasswordResult.NotFound
        if (existing.isForward) return UpdatePasswordResult.Rejected(t.forwardNoPassword)

        val password = normalizePassword(existing.host, rawPassword)
        if (password.isEmpty() || password.length > 256) {
            return UpdatePasswordResult.Rejected(t.enterPassword)
        }

        verifyLogin(existing.copy(password = password), t)?.let { problem ->
            return UpdatePasswordResult.Rejected(problem.message)
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
        if (deleted.isForward) inbound?.forget(deleted)
        starred?.deleteAccount(accountId)
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
        accounts.forEach { account ->
            supervisor.start(account)
            // У ящика-пересылки нет подключения к почте: он всегда в порядке
            if (account.isForward) healthMonitor.onConnected(account)
        }
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

    // Почему не удалось войти: текст для пользователя и причина для воронки
    private data class LoginProblem(val message: String, val reason: String)

    private suspend fun verifyLogin(account: MailAccountConfig, t: Texts): LoginProblem? {
        return try {
            val connected = withTimeoutOrNull(LOGIN_TIMEOUT_MILLIS) {
                imapClient.fetchCursor(account)
            }

            if (connected == null) {
                LoginProblem(t.serverTimeout(account.host), AddMailAccountResult.REASON_TIMEOUT)
            } else {
                null
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: AuthenticationFailedException) {
            logger.info("Login check failed for {}: authentication rejected", account.username)
            LoginProblem(AppPasswordHints.rejectionText(account.host, account.password, t), AddMailAccountResult.REASON_AUTH)
        } catch (exception: Exception) {
            logger.info("Login check failed for {}: {} ({})", account.username, exception.javaClass.simpleName, exception.message)
            LoginProblem(t.cannotConnect(account.host), AddMailAccountResult.REASON_CONNECT)
        }
    }

    // Gmail показывает пароль приложения группами по 4 буквы: пробелы убираем
    private fun normalizePassword(host: String, raw: String): String {
        val trimmed = raw.trim()
        return if (AppPasswordHints.isGmail(host)) trimmed.filterNot(Char::isWhitespace) else trimmed
    }

    private companion object {
        const val LOGIN_TIMEOUT_MILLIS = 40_000L
        val EMAIL_PATTERN = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}
