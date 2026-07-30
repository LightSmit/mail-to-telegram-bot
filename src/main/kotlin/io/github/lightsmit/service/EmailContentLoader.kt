package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.mail.EmailSummary
import io.github.lightsmit.mail.ImapMailClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

private data class CachedEmailKey(
    val accountCode: String,
    val uidValidity: Long,
    val uid: Long,
)

class EmailContentLoader(
    private val imapClient: ImapMailClient,
    private val accountCode: (MailAccountConfig) -> String,
) : AutoCloseable {

    private val logger =
        LoggerFactory.getLogger(EmailContentLoader::class.java)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )

    private val fetchSemaphore = Semaphore(2)
    private val entries =
        ConcurrentHashMap<CachedEmailKey, Deferred<EmailSummary?>>()

    fun prefetch(
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
    ): Deferred<EmailSummary?> {
        val key = CachedEmailKey(
            accountCode = accountCode(account),
            uidValidity = uidValidity,
            uid = uid,
        )

        return entries.computeIfAbsent(key) {
            val deferred = scope.async {
                fetchSemaphore.withPermit {
                    fetchWithRetry(
                        account = account,
                        uidValidity = uidValidity,
                        uid = uid,
                    )
                }
            }

            deferred.invokeOnCompletion { failure ->
                if (failure != null) {
                    entries.remove(key, deferred)
                }
            }

            scope.launch {
                delay(CACHE_TTL_MILLIS)
                if (entries.remove(key, deferred)) {
                    val message = runCatching {
                        deferred.await()
                    }.getOrNull()
                    message?.let(::deleteAttachments)
                }
            }

            deferred
        }
    }

    suspend fun get(
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
    ): EmailSummary? {
        return prefetch(account, uidValidity, uid).await()
    }

    fun release(
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
    ) {
        val key = CachedEmailKey(
            accountCode = accountCode(account),
            uidValidity = uidValidity,
            uid = uid,
        )

        val deferred = entries.remove(key) ?: return
        scope.launch {
            val message = runCatching { deferred.await() }.getOrNull()
            message?.let(::deleteAttachments)
        }
    }

    private suspend fun fetchWithRetry(
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
    ): EmailSummary? {
        val maximumAttempts = 3

        for (attempt in 1..maximumAttempts) {
            try {
                val startedAt = System.currentTimeMillis()
                val message = imapClient.fetchByUid(
                    account = account,
                    expectedUidValidity = uidValidity,
                    uid = uid,
                )

                logger.info(
                    "Prefetched email UID {} from mailbox {} in {} ms",
                    uid,
                    account.username,
                    System.currentTimeMillis() - startedAt,
                )
                return message
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (attempt == maximumAttempts) throw exception
                logger.warn(
                    "Prefetch attempt {}/{} for email UID {} from mailbox {} failed: {}",
                    attempt,
                    maximumAttempts,
                    uid,
                    account.username,
                    exception.javaClass.simpleName,
                )
                delay(attempt * 2_000L)
            }
        }

        return null
    }

    private fun deleteAttachments(message: EmailSummary) {
        message.attachments.forEach { attachment ->
            runCatching {
                Files.deleteIfExists(attachment.tempFile)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private companion object {
        const val CACHE_TTL_MILLIS = 6L * 60L * 60L * 1_000L
    }
}
