package io.github.lightsmit.service

import io.github.lightsmit.billing.Plan
import io.github.lightsmit.config.MailAccountConfig

/**
 * Сколько ящиков работает на тарифе пользователя. Если ящиков больше лимита (подписка закончилась),
 * работают первые по времени подключения из тех, что не на паузе. Чтобы выбрать, какой оставить,
 * пользователь ставит остальные на паузу или удаляет их.
 */
class MailboxQuota(
    private val registry: MailAccountRegistry,
    private val plan: suspend (Long) -> Plan,
) {
    suspend fun limit(ownerChatId: Long): Int = plan(ownerChatId).maxMailboxes

    /** Ящики сверх лимита: письма из них не присылаются. */
    suspend fun overLimitIds(ownerChatId: Long): Set<Long> =
        overLimit(registry.findByOwner(ownerChatId), limit(ownerChatId))

    suspend fun isOverLimit(account: MailAccountConfig): Boolean =
        account.id in overLimitIds(account.ownerChatId)

    companion object {
        fun overLimit(accounts: List<MailAccountConfig>, limit: Int): Set<Long> =
            accounts
                .filter { account -> !account.filters.paused }
                .sortedBy { account -> account.id }
                .drop(limit)
                .map { account -> account.id }
                .toSet()
    }
}
