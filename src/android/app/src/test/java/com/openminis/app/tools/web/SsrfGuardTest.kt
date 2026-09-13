package com.openminis.app.tools.web

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * [T-ssrf-filter] Range-predicate contract for the SSRF guard (audit P0 #4).
 *
 * checkHost() on IP LITERALS never touches DNS (InetAddress.getAllByName
 * parses literals locally), so every blocked range is JVM-testable without
 * network. Hostname resolution (public hosts resolve and pass; NXDOMAIN
 * refuses) is on-device verified — the JVM here would depend on the
 * runner's resolver.
 */
class SsrfGuardTest {

    private fun blocked(ip: String): String? = SsrfGuard.checkHost(ip)

    private fun assertBlocked(ip: String) {
        val refusal = blocked(ip)
        assertNotNull("expected $ip to be blocked", refusal)
        assertTrue(refusal!!.contains("SSRF guard"))
    }

    private fun assertAllowed(ip: String) {
        assertNull("expected $ip to be allowed", blocked(ip))
    }

    // ── Metadata + loopback: the highest-value blocks ─────────────────────

    @Test
    fun `cloud metadata endpoints are blocked`() {
        assertBlocked("169.254.169.254")  // AWS/GCE/Azure metadata
        // metadata.google.internal resolves to 169.254.169.254 INSIDE cloud
        // environments and NXDOMAINs elsewhere. Both outcomes are refusals:
        // blocked-range hit, or fail-closed unresolvable. The assertNotNull
        // shape covers both; on CI runners it exercises the NXDOMAIN branch.
        assertNotNull(
            "metadata.google.internal must never pass (either blocked range or NXDOMAIN)",
            blocked("metadata.google.internal"),
        )
        assertBlocked("fd00:ec2::254")     // AWS IMDSv6 endpoint (ULA fc/fd range)
    }

    @Test
    fun `loopback variants blocked`() {
        assertBlocked("127.0.0.1")
        assertBlocked("127.8.8.8")        // whole 127/8 is loopback
        assertBlocked("localhost")
        assertBlocked("::1")
    }

    @Test
    fun `private v4 ranges blocked`() {
        assertBlocked("10.0.0.1")
        assertBlocked("10.255.255.255")
        assertBlocked("172.16.0.1")
        assertBlocked("172.31.255.254")
        assertBlocked("192.168.1.1")
        assertBlocked("192.168.0.100")
    }

    @Test
    fun `private v6 ranges blocked`() {
        assertBlocked("fd12:3456:789a::1") // ULA fd00::/8
        assertBlocked("fc00::1")            // ULA fc00::/7
        assertBlocked("::ffff:127.0.0.1")   // IPv4-mapped IPv6
        assertBlocked("::ffff:10.0.0.1")
    }

    @Test
    fun `cgnat tailscale range blocked`() {
        assertBlocked("100.64.0.1")
        assertBlocked("100.100.100.100")  // Tailscale MagicDNS resolver
        assertBlocked("100.127.255.255")
    }

    @Test
    fun `link-local blocked`() {
        assertBlocked("169.254.1.1")
        assertBlocked("fe80::1")
    }

    @Test
    fun `special ranges blocked`() {
        assertBlocked("0.0.0.0")
        assertBlocked("192.0.0.8")   // IETF dummy
        assertBlocked("198.18.0.5")  // benchmarking
        assertBlocked("198.19.255.1")
        assertBlocked("224.0.0.1")  // multicast
    }

    // ── Public space must NOT be over-blocked ──────────────────────────────
    // Over-blocking is a regression too: a filter that eats the internet
    // gets disabled by users.

    @Test
    fun `public v4 allowed`() {
        assertAllowed("1.1.1.1")
        assertAllowed("8.8.8.8")
        assertAllowed("93.184.216.34")   // example.com
        assertAllowed("100.128.0.1")      // just past CGNAT 100.127.255.255
        assertAllowed("100.63.255.255")    // just before CGNAT
    }

    @Test
    fun `public v6 allowed`() {
        assertAllowed("2606:4700:4700::1111") // Cloudflare DNS
        assertAllowed("2001:4860:4860::8888") // Google DNS
    }

    @Test
    fun `edge cases at range boundaries`() {
        // 172.16.0.0/12 = 172.16.0.0 – 172.31.255.255
        assertBlocked("172.16.0.0")  // range start
        assertAllowed("172.32.0.1")    // first public after the block
        assertAllowed("172.15.255.255") // last public before the block
        // CGNAT 100.64.0.0/10 = 100.64.0.0 – 100.127.255.255
        assertBlocked("100.64.0.0")
        assertAllowed("100.63.255.255")
        assertAllowed("100.128.0.0")
    }

    // ── URL parsing layer ──────────────────────────────────────────────────

    @Test
    fun `checkUrl rejects non-http schemes`() {
        val refusal = SsrfGuard.checkUrl("file:///etc/passwd")
        assertNotNull(refusal)
        assertTrue(refusal.contains("http"))
        assertNotNull(SsrfGuard.checkUrl("ftp://example.com"))
    }

    @Test
    fun `checkUrl parses and checks the host`() {
        assertNotNull(SsrfGuard.checkUrl("http://127.0.0.1:8080/admin"))
        assertNotNull(SsrfGuard.checkUrl("https://169.254.169.254/latest/meta-data/iam/security-credentials/"))
    }

    @Test
    fun `checkUrl malformed fails closed`() {
        assertNotNull(SsrfGuard.checkUrl("http://"))     // no host
        assertNotNull(SsrfGuard.checkUrl("not-a-url"))
    }

    // ── GuardDns (connection-time enforcement) ────────────────────────────

    @Test
    fun `guardDns throws on blocked literal`() {
        val dns = SsrfGuard.GuardDns()
        try {
            dns.lookup("127.0.0.1")
            throw AssertionError("expected UnknownHostException")
        } catch (e: java.net.UnknownHostException) {
            assertTrue(e.message!!.contains("SSRF guard"))
        }
    }

    @Test
    fun `guardDns throws on unresolvable host`() {
        val dns = SsrfGuard.GuardDns()
        try {
            // .invalid is RFC-reserved NXDOMAIN
            dns.lookup("this-domain-cannot-exist.invalid")
            throw AssertionError("expected UnknownHostException")
        } catch (_: java.net.UnknownHostException) { }
    }
}
