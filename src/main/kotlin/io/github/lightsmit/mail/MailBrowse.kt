package io.github.lightsmit.mail

import java.time.Instant

/** Папка для просмотра в приложении. */
enum class MailFolderRole {
    INBOX,
    SENT;

    companion object {
        fun parse(value: String?): MailFolderRole? = when (value?.trim()?.lowercase()) {
            null, "", "inbox" -> INBOX
            "sent" -> SENT
            else -> null
        }
    }
}

/** Строка списка писем: без текста, только то, что нужно для списка. */
data class MailListItem(
    val uid: Long,
    val fromName: String,
    val fromAddress: String?,
    val to: String,
    val subject: String,
    val date: Instant?,
    val seen: Boolean,
    val hasAttachments: Boolean,
)

data class MailListPage(
    val uidValidity: Long,
    /** Сколько писем подходит под условия (во всей папке). */
    val total: Int,
    val items: List<MailListItem>,
    val hasMore: Boolean,
)

/** Открытое письмо. Вложения - только имена, сами файлы присылаются в чат по кнопке. */
data class MailMessageView(
    val uid: Long,
    val from: String,
    val to: String,
    val cc: String,
    val subject: String,
    val date: Instant?,
    val body: String?,
    val attachments: List<String>,
    val seen: Boolean,
)

/** Нужной папки в ящике нет (например, сервис не отмечает папку "Отправленные"). */
class MailFolderNotFoundException(role: MailFolderRole) : RuntimeException("Folder $role not found")
