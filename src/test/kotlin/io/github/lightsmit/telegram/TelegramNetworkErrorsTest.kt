package io.github.lightsmit.telegram

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramNetworkErrorsTest {
    @Test
    fun `failed connection means the request was not sent`() {
        assertTrue(TelegramNetworkErrors.definitelyNotSent(ConnectException("refused")))
        assertTrue(TelegramNetworkErrors.definitelyNotSent(UnknownHostException("api.telegram.org")))
        assertTrue(TelegramNetworkErrors.definitelyNotSent(IOException("wrapped", ConnectException("refused"))))
    }

    @Test
    fun `timeout or broken response may mean the message was delivered`() {
        assertFalse(TelegramNetworkErrors.definitelyNotSent(SocketTimeoutException("read timed out")))
        assertFalse(TelegramNetworkErrors.definitelyNotSent(IOException("unexpected end of stream")))
    }
}
