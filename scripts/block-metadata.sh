#!/usr/bin/env bash
# Drops everything that containers send to the cloud metadata address (169.254.169.254).
#
# That address hands out the VM's credentials to whoever asks from inside the VM. The application checks
# the addresses of MCP servers, but a check in the application cannot stop a DNS name that changes
# between the check and the connection, or a redirect. A firewall rule below the application can.
#
# The rule goes into Docker's DOCKER-USER chain, which Docker evaluates first for all container traffic
# and never overwrites. It does not affect the host itself (the VM's own tools keep working).
#
#   sudo scripts/block-metadata.sh                    # add the rule now (idempotent)
#   sudo scripts/block-metadata.sh --install-service  # also re-add it at every boot / Docker restart
#   scripts/block-metadata.sh --dry-run               # print what would be run, change nothing
#   METADATA_ADDR=...                                 # another address to block (default 169.254.169.254)
#
# Not covered: IPv6 metadata endpoints (for example fd00:ec2::254 on AWS): add the same rule with
# ip6tables if the VM has IPv6 egress.
set -euo pipefail

ADDR="${METADATA_ADDR:-169.254.169.254}"
DRY=false
INSTALL=false
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY=true ;;
    --install-service) INSTALL=true ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
UNIT=/etc/systemd/system/agentfw-block-metadata.service

run() {
  if $DRY; then echo "+ $*"; else "$@"; fi
}

apply_rule() {
  if $DRY; then
    echo "+ iptables -C DOCKER-USER -d $ADDR -j DROP   (skip the next line if it exists)"
    echo "+ iptables -I DOCKER-USER 1 -d $ADDR -j DROP"
    return
  fi
  command -v iptables >/dev/null 2>&1 || { echo "iptables is not installed: nothing blocked" >&2; exit 1; }
  # Docker creates the chain when its daemon starts; at boot this may run a moment earlier
  for _ in $(seq 1 30); do
    iptables -n -L DOCKER-USER >/dev/null 2>&1 && break
    sleep 2
  done
  iptables -n -L DOCKER-USER >/dev/null 2>&1 \
    || { echo "the DOCKER-USER chain does not exist: is Docker running?" >&2; exit 1; }
  if iptables -C DOCKER-USER -d "$ADDR" -j DROP 2>/dev/null; then
    echo "already present: DOCKER-USER drops traffic to $ADDR"
  else
    iptables -I DOCKER-USER 1 -d "$ADDR" -j DROP
    echo "added: DOCKER-USER drops traffic to $ADDR"
  fi
}

install_service() {
  local unit
  unit="[Unit]
Description=Block container access to the cloud metadata address ($ADDR)
After=docker.service
Requires=docker.service
PartOf=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
Environment=METADATA_ADDR=$ADDR
ExecStart=/usr/bin/env bash $SELF

[Install]
WantedBy=multi-user.target
"
  if $DRY; then
    echo "+ write $UNIT:"
    printf '%s\n' "$unit"
    echo "+ systemctl daemon-reload && systemctl enable agentfw-block-metadata.service"
    return
  fi
  printf '%s\n' "$unit" > "$UNIT"
  systemctl daemon-reload
  systemctl enable agentfw-block-metadata.service
  echo "installed $UNIT (re-applies the rule at boot and when Docker restarts)"
}

apply_rule
if $INSTALL; then
  install_service
fi
