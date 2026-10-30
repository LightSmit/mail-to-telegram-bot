package io.github.lightsmit.config

data class MailAccountConfig(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** Telegram ID владельца: ему (в личный чат) приходят письма из этого ящика. */
    val ownerChatId: Long = 0,
    /** ID записи в таблице mail_accounts (0 - ящик ещё не сохранён в базе). */
    val id: Long = 0,
) {
    // Пароль не должен попадать в логи даже случайно
    override fun toString(): String =
        "MailAccountConfig(id=$id, owner=$ownerChatId, name=$name, host=$host, port=$port, username=$username)"
}

object MailAccountKeys {

    /** Ключ ящика в таблицах состояния и outbox: владелец + сервер + логин. */
    fun accountKey(account: MailAccountConfig): String =
        "${account.ownerChatId}:${account.host}:${account.username}".lowercase()

    /** Ключ из однопользовательской версии (без владельца). Нужен только для миграции. */
    fun legacyAccountKey(account: MailAccountConfig): String =
        "${account.host}:${account.username}".lowercase()
}

/**
 * Ящики из .env (MAIL_ACCOUNT_1_* ...). Теперь используются только для разового
 * импорта в базу на владельца TELEGRAM_CHAT_ID; дальше всё управляется из Mini App.
 */
object MailAccountConfigLoader {

    fun load(): List<MailAccountConfig> {
        val accounts = mutableListOf<MailAccountConfig>()
        var index = 1

        while (true) {
            val prefix = "MAIL_ACCOUNT_$index"

            val username = Environment.get("${prefix}_USERNAME")
                ?: break

            val password = Environment.require("${prefix}_PASSWORD")

            val host = Environment.get("${prefix}_HOST")
                ?: "imap.mail.ru"

            val portVariable = "${prefix}_PORT"
            val portValue = Environment.get(portVariable)

            val port = when {
                portValue == null -> 993
                portValue.toIntOrNull() != null -> portValue.toInt()
                else -> error("$portVariable must be a valid integer")
            }

            val name = Environment.get("${prefix}_NAME")
                ?: username

            accounts += MailAccountConfig(
                name = name,
                host = host,
                port = port,
                username = username,
                password = password,
            )

            index++
        }

        return accounts
    }
}
