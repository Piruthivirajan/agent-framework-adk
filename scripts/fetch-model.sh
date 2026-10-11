#!/usr/bin/env bash
# Downloads the cross-encoder files into MODEL_DIR and verifies each sha256. Used by the Dockerfile so
# the image has the model baked in (no download on the first request, no dependency on Hugging Face at
# runtime). The same commit and hashes are pinned in ModelFiles.java; a test fails if they drift apart.
#
#   MODEL_DIR=models/ms-marco-MiniLM-L-6-v2 scripts/fetch-model.sh
#
# Source: cross-encoder/ms-marco-MiniLM-L6-v2 (Apache-2.0), fp32 ONNX export plus its tokenizer.
set -euo pipefail

COMMIT="233902d25c440f23af6f7d6e94d2946bac0bee0a"
BASE="https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/${COMMIT}"
DEST="${MODEL_DIR:-models/ms-marco-MiniLM-L-6-v2}"

# name <TAB> path in the repository <TAB> sha256
FILES=(
  "model.onnx|onnx/model.onnx|5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a"
  "tokenizer.json|tokenizer.json|d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66"
)

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mkdir -p "$DEST"
for entry in "${FILES[@]}"; do
  IFS='|' read -r name path sha <<<"$entry"
  target="$DEST/$name"
  if [[ -f "$target" && "$(sha256_of "$target")" == "$sha" ]]; then
    echo "ok        $name"
    continue
  fi
  echo "download  $name"
  curl -fSL --retry 3 --retry-delay 2 -o "$target.part" "$BASE/$path"
  actual="$(sha256_of "$target.part")"
  if [[ "$actual" != "$sha" ]]; then
    echo "CHECKSUM MISMATCH $name: expected $sha, got $actual" >&2
    rm -f -- "$target.part"
    exit 1
  fi
  mv -- "$target.part" "$target"
  echo "ok        $name"
done
echo "model files complete in $DEST"
