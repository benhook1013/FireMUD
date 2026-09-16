#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "worktree topology contract requires jq on PATH" >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/validation/report-worktree-pr-topology.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

BIN_DIR="$TEMP_DIR/bin"
DEFAULT_REPO="$TEMP_DIR/default-repo"
VALID_WORKTREE="$TEMP_DIR/valid-worktree"
INACCESSIBLE_WORKTREE="$TEMP_DIR/inaccessible-worktree"
MISSING_WORKTREE="$TEMP_DIR/missing-worktree"
PRUNABLE_WORKTREE="$TEMP_DIR/prunable-worktree"
BARE_WORKTREE="$TEMP_DIR/bare-worktree"
mkdir -p "$BIN_DIR" "$DEFAULT_REPO" "$VALID_WORKTREE" "$INACCESSIBLE_WORKTREE" "$BARE_WORKTREE"

cat > "$BIN_DIR/git" <<EOF
#!/usr/bin/env bash
set -euo pipefail

if [[ "\${1:-}" == "rev-parse" && "\${2:-}" == "--show-toplevel" ]]; then
  printf '%s\\n' "$DEFAULT_REPO"
  exit 0
fi

if [[ "\${1:-}" == "worktree" && "\${2:-}" == "list" && "\${3:-}" == "--porcelain" ]]; then
  cat <<'WORKTREES'
worktree $VALID_WORKTREE
HEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
branch refs/heads/valid-branch
locked valid worktree

worktree $INACCESSIBLE_WORKTREE
HEAD 1111111111111111111111111111111111111111
branch refs/heads/unavailable-branch

worktree $MISSING_WORKTREE
HEAD 2222222222222222222222222222222222222222
branch refs/heads/missing-branch

worktree $PRUNABLE_WORKTREE
HEAD 3333333333333333333333333333333333333333
branch refs/heads/prunable-branch
prunable gitdir file points to non-existent location

worktree $BARE_WORKTREE
bare

WORKTREES
  exit 0
fi

if [[ "\${1:-}" == "-C" && "\${3:-}" == "status" ]]; then
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

if [[ "\${1:-}" == "for-each-ref" ]]; then
  printf '%s\\n' 'valid-branch	aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa	origin/valid-branch	2026-07-14T00:00:00Z'
  printf '%s\\n' 'unavailable-branch	1111111111111111111111111111111111111111	-	2026-07-13T00:00:00Z'
  printf '%s\\n' 'missing-branch	2222222222222222222222222222222222222222	-	2026-07-12T00:00:00Z'
  printf '%s\\n' 'prunable-branch	3333333333333333333333333333333333333333	-	2026-07-11T00:00:00Z'
  exit 0
fi

echo "unexpected git invocation: \$*" >&2
exit 1
EOF

cat > "$BIN_DIR/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "repo" && "${2:-}" == "view" ]]; then
  printf '%s\n' '{"nameWithOwner":"example/test"}'
  exit 0
fi

if [[ "${1:-}" == "pr" && "${2:-}" == "list" ]]; then
  cat <<'PRS'
[{"number":1,"headRefName":"valid-branch","headRefOid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","baseRefName":"develop","baseRefOid":"cccccccccccccccccccccccccccccccccccccccc","headRepository":{"id":"R_kgDOexample","name":"test"},"headRepositoryOwner":{"login":"example"},"changedFiles":2,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Valid PR","url":"https://example.test/pr/1","isDraft":false}]
PRS
  exit 0
fi

echo "unexpected gh invocation: $*" >&2
exit 1
EOF

chmod +x "$BIN_DIR/git" "$BIN_DIR/gh"

NO_OPTIONS_BIN="$TEMP_DIR/no-options-bin"
NO_OPTIONS_ARGS="$TEMP_DIR/no-options-args"
mkdir -p "$NO_OPTIONS_BIN"
cat > "$NO_OPTIONS_BIN/python3" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$@" > "$NO_OPTIONS_ARGS"
EOF
chmod +x "$NO_OPTIONS_BIN/python3"
NO_OPTIONS_ARGS="$NO_OPTIONS_ARGS" PATH="$NO_OPTIONS_BIN:$PATH" bash "$SCRIPT" >/dev/null
[[ "$(wc -l < "$NO_OPTIONS_ARGS")" -eq 1 ]]
grep -Fqx "${SCRIPT%.sh}.py" "$NO_OPTIONS_ARGS"

output_file="$TEMP_DIR/output"
error_file="$TEMP_DIR/error"
PATH="$BIN_DIR:$PATH" bash "$SCRIPT" > "$output_file" 2> "$error_file"

grep -Fqx $'PATH\tBRANCH\tHEAD\tSTATUS' "$output_file"
grep -Fqx "$VALID_WORKTREE"$'\tvalid-branch\taaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\tdirty (locked)' "$output_file"
grep -Fqx "$INACCESSIBLE_WORKTREE"$'\tunavailable-branch\t1111111111111111111111111111111111111111\tunavailable' "$output_file"
grep -Fqx "$MISSING_WORKTREE"$'\tmissing-branch\t2222222222222222222222222222222222222222\tmissing' "$output_file"
grep -Fqx "$PRUNABLE_WORKTREE"$'\tprunable-branch\t3333333333333333333333333333333333333333\tprunable' "$output_file"
grep -Fqx "$BARE_WORKTREE"$'\t(bare)\t-\tbare' "$output_file"
[[ ! -s "$error_file" ]]

inventory_json="$(PATH="$BIN_DIR:$PATH" bash "$SCRIPT" --json)"
jq -e '.mode == "inventory" and .status == "ok" and .errors == []' <<<"$inventory_json" >/dev/null
jq -e '.worktrees | any(.bare and .head_sha == null and .status == "bare")' <<<"$inventory_json" >/dev/null

echo "worktree topology contract checks passed"

CONTRADICTORY_BIN="$TEMP_DIR/contradictory-bin"
mkdir -p "$CONTRADICTORY_BIN"
cat > "$CONTRADICTORY_BIN/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1 $2" == "pr list" || "$1 $2" == "pr view" ]]; then
  cat <<'PR'
[{"number":42,"headRefName":"feature/head","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","baseRefName":"develop","baseRefOid":"cccccccccccccccccccccccccccccccccccccccc","headRepository":{"id":"R_kgDOownerrepo","name":"repo","nameWithOwner":"owner/other-repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":3,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Contradictory","url":"https://example.test/pr/42","isDraft":false}]
PR
  exit 0
fi
echo "unexpected gh invocation: $*" >&2
exit 1
EOF
chmod +x "$CONTRADICTORY_BIN/gh"
if contradictory_json="$(cd "$DEFAULT_REPO" && PATH="$CONTRADICTORY_BIN:$BIN_DIR:$PATH" bash "$SCRIPT" --repo owner/repo --pr 42 --json)"; then
  echo "contradictory repository identity unexpectedly succeeded" >&2
  exit 1
fi
jq -e '.status == "ambiguous" and (.errors | any(contains("head repository identity fields contradict each other")))' <<<"$contradictory_json" >/dev/null

echo "contradictory repository identity contract checks passed"

SELECTED_REPO="$TEMP_DIR/selected-repo"
SELECTED_WORKTREE="$SELECTED_REPO/selected-worktree"
SELECTED_BARE="$SELECTED_REPO/selected-bare"
SELECTED_BIN="$TEMP_DIR/selected-bin"
mkdir -p "$SELECTED_REPO" "$SELECTED_WORKTREE" "$SELECTED_BARE" "$SELECTED_BIN"

cat > "$SELECTED_BIN/git" <<EOF
#!/usr/bin/env bash
set -euo pipefail

if [[ "\${1:-}" == "rev-parse" && "\${2:-}" == "--show-toplevel" ]]; then
  printf '%s\\n' "$SELECTED_REPO"
  exit 0
fi
if [[ "\${1:-}" == "worktree" && "\${2:-}" == "list" && "\${3:-}" == "--porcelain" ]]; then
  cat <<'WORKTREES'
worktree $SELECTED_WORKTREE
HEAD bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
branch refs/heads/feature/head
locked selected for contract

worktree $SELECTED_BARE
bare

WORKTREES
  exit 0
fi
if [[ "\$1" == "-C" && "\$3" == "status" ]]; then
  exit 0
fi
if [[ "\$1" == "for-each-ref" ]]; then
  printf '%s\\n' 'feature/head	bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb	origin/feature/head	2026-09-14T00:00:00+00:00'
  exit 0
fi
echo "unexpected git invocation: \$*" >&2
exit 1
EOF

cat > "$SELECTED_BIN/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1 $2" == "pr list" ]]; then
  cat <<'PRS'
[{"number":42,"headRefName":"feature/head","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","baseRefName":"develop","baseRefOid":"cccccccccccccccccccccccccccccccccccccccc","headRepository":{"id":"R_kgDOownerrepo","name":"repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":3,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Selected","url":"https://example.test/pr/42","isDraft":false},{"number":100,"headRefName":"feature/dependent","headRefOid":"dddddddddddddddddddddddddddddddddddddddd","baseRefName":"feature/head","baseRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","headRepository":{"id":"R_kgDOownerrepo","name":"repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":1,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Dependent","url":"https://example.test/pr/100","isDraft":false},{"number":50,"headRefName":"feature/grandchild","headRefOid":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","baseRefName":"feature/dependent","baseRefOid":"dddddddddddddddddddddddddddddddddddddddd","headRepository":{"id":"R_kgDOownerrepo","name":"repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":1,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Grandchild","url":"https://example.test/pr/50","isDraft":false},{"number":45,"headRefName":"renovate/dependent","headRefOid":"ffffffffffffffffffffffffffffffffffffffff","baseRefName":"feature/head","baseRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","headRepository":{"id":"R_kgDOownerrepo","name":"repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":1,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Renovate dependent","url":"https://example.test/pr/45","isDraft":false},{"number":44,"headRefName":"feature/forked","headRefOid":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","baseRefName":"feature/head","baseRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","headRepository":{"id":"R_kgDOforkrepo","name":"repo"},"headRepositoryOwner":{"login":"fork"},"changedFiles":1,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Foreign collision","url":"https://example.test/pr/44","isDraft":false},{"number":46,"headRefName":"renovate/unrelated","headRefOid":"1212121212121212121212121212121212121212","baseRefName":"develop","baseRefOid":"cccccccccccccccccccccccccccccccccccccccc","headRepository":{"id":"R_kgDOownerrepo","name":"repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":1,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Unrelated Renovate","url":"https://example.test/pr/46","isDraft":false}]
PRS
  exit 0
fi
if [[ "$1 $2" == "pr view" ]]; then
  cat <<'PR'
{"state":"OPEN","number":42,"headRefName":"feature/head","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","baseRefName":"develop","baseRefOid":"cccccccccccccccccccccccccccccccccccccccc","headRepository":{"id":"R_kgDOownerrepo","name":"repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":3,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Selected","url":"https://example.test/pr/42","isDraft":false}
PR
  exit 0
fi
echo "unexpected gh invocation: $*" >&2
exit 1
EOF

chmod +x "$SELECTED_BIN/git" "$SELECTED_BIN/gh"
selected_json="$(cd "$SELECTED_REPO" && PATH="$SELECTED_BIN:$PATH" bash "$SCRIPT" --repo owner/repo --pr 42 --json)"
jq -e '.mode == "selected-stack" and .status == "ok"' <<<"$selected_json" >/dev/null
jq -e '.chain | map(.number) == [42, 100, 50]' <<<"$selected_json" >/dev/null
jq -e '.chain[0].head.sha == "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"' <<<"$selected_json" >/dev/null
jq -e '.chain[0].local_head.status == "published"' <<<"$selected_json" >/dev/null
jq -e '.chain[0].worktrees | any(.locked and .status == "clean" and .head_matches)' <<<"$selected_json" >/dev/null
jq -e '.chain[0].worktrees | all(.head_sha != null)' <<<"$selected_json" >/dev/null
jq -e '.chain[1].base.sha == "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"' <<<"$selected_json" >/dev/null
jq -e '.chain[2].base.sha == "dddddddddddddddddddddddddddddddddddddddd"' <<<"$selected_json" >/dev/null
jq -e '.omitted_renovate | map(.number) == [45] and .[0].relation == "dependent" and .[0].linked_prs == [42]' <<<"$selected_json" >/dev/null

selected_human="$(cd "$SELECTED_REPO" && PATH="$SELECTED_BIN:$PATH" bash "$SCRIPT" --repo owner/repo --pr 42)"
grep -Fqx 'omitted Renovate PR #45 (dependent linked to #42; excluded from the default selected stack; use --include-renovate to include)' <<<"$selected_human"
if grep -Fq '#46' <<<"$selected_human"; then
  echo "unrelated Renovate PR was reported as omitted" >&2
  exit 1
fi

selected_renovate_json="$(cd "$SELECTED_REPO" && PATH="$SELECTED_BIN:$PATH" bash "$SCRIPT" --repo owner/repo --pr 42 --include-renovate --json)"
jq -e '.status == "ok" and (.chain | map(.number) | index(45) != null)' <<<"$selected_renovate_json" >/dev/null
jq -e '.omitted_renovate == []' <<<"$selected_renovate_json" >/dev/null

help_output="$(bash "$SCRIPT" --help)"
grep -Fqx 'Usage: report-worktree-pr-topology.sh [--repo OWNER/REPO] [--include-renovate] [--json]' <<<"$help_output"
grep -Fqx '       report-worktree-pr-topology.sh --pr N [--repo OWNER/REPO] [--include-renovate] [--json]' <<<"$help_output"
grep -Fqx 'machine-readable form for either inventory or selected-stack mode.' <<<"$help_output"
grep -Fq -- '--json' <<<"$help_output"
grep -Fq -- '--include-renovate' <<<"$help_output"
grep -Fq -- '--pr N' <<<"$help_output"
grep -Fq 'report-worktree-pr-topology.sh --json' "$ROOT_DIR/dev-tools/README.md"
grep -Fq 'report-worktree-pr-topology.sh --pr N --include-renovate --json' "$ROOT_DIR/dev-tools/README.md"

echo "selected worktree topology contract checks passed"

LIMIT_BIN="$TEMP_DIR/limit-bin"
mkdir -p "$LIMIT_BIN"
cat > "$LIMIT_BIN/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1 $2" == "pr list" ]]; then
  [[ " $* " == *" --limit 1000 "* ]] || {
    echo "paginated inventory must request --limit 1000" >&2
    exit 97
  }
  python3 - <<'PY'
import json

selected = {
    "number": 42,
    "headRefName": "feature/head",
    "headRefOid": "b" * 40,
    "baseRefName": "develop",
    "baseRefOid": "c" * 40,
    "headRepository": {"id": "R_kgDOownerrepo", "name": "repo"},
    "headRepositoryOwner": {"login": "owner"},
    "changedFiles": 3,
    "mergeable": "MERGEABLE",
    "mergeStateStatus": "CLEAN",
    "title": "Selected",
    "url": "https://example.test/pr/42",
    "isDraft": False,
}
prs = [selected]
for number in range(100, 200):
    if number == 143:
        continue
    prs.append(
        {
            "number": number,
            "headRefName": f"feature/unrelated-{number}",
            "headRefOid": f"{number:040x}",
            "baseRefName": "develop",
            "baseRefOid": "c" * 40,
            "headRepository": {"id": "R_kgDOownerrepo", "name": "repo"},
            "headRepositoryOwner": {"login": "owner"},
            "changedFiles": 1,
            "mergeable": "MERGEABLE",
            "mergeStateStatus": "CLEAN",
            "title": "Unrelated",
            "url": f"https://example.test/pr/{number}",
            "isDraft": False,
        }
    )
prs.append(
    {
        **selected,
        "number": 143,
        "headRefName": "feature/beyond-one-hundred",
        "headRefOid": "d" * 40,
        "baseRefName": "feature/head",
        "baseRefOid": "b" * 40,
        "title": "Dependent beyond one hundred",
        "url": "https://example.test/pr/143",
    }
)
print(json.dumps(prs))
PY
  exit 0
fi
if [[ "$1 $2" == "pr view" ]]; then
  cat <<'PR'
{"state":"OPEN","number":42,"headRefName":"feature/head","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","baseRefName":"develop","baseRefOid":"cccccccccccccccccccccccccccccccccccccccc","headRepository":{"id":"R_kgDOownerrepo","name":"repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":3,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Selected","url":"https://example.test/pr/42","isDraft":false}
PR
  exit 0
fi
echo "unexpected gh invocation: $*" >&2
exit 1
EOF
chmod +x "$LIMIT_BIN/gh"
over_one_hundred_json="$(cd "$SELECTED_REPO" && PATH="$LIMIT_BIN:$SELECTED_BIN:$PATH" bash "$SCRIPT" --repo owner/repo --pr 42 --json)"
jq -e '.status == "ok" and (.chain | map(.number) | index(143) != null)' <<<"$over_one_hundred_json" >/dev/null

over_one_hundred_human="$(cd "$SELECTED_REPO" && PATH="$LIMIT_BIN:$SELECTED_BIN:$PATH" bash "$SCRIPT" --repo owner/repo)"
grep -Fqx $'143\tfeature/beyond-one-hundred\tfeature/head\tCLEAN\tDependent beyond one hundred\thttps://example.test/pr/143' <<<"$over_one_hundred_human"

echo "paginated topology inventory contract checks passed"

CYCLE_BIN="$TEMP_DIR/cycle-bin"
mkdir -p "$CYCLE_BIN"
cat > "$CYCLE_BIN/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1 $2" == "pr list" ]]; then
  cat <<'PRS'
[{"number":42,"headRefName":"feature/head","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","baseRefName":"feature/dependent","baseRefOid":"dddddddddddddddddddddddddddddddddddddddd","headRepository":{"id":"R_kgDOownerrepo","name":"repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":3,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Selected","url":"https://example.test/pr/42","isDraft":false},{"number":100,"headRefName":"feature/dependent","headRefOid":"dddddddddddddddddddddddddddddddddddddddd","baseRefName":"feature/head","baseRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","headRepository":{"id":"R_kgDOownerrepo","name":"repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":1,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Cyclic dependent","url":"https://example.test/pr/100","isDraft":false}]
PRS
  exit 0
fi
if [[ "$1 $2" == "pr view" ]]; then
  cat <<'PR'
{"state":"OPEN","number":42,"headRefName":"feature/head","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","baseRefName":"feature/dependent","baseRefOid":"dddddddddddddddddddddddddddddddddddddddd","headRepository":{"id":"R_kgDOownerrepo","name":"repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":3,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Selected","url":"https://example.test/pr/42","isDraft":false}
PR
  exit 0
fi
echo "unexpected gh invocation: $*" >&2
exit 1
EOF
chmod +x "$CYCLE_BIN/gh"
if cycle_json="$(cd "$SELECTED_REPO" && PATH="$CYCLE_BIN:$SELECTED_BIN:$PATH" bash "$SCRIPT" --repo owner/repo --pr 42 --json)"; then
  echo "cyclic topology fixture unexpectedly succeeded" >&2
  exit 1
fi
jq -e '.status == "ambiguous" and (.errors | any(contains("directed PR dependency cycle")))' <<<"$cycle_json" >/dev/null

echo "cyclic topology contract checks passed"

FANOUT_BIN="$TEMP_DIR/fanout-bin"
mkdir -p "$FANOUT_BIN"
cat > "$FANOUT_BIN/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1 $2" == "pr list" ]]; then
  python3 - <<'PY'
import json

selected = {
    "number": 42,
    "headRefName": "feature/head",
    "headRefOid": "b" * 40,
    "baseRefName": "develop",
    "baseRefOid": "c" * 40,
    "headRepository": {"id": "R_kgDOownerrepo", "name": "repo"},
    "headRepositoryOwner": {"login": "owner"},
    "changedFiles": 3,
    "mergeable": "MERGEABLE",
    "mergeStateStatus": "CLEAN",
    "title": "Selected",
    "url": "https://example.test/pr/42",
    "isDraft": False,
}
prs = [selected]
for number in range(1000, 1051):
    prs.append(
        {
            "number": number,
            "headRefName": f"feature/child-{number}",
            "headRefOid": f"{number:040x}",
            "baseRefName": "feature/head",
            "baseRefOid": "b" * 40,
            "headRepository": {"id": "R_kgDOownerrepo", "name": "repo"},
            "headRepositoryOwner": {"login": "owner"},
            "changedFiles": 1,
            "mergeable": "MERGEABLE",
            "mergeStateStatus": "CLEAN",
            "title": "Fanout child",
            "url": f"https://example.test/pr/{number}",
            "isDraft": False,
        }
    )
print(json.dumps(prs))
PY
  exit 0
fi
if [[ "$1 $2" == "pr view" ]]; then
  cat <<'PR'
{"state":"OPEN","number":42,"headRefName":"feature/head","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","baseRefName":"develop","baseRefOid":"cccccccccccccccccccccccccccccccccccccccc","headRepository":{"id":"R_kgDOownerrepo","name":"repo"},"headRepositoryOwner":{"login":"owner"},"changedFiles":3,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","title":"Selected","url":"https://example.test/pr/42","isDraft":false}
PR
  exit 0
fi
echo "unexpected gh invocation: $*" >&2
exit 1
EOF
chmod +x "$FANOUT_BIN/gh"
if fanout_json="$(cd "$SELECTED_REPO" && PATH="$FANOUT_BIN:$SELECTED_BIN:$PATH" bash "$SCRIPT" --repo owner/repo --pr 42 --json)"; then
  echo "wide fanout topology fixture unexpectedly succeeded" >&2
  exit 1
fi
jq -e '.status == "ambiguous" and (.chain | length) == 50 and (.errors | index("selected stack exceeds the 50-PR bound") != null)' <<<"$fanout_json" >/dev/null

echo "wide fanout topology bound contract checks passed"
