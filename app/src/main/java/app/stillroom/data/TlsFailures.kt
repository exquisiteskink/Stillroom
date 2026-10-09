package app.stillroom.data

import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Wording for a failed certificate check. The text never includes the URL, the API key,
 * a response body, or the exception's own message.
 */
internal object TlsFailures {
    const val GENERIC = "TLS verification failed. Check the server certificate."
    const val EXPIRED = "The server certificate has expired."
    const val NOT_YET_VALID = "The server certificate is not valid yet."
    const val HOSTNAME = "The server certificate does not match this hostname."
    const val UNTRUSTED = "The server certificate is not trusted. Install your CA's root in Android's CA certificate settings, and make sure the server sends its intermediate certificates."

    val messages = setOf(GENERIC, EXPIRED, NOT_YET_VALID, HOSTNAME, UNTRUSTED)

    fun isTls(error: Throwable): Boolean = causes(error).any {
        it is SSLException || it is CertificateException || it is CertPathValidatorException
    }

    /** A specific cause is named only when a certificate exception actually says so. */
    fun message(error: Throwable): String {
        val chain = causes(error)
        val path = chain.filterIsInstance<CertPathValidatorException>()
        val expired = chain.any { it is CertificateExpiredException } ||
            path.any { it.reason == CertPathValidatorException.BasicReason.EXPIRED }
        val notYet = chain.any { it is CertificateNotYetValidException } ||
            path.any { it.reason == CertPathValidatorException.BasicReason.NOT_YET_VALID }
        return when {
            expired -> EXPIRED
            notYet -> NOT_YET_VALID
            chain.any { it is SSLPeerUnverifiedException } -> HOSTNAME
            path.isNotEmpty() -> UNTRUSTED
            else -> GENERIC
        }
    }

    fun wrap(error: Throwable, otherwise: String): IllegalStateException =
        if (isTls(error)) IllegalStateException(message(error), error) else IllegalStateException(otherwise)

    private fun causes(error: Throwable): List<Throwable> {
        val found = ArrayList<Throwable>(8)
        var current: Throwable? = error
        while (current != null && found.size < 8 && current !in found) {
            found += current
            current = current.cause
        }
        return found
    }
}
