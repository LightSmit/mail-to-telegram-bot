package io.github.lightsmit.security

import io.github.lightsmit.config.Environment

/**
 * Единая точка доступа к шифрованию. Ключ обязателен: без ENCRYPTION_KEY
 * приложение не стартует (вместо молчаливого ключа по умолчанию).
 */
object CryptoHelper {
    private val crypto: AesGcmCrypto by lazy {
        val key = Environment.get("ENCRYPTION_KEY")
            ?: error(
                "Missing required configuration value: ENCRYPTION_KEY " +
                    "(at least ${AesGcmCrypto.MIN_KEY_LENGTH} characters; generate with: openssl rand -base64 48)",
            )

        // Старые записи могли быть зашифрованы прежним ключом или ключом по умолчанию.
        val legacyKeys = listOfNotNull(
            Environment.get("ENCRYPTION_KEY_LEGACY"),
            key,
            "DefaultSecureKey",
        ).distinct()

        AesGcmCrypto(masterKey = key, legacyKeys = legacyKeys)
    }

    /** Вызвать при старте приложения, чтобы упасть сразу, а не при первом шифровании. */
    fun requireConfigured() {
        crypto
    }

    fun encrypt(data: String): String = crypto.encrypt(data)

    fun decrypt(encryptedData: String): String = crypto.decrypt(encryptedData)

    fun isCurrentFormat(stored: String): Boolean = crypto.isCurrentFormat(stored)
}
