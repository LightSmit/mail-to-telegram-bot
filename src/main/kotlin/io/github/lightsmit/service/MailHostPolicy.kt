package io.github.lightsmit.service

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Правила для IMAP-сервера, который вводит пользователь. Сервер подключается
 * туда, куда ему скажут, поэтому внутренние адреса (localhost, локальная сеть,
 * сеть Docker) запрещены: иначе через бота можно было бы прощупывать сам сервер.
 */
object MailHostPolicy {

    const val IMAPS_PORT = 993

    private val hostnamePattern =
        Regex("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    /** Приводит имя сервера к виду "imap.example.com" или возвращает null, если оно некорректно. */
    fun normalizeHostname(raw: String): String? {
        val host = raw.trim().trimEnd('.').lowercase()
        return host.takeIf { hostnamePattern.matches(it) }
    }

    /** true, если адрес публичный (не loopback, не локальная сеть, не служебные диапазоны). */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }

        val bytes = address.address

        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                when {
                    first == 0 -> false                          // 0.0.0.0/8
                    first == 100 && second in 64..127 -> false   // 100.64.0.0/10 (CGNAT)
                    first == 192 && second == 0 && (bytes[2].toInt() and 0xFF) == 0 -> false // 192.0.0.0/24
                    first == 198 && second in 18..19 -> false    // 198.18.0.0/15
                    first >= 224 -> false                        // multicast и зарезервированные
                    else -> true
                }
            }

            is Inet6Address -> {
                val first = bytes[0].toInt() and 0xFF
                // fc00::/7 (unique local)
                (first and 0xFE) != 0xFC
            }

            else -> false
        }
    }
}
