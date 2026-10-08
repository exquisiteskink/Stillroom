package app.stillroom

import app.stillroom.domain.Account
import app.stillroom.domain.AccountId
import app.stillroom.domain.GrocyCompatibility
import app.stillroom.domain.ServerAddress
import app.stillroom.domain.parseGrocyApiKeyQr
import app.stillroom.domain.retainedVerifiedGrants
import org.junit.Assert.*
import org.junit.Test

class AccountPolicyTest {
    @Test fun tlsIsDefaultAndUrlsHaveCanonicalIdentity() {
        assertEquals("https://example.com/api", ServerAddress.parse("EXAMPLE.com/").apiBase)
        assertEquals("https://example.com/grocy/api", ServerAddress.parse("https://example.com:443/grocy/api/").apiBase)
        assertEquals(ServerAddress.parse("https://example.com/grocy"), ServerAddress.parse("https://example.com/grocy/api/"))
        assertEquals("http://127.0.0.1:9283/api", ServerAddress.parse("http://127.0.0.1:9283", true).apiBase)
    }

    @Test fun insecureAndAmbiguousInputsAreRejected() {
        listOf("http://127.0.0.1:9283", "ftp://example.com", "https://user:secret@example.com", "https://example.com?a=b",
            "https://example.com#fragment", "https://example.com:0", "https://example.com:65536", "").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse(text) }
        }
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse("https://exam\nple.com") }
    }

    @Test fun accountIdentityIncludesServerAndVerifiedUser() {
        val one = ServerAddress.parse("https://one.example")
        val two = ServerAddress.parse("https://two.example")
        assertNotEquals(AccountId.of(one, 2), AccountId.of(one, 3))
        assertNotEquals(AccountId.of(one, 2), AccountId.of(two, 2))
        assertEquals(AccountId.of(one, 2), AccountId.of(ServerAddress.parse("https://one.example:443/api/"), 2))
        assertThrows(IllegalArgumentException::class.java) { AccountId("../../another-account") }
        assertThrows(IllegalArgumentException::class.java) { AccountId.of(one, 0) }
    }

    @Test fun onlyKnownVersionsAreCompatibleAndUnknownPermissionsAreRestricted() {
        assertNull(GrocyCompatibility.warning("4.7.1"))
        assertTrue(GrocyCompatibility.warning("4.6.0")!!.contains("fractional"))
        assertTrue(GrocyCompatibility.warning("99.0.0")!!.startsWith("Version mismatch:"))
        val address = ServerAddress.parse("example.com")
        val account = Account(AccountId.of(address, 3), address, 3, "test-child", "4.7.1", null)
        assertTrue(account.restricted)
        assertTrue(account.copy(permissions = setOf("CHORES", "CHORE_TRACK_EXECUTION")).restricted)
        assertFalse(account.copy(permissions = setOf("ADMIN")).restricted)
        assertFalse(account.copy(permissions = setOf("STOCK")).restricted)
        assertFalse(account.copy(permissions = setOf("RECIPES")).restricted)
    }

    @Test fun reactivationKeepsVerifiedChildGrantsWhenPermissionReadIsUnavailable() {
        val address = ServerAddress.parse("https://home.example")
        val parent = AccountId.of(address, 1)
        val child = AccountId.of(address, 2)
        val preserved = Account(child, address, 2, "child", "4.7.1", setOf("CHORES", "CHORE_TRACK_EXECUTION"), parent)
        val kept = retainedVerifiedGrants(null, null, child, preserved)
        assertEquals(setOf("CHORES", "CHORE_TRACK_EXECUTION"), kept.permissions)
        assertEquals(parent, kept.verifier)
    }

    @Test fun freshConnectionAndUnverifiedChildDoNotInventGrants() {
        val address = ServerAddress.parse("https://home.example")
        val child = AccountId.of(address, 2)
        val other = AccountId.of(address, 9)
        val unverified = Account(child, address, 2, "child", "4.7.1", null)
        assertNull(retainedVerifiedGrants(null, null, null, unverified).permissions)
        assertNull(retainedVerifiedGrants(null, null, child, unverified).permissions)
        assertNull(retainedVerifiedGrants(null, null, child, unverified).verifier)
        assertNull(retainedVerifiedGrants(null, null, other, unverified.copy(permissions = setOf("ADMIN"))).permissions)
        assertEquals(setOf("STOCK"), retainedVerifiedGrants(setOf("STOCK"), null, child, unverified).permissions)
    }

    @Test fun grocyApiKeyQrFillsServerAndKeyWithoutKeepingACalendarSecret() {
        val scanned = parseGrocyApiKeyQr("https://grocy.example/grocy/api|example-key")
        assertEquals("https://grocy.example/grocy/api", scanned.serverUrl)
        assertEquals("example-key", scanned.apiKey)
        assertFalse(scanned.insecureHttp)
        val local = parseGrocyApiKeyQr("http://127.0.0.1:9283/api|local-key")
        assertEquals("http://127.0.0.1:9283/api", local.serverUrl)
        assertTrue(local.insecureHttp)
        assertNull(parseGrocyApiKeyQr("/api|relative-key").serverUrl)
        assertEquals("relative-key", parseGrocyApiKeyQr("/api|relative-key").apiKey)
        assertThrows(IllegalArgumentException::class.java) { parseGrocyApiKeyQr("https://grocy.example/api/calendar/ical?secret=hidden") }
        assertThrows(IllegalArgumentException::class.java) { parseGrocyApiKeyQr("grcy:p:13") }
    }
}
