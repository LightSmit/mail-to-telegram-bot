package io.github.lightsmit.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование секретов (пароли почтовых ящиков) в базе.
 *
 * Формат нового значения: "v2:" + Base64(nonce[12] || ciphertext || gcmTag[16]).
 * AES-256-GCM, случайный nonce на каждое шифрование, ключ = SHA-256(masterKey).
 *
 * Значения без префикса "v2:" считаются старым форматом (AES/ECB, ключ = первые
 * 16 байт строки). Они только читаются; при следующем сохранении запишутся уже в v2.
 */
class AesGcmCrypto(
    masterKey: String,
    private val legacyKeys: List<String> = emptyList(),
) {
    init {
        require(masterKey.length >= MIN_KEY_LENGTH) {
            "ENCRYPTION_KEY must be at least $MIN_KEY_LENGTH characters long"
        }
    }

    private val secretKey = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(masterKey.toByteArray(Charsets.UTF_8)),
        "AES",
    )

    private val random = SecureRandom()

    fun encrypt(plainText: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(nonce + encrypted)
    }

    fun decrypt(stored: String): String {
        return if (stored.startsWith(PREFIX)) {
            decryptV2(stored.removePrefix(PREFIX))
        } else {
            decryptLegacy(stored)
        }
    }

    fun isCurrentFormat(stored: String): Boolean = stored.startsWith(PREFIX)

    private fun decryptV2(payload: String): String {
        val raw = Base64.getDecoder().decode(payload)
        require(raw.size > NONCE_BYTES) { "Encrypted value is too short" }
        val nonce = raw.copyOfRange(0, NONCE_BYTES)
        val cipherText = raw.copyOfRange(NONCE_BYTES, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun decryptLegacy(stored: String): String {
        val raw = Base64.getDecoder().decode(stored)
        var lastError: Exception? = null
        for (legacyKey in legacyKeys) {
            try {
                val key = SecretKeySpec(legacyKey.toByteArray().copyOf(16), "AES")
                val cipher = Cipher.getInstance("AES")
                cipher.init(Cipher.DECRYPT_MODE, key)
                return String(cipher.doFinal(raw))
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            "Cannot decrypt legacy value; set ENCRYPTION_KEY_LEGACY to the previous key",
            lastError,
        )
    }

    companion object {
        const val PREFIX = "v2:"
        const val MIN_KEY_LENGTH = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }
}
