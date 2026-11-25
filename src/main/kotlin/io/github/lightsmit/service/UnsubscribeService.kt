package io.github.lightsmit.service

import io.github.lightsmit.mail.Unsubscribe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Отписка в один клик (RFC 8058): POST «List-Unsubscribe=One-Click» на ссылку рассылки.
 * Только https и только публичные адреса; перенаправления не выполняются.
 */
class UnsubscribeService(
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) {
    private val logger = LoggerFactory.getLogger(UnsubscribeService::class.java)

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    suspend fun oneClick(url: String): Boolean = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return@withContext false
        val host = uri.host
        if (!uri.scheme.equals("https", ignoreCase = true) || host.isNullOrBlank()) return@withContext false

        val addresses = runCatching { resolve(host) }.getOrDefault(emptyList())
        if (addresses.isEmpty() || !addresses.all(Unsubscribe::isPublicAddress)) {
            logger.warn("Unsubscribe link host {} is not public, skipped", host)
            return@withContext false
        }

        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "MailToTelegramBot/1.0 (List-Unsubscribe)")
            .POST(HttpRequest.BodyPublishers.ofString("List-Unsubscribe=One-Click"))
            .build()
        try {
            val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            logger.info("One-click unsubscribe to {} answered {}", host, status)
            status in 200..299
        } catch (exception: java.io.IOException) {
            logger.warn("One-click unsubscribe to {} failed: {}", host, exception.javaClass.simpleName)
            false
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
