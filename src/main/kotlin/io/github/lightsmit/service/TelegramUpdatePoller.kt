package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.storage.TelegramUpdateStateRepository
import io.github.lightsmit.telegram.MailViewAction
import io.github.lightsmit.telegram.MailViewCallbackCodec
import io.github.lightsmit.telegram.TelegramApiException
import io.github.lightsmit.telegram.TelegramCallbackQueryUpdate
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class TelegramUpdatePoller(
    private val accounts: List<MailAccountConfig>,
    private val telegramChatId: Long,
    private val pollingClient: TelegramClient,
    private val controlClient: TelegramClient,
    private val forwardingService: MailForwardingService,
    private val stateRepository: TelegramUpdateStateRepository,
) {

    private val logger =
        LoggerFactory.getLogger(TelegramUpdatePoller::class.java)

    private val inProgressEmails =
        ConcurrentHashMap.newKeySet<String>()

    suspend fun run() = supervisorScope {
        var nextOffset = stateRepository.findNextOffset()

        while (currentCoroutineContext().isActive) {
            try {
                val batch = pollingClient.getCallbackQueryUpdates(
                    offset = nextOffset,
                    timeoutSeconds = LONG_POLL_TIMEOUT_SECONDS,
                )

                batch.nextOffset?.let { offset ->
                    stateRepository.saveNextOffset(offset)
                    nextOffset = offset
                }

                for (callbackQuery in batch.callbackQueries) {
                    launch {
                        handleCallbackQuery(callbackQuery)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn(
                    "Telegram update polling failed: {}",
                    exception.javaClass.simpleName,
                )
                delay(POLL_RETRY_DELAY_MILLIS)
            }
        }
    }

    private suspend fun handleCallbackQuery(
        update: TelegramCallbackQueryUpdate,
    ) {
        if (
            update.chatId != telegramChatId ||
            update.fromUserId != telegramChatId
        ) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = "Доступ запрещён",
                showAlert = true,
            )
            return
        }

        val request = update.data
            ?.let(MailViewCallbackCodec::decode)

        if (request == null) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = "Некорректная кнопка",
                showAlert = true,
            )
            return
        }

        val account = accounts.firstOrNull { configuredAccount ->
            MailViewCallbackCodec.accountCode(configuredAccount) ==
                    request.accountCode
        }

        if (account == null) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = "Почтовый ящик больше не настроен",
                showAlert = true,
            )
            return
        }

        if (
            request.action in setOf(
                MailViewAction.TEXT,
                MailViewAction.BACK,
            ) &&
            update.messageId == null
        ) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = "Не удалось определить сообщение",
                showAlert = true,
            )
            return
        }

        val emailKey = buildString {
            append(request.accountCode)
            append(':')
            append(request.uidValidity)
            append(':')
            append(request.uid)
        }

        if (!inProgressEmails.add(emailKey)) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = "Письмо уже обрабатывается",
                showAlert = false,
            )
            return
        }

        try {
            when (request.action) {
                MailViewAction.TEXT -> {
                    performActionWithAcknowledgement(
                        callbackQueryId = update.callbackQueryId,
                        text = "Открываю текст…",
                    ) {
                        forwardingService.openTextView(
                            account = account,
                            expectedUidValidity = request.uidValidity,
                            uid = request.uid,
                            sourceMessageId = requireNotNull(
                                update.messageId,
                            ),
                        )
                    }
                }

                MailViewAction.ATTACHMENTS -> {
                    performActionWithAcknowledgement(
                        callbackQueryId = update.callbackQueryId,
                        text = "Отправляю вложения…",
                    ) {
                        forwardingService.sendAttachments(
                            account = account,
                            expectedUidValidity = request.uidValidity,
                            uid = request.uid,
                        )
                    }
                }

                MailViewAction.BACK -> {
                    performActionWithAcknowledgement(
                        callbackQueryId = update.callbackQueryId,
                        text = null,
                    ) {
                        forwardingService.returnToSummary(
                            account = account,
                            expectedUidValidity = request.uidValidity,
                            uid = request.uid,
                            sourceMessageId = requireNotNull(
                                update.messageId,
                            ),
                        )
                    }
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn(
                "Failed to process action {} for email UID {} from mailbox {}: {}",
                request.action,
                request.uid,
                account.username,
                exception.javaClass.simpleName,
            )

            runCatching {
                controlClient.sendMessage(
                    chatId = telegramChatId,
                    text = "⚠️ Не удалось выполнить действие. Повторите попытку.",
                )
            }
        } finally {
            inProgressEmails.remove(emailKey)
        }
    }

    private suspend fun performActionWithAcknowledgement(
        callbackQueryId: String,
        text: String?,
        action: suspend () -> Unit,
    ) = supervisorScope {
        launch {
            answerSafely(
                callbackQueryId = callbackQueryId,
                text = text,
                showAlert = false,
            )
        }

        action()
    }

    private suspend fun answerSafely(
        callbackQueryId: String,
        text: String?,
        showAlert: Boolean,
    ) {
        try {
            withTimeoutOrNull(CALLBACK_ANSWER_TIMEOUT_MILLIS) {
                controlClient.answerCallbackQuery(
                    callbackQueryId = callbackQueryId,
                    text = text,
                    showAlert = showAlert,
                )
            }
        } catch (exception: TelegramApiException) {
            if (
                !exception.description.contains(
                    other = "query is too old",
                    ignoreCase = true,
                )
            ) {
                logger.warn(
                    "Failed to answer callback query: TelegramApiException",
                )
            }
        } catch (exception: Exception) {
            logger.warn(
                "Failed to answer callback query: {}",
                exception.javaClass.simpleName,
            )
        }
    }

    private companion object {
        const val LONG_POLL_TIMEOUT_SECONDS = 25
        const val POLL_RETRY_DELAY_MILLIS = 1_000L
        const val CALLBACK_ANSWER_TIMEOUT_MILLIS = 5_000L
    }
}
