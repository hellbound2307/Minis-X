#!/usr/bin/env python3
"""[marketplace] unitconv — offline unit conversion MCP server.

Zero dependencies, zero network. Serves as the catalog's offline reference
plugin: if this works but network plugins fail, the netguard/policy layer is
the variable, not the kernel.

MCP stdio server (JSON-RPC 2.0, newline-delimited): initialize →
notifications/initialized → tools/list → tools/call.
"""
import json
import sys

PROTOCOL_VERSION = "2025-06-18"

# Base-unit factors (metres, kg, bytes, m/s)
LENGTH = {"m": 1.0, "km": 1000.0, "cm": 0.01, "mm": 0.001, "mi": 1609.344, "yd": 0.9144, "ft": 0.3048, "in": 0.0254, "nmi": 1852.0}
MASS = {"kg": 1.0, "g": 0.001, "mg": 1e-6, "t": 1000.0, "lb": 0.45359237, "oz": 0.028349523125, "st": 6.35029318}
DATA = {"B": 1.0, "KB": 1e3, "MB": 1e6, "GB": 1e9, "TB": 1e12, "KiB": 1024.0, "MiB": 1024.0**2, "GiB": 1024.0**3, "TiB": 1024.0**4}
SPEED = {"m/s": 1.0, "km/h": 1.0 / 3.6, "mph": 0.44704, "kn": 0.514444, "ft/s": 0.3048}
TEMP = {"C", "F", "K"}
TABLES = {"length": LENGTH, "mass": MASS, "data": DATA, "speed": SPEED}


def temp_conv(v, f, t):
    if f == t:
        return v
    if f == "F":
        v = (v - 32) * 5 / 9
    elif f == "K":
        v = v - 273.15
    if t == "F":
        return v * 9 / 5 + 32
    if t == "K":
        return v + 273.15
    return v


def convert(value, unit_from, unit_to):
    if unit_from.upper() in TEMP and unit_to.upper() in TEMP:
        return temp_conv(value, unit_from.upper(), unit_to.upper())
    for table in TABLES.values():
        keys = {k.lower(): k for k in table}
        if unit_from.lower() in keys and unit_to.lower() in keys:
            base = value * table[keys[unit_from.lower()]]
            return base / table[keys[unit_to.lower()]]
    raise ValueError(f"unknown units: {unit_from} → {unit_to}")


TOOLS = [
    {
        "name": "unit_convert",
        "description": "Convert between units: length (m/km/cm/mm/mi/yd/ft/in/nmi), mass (kg/g/mg/t/lb/oz/st), data (B/KB/MB/GB/TB/KiB/MiB/GiB/TiB), speed (m/s,km/h,mph/kn,ft/s), temperature (C/F/K). Fully offline.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "value": {"type": "number", "description": "The numeric value to convert"},
                "from": {"type": "string", "description": "Source unit (e.g. 'km', 'lb', 'C')"},
                "to": {"type": "string", "description": "Target unit"},
            },
            "required": ["value", "from", "to"],
        },
    },
    {
        "name": "unit_table",
        "description": "List all supported conversion categories and units.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


def handle_call(name, args):
    if name == "unit_table":
        lines = [f"{cat}: {', '.join(tbl)}" for cat, tbl in TABLES.items()]
        lines.append("temperature: C, F, K")
        return {"content": [text("\n".join(lines))]}
    if name == "unit_convert":
        try:
            value = float(args["value"])
            result = convert(value, str(args["from"]), str(args["to"]))
            pretty = f"{result:,.6g}"
            return {"content": [text(f"{value:g} {args['from']} = {pretty} {args['to']}")]}
        except (KeyError, ValueError, TypeError) as e:
            return {"content": [text(f"Error: {e}")], "isError": True}
    return {"content": [text(f"Unknown tool: {name}")], "isError": True}


def text(s):
    return {"type": "text", "text": s}


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
                "serverInfo": {"name": "unitconv", "version": "1.0.0"},
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
            result = None
            send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "method not found"}})
            continue
        if rid is not None:
            send({"jsonrpc": "2.0", "id": rid, "result": result})


def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


if __name__ == "__main__":
    main()
