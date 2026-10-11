#!/usr/bin/env bash
# Restores the chunks collection from an archive made by scripts/dump-db.sh into a MongoDB container,
# REPLACING what is there. Afterwards the application creates the search indexes at startup and its
# idempotent ingestion finds every document up to date (same file hash, embedding model and chunking
# version), so nothing is embedded. If a corpus file differs from the dumped one, only that document is
# re-ingested: normal ingestion stays the fallback.
#
#   scripts/restore-db.sh deploy/seed/agentfw-chunks.archive.gz [container-name-or-id]
#
# Environment: MONGO_CONTAINER (default agent-framework-mongodb-1), MONGO_DB (default agentfw),
# DOCKER (default docker; deploy.sh sets "sudo docker" when needed).
set -euo pipefail
export MSYS_NO_PATHCONV=1

DOCKER="${DOCKER:-docker}"
ARCHIVE="${1:?usage: scripts/restore-db.sh ARCHIVE [container]}"
CONTAINER="${2:-${MONGO_CONTAINER:-agent-framework-mongodb-1}}"
DB="${MONGO_DB:-agentfw}"

[[ -f "$ARCHIVE" ]] || { echo "archive not found: $ARCHIVE" >&2; exit 1; }

if [[ -f "$ARCHIVE.sha256" ]]; then
  expected="$(cut -d' ' -f1 "$ARCHIVE.sha256")"
  if command -v sha256sum >/dev/null 2>&1; then actual="$(sha256sum "$ARCHIVE" | cut -d' ' -f1)"
  else actual="$(shasum -a 256 "$ARCHIVE" | cut -d' ' -f1)"; fi
  [[ "$expected" == "$actual" ]] || { echo "CHECKSUM MISMATCH for $ARCHIVE" >&2; exit 1; }
  echo "checksum ok"
fi

# the archive goes in through stdin: no temporary file in the container (a file copied in with
# `docker cp` is owned by root and could not be removed by the database user)
$DOCKER exec -i "$CONTAINER" mongorestore --quiet --archive --gzip --drop   --nsInclude 'agentfw.chunks' --nsFrom 'agentfw.*' --nsTo "$DB.*" < "$ARCHIVE"

$DOCKER exec "$CONTAINER" mongosh "$DB" --quiet --eval \
  'print("restored: " + db.chunks.countDocuments() + " chunks, " + db.chunks.distinct("docId").length + " documents")'
