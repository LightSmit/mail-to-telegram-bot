package io.github.lightsmit.service

import io.github.lightsmit.billing.Plan
import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.storage.MailAccountRepository
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Пересылка писем ящика в групповой чат (тариф Business).
 *
 * Как привязать: в приложении владелец получает ссылку t.me/бот?startgroup=grp_код,
 * Telegram предлагает выбрать группу и добавить в нее бота, а в группе появляется
 * "/start grp_код". По коду бот понимает, какой ящик чей, и привязывает группу.
 */
class GroupLinkService(
    private val repository: MailAccountRepository,
    private val supervisor: MailboxSupervisor,
    private val plan: suspend (Long) -> Plan,
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(GroupLinkService::class.java)
    private val random = SecureRandom()

    private data class Pending(val ownerId: Long, val accountId: Long, val createdAt: Instant)

    // Коды живут в памяти 30 минут: после перезапуска бота ссылку просто получают заново
    private val pending = ConcurrentHashMap<String, Pending>()

    /** Пересылка в группу доступна только на Business. */
    suspend fun allowed(ownerId: Long): Boolean = plan(ownerId) == Plan.BUSINESS

    /** Код для ссылки ?startgroup=. null - ящик не найден или чужой. */
    suspend fun createCode(ownerId: Long, accountId: Long): String? {
        repository.findByIdAndOwner(accountId, ownerId) ?: return null
        val now = clock()
        pending.entries.removeIf { (_, value) -> Duration.between(value.createdAt, now) > CODE_TTL }
        val code = CODE_PREFIX + buildString {
            repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        pending[code] = Pending(ownerId, accountId, now)
        return code
    }

    /** В группе нажали "Начать" по ссылке с кодом. */
    suspend fun link(code: String, userId: Long, chatId: Long, title: String?): LinkResult {
        val request = pending[code]
        if (request == null || Duration.between(request.createdAt, clock()) > CODE_TTL) {
            pending.remove(code)
            return LinkResult.Expired
        }
        // Привязать может только владелец ящика: иначе кто угодно перенаправил бы чужую почту
        if (request.ownerId != userId) return LinkResult.WrongUser
        if (!allowed(userId)) return LinkResult.NeedBusiness

        pending.remove(code)
        val updated = repository.setGroup(request.accountId, request.ownerId, chatId, title)
            ?: return LinkResult.Expired
        supervisor.refresh(updated)
        logger.info("Mailbox {} (id {}) linked to group {} by owner {}", updated.username, updated.id, chatId, userId)
        return LinkResult.Linked(updated)
    }

    /** Отвязать группу от ящика (из приложения). */
    suspend fun unlink(ownerId: Long, accountId: Long): MailAccountConfig? {
        val updated = repository.setGroup(accountId, ownerId, chatId = null, title = null) ?: return null
        supervisor.refresh(updated)
        logger.info("Mailbox {} (id {}) unlinked from its group by owner {}", updated.username, updated.id, ownerId)
        return updated
    }

    /** Бота удалили из группы: письма ящиков снова идут владельцам в личный чат. */
    suspend fun onRemovedFromGroup(chatId: Long): List<MailAccountConfig> =
        repository.findByGroup(chatId).mapNotNull { account ->
            repository.setGroup(account.id, account.ownerChatId, chatId = null, title = null)?.also { updated ->
                supervisor.refresh(updated)
                logger.info("Mailbox {} (id {}) unlinked: bot removed from group {}", updated.username, updated.id, chatId)
            }
        }

    /** Группа стала супергруппой: у нее новый ID. */
    suspend fun onMigrated(oldChatId: Long, newChatId: Long) {
        repository.findByGroup(oldChatId).forEach { account ->
            repository.setGroup(account.id, account.ownerChatId, newChatId, account.groupTitle)?.let { updated ->
                supervisor.refresh(updated)
                logger.info("Mailbox {} (id {}) moved from group {} to {}", updated.username, updated.id, oldChatId, newChatId)
            }
        }
    }

    sealed interface LinkResult {
        data class Linked(val account: MailAccountConfig) : LinkResult
        data object Expired : LinkResult
        data object WrongUser : LinkResult
        data object NeedBusiness : LinkResult
    }

    companion object {
        const val CODE_PREFIX = "grp_"
        private const val CODE_LENGTH = 16
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        private val CODE_TTL: Duration = Duration.ofMinutes(30)
    }
}
