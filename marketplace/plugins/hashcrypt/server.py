#!/usr/bin/env python3
"""[marketplace] hashcrypt — offline hashing & encoding MCP server.

Zero dependencies, zero network. Standard-library only: SHA-256/SHA-1/MD5
digests, HMAC, base64/base32 encode+decode, hex, URL encode/decode, UUID
generation, password generation (secrets module).

Everything runs locally — nothing leaves the device.
"""
import base64
import hashlib
import hmac as hmac_mod
import json
import secrets
import string
import sys
import urllib.parse
import uuid

PROTOCOL_VERSION = "2025-06-18"


def text(s):
    return {"type": "text", "text": s}


TOOLS = [
    {
        "name": "hash",
        "description": "Hash data with sha256 (default), sha1, sha512 or md5. Input is the raw string (utf-8).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "data": {"type": "string", "description": "The string to hash"},
                "algorithm": {"type": "string", "description": "sha256|sha1|sha512|md5", "enum": ["sha256", "sha1", "sha512", "md5"]},
            },
            "required": ["data"],
        },
    },
    {
        "name": "hmac",
        "description": "Compute HMAC-SHA256 of data with a key.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "data": {"type": "string"},
                "key": {"type": "string"},
            },
            "required": ["data", "key"],
        },
    },
    {
        "name": "base64",
        "description": "Base64 encode or decode.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "data": {"type": "string"},
                "decode": {"type": "boolean", "description": "true = decode (input is base64), false = encode"},
            },
            "required": ["data"],
        },
    },
    {
        "name": "url_encode",
        "description": "URL-encode or URL-decode a string.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "data": {"type": "string"},
                "decode": {"type": "boolean"},
            },
            "required": ["data"],
        },
    },
    {
        "name": "uuid4",
        "description": "Generate a random UUIDv4.",
        "inputSchema": {"type": "object", "properties": {"count": {"type": "integer", "description": "How many (default 1, max 20)"}}},
    },
    {
        "name": "password",
        "description": "Generate a cryptographically random password. Length 8-128 (default 20). Does not use ambiguous chars by default.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "length": {"type": "integer"},
                "symbols": {"type": "boolean", "description": "Include symbols (default false)"},
            },
        },
    },
]


def handle_call(name, args):
    try:
        if name == "hash":
            algo = (args.get("algorithm") or "sha256").lower()
            if algo not in ("sha256", "sha1", "sha512", "md5"):
                return {"content": [text(f"Unsupported algorithm: {algo}")], "isError": True}
            digest = hashlib.new(algo, args["data"].encode("utf-8")).hexdigest()
            return {"content": [text(digest)]}
        if name == "hmac":
            mac = hmac_mod.new(args["key"].encode("utf-8"), args["data"].encode("utf-8"), hashlib.sha256).hexdigest()
            return {"content": [text(mac)]}
        if name == "base64":
            if args.get("decode"):
                out = base64.b64decode(args["data"]).decode("utf-8", errors="replace")
            else:
                out = base64.b64encode(args["data"].encode("utf-8")).decode("ascii")
            return {"content": [text(out)]}
        if name == "url_encode":
            data = args["data"]
            out = urllib.parse.unquote_plus(data) if args.get("decode") else urllib.parse.quote_plus(data)
            return {"content": [text(out)]}
        if name == "uuid4":
            n = min(max(int(args.get("count", 1)), 1), 20)
            return {"content": [text("\n".join(str(uuid.uuid4()) for _ in range(n)))]}
        if name == "password":
            length = min(max(int(args.get("length", 20)), 8), 128)
            alphabet = string.ascii_letters + string.digits
            if not args.get("symbols", False):
                alphabet = alphabet.replace("O", "").replace("0", "").replace("l", "").replace("I", "")
            else:
                alphabet += "!@#$%^&*-_=+?"
            out = "".join(secrets.choice(alphabet) for _ in range(length))
            return {"content": [text(out)]}
        return {"content": [text(f"Unknown tool: {name}")], "isError": True}
    except Exception as e:  # noqa: BLE001
        return {"content": [text(f"Error: {e}")], "isError": True}


def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue
        method = req.get("method", "")
        rid = req.get("id")
        if method == "initialize":
            result = {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "hashcrypt", "version": "1.0.0"},
            }
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            params = req.get("params") or {}
            result = handle_call(params.get("name", ""), params.get("arguments") or {})
        elif method.startswith("notifications/"):
            continue
        else:
            if rid is None:
                continue
            send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "method not found"}})
            continue
        if rid is not None:
            send({"jsonrpc": "2.0", "id": rid, "result": result})


if __name__ == "__main__":
    main()
