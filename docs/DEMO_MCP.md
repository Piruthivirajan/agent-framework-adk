# Demo MCP server and scenario D

Scenario D: *add an MCP server and its credentials in the UI, then ask a question that needs its data;
the tool is called, its data is used in the answer, and no restart is needed.*

The repository includes a tiny MCP server for exactly this: one Java file, JDK only, no extra
download. Its data is invented (orders, an on-call roster), so a question about it can only be answered
through the server.

## 1. Start the demo server

From the project folder (Java 21 is already required by the application):

```bash
java src/test/java/com/demo/agentfw/mcp/DemoMcpServer.java --port 8099 --token demo-secret
```

It prints its two endpoints:

| transport | URL | header |
| --- | --- | --- |
| Streamable HTTP (current) | `http://localhost:8099/mcp` | `Authorization: Bearer demo-secret` |
| SSE (legacy) | `http://localhost:8099/sse` | `Authorization: Bearer demo-secret` |

Tools it offers:

| tool | what it does | example |
| --- | --- | --- |
| `lookup_order(order_id)` | status, carrier and delivery estimate of an order | `ORD-1001` shipped, `ORD-1002` processing, `ORD-1003` delivered |
| `oncall_engineer(team)` | who is on call today | `platform`, `payments`, `search` |
| `billing_report(month)` | **always fails** with a 503, to show how tool errors are reported | `2026-09` |

## 2. Start the application and open the settings page

```bash
docker compose up -d        # MongoDB Atlas local
# the demo server is on this machine, which the SSRF guard refuses unless the host is allowed:
AGENTFW_MCP_ALLOWED_HOSTS=localhost ./mvnw spring-boot:run      # then open http://localhost:8080
```

Open **MCP settings** (side panel of the chat page, or `http://localhost:8080/mcp.html`).

1. **Name** `Orders`, **URL** `http://localhost:8099/mcp`, **Transport** *Streamable HTTP*.
2. **Header** `Authorization` = `Bearer demo-secret`.
3. **Test connection**: it should list the three tools. (Try a wrong token first to see
   "Invalid credentials: the MCP server rejected the token or header (HTTP 401)".)
4. **Save server**. The tools are available to the assistant at the very next message: no restart.

The header value is not shown again anywhere; the server list shows the header name and
"values hidden". With `AGENTFW_MCP_SECRET_KEY` set (`scripts/deploy.sh` generates one) the server is saved
in MongoDB with its address and header values encrypted and is connected again after a restart; without it
the credentials are kept in memory only and are lost on restart (the page says which of the two applies).

## 3. Ask questions

In the chat:

- *Where is order ORD-1001 and when will it arrive?* gets an answer from `lookup_order`. The answer is
  labelled **"From tool lookup_order on Orders · not checked against the documents"**; open **How this
  answer was produced** to see the tool call, its arguments and the **raw tool result**.
- *Who is the on-call engineer for the payments team today?* is answered from `oncall_engineer`.
- *Show me the billing report for 2026-09.* shows the tool error: "The tool billing_report on Orders
  reported an error: ...". It is never turned into a made-up report.
- *What does the Reliability pillar say about backups?* is still answered from the PDFs, with citations:
  the tools are only used when the documents have no answer or the question is about the tools' data.
- *Who won the 2018 FIFA World Cup?* is not in the documents and no tool fits: "Not found in the
  reference documents."

## 4. See the error messages

| try | message |
| --- | --- |
| wrong token | Invalid credentials: the MCP server rejected the token or header (HTTP 401). |
| URL `http://localhost:8099/nothing` | The server answered "not found" at ... Check the path of the URL. |
| stop the demo server, press **Test again** | Unable to connect to MCP server: nothing answered at ... |
| SSE URL with transport Streamable HTTP (or the reverse) | The address answered with HTTP 405 ... Check the transport. |
| `npx some-server` as the URL | That is not a URL. Servers started by a local command (stdio) are not supported ... |
| `http://169.254.169.254/latest/meta-data/` | This address is not allowed: it points to a private, loopback or link-local network ... |
| `http://localhost:8099/mcp` without `AGENTFW_MCP_ALLOWED_HOSTS=localhost` | the same "not allowed" sentence, with the setting to change |

## 5. Scripted version

With the application on port 8080 and the demo server running:

```bash
python scripts/live-mcp-check.py
```

It configures the server through the API (both transports), asks the questions above and prints the
tool calls. It deletes the servers it created.

## Limits to know

- **stdio is not supported**, by design: a hosted application cannot run a user-supplied command.
- Configuration is persisted **only with `AGENTFW_MCP_SECRET_KEY`** (AES-GCM, one key from the environment;
  a key that is lost or changed leaves the saved servers unreadable, they are not deleted). Without the key
  it is in memory only and a restart forgets it.
- The server URL is fetched by the application. An **SSRF guard** refuses addresses that resolve to a
  private, loopback or link-local network (the cloud metadata address included) unless the host is listed in
  `AGENTFW_MCP_ALLOWED_HOSTS` (that is why the demo needs it: `localhost` on a laptop, `demo-mcp` in Docker
  Compose). Not covered by the guard: DNS rebinding between the check and the connection. For the metadata
  address the deployment adds an egress rule (`scripts/block-metadata.sh`, installed by `deploy.sh`).
- Tool answers are written by the model from the tool result and are **not** verified the way document
  answers are; the raw result is always shown next to the answer.
