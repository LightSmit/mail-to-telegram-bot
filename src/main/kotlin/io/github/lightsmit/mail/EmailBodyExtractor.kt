package io.github.lightsmit.mail

import jakarta.mail.Multipart
import jakarta.mail.Part
import org.jsoup.Jsoup

object EmailBodyExtractor {

    fun extract(part: Part): String? {
        val plainTextBodies = mutableListOf<String>()
        val htmlBodies = mutableListOf<String>()

        collectBodies(
            part = part,
            plainTextBodies = plainTextBodies,
            htmlBodies = htmlBodies,
        )

        val body = plainTextBodies
            .firstOrNull(String::isNotBlank)
            ?: htmlBodies.firstOrNull(String::isNotBlank)

        return body
            ?.let(::normalize)
            ?.takeIf(String::isNotBlank)
    }

    private fun collectBodies(
        part: Part,
        plainTextBodies: MutableList<String>,
        htmlBodies: MutableList<String>,
    ) {
        if (isAttachment(part)) {
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
                    collectBodies(
                        part = multipart.getBodyPart(index),
                        plainTextBodies = plainTextBodies,
                        htmlBodies = htmlBodies,
                    )
                }
            }

            part.isMimeType("message/rfc822") -> {
                val nestedPart = part.content as? Part
                    ?: return

                collectBodies(
                    part = nestedPart,
                    plainTextBodies = plainTextBodies,
                    htmlBodies = htmlBodies,
                )
            }
        }
    }

    private fun isAttachment(part: Part): Boolean {
        val disposition = part.disposition

        return disposition.equals(
            Part.ATTACHMENT,
            ignoreCase = true,
        ) || !part.fileName.isNullOrBlank()
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
}