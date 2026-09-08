#!/usr/bin/env python3
"""minis-x websearch plugin — Exa + Tavily web search. stdio JSON-RPC 2.0, zero-dep."""
import json, sys, os, urllib.request, urllib.error

PROTO = "2025-06-18"
NAME, VER = "websearch", "1.0.0"

def _link(k):
    return "minis://settings/environments?create_key=%s&create_value=" % k

def _post(url, body, headers, timeout=45):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())

def exa(q, n, domain=None, after=None):
    key = os.environ.get("EXA_API_KEY")
    if not key:
        raise RuntimeError("EXA_API_KEY not set - add it: %s" % _link("EXA_API_KEY"))
    body = {"query": q, "numResults": n, "type": "auto",
            "contents": {"text": {"maxCharacters": 900}}}
    if domain:
        body["includeDomains"] = [domain]
    if after:
        body["startPublishedDate"] = after
    try:
        data = _post("https://api.exa.ai/search", body,
                     {"x-api-key": key, "Content-Type": "application/json"})
    except Exception:
        body.pop("contents", None)  # retry lean if contents rejected
        data = _post("https://api.exa.ai/search", body,
                     {"x-api-key": key, "Content-Type": "application/json"})
    out = []
    for i, r in enumerate(data.get("results", []), 1):
        txt = (r.get("text") or r.get("summary") or "").replace("\n", " ")[:450]
        out.append("%d. %s\n   %s%s" % (i, r.get("title", "(no title)"), r.get("url", ""),
                                        ("\n   " + txt) if txt else ""))
    if not out:
        return "exa: 0 results"
    return "exa | %d results\n\n%s" % (len(out), "\n\n".join(out))

def tavily(q, n, domain=None):
    key = os.environ.get("TAVILY_API_KEY")
    if not key:
        raise RuntimeError("TAVILY_API_KEY not set - add it: %s" % _link("TAVILY_API_KEY"))
    body = {"query": q, "max_results": n, "include_answer": True, "search_depth": "basic"}
    if domain:
        body["include_domains"] = [domain]
    data = _post("https://api.tavily.com/search", body,
                 {"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    out = []
    for i, r in enumerate(data.get("results", []), 1):
        out.append("%d. %s\n   %s\n   %s" % (i, r.get("title", "(no title)"), r.get("url", ""),
                                             (r.get("content") or "")[:400].replace("\n", " ")))
    head = "tavily | %d results" % len(out)
    if data.get("answer"):
        head += "\nanswer: %s" % data["answer"]
    if not out:
        return head + "\n(no results)"
    return head + "\n\n" + "\n\n".join(out)

TOOLS = [{
    "name": "search",
    "description": ("Web search. engine=auto uses Exa when its key exists, else Tavily; "
                    "engine=exa|tavily forces one. Optional domain filter; after=YYYY-MM-DD "
                    "publish-date lower bound (Exa). Returns titles, URLs, snippets."),
    "inputSchema": {
        "type": "object",
        "properties": {
            "query": {"type": "string"},
            "n": {"type": "integer", "default": 5, "maximum": 10},
            "engine": {"type": "string", "enum": ["auto", "exa", "tavily"], "default": "auto"},
            "domain": {"type": "string", "description": "restrict to one domain, e.g. github.com"},
            "after": {"type": "string", "description": "YYYY-MM-DD (Exa only)"}
        },
        "required": ["query"]
    }
}]

def call_tool(name, a):
    if name != "search":
        raise RuntimeError("unknown tool %s" % name)
    q = (a.get("query") or "").strip()
    if not q:
        raise RuntimeError("query required")
    n = min(int(a.get("n") or 5), 10)
    eng = a.get("engine") or "auto"
    if eng == "auto":
        if os.environ.get("EXA_API_KEY"):
            eng = "exa"
        elif os.environ.get("TAVILY_API_KEY"):
            eng = "tavily"
        else:
            raise RuntimeError("no search key set. EXA_API_KEY: %s or TAVILY_API_KEY: %s"
                               % (_link("EXA_API_KEY"), _link("TAVILY_API_KEY")))
    if eng == "exa":
        return exa(q, n, a.get("domain"), a.get("after"))
    return tavily(q, n, a.get("domain"))

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
                out = call_tool(params.get("name"), params.get("arguments") or {})
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
