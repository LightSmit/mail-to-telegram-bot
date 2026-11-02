package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.i18n.Lang
import io.github.lightsmit.i18n.LANGUAGE_PROMPT
import io.github.lightsmit.i18n.Texts
import io.github.lightsmit.telegram.TelegramInlineButton
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
    // Удаление всех данных пользователя по команде /delete_my_data
    private val userData: UserDataService? = null,
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
        touchUser(update.fromUserId, update.languageCode)
        val t = textsFor(update.fromUserId, update.languageCode)

        // Кнопки работают только в личном чате с ботом: письма приходят туда
        if (update.chatId != update.fromUserId) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = t.accessDenied,
                showAlert = true,
            )
            return
        }

        update.data?.takeIf { data -> data.startsWith(LANGUAGE_CALLBACK_PREFIX) }?.let { data ->
            chooseLanguage(update, data.removePrefix(LANGUAGE_CALLBACK_PREFIX))
            return
        }

        val request = update.data
            ?.let(MailViewCallbackCodec::decode)

        if (request == null) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = t.badButton,
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
                text = t.mailboxGone,
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
                text = t.cannotFindMessage,
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
                text = t.alreadyProcessing,
                showAlert = false,
            )
            return
        }

        try {
            when (request.action) {
                MailViewAction.TEXT -> {
                    performActionWithAcknowledgement(
                        callbackQueryId = update.callbackQueryId,
                        text = t.openingText,
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
                        text = t.sendingAttachmentsShort,
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
                        text = if (marked) t.markedRead else t.messageGone,
                        showAlert = !marked,
                    )
                }

                MailViewAction.REPLY -> {
                    startReply(update.callbackQueryId, account, request.uidValidity, request.uid, t)
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
                    text = t.actionFailed,
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
        touchUser(message.fromUserId, message.languageCode)
        val t = textsFor(message.fromUserId, message.languageCode)

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
                    sendPlain(message.chatId, t.replyCancelled)
                    return
                }
                message.text == null -> {
                    sendPlain(message.chatId, t.replyTextOnly)
                    return
                }
                !fullText.startsWith("/") -> {
                    sendReply(replies, message.chatId, message.fromUserId, message.text, t)
                    return
                }
            }
        }

        if (command == "/cancel") {
            sendPlain(message.chatId, t.nothingToCancel)
            return
        }

        if (command == "/delete_my_data" || command == "/delete_my_data_confirm") {
            handleDeleteData(message.chatId, message.fromUserId, confirmed = command == "/delete_my_data_confirm", t = t)
            return
        }

        if (command == "/language") {
            askLanguage(message.chatId)
            return
        }

        val text = when (command) {
            "/start" -> t.start()
            "/help" -> t.help(replyEnabled = replyService != null)
            "/mailboxes" -> mailboxesText(message.fromUserId, t)
            else -> t.defaultReply
        }

        try {
            if (miniAppUrl != null) {
                controlClient.sendMessageWithWebAppButton(
                    chatId = message.chatId,
                    text = text,
                    buttonText = t.openApp,
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
        t: Texts,
    ) {
        val replies = replyService
        if (replies == null) {
            answerSafely(callbackQueryId, t.replyDisabled, showAlert = true)
            return
        }

        when (val result = replies.start(account, uidValidity, uid)) {
            is MailReplyService.StartResult.Ready -> {
                answerSafely(callbackQueryId, null, showAlert = false)
                controlClient.sendMessageForceReply(
                    chatId = account.ownerChatId,
                    text = t.replyPrompt(
                        subject = result.subject.ifBlank { t.noSubject }.take(200),
                        recipients = result.recipients.take(300),
                        mailbox = account.username,
                    ),
                    placeholder = t.replyPlaceholder,
                )
            }
            MailReplyService.StartResult.NotFound -> answerSafely(
                callbackQueryId, t.messageGone, showAlert = true,
            )
            MailReplyService.StartResult.NoRecipient -> answerSafely(
                callbackQueryId, t.replyNoRecipient, showAlert = true,
            )
            MailReplyService.StartResult.Unsupported -> answerSafely(
                callbackQueryId, t.replyUnsupported, showAlert = true,
            )
        }
    }

    private suspend fun sendReply(
        replies: MailReplyService,
        chatId: Long,
        userId: Long,
        body: String,
        t: Texts,
    ) {
        val text = when (val result = replies.send(userId, body)) {
            is MailReplyService.SendResult.Sent -> t.replySent(result.recipients)
            MailReplyService.SendResult.NoPending -> t.replyExpired
            MailReplyService.SendResult.TooLong -> t.replyTooLong(MailReplyService.MAX_BODY_LENGTH)
            MailReplyService.SendResult.MailboxGone -> t.replyMailboxGone
            MailReplyService.SendResult.AuthFailed -> t.replyAuthFailed
            MailReplyService.SendResult.Failed -> t.replyFailed
        }
        sendPlain(chatId, text)
    }

    // Сначала предупреждение, удаление - только отдельной командой подтверждения
    private suspend fun handleDeleteData(chatId: Long, userId: Long, confirmed: Boolean, t: Texts) {
        val service = userData ?: return
        val text = try {
            if (!confirmed) {
                t.deleteWarning(service.mailboxCount(userId))
            } else {
                t.deleteDone(service.deleteAll(userId), service.backupKeepDays)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to delete data of user {}: {}", userId, exception.javaClass.simpleName)
            t.deleteFailed
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

    // Язык: из текущего сообщения Telegram, иначе последний известный
    // Язык ответа: выбранный вручную (/language), иначе из этого сообщения Telegram, иначе сохраненный
    private suspend fun textsFor(userId: Long, languageCode: String?): Texts {
        val users = botUsers ?: return Lang.fromCode(languageCode).texts
        return runCatching { users.resolve(userId, languageCode) }.getOrElse { Lang.fromCode(languageCode) }.texts
    }

    /** Кнопки выбора языка; вопрос на двух языках, чтобы его понял любой. */
    private suspend fun askLanguage(chatId: Long) {
        try {
            controlClient.sendMessageWithButtons(
                chatId = chatId,
                text = LANGUAGE_PROMPT,
                buttons = listOf(
                    TelegramInlineButton(text = "🇷🇺 Русский", callbackData = LANGUAGE_CALLBACK_PREFIX + "ru"),
                    TelegramInlineButton(text = "🇬🇧 English", callbackData = LANGUAGE_CALLBACK_PREFIX + "en"),
                ),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to send language choice to {}: {}", chatId, exception.javaClass.simpleName)
        }
    }

    // Нажали "Русский" или "English": запоминаем выбор, переводим меню команд в этом чате
    private suspend fun chooseLanguage(update: TelegramCallbackQueryUpdate, code: String) {
        val lang = if (code == "en") Lang.EN else Lang.RU
        val users = botUsers
        if (users == null) {
            answerSafely(update.callbackQueryId, null, showAlert = false)
            return
        }

        users.setLanguage(update.fromUserId, lang)
        val texts = lang.texts
        answerSafely(update.callbackQueryId, texts.languageSet, showAlert = false)

        runCatching {
            controlClient.setMyCommands(
                commands = listOf(
                    "start" to texts.commandStart,
                    "mailboxes" to texts.commandMailboxes,
                    "help" to texts.commandHelp,
                    "language" to texts.commandLanguage,
                ),
                chatId = update.fromUserId,
            )
        }

        val chatId = update.chatId ?: update.fromUserId
        try {
            if (miniAppUrl != null) {
                controlClient.sendMessageWithWebAppButton(
                    chatId = chatId,
                    text = texts.languageSet,
                    buttonText = texts.openApp,
                    webAppUrl = miniAppUrl,
                )
            } else {
                controlClient.sendMessage(chatId = chatId, text = texts.languageSet)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to confirm language for {}: {}", update.fromUserId, exception.javaClass.simpleName)
        }
    }

    private suspend fun touchUser(userId: Long, languageCode: String?) {
        try {
            botUsers?.touch(userId, languageCode)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug("Failed to record bot user {}: {}", userId, exception.javaClass.simpleName)
        }
    }

    private suspend fun mailboxesText(ownerChatId: Long, t: Texts): String {
        val service = accountService ?: return t.mailboxesAppOnly
        val accounts = service.list(ownerChatId)

        if (accounts.isEmpty()) {
            return t.mailboxesEmpty
        }

        return buildString {
            appendLine(t.mailboxesTitle)
            accounts.forEach { account ->
                val health = service.health(account.id)
                val (mark, status) = if (account.filters.paused) "⏸" to t.statusPaused else when (health.status) {
                    MailboxStatus.OK -> "🟢" to t.statusOk
                    MailboxStatus.CONNECTING -> "🟡" to t.statusConnecting
                    MailboxStatus.ERROR -> "🔴" to (health.problem?.text(t)?.replaceFirstChar(Char::lowercase) ?: t.statusErrorFallback)
                }
                appendLine()
                append(mark).append(' ').append(account.username)
                if (account.name != account.username) append(" (").append(account.name).append(')')
                appendLine()
                append("    ").append(status)
            }
            appendLine()
            appendLine()
            append(t.mailboxesFooter)
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
        const val LANGUAGE_CALLBACK_PREFIX = "lang:"
        const val LONG_POLL_TIMEOUT_SECONDS = 25
        const val POLL_RETRY_DELAY_MILLIS = 1_000L
        const val CALLBACK_ANSWER_TIMEOUT_MILLIS = 5_000L
    }
}
