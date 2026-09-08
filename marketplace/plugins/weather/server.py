#!/usr/bin/env python3
"""[marketplace] weather — current conditions + forecast via wttr.in.

Network plugin: declares network: [wttr.in] — the netguard arms its
allowlist at spawn; every other host is blocked at connect(). No API key
required (wttr.in free tier).
"""
import json
import sys
import urllib.request

PROTOCOL_VERSION = "2025-06-18"
BASE = "https://wttr.in"


def text(s):
    return {"type": "text", "text": s}


def fetch_json(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", errors="replace"))


TOOLS = [
    {
        "name": "current",
        "description": "Current weather for a location (city, 'city,country', or coordinates). Includes temperature, feels-like, humidity, wind, conditions.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "location": {"type": "string", "description": "e.g. 'Algiers', 'New+York', '36.7,3.1'"},
            },
            "required": ["location"],
        },
    },
    {
        "name": "forecast",
        "description": "3-day forecast for a location: min/max C, sunrise/sunset, hourly summary for today.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "location": {"type": "string"},
            },
            "required": ["location"],
        },
    },
]


def handle_call(name, args):
    location = urllib.parse.quote(str(args.get("location", "")).replace(" ", "+").strip())
    if not location:
        return {"content": [text("Error: 'location' is required")], "isError": True}
    try:
        if name == "current":
            data = fetch_json(f"{BASE}/{location}?format=j1")
            cur = data["current_condition"][0]
            desc = cur.get("weatherDesc", [{}])[0].get("value", "?")
            area = data.get("nearest_area", [{}])[0]
            place = ", ".join(a.get("value", "") for a in area.get("areaName", [{"value": location}]))
            out = (
                f"Weather in {place}:\n"
                f"  {desc}\n"
                f"  Temp: {cur['temp_C']}°C (feels {cur['FeelsLikeC']}°C)\n"
                f"  Humidity: {cur['humidity']}%  |  Wind: {cur['windspeedKmph']} km/h {cur['winddir16Point']}\n"
                f"  Pressure: {cur['pressure']} hPa  |  Visibility: {cur['visibility']} km\n"
                f"  Cloud cover: {cur['cloudcover']}%  |  UV: {cur.get('uvIndex', '?')}"
            )
            return {"content": [text(out)]}
        if name == "forecast":
            data = fetch_json(f"{BASE}/{location}?format=j1")
            area = data.get("nearest_area", [{}])[0]
            place = ", ".join(a.get("value", "") for a in area.get("areaName", [{"value": location}]))
            lines = [f"Forecast for {place}:"]
            for day in data.get("weather", [])[:3]:
                astronomy = day.get("astronomy", [{}])[0]
                lines.append(
                    f"\n{day.get('date','?')}: min {day['mintempC']}°C / max {day['maxtempC']}°C — "
                    f"{day.get('hourly',[{}])[4].get('weatherDesc',[{}])[0].get('value','?')} | "
                    f"sunrise {astronomy.get('sunrise','?')} sunset {astronomy.get('sunset','?')}"
                )
            return {"content": [text("\n".join(lines))]}
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
                "serverInfo": {"name": "weather", "version": "1.0.0"},
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
