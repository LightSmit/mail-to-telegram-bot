package io.github.lightsmit.mail

import java.time.Instant

data class EmailSummary(
    val uid: Long,
    val from: String,
    val subject: String,
    val sentAt: Instant?,
    val body: String?,
    val attachments: List<EmailAttachment>,
    val skippedAttachments: List<SkippedAttachment>,
)