package io.github.lightsmit.security

import io.github.lightsmit.config.Environment
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {
    private const val ALGORITHM = "AES"

    private val secretKey by lazy {
        val keyStr = Environment.get("ENCRYPTION_KEY") ?: "DefaultSecureKey"
        // Берем ровно 16 байт для AES-128
        val keyBytes = keyStr.toByteArray().copyOf(16)
        SecretKeySpec(keyBytes, ALGORITHM)
    }

    fun encrypt(data: String): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray()))
    }

    fun decrypt(encryptedData: String): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        return String(cipher.doFinal(Base64.getDecoder().decode(encryptedData)))
    }
}