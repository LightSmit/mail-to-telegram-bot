package io.github.lightsmit.mail

import java.nio.file.Path

data class EmailAttachment(
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val tempFile: Path,
)

data class SkippedAttachment(
    val fileName: String,
    val reason: String,
)

data class EmailContent(
    val body: String?,
    val attachments: List<EmailAttachment>,
    val skippedAttachments: List<SkippedAttachment>,
)