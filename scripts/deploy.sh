#!/usr/bin/env bash
# Deploys the application on a fresh Ubuntu 22.04 / 24.04 VM (x86_64): installs Docker, fetches the
# corpus, builds and starts the stack, waits until it is healthy and the corpus is ingested.
#
#   git clone <repo> && cd <repo>
#   scripts/deploy.sh                 # first run: creates .env and stops so you can fill in the secrets
#   $EDITOR .env                      # GOOGLE_API_KEY, AGENTFW_DEMO_PASSWORD, ACME_EMAIL
#   scripts/deploy.sh                 # second run: does the deployment
#
# Options:  --demo          also start the demo MCP server (scenario D)
#           --no-firewall   do not touch ufw
# Safe to run again: it rebuilds and restarts what changed and skips what is already done.
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f docker-compose.prod.yml)
WITH_DEMO=false
FIREWALL=true
for arg in "$@"; do
  case "$arg" in
    --demo) WITH_DEMO=true ;;
    --no-firewall) FIREWALL=false ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

say() { printf '\n==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

SUDO=""
if [[ $EUID -ne 0 ]]; then
  command -v sudo >/dev/null 2>&1 || die "run as root or install sudo"
  SUDO="sudo"
fi

# value of KEY in .env (empty if missing)
env_get() { grep -E "^$1=" .env 2>/dev/null | tail -1 | cut -d= -f2- || true; }
# set KEY=VALUE in .env (replace or append)
env_set() {
  if grep -qE "^$1=" .env; then
    sed -i "s|^$1=.*|$1=$2|" .env
  else
    printf '%s=%s\n' "$1" "$2" >> .env
  fi
}

# ---- 1. Docker ----------------------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  say "Installing Docker Engine and the compose plugin (official Docker apt repository)"
  $SUDO apt-get update -y
  $SUDO apt-get install -y ca-certificates curl gnupg
  $SUDO install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | $SUDO gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
  $SUDO chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    | $SUDO tee /etc/apt/sources.list.d/docker.list >/dev/null
  $SUDO apt-get update -y
  $SUDO apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  $SUDO systemctl enable --now docker
fi
if ! docker info >/dev/null 2>&1; then
  # the current user is not in the docker group yet: run docker through sudo for this script
  if [[ -n "$SUDO" ]] && $SUDO docker info >/dev/null 2>&1; then
    COMPOSE=($SUDO docker compose -f docker-compose.prod.yml)
    docker() { $SUDO docker "$@"; }
  else
    die "cannot talk to the Docker daemon"
  fi
fi

# ---- 2. .env ------------------------------------------------------------------------------------------
if [[ ! -f .env ]]; then
  cp .env.example .env
  chmod 600 .env
  say ".env created from .env.example"
fi
chmod 600 .env

if [[ -z "$(env_get DOMAIN)" ]]; then
  ip="$(curl -4 -fsS --max-time 10 https://api.ipify.org || true)"
  [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "could not detect the public IPv4 address; set DOMAIN in .env"
  env_set DOMAIN "${ip//./-}.sslip.io"
  say "DOMAIN set to ${ip//./-}.sslip.io (sslip.io resolves it to $ip, so a certificate can be issued without owning a domain)"
fi
# the container runs as the user who owns ./corpus (the invoking user even under sudo)
if [[ -z "$(env_get APP_UID)" ]]; then env_set APP_UID "${SUDO_UID:-$(id -u)}"; fi
if [[ -z "$(env_get APP_GID)" ]]; then env_set APP_GID "${SUDO_GID:-$(id -g)}"; fi
if $WITH_DEMO; then
  env_set COMPOSE_PROFILES demo
  if [[ -z "$(env_get AGENTFW_MCP_ALLOWED_HOSTS)" ]]; then env_set AGENTFW_MCP_ALLOWED_HOSTS demo-mcp; fi
fi

missing=()
for key in GOOGLE_API_KEY AGENTFW_DEMO_PASSWORD ACME_EMAIL; do
  value="$(env_get "$key")"
  if [[ -z "$value" || "$value" == your-* ]]; then missing+=("$key"); fi
done
if [[ ${#missing[@]} -gt 0 ]]; then
  echo
  echo "Fill in these values in $(pwd)/.env, then run scripts/deploy.sh again:"
  printf '  %s\n' "${missing[@]}"
  exit 1
fi

# ---- 3. corpus ----------------------------------------------------------------------------------------
say "Fetching the corpus PDFs (sha256-verified)"
CORPUS_DIR=corpus bash scripts/fetch-corpus.sh
chown -R "$(env_get APP_UID):$(env_get APP_GID)" corpus 2>/dev/null || $SUDO chown -R "$(env_get APP_UID):$(env_get APP_GID)" corpus

# ---- 4. firewall --------------------------------------------------------------------------------------
if $FIREWALL && command -v ufw >/dev/null 2>&1; then
  say "Firewall: allow SSH, HTTP and HTTPS only"
  $SUDO ufw allow 22/tcp >/dev/null
  $SUDO ufw allow 80/tcp >/dev/null
  $SUDO ufw allow 443/tcp >/dev/null
  $SUDO ufw allow 443/udp >/dev/null
  $SUDO ufw --force enable >/dev/null
  # Docker publishes ports by editing iptables itself, bypassing ufw: that is why docker-compose.prod.yml
  # publishes nothing except Caddy's 80 and 443.
fi

# ---- 5. build and start -------------------------------------------------------------------------------
say "Building the image (downloads the model and verifies its sha256) and starting the stack"
"${COMPOSE[@]}" up -d --build

# ---- 6. wait for health -------------------------------------------------------------------------------
say "Waiting for the application to be healthy"
cid="$("${COMPOSE[@]}" ps -q app)"
for i in $(seq 1 90); do
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo unknown)"
  if [[ "$status" == "healthy" ]]; then break; fi
  sleep 5
done
if [[ "$status" != "healthy" ]]; then
  "${COMPOSE[@]}" logs --tail=60 app
  die "the application did not become healthy (status: $status)"
fi

# ---- 7. wait for ingestion ----------------------------------------------------------------------------
say "Waiting for the corpus to be ingested (first run: a few minutes of embedding calls)"
for i in $(seq 1 120); do
  if "${COMPOSE[@]}" logs app 2>/dev/null | grep -q "Ingestion finished"; then
    "${COMPOSE[@]}" logs app 2>/dev/null | grep "Ingestion finished" | tail -1 | sed 's/^.*Ingestion finished/Ingestion finished/'
    break
  fi
  sleep 5
done
"${COMPOSE[@]}" logs app 2>/dev/null | grep -q "Ingestion finished" \
  || echo "Ingestion has not reported yet; follow it with: ${COMPOSE[*]} logs -f app"

domain="$(env_get DOMAIN)"
say "Done"
echo "  URL:      https://${domain}"
echo "  Sign in:  the AGENTFW_DEMO_PASSWORD from .env"
echo "  Logs:     ${COMPOSE[*]} logs -f app"
echo "  The first request to a new host name may take a few seconds while Caddy obtains the certificate."
if $WITH_DEMO; then
  echo "  Demo MCP: add http://demo-mcp:8099/mcp with header Authorization = Bearer $(env_get DEMO_MCP_TOKEN)"
fi
exit 0
