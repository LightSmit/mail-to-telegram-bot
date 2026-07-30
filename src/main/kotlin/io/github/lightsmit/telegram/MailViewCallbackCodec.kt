package io.github.lightsmit.telegram

import io.github.lightsmit.config.MailAccountConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

enum class MailViewAction(
    val code: String,
) {
    TEXT("t"),
    ATTACHMENTS("a"),
    BACK("b");

    companion object {
        fun fromCode(code: String): MailViewAction? {
            return entries.firstOrNull { action ->
                action.code == code
            }
        }
    }
}

data class MailViewCallback(
    val action: MailViewAction,
    val accountCode: String,
    val uidValidity: Long,
    val uid: Long,
)

object MailViewCallbackCodec {

    private const val PREFIX = "mail"
    private const val MAX_CALLBACK_BYTES = 64

    fun encode(
        action: MailViewAction,
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
    ): String {
        require(uidValidity > 0) {
            "UIDVALIDITY must be greater than zero"
        }

        require(uid > 0) {
            "UID must be greater than zero"
        }

        val data = buildString {
            append(PREFIX)
            append(':')
            append(action.code)
            append(':')
            append(accountCode(account))
            append(':')
            append(uidValidity)
            append(':')
            append(uid)
        }

        val byteCount = data
            .toByteArray(StandardCharsets.UTF_8)
            .size

        require(byteCount <= MAX_CALLBACK_BYTES) {
            "Telegram callback data exceeds 64 bytes"
        }

        return data
    }

    fun decode(data: String): MailViewCallback? {
        val parts = data.split(':')

        if (
            parts.size != 5 ||
            parts[0] != PREFIX
        ) {
            return null
        }

        val action = MailViewAction.fromCode(parts[1])
            ?: return null

        val accountCode = parts[2]
            .takeIf(String::isNotBlank)
            ?: return null

        val uidValidity = parts[3]
            .toLongOrNull()
            ?.takeIf { value -> value > 0 }
            ?: return null

        val uid = parts[4]
            .toLongOrNull()
            ?.takeIf { value -> value > 0 }
            ?: return null

        return MailViewCallback(
            action = action,
            accountCode = accountCode,
            uidValidity = uidValidity,
            uid = uid,
        )
    }

    fun accountCode(
        account: MailAccountConfig,
    ): String {
        val source = buildString {
            append(account.host.lowercase())
            append('\n')
            append(account.username.lowercase())
        }

        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(
                source.toByteArray(
                    StandardCharsets.UTF_8,
                ),
            )

        return HexFormat
            .of()
            .formatHex(
                digest.copyOfRange(0, 6),
            )
    }
}
