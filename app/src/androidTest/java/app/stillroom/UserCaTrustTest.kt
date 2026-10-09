package app.stillroom

import android.security.NetworkSecurityPolicy
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.stillroom.data.HttpClients
import app.stillroom.data.MutationTransport
import app.stillroom.data.OpenFoodFactsLookup
import app.stillroom.data.RecipeFiles
import app.stillroom.data.TlsFailures
import app.stillroom.data.UrlConnectionGrocyTransport
import app.stillroom.domain.ServerAddress
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

/**
 * Platform trust on a device. JVM tests cannot show that Android's user CA store is consulted.
 * Synthetic keys stay in the test process. Nothing here is a household certificate.
 */
class UserCaTrustTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val servers = mutableListOf<AutoCloseable>()
    private val credential = "synthetic-test-credential"

    @After fun closeServers() {
        servers.forEach { runCatching { it.close() } }
        if (InstrumentationRegistry.getArguments().getString("keepSyntheticCa") != "true") {
            File(context().noBackupFilesDir, CHAIN_FILE).delete()
            File(context().noBackupFilesDir, ROOT_FILE).delete()
        }
    }

    @Test fun releaseConfigTrustsSystemAndUserCasAndStillAllowsExplicitHttp() {
        val parser = context().resources.getXml(R.xml.network_security_config)
        val sources = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "certificates") {
                val src = parser.getAttributeValue(null, "src")
                    ?: (0 until parser.attributeCount).firstNotNullOfOrNull { index ->
                        parser.getAttributeValue(index)?.takeIf { it == "system" || it == "user" }
                    }
                if (src != null) sources += src
            }
            event = parser.next()
        }
        parser.close()
        assertTrue(sources.contains("system"))
        assertTrue(sources.contains("user"))
        // Scanner lookups and background sync use this same client. Login uses platform HttpsURLConnection.
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        assertFalse(HttpClients.base.followRedirects)
        assertFalse(HttpClients.base.followSslRedirects)
        assertFalse(HttpClients.base.retryOnConnectionFailure)
        assertFalse(OpenFoodFactsLookup().client.followRedirects)
        assertEquals(HttpClients.base.connectionPool, OpenFoodFactsLookup().client.connectionPool)
        assertEquals(HttpClients.base.connectionPool, MutationTransport().client.connectionPool)
        assertEquals(HttpClients.base.connectionPool, RecipeFiles(ServerAddress.parse("https://example.test"), credential).client.connectionPool)
    }

    @Test fun privateChainFailsUntilItsRootIsInstalled() = runBlocking {
        val chain = Chain.create(validHost = true)
        val bound = serve(chain.leafKey, arrayOf(chain.leafCert, chain.intermediateCert))
        val address = ServerAddress.parse("https://127.0.0.1:${bound.port}")
        assumeTrue("A user CA for this ephemeral root is already present, so absence cannot be shown.", !userStoreContains(chain.rootCert))
        assertRejected(TlsFailures.UNTRUSTED) { UrlConnectionGrocyTransport().get(address, credential, "/system/info") }
        assertRejected(TlsFailures.UNTRUSTED) { MutationTransport().request(address, credential, "GET", "/system/info") }
        assertRejected(TlsFailures.UNTRUSTED) { MutationTransport().request(address, credential, "POST", "/objects/products", "{}") }
        assertRejected(TlsFailures.UNTRUSTED) { RecipeFiles(address, credential).request("chili.jpg", null) }
        assertRejected(TlsFailures.UNTRUSTED) { RecipeFiles(address, credential).request("chili.jpg", byteArrayOf(1, 2, 3)) }
    }

    @Test fun wrongHostExpiredUnrelatedAndIncompleteChainsFail() = runBlocking {
        val saved = File(context().noBackupFilesDir, CHAIN_FILE)
        val chain = if (saved.exists()) Chain.load(saved) else Chain.create(validHost = true)
        val trusted = userStoreContains(chain.rootCert)
        val wrong = serve(chain.leafKey, arrayOf(chain.wrongHostCert, chain.intermediateCert))
        val expired = serve(chain.leafKey, arrayOf(chain.expiredCert, chain.intermediateCert))
        val unrelated = serve(chain.otherKey, arrayOf(chain.otherCert, chain.otherIntermediateCert))
        // A leaf whose intermediate is never sent. Conscrypt caches intermediates from earlier handshakes.
        val incomplete = serve(chain.withheldLeafKey, arrayOf(chain.withheldLeafCert))
        assertRejected(if (trusted) TlsFailures.HOSTNAME else null) {
            UrlConnectionGrocyTransport().get(ServerAddress.parse("https://127.0.0.1:${wrong.port}"), credential, "/system/info")
        }
        assertRejected(if (trusted) TlsFailures.EXPIRED else null) {
            MutationTransport().request(ServerAddress.parse("https://127.0.0.1:${expired.port}"), credential, "GET", "/system/info")
        }
        assertRejected("unrelated", TlsFailures.UNTRUSTED) {
            UrlConnectionGrocyTransport().get(ServerAddress.parse("https://127.0.0.1:${unrelated.port}"), credential, "/system/info")
        }
        assertRejected("incomplete", TlsFailures.UNTRUSTED) {
            RecipeFiles(ServerAddress.parse("https://127.0.0.1:${incomplete.port}"), credential).request("chili.jpg", null)
        }
    }

    @Test fun installedUserRootTrustsItsChainAndDoesNotTrustAnother() = runBlocking {
        val chain = savedOrFresh()
        val installed = userStoreContains(chain.rootCert)
        assumeTrue(
            "Synthetic root is not in Android's user CA store, so the success check did not run. " +
                "Install ${File(context().noBackupFilesDir, ROOT_FILE).absolutePath} as a user CA and rerun with keepSyntheticCa=true.",
            installed,
        )
        val bound = serve(chain.leafKey, arrayOf(chain.leafCert, chain.intermediateCert))
        val address = ServerAddress.parse("https://127.0.0.1:${bound.port}")
        val login = UrlConnectionGrocyTransport().get(address, credential, "/system/info")
        assertEquals("{}", login)
        val read = MutationTransport().request(address, credential, "GET", "/system/info")
        assertEquals(200, read.first)
        val write = MutationTransport().request(address, credential, "PUT", "/objects/products/1", """{"name":"synthetic"}""")
        assertEquals(200, write.first)
        val downloaded = RecipeFiles(address, credential).request("chili.jpg", null)
        assertTrue(downloaded.isNotEmpty())
        val uploaded = RecipeFiles(address, credential).request("chili.jpg", byteArrayOf(9, 9))
        assertTrue(uploaded.isNotEmpty())
        val other = serve(chain.otherKey, arrayOf(chain.otherCert, chain.otherIntermediateCert))
        assertRejected(TlsFailures.UNTRUSTED) {
            MutationTransport().request(ServerAddress.parse("https://127.0.0.1:${other.port}"), credential, "GET", "/system/info")
        }
    }

    @Test fun tlsFailureDoesNotFallBackToHttpAndHttpStillRequiresOptIn() = runBlocking {
        val chain = Chain.create(validHost = true)
        val https = serve(chain.leafKey, arrayOf(chain.leafCert, chain.intermediateCert))
        val hits = AtomicInteger()
        val http = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        servers += http
        Thread {
            while (!http.isClosed) {
                try { http.accept().use { hits.incrementAndGet() } } catch (_: Exception) { break }
            }
        }.apply { isDaemon = true; start() }
        assertRejected(null) { UrlConnectionGrocyTransport().get(ServerAddress.parse("https://127.0.0.1:${https.port}"), credential, "/system/info") }
        Thread.sleep(300)
        assertTrue(https.attempts.get() >= 1)
        assertEquals(0, hits.get())
        val plain = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        servers += plain
        Thread {
            try {
                plain.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                    socket.getOutputStream().flush()
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val denied = runCatching { ServerAddress.parse("http://127.0.0.1:${plain.localPort}") }
        assertTrue(denied.exceptionOrNull()?.message?.contains("HTTPS is required") == true)
        val body = UrlConnectionGrocyTransport().get(ServerAddress.parse("http://127.0.0.1:${plain.localPort}", true), credential, "/system/info")
        assertEquals("{}", body)
    }

    @Test fun okHttpRedirectDoesNotForwardTheApiKey() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { source ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { destination ->
                destination.soTimeout = 400
                val serving = Thread {
                    source.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${destination.localPort}/api/system/info\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }.apply { isDaemon = true; start() }
                val result = MutationTransport().request(ServerAddress.parse("http://127.0.0.1:${source.localPort}", true), credential, "GET", "/system/info")
                assertEquals(302, result.first)
                assertFalse(result.second.contains(credential))
                serving.join(3000)
                val forwarded = runCatching { destination.accept().close() }
                assertTrue(forwarded.isFailure)
            }
        }
    }

    @Test fun publicHttpsStillUsesSystemTrust() {
        try {
            val connection = URL("https://example.com/").openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "text/html")
            val status = connection.responseCode
            connection.disconnect()
            assertTrue(status in 200..399)
            val response = HttpClients.base.newCall(Request.Builder().url("https://example.com/").header("Accept", "text/html").build()).execute()
            response.use { assertTrue(it.code in 200..399) }
        } catch (error: Exception) {
            assumeNoException("Public HTTPS needs a network path to example.com", error)
        }
    }

    @Test fun connectionFormExplainsPrivateCaTrust() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            val accounts = compose.onAllNodesWithContentDescription("Open accounts")
            if (accounts.fetchSemanticsNodes().isNotEmpty()) accounts[0].performClick()
            else compose.onNodeWithText("Connect").performClick()
            compose.waitForIdle()
            val add = compose.onAllNodesWithText("Add account")
            if (add.fetchSemanticsNodes().isNotEmpty()) add[0].performClick()
            compose.onNodeWithTag("private-ca-help").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("private-ca-help").assertTextContains("not one Grocy server", substring = true)
            compose.onNodeWithTag("private-ca-help").assertTextContains("intermediate", substring = true)
            compose.onNodeWithTag("private-ca-help").assertTextContains("hostname must match", substring = true)
            compose.onNodeWithTag("private-ca-help").assertTextContains("does not switch the connection to HTTP", substring = true)
        }
    }

    private fun savedOrFresh(): Chain {
        val file = File(context().noBackupFilesDir, CHAIN_FILE)
        if (file.exists()) return Chain.load(file)
        val chain = Chain.create(validHost = true)
        if (InstrumentationRegistry.getArguments().getString("keepSyntheticCa") == "true") {
            chain.save(file)
            File(context().noBackupFilesDir, ROOT_FILE).writeText(chain.rootPem())
        }
        return chain
    }

    private suspend fun assertRejected(expected: String?, call: suspend () -> Any) = assertRejected("", expected, call)

    private suspend fun assertRejected(label: String, expected: String?, call: suspend () -> Any) {
        val error = try {
            call()
            error("TLS connection succeeded")
        } catch (caught: Throwable) { caught }
        val text = error.message.orEmpty()
        assertFalse(text.contains(credential))
        assertFalse(text.contains("http://"))
        assertFalse(text.contains("https://"))
        if (expected == null) assertTrue("$label $text", text in TlsFailures.messages) else assertEquals(label, expected, text)
        assertNotEquals("{}", text)
    }

    private class Bound(val port: Int, val attempts: AtomicInteger)

    /** Handshake failures still count. A client retry would increment again. */
    private fun serve(key: PrivateKey, chain: Array<X509Certificate>): Bound {
        val password = "synthetic".toCharArray()
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("server", key, password, chain)
        val factories = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factories.init(store, password)
        val context = SSLContext.getInstance("TLS")
        context.init(factories.keyManagers, null, SecureRandom())
        val server = context.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        servers += server
        val attempts = AtomicInteger()
        Thread {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    if (server.isClosed) break
                    attempts.incrementAndGet()
                    continue
                }
                attempts.incrementAndGet()
                try {
                    socket.use {
                        it.soTimeout = 4_000
                        val request = ByteArray(4096)
                        val count = it.getInputStream().read(request)
                        if (count > 0) check(!request.decodeToString(0, count).contains("synthetic-test-credential-leaked"))
                        val body = "{}"
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        it.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                    if (server.isClosed) break
                }
            }
        }.apply { isDaemon = true; name = "synthetic-tls"; start() }
        return Bound(server.localPort, attempts)
    }

    private fun userStoreContains(cert: X509Certificate): Boolean {
        val store = KeyStore.getInstance("AndroidCAStore")
        store.load(null, null)
        val aliases = store.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (!alias.startsWith("user:")) continue
            val stored = store.getCertificate(alias) as? X509Certificate ?: continue
            if (stored.publicKey.encoded.contentEquals(cert.publicKey.encoded)) return true
        }
        return false
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Chain(
        val rootCert: X509Certificate,
        val intermediateCert: X509Certificate,
        val leaf: KeyPair,
        val leafKey: PrivateKey,
        val leafCert: X509Certificate,
        val withheldLeafKey: PrivateKey,
        val withheldLeafCert: X509Certificate,
        val expiredCert: X509Certificate,
        val wrongHostCert: X509Certificate,
        val other: KeyPair,
        val otherKey: PrivateKey,
        val otherCert: X509Certificate,
        val otherIntermediateCert: X509Certificate,
    ) {
        fun rootPem(): String {
            val wrapped = android.util.Base64.encodeToString(rootCert.encoded, android.util.Base64.DEFAULT)
            return "-----BEGIN CERTIFICATE-----\n$wrapped-----END CERTIFICATE-----\n"
        }

        fun save(file: File) {
            val password = "synthetic".toCharArray()
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, null)
            store.setKeyEntry("leaf", leafKey, password, arrayOf(leafCert, intermediateCert, rootCert))
            store.setKeyEntry("withheld", withheldLeafKey, password, arrayOf(withheldLeafCert))
            store.setCertificateEntry("root", rootCert)
            store.setCertificateEntry("intermediate", intermediateCert)
            store.setCertificateEntry("expired", expiredCert)
            store.setCertificateEntry("wrong", wrongHostCert)
            store.setKeyEntry("other", otherKey, password, arrayOf(otherCert, otherIntermediateCert))
            store.setCertificateEntry("other-intermediate", otherIntermediateCert)
            file.outputStream().use { store.store(it, password) }
        }

        companion object {
            fun load(file: File): Chain {
                val password = "synthetic".toCharArray()
                val store = KeyStore.getInstance("PKCS12")
                file.inputStream().use { store.load(it, password) }
                val leafKey = store.getKey("leaf", password) as PrivateKey
                val leafCert = store.getCertificate("leaf") as X509Certificate
                val withheldKey = store.getKey("withheld", password) as PrivateKey
                val withheldCert = store.getCertificate("withheld") as X509Certificate
                return Chain(
                    rootCert = store.getCertificate("root") as X509Certificate,
                    intermediateCert = store.getCertificate("intermediate") as X509Certificate,
                    leaf = KeyPair(leafCert.publicKey, leafKey),
                    leafKey = leafKey,
                    leafCert = leafCert,
                    withheldLeafKey = withheldKey,
                    withheldLeafCert = withheldCert,
                    expiredCert = store.getCertificate("expired") as X509Certificate,
                    wrongHostCert = store.getCertificate("wrong") as X509Certificate,
                    other = KeyPair((store.getCertificate("other") as X509Certificate).publicKey, store.getKey("other", password) as PrivateKey),
                    otherKey = store.getKey("other", password) as PrivateKey,
                    otherCert = store.getCertificate("other") as X509Certificate,
                    otherIntermediateCert = store.getCertificate("other-intermediate") as X509Certificate,
                )
            }

            fun create(validHost: Boolean): Chain {
                val now = System.currentTimeMillis()
                val before = Date(now - 86_400_000)
                val after = Date(now + 30L * 86_400_000)
                val root = keys()
                val rootCert = sign("Stillroom synthetic test root", root, null, null, before, after, ca = true, pathLen = 1, ip = null, dns = null)
                val intermediate = keys()
                val intermediateCert = sign("Stillroom synthetic test intermediate", intermediate, rootCert, root.private, before, after, ca = true, pathLen = 0, ip = null, dns = null)
                val leaf = keys()
                val leafCert = sign("127.0.0.1", leaf, intermediateCert, intermediate.private, before, after, ca = false, pathLen = null, ip = "127.0.0.1", dns = null)
                val withheldIntermediate = keys()
                val withheldIntermediateCert = sign("Stillroom withheld intermediate", withheldIntermediate, rootCert, root.private, before, after, ca = true, pathLen = 0, ip = null, dns = null)
                val withheldLeaf = keys()
                val withheldLeafCert = sign("127.0.0.1", withheldLeaf, withheldIntermediateCert, withheldIntermediate.private, before, after, ca = false, pathLen = null, ip = "127.0.0.1", dns = null)
                val expired = sign("127.0.0.1", leaf, intermediateCert, intermediate.private, Date(1_577_836_800_000), Date(1_577_923_200_000), ca = false, pathLen = null, ip = "127.0.0.1", dns = null)
                val wrong = sign("grocy.invalid", leaf, intermediateCert, intermediate.private, before, after, ca = false, pathLen = null, ip = null, dns = "grocy.invalid")
                val otherRoot = keys()
                val otherRootCert = sign("Stillroom unrelated test root", otherRoot, null, null, before, after, ca = true, pathLen = 1, ip = null, dns = null)
                val otherIntermediate = keys()
                val otherIntermediateCert = sign("Stillroom unrelated test intermediate", otherIntermediate, otherRootCert, otherRoot.private, before, after, ca = true, pathLen = 0, ip = null, dns = null)
                val other = keys()
                val otherCert = sign("127.0.0.1", other, otherIntermediateCert, otherIntermediate.private, before, after, ca = false, pathLen = null, ip = "127.0.0.1", dns = null)
                check(validHost)
                leafCert.verify(intermediateCert.publicKey)
                intermediateCert.verify(rootCert.publicKey)
                return Chain(rootCert, intermediateCert, leaf, leaf.private, leafCert, withheldLeaf.private, withheldLeafCert, expired, wrong, other, other.private, otherCert, otherIntermediateCert)
            }

            private fun keys(): KeyPair {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(2048)
                return generator.generateKeyPair()
            }

            private fun sign(
                name: String,
                key: KeyPair,
                issuer: X509Certificate?,
                issuerKey: PrivateKey?,
                notBefore: Date,
                notAfter: Date,
                ca: Boolean,
                pathLen: Int?,
                ip: String?,
                dns: String?,
            ): X509Certificate {
                val subject = X500Name("CN=$name")
                val issuerName = if (issuer == null) subject else X500Name.getInstance(issuer.subjectX500Principal.encoded)
                val builder = JcaX509v3CertificateBuilder(
                    issuerName, BigInteger(63, SecureRandom()).add(BigInteger.ONE), notBefore, notAfter, subject, key.public,
                )
                builder.addExtension(Extension.basicConstraints, true, if (pathLen == null) BasicConstraints(ca) else BasicConstraints(pathLen))
                val usage = if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature or KeyUsage.keyEncipherment
                builder.addExtension(Extension.keyUsage, true, KeyUsage(usage))
                if (!ca) {
                    builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
                    val names = buildList {
                        if (ip != null) add(GeneralName(GeneralName.iPAddress, DEROctetString(InetAddress.getByName(ip).address)))
                        if (dns != null) add(GeneralName(GeneralName.dNSName, dns))
                    }
                    if (names.isNotEmpty()) builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
                }
                // The platform signer is enough. A Bouncy Castle provider is not installed, so it cannot replace Android trust.
                val signer = JcaContentSignerBuilder("SHA256withRSA").build(issuerKey ?: key.private)
                return JcaX509CertificateConverter().getCertificate(builder.build(signer))
            }
        }
    }

    companion object {
        private const val CHAIN_FILE = "stillroom-synthetic-ca.p12"
        private const val ROOT_FILE = "stillroom-synthetic-root.crt"
    }
}
