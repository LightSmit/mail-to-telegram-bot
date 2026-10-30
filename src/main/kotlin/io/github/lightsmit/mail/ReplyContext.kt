package io.github.lightsmit.mail

import jakarta.mail.internet.InternetAddress

/** Что нужно знать об исходном письме, чтобы ответить на него в той же цепочке. */
data class ReplyContext(
    val recipients: List<InternetAddress>,
    val subject: String,
    val messageId: String?,
    val references: String?,
)

object ReplyFormat {

    private const val MAX_REFERENCES = 20
    private val replyPrefix = Regex("^\\s*(re|ответ|отв)\\s*(\\[\\d+])?\\s*:", RegexOption.IGNORE_CASE)
    private val lineBreaks = Regex("[\\r\\n]+")

    /** "Re: тема" без повторного "Re:", если он уже есть. */
    fun subject(original: String): String {
        val clean = original.replace(lineBreaks, " ").trim()
        if (clean.isEmpty()) return "Re: (без темы)"
        return if (replyPrefix.containsMatchIn(clean)) clean else "Re: $clean"
    }

    /** Заголовок References: старая цепочка + исходное письмо, не длиннее MAX_REFERENCES звеньев. */
    fun references(context: ReplyContext): String? {
        val ids = buildList {
            context.references?.split(' ')?.filter { id -> id.isNotBlank() }?.let(::addAll)
            context.messageId?.let(::add)
        }.distinct()
        return ids.takeLast(MAX_REFERENCES).joinToString(" ").takeIf(String::isNotEmpty)
    }

    /** Адреса получателей для показа пользователю. */
    fun recipientsText(context: ReplyContext): String =
        context.recipients.joinToString(", ") { address -> address.address.orEmpty() }
}
