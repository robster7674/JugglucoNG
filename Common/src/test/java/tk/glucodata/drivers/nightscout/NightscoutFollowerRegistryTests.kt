package tk.glucodata.drivers.nightscout

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection

/**
 * Unit tests for NightscoutFollowerRegistry.applyAuth() HTTP header logic.
 *
 * Covers:
 * - Empty secret → no auth header set
 * - Raw 40-char SHA1 hex secret (Nightscout api-secret style)
 * - Plain text secret → SHA1 hashed
 * - Bearer token prefix
 * - token=<value> prefix
 */
class NightscoutFollowerRegistryTests {

    private fun applyAuthToMock(secret: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val connection = object : HttpURLConnection(null as java.net.URL?) {
            override fun setRequestProperty(key: String, value: String) {
                headers[key] = value
            }
            override fun getResponseCode() = 200
            override fun getInputStream() = null as java.io.InputStream?
            override fun getOutputStream() = null as java.io.OutputStream?
        }
        NightscoutFollowerRegistry.applyAuth(connection, secret)
        return headers
    }

    // ---------- applyAuth ----------

    @Test
    fun applyAuth_emptySecret_noHeaders() {
        val headers = applyAuthToMock("")
        assertTrue("no headers expected", headers.isEmpty())
    }

    @Test
    fun applyAuth_whitespaceOnlySecret_noHeaders() {
        val headers = applyAuthToMock("   ")
        assertTrue("no headers expected", headers.isEmpty())
    }

    @Test
    fun applyAuth_rawSha1Hex_sentAsApiSecret() {
        // 40-char hex = raw SHA1 (Nightscout stores api-secret this way)
        val sha1hex = "a".repeat(40)
        val headers = applyAuthToMock(sha1hex)
        assertEquals(sha1hex, headers["api-secret"])
        assertNull(headers["Authorization"])
    }

    @Test
    fun applyAuth_plainText_hashed() {
        val plain = "my-super-secret"
        val headers = applyAuthToMock(plain)
        // SHA-1 of "my-super-secret" = 8ae8a0d03a065a868f4e80b61f6a11f7f5ac02a0
        assertEquals("8ae8a0d03a065a868f4e80b61f6a11f7f5ac02a0", headers["api-secret"])
    }

    @Test
    fun applyAuth_bearerToken_forwardedAsAuthorization() {
        val headers = applyAuthToMock("Bearer my-token-123")
        assertEquals("Bearer my-token-123", headers["Authorization"])
        assertNull(headers["api-secret"])
    }

    @Test
    fun applyAuth_bearerPrefixCaseInsensitive() {
        val headers = applyAuthToMock("bearer my-token-456")
        assertEquals("Bearer my-token-456", headers["Authorization"])
    }

    @Test
    fun applyAuth_tokenEqualsPrefix_strippedAndBearerAdded() {
        val headers = applyAuthToMock("token=abc-xyz")
        assertEquals("Bearer abc-xyz", headers["Authorization"])
    }

    @Test
    fun applyAuth_tokenEqualsCaseInsensitive() {
        val headers = applyAuthToMock("TOKEN=xyz-789")
        assertEquals("Bearer xyz-789", headers["Authorization"])
    }

    // ---------- normalizeUrl ----------

    @Test
    fun normalizeUrl_addsHttpsWhenMissing() {
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("example.com"))
        assertEquals("https://example.com/", NightscoutFollowerRegistry.normalizeUrl("example.com/"))
    }

    @Test
    fun normalizeUrl_preservesExistingScheme() {
        assertEquals("http://example.com", NightscoutFollowerRegistry.normalizeUrl("http://example.com"))
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("https://example.com"))
    }

    @Test
    fun normalizeUrl_stripsTrailingSlash() {
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("https://example.com/"))
        assertEquals("https://example.com/path", NightscoutFollowerRegistry.normalizeUrl("https://example.com/path/"))
    }

    @Test
    fun normalizeUrl_trimsWhitespace() {
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("  https://example.com  "))
    }

    @Test
    fun normalizeUrl_emptyReturnsEmpty() {
        assertEquals("", NightscoutFollowerRegistry.normalizeUrl(""))
        assertEquals("", NightscoutFollowerRegistry.normalizeUrl(null))
        assertEquals("", NightscoutFollowerRegistry.normalizeUrl("   "))
    }

    // ---------- deriveSensorId ----------

    @Test
    fun deriveSensorId_prefixAndHash() {
        val id = NightscoutFollowerRegistry.deriveSensorId("https://example.com")
        assertTrue("should start with NSF-", id.startsWith("NSF-"))
        assertEquals(16, id.length) // "NSF-" (4) + 12 hex chars (6 bytes)
    }

    @Test
    fun deriveSensorId_sameUrlProducesSameId() {
        val id1 = NightscoutFollowerRegistry.deriveSensorId("https://example.com")
        val id2 = NightscoutFollowerRegistry.deriveSensorId("example.com")
        assertEquals(id1, id2)
    }

    @Test
    fun deriveSensorId_differentUrlsProduceDifferentIds() {
        val idA = NightscoutFollowerRegistry.deriveSensorId("https://site-a.com")
        val idB = NightscoutFollowerRegistry.deriveSensorId("https://site-b.com")
        assertNotEquals(idA, idB)
    }

    @Test
    fun deriveSensorId_unconfiguredReturnsUnconfiguredSuffix() {
        val id = NightscoutFollowerRegistry.deriveSensorId(null)
        assertTrue(id.endsWith("UNCONFIGURED"))
    }

    // ---------- matchesSensorId ----------

    @Test
    fun matchesSensorId_exactMatch() {
        assertTrue(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", "NSF-ABC123"))
    }

    @Test
    fun matchesSensorId_caseInsensitive() {
        assertTrue(NightscoutFollowerRegistry.matchesSensorId("nsf-abc123", "NSF-ABC123"))
        assertTrue(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", "nsf-abc123"))
    }

    @Test
    fun matchesSensorId_whitespaceTrimmed() {
        assertTrue(NightscoutFollowerRegistry.matchesSensorId("  NSF-ABC123  ", "NSF-ABC123"))
    }

    @Test
    fun matchesSensorId_emptyCandidate_returnsFalse() {
        assertFalse(NightscoutFollowerRegistry.matchesSensorId("", "NSF-ABC123"))
        assertFalse(NightscoutFollowerRegistry.matchesSensorId(null, "NSF-ABC123"))
    }

    @Test
    fun matchesSensorId_emptyExpected_returnsFalse() {
        assertFalse(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", ""))
        assertFalse(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", null))
    }

    @Test
    fun matchesSensorId_mismatch_returnsFalse() {
        assertFalse(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", "NSF-DEF456"))
    }
}