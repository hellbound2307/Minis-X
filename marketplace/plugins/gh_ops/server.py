#!/usr/bin/env python3
"""minis-x gh_ops plugin — GitHub sword-loop operations. stdio JSON-RPC, zero-dep.

ci_runs / ci_redispatch / commit_file / release_cut / repo_triage.
Auth: GITHUB_TOKEN (declared in manifest env, scrubbed-safe). Default repo via GH_DEFAULT_REPO.
"""
import json, sys, os, base64, urllib.request, urllib.error

PROTO = "2025-06-18"
NAME, VER = "gh_ops", "1.0.0"

def _link(k):
    return "minis://settings/environments?create_key=%s&create_value=" % k

def tok():
    t = os.environ.get("GITHUB_TOKEN")
    if not t:
        raise RuntimeError("GITHUB_TOKEN not set - add it: %s" % _link("GITHUB_TOKEN"))
    return t

def repo(a):
    r = a.get("repo") or os.environ.get("GH_DEFAULT_REPO")
    if not r:
        raise RuntimeError("repo required (owner/name) or set GH_DEFAULT_REPO")
    return r.strip("/")

def gh(path, method="GET", body=None, raw=None, ctype="application/json", host="api.github.com"):
    url = "https://%s%s" % (host, path)
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": "Bearer " + tok(),
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "minisx-ghops",
        "Content-Type": ctype})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            blob = r.read().decode()
            return r.status, (json.loads(blob) if blob and blob.strip().startswith(("{", "[")) else blob)
    except urllib.error.HTTPError as e:
        blob = e.read().decode(errors="replace")
        try:
            return e.code, json.loads(blob)
        except Exception:
            return e.code, blob

def _fmt_run(r):
    c = r.get("conclusion") or r.get("status")
    mark = {"success": "OK ", "failure": "FAIL", "cancelled": "CANC", "timed_out": "TIME"}.get(c, "....")
    return "%s #%s [%s] %s | %s %s | %s" % (
        mark, r.get("run_number"), str(r.get("id"))[-6:], r.get("name"),
        r.get("head_branch"), str(r.get("head_sha") or "")[:7], r.get("created_at"))

def do_ci_runs(a):
    o, r = repo(a).split("/", 1)
    q = "/repos/%s/%s/actions/runs?per_page=%d" % (o, r, min(int(a.get("n") or 10), 30))
    if a.get("branch"):
        q += "&branch=" + a["branch"]
    status, data = gh(q)
    if status != 200:
        raise RuntimeError("api %s: %s" % (status, data if isinstance(data, str) else data.get("message")))
    runs = data.get("workflow_runs", [])
    if a.get("run_id"):
        js, jobs = gh("/repos/%s/%s/actions/runs/%s/jobs" % (o, r, a["run_id"]))
        lines = []
        for j in jobs.get("jobs", []):
            lines.append("job: %s -> %s" % (j.get("name"), j.get("conclusion")))
            for s in j.get("steps", []):
                if s.get("conclusion") not in ("success", "skipped", None):
                    lines.append("   step FAILED: %s (%s)" % (s.get("name"), s.get("conclusion")))
        return "\n".join(lines) or "no jobs"
    if not runs:
        return "no workflow runs"
    return "\n".join(_fmt_run(x) for x in runs)

def do_ci_redispatch(a):
    o, r = repo(a).split("/", 1)
    wf = a.get("workflow")
    if not wf:
        raise RuntimeError("workflow filename required, e.g. android.yml")
    ref = a.get("ref") or "main"
    status, data = gh("/repos/%s/%s/actions/workflows/%s/dispatches" % (o, r, wf),
                      method="POST", body={"ref": ref, "inputs": a.get("inputs") or {}})
    if status == 204:
        return "dispatched %s @ %s — poll with ci_runs" % (wf, ref)
    raise RuntimeError("dispatch %s: %s" % (status, data if isinstance(data, str) else data.get("message")))

def do_commit_file(a):
    o, r = repo(a).split("/", 1)
    path = (a.get("path") or "").lstrip("/")
    if not path:
        raise RuntimeError("path required (repo-relative), e.g. marketplace/plugins/x/server.py")
    msg = a.get("message") or ("update %s (gh_ops)" % path)
    src = a.get("file")
    if src:
        fp = os.path.abspath(src)
        if not (fp.startswith("/var/minis/workspace/") or fp.startswith("/var/minis/attachments/")):
            raise RuntimeError("file outside workspace/attachments: %s" % fp)
        content = open(fp, "rb").read()
    else:
        content = a.get("content", "").encode()
    st, cur = gh("/repos/%s/%s/contents/%s" % (o, r, path))
    body = {"message": msg,
            "content": base64.b64encode(content).decode(),
            "branch": a.get("branch") or "main"}
    if st == 200 and isinstance(cur, dict) and cur.get("sha"):
        body["sha"] = cur["sha"]
    st2, res = gh("/repos/%s/%s/contents/%s" % (o, r, path), method="PUT", body=body)
    if st2 in (200, 201):
        c = res.get("commit", {})
        return "committed %s -> %s (sha %s)" % (path, a.get("branch") or "main", str(c.get("sha"))[:8])
    raise RuntimeError("commit %s: %s" % (st2, res if isinstance(res, str) else res.get("message")))

def do_release_cut(a):
    o, r = repo(a).split("/", 1)
    tag = a.get("tag")
    if not tag:
        raise RuntimeError("tag required, e.g. v1.20-x0")
    body = {"tag_name": tag, "name": a.get("name") or tag,
            "body": a.get("notes") or "", "draft": False, "prerelease": bool(a.get("prerelease"))}
    if a.get("target"):
        body["target_commitish"] = a["target"]
    st, rel = gh("/repos/%s/%s/releases" % (o, r), method="POST", body=body)
    if st == 422:
        st, rel = gh("/repos/%s/%s/releases/tags/%s" % (o, r, tag))
        note = "release %s already existed (id %s)" % (tag, rel.get("id"))
    elif st in (201, 200):
        note = "release %s created (id %s)" % (tag, rel.get("id"))
    else:
        raise RuntimeError("release %s: %s" % (st, rel if isinstance(rel, str) else rel.get("message")))
    rel_id = rel.get("id")
    out = [note]
    asset = a.get("asset")
    if asset and rel_id:
        fp = os.path.abspath(asset)
        if not (fp.startswith("/var/minis/workspace/") or fp.startswith("/var/minis/attachments/")):
            raise RuntimeError("asset outside workspace/attachments: %s" % fp)
        aname = os.path.basename(fp)
        blob = open(fp, "rb").read()
        st3, up = gh("/repos/%s/%s/releases/%s/assets?name=%s" % (o, r, rel_id, aname),
                     method="POST", raw=blob, ctype="application/octet-stream",
                     host="uploads.github.com")
        if st3 == 201:
            out.append("asset uploaded: %s (%d bytes) -> %s" % (aname, len(blob), up.get("browser_download_url")))
        else:
            out.append("asset upload FAILED %s: %s" % (st3, up if isinstance(up, str) else up.get("message")))
    return "\n".join(out)

def do_repo_triage(a):
    o, r = repo(a).split("/", 1)
    lines = []
    st, prs = gh("/repos/%s/%s/pulls?state=open&per_page=15" % (o, r))
    if st == 200:
        lines.append("open PRs: %d" % len(prs))
        for p in prs:
            lines.append("  #%s %s | %s -> %s | %s" % (
                p.get("number"), p.get("title"), p.get("head", {}).get("ref"),
                p.get("base", {}).get("ref"), p.get("html_url")))
    else:
        lines.append("pulls %s: %s" % (st, prs if isinstance(prs, str) else prs.get("message")))
    st2, iss = gh("/repos/%s/%s/issues?state=open&per_page=15" % (o, r))
    if st2 == 200:
        real = [i for i in iss if "pull_request" not in i]
        lines.append("open issues: %d" % len(real))
        for i in real:
            lines.append("  #%s %s | %s" % (i.get("number"), i.get("title"), i.get("html_url")))
    else:
        lines.append("issues %s: %s" % (st2, iss if isinstance(iss, str) else iss.get("message")))
    return "\n".join(lines)

TOOLS = [
    {"name": "ci_runs",
     "description": "List recent workflow runs (red/green). Pass run_id to get failed job/step breakdown.",
     "inputSchema": {"type": "object",
                     "properties": {"repo": {"type": "string"}, "n": {"type": "integer"},
                                    "branch": {"type": "string"}, "run_id": {"type": "integer"}}}},
    {"name": "ci_redispatch",
     "description": "Trigger workflow_dispatch (workflow filename e.g. android.yml, ref, inputs).",
     "inputSchema": {"type": "object",
                     "properties": {"repo": {"type": "string"}, "workflow": {"type": "string"},
                                    "ref": {"type": "string"}, "inputs": {"type": "object"}},
                     "required": ["workflow"]}},
    {"name": "commit_file",
     "description": "Git-less commit: create/update one file on a branch (content string or file path in workspace/attachments).",
     "inputSchema": {"type": "object",
                     "properties": {"repo": {"type": "string"}, "path": {"type": "string"},
                                    "content": {"type": "string"}, "file": {"type": "string"},
                                    "message": {"type": "string"}, "branch": {"type": "string"}},
                     "required": ["path"]}},
    {"name": "release_cut",
     "description": "Create release from tag (reuses existing on 422) and optionally upload asset (APK) from workspace/attachments.",
     "inputSchema": {"type": "object",
                     "properties": {"repo": {"type": "string"}, "tag": {"type": "string"},
                                    "name": {"type": "string"}, "notes": {"type": "string"},
                                    "target": {"type": "string"}, "prerelease": {"type": "boolean"},
                                    "asset": {"type": "string"}},
                     "required": ["tag"]}},
    {"name": "repo_triage",
     "description": "Open PRs + issues for a repo (triage without leaving chat).",
     "inputSchema": {"type": "object", "properties": {"repo": {"type": "string"}}}}
]

DISPATCH = {"ci_runs": do_ci_runs, "ci_redispatch": do_ci_redispatch,
            "commit_file": do_commit_file, "release_cut": do_release_cut,
            "repo_triage": do_repo_triage}

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
