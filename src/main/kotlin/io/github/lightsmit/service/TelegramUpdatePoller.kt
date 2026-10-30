package io.github.lightsmit.service

import io.github.lightsmit.storage.TelegramUpdateStateRepository
import io.github.lightsmit.telegram.MailViewAction
import io.github.lightsmit.telegram.MailViewCallbackCodec
import io.github.lightsmit.telegram.TelegramApiException
import io.github.lightsmit.telegram.TelegramCallbackQueryUpdate
import io.github.lightsmit.telegram.TelegramClient
import io.github.lightsmit.telegram.TelegramMessageUpdate
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
    private val registry: MailAccountRegistry,
    private val pollingClient: TelegramClient,
    private val controlClient: TelegramClient,
    private val forwardingService: MailForwardingService,
    private val stateRepository: TelegramUpdateStateRepository,
    // Адрес Mini App для кнопки в приветствии; null - кнопку не показываем
    private val miniAppUrl: String? = null,
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

                for (message in batch.messages) {
                    launch {
                        handleMessage(message)
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
        // Кнопки работают только в личном чате с ботом: письма приходят туда
        if (update.chatId != update.fromUserId) {
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

        // Ищем только среди ящиков нажавшего: чужие письма открыть нельзя
        val account = registry.findForCallback(
            accountCode = request.accountCode,
            ownerChatId = update.fromUserId,
        )

        if (account == null) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = "Почтовый ящик больше не подключён",
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
            append(account.ownerChatId)
            append(':')
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
                    chatId = account.ownerChatId,
                    text = "⚠️ Не удалось выполнить действие. Повторите попытку.",
                )
            }
        } finally {
            inProgressEmails.remove(emailKey)
        }
    }

    // Бот не переписывается: на /start и /help отвечает инструкцией,
    // на остальное - короткой подсказкой. Только в личном чате.
    private suspend fun handleMessage(
        message: TelegramMessageUpdate,
    ) {
        if (message.chatType != "private") return

        val command = message.text
            ?.trim()
            ?.substringBefore(' ')
            ?.substringBefore('@')
            ?.lowercase()

        val text = when (command) {
            "/start", "/help" -> WELCOME_TEXT
            else -> "Я не отвечаю на сообщения, зато пересылаю сюда новые письма 🙂\n\n" +
                "Подключить или удалить ящик можно в приложении. Инструкция: /help"
        }

        try {
            if (miniAppUrl != null) {
                controlClient.sendMessageWithWebAppButton(
                    chatId = message.chatId,
                    text = text,
                    buttonText = "📬 Открыть приложение",
                    webAppUrl = miniAppUrl,
                )
            } else {
                controlClient.sendMessage(chatId = message.chatId, text = text)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to answer message from {}: {}", message.fromUserId, exception.javaClass.simpleName)
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
        val WELCOME_TEXT = """
            👋 Привет! Я пересылаю новые письма из ваших почтовых ящиков прямо сюда, в Telegram.

            Как подключить ящик:
            1. Откройте приложение кнопкой ниже (или кнопкой меню слева от поля ввода).
            2. Создайте в настройках почты пароль приложения. Это НЕ ваш обычный пароль от почты, а отдельный код, который выдаёт сам почтовый сервис. Пошаговая инструкция для каждого сервиса есть в приложении.
            3. Введите адрес почты и пароль приложения. Бот проверит вход и начнёт присылать новые письма.

            Поддерживаются Mail.ru, Яндекс, Gmail, Рамблер, iCloud и другие ящики с доступом по IMAP.
        """.trimIndent()

        const val LONG_POLL_TIMEOUT_SECONDS = 25
        const val POLL_RETRY_DELAY_MILLIS = 1_000L
        const val CALLBACK_ANSWER_TIMEOUT_MILLIS = 5_000L
    }
}
