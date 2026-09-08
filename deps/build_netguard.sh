#!/usr/bin/env bash
#
# Build minis-netguard.so — the LD_PRELOAD connect() guard for plugin MCP
# servers [T-plugin-netguard].
#
# The shim intercepts connect() and enforces the plugin's declared network
# allowlist ($MINIS_NETGUARD_ALLOW, comma-separated hostnames). Enforcement
# semantics (validated against a live aarch64 sandbox):
#   - var unset            → no guard (legacy, user-configured servers)
#   - allowlist non-empty  → default-DENY public egress; allow loopback/
#     private ranges unless "!" present; allowlist matched by forward-resolved
#     IP cache (refreshed every 32 connects) + IP literals + reverse-DNS
#   - subdomains match     → "example.com" allows "www.example.com"
#
# Built with the NDK toolchain (already required by build_proot.sh); output
# lands in default_mount so the shim is vendored into the rootfs and available
# to the minis-mcp-cli daemon at /usr/local/lib/minis/netguard.so.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/../src/android/app/src/main/assets/default_mount/usr/local/lib/minis"
OUT_FILE="$OUT_DIR/netguard.so"

# CRITICAL: the shim is LD_PRELOADed into GUEST processes (Alpine musl
# python3), NOT into bionic processes. An NDK-built .so needs bionic symbols
# (__errno, __sF) and DT_NEEDED libc.so -> musl loader fails relocation and
# EVERY plugin spawn dies instantly (verified on-device 2026-09-08). Prefer a
# musl cross-compiler via $CC; NDK is only a fallback with a loud warning.
CLANG="${CC:-}"
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    for c in "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android2*-clang \
             "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android-clang; do
        [ -x "$c" ] && CLANG="$c" && break
    done
fi
if [ -z "$CLANG" ]; then
    # fallback: NDK discovery (bionic output — will NOT load under musl guests)
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
        for c in "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android2*-clang \
                 "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android-clang; do
            [ -x "$c" ] && CLANG="$c" && break
        done
    fi
    [ -n "$CLANG" ] && echo "WARNING: building netguard with NDK (bionic) — this shim will CRASH musl guest spawns. Set CC=aarch64-linux-musl-gcc instead."
    [ -z "$CLANG" ] && { echo "ERROR: no compiler found (set CC=aarch64-linux-musl-gcc or ANDROID_NDK_HOME)"; exit 1; }
fi

echo "[netguard] building with $CLANG"
mkdir -p "$OUT_DIR"
"$CLANG" -shared -fPIC -O2 -Wall -o "$OUT_FILE" "$SCRIPT_DIR/netguard/netguard.c" -ldl
echo "[netguard] OK -> $OUT_FILE ($(stat -c%s "$OUT_FILE") bytes)"

# Smoke check: it must be an ELF shared object.
head -c 4 "$OUT_FILE" | od -An -tx1 | grep -q "7f 45 4c 46" || { echo "not ELF"; exit 1; }
echo "[netguard] ELF header verified"
