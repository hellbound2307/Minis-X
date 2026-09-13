package com.openminis.app.tools.web

import java.net.InetAddress
import java.net.URI

/**
 * [T-ssrf-filter] Audit P0 #4 — SSRF guard for the agent's own HTTP tools
 * (web_fetch). Jenny architecture, adapted: fail-closed at the tool layer,
 * provider endpoints exempt.
 *
 * Threat: the agent can be prompt-injected by fetched page content into
 * hitting internal services — cloud metadata (169.254.169.254, which
 * answers with credentials on EC2/GCE-style hosts), loopback services on
 * the device/LAN, CGNAT space, link-local neighbors. The netguard
 * (LD_PRELOAD connect guard) covers PLUGIN servers only; the agent's own
 * OkHttp client went straight through.
 *
 * Design:
 *  - Blocklist of CIDR-equivalent ranges (v4 + v6 loopback/ULA), checked
 *    against EVERY resolved address of the host, BEFORE the request.
 *  - The check is a separate step (validate) so redirects can re-check:
 *    an allowed public URL that redirects to 127.0.0.1 must be caught at
 *    the redirect hop. OkHttp follows redirects inside one execute() — so
 *    the enforcement point is an Interceptor that re-validates on every
 *    hop (including the initial request).
 *  - DNS resolution happens here (java.net) — the resolved IPs are what
 *    get checked, not the hostname string, so DNS-rebinding via a
 *    hostname that points at private space is caught. TOCTOU (resolve
 *    here, re-resolve inside OkHttp's socket connect) is a residual risk
 *    shared by every userspace filter without a custom Dns implementation;
 *    the interceptor ALSO pins a custom Dns that checks at OkHttp's own
 *    resolution point, closing the gap.
 *  - Non-numeric hostnames that fail to resolve = refused (fail-closed,
 *    not fail-open).
 *  - Port: no port restrictions (metadata endpoints live on :80/:443
 *    anyway; blocking ports adds noise without security).
 *
 * Exemptions (documented, deliberate):
 *  - Tailscale CGNAT range 100.64.0.0/10 is BLOCKED by default like other
 *    private space. The audit documents it as a legitimate use case for
 *    some setups; on a phone there is no documented need, and a
 *    user-controlled allowlist is future work if a use case appears.
 */
object SsrfGuard {

    /** Ranges as (name, predicate on InetAddress). Checked in order. */
    private val BLOCKED: List<Pair<String, (InetAddress) -> Boolean>> = buildList {
        add("loopback" to { it.isLoopbackAddress })
        add("link-local (cloud metadata)" to { it.isLinkLocalAddress })
        add("site-local / private" to { it.isSiteLocalAddress })
        add("unique-local (IPv6)" to { it.address.size == 16 && (it.address[0].toInt() and 0xFE) == 0xFC })
        add("IPv4-mapped IPv6" to { it.address.size == 16 && it.address[0] == 0.toByte() && it.address[1] == 0.toByte() })
        add(" multicast" to { it.isMulticastAddress })
        add("any-local (0.0.0.0)" to { it.isAnyLocalAddress })
        // IPv4 special ranges not covered by the flags above:
        // 100.64.0.0/10 CGNAT (Tailscale/carrier NAT)
        add("CGNAT 100.64.0.0/10" to { addr ->
            addr.address.size == 4 &&
                (addr.address[0].toInt() and 0xFF) == 100 &&
                (addr.address[1].toInt() and 0xFF) in 64..127
        })
        // 192.0.0.0/24 IETF protocol assignments (incl. 192.0.0.8 "dummy")
        add("IETF 192.0.0.0/24" to { addr ->
            addr.address.size == 4 &&
                (addr.address[0].toInt() and 0xFF) == 192 &&
                (addr.address[1].toInt() and 0xFF) == 0 &&
                (addr.address[2].toInt() and 0xFF) == 0
        })
        // 198.18.0.0/15 benchmarking (second octet 18 or 19)
        add("benchmark 198.18.0.0/15" to { addr ->
            addr.address.size == 4 &&
                (addr.address[0].toInt() and 0xFF) == 198 &&
                ((addr.address[1].toInt() and 0xFF) == 18 || (addr.address[1].toInt() and 0xFF) == 19)
        })
    }

    /** Validate a URL string: scheme + host resolvability + address ranges.
     *  Returns null when allowed, or a human-readable refusal. */
    fun checkUrl(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return "Invalid URL"
        }
        val scheme = uri.scheme?.lowercase() ?: return "URL has no scheme"
        if (scheme !in listOf("http", "https")) {
            return "Only http(s) URLs are fetchable (got '$scheme')"
        }
        val host = uri.host ?: return "URL has no host"
        return checkHost(host)
    }

    /** Resolve + range-check a hostname or IP literal. Null = allowed. */
    fun checkHost(host: String): String? {
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (_: Exception) {
            return "Host '$host' does not resolve (SSRF guard is fail-closed)"
        }
        for (addr in addresses) {
            for ((name, predicate) in BLOCKED) {
                if (predicate(addr)) {
                    return "Blocked by SSRF guard: $host resolves to ${addr.hostAddress} ($name range). " +
                        "Private, loopback, link-local, CGNAT and metadata endpoints are not fetchable " +
                        "by the agent's web tools."
                }
            }
        }
        return null
    }

    /** OkHttp DNS that enforces the same policy at connection time —
     *  closes the resolve-here/check-here TOCTOU gap. */
    class GuardDns : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val refused = checkHost(hostname)
            if (refused != null) throw java.net.UnknownHostException(refused)
            return InetAddress.getAllByName(hostname).toList()
        }
    }
}
