#!/usr/bin/env bash
# Downloads the corpus PDFs listed in scripts/corpus-manifest.tsv into corpus/ and verifies each sha256.
#
#   scripts/fetch-corpus.sh           download what is missing or has the wrong hash
#   scripts/fetch-corpus.sh --check   only report status, download nothing
#
# CORPUS_DIR overrides the target folder (default: corpus). Needs bash, curl and sha256sum (or shasum).
# Works in Git Bash on Windows and in the Linux deployment image. Exit code 1 if any file is missing or wrong.
set -euo pipefail

cd "$(dirname "$0")/.."
MANIFEST="scripts/corpus-manifest.tsv"
DEST="${CORPUS_DIR:-corpus}"
CHECK_ONLY=false
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=true

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mkdir -p "$DEST"
bad=0
while IFS=$'\t' read -r sha file url; do
  [[ -z "${sha}" || "${sha}" == \#* ]] && continue
  target="$DEST/$file"

  if [[ -f "$target" ]]; then
    actual="$(sha256_of "$target")"
    if [[ "$actual" == "$sha" ]]; then echo "ok        $file"; continue; fi
    echo "WRONG HASH $file (have ${actual:0:12}..., want ${sha:0:12}...)" >&2
    if $CHECK_ONLY; then bad=$((bad + 1)); continue; fi
  elif $CHECK_ONLY; then
    echo "MISSING   $file" >&2; bad=$((bad + 1)); continue
  fi

  echo "download  $file"
  part="$target.part"
  curl -fSL --retry 3 --retry-delay 2 -o "$part" "$url"
  actual="$(sha256_of "$part")"
  if [[ "$actual" != "$sha" ]]; then
    echo "CHECKSUM MISMATCH $file: expected $sha, got $actual" >&2
    rm -f -- "$part"
    bad=$((bad + 1))
    continue
  fi
  mv -- "$part" "$target"
  echo "ok        $file"
done < "$MANIFEST"

if [[ $bad -gt 0 ]]; then echo "$bad file(s) missing or wrong" >&2; exit 1; fi
echo "corpus complete in $DEST"
