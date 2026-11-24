package io.github.lightsmit.mail

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimeUtility
import io.github.lightsmit.i18n.SKIP_TOO_LARGE
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.nio.file.Files
import java.nio.file.Path

object EmailContentExtractor {

    fun extract(
        part: Part,
        tempDirectory: Path,
        maxAttachmentSizeBytes: Long,
        includeAttachments: Boolean = true,
    ): EmailContent {
        require(maxAttachmentSizeBytes > 0) {
            "Maximum attachment size must be greater than zero"
        }

        Files.createDirectories(tempDirectory)

        val plainTextBodies = mutableListOf<String>()
        val htmlBodies = mutableListOf<String>()
        val attachments = mutableListOf<EmailAttachment>()
        val skippedAttachments =
            mutableListOf<SkippedAttachment>()

        try {
            collectParts(
                part = part,
                tempDirectory = tempDirectory,
                maxAttachmentSizeBytes =
                    maxAttachmentSizeBytes,
                includeAttachments =
                    includeAttachments,
                plainTextBodies = plainTextBodies,
                htmlBodies = htmlBodies,
                attachments = attachments,
                skippedAttachments =
                    skippedAttachments,
            )
        } catch (exception: Exception) {
            attachments.forEach { attachment ->
                runCatching {
                    Files.deleteIfExists(
                        attachment.tempFile,
                    )
                }
            }

            throw exception
        }

        val body = plainTextBodies
            .firstOrNull { text ->
                text.isNotBlank()
            }
            ?: htmlBodies.firstOrNull { text ->
                text.isNotBlank()
            }

        return EmailContent(
            body = body
                ?.let(::cleanText)
                ?.takeIf { text ->
                    text.isNotBlank()
                },

            attachments = attachments,

            skippedAttachments =
                skippedAttachments,
        )
    }

    /** Имена вложений письма без скачивания самих файлов (для просмотра в приложении). */
    fun attachmentNames(part: Part): List<String> {
        val names = mutableListOf<String>()

        fun walk(current: Part) {
            if (isAttachment(current)) {
                names += extractFileName(current, names.size + 1)
                return
            }
            if (current.isMimeType("multipart/*")) {
                val multipart = current.content as? Multipart ?: return
                for (index in 0 until multipart.count) {
                    walk(multipart.getBodyPart(index))
                }
            }
        }

        runCatching { walk(part) }
        return names
    }

    private fun collectParts(
        part: Part,
        tempDirectory: Path,
        maxAttachmentSizeBytes: Long,
        includeAttachments: Boolean,
        plainTextBodies: MutableList<String>,
        htmlBodies: MutableList<String>,
        attachments: MutableList<EmailAttachment>,
        skippedAttachments:
        MutableList<SkippedAttachment>,
    ) {
        if (isAttachment(part)) {
            if (includeAttachments) {
                saveAttachment(
                    part = part,
                    tempDirectory = tempDirectory,
                    maxAttachmentSizeBytes =
                        maxAttachmentSizeBytes,
                    attachments = attachments,
                    skippedAttachments =
                        skippedAttachments,
                )
            }

            return
        }

        when {
            part.isMimeType("text/plain") -> {
                val text = part.content as? String
                    ?: return

                plainTextBodies += text
            }

            part.isMimeType("text/html") -> {
                val html = part.content as? String
                    ?: return

                htmlBodies += convertHtmlToText(html)
            }

            part.isMimeType("multipart/*") -> {
                val multipart = part.content as? Multipart
                    ?: return

                for (index in 0 until multipart.count) {
                    collectParts(
                        part = multipart.getBodyPart(index),

                        tempDirectory =
                            tempDirectory,

                        maxAttachmentSizeBytes =
                            maxAttachmentSizeBytes,

                        includeAttachments =
                            includeAttachments,

                        plainTextBodies =
                            plainTextBodies,

                        htmlBodies =
                            htmlBodies,

                        attachments =
                            attachments,

                        skippedAttachments =
                            skippedAttachments,
                    )
                }
            }

            part.isMimeType("message/rfc822") -> {
                val nestedPart = part.content as? Part
                    ?: return

                collectParts(
                    part = nestedPart,

                    tempDirectory =
                        tempDirectory,

                    maxAttachmentSizeBytes =
                        maxAttachmentSizeBytes,

                    includeAttachments =
                        includeAttachments,

                    plainTextBodies =
                        plainTextBodies,

                    htmlBodies =
                        htmlBodies,

                    attachments =
                        attachments,

                    skippedAttachments =
                        skippedAttachments,
                )
            }
        }
    }

    private fun saveAttachment(
        part: Part,
        tempDirectory: Path,
        maxAttachmentSizeBytes: Long,
        attachments: MutableList<EmailAttachment>,
        skippedAttachments:
        MutableList<SkippedAttachment>,
    ) {
        val attachmentNumber =
            attachments.size +
                    skippedAttachments.size +
                    1

        val fileName = extractFileName(
            part = part,
            attachmentNumber = attachmentNumber,
        )

        val declaredSize = part.size.toLong()

        if (
            declaredSize >= 0 &&
            declaredSize > maxAttachmentSizeBytes
        ) {
            skippedAttachments += SkippedAttachment(
                fileName = fileName,
                reason =
                    SKIP_TOO_LARGE,
            )

            return
        }

        val tempFile = Files.createTempFile(
            tempDirectory,
            "mail-",
            "-$fileName",
        )

        var totalBytes = 0L

        try {
            part.inputStream.use { input ->
                Files.newOutputStream(tempFile).use { output ->
                    val buffer =
                        ByteArray(DEFAULT_BUFFER_SIZE)

                    while (true) {
                        val readBytes = input.read(buffer)

                        if (readBytes < 0) {
                            break
                        }

                        totalBytes += readBytes

                        if (
                            totalBytes >
                            maxAttachmentSizeBytes
                        ) {
                            throw AttachmentTooLargeException()
                        }

                        output.write(
                            buffer,
                            0,
                            readBytes,
                        )
                    }
                }
            }
        } catch (_: AttachmentTooLargeException) {
            Files.deleteIfExists(tempFile)

            skippedAttachments += SkippedAttachment(
                fileName = fileName,
                reason =
                    SKIP_TOO_LARGE,
            )

            return
        } catch (exception: Exception) {
            Files.deleteIfExists(tempFile)
            throw exception
        }

        val contentType = part.contentType
            .substringBefore(';')
            .trim()
            .ifBlank {
                "application/octet-stream"
            }

        attachments += EmailAttachment(
            fileName = fileName,
            contentType = contentType,
            sizeBytes = totalBytes,
            tempFile = tempFile,
        )
    }

    private fun isAttachment(
        part: Part,
    ): Boolean {
        val disposition = part.disposition

        return disposition.equals(
            Part.ATTACHMENT,
            ignoreCase = true,
        ) || (
                !part.fileName.isNullOrBlank() &&
                        !part.isMimeType("multipart/*")
                )
    }

    private fun extractFileName(
        part: Part,
        attachmentNumber: Int,
    ): String {
        val rawName = part.fileName
            ?.takeIf { fileName ->
                fileName.isNotBlank()
            }

        val decodedName = rawName?.let { name ->
            runCatching {
                MimeUtility.decodeText(name)
            }.getOrDefault(name)
        }

        val baseName = decodedName
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.trim()
            ?.takeIf { fileName ->
                fileName.isNotBlank()
            }
            ?: "attachment-$attachmentNumber"

        val sanitizedName = baseName
            .replace(
                Regex("""[\\/:*?"<>|\p{Cc}]"""),
                "_",
            )
            .trim()
            .trimEnd('.')
            .take(150)

        return sanitizedName.ifBlank {
            "attachment-$attachmentNumber"
        }
    }

    // Текст из HTML так, как его показал бы браузер: пробелы и переносы из исходника
    // схлопываются, новая строка - только на <br> и границах блоков (абзацы, строки таблиц)
    private fun convertHtmlToText(
        html: String,
    ): String {
        val document = Jsoup.parse(html)
        document.select("script, style, head, title").remove()

        val text = StringBuilder()
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when {
                        node is TextNode -> text.append(node.text())
                        node is Element && node.normalName() == "br" -> text.append('\n')
                        node is Element && node.normalName() in BLOCK_TAGS -> text.append('\n')
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node !is Element) return
                    when (node.normalName()) {
                        in BLOCK_TAGS -> text.append('\n')
                        // Ячейки одной строки таблицы - через пробел, а не слитно
                        "td", "th" -> text.append(' ')
                    }
                }
            },
            document.body(),
        )
        return text.toString()
    }

    private fun normalize(
        text: String,
    ): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\t', ' ')
            // Невидимые символы рассылок (zero-width, мягкий перенос): из них состоят «пустые» строки
            .replace(INVISIBLE, "")
            .filter { character ->
                character == '\n' ||
                        !character.isISOControl()
            }
            .lines()
            .joinToString("\n") { line ->
                // Отступы из верстки рассылок не нужны в Telegram: убираем их и двойные пробелы
                line.replace(SPACES, " ").trim()
            }
            .replace(
                regex = Regex("\n{3,}"),
                replacement = "\n\n",
            )
            .trim()
    }

    // Некоторые почты (Mail.ru) кладут в текстовую версию письма ссылки с HTML-экранированием:
    // ...?a=1&amp;b=2. По такой ссылке параметры теряются, поэтому возвращаем обычный &
    internal fun unescapeLinks(
        text: String,
    ): String =
        LINK_PATTERN.replace(text) { match ->
            match.value
                .replace("&amp;", "&")
                .replace("%26amp%3B", "%26", ignoreCase = true)
        }

    internal fun cleanText(text: String): String = unescapeLinks(normalize(text))

    private val LINK_PATTERN = Regex("https?://[^\\s<>\"]+")
    private val INVISIBLE = Regex("[\u00AD\u034F\u180E\u200B\u200C\u200E\u200F\u2060-\u2064\uFEFF]")
    private val SPACES = Regex("[ \t\u00A0\u2007\u202F]+")
    private val BLOCK_TAGS = setOf(
        "p", "div", "li", "tr", "table", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "section", "article", "header", "footer", "hr", "center",
    )

    private class AttachmentTooLargeException :
        RuntimeException()
}