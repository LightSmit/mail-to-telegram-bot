package io.github.lightsmit.config

import java.nio.file.Files
import java.nio.file.Path

object Environment {

    private val fileValues: Map<String, String> by lazy {
        loadDotEnv()
    }

    fun get(name: String): String? {
        val systemValue = System.getenv(name)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (systemValue != null) {
            return systemValue
        }

        return fileValues[name]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun require(name: String): String {
        return get(name)
            ?: error("Missing required configuration value: $name")
    }

    private fun loadDotEnv(path: Path = Path.of(".env")): Map<String, String> {
        if (!Files.exists(path)) {
            return emptyMap()
        }

        return Files.readAllLines(path)
            .mapNotNull { rawLine ->
                val line = rawLine.trim()

                if (line.isEmpty() || line.startsWith("#")) {
                    return@mapNotNull null
                }

                val separatorIndex = line.indexOf('=')

                if (separatorIndex <= 0) {
                    return@mapNotNull null
                }

                val key = line
                    .substring(0, separatorIndex)
                    .trim()

                val value = line
                    .substring(separatorIndex + 1)
                    .trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")

                key to value
            }
            .toMap()
    }
}