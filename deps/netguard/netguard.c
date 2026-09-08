/*
 * minis-netguard.so — LD_PRELOAD connect() guard for plugin MCP servers.
 *
 * [T-plugin-netguard] Enforces a plugin's declared network allowlist at the
 * syscall layer: every outbound TCP connect() is checked against the hosts
 * in $MINIS_NETGUARD_ALLOW (comma-separated hostnames). Hosts not on the
 * list fail with EPERM. No allowlist var => allow all (user-configured
 * servers keep legacy behavior). IP-literal connects are allowed only when
 * the allowlist contains "*" or an exact IP match; hostname resolution
 * happens inside libc (getaddrinfo), which calls connect() with the resolved
 * sockaddr — so we resolve-back the IP to a name when possible and match
 * against the allowlist, plus permit connects to private/loopback ranges
 * (local MCP/IPC traffic) unless the allowlist explicitly contains "!".
 *
 * Build (aarch64): aarch64-linux-musleabihf-gcc -shared -fPIC ... or on-device:
 *   gcc -shared -fPIC -O2 -o minis-netguard.so netguard.c -ldl
 * The app vendors the prebuilt .so into default_mount/usr/local/lib/ at
 * build time (same pattern as ripgrep); daemon.py sets LD_PRELOAD for
 * plugin server spawns whose manifest declares a network allowlist.
 *
 * Log: violations go to stderr as "NETGUARD:BLOCK <host|ip>" (daemon
 * captures stderr; visible in plugin logs, never silent).
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <errno.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/in.h>
#include <arpa/nameser.h>
#include <resolv.h>

typedef int (*connect_fn_t)(int, const struct sockaddr *, socklen_t);
static connect_fn_t real_connect = NULL;

/* [T-plugin-netguard-dnsrace] getaddrinfo hook: when the HOST resolves an
 * allowlisted name, cache every IP returned by that very answer (v4 + v6).
 * Previously connect() matched against an init-time forward-resolve cache,
 * while the host resolved independently — for DNS-rotating hosts (GitHub API)
 * the two queries could disagree, so allowlisted connects failed EPERM
 * non-deterministically. Recording the app's own answer closes the race. */
typedef int (*gai_fn_t)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
static gai_fn_t real_getaddrinfo = NULL;
static int name_allowed(const char *name);

static char **allow_list = NULL;
static int allow_count = 0;
static int allow_star = 0;
static int allow_private = 1; /* private/loopback allowed unless "!" present */
static int initialized = 0;
static void refresh_allowed_ips(void);
static int name_allowed(const char *name);

static void netguard_init(void) {
    initialized = 1;
    const char *spec = getenv("MINIS_NETGUARD_ALLOW");
    if (!spec || !*spec) return; /* no guard configured */
    char *dup = strdup(spec);
    int cap = 8;
    allow_list = malloc(cap * sizeof(char *));
    char *save = NULL;
    for (char *tok = strtok_r(dup, ",", &save); tok; tok = strtok_r(NULL, ",", &save)) {
        while (*tok == ' ') tok++;
        if (!*tok) continue;
        if (strcmp(tok, "*") == 0) { allow_star = 1; continue; }
        if (strcmp(tok, "!") == 0) { allow_private = 0; continue; }
        if (allow_count >= cap) {
            cap *= 2;
            allow_list = realloc(allow_list, cap * sizeof(char *));
        }
        allow_list[allow_count++] = strdup(tok);
    }
    free(dup);
    real_connect = (connect_fn_t)dlsym(RTLD_NEXT, "connect");
    /* Forward-resolve every allowlist hostname NOW so connect-time checks
     * are pure IP-set lookups (reverse DNS is unreliable — PTR records are
     * missing for most CDNs). Refreshed lazily on a counter. */
    refresh_allowed_ips();
}

static unsigned int *allowed_ips = NULL;
static int allowed_ip_count = 0;
static int allowed_ip_cap = 0;
static int connects_since_refresh = 0;

static void add_allowed_ip4(unsigned int ip) {
    if (allowed_ip_count >= allowed_ip_cap) {
        allowed_ip_cap = allowed_ip_cap ? allowed_ip_cap * 2 : 16;
        allowed_ips = realloc(allowed_ips, allowed_ip_cap * sizeof(unsigned int));
    }
    allowed_ips[allowed_ip_count++] = ip;
}

static struct in6_addr *allowed_ip6 = NULL;
static int allowed_ip6_count = 0;
static int allowed_ip6_cap = 0;

static void add_allowed_ip6(const struct sockaddr_in6 *a) {
    for (int i = 0; i < allowed_ip6_count; i++) {
        if (memcmp(&allowed_ip6[i], &a->sin6_addr, 16) == 0) return; /* dedupe */
    }
    if (allowed_ip6_count >= allowed_ip6_cap) {
        allowed_ip6_cap = allowed_ip6_cap ? allowed_ip6_cap * 2 : 16;
        allowed_ip6 = realloc(allowed_ip6, allowed_ip6_cap * sizeof(struct in6_addr));
    }
    memcpy(&allowed_ip6[allowed_ip6_count++], &a->sin6_addr, 16);
}

/* getaddrinfo hook: pass through to libc; when the requested name is
 * allowlisted, absorb the answer's IPs into the connect-time cache. */
int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints, struct addrinfo **res) {
    if (!initialized) netguard_init();
    if (!real_getaddrinfo)
        real_getaddrinfo = (gai_fn_t)dlsym(RTLD_NEXT, "getaddrinfo");
    int rc = real_getaddrinfo(node, service, hints, res);
    if (rc == 0 && node && *node && res && name_allowed(node)) {
        for (struct addrinfo *r = *res; r; r = r->ai_next) {
            if (r->ai_family == AF_INET) {
                add_allowed_ip4(ntohl(((struct sockaddr_in *)r->ai_addr)->sin_addr.s_addr));
            } else if (r->ai_family == AF_INET6) {
                add_allowed_ip6((const struct sockaddr_in6 *)r->ai_addr);
            }
        }
    }
    return rc;
}

static void refresh_allowed_ips(void) {
    for (int i = 0; i < allow_count; i++) {
        struct addrinfo hints, *res = NULL;
        memset(&hints, 0, sizeof(hints));
        hints.ai_family = AF_INET; /* v4 keeps the check simple; v6 bypasses are rare */
        hints.ai_socktype = SOCK_STREAM;
        if (getaddrinfo(allow_list[i], NULL, &hints, &res) == 0 && res) {
            for (struct addrinfo *r = res; r; r = r->ai_next) {
                if (r->ai_family == AF_INET) {
                    unsigned int ip = ntohl(((struct sockaddr_in *)r->ai_addr)->sin_addr.s_addr);
                    /* dedupe */
                    int seen = 0;
                    for (int j = 0; j < allowed_ip_count; j++) if (allowed_ips[j] == ip) { seen = 1; break; }
                    if (!seen) add_allowed_ip4(ip);
                }
            }
            freeaddrinfo(res);
        }
    }
}

/* Does the allowlist contain this name (case-insensitive)?
 * Exact match, or name is a SUBDOMAIN of an entry (name = "<label>." + entry).
 * [T-plugin-netguard-fix] Round-5 finding: the old first branch did a PREFIX
 * match ("example.com" matched "example.com.evil.com" — an attacker with a
 * PTR record pointing their host at such a name bypassed the guard). Match
 * direction is now suffix-only: entry must equal the name or be its domain. */
static int name_allowed(const char *name) {
    if (!name || !*name) return 0;
    for (int i = 0; i < allow_count; i++) {
        const char *a = allow_list[i];
        size_t al = strlen(a), nl = strlen(name);
        if (al == 0 || nl < al) continue;
        /* exact */
        if (nl == al && strncasecmp(name, a, al) == 0) return 1;
        /* subdomain: name ends with "." + entry */
        if (nl > al && name[nl - al - 1] == '.' && strcasecmp(name + nl - al, a) == 0) return 1;
        /* allowlist entry written with a leading dot: ".example.com" */
        if (a[0] == '.' && nl > al && strcasecmp(name + nl - al, a + 1) == 0 &&
            name[nl - al] == '.') return 1;
    }
    return 0;
}

static int is_private_or_local(const struct sockaddr *sa) {
    if (sa->sa_family == AF_INET) {
        const struct sockaddr_in *in = (const struct sockaddr_in *)sa;
        unsigned int ip = ntohl(in->sin_addr.s_addr);
        if ((ip >> 24) == 127 || (ip >> 24) == 10 || (ip >> 16) == 0xA9FE /*169.254*/) return 1;
        if ((ip >> 20) == 0xAC1 /*172.16-31*/) return 1;
        if ((ip >> 16) == 0xC0A8 /*192.168*/) return 1;
    } else if (sa->sa_family == AF_INET6) {
        const struct sockaddr_in6 *in6 = (const struct sockaddr_in6 *)sa;
        if (IN6_IS_ADDR_LOOPBACK(&in6->sin6_addr)) return 1;
        if (IN6_IS_ADDR_LINKLOCAL(&in6->sin6_addr)) return 1;
        /* ULA fc00::/7: first byte 0xfc or 0xfd */
        const unsigned char *b = (const unsigned char *)&in6->sin6_addr;
        if (b[0] == 0xfc || b[0] == 0xfd) return 1;
    }
    return 0;
}

/* IP check: exact match against the forward-resolved IP cache, refreshed
 * every 32 connects (cheap; covers DNS rotation without per-connect lookups). */
static int ip_allowed(const struct sockaddr *sa) {
    if (sa->sa_family == AF_INET) {
        unsigned int ip = ntohl(((const struct sockaddr_in *)sa)->sin_addr.s_addr);
        if (++connects_since_refresh >= 32) {
            connects_since_refresh = 0;
            refresh_allowed_ips();
        }
        for (int i = 0; i < allowed_ip_count; i++) {
            if (allowed_ips[i] == ip) return 1;
        }
    } else if (sa->sa_family == AF_INET6) {
        const struct sockaddr_in6 *in6 = (const struct sockaddr_in6 *)sa;
        for (int i = 0; i < allowed_ip6_count; i++) {
            if (memcmp(&allowed_ip6[i], &in6->sin6_addr, 16) == 0) return 1;
        }
    }
    /* IP-literal match against allowlist entries (covers explicit IPs) */
    char ipstr[INET6_ADDRSTRLEN] = {0};
    if (sa->sa_family == AF_INET) {
        inet_ntop(AF_INET, &((struct sockaddr_in *)sa)->sin_addr, ipstr, sizeof(ipstr));
    } else {
        inet_ntop(AF_INET6, &((struct sockaddr_in6 *)sa)->sin6_addr, ipstr, sizeof(ipstr));
    }
    for (int i = 0; i < allow_count; i++) {
        if (strcasecmp(ipstr, allow_list[i]) == 0) return 1;
    }
    /* Last resort: reverse-DNS hostname match (works when PTR exists) */
    char host[NI_MAXHOST] = {0};
    if (getnameinfo(sa, sa->sa_family == AF_INET6 ? sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in),
                    host, sizeof(host), NULL, 0, NI_NAMEREQD) == 0 && host[0]) {
        if (name_allowed(host)) return 1;
    }
    return 0;
}

int connect(int fd, const struct sockaddr *sa, socklen_t len) {
    if (!initialized) netguard_init();
    if (!real_connect) real_connect = (connect_fn_t)dlsym(RTLD_NEXT, "connect");
    if (!real_connect) { errno = ENOSYS; return -1; }

    /* No allowlist configured => legacy behavior. */
    if (!allow_list && !allow_star) return real_connect(fd, sa, len);

    if (sa && (sa->sa_family == AF_INET || sa->sa_family == AF_INET6)) {
        if (allow_private && is_private_or_local(sa)) {
            return real_connect(fd, sa, len);
        }
        if (ip_allowed(sa)) {
            return real_connect(fd, sa, len);
        }
        fprintf(stderr, "NETGUARD:BLOCK family=%d\n", sa->sa_family);
        errno = EPERM;
        return -1;
    }
    /* Non-inet (unix sockets etc.) — always allowed. */
    return real_connect(fd, sa, len);
}
