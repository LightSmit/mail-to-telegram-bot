package io.github.lightsmit.mail

import java.time.Instant

data class EmailSummary(
    val uid: Long,
    val from: String,
    val subject: String,
    val sentAt: Instant?,
    val receivedAt: Instant?,
    val body: String?,
    val attachments: List<EmailAttachment>,
    val skippedAttachments: List<SkippedAttachment>,
    // Адрес отправителя без имени, в нижнем регистре (для фильтров)
    val senderAddress: String? = null,
    // Массовая рассылка или автоответ: есть List-Unsubscribe/List-Id, Precedence: bulk и т.п.
    val isBulk: Boolean = false,
)