package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.config.MailAccountKeys
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.storage.MailAccountRepository
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailStateRepository
import io.github.lightsmit.telegram.MailViewCallbackCodec
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

sealed interface AddMailAccountResult {
    data class Added(val account: MailAccountConfig) : AddMailAccountResult
    data class Rejected(val message: String) : AddMailAccountResult
    data class LimitReached(val limit: Int) : AddMailAccountResult
}

/**
 * Управление ящиками пользователей: проверка и добавление из Mini App,
 * удаление, запуск слежения при старте, импорт старых ящиков из .env.
 */
class MailAccountService(
    private val repository: MailAccountRepository,
    private val supervisor: MailboxSupervisor,
    private val stateRepository: MailStateRepository,
    private val outboxRepository: MailNotificationOutboxRepository,
    private val imapClient: ImapMailClient,
    private val maxAccountsPerOwner: Int,
) {

    private val logger = LoggerFactory.getLogger(MailAccountService::class.java)

    suspend fun list(ownerChatId: Long): List<MailAccountConfig> =
        repository.findAllByOwnerId(ownerChatId)

    suspend fun add(ownerChatId: Long, request: NewMailAccount): AddMailAccountResult {
        val username = request.username.trim().let { value ->
            if ('@' in value) value.lowercase() else value
        }
        if (username.isEmpty() || username.length > 254) {
            return AddMailAccountResult.Rejected("Укажите адрес почты")
        }

        val password = request.password.trim()
        if (password.isEmpty() || password.length > 256) {
            return AddMailAccountResult.Rejected("Укажите пароль приложения")
        }

        if (request.port != MailHostPolicy.IMAPS_PORT) {
            return AddMailAccountResult.Rejected("Поддерживается только IMAP с шифрованием (порт 993)")
        }

        val host = MailHostPolicy.normalizeHostname(request.host)
            ?: return AddMailAccountResult.Rejected("Некорректный адрес IMAP-сервера")

        resolveHostProblem(host)?.let { problem ->
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

        verifyLogin(candidate)?.let { problem ->
            return AddMailAccountResult.Rejected(problem)
        }

        val saved = repository.upsert(ownerChatId, candidate)
        supervisor.start(saved)

        logger.info("Mailbox {} (id {}) added by owner {}", saved.username, saved.id, ownerChatId)
        return AddMailAccountResult.Added(saved)
    }

    /** Удаляет ящик владельца. false - ящика нет или он чужой. */
    suspend fun delete(ownerChatId: Long, accountId: Long): Boolean {
        val deleted = repository.delete(accountId, ownerChatId) ?: return false
        supervisor.stop(accountId)
        stateRepository.delete(MailAccountKeys.accountKey(deleted))
        logger.info("Mailbox {} (id {}) deleted by owner {}", deleted.username, deleted.id, ownerChatId)
        return true
    }

    /** Запускает слежение за всеми ящиками из базы. Вызывается один раз при старте. */
    suspend fun startAll(): Int {
        val accounts = repository.findAll()
        accounts.forEach { account -> supervisor.start(account) }
        return accounts.size
    }

    /**
     * Разовый импорт ящиков из .env (MAIL_ACCOUNT_*) на владельца TELEGRAM_CHAT_ID.
     * Позиция чтения и очередь уведомлений переносятся на новые ключи, поэтому
     * письма не дублируются и не теряются. Уже импортированные ящики пропускаются.
     */
    suspend fun importLegacyAccounts(
        accounts: List<MailAccountConfig>,
        ownerChatId: Long,
    ): Int {
        var imported = 0

        for (legacy in accounts) {
            val owned = legacy.copy(
                host = legacy.host.trim().lowercase(),
                username = legacy.username.trim().let { if ('@' in it) it.lowercase() else it },
                ownerChatId = ownerChatId,
            )

            if (!repository.insertIfAbsent(ownerChatId, owned)) {
                continue
            }

            val oldKey = MailAccountKeys.legacyAccountKey(legacy)
            val newKey = MailAccountKeys.accountKey(owned)
            stateRepository.renameKey(oldKey, newKey)
            val movedItems = outboxRepository.rekeyAccount(
                oldAccountKey = oldKey,
                newAccountKey = newKey,
                newAccountCode = MailViewCallbackCodec.accountCode(owned),
            )

            imported++
            logger.info(
                "Imported mailbox {} from .env for owner {} ({} outbox records moved)",
                owned.username,
                ownerChatId,
                movedItems,
            )
        }

        return imported
    }

    private suspend fun resolveHostProblem(host: String): String? = withContext(Dispatchers.IO) {
        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (exception: UnknownHostException) {
            return@withContext "Сервер $host не найден. Проверьте адрес (например, у Gmail это imap.gmail.com)"
        }

        if (addresses.isEmpty() || addresses.any { address -> !MailHostPolicy.isPublicAddress(address) }) {
            "Адрес сервера $host не разрешён"
        } else {
            null
        }
    }

    private suspend fun verifyLogin(account: MailAccountConfig): String? {
        return try {
            val connected = withTimeoutOrNull(LOGIN_TIMEOUT_MILLIS) {
                imapClient.fetchCursor(account)
            }

            if (connected == null) {
                "Сервер ${account.host} не ответил вовремя. Попробуйте позже"
            } else {
                null
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: AuthenticationFailedException) {
            logger.info("Login check failed for {}: authentication rejected", account.username)
            "Почта отклонила вход. Проверьте адрес и пароль приложения, а также что доступ по IMAP включён в настройках ящика"
        } catch (exception: Exception) {
            logger.info("Login check failed for {}: {} ({})", account.username, exception.javaClass.simpleName, exception.message)
            "Не удалось подключиться к ${account.host}. Проверьте адрес сервера"
        }
    }

    private companion object {
        const val LOGIN_TIMEOUT_MILLIS = 40_000L
    }
}
