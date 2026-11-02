package io.github.lightsmit.config

data class MailAccountConfig(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** Telegram ID владельца: ему (в личный чат) приходят письма из этого ящика. */
    val ownerChatId: Long = 0,
    /** ID записи в таблице mail_accounts (0 - ящик еще не сохранен в базе). */
    val id: Long = 0,
    /** Какие письма этого ящика не присылать. */
    val filters: MailFilters = MailFilters(),
) {
    // Пароль не должен попадать в логи даже случайно
    override fun toString(): String =
        "MailAccountConfig(id=$id, owner=$ownerChatId, name=$name, host=$host, port=$port, username=$username)"
}

/** Настройки фильтрации писем ящика. */
data class MailFilters(
    /** Пауза: письма не присылаются, после снятия паузы накопившиеся тоже не придут. */
    val paused: Boolean = false,
    /** Не присылать массовые рассылки и автоответы. */
    val skipBulk: Boolean = false,
    /** Адреса (name@example.com) и домены (example.com), письма от которых не присылать. */
    val blockedSenders: List<String> = emptyList(),
    /** Слова и фразы: если тема их содержит, письмо не присылать. */
    val blockedSubjectWords: List<String> = emptyList(),
    /** Если список не пуст, присылать только письма от этих адресов и доменов. */
    val allowedSenders: List<String> = emptyList(),
)

object MailAccountKeys {

    /** Ключ ящика в таблицах состояния и outbox: владелец + сервер + логин. */
    fun accountKey(account: MailAccountConfig): String =
        "${account.ownerChatId}:${account.host}:${account.username}".lowercase()
}
