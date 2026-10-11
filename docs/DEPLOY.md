# Deployment: one VM, Docker Compose, HTTPS

The stack is four containers on one machine: **Caddy** (HTTPS, the only thing reachable from outside),
the **application**, **MongoDB Atlas local** (mongod + the search process), and optionally the
**demo MCP server**. `scripts/deploy.sh` does the whole installation on a fresh Ubuntu VM.

## What the VM needs

| | requirement | why |
| --- | --- | --- |
| **OS** | **Ubuntu Server 24.04 LTS** (22.04 also works), **x86_64 / amd64** | `deploy.sh` uses the official Docker apt repository. amd64 is what the image and the ONNX Runtime native library were built and tested for. |
| **CPU** | **4 vCPU**, dedicated or non-burstable (2 vCPU works, slower) | Reranking runs on the CPU: 12 passages through a 22M-parameter model per search. Measured on a 6-core laptop: about 0.7 s per search. A shared or burstable core that runs out of credits will make every answer slower. |
| **RAM** | **8 GB** (4 GB is the floor, add 2 GB of swap) | MongoDB + its search process take 2 to 3 GB, the application about 1.5 GB (JVM, the 91 MB model, ONNX Runtime), the image build needs 2 GB for a few minutes. |
| **Disk** | **40 GB SSD** (25 GB is enough) | Images about 4 GB, build cache about 1 GB, the corpus 40 MB, the database well under 1 GB. |
| **Network** | a **public IPv4 address**, outbound internet | The host name `<ip>.sslip.io` resolves to it. Outbound: `huggingface.co` (build only), `generativelanguage.googleapis.com` (Gemini, every request), Docker Hub and `download.docker.com` (install), `docs.aws.amazon.com` (corpus download), Let's Encrypt / ZeroSSL (certificate). |

### Ports

| port | direction | open to | purpose |
| --- | --- | --- | --- |
| **22/tcp** | inbound | your own IP only | SSH |
| **80/tcp** | inbound | the internet | certificate challenge and redirect to https (Let's Encrypt validates over port 80) |
| **443/tcp** | inbound | the internet | the application over https (including the server-sent event streams) |
| 443/udp | inbound | the internet | optional, HTTP/3 |
| 27017, 8080, 8099 | - | **nobody** | MongoDB, the application and the demo MCP server are on the internal Docker network only; `docker-compose.prod.yml` publishes nothing but Caddy's 80 and 443. |

In a cloud provider's security group or firewall: allow 22 from your IP, 80 and 443 from anywhere.
Nothing else. If port 80 is blocked the certificate cannot be issued and the site will not start over https.

Examples of machines of this class: Hetzner CX32/CPX31, DigitalOcean "General Purpose 4 vCPU / 8 GB" or a
Premium 4 GB / 2 vCPU for a tight budget, AWS `m6i.large` (2 vCPU / 8 GB) or `c6i.xlarge` (4 vCPU /
8 GB). Avoid `t3`/`t4g` burstable instances for the rerank step.

## Steps

```bash
# on the VM, as a normal user with sudo
git clone <your repository URL> agentfw && cd agentfw
bash scripts/deploy.sh               # 1st run: creates .env, prints what to fill in, stops
nano .env                            # GOOGLE_API_KEY, AGENTFW_DEMO_PASSWORD, ACME_EMAIL
bash scripts/deploy.sh --demo        # 2nd run: installs Docker, fetches the corpus, builds, starts, ingests
```

`--demo` also starts the demo MCP server for scenario D (leave it out to run without it).
The script prints the URL (`https://<ip-with-dashes>.sslip.io`), and the first request may take a few
seconds while Caddy obtains the certificate.

What `deploy.sh` does, in order: install Docker and the compose plugin if missing; create `.env` and fill
`DOMAIN` (from the public IP) and `APP_UID`/`APP_GID`; stop if `GOOGLE_API_KEY`, `AGENTFW_DEMO_PASSWORD`
or `ACME_EMAIL` is empty; download the 10 corpus PDFs and verify their sha256; allow only 22, 80 and 443
in `ufw`; `docker compose up -d --build` (the image build downloads the cross-encoder model and fails on a
sha256 mismatch); wait for the application's health check; wait for "Ingestion finished" in the log.

### After the deployment: the checks to do (and to write down for the README)

1. Open the URL, sign in, ask a question from the PDFs. In **How this answer was produced**, read the
   **rerank** time of a search. Record it: it is the number to compare with the laptop's 0.7 s.
2. Upload a small text file and ask for a summary; add the demo MCP server and ask about order ORD-1001
   (`docs/DEMO_MCP.md`, with the host `demo-mcp`).
3. `docker compose -f docker-compose.prod.yml ps` shows `app` healthy, `mongodb` healthy.
4. Gemini quota: the free tier has low requests-per-minute limits; the first ingestion embeds about 2,350
   passages and may need minutes of retries. A paid key removes the wait. A 429 during a chat shows
   "The language model is rate limited right now ...".

### Operating it

```bash
docker compose -f docker-compose.prod.yml logs -f app        # application log (no secrets in it)
docker compose -f docker-compose.prod.yml restart app        # MCP configuration is in memory: it is lost
git pull && bash scripts/deploy.sh                           # update: rebuild and restart what changed
docker compose -f docker-compose.prod.yml down               # stop; volumes (database, certificates) stay
docker compose -f docker-compose.prod.yml down -v            # stop and WIPE the database and certificates
```

Backups: the database can be rebuilt from the corpus by re-ingesting; documents added through "Add to
knowledge base" live in `./corpus` on the host (back that folder up) and in the database.

### Security notes for a reachable deployment

- The whole application is behind one shared password (`AGENTFW_DEMO_PASSWORD`): use a long one.
- Cookies are `Secure`, `HttpOnly`, `SameSite=Lax`; Caddy adds HSTS and a content security policy.
- The MCP URL guard refuses private, loopback and link-local addresses (cloud metadata included) unless a
  host is listed in `AGENTFW_MCP_ALLOWED_HOSTS` (the compose demo profile sets `demo-mcp`).
- Secrets are in `.env` (mode 600, not in git) and in the container environment; they are not in the image.
- sslip.io is a public service that maps a name to an IP; the certificate is issued for that name by a
  public CA. If the shared-domain rate limits of Let's Encrypt are hit, Caddy falls back to ZeroSSL; with
  your own domain name this issue does not exist (set `DOMAIN` to it and point an A record at the VM).
