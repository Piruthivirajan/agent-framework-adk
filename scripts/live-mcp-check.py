"""Live scenario D check. Needs the application on :8080 (started with AGENTFW_MCP_ALLOWED_HOSTS=localhost, or the SSRF guard
refuses the demo server) and the demo MCP server on :8099:

    java src/test/java/com/demo/agentfw/mcp/DemoMcpServer.java --port 8099 --token demo-secret
    python scripts/live-mcp-check.py

It configures the demo server through the settings API (Streamable HTTP, then SSE), asks questions that
need its data, a question that the documents answer, a tool error, and exercises the error messages for a
wrong token, a wrong path and a server that is down. With --login it signs in first (AGENTFW_DEMO_PASSWORD).
"""
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("AGENTFW_BASE", "http://localhost:8080")
DEMO = os.environ.get("DEMO_MCP", "http://localhost:8099")
TOKEN = os.environ.get("DEMO_MCP_TOKEN", "demo-secret")

jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"} if data else {})
    try:
        with opener.open(req, timeout=240) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def server(name, path, transport, token=TOKEN):
    return {"name": name, "url": DEMO + path, "transport": transport,
            "headers": {"Authorization": "Bearer " + token}}


def ask(session, text):
    t = time.time()
    status, d = call("POST", "/api/chat", {"sessionId": session, "message": text})
    if status != 200:
        print(f"   HTTP {status}: {d}")
        return None
    print(f"   [{d['kind']}] route={d['route']} tools={d['toolCalls']} {time.time() - t:.1f} s")
    print("   answer:", d["answer"][:300].replace("\n", " "))
    for c in (d.get("mcp") or {}).get("calls", []):
        print(f"   tool call: {c['serverName']} / {c['tool']} {c['arguments']} -> "
              f"{'ERROR ' if c['error'] else ''}{(c['message'] if c['error'] else c['result'])[:160]!r}")
    return d


def main():
    if "--login" in sys.argv:
        s, d = call("POST", "/api/login", {"password": os.environ["AGENTFW_DEMO_PASSWORD"]})
        print("login:", s)
    for s in call("GET", "/api/mcp/servers")[1]:
        call("DELETE", "/api/mcp/servers/" + s["id"])

    print("== error messages")
    for label, cfg in [
        ("wrong token", server("bad token", "/mcp", "STREAMABLE_HTTP", "not-the-token")),
        ("wrong path", server("bad path", "/nothing", "STREAMABLE_HTTP")),
        ("server down", {"name": "down", "url": "http://localhost:9/mcp", "transport": "STREAMABLE_HTTP", "headers": {}}),
        ("wrong transport", server("wrong transport", "/mcp", "SSE")),
        ("stdio command", {"name": "stdio", "url": "npx some-mcp-server", "transport": "STREAMABLE_HTTP", "headers": {}}),
    ]:
        status, d = call("POST", "/api/mcp/servers/test", cfg)
        msg = d["error"]["message"] if status == 200 and d.get("error") else d.get("message") if isinstance(d, dict) else d
        print(f"   {label:16} -> HTTP {status} {msg}")

    print("== Streamable HTTP")
    status, view = call("POST", "/api/mcp/servers", server("Orders", "/mcp", "STREAMABLE_HTTP"))
    print("   saved:", status, view["status"], [t["name"] for t in view["tools"]])
    sess = "mcp-" + uuid.uuid4().hex[:6]
    for q in ["Where is order ORD-1001 and when will it arrive?",
              "Who is the on-call engineer for the payments team today?",
              "What does the Reliability pillar say about backups?",
              "Show me the billing report for 2026-09.",
              "Who won the 2018 FIFA World Cup?"]:
        print("Q:", q)
        ask(sess, q)

    print("== legacy SSE (replace the server)")
    call("DELETE", "/api/mcp/servers/" + view["id"])
    status, view2 = call("POST", "/api/mcp/servers", server("Orders (SSE)", "/sse", "SSE"))
    print("   saved:", status, view2["status"], [t["name"] for t in view2["tools"]])
    print("Q: Where is order ORD-1002?")
    ask("mcp-" + uuid.uuid4().hex[:6], "Where is order ORD-1002?")
    call("DELETE", "/api/mcp/servers/" + view2["id"])


if __name__ == "__main__":
    main()
