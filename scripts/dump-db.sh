#!/usr/bin/env bash
# Dumps the ingested chunks (text, metadata AND embeddings) of the local development database, so that a
# deployment can restore exactly this data and needs no embedding calls (and no Gemini quota) at deploy
# time. Search indexes are not part of a dump; the application creates them at startup.
#
#   scripts/dump-db.sh                               # -> deploy/seed/agentfw-chunks.archive.gz
#   scripts/dump-db.sh path/to/file.archive.gz
#
# Environment: MONGO_CONTAINER (default agent-framework-mongodb-1), MONGO_DB (default agentfw),
# DOCKER (default docker). Writes the archive, FILE.sha256 and FILE.manifest.json (what is in it).
# The dump contains the text of the corpus PDFs: it is gitignored; copy it to the VM with scp.
set -euo pipefail
export MSYS_NO_PATHCONV=1            # Git Bash on Windows must not rewrite /tmp paths

cd "$(dirname "$0")/.."
DOCKER="${DOCKER:-docker}"
CONTAINER="${MONGO_CONTAINER:-agent-framework-mongodb-1}"
DB="${MONGO_DB:-agentfw}"
OUT="${1:-deploy/seed/agentfw-chunks.archive.gz}"
TMP="/tmp/agentfw-dump.archive"

mkdir -p "$(dirname "$OUT")"
$DOCKER exec "$CONTAINER" mongodump --quiet --db "$DB" --collection chunks --archive="$TMP" --gzip
$DOCKER cp "$CONTAINER:$TMP" "$OUT"
$DOCKER exec "$CONTAINER" rm -f "$TMP"

# what the archive holds, so that a restore can be checked against it
$DOCKER exec "$CONTAINER" mongosh "$DB" --quiet --eval '
  JSON.stringify({
    createdAt: new Date().toISOString(),
    chunks: db.chunks.countDocuments(),
    documents: db.chunks.aggregate([
      { $group: { _id: "$docId", chunks: { $sum: 1 }, contentHash: { $first: "$contentHash" },
                  embeddingModel: { $first: "$embeddingModel" }, pipelineVersion: { $first: "$pipelineVersion" } } },
      { $sort: { _id: 1 } }]).toArray()
  }, null, 2)' > "$OUT.manifest.json"

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$(dirname "$OUT")" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
else
  (cd "$(dirname "$OUT")" && shasum -a 256 "$(basename "$OUT")" > "$(basename "$OUT").sha256")
fi

echo "dumped $(grep -c '"_id"' "$OUT.manifest.json") documents, $(grep -m1 '"chunks"' "$OUT.manifest.json" | tr -dc '0-9') chunks"
ls -l "$OUT" | awk '{printf "archive: %s (%.1f MB)\n", $NF, $5/1e6}'
echo "sha256:  $(cut -d' ' -f1 "$OUT.sha256")"
