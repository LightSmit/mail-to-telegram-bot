package io.github.lightsmit.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AesGcmCryptoTest {

    private val key = "k".repeat(40)

    @Test
    fun `encrypt then decrypt returns original`() {
        val crypto = AesGcmCrypto(key)
        val encrypted = crypto.encrypt("p@ssw0rd пароль")
        assertTrue(encrypted.startsWith("v2:"))
        assertEquals("p@ssw0rd пароль", crypto.decrypt(encrypted))
    }

    @Test
    fun `same plaintext gives different ciphertext`() {
        val crypto = AesGcmCrypto(key)
        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val crypto = AesGcmCrypto(key)
        val raw = Base64.getDecoder().decode(crypto.encrypt("secret").removePrefix("v2:"))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        val tampered = "v2:" + Base64.getEncoder().encodeToString(raw)
        assertFailsWith<Exception> { crypto.decrypt(tampered) }
    }

    @Test
    fun `wrong key is rejected`() {
        val encrypted = AesGcmCrypto(key).encrypt("secret")
        assertFailsWith<Exception> { AesGcmCrypto("x".repeat(40)).decrypt(encrypted) }
    }

    @Test
    fun `short key is rejected`() {
        assertFailsWith<IllegalArgumentException> { AesGcmCrypto("short") }
    }

    @Test
    fun `legacy ECB value is still readable`() {
        val legacyKey = "DefaultSecureKey"
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(legacyKey.toByteArray().copyOf(16), "AES"))
        val legacyValue = Base64.getEncoder().encodeToString(cipher.doFinal("old-password".toByteArray()))

        val crypto = AesGcmCrypto(key, legacyKeys = listOf(legacyKey))
        assertEquals("old-password", crypto.decrypt(legacyValue))
        assertTrue(!crypto.isCurrentFormat(legacyValue))
    }
}
