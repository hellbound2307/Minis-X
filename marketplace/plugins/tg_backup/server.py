#!/usr/bin/env python3
"""minis-x tg_backup plugin — Telegram Bot API backup/restore rail. stdio JSON-RPC, zero-dep.

Anti-wipe design: send_file returns the file_id (the restore key).
get_file(file_id) re-downloads the artifact from Telegram storage.
"""
import json, sys, os, urllib.request, urllib.error

PROTO = "2025-06-18"
NAME, VER = "tg_backup", "1.0.0"
SCOPES = ("/var/minis/workspace/", "/var/minis/shared/", "/var/minis/attachments/")

def _link(k):
    return "minis://settings/environments?create_key=%s&create_value=" % k

def tok():
    t = os.environ.get("TG_BOT_TOKEN")
    if not t:
        raise RuntimeError("TG_BOT_TOKEN not set - add it: %s (token from @BotFather)" % _link("TG_BOT_TOKEN"))
    return t

def chat_default(a):
    c = a.get("chat_id") or os.environ.get("TG_CHAT_ID")
    if not c:
        raise RuntimeError("chat_id required and TG_CHAT_ID not set. Send /start to your bot in Telegram, "
                           "then call get_updates to read your chat id, then set it: %s" % _link("TG_CHAT_ID"))
    return str(c)

def tg(method, params):
    req = urllib.request.Request("https://api.telegram.org/bot%s/%s" % (tok(), method),
                                 data=json.dumps(params).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=90) as r:
        data = json.loads(r.read().decode())
    if not data.get("ok"):
        raise RuntimeError("telegram: %s" % data.get("description"))
    return data.get("result")

def _check_path(p, must_exist=True):
    fp = os.path.abspath(p)
    if not fp.startswith(SCOPES):
        raise RuntimeError("path outside allowed scopes (workspace/shared/attachments): %s" % fp)
    if must_exist and not os.path.isfile(fp):
        raise RuntimeError("no such file: %s" % fp)
    return fp

def do_get_me(a):
    r = tg("getMe", {})
    return "bot: @%s (%s) id=%s — can_join_groups=%s" % (
        r.get("username"), r.get("first_name"), r.get("id"), r.get("can_join_groups"))

def do_send_text(a):
    chat = chat_default(a)
    text = a.get("text") or ""
    if not text.strip():
        raise RuntimeError("text required")
    params = {"chat_id": chat, "text": text[:4096]}
    if a.get("parse_mode"):
        params["parse_mode"] = a["parse_mode"]
    if a.get("reply_to"):
        params["reply_to_message_id"] = int(a["reply_to"])
    r = tg("sendMessage", params)
    return "delivered to chat %s, message_id=%s" % (chat, r.get("message_id"))

def do_send_file(a):
    chat = chat_default(a)
    fp = _check_path(a.get("path"))
    size = os.path.getsize(fp)
    if size > 49 * 1024 * 1024:
        raise RuntimeError("file too large for Bot API sendDocument (%d > 49MB); split it" % size)
    b = "----minisx" + os.urandom(8).hex()
    parts = []

    def field(name, val):
        parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                      % (b, name, val)).encode())

    field("chat_id", chat)
    cap = a.get("caption")
    if cap:
        field("caption", cap[:1024])
    data = open(fp, "rb").read()
    parts.append(("--%s\r\nContent-Disposition: form-data; name=\"document\"; filename=\"%s\"\r\n"
                  "Content-Type: application/octet-stream\r\n\r\n"
                  % (b, os.path.basename(fp))).encode() + data + b"\r\n")
    parts.append(("--%s--\r\n" % b).encode())
    req = urllib.request.Request("https://api.telegram.org/bot%s/sendDocument" % tok(),
                                 data=b"".join(parts),
                                 headers={"Content-Type": "multipart/form-data; boundary=" + b})
    with urllib.request.urlopen(req, timeout=600) as r:
        res = json.loads(r.read().decode())
    if not res.get("ok"):
        raise RuntimeError("telegram: %s" % res.get("description"))
    msg = res.get("result", {})
    doc = msg.get("document", {})
    return ("sent %s (%d bytes) to chat %s | message_id=%s\nfile_id=%s\n"
            "file_id IS the restore key - record it next to the bundle.") % (
        os.path.basename(fp), size, chat, msg.get("message_id"), doc.get("file_id"))

def do_get_file(a):
    fid = a.get("file_id")
    if not fid:
        raise RuntimeError("file_id required")
    r = tg("getFile", {"file_id": fid})
    fpath = r.get("file_path")
    if not fpath:
        raise RuntimeError("telegram returned no file_path")
    url = "https://api.telegram.org/file/bot%s/%s" % (tok(), fpath)
    with urllib.request.urlopen(urllib.request.Request(url), timeout=600) as resp:
        blob = resp.read()
    outdir = "/var/minis/workspace/telegram"
    os.makedirs(outdir, exist_ok=True)
    base = (a.get("as_name") or "").strip() or os.path.basename(fpath) or "restore.bin"
    base = os.path.basename(base)  # no path traversal
    out = os.path.join(outdir, base)
    i = 1
    while os.path.exists(out):
        root, ext = os.path.splitext(base)
        out = os.path.join(outdir, "%s.%d%s" % (root, i, ext))
        i += 1
    with open(out, "wb") as f:
        f.write(blob)
    return "restored %d bytes -> %s" % (len(blob), out)

def do_get_updates(a):
    n = min(int(a.get("n") or 5), 20)
    r = tg("getUpdates", {"limit": n})
    lines = []
    for u in r:
        m = u.get("message") or u.get("edited_message") or u.get("channel_post") or {}
        chat = m.get("chat", {})
        frm = (m.get("from") or {}).get("username", "?")
        lines.append("chat_id=%s (%s) from=@%s: %s"
                     % (chat.get("id"), chat.get("type"), frm,
                        (m.get("text") or "<no text>")[:120]))
    return "\n".join(lines) if lines else "no pending updates (send /start to the bot first)"

TOOLS = [
    {"name": "get_me",
     "description": "Bot health check: identity of TG_BOT_TOKEN.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "send_text",
     "description": "sendMessage to owner chat (default chat from TG_CHAT_ID).",
     "inputSchema": {"type": "object",
                     "properties": {"text": {"type": "string"},
                                    "chat_id": {"type": "string"},
                                    "parse_mode": {"type": "string", "enum": ["HTML", "Markdown", "MarkdownV2"]},
                                    "reply_to": {"type": "integer"}},
                     "required": ["text"]}},
    {"name": "send_file",
     "description": "sendDocument from workspace/shared/attachments (<=49MB). Returns file_id = restore key.",
     "inputSchema": {"type": "object",
                     "properties": {"path": {"type": "string"},
                                    "chat_id": {"type": "string"},
                                    "caption": {"type": "string"}},
                     "required": ["path"]}},
    {"name": "get_file",
     "description": ("Restore a file by file_id -> /var/minis/workspace/telegram/. "
                     "Pass as_name to override the filename (Telegram renames stored docs to file_N.ext)."),
     "inputSchema": {"type": "object",
                     "properties": {"file_id": {"type": "string"},
                                    "as_name": {"type": "string"}},
                     "required": ["file_id"]}},
    {"name": "get_updates",
     "description": "Recent messages (use to discover your chat_id after /start).",
     "inputSchema": {"type": "object",
                     "properties": {"n": {"type": "integer", "default": 5, "maximum": 20}}}}
]

DISPATCH = {"get_me": do_get_me, "send_text": do_send_text, "send_file": do_send_file,
            "get_file": do_get_file, "get_updates": do_get_updates}

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
        except Exception:
            continue
        method = req.get("method")
        rid = req.get("id")
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": PROTO,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": NAME, "version": VER}}})
        elif method and method.startswith("notifications/"):
            continue
        elif method == "tools/list":
            send({"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            params = req.get("params") or {}
            try:
                fname = params.get("name")
                if fname not in DISPATCH:
                    raise RuntimeError("unknown tool %s" % fname)
                out = DISPATCH[fname](params.get("arguments") or {})
                send({"jsonrpc": "2.0", "id": rid,
                      "result": {"content": [{"type": "text", "text": out}]}})
            except Exception as e:
                send({"jsonrpc": "2.0", "id": rid,
                      "result": {"content": [{"type": "text", "text": "ERROR: %s" % e}],
                                 "isError": True}})
        elif rid is not None:
            send({"jsonrpc": "2.0", "id": rid,
                  "error": {"code": -32601, "message": "unknown method: %s" % method}})

if __name__ == "__main__":
    main()
