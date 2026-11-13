package io.github.lightsmit.service

import io.github.lightsmit.i18n.Texts

/**
 * Понятные подсказки, когда почта отклонила вход: самая частая ошибка новичков -
 * обычный пароль от почты вместо пароля приложения.
 */
object AppPasswordHints {
    // Пароль приложения Google: 16 латинских букв (пробелы уже убраны)
    private val GMAIL_APP_PASSWORD = Regex("^[a-zA-Z]{16}$")

    fun isGmail(host: String): Boolean = host.trim().lowercase() == "imap.gmail.com"

    private fun isYandex(host: String): Boolean = host.trim().lowercase() in setOf("imap.yandex.ru", "imap.yandex.com")

    private fun isMailru(host: String): Boolean = host.trim().lowercase() == "imap.mail.ru"

    fun rejectionText(host: String, password: String, t: Texts): String = when {
        isGmail(host) && !GMAIL_APP_PASSWORD.matches(password) -> t.loginRejectedGmailRegular
        isGmail(host) -> t.loginRejectedGmail
        isYandex(host) -> t.loginRejectedYandex
        isMailru(host) -> t.loginRejectedMailru
        else -> t.loginRejected
    }
}
