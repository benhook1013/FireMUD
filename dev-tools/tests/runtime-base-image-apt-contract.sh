#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOCKERFILE="$ROOT_DIR/docker/base.Dockerfile"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/firemud-runtime-base-apt.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT

RUN_SCRIPT="$TEMP_DIR/docker-run.sh"
FAKE_BIN="$TEMP_DIR/bin"
FIREMUD_APT_MIRROR_LIST_FILE="$TEMP_DIR/ubuntu-archive-mirrors.txt"
mkdir -p "$FAKE_BIN"

python3 - "$DOCKERFILE" "$RUN_SCRIPT" <<'PY'
from pathlib import Path
import sys

dockerfile = Path(sys.argv[1])
output = Path(sys.argv[2])
lines = dockerfile.read_text(encoding="utf-8").splitlines()
instructions = []
index = 0

while index < len(lines):
    line = lines[index]
    if not line.startswith("RUN "):
        index += 1
        continue

    parts = [line[4:].rstrip()]
    while parts[-1].endswith("\\"):
        parts[-1] = parts[-1][:-1].rstrip()
        index += 1
        if index >= len(lines):
            raise SystemExit("Dockerfile RUN instruction ends unexpectedly")
        parts.append(lines[index].strip())
    instruction = " ".join(parts)
    if "apt-get" in instruction and "ubuntu.sources" in instruction:
        forbidden_options = (
            "AllowInsecureRepositories",
            "AllowDowngradeToInsecureRepositories",
            "AllowUnauthenticated",
            "Check-Valid-Until \"false\"",
            "Check-Date \"false\"",
            "trusted=yes",
            "Verify-Peer \"false\"",
            "Verify-Host \"false\"",
        )
        for option in forbidden_options:
            if option in instruction:
                raise SystemExit(f"insecure APT option is forbidden: {option}")
        instructions.append(instruction)
    index += 1

if len(instructions) != 1:
    raise SystemExit(
        f"expected one Ubuntu APT RUN instruction, found {len(instructions)}"
    )

canonical_source_path = "/etc/apt/sources.list.d/ubuntu.sources"
mirror_list_path = "/etc/apt/ubuntu-archive-mirrors.txt"
instruction = instructions[0]
if instruction.count(canonical_source_path) != 1:
    raise SystemExit("Dockerfile must use the canonical Ubuntu deb822 source path once")
if instruction.count(mirror_list_path) != 1:
    raise SystemExit("Dockerfile must use the canonical Ubuntu mirror-list path once")

# The fixture redirects the source and local mirror-list paths; APT logic stays intact.
instruction = instruction.replace(
    canonical_source_path, "$FIREMUD_APT_SOURCE_FILE", 1
)
instruction = instruction.replace(
    mirror_list_path, "$FIREMUD_APT_MIRROR_LIST_FILE", 1
)
if canonical_source_path in instruction or mirror_list_path in instruction:
    raise SystemExit("fixture scratch RUN still contains a production APT path")

output.write_text("#!/bin/sh\n" + instruction + "\n", encoding="utf-8")
PY

cat > "$FAKE_BIN/apt-get" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

args=("$@")
command=""
has_retry_option=false
has_error_on_any=false
has_curl=false

for ((index = 0; index < ${#args[@]}; index += 1)); do
  case "${args[index]}" in
    update|install)
      command="${args[index]}"
      ;;
    --error-on=any)
      has_error_on_any=true
      ;;
    curl)
      has_curl=true
      ;;
    -o)
      if ((index + 1 < ${#args[@]})) && [[ "${args[index + 1]}" == "Acquire::Retries=3" ]]; then
        has_retry_option=true
      fi
      ;;
  esac
done

[[ "$command" == update || "$command" == install ]] || {
  echo "fake apt-get received an unexpected command: $*" >&2
  exit 90
}
[[ "$has_retry_option" == true ]] || {
  echo "APT invocation is missing the bounded retry setting: $*" >&2
  exit 91
}

if [[ "$command" == update ]]; then
  [[ "$has_error_on_any" == true ]] || {
    echo "APT update is missing --error-on=any" >&2
    exit 92
  }
  case "$APT_EXPECTED_LAYOUT" in
    archive)
      grep -Fqx "URIs: mirror+file:$FIREMUD_APT_MIRROR_LIST_FILE" "$FIREMUD_APT_SOURCE_FILE"
      [[ $(grep -Fc "URIs: mirror+file:$FIREMUD_APT_MIRROR_LIST_FILE" "$FIREMUD_APT_SOURCE_FILE") -eq 1 ]]
      grep -Fqx 'URIs: https://security.ubuntu.com/ubuntu/' "$FIREMUD_APT_SOURCE_FILE"
      grep -Fqx 'Suites: resolute-security' "$FIREMUD_APT_SOURCE_FILE"
      grep -Fqx 'Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg' "$FIREMUD_APT_SOURCE_FILE"
      test -s "$FIREMUD_APT_MIRROR_LIST_FILE"
      grep -Fqx $'https://archive.ubuntu.com/ubuntu/\tpriority:1' "$FIREMUD_APT_MIRROR_LIST_FILE"
      grep -Fqx $'https://mirror.math.princeton.edu/pub/ubuntu/\tpriority:2' "$FIREMUD_APT_MIRROR_LIST_FILE"
      ;;
    ports)
      grep -Eq '^URIs:[[:space:]]*https://ports\.ubuntu\.com/ubuntu-ports/?[[:space:]]*$' "$FIREMUD_APT_SOURCE_FILE"
      grep -Fqx 'Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg' "$FIREMUD_APT_SOURCE_FILE"
      test ! -e "$FIREMUD_APT_MIRROR_LIST_FILE"
      ;;
    *)
      echo "fixture has an unexpected APT source layout: $APT_EXPECTED_LAYOUT" >&2
      exit 94
      ;;
  esac
  printf '%s\n' update >> "$APT_CALL_LOG"
  exit "$APT_UPDATE_EXIT"
fi

[[ "$has_error_on_any" == false && "$has_curl" == true ]] || {
  echo "APT install invocation must install curl without update-only options: $*" >&2
  exit 93
}
printf '%s\n' install >> "$APT_CALL_LOG"
exit "$APT_INSTALL_EXIT"
SH
chmod +x "$FAKE_BIN/apt-get"

for command in rm groupadd useradd; do
  cat > "$FAKE_BIN/$command" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "${0##*/}" >> "$AUX_CALL_LOG"
SH
  chmod +x "$FAKE_BIN/$command"
done

cat > "$TEMP_DIR/sources.input" <<'SOURCES'
Types: deb
URIs: http://archive.ubuntu.com/ubuntu/
Suites: resolute resolute-updates resolute-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: http://security.ubuntu.com/ubuntu/
Suites: resolute-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
SOURCES

cat > "$TEMP_DIR/sources.expected" <<SOURCES
Types: deb
URIs: mirror+file:$FIREMUD_APT_MIRROR_LIST_FILE
Suites: resolute resolute-updates resolute-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: https://security.ubuntu.com/ubuntu/
Suites: resolute-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
SOURCES

cat > "$TEMP_DIR/sources.ports.input" <<'SOURCES'
Types: deb
URIs: http://ports.ubuntu.com/ubuntu-ports/
Suites: resolute resolute-updates resolute-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: http://ports.ubuntu.com/ubuntu-ports/
Suites: resolute-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
SOURCES

cat > "$TEMP_DIR/sources.ports.expected" <<'SOURCES'
Types: deb
URIs: https://ports.ubuntu.com/ubuntu-ports/
Suites: resolute resolute-updates resolute-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: https://ports.ubuntu.com/ubuntu-ports/
Suites: resolute-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
SOURCES

run_dockerfile_instruction() {
  local source_file="$1"
  local update_exit="$2"
  local install_exit="$3"
  local apt_call_log="$4"
  local aux_call_log="$5"
  local expected_layout="$6"

  FIREMUD_APT_SOURCE_FILE="$source_file" \
    APT_UPDATE_EXIT="$update_exit" \
    APT_INSTALL_EXIT="$install_exit" \
    APT_EXPECTED_LAYOUT="$expected_layout" \
    APT_CALL_LOG="$apt_call_log" \
    AUX_CALL_LOG="$aux_call_log" \
    FIREMUD_APT_MIRROR_LIST_FILE="$FIREMUD_APT_MIRROR_LIST_FILE" \
    PATH="$FAKE_BIN:$PATH" \
    /bin/sh "$RUN_SCRIPT"
}

cp "$TEMP_DIR/sources.input" "$TEMP_DIR/sources.success"
: > "$TEMP_DIR/success-apt.log"
: > "$TEMP_DIR/success-aux.log"
run_dockerfile_instruction \
  "$TEMP_DIR/sources.success" 0 0 \
  "$TEMP_DIR/success-apt.log" "$TEMP_DIR/success-aux.log" archive
cmp "$TEMP_DIR/sources.expected" "$TEMP_DIR/sources.success"
printf 'https://archive.ubuntu.com/ubuntu/\tpriority:1\nhttps://mirror.math.princeton.edu/pub/ubuntu/\tpriority:2\n' \
  > "$TEMP_DIR/mirrorlist.expected"
cmp "$TEMP_DIR/mirrorlist.expected" "$FIREMUD_APT_MIRROR_LIST_FILE"
printf 'update\ninstall\n' > "$TEMP_DIR/calls.expected"
printf 'rm\ngroupadd\nuseradd\n' > "$TEMP_DIR/aux-calls.expected"

python3 - "$TEMP_DIR/sources.input" "$TEMP_DIR/sources.whitespace.input" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
source = source.replace(
    "URIs: http://archive.ubuntu.com/ubuntu/",
    "URIs:\t  http://archive.ubuntu.com/ubuntu/ \t  ",
    1,
)
Path(sys.argv[2]).write_text(source, encoding="utf-8")
PY
cp "$TEMP_DIR/sources.whitespace.input" "$TEMP_DIR/sources.whitespace.success"
: > "$TEMP_DIR/whitespace-success-apt.log"
: > "$TEMP_DIR/whitespace-success-aux.log"
run_dockerfile_instruction \
  "$TEMP_DIR/sources.whitespace.success" 0 0 \
  "$TEMP_DIR/whitespace-success-apt.log" "$TEMP_DIR/whitespace-success-aux.log" archive
cmp "$TEMP_DIR/sources.expected" "$TEMP_DIR/sources.whitespace.success"
cmp "$TEMP_DIR/mirrorlist.expected" "$FIREMUD_APT_MIRROR_LIST_FILE"
cmp "$TEMP_DIR/calls.expected" "$TEMP_DIR/whitespace-success-apt.log"
cmp "$TEMP_DIR/aux-calls.expected" "$TEMP_DIR/whitespace-success-aux.log"

cmp "$TEMP_DIR/calls.expected" "$TEMP_DIR/success-apt.log"
cmp "$TEMP_DIR/aux-calls.expected" "$TEMP_DIR/success-aux.log"

cp "$TEMP_DIR/sources.ports.input" "$TEMP_DIR/sources.ports.success"
rm -f "$FIREMUD_APT_MIRROR_LIST_FILE"
: > "$TEMP_DIR/ports-success-apt.log"
: > "$TEMP_DIR/ports-success-aux.log"
run_dockerfile_instruction \
  "$TEMP_DIR/sources.ports.success" 0 0 \
  "$TEMP_DIR/ports-success-apt.log" "$TEMP_DIR/ports-success-aux.log" ports
cmp "$TEMP_DIR/sources.ports.expected" "$TEMP_DIR/sources.ports.success"
cmp "$TEMP_DIR/calls.expected" "$TEMP_DIR/ports-success-apt.log"
cmp "$TEMP_DIR/aux-calls.expected" "$TEMP_DIR/ports-success-aux.log"

cp "$TEMP_DIR/sources.input" "$TEMP_DIR/sources.update-failure"
: > "$TEMP_DIR/update-failure-apt.log"
: > "$TEMP_DIR/update-failure-aux.log"
if run_dockerfile_instruction \
  "$TEMP_DIR/sources.update-failure" 17 0 \
  "$TEMP_DIR/update-failure-apt.log" "$TEMP_DIR/update-failure-aux.log" archive \
  2>"$TEMP_DIR/update-failure.stderr"; then
  echo "Dockerfile APT RUN unexpectedly succeeded when update failed" >&2
  exit 1
fi
printf 'update\n' > "$TEMP_DIR/update-only.expected"
cmp "$TEMP_DIR/update-only.expected" "$TEMP_DIR/update-failure-apt.log"
test ! -s "$TEMP_DIR/update-failure-aux.log"

python3 - "$TEMP_DIR/sources.input" "$TEMP_DIR/sources.missing-security" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
filtered = [
    line for line in source
    if not line.startswith("URIs: http://security.ubuntu.com/ubuntu")
]
Path(sys.argv[2]).write_text("\n".join(filtered) + "\n", encoding="utf-8")
PY
: > "$TEMP_DIR/missing-source-apt.log"
: > "$TEMP_DIR/missing-source-aux.log"
if run_dockerfile_instruction \
  "$TEMP_DIR/sources.missing-security" 0 0 \
  "$TEMP_DIR/missing-source-apt.log" "$TEMP_DIR/missing-source-aux.log" archive \
  2>"$TEMP_DIR/missing-source.stderr"; then
  echo "Dockerfile APT RUN unexpectedly accepted a missing canonical source" >&2
  exit 1
fi
test ! -s "$TEMP_DIR/missing-source-apt.log"
test ! -s "$TEMP_DIR/missing-source-aux.log"

echo "Runtime base-image APT contract passed."
