package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.mail.MailFolderNotFoundException
import io.github.lightsmit.mail.MailFolderRole
import io.github.lightsmit.mail.MailListPage
import io.github.lightsmit.mail.MailMessageView
import io.github.lightsmit.storage.MailAccountRepository
import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/** Результат запроса к ящику из приложения: данные или понятная ошибка. */
sealed interface BrowseResult<out T> {
    data class Ok<T>(val value: T) : BrowseResult<T>
    data object MailboxNotFound : BrowseResult<Nothing>
    data object MessageNotFound : BrowseResult<Nothing>
    data class Failed(val reason: BrowseFailure) : BrowseResult<Nothing>
}

/** Почему не удалось получить письма; текст для пользователя подбирает API на его языке. */
enum class BrowseFailure { TIMEOUT, AUTH, NO_SENT_FOLDER, OTHER }

/**
 * Просмотр писем в Mini App. Письма читаются из ящика по запросу и на сервере
 * не хранятся. Доступ только к своим ящикам.
 */
class MailBrowserService(
    private val repository: MailAccountRepository,
    private val imapClient: ImapMailClient,
    // Прислать вложения письма в чат (использует уже готовую отправку из бота)
    private val sendAttachmentsToChat: suspend (MailAccountConfig, Long, Long) -> Unit,
) {

    private val logger = LoggerFactory.getLogger(MailBrowserService::class.java)

    suspend fun list(
        ownerId: Long,
        accountId: Long,
        role: MailFolderRole,
        offset: Int,
        limit: Int,
        search: String?,
        unreadOnly: Boolean,
    ): BrowseResult<MailListPage> = withAccount(ownerId, accountId) { account ->
        imapClient.listMessages(
            account = account,
            role = role,
            offset = offset.coerceAtLeast(0),
            limit = limit.coerceIn(1, MAX_PAGE_SIZE),
            search = search?.trim()?.take(MAX_SEARCH_LENGTH)?.takeIf(String::isNotEmpty),
            unreadOnly = unreadOnly,
        )
    }

    suspend fun read(
        ownerId: Long,
        accountId: Long,
        role: MailFolderRole,
        uidValidity: Long,
        uid: Long,
    ): BrowseResult<MailMessageView> = withAccount(ownerId, accountId) { account ->
        imapClient.readMessage(account, role, uidValidity, uid)
    }

    suspend fun markRead(ownerId: Long, accountId: Long, uidValidity: Long, uid: Long): BrowseResult<Unit> =
        withAccount(ownerId, accountId) { account ->
            if (imapClient.markSeen(account, uidValidity, uid)) Unit else null
        }

    suspend fun sendAttachments(ownerId: Long, accountId: Long, uidValidity: Long, uid: Long): BrowseResult<Unit> =
        withAccount(ownerId, accountId) { account ->
            sendAttachmentsToChat(account, uidValidity, uid)
        }

    // null из block - письмо не найдено
    private suspend fun <T> withAccount(
        ownerId: Long,
        accountId: Long,
        block: suspend (MailAccountConfig) -> T?,
    ): BrowseResult<T> {
        val account = repository.findByIdAndOwner(accountId, ownerId)
            ?: return BrowseResult.MailboxNotFound

        return try {
            val value = withTimeout(REQUEST_TIMEOUT_MILLIS) { block(account) }
            if (value != null) BrowseResult.Ok(value) else BrowseResult.MessageNotFound
        } catch (exception: TimeoutCancellationException) {
            BrowseResult.Failed(BrowseFailure.TIMEOUT)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: AuthenticationFailedException) {
            BrowseResult.Failed(BrowseFailure.AUTH)
        } catch (exception: MailFolderNotFoundException) {
            BrowseResult.Failed(BrowseFailure.NO_SENT_FOLDER)
        } catch (exception: Exception) {
            logger.warn("Mail browse request for mailbox {} failed: {}", account.username, exception.javaClass.simpleName)
            BrowseResult.Failed(BrowseFailure.OTHER)
        }
    }

    companion object {
        const val MAX_PAGE_SIZE = 50
        const val MAX_SEARCH_LENGTH = 100
        const val REQUEST_TIMEOUT_MILLIS = 60_000L
    }
}
