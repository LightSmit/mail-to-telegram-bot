package io.github.lightsmit.mail

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimeUtility
import org.jsoup.Jsoup
import java.nio.file.Files
import java.nio.file.Path

object EmailContentExtractor {

    fun extract(
        part: Part,
        tempDirectory: Path,
        maxAttachmentSizeBytes: Long,
    ): EmailContent {
        require(maxAttachmentSizeBytes > 0) {
            "Maximum attachment size must be greater than zero"
        }

        Files.createDirectories(tempDirectory)

        val plainTextBodies = mutableListOf<String>()
        val htmlBodies = mutableListOf<String>()
        val attachments = mutableListOf<EmailAttachment>()
        val skippedAttachments = mutableListOf<SkippedAttachment>()

        try {
            collectParts(
                part = part,
                tempDirectory = tempDirectory,
                maxAttachmentSizeBytes = maxAttachmentSizeBytes,
                plainTextBodies = plainTextBodies,
                htmlBodies = htmlBodies,
                attachments = attachments,
                skippedAttachments = skippedAttachments,
            )
        } catch (exception: Exception) {
            attachments.forEach { attachment ->
                runCatching {
                    Files.deleteIfExists(attachment.tempFile)
                }
            }

            throw exception
        }

        val body = plainTextBodies
            .firstOrNull(String::isNotBlank)
            ?: htmlBodies.firstOrNull(String::isNotBlank)

        return EmailContent(
            body = body
                ?.let(::normalize)
                ?.takeIf(String::isNotBlank),

            attachments = attachments,
            skippedAttachments = skippedAttachments,
        )
    }

    private fun collectParts(
        part: Part,
        tempDirectory: Path,
        maxAttachmentSizeBytes: Long,
        plainTextBodies: MutableList<String>,
        htmlBodies: MutableList<String>,
        attachments: MutableList<EmailAttachment>,
        skippedAttachments: MutableList<SkippedAttachment>,
    ) {
        if (isAttachment(part)) {
            saveAttachment(
                part = part,
                tempDirectory = tempDirectory,
                maxAttachmentSizeBytes = maxAttachmentSizeBytes,
                attachments = attachments,
                skippedAttachments = skippedAttachments,
            )

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
                        tempDirectory = tempDirectory,
                        maxAttachmentSizeBytes = maxAttachmentSizeBytes,
                        plainTextBodies = plainTextBodies,
                        htmlBodies = htmlBodies,
                        attachments = attachments,
                        skippedAttachments = skippedAttachments,
                    )
                }
            }

            part.isMimeType("message/rfc822") -> {
                val nestedPart = part.content as? Part
                    ?: return

                collectParts(
                    part = nestedPart,
                    tempDirectory = tempDirectory,
                    maxAttachmentSizeBytes = maxAttachmentSizeBytes,
                    plainTextBodies = plainTextBodies,
                    htmlBodies = htmlBodies,
                    attachments = attachments,
                    skippedAttachments = skippedAttachments,
                )
            }
        }
    }

    private fun saveAttachment(
        part: Part,
        tempDirectory: Path,
        maxAttachmentSizeBytes: Long,
        attachments: MutableList<EmailAttachment>,
        skippedAttachments: MutableList<SkippedAttachment>,
    ) {
        val attachmentNumber =
            attachments.size + skippedAttachments.size + 1

        val fileName = extractFileName(
            part = part,
            attachmentNumber = attachmentNumber,
        )

        val declaredSize = part.size.toLong()

        if (declaredSize > maxAttachmentSizeBytes) {
            skippedAttachments += SkippedAttachment(
                fileName = fileName,
                reason = "размер превышает установленный предел",
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
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                    while (true) {
                        val readBytes = input.read(buffer)

                        if (readBytes < 0) {
                            break
                        }

                        totalBytes += readBytes

                        if (totalBytes > maxAttachmentSizeBytes) {
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
                reason = "размер превышает установленный предел",
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

    private fun isAttachment(part: Part): Boolean {
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
            ?.takeIf(String::isNotBlank)

        val decodedName = rawName?.let { name ->
            runCatching {
                MimeUtility.decodeText(name)
            }.getOrDefault(name)
        }

        val baseName = decodedName
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.trim()
            ?.takeIf(String::isNotBlank)
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

    private fun convertHtmlToText(html: String): String {
        val document = Jsoup.parse(html)

        document.select("br").after("\n")

        document
            .select(
                "p, div, li, tr, h1, h2, h3, h4, h5, h6, blockquote",
            )
            .before("\n")

        return document
            .body()
            .wholeText()
    }

    private fun normalize(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { character ->
                character == '\n' ||
                        character == '\t' ||
                        !character.isISOControl()
            }
            .lines()
            .joinToString("\n") { line ->
                line.trimEnd()
            }
            .replace(
                regex = Regex("\n{3,}"),
                replacement = "\n\n",
            )
            .trim()
    }

    private class AttachmentTooLargeException : RuntimeException()
}