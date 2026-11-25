package io.github.lightsmit.mail

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Ссылка отписки из заголовков рассылки (RFC 2369, RFC 8058).
 * oneClick - отписка одним POST-запросом без перехода на страницу.
 */
data class UnsubscribeLink(
    val url: String,
    val oneClick: Boolean,
)

object Unsubscribe {

    private const val MAX_URL_LENGTH = 2_000

    /**
     * List-Unsubscribe: «<mailto:...>, <https://...>». Берем первую https-ссылку:
     * mailto бот не обрабатывает (для этого нужен исходящий SMTP).
     */
    fun parse(listUnsubscribe: String?, listUnsubscribePost: String?): UnsubscribeLink? {
        val header = listUnsubscribe?.takeIf(String::isNotBlank) ?: return null
        val url = Regex("<\\s*(https://[^>\\s]+)\\s*>", RegexOption.IGNORE_CASE)
            .findAll(header)
            .map { match -> match.groupValues[1].trim() }
            .firstOrNull { value -> value.length <= MAX_URL_LENGTH && isWellFormed(value) }
            ?: return null
        val oneClick = listUnsubscribePost
            ?.replace(" ", "")
            ?.contains("List-Unsubscribe=One-Click", ignoreCase = true) == true
        return UnsubscribeLink(url = url, oneClick = oneClick)
    }

    private fun isWellFormed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    /**
     * Адрес в интернете, а не во внутренней сети сервера: ссылку из чужого письма
     * нельзя использовать, чтобы бот стучался к своим же сервисам.
     */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                when {
                    first == 0 || first >= 240 -> false
                    first == 100 && second in 64..127 -> false // CGNAT 100.64.0.0/10
                    first == 192 && second == 0 && (bytes[2].toInt() and 0xFF) == 0 -> false
                    first == 198 && (second == 18 || second == 19) -> false // 198.18.0.0/15
                    else -> true
                }
            }
            is Inet6Address -> {
                val first = bytes[0].toInt() and 0xFF
                // fc00::/7 - локальные адреса IPv6; ::ffff:a.b.c.d - проверяем как IPv4
                if ((first and 0xFE) == 0xFC) return false
                val mapped = bytes.take(10).all { value -> value.toInt() == 0 } &&
                    (bytes[10].toInt() and 0xFF) == 0xFF && (bytes[11].toInt() and 0xFF) == 0xFF
                if (mapped) isPublicAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16))) else true
            }
            else -> false
        }
    }
}
