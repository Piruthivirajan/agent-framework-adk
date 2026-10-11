"""Smoke test of a running deployment, through its public address. Exit code 0 only if every check passes.

    AGENTFW_DEMO_PASSWORD=... python scripts/smoke-test.py https://203-0-113-10.sslip.io
    AGENTFW_DEMO_PASSWORD=smoke-test-pass python scripts/smoke-test.py https://localhost --insecure --demo-mcp demo-mcp:8099

  --insecure   accept Caddy's local certificate authority (DOMAIN=localhost)
  --demo-mcp   host:port of the demo MCP server as the *application* sees it (docker compose: demo-mcp:8099);
               enables the MCP checks. DEMO_MCP_TOKEN (default demo-secret) is its bearer token.

Checks: http redirects to https, security headers, login (wrong and right password, cookie flags), the
streaming endpoint delivers stage events while the turn is still running (Caddy does not buffer SSE), a
RAG answer with citations, a file summary, and with --demo-mcp: the SSRF guard, a wrong token, a tool
answer. It sends about 4 chat requests (model calls).
"""
import http.cookiejar
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

args = [a for a in sys.argv[1:] if not a.startswith("--")]
BASE = (args[0] if args else "http://localhost:8080").rstrip("/")
INSECURE = "--insecure" in sys.argv
DEMO = None
if "--demo-mcp" in sys.argv:
    DEMO = sys.argv[sys.argv.index("--demo-mcp") + 1]
PASSWORD = os.environ.get("AGENTFW_DEMO_PASSWORD", "")
DEMO_TOKEN = os.environ.get("DEMO_MCP_TOKEN", "demo-secret")

ctx = ssl.create_default_context()
if INSECURE:
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), urllib.request.HTTPSHandler(context=ctx))
raw_opener = urllib.request.build_opener(NoRedirect, urllib.request.HTTPSHandler(context=ctx))

failures = []


def check(name, ok, detail=""):
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""))
    if not ok:
        failures.append(name)


def req(method, path, body=None, raw=False, headers=None, timeout=300):
    data = None
    h = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode()
        h.setdefault("Content-Type", "application/json")
    r = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    try:
        with (raw_opener if raw else opener).open(r, timeout=timeout) as resp:
            text = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(text) if text.startswith(("{", "[")) else text), resp.headers
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(text), e.headers
        except ValueError:
            return e.code, text, e.headers


def multipart(session, filename, content):
    b = "----b" + uuid.uuid4().hex
    body = (f'--{b}\r\nContent-Disposition: form-data; name="sessionId"\r\n\r\n{session}\r\n'
            f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            f'Content-Type: text/plain\r\n\r\n').encode() + content + f"\r\n--{b}--\r\n".encode()
    r = urllib.request.Request(BASE + "/api/files", data=body,
                               headers={"Content-Type": "multipart/form-data; boundary=" + b})
    with opener.open(r, timeout=120) as resp:
        return json.load(resp)


def stream_chat(session, message):
    r = urllib.request.Request(BASE + "/api/chat/stream", method="POST",
                               data=json.dumps({"sessionId": session, "message": message}).encode(),
                               headers={"Content-Type": "application/json", "Accept": "text/event-stream"})
    t0 = time.time()
    events, result, event = [], None, None
    with opener.open(r, timeout=300) as resp:
        for line in resp:
            line = line.decode().rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data = json.loads(line[5:])
                events.append((round(time.time() - t0, 2), event))
                if event == "result":
                    result = data
    return events, result


print(f"== {BASE}")
# ---- transport and headers -------------------------------------------------------------------------
if BASE.startswith("https://"):
    http_base = "http://" + BASE[len("https://"):]
    try:
        r = urllib.request.Request(http_base + "/")
        raw_opener.open(r, timeout=20)
        check("http redirects to https", False, "no redirect")
    except urllib.error.HTTPError as e:
        check("http redirects to https", e.code in (301, 302, 307, 308) and
              e.headers.get("Location", "").startswith("https://"), f"{e.code} -> {e.headers.get('Location')}")
    except Exception as e:  # noqa: BLE001
        check("http redirects to https", False, str(e)[:100])

status, _, headers = req("GET", "/login.html")
check("login page is served", status == 200)
if BASE.startswith("https://"):
    check("HSTS header", "max-age" in (headers.get("Strict-Transport-Security") or ""))
check("content security policy header", "default-src 'self'" in (headers.get("Content-Security-Policy") or ""))
check("health check is open", req("GET", "/actuator/health")[0] == 200)

# ---- login -------------------------------------------------------------------------------------------
if not PASSWORD:
    print("AGENTFW_DEMO_PASSWORD is not set: cannot test the login")
    sys.exit(2)
status, body, _ = req("GET", "/api/mcp/servers", raw=True)
check("API without a session is 401", status == 401, str(status))
status, body, _ = req("POST", "/api/login", {"password": "definitely-wrong"})
check("wrong password is refused", status == 401 and body.get("code") == "INVALID_PASSWORD", str(status))
status, body, headers = req("POST", "/api/login", {"password": PASSWORD})
cookie = headers.get("Set-Cookie") or ""
check("right password signs in", status == 200 and body.get("authenticated") is True, str(status))
check("session cookie is HttpOnly and SameSite", "HttpOnly" in cookie and "SameSite=Lax" in cookie, cookie.split(";")[0][:14] + "...")
if BASE.startswith("https://"):
    check("session cookie is Secure", "Secure" in cookie)

# ---- RAG through the proxy, with streaming ----------------------------------------------------------
session = "smoke-" + uuid.uuid4().hex[:6]
events, result = stream_chat(session, "What is the AWS Well-Architected Tool?")
stages = [(t, e) for t, e in events if e == "stage"]
result_at = next((t for t, e in events if e == "result"), None)
check("stage events arrive while the turn is running (SSE not buffered)",
      bool(stages) and result_at is not None and result_at - stages[0][0] > 2.0,
      f"first stage at {stages[0][0] if stages else None} s, result at {result_at} s, {len(stages)} stages")
check("RAG answer is verified and cited", bool(result) and result["kind"] == "answer" and result["grounded"]
      and len(result["sources"]) > 0 and "[" in result["answer"],
      f"kind={result and result['kind']}, sources={result and len(result['sources'])}")
check("trace carries the retrieval", bool(result) and len(result["retrievals"]) >= 1 and
      result["retrievals"][0]["rerank"]["millis"] >= 0,
      f"rerank {result['retrievals'][0]['rerank']['millis']} ms" if result and result["retrievals"] else "")
status, nf, _ = req("POST", "/api/chat", {"sessionId": session + "b", "message": "Who won the 2018 FIFA World Cup?"})
check("a question outside the documents is not answered", status == 200 and nf["kind"] == "not_found", nf.get("kind"))

# ---- file summary -----------------------------------------------------------------------------------
note = ("Quarterly platform review. The queue migration finished in March with 12 minutes of delay and no "
        "lost messages. Compute spend fell by 12 percent. Two incidents: an expired certificate and a "
        "runaway batch job that held database connections for 25 minutes.").encode()
f = multipart(session, "review.txt", note)
status, summary, _ = req("POST", "/api/chat", {"sessionId": session, "message": "Summarize this file in one sentence", "fileId": f["id"]})
check("file summary", status == 200 and summary["kind"] == "summary" and summary["file"]["name"] == "review.txt",
      f"{status} {summary.get('kind') if isinstance(summary, dict) else summary}")

# ---- MCP ---------------------------------------------------------------------------------------------
if DEMO:
    status, t, _ = req("POST", "/api/mcp/servers/test",
                       {"name": "metadata", "url": "http://169.254.169.254/latest/meta-data/",
                        "transport": "STREAMABLE_HTTP", "headers": {}})
    check("SSRF guard refuses the cloud metadata address", status == 200 and t["ok"] is False
          and t["error"]["code"] == "BLOCKED_ADDRESS", t.get("error", {}).get("code") if isinstance(t, dict) else "")
    url = f"http://{DEMO}/mcp"
    status, t, _ = req("POST", "/api/mcp/servers/test", {"name": "Orders", "url": url, "transport": "STREAMABLE_HTTP",
                                                       "headers": {"Authorization": "Bearer wrong-token"}})
    check("wrong token gives the invalid-credentials message", status == 200 and t["ok"] is False
          and t["error"]["code"] == "INVALID_CREDENTIALS" and t["error"]["message"].startswith("Invalid credentials"),
          t["error"]["message"][:70] if isinstance(t, dict) and t.get("error") else str(t)[:80])
    status, v, _ = req("POST", "/api/mcp/servers", {"name": "Orders", "url": url, "transport": "STREAMABLE_HTTP",
                                                  "headers": {"Authorization": "Bearer " + DEMO_TOKEN}})
    check("demo server saved and its tools loaded", status == 201 and v["status"] == "CONNECTED"
          and len(v["tools"]) >= 1, f"{status} {v.get('status') if isinstance(v, dict) else v}")
    check("saved view does not contain the token", DEMO_TOKEN not in json.dumps(v))
    if status == 201:
        status, a, _ = req("POST", "/api/chat", {"sessionId": session + "m", "message": "Where is order ORD-1001?"})
        check("MCP tool answer with the raw result", status == 200 and a["kind"] == "tool_answer"
              and "BlueFreight" in json.dumps(a["mcp"]["calls"][0]["result"]),
              f"{a.get('kind')} via {a.get('route')}" if isinstance(a, dict) else str(a)[:80])
        req("DELETE", "/api/mcp/servers/" + v["id"])

print()
print("ALL CHECKS PASSED" if not failures else f"{len(failures)} CHECK(S) FAILED: " + "; ".join(failures))
sys.exit(1 if failures else 0)
