package io.github.lightsmit.config

data class MailAccountConfig(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

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

        check(accounts.isNotEmpty()) {
            "No mail accounts are configured"
        }

        return accounts
    }
}