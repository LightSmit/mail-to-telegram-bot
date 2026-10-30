package io.github.lightsmit.service

import io.github.lightsmit.storage.BotUserRepository
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
    // Для команды /mailboxes: список ящиков пользователя и их статус
    private val accountService: MailAccountService? = null,
    // Учет пользователей бота (статистика, рассылки)
    private val botUsers: BotUserRepository? = null,
    // Команды администратора: /admin, /broadcast, /backup
    private val adminService: AdminService? = null,
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
        touchUser(update.fromUserId)

        // Кнопки работают только в личном чате с ботом: письма приходят туда
        if (update.chatId != update.fromUserId) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = "Доступ запрещен",
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
                text = "Почтовый ящик больше не подключен",
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

                MailViewAction.READ -> {
                    val marked = forwardingService.markAsRead(
                        account = account,
                        expectedUidValidity = request.uidValidity,
                        uid = request.uid,
                    )
                    answerSafely(
                        callbackQueryId = update.callbackQueryId,
                        text = if (marked) {
                            "✅ Отмечено прочитанным в ящике"
                        } else {
                            "Письмо не найдено: его могли удалить или переместить"
                        },
                        showAlert = !marked,
                    )
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

    // Бот не переписывается: отвечает на команды /start, /help, /mailboxes,
    // на остальное - короткой подсказкой. Только в личном чате.
    private suspend fun handleMessage(
        message: TelegramMessageUpdate,
    ) {
        if (message.chatType != "private") return
        touchUser(message.fromUserId)

        val fullText = message.text?.trim().orEmpty()
        val firstToken = fullText.substringBefore(' ').substringBefore('\n')
        val command = firstToken
            .substringBefore('@')
            .lowercase()
            .takeIf { fullText.isNotEmpty() }

        val admin = adminService?.takeIf { service -> service.isAdmin(message.fromUserId) }
        if (admin != null && command != null && handleAdminCommand(admin, message.chatId, message.fromUserId, command, fullText.removePrefix(firstToken).trim())) {
            return
        }

        val text = when (command) {
            "/start" -> START_TEXT
            "/help" -> HELP_TEXT
            "/mailboxes" -> mailboxesText(message.fromUserId)
            else -> "Я не отвечаю на сообщения, зато пересылаю сюда новые письма 🙂\n\n" +
                "Команды: /mailboxes - мои ящики, /help - как подключить почту."
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

    // true - команда администратора обработана
    private suspend fun handleAdminCommand(
        admin: AdminService,
        chatId: Long,
        adminId: Long,
        command: String,
        argument: String,
    ): Boolean {
        val reply: String = try {
            when (command) {
                "/admin", "/stats" -> admin.statsText()
                "/broadcast" -> admin.prepareBroadcast(adminId, argument)
                "/broadcast_cancel" -> admin.cancelBroadcast(adminId)
                "/broadcast_send" -> {
                    sendPlain(chatId, "Начинаю рассылку. Пришлю итог, когда закончу.")
                    admin.sendBroadcast(adminId)
                }
                "/backup" -> admin.backupText()
                else -> return false
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Admin command {} failed: {}", command, exception.javaClass.simpleName)
            "Команда не выполнена: ${exception.javaClass.simpleName}. Подробности в логе сервера."
        }

        sendPlain(chatId, reply)
        return true
    }

    private suspend fun sendPlain(chatId: Long, text: String) {
        try {
            controlClient.sendMessage(chatId = chatId, text = text)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to send message to {}: {}", chatId, exception.javaClass.simpleName)
        }
    }

    private suspend fun touchUser(userId: Long) {
        try {
            botUsers?.touch(userId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug("Failed to record bot user {}: {}", userId, exception.javaClass.simpleName)
        }
    }

    private suspend fun mailboxesText(ownerChatId: Long): String {
        val service = accountService ?: return "Список ящиков открывается в приложении."
        val accounts = service.list(ownerChatId)

        if (accounts.isEmpty()) {
            return "У вас пока нет подключенных ящиков. Подключите первый в приложении, инструкция там же."
        }

        return buildString {
            appendLine("📬 Ваши ящики")
            accounts.forEach { account ->
                val health = service.health(account.id)
                val (mark, status) = when (health.status) {
                    MailboxStatus.OK -> "🟢" to "письма приходят"
                    MailboxStatus.CONNECTING -> "🟡" to "подключается"
                    MailboxStatus.ERROR -> "🔴" to (health.problem?.replaceFirstChar(Char::lowercase) ?: "не удается подключиться")
                }
                appendLine()
                append(mark).append(' ').append(account.username)
                if (account.name != account.username) append(" (").append(account.name).append(')')
                appendLine()
                append("    ").append(status)
            }
            appendLine()
            appendLine()
            append("Подключить или отключить ящик можно в приложении.")
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
        val START_TEXT = """
            👋 Привет! Я пересылаю новые письма из ваших почтовых ящиков прямо сюда, в Telegram.

            Нажмите «Открыть приложение», чтобы подключить первый ящик. Понадобится адрес почты и пароль приложения, инструкция для вашего почтового сервиса будет там же.

            /help - подробно о том, как все работает
            /mailboxes - мои ящики и их состояние
        """.trimIndent()

        val HELP_TEXT = """
            📖 Как это работает

            Я подключаюсь к вашему ящику по протоколу IMAP и присылаю сюда карточку каждого нового письма: от кого, тема, дата и есть ли вложения. Старые письма не пересылаются.

            Как подключить ящик
            1. Откройте приложение кнопкой ниже.
            2. Введите адрес почты, сервис определится сам.
            3. Создайте в настройках почты пароль приложения по инструкции в приложении и вставьте его. Обычный пароль от почты не подойдет: почтовые сервисы не пускают по нему сторонние программы.
            4. Нажмите «Подключить ящик». Я проверю вход и напишу, что ящик подключен.

            Кнопки под письмом
            📄 Текст - показать текст письма
            📎 Вложения - прислать файлы из письма

            Если ящик перестанет работать (например, вы сменили пароль от почты), я напишу об этом. Отключить ящик можно в приложении: пароль приложения при этом стоит удалить и в настройках почты.

            Поддерживаются Mail.ru, Яндекс, Gmail, Рамблер, iCloud и другие ящики с доступом по IMAP.
        """.trimIndent()

        const val LONG_POLL_TIMEOUT_SECONDS = 25
        const val POLL_RETRY_DELAY_MILLIS = 1_000L
        const val CALLBACK_ANSWER_TIMEOUT_MILLIS = 5_000L
    }
}
