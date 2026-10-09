package app.stillroom

import app.stillroom.data.TlsFailures
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsFailureTest {
    @Test fun specificCausesComeFromTheExceptionType() {
        assertEquals(TlsFailures.EXPIRED, TlsFailures.message(SSLHandshakeException("handshake").apply {
            initCause(CertPathValidatorException("expired", CertificateExpiredException(), null, -1, CertPathValidatorException.BasicReason.EXPIRED))
        }))
        assertEquals(TlsFailures.NOT_YET_VALID, TlsFailures.message(CertificateNotYetValidException("not yet")))
        assertEquals(TlsFailures.HOSTNAME, TlsFailures.message(SSLPeerUnverifiedException("Hostname example.test not verified")))
        assertEquals(TlsFailures.UNTRUSTED, TlsFailures.message(CertPathValidatorException("Trust anchor for certification path not found.")))
        assertEquals(TlsFailures.GENERIC, TlsFailures.message(SSLHandshakeException("handshake failed")))
    }

    @Test fun wordingDoesNotCopyTheExceptionText() {
        val secret = "synthetic-test-credential"
        val error = SSLPeerUnverifiedException("https://grocy.example/api?key=$secret")
        val message = TlsFailures.message(error)
        assertEquals(TlsFailures.HOSTNAME, message)
        assertFalse(message.contains(secret))
        assertFalse(message.contains("https://"))
        assertTrue(TlsFailures.messages.contains(message))
    }
}
