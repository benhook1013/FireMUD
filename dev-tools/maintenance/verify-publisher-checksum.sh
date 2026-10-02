#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 <digest|manifest> <checksum-file> <artifact> <publisher-asset-name>" >&2
  exit 2
fi

format="$1"
checksum_file="$2"
artifact="$3"
asset_name="$4"
[[ "$asset_name" == "$(basename -- "$asset_name")" && "$asset_name" != . && "$asset_name" != .. ]] || {
  echo "publisher asset name must be one filename" >&2
  exit 2
}
[[ -f "$checksum_file" && -f "$artifact" ]] || {
  echo "publisher checksum and downloaded artifact must both be regular files" >&2
  exit 2
}

expected=""
case "$format" in
  digest)
    mapfile -t rows < "$checksum_file"
    if [[ "${#rows[@]}" -ne 1 || ! "${rows[0]}" =~ ^[0-9a-f]{64}$ ]]; then
      echo "publisher checksum must contain exactly one lowercase SHA-256" >&2
      exit 2
    fi
    expected="${rows[0]}"
    ;;
  manifest)
    matches=()
    while IFS= read -r row || [[ -n "$row" ]]; do
      row="${row%$'\r'}"
      if [[ ! "$row" =~ ^([0-9a-f]{64})[[:space:]]+\*?([^[:space:]].*)$ ]]; then
        echo "publisher checksum manifest contains a malformed row" >&2
        exit 2
      fi
      if [[ "${BASH_REMATCH[2]}" == "$asset_name" ]]; then
        matches+=("${BASH_REMATCH[1]}")
      fi
    done < "$checksum_file"
    if [[ "${#matches[@]}" -ne 1 ]]; then
      echo "publisher checksum manifest must name ${asset_name} exactly once" >&2
      exit 2
    fi
    expected="${matches[0]}"
    ;;
  *)
    echo "checksum format must be digest or manifest" >&2
    exit 2
    ;;
esac

actual="$(sha256sum --binary "$artifact")"
actual="${actual%% *}"
if [[ "$actual" != "$expected" ]]; then
  echo "publisher checksum does not match ${asset_name}" >&2
  exit 1
fi
