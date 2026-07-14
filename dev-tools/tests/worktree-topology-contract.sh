#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/validation/report-worktree-pr-topology.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

BIN_DIR="$TEMP_DIR/bin"
VALID_WORKTREE="$TEMP_DIR/valid-worktree"
INACCESSIBLE_WORKTREE="$TEMP_DIR/inaccessible-worktree"
MISSING_WORKTREE="$TEMP_DIR/missing-worktree"
PRUNABLE_WORKTREE="$TEMP_DIR/prunable-worktree"
mkdir -p "$BIN_DIR" "$VALID_WORKTREE" "$INACCESSIBLE_WORKTREE"

cat > "$BIN_DIR/git" <<EOF
#!/usr/bin/env bash
set -euo pipefail

if [[ "\$1" == "worktree" && "\$2" == "list" ]]; then
  cat <<'WORKTREES'
worktree $VALID_WORKTREE
HEAD valid-head
branch refs/heads/valid-branch

worktree $INACCESSIBLE_WORKTREE
HEAD unavailable-head
branch refs/heads/unavailable-branch

worktree $MISSING_WORKTREE
HEAD missing-head
branch refs/heads/missing-branch

worktree $PRUNABLE_WORKTREE
HEAD prunable-head
branch refs/heads/prunable-branch
prunable gitdir file points to non-existent location

WORKTREES
  exit 0
fi

if [[ "\$1" == "-C" && "\$3" == "status" ]]; then
  case "\$2" in
    "$VALID_WORKTREE")
      printf ' M tracked-file\n'
      exit 0
      ;;
    "$INACCESSIBLE_WORKTREE")
      echo 'fatal: not a git repository' >&2
      exit 128
      ;;
    *)
      echo "unexpected worktree status path: \$2" >&2
      exit 1
      ;;
  esac
fi

if [[ "\$1" == "for-each-ref" ]]; then
  printf 'valid-branch|origin/valid-branch|valid-head|2026-07-14T00:00:00Z\n'
  exit 0
fi

echo "unexpected git invocation: \$*" >&2
exit 1
EOF

cat > "$BIN_DIR/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1" == "pr" && "$2" == "list" ]]; then
  printf '1\tvalid-branch\tdevelop\tCLEAN\tValid PR\thttps://example.test/pr/1\n'
  exit 0
fi

echo "unexpected gh invocation: $*" >&2
exit 1
EOF

chmod +x "$BIN_DIR/git" "$BIN_DIR/gh"

output_file="$TEMP_DIR/output"
error_file="$TEMP_DIR/error"
PATH="$BIN_DIR:$PATH" bash "$SCRIPT" --repo example/test > "$output_file" 2> "$error_file"

grep -Fqx $'PATH\tBRANCH\tHEAD\tSTATUS' "$output_file"
grep -Fqx "$VALID_WORKTREE"$'\tvalid-branch\tvalid-head\tdirty' "$output_file"
grep -Fqx "$INACCESSIBLE_WORKTREE"$'\tunavailable-branch\tunavailable-head\tunavailable' "$output_file"
grep -Fqx "$MISSING_WORKTREE"$'\tmissing-branch\tmissing-head\tmissing' "$output_file"
grep -Fqx "$PRUNABLE_WORKTREE"$'\tprunable-branch\tprunable-head\tprunable' "$output_file"
[[ ! -s "$error_file" ]]

echo "worktree topology contract checks passed"
