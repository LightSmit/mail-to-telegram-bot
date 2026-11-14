package io.github.lightsmit.telegram

/**
 * Какие сетевые ошибки точно означают, что запрос не дошел до Telegram.
 * Не удалось найти адрес, подключиться или договориться о шифровании - сервер Telegram
 * запрос не получил, и повторять его безопасно. Таймаут ответа, обрыв соединения и прочее -
 * запрос мог дойти, и сообщение могло быть доставлено.
 */
object TelegramNetworkErrors {
    // Имена классов, а не сами классы: часть из них живет в разных модулях Ktor и OkHttp
    private val NOT_SENT = setOf(
        "ConnectException",
        "ConnectTimeoutException",
        "UnknownHostException",
        "NoRouteToHostException",
        "UnresolvedAddressException",
        "SSLHandshakeException",
        "PortUnreachableException",
    )

    fun definitelyNotSent(exception: Throwable): Boolean {
        var current: Throwable? = exception
        var depth = 0
        while (current != null && depth < 10) {
            if (current.javaClass.simpleName in NOT_SENT) return true
            current = current.cause.takeIf { cause -> cause !== current }
            depth++
        }
        return false
    }
}
