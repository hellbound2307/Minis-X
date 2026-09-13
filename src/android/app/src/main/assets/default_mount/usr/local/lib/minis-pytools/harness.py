#!/usr/bin/env python3
"""minis-pytools harness — runner + introspector for agent-minted Python tools.

Ships via default_mount (installed into the rootfs at boot, same pattern as
minis-mcp-cli). Two modes:

  run <tool_name> <args_b64> [code_override_b64]
      Loads /var/minis/meta-tools/<tool_name>.py (or decodes the override),
      decodes the base64 args JSON, execs the tool code, and calls
      main(**args). The tool's stdout (plus stderr tail on failure) is the
      result. Exit 0 = success, 1 = tool raised, 2 = harness error.

  introspect <tool_name_or_path>
      Reads the tool code, extracts main's signature (basic type annotations
      str/int/float/bool) and docstring, prints one JSON line:
        {"ok": true, "description": ..., "schema": {...}}
        {"ok": false, "error": "...", "message": "..."}

Both modes base64-encode content through argv so no quoting ever breaks —
the two-stage embed pattern (args b64 -> code exec -> main(**args)) taken
from Zafiro's CustomPyToolHarness. Full output of the last run is also
written to /var/minis/meta-tools/.last_output.txt so a truncated inline
result can be file_read in full.
"""

import base64
import inspect
import json
import os
import sys
import traceback

TOOLS_DIR = "/var/minis/meta-tools"
LAST_OUTPUT = os.path.join(TOOLS_DIR, ".last_output.txt")

TYPE_MAP = {"str": "string", "int": "integer", "float": "number", "bool": "boolean"}

MAX_INLINE_BYTES = 30_000


def _decode_b64(data):
    return base64.b64decode(data).decode("utf-8")


def _tool_path(name):
    # Name is validated Kotlin-side (^[a-z][a-z0-9_]{2,31}$, no separators);
    # the basename check here is defense in depth, never the gate.
    safe = os.path.basename(name)
    return os.path.join(TOOLS_DIR, safe + ".py")


def _load_code(tool_name, code_override_b64):
    if code_override_b64:
        return _decode_b64(code_override_b64), "<override>"
    path = _tool_path(tool_name)
    if not os.path.exists(path):
        return None, None
    with open(path, "r", encoding="utf-8") as f:
        return f.read(), path


def _write_last_output(text):
    try:
        os.makedirs(TOOLS_DIR, exist_ok=True)
        with open(LAST_OUTPUT, "w", encoding="utf-8") as f:
            f.write(text)
    except OSError:
        pass


def run(tool_name, args_b64, code_override_b64=None):
    code, path = _load_code(tool_name, code_override_b64)
    if code is None:
        print("Harness error: tool '%s' not found at %s" % (tool_name, _tool_path(tool_name)))
        return 2
    try:
        args = json.loads(_decode_b64(args_b64)) if args_b64 else {}
        if not isinstance(args, dict):
            print("Harness error: decoded args are not a JSON object")
            return 2
    except Exception as e:
        print("Harness error: bad args JSON: %s" % e)
        return 2

    import io
    import contextlib

    out = io.StringIO()
    err = io.StringIO()
    status = 0
    try:
        ns = {}
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            exec(compile(code, path or "<pytool>", "exec"), ns)
            main = ns.get("main")
            if not callable(main):
                print("Harness error: tool must define a callable main(...).")
                status = 2
            else:
                main(**args)
    except SystemExit as e:
        # A tool may exit deliberately; treat 0 as success, anything else as failure.
        code0 = e.code if isinstance(e.code, int) else 1
        if code0 != 0:
            status = 1
            err.write("SystemExit: %s\n" % (e.code,))
    except BaseException:
        status = 1
        err.write(traceback.format_exc())

    combined = out.getvalue()
    if err.getvalue().strip():
        combined = (combined + ("\n" if combined and not combined.endswith("\n") else "") +
                    "[stderr]\n" + err.getvalue().strip())

    _write_last_output(combined)

    if len(combined.encode("utf-8")) > MAX_INLINE_BYTES:
        head = combined.encode("utf-8")[:MAX_INLINE_BYTES].decode("utf-8", "ignore")
        total = len(combined.encode("utf-8"))
        print("%s\n…[truncated: showing first %d of %d bytes; full output at %s — file_read it]"
              % (head, MAX_INLINE_BYTES, total, LAST_OUTPUT))
    else:
        print(combined, end="" if combined.endswith("\n") or not combined else "\n")
    return status


def introspect(target):
    # Accept a tool name or a direct path (test action introspects draft files).
    if os.path.sep in target:
        path = target
    else:
        path = _tool_path(target)
    try:
        with open(path, "r", encoding="utf-8") as f:
            code = f.read()
    except OSError as e:
        print(json.dumps({"ok": False, "error": "NOT_FOUND", "message": str(e)}))
        return 2

    ns = {}
    try:
        exec(compile(code, path, "exec"), ns)
    except SyntaxError as e:
        print(json.dumps({"ok": False, "error": "SyntaxError", "line": e.lineno, "message": e.msg}))
        return 1
    except BaseException as e:
        print(json.dumps({"ok": False, "error": type(e).__name__, "message": str(e)}))
        return 1

    main = ns.get("main")
    if not callable(main):
        print(json.dumps({"ok": False, "error": "MISSING_MAIN",
                          "message": "Tool must define a callable main(...)."}))
        return 1

    props, required, bad = {}, [], []
    try:
        params = list(inspect.signature(main).parameters.values())
    except (TypeError, ValueError) as e:
        print(json.dumps({"ok": False, "error": "SIGNATURE", "message": str(e)}))
        return 1
    for p in params:
        if p.kind in (p.VAR_POSITIONAL, p.VAR_KEYWORD):
            # *args/**kwargs accepted but produce no schema fields; calls pass
            # named kwargs only, so **kwargs is harmless and *args unused.
            continue
        tname = getattr(p.annotation, "__name__", None)
        if tname not in TYPE_MAP:
            bad.append(p.name)
            continue
        prop = {"type": TYPE_MAP[tname]}
        if p.default is not inspect.Parameter.empty:
            prop["description"] = "default: %r" % (p.default,)
        else:
            required.append(p.name)
        props[p.name] = prop

    if bad:
        print(json.dumps({"ok": False, "error": "UNANNOTATED_PARAMS",
                          "message": "Parameters need basic type annotations (str/int/float/bool): "
                                     + ", ".join(bad)}))
        return 1

    doc = (inspect.getdoc(main) or "").strip()
    print(json.dumps({"ok": True, "description": doc,
                      "schema": {"type": "object", "properties": props, "required": required}}))
    return 0


def main():
    if len(sys.argv) < 3:
        print("usage: harness.py run|introspect <tool> [b64...]")
        return 2
    mode, tool = sys.argv[1], sys.argv[2]
    if mode == "run":
        return run(tool, sys.argv[3] if len(sys.argv) > 3 else "",
                   sys.argv[4] if len(sys.argv) > 4 else None)
    if mode == "introspect":
        return introspect(tool)
    print("unknown mode: %s" % mode)
    return 2


if __name__ == "__main__":
    sys.exit(main())
