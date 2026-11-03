package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.config.MailAccountKeys
import io.github.lightsmit.telegram.MailViewCallbackCodec
import java.util.concurrent.ConcurrentHashMap

/**
 * Ящики, за которыми бот следит прямо сейчас. Меняется на лету: ящик добавили
 * в Mini App - появился здесь, удалили - исчез.
 */
class MailAccountRegistry {

    private val accountsByKey = ConcurrentHashMap<String, MailAccountConfig>()

    fun put(account: MailAccountConfig) {
        accountsByKey[MailAccountKeys.accountKey(account)] = account
    }

    /** Обновляет настройки ящика, если за ним уже следят (например, изменили фильтры). */
    fun replaceIfPresent(account: MailAccountConfig) {
        accountsByKey.computeIfPresent(MailAccountKeys.accountKey(account)) { _, _ -> account }
    }

    fun remove(account: MailAccountConfig) {
        accountsByKey.remove(MailAccountKeys.accountKey(account))
    }

    fun findByKey(accountKey: String): MailAccountConfig? =
        accountsByKey[accountKey]

    /** Ящик для нажатой кнопки: только среди ящиков нажавшего пользователя. */
    fun findForCallback(accountCode: String, ownerChatId: Long): MailAccountConfig? =
        accountsByKey.values.firstOrNull { account ->
            account.ownerChatId == ownerChatId &&
                MailViewCallbackCodec.accountCode(account) == accountCode
        }

    /** Ящик для кнопки под письмом в групповом чате: среди ящиков, привязанных к этой группе. */
    fun findForGroupCallback(accountCode: String, groupChatId: Long): MailAccountConfig? =
        accountsByKey.values.firstOrNull { account ->
            account.groupChatId == groupChatId &&
                MailViewCallbackCodec.accountCode(account) == accountCode
        }

    /** Все отслеживаемые ящики пользователя. */
    fun findByOwner(ownerChatId: Long): List<MailAccountConfig> =
        accountsByKey.values.filter { account -> account.ownerChatId == ownerChatId }

    fun size(): Int = accountsByKey.size
}
