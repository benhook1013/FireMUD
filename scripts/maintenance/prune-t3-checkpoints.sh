#!/usr/bin/env bash
set -euo pipefail

KEEP=5
APPLY=0
RUN_GC=1
INSPECT_CODEX=1
CODEX_ROOT="${CODEX_ROOT:-$HOME/.codex}"

usage() {
  cat <<'EOF'
Inspect and optionally prune old T3 Code checkpoint refs while keeping the newest turns per thread.

Usage:
  scripts/maintenance/prune-t3-checkpoints.sh [options]

Options:
  --keep N             Keep the latest N checkpoint refs per thread. Default: 5.
  --apply              Delete older refs after reporting the plan. Default is report-only.
  --dry-run            Alias for the default report-only mode.
  --no-gc              Skip reflog expiry and git gc after deleting refs.
  --codex-root PATH    Inspect Codex state under PATH instead of ~/.codex.
  --no-codex-inspect   Skip local Codex-state inspection and classification.
  --help               Show this help text.

This script only considers refs matching:
  refs/t3/checkpoints/<thread-id>/turn/<integer>

It never touches normal branches, tags, remotes, or other refs.
EOF
}

die() {
  echo "Error: $*" >&2
  exit 1
}

validate_positive_integer() {
  local value="$1"
  [[ "$value" =~ ^[1-9][0-9]*$ ]]
}

inspect_codex_state() {
  local thread_file="$1"
  local output_file="$2"

  if ! command -v python3 >/dev/null 2>&1; then
    echo "Codex-state inspection unavailable: python3 not found." >&2
    return 1
  fi

  if ! python3 - "$CODEX_ROOT" "$thread_file" >"$output_file" <<'PY'
import base64
import json
import os
import sqlite3
import sys
from pathlib import Path

codex_root = Path(sys.argv[1]).expanduser()
thread_file = Path(sys.argv[2])
thread_ids = [line.strip() for line in thread_file.read_text().splitlines() if line.strip()]


def decode_thread_id(value: str):
    padded = value + "=" * ((4 - len(value) % 4) % 4)
    try:
        decoded = base64.urlsafe_b64decode(padded.encode("ascii"))
    except Exception:
        return None
    if len(decoded) == 16:
        try:
            import uuid
            return str(uuid.UUID(bytes=decoded))
        except Exception:
            return None
    try:
        text = decoded.decode("utf-8")
    except UnicodeDecodeError:
        return None
    return text if text and text.isprintable() else None


records = []
db_thread_ids = set()
db_path = codex_root / "state_5.sqlite"
db_status = "missing"
db_error = None
if db_path.exists():
    try:
        with sqlite3.connect(db_path) as conn:
            tables = {row[0] for row in conn.execute("select name from sqlite_master where type = 'table'")}
            if "threads" in tables:
                columns = {row[1] for row in conn.execute("pragma table_info(threads)")}
                if "id" in columns:
                    db_thread_ids = {
                        str(row[0]) for row in conn.execute("select id from threads") if row[0] is not None
                    }
                    db_status = "threads.id"
                else:
                    db_status = "threads table without id column"
            else:
                db_status = "threads table missing"
    except Exception as exc:
        db_status = "error"
        db_error = str(exc)

sessions_root = codex_root / "sessions"
session_hits = {thread_id: [] for thread_id in thread_ids}
session_status = "missing"
if sessions_root.exists():
    session_status = "scanned"
    search_tokens = {}
    for thread_id in thread_ids:
        tokens = {thread_id}
        decoded = decode_thread_id(thread_id)
        if decoded:
            tokens.add(decoded)
        search_tokens[thread_id] = tuple(token for token in tokens if token)

    for path in sessions_root.rglob("*"):
        if not path.is_file() or path.suffix not in {".json", ".jsonl"}:
            continue
        try:
            text = path.read_text(errors="ignore")
        except Exception:
            continue
        rel = str(path.relative_to(codex_root))
        for thread_id, tokens in search_tokens.items():
            if session_hits[thread_id]:
                continue
            if any(token in text for token in tokens):
                session_hits[thread_id].append(rel)

for thread_id in thread_ids:
    decoded = decode_thread_id(thread_id)
    exact_db = thread_id in db_thread_ids
    decoded_db = bool(decoded and decoded in db_thread_ids)
    session_paths = session_hits.get(thread_id, [])
    if exact_db or decoded_db:
        classification = "active-candidate"
    elif session_paths:
        classification = "archived-candidate"
    else:
        classification = "orphaned-candidate"
    details = []
    if exact_db:
        details.append("exact sqlite thread id match")
    if decoded_db:
        details.append(f"decoded sqlite thread id match ({decoded})")
    elif decoded:
        details.append(f"decoded id candidate: {decoded}")
    if session_paths:
        details.append(f"session metadata hit: {session_paths[0]}")
    if not details:
        details.append("no sqlite or session metadata match found")
    records.append(
        {
            "threadId": thread_id,
            "classification": classification,
            "details": "; ".join(details),
        }
    )

result = {
    "codexRoot": str(codex_root),
    "dbStatus": db_status,
    "dbError": db_error,
    "sessionStatus": session_status,
    "records": records,
}
print(json.dumps(result))
PY
  then
    echo "Codex-state inspection failed." >&2
    return 1
  fi

  return 0
}

while (($# > 0)); do
  case "$1" in
    --keep)
      (($# >= 2)) || die "--keep requires a value"
      KEEP="$2"
      shift 2
      ;;
    --apply)
      APPLY=1
      shift
      ;;
    --dry-run)
      APPLY=0
      shift
      ;;
    --no-gc)
      RUN_GC=0
      shift
      ;;
    --codex-root)
      (($# >= 2)) || die "--codex-root requires a value"
      CODEX_ROOT="$2"
      shift 2
      ;;
    --no-codex-inspect)
      INSPECT_CODEX=0
      shift
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

validate_positive_integer "$KEEP" || die "--keep must be a positive integer"

git rev-parse --git-dir >/dev/null 2>&1 || die "not inside a Git repository"

declare -A THREAD_ENTRIES=()
THREADS_FOUND=0
VALID_REFS=0
KEPT_REFS=0
DELETED_REFS=0
IGNORED_REFS=0

mapfile -t ALL_REFS < <(git for-each-ref --format='%(refname)' refs/t3/checkpoints)

for ref in "${ALL_REFS[@]}"; do
  if [[ "$ref" =~ ^refs/t3/checkpoints/([^/]+)/turn/([0-9]+)$ ]]; then
    thread_id="${BASH_REMATCH[1]}"
    turn_number="${BASH_REMATCH[2]}"
    THREAD_ENTRIES["$thread_id"]+="${turn_number}"$'\t'"${ref}"$'\n'
    VALID_REFS=$((VALID_REFS + 1))
  else
    IGNORED_REFS=$((IGNORED_REFS + 1))
    echo "Ignoring non-checkpoint-shaped ref under refs/t3/checkpoints: $ref" >&2
  fi
done

THREADS_FOUND="${#THREAD_ENTRIES[@]}"

if ((THREADS_FOUND == 0)); then
  echo "No T3 checkpoint refs found matching refs/t3/checkpoints/<thread-id>/turn/<integer>."
  echo "Threads found: 0"
  echo "Refs kept: 0"
  echo "Refs would delete: 0"
  exit 0
fi

if ((APPLY)); then
  echo "Apply mode: keeping latest $KEEP checkpoint refs per thread."
else
  echo "Report-only mode: keeping latest $KEEP checkpoint refs per thread."
fi

THREAD_FILE="$(mktemp)"
INSPECTION_FILE="$(mktemp)"
trap 'rm -f "$THREAD_FILE" "$INSPECTION_FILE"' EXIT
printf '%s\n' "${!THREAD_ENTRIES[@]}" | sort >"$THREAD_FILE"

if ((INSPECT_CODEX)); then
  echo
  echo "Codex-state candidate classification:"
  if inspect_codex_state "$THREAD_FILE" "$INSPECTION_FILE"; then
    python3 - "$INSPECTION_FILE" <<'PY'
import json
import sys
from collections import Counter

payload = json.load(open(sys.argv[1]))
print(f"  Codex root: {payload['codexRoot']}")
print(f"  SQLite status: {payload['dbStatus']}")
if payload.get("dbError"):
    print(f"  SQLite error: {payload['dbError']}")
print(f"  Session metadata status: {payload['sessionStatus']}")
counts = Counter(record["classification"] for record in payload["records"])
for key in ("active-candidate", "archived-candidate", "orphaned-candidate"):
    print(f"  {key}: {counts.get(key, 0)}")
for record in payload["records"]:
    print(f"  - {record['threadId']}: {record['classification']} ({record['details']})")
PY
  else
    if ((APPLY)); then
      die "Codex-state inspection is unavailable; rerun without --apply or pass --no-codex-inspect if you intentionally want to bypass local-state classification."
    fi
    echo "  Codex-state inspection unavailable; continuing with ref-only report."
  fi
else
  echo
  echo "Codex-state candidate classification skipped because --no-codex-inspect was provided."
fi

echo

while IFS= read -r thread_id; do
  [[ -n "$thread_id" ]] || continue

  mapfile -t sorted_entries < <(
    printf '%s' "${THREAD_ENTRIES[$thread_id]}" | awk 'NF' | sort -t $'\t' -k1,1n
  )

  count="${#sorted_entries[@]}"
  if ((count == 0)); then
    continue
  fi

  echo "Thread $thread_id: $count checkpoint ref(s)"

  if ((count <= KEEP)); then
    KEPT_REFS=$((KEPT_REFS + count))
    echo "  keeping all $count ref(s)"
    continue
  fi

  refs_to_delete=$((count - KEEP))
  refs_to_keep=$KEEP

  KEPT_REFS=$((KEPT_REFS + refs_to_keep))

  if ((APPLY)); then
    echo "  deleting $refs_to_delete older ref(s); keeping $refs_to_keep newest"
  else
    echo "  would delete $refs_to_delete older ref(s); keeping $refs_to_keep newest"
  fi

  for ((i = 0; i < refs_to_delete; i++)); do
    entry="${sorted_entries[$i]}"
    turn_number="${entry%%$'\t'*}"
    ref="${entry#*$'\t'}"
    if ((APPLY)); then
      echo "    deleting turn $turn_number: $ref"
      git update-ref -d "$ref"
    else
      echo "    would delete turn $turn_number: $ref"
    fi
    DELETED_REFS=$((DELETED_REFS + 1))
  done
done <"$THREAD_FILE"

echo
echo "Summary:"
echo "  Threads found: $THREADS_FOUND"
echo "  Valid checkpoint refs: $VALID_REFS"
echo "  Refs kept: $KEPT_REFS"
if ((APPLY)); then
  echo "  Refs deleted: $DELETED_REFS"
else
  echo "  Refs would delete: $DELETED_REFS"
fi
if ((IGNORED_REFS > 0)); then
  echo "  Ignored non-matching refs: $IGNORED_REFS"
fi

if ((APPLY == 0)); then
  echo "Report-only mode: skipping ref deletion, reflog expiry, and git gc."
  exit 0
fi

if ((DELETED_REFS == 0)); then
  echo "No refs deleted; skipping reflog expiry and git gc."
  exit 0
fi

if ((RUN_GC == 0)); then
  echo "Skipping reflog expiry and git gc because --no-gc was provided."
  exit 0
fi

echo "Expiring reflogs and pruning unreachable objects..."
git reflog expire --expire=now --all
git gc --prune=now
echo "Finished pruning T3 checkpoint refs."
