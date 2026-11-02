package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
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
    // Ответ на письмо из Telegram; null - кнопка "Ответить" не работает
    private val replyService: MailReplyService? = null,
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

                MailViewAction.REPLY -> {
                    startReply(update.callbackQueryId, account, request.uidValidity, request.uid)
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

        // Ответ на письмо: ждем текст после нажатия "Ответить"
        val replies = replyService
        if (replies != null && replies.isWaiting(message.fromUserId)) {
            when {
                command == "/cancel" -> {
                    replies.cancel(message.fromUserId)
                    sendPlain(message.chatId, "Ответ отменен.")
                    return
                }
                message.text == null -> {
                    sendPlain(message.chatId, "Пока можно ответить только текстом. Напишите ответ или отправьте /cancel.")
                    return
                }
                !fullText.startsWith("/") -> {
                    sendReply(replies, message.chatId, message.fromUserId, message.text)
                    return
                }
            }
        }

        if (command == "/cancel") {
            sendPlain(message.chatId, "Сейчас нечего отменять.")
            return
        }

        val text = when (command) {
            "/start" -> START_TEXT
            "/help" -> if (replyService != null) HELP_TEXT else HELP_TEXT.lines().filterNot { line -> line.startsWith("↩️") }.joinToString("\n")
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

    private suspend fun startReply(
        callbackQueryId: String,
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
    ) {
        val replies = replyService
        if (replies == null) {
            answerSafely(callbackQueryId, "Ответ на письма из Telegram временно отключен", showAlert = true)
            return
        }

        when (val result = replies.start(account, uidValidity, uid)) {
            is MailReplyService.StartResult.Ready -> {
                answerSafely(callbackQueryId, null, showAlert = false)
                controlClient.sendMessageForceReply(
                    chatId = account.ownerChatId,
                    text = "↩️ Ответ на «${result.subject.take(200)}»\n" +
                        "Кому: ${result.recipients.take(300)}\n" +
                        "С ящика: ${account.username}\n\n" +
                        "Напишите текст ответа следующим сообщением. /cancel - отменить.",
                    placeholder = "Текст ответа",
                )
            }
            MailReplyService.StartResult.NotFound -> answerSafely(
                callbackQueryId, "Письмо не найдено: его могли удалить или переместить", showAlert = true,
            )
            MailReplyService.StartResult.NoRecipient -> answerSafely(
                callbackQueryId, "В письме нет адреса для ответа", showAlert = true,
            )
            MailReplyService.StartResult.Unsupported -> answerSafely(
                callbackQueryId, "Для этого почтового сервиса ответ из Telegram пока не поддерживается", showAlert = true,
            )
        }
    }

    private suspend fun sendReply(
        replies: MailReplyService,
        chatId: Long,
        userId: Long,
        body: String,
    ) {
        val text = when (val result = replies.send(userId, body)) {
            is MailReplyService.SendResult.Sent -> "✅ Ответ отправлен: ${result.recipients}"
            MailReplyService.SendResult.NoPending ->
                "Время на ответ вышло. Нажмите «↩️ Ответить» под письмом еще раз."
            MailReplyService.SendResult.TooLong ->
                "Слишком длинный ответ: не больше ${MailReplyService.MAX_BODY_LENGTH} символов. Сократите текст и отправьте снова."
            MailReplyService.SendResult.MailboxGone ->
                "Этот ящик больше не подключен, ответ не отправлен."
            MailReplyService.SendResult.AuthFailed ->
                "⚠️ Почта не приняла пароль приложения для отправки писем. Проверьте, что пароль приложения разрешает отправку (SMTP), и подключите ящик заново."
            MailReplyService.SendResult.Failed ->
                "⚠️ Не удалось связаться с почтовым сервером для отправки. Попробуйте отправить текст еще раз позже или /cancel."
        }
        sendPlain(chatId, text)
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
                val (mark, status) = if (account.filters.paused) "⏸" to "на паузе" else when (health.status) {
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
            ↩️ Ответить - ответить на письмо прямо отсюда: напишите текст, и я отправлю его с вашего ящика
            ✅ Прочитано - отметить письмо прочитанным в почте

            В приложении можно открыть любой ящик и посмотреть его письма: входящие и отправленные, с поиском и фильтрами.

            Тихие часы (письма без звука) и сводку (письма пачкой раз в несколько часов) можно включить в приложении, раздел «Уведомления».

            Если ящик перестанет работать (например, вы сменили пароль от почты), я напишу об этом. Отключить ящик можно в приложении: пароль приложения при этом стоит удалить и в настройках почты.

            Поддерживаются Mail.ru, Яндекс, Gmail, Рамблер, iCloud и другие ящики с доступом по IMAP.
        """.trimIndent()

        const val LONG_POLL_TIMEOUT_SECONDS = 25
        const val POLL_RETRY_DELAY_MILLIS = 1_000L
        const val CALLBACK_ANSWER_TIMEOUT_MILLIS = 5_000L
    }
}
