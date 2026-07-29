package io.github.lightsmit.mail

data class MailboxCursor(
    val uidValidity: Long,
    val latestUid: Long,
)

data class MailboxBatch(
    val uidValidity: Long,
    val latestUid: Long,
    val messages: List<EmailSummary>,
)