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
    /** Групповой чат, куда приходят письма ящика (тариф Business); null - в личный чат владельца. */
    val groupChatId: Long? = null,
    val groupTitle: String? = null,
) {
    /**
     * Ящик в режиме пересылки: почта сама пересылает письма на личный адрес бота
     * (токен@домен), входа в ящик нет. Токен адреса хранится вместо пароля.
     */
    val isForward: Boolean get() = host == FORWARD_HOST

    companion object {
        // Условный «сервер» ящиков-пересылок: по нему их отличаем от IMAP
        const val FORWARD_HOST = "forward.inbound"
    }

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
    /** Пауза до этого момента (мс, UTC); null - пока не выключат вручную. */
    val pausedUntil: Long? = null,
    /** Письма этого ящика приходят без звука. */
    val silent: Boolean = false,
) {
    /** Действует ли пауза сейчас: пауза на время сама заканчивается в pausedUntil. */
    fun isPausedAt(nowMillis: Long = System.currentTimeMillis()): Boolean =
        paused && (pausedUntil == null || nowMillis < pausedUntil)
}

object MailAccountKeys {

    /** Ключ ящика в таблицах состояния и outbox: владелец + сервер + логин. */
    fun accountKey(account: MailAccountConfig): String =
        "${account.ownerChatId}:${account.host}:${account.username}".lowercase()
}
