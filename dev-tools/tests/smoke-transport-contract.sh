#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_OWNED_COMPOSE_HELPER="$ROOT_DIR/dev-tools/smoke/run-owned-compose.sh"

python3 - <<'PY' "$ROOT_DIR"
import sys
import ssl
from pathlib import Path
from unittest.mock import patch

root = Path(sys.argv[1])
sys.path.insert(0, str(root / "dev-tools" / "smoke"))

import smoke_common
from smoke_common import (
    open_telnet_socket,
    run_telnet_smoke_session,
    run_transport_session,
    run_websocket_smoke_session,
)


class FakeSession:
    def __init__(self, chunks=None):
        self.chunks = list(chunks or [])
        self.sent = []
        self.closed = False

    def sendall(self, payload):
        self.sent.append(payload.decode("iso-8859-1"))

    def recv(self, _size=None):
        if self.chunks:
            chunk = self.chunks.pop(0)
            return chunk if _size is None else chunk.encode("iso-8859-1")
        return "" if _size is None else b""

    def settimeout(self, _timeout):
        return None

    def send(self, payload):
        self.sent.append(payload)


    def close(self):
        self.closed = True


class CommandResponseSession(FakeSession):
    def __init__(self, responses):
        super().__init__()
        self.responses = list(responses)

    def sendall(self, payload):
        super().sendall(payload)
        self.chunks = [self.responses.pop(0)]

    def send(self, payload):
        super().send(payload)
        self.chunks = [self.responses.pop(0)]


opened = []


class FakeTlsContext:
    def __init__(self, wrapped=None, failure=None):
        self.wrapped = wrapped or FakeSession()
        self.failure = failure
        self.check_hostname = False
        self.verify_mode = ssl.CERT_NONE
        self.loaded_ca_files = []
        self.wrap_calls = []

    def load_verify_locations(self, *, cafile):
        self.loaded_ca_files.append(cafile)

    def wrap_socket(self, raw_socket, *, server_hostname):
        self.wrap_calls.append((raw_socket, server_hostname))
        if self.failure is not None:
            raise self.failure
        return self.wrapped


raw_tls_socket = FakeSession()
wrapped_tls_socket = FakeSession()
tls_context = FakeTlsContext(wrapped=wrapped_tls_socket)
with patch("smoke_common.socket.create_connection", return_value=raw_tls_socket) as connect, patch(
    "smoke_common.ssl.create_default_context", return_value=tls_context
):
    result = open_telnet_socket(
        "203.0.113.10",
        2323,
        1,
        tls_enabled=True,
        tls_server_hostname="preview.example.test",
        tls_ca_file="ca.pem",
    )
assert result is wrapped_tls_socket
assert connect.call_count == 1
assert tls_context.loaded_ca_files == ["ca.pem"]
assert tls_context.wrap_calls == [(raw_tls_socket, "preview.example.test")]
assert tls_context.check_hostname is True
assert tls_context.verify_mode == ssl.CERT_REQUIRED
assert raw_tls_socket.closed is False


failed_raw_tls_socket = FakeSession()
failed_tls_context = FakeTlsContext(failure=ssl.SSLError("certificate mismatch"))
with patch(
    "smoke_common.socket.create_connection", return_value=failed_raw_tls_socket
) as connect, patch(
    "smoke_common.ssl.create_default_context", return_value=failed_tls_context
):
    try:
        open_telnet_socket(
            "203.0.113.10",
            2323,
            1,
            tls_enabled=True,
            tls_server_hostname="preview.example.test",
        )
    except ssl.SSLError:
        pass
    else:
        raise AssertionError("TLS failure unexpectedly succeeded")
assert failed_raw_tls_socket.closed is True
assert connect.call_count == 1


try:
    open_telnet_socket("example.test", 2323, 1)
except TypeError as exc:
    assert "tls_enabled" in str(exc)
else:
    raise AssertionError("Telnet socket helper accepted an omitted TLS mode")

for invalid_tls_enabled in (None, "true", 0, 1, [], {}):
    try:
        open_telnet_socket(
            "example.test", 2323, 1, tls_enabled=invalid_tls_enabled
        )
    except TypeError as exc:
        assert "tls_enabled" in str(exc)
    else:
        raise AssertionError(
            f"Telnet socket helper accepted non-boolean TLS mode: {invalid_tls_enabled!r}"
        )

for invalid_options in (
    {"tls_enabled": True},
    {"tls_enabled": False, "tls_server_hostname": "example.test"},
    {"tls_enabled": False, "tls_ca_file": "ca.pem"},
):
    try:
        open_telnet_socket("example.test", 2323, 1, **invalid_options)
    except ValueError:
        pass
    else:
        raise AssertionError(f"invalid Telnet TLS options were accepted: {invalid_options}")


def open_telnet():
    session = FakeSession(["OK WORLDS\n"])
    opened.append(session)
    return session


telnet_responses = run_telnet_smoke_session(
    "example.test",
    2323,
    [("WORLDS", ["OK WORLDS"], "WORLDS")],
    1,
    open_session=open_telnet,
    tls_enabled=False,
)
assert telnet_responses == ["OK WORLDS\n"]
assert opened[0].sent == ["WORLDS\r\n"]
assert opened[0].closed is True


opened_ws = []


def open_ws():
    session = FakeSession(["OK LOGIN"])
    opened_ws.append(session)
    return session


ws_responses = run_websocket_smoke_session(
    open_ws,
    [("LOGIN demo swordfish", ["OK LOGIN"], "LOGIN")],
    1,
    session_label="contract websocket",
)
assert ws_responses == ["OK LOGIN"]
assert opened_ws[0].sent == ["LOGIN demo swordfish"]
assert opened_ws[0].closed is True


attempts = {"count": 0}


def open_after_retry():
    attempts["count"] += 1
    if attempts["count"] == 1:
        raise OSError("temporary failure")
    return FakeSession()


result = run_transport_session(
    open_after_retry,
    lambda session: session,
    "retrying session",
    retry_window_seconds=1,
    retry_interval_seconds=0,
)
assert isinstance(result, FakeSession)
assert attempts["count"] == 2


for transient_failure in (True, False):
    deadline_attempts = {"count": 0}

    def open_until_deadline():
        deadline_attempts["count"] += 1
        if transient_failure:
            return FakeSession()
        raise OSError("temporary failure")

    def fail_until_deadline(_session):
        raise smoke_common.TransientUpstreamSmokeFailure("temporary upstream failure")

    with patch("smoke_common.time.time", side_effect=[0, 0, 1]), patch(
        "smoke_common.time.sleep"
    ) as sleep:
        try:
            run_transport_session(
                open_until_deadline,
                fail_until_deadline,
                "deadline-bound session",
                retry_window_seconds=1,
                retry_interval_seconds=2,
            )
            raise AssertionError("deadline-bound retry unexpectedly opened another session")
        except (smoke_common.TransientUpstreamSmokeFailure, RuntimeError):
            pass
    sleep.assert_called_once_with(1)
    assert deadline_attempts["count"] == 1


upstream_attempts = []


def open_after_transient_upstream_failure():
    responses = (
        ["ERROR UNAVAILABLE Login is temporarily unavailable."]
        if not upstream_attempts
        else ["OK LOGIN"]
    )
    session = FakeSession(responses)
    upstream_attempts.append(session)
    return session


upstream_responses = run_telnet_smoke_session(
    "example.test",
    2323,
    [("LOGIN demo swordfish", ["OK LOGIN"], "LOGIN")],
    1,
    open_session=open_after_transient_upstream_failure,
    retry_window_seconds=1,
    retry_interval_seconds=0,
    tls_enabled=False,
)
assert upstream_responses == ["OK LOGIN"]
assert len(upstream_attempts) == 2
assert all(session.closed for session in upstream_attempts)


for transport in ("telnet", "websocket"):
    later_failure_attempts = []

    def open_later_failure():
        session = CommandResponseSession(
            ["OK LOGIN", "ERROR UPSTREAM_FAILURE Gameplay unavailable."]
        )
        later_failure_attempts.append(session)
        return session

    try:
        steps = [
            ("LOGIN demo swordfish", ["OK LOGIN"], "LOGIN"),
            ("LOOK", ["A room"], "LOOK"),
        ]
        if transport == "telnet":
            run_telnet_smoke_session(
                "example.test",
                2323,
                steps,
                1,
                open_session=open_later_failure,
                retry_window_seconds=1,
                retry_interval_seconds=0,
                tls_enabled=False,
            )
        else:
            run_websocket_smoke_session(
                open_later_failure,
                steps,
                1,
                retry_window_seconds=1,
                retry_interval_seconds=0,
            )
        raise AssertionError(f"{transport} later-step failure unexpectedly retried")
    except RuntimeError as exc:
        assert not isinstance(exc, smoke_common.TransientUpstreamSmokeFailure)
        assert "ERROR UPSTREAM_FAILURE" in str(exc)
    assert len(later_failure_attempts) == 1
    assert later_failure_attempts[0].closed is True


class FakeHttpResponse:
    def __init__(self, body):
        self.body = body
        self.headers = {}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self.body


for body in (b"\xff", b"{ malformed"):
    with patch(
        "smoke_common.urllib.request.urlopen", return_value=FakeHttpResponse(body)
    ):
        try:
            smoke_common.http_request_json_with_headers("http://example.test", 1)
        except smoke_common.ProbeOperationalFailure as exc:
            assert "returned invalid JSON" in str(exc)
        else:
            raise AssertionError("invalid upstream HTTP JSON was not classified")


def unrelated_http_failure(*_args, **_kwargs):
    raise ValueError("unexpected HTTP client programming failure")


with patch(
    "smoke_common.urllib.request.urlopen", side_effect=unrelated_http_failure
):
    try:
        smoke_common.http_request_json_with_headers("http://example.test", 1)
    except ValueError as exc:
        assert str(exc) == "unexpected HTTP client programming failure"
    else:
        raise AssertionError("unrelated HTTP exception was incorrectly classified")


item_steps = smoke_common.gameplay_item_container_equipment_steps(
    "demo@example.com",
    "swordfish",
    "OK WORLDS",
    "OK LOGIN",
    "OK PLAY",
    "OK LOOK",
)
get_index = next(index for index, step in enumerate(item_steps) if step[0] == "GET Torch")
assert item_steps[get_index + 1] == (
    "INVENTORY",
    ["Inventory:", "- Torch"],
    "INVENTORY after GET",
)

print("smoke transport contract checks passed")
PY

assert_command_rejects() {
  local expected_substring="$1"
  shift
  local output status
  set +e
  output="$("$@" 2>&1)"
  status=$?
  set -e
  if [[ "$status" -eq 0 ]]; then
    echo "expected command to fail, but it succeeded: $*" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected_substring"* ]]; then
    echo "expected failed command output to contain '$expected_substring': $*" >&2
    echo "$output" >&2
    exit 1
  fi
}

for script in \
  "$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh" \
  "$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"; do
  assert_command_rejects \
    "SMOKE_MUTATION_EXTENSION must be boolean" \
    env SMOKE_MUTATION_EXTENSION=invalid bash "$script"
  assert_command_rejects \
    "Mutation extension requires SMOKE_MUTATION_BOUNDARY=run-owned-compose" \
    env SMOKE_MUTATION_EXTENSION=true bash "$script"
  for project_name in docker smoke-full firemud-smoke- smoke-full--1 smoke-full-1- smoke-full-1-1-extra; do
    assert_command_rejects \
      "COMPOSE_PROJECT_NAME must exactly match firemud-smoke-contract-local" \
      env SMOKE_MUTATION_EXTENSION=true \
      SMOKE_MUTATION_BOUNDARY=run-owned-compose \
      GITHUB_ACTIONS=false \
      FIREMUD_SMOKE_RUN_ID=contract-local \
      COMPOSE_PROJECT_NAME="$project_name" bash "$script"
  done
  assert_command_rejects \
    "SMOKE_MUTATION_BOUNDARY=restricted-synthetic is unavailable" \
    env SMOKE_MUTATION_EXTENSION=true \
    SMOKE_MUTATION_BOUNDARY=restricted-synthetic \
    GITHUB_ACTIONS=false \
    COMPOSE_PROJECT_NAME=firemud-smoke-contract bash "$script"
  grep -q 'SMOKE_MUTATION_EXTENSION=.*false' "$script"
  grep -q 'SMOKE_MUTATION_BOUNDARY=.*' "$script"
  grep -q 'require_smoke_mutation_boundary' "$script"
  grep -q 'run-owned-compose' "$script"
  grep -q 'login_play_look_steps' "$script"
  grep -q 'gameplay_item_container_equipment_steps' "$script"
  if grep -q 'COMPOSE_PROJECT_NAME:-.*=~' "$script"; then
    echo "inline COMPOSE_PROJECT_NAME validator remains in $script" >&2
    exit 1
  fi
  helper_source_line="$(grep -n 'run-owned-compose.sh' "$script" | head -1 | cut -d: -f1)"
  mutation_gate_line="$(grep -n '^[[:space:]]*require_smoke_mutation_boundary$' "$script" | head -1 | cut -d: -f1)"
  endpoint_guard_line="$(grep -n 'Mutation mode requires' "$script" | head -1 | cut -d: -f1)"
  service_guard_line="$(grep -n '^[[:space:]]*require_run_owned_compose_service' "$script" | head -1 | cut -d: -f1)"
  # shellcheck disable=SC2016 # Match the literal shell variable in the target script.
  python_line="$(grep -n '\$PYTHON.*<<' "$script" | head -1 | cut -d: -f1)"
  if [[ -z "$helper_source_line" || -z "$mutation_gate_line" || -z "$endpoint_guard_line" \
    || -z "$service_guard_line" || -z "$python_line" \
    || "$helper_source_line" -ge "$mutation_gate_line" \
    || "$mutation_gate_line" -ge "$endpoint_guard_line" \
    || "$endpoint_guard_line" -ge "$service_guard_line" \
    || "$service_guard_line" -ge "$python_line" ]]; then
    echo "executable shared mutation gate and transport consequences are not ordered before Python in $script" >&2
    exit 1
  fi
  if grep -q '^[[:space:]]*require_run_owned_compose_project$' "$script"; then
    echo "transport-local mutation guard still calls the shared project gate directly in $script" >&2
    exit 1
  fi
done

for script in \
  "$ROOT_DIR/dev-tools/verify-fresh-bootstrap.sh" \
  "$ROOT_DIR/dev-tools/verify-restart-state.sh" \
  "$ROOT_DIR/dev-tools/verify-smoke-images.sh"; do
  assert_command_rejects \
    "independent transport identities/state are not proven" \
    env SMOKE_MUTATION_EXTENSION=true bash "$script"
  grep -q 'independent transport identities/state are not proven' "$script"
  grep -q 'LOOK baseline proofs' "$script"
done

for script in "$ROOT_DIR/dev-tools/verify-fresh-bootstrap.sh" "$ROOT_DIR/dev-tools/verify-smoke-images.sh"; do
  grep -qE '(claim_run_owned_compose_project|require_run_owned_compose_project)' "$script"
  grep -q 'run-owned-compose.sh' "$script"
  mapfile -t down_lines < <(grep -nE '^[[:space:]]*docker compose .*down -v --remove-orphans([[:space:]]|$)' "$script" || true)
  if ((${#down_lines[@]} == 0)); then
    echo "no destructive compose teardown found in $script" >&2
    exit 1
  fi
  for down_entry in "${down_lines[@]}"; do
    down_line="${down_entry%%:*}"
    guard_line="$(grep -nE '^[[:space:]]*(claim_run_owned_compose_project|require_run_owned_compose_project)$' "$script" | awk -F: -v down="$down_line" '$1 < down {line=$1} END {print line}')"
    if [[ -z "$guard_line" || "$guard_line" -ge "$down_line" ]]; then
      echo "destructive compose teardown at line $down_line is not guarded in $script" >&2
      exit 1
    fi
  done
done

restart_script="$ROOT_DIR/dev-tools/verify-restart-state.sh"
grep -q 'run-owned-compose.sh' "$restart_script"
restart_helper_source_line="$(grep -n 'run-owned-compose.sh' "$restart_script" | head -1 | cut -d: -f1)"
restart_guard_line="$(grep -n '^[[:space:]]*require_run_owned_compose_project$' "$restart_script" | head -1 | cut -d: -f1)"
restart_compose_line="$(grep -n '^[[:space:]]*docker compose' "$restart_script" | head -1 | cut -d: -f1)"
if [[ -z "$restart_helper_source_line" || -z "$restart_guard_line" || -z "$restart_compose_line" \
  || "$restart_helper_source_line" -ge "$restart_guard_line" \
  || "$restart_guard_line" -ge "$restart_compose_line" ]]; then
  echo "restart-state must source and call the run-owned helper before Compose" >&2
  exit 1
fi

assert_command_rejects \
  "FIREMUD_SMOKE_RUN_ID must match" \
  env GITHUB_ACTIONS=false COMPOSE_PROJECT_NAME=firemud-smoke-shared bash "$RUN_OWNED_COMPOSE_HELPER"
assert_command_rejects \
  "FIREMUD_SMOKE_RUN_ID must match" \
  env GITHUB_ACTIONS=false FIREMUD_SMOKE_RUN_ID=Invalid_ID \
  COMPOSE_PROJECT_NAME=firemud-smoke-Invalid_ID bash "$RUN_OWNED_COMPOSE_HELPER"
assert_command_rejects \
  "COMPOSE_PROJECT_NAME must exactly match firemud-smoke-contract-local" \
  env GITHUB_ACTIONS=false FIREMUD_SMOKE_RUN_ID=contract-local \
  COMPOSE_PROJECT_NAME=firemud-smoke-other bash "$RUN_OWNED_COMPOSE_HELPER"
assert_command_rejects \
  "GitHub Actions mode requires nonempty GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, and GITHUB_JOB" \
  env GITHUB_ACTIONS=true GITHUB_RUN_ID=123 GITHUB_RUN_ATTEMPT= GITHUB_JOB=smoke \
  COMPOSE_PROJECT_NAME=smoke-full-123-2 bash "$RUN_OWNED_COMPOSE_HELPER"
assert_command_rejects \
  "COMPOSE_PROJECT_NAME must exactly match smoke-full-123-2" \
  env GITHUB_ACTIONS=true GITHUB_RUN_ID=123 GITHUB_RUN_ATTEMPT=2 GITHUB_JOB=smoke \
  COMPOSE_PROJECT_NAME=smoke-full-123-1 bash "$RUN_OWNED_COMPOSE_HELPER"


TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT
FAKE_DOCKER_BIN="$TEST_ROOT/bin"
FAKE_DOCKER_STATE="$TEST_ROOT/state"
OWNERSHIP_TEST_DIR="$TEST_ROOT/ownership"
mkdir -p "$FAKE_DOCKER_BIN" "$FAKE_DOCKER_STATE" "$OWNERSHIP_TEST_DIR"
chmod 700 "$OWNERSHIP_TEST_DIR"
cat >"$FAKE_DOCKER_BIN/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail

state_dir=$FAKE_DOCKER_STATE
command_name=$1
shift
field_matches() {
  local expected=$1
  local actual=$2
  [[ -z "$expected" || "$expected" == "$actual" ]]
}

case "$command_name" in
  ps)
    project=""
    service=""
    status=""
    while (($# > 0)); do
      case "$1" in
        -a) shift ;;
        --filter)
          filter=$2
          case "$filter" in
            label=com.docker.compose.project=*) project=${filter##*=} ;;
            label=com.docker.compose.service=*) service=${filter##*=} ;;
            status=*) status=${filter##*=} ;;
          esac
          shift 2
          ;;
        --format) shift 2 ;;
        *) shift ;;
      esac
    done
    [[ -f "$state_dir/containers" ]] || exit 0
    while IFS='|' read -r id row_project row_service row_status row_ports; do
      field_matches "$project" "$row_project" || continue
      field_matches "$service" "$row_service" || continue
      field_matches "$status" "$row_status" || continue
      printf '%s\n' "$id"
    done <"$state_dir/containers"
    ;;
  network)
    [[ "$1" == ls ]] || exit 99
    shift
    project=""
    while (($# > 0)); do
      if [[ "$1" == --filter ]]; then
        filter=$2
        [[ "$filter" == label=com.docker.compose.project=* ]] && project=${filter##*=}
        shift 2
      else
        shift
      fi
    done
    [[ -f "$state_dir/networks" ]] || exit 0
    while IFS='|' read -r id row_project; do
      field_matches "$project" "$row_project" || continue
      printf '%s\n' "$id"
    done <"$state_dir/networks"
    ;;
  volume)
    [[ "$1" == ls ]] || exit 99
    shift
    project=""
    while (($# > 0)); do
      if [[ "$1" == --filter ]]; then
        filter=$2
        [[ "$filter" == label=com.docker.compose.project=* ]] && project=${filter##*=}
        shift 2
      else
        shift
      fi
    done
    [[ -f "$state_dir/volumes" ]] || exit 0
    while IFS='|' read -r name row_project; do
      field_matches "$project" "$row_project" || continue
      printf '%s\n' "$name"
    done <"$state_dir/volumes"
    ;;
  port)
    id=$1
    requested=$2
    [[ -f "$state_dir/containers" ]] || exit 1
    while IFS='|' read -r row_id _project _service _status row_ports; do
      [[ "$row_id" == "$id" ]] || continue
      IFS=',' read -ra bindings <<<"$row_ports"
      for binding in "${bindings[@]}"; do
        binding_port=${binding%%=*}
        binding_value=${binding#*=}
        [[ "$binding_port" == "$requested" ]] && printf '%s\n' "$binding_value"
      done
      exit 0
    done <"$state_dir/containers"
    exit 1
    ;;
  *)
    echo "unsupported fake docker command: $command_name" >&2
    exit 99
    ;;
esac
FAKE_DOCKER
chmod 700 "$FAKE_DOCKER_BIN/docker"
export PATH="$FAKE_DOCKER_BIN:$PATH"
export FAKE_DOCKER_STATE

OWNERSHIP_TOKEN=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
export GITHUB_ACTIONS=false FIREMUD_SMOKE_RUN_ID=contract-local
export COMPOSE_PROJECT_NAME=firemud-smoke-contract-local
export FIREMUD_SMOKE_OWNERSHIP_TOKEN="$OWNERSHIP_TOKEN"
export FIREMUD_SMOKE_OWNERSHIP_DIR="$OWNERSHIP_TEST_DIR" FIREMUD_SMOKE_TEST_MODE=1

run_owned_helper() {
  local action="$1"
  shift
  bash -c 'source "$1"; shift; "$@"' _ "$RUN_OWNED_COMPOSE_HELPER" "$action" "$@"
}

normalized_mutation_false="$(
  # shellcheck disable=SC2016 # Match the literal shell variables in the child shell.
  env SMOKE_MUTATION_EXTENSION=0 SMOKE_MUTATION_BOUNDARY= \
    bash -c 'source "$1"; require_smoke_mutation_boundary; printf "%s|%s\n" "$SMOKE_MUTATION_EXTENSION" "$SMOKE_MUTATION_BOUNDARY"' \
    _ "$RUN_OWNED_COMPOSE_HELPER"
)"
[[ "$normalized_mutation_false" == "false|" ]]
normalized_mutation_true="$(
  # shellcheck disable=SC2016 # Match the literal shell variables in the child shell.
  env SMOKE_MUTATION_EXTENSION=1 SMOKE_MUTATION_BOUNDARY=restricted-synthetic \
    bash -c 'source "$1"; require_smoke_mutation_boundary || :; printf "%s|%s\n" "$SMOKE_MUTATION_EXTENSION" "$SMOKE_MUTATION_BOUNDARY"' \
    _ "$RUN_OWNED_COMPOSE_HELPER" 2>/dev/null
)"
[[ "$normalized_mutation_true" == "true|restricted-synthetic" ]]
# shellcheck disable=SC2016
RUN_OWNED_CHILD_BASH='source "$1"; shift; "$@"'
# shellcheck disable=SC2016
RUN_OWNED_NO_PIPEFAIL_CHILD_BASH='set +o pipefail; source "$1"; shift; "$@"'
# shellcheck disable=SC2016
RUN_OWNED_UNSET_TOKEN_CHILD_BASH='unset FIREMUD_SMOKE_OWNERSHIP_TOKEN; source "$1"; shift; "$@"'
# shellcheck disable=SC2016
RUN_OWNED_HOLD_LOCK_CHILD_BASH='source "$1"; claim_run_owned_compose_project; : >"$2"; sleep 10'
# shellcheck disable=SC2016
RUN_OWNED_UMASK_CHILD_BASH='source "$1"; shift; umask 027; "$@"; [[ "$(umask)" == 0027 ]]'

NO_FLOCK_BIN="$TEST_ROOT/no-flock-bin"
mkdir -p "$NO_FLOCK_BIN"
BASH_EXECUTABLE="$(command -v bash)"
for dependency in uname stat readlink id mktemp awk sha256sum; do
  ln -s "$(command -v "$dependency")" "$NO_FLOCK_BIN/$dependency"
done
assert_command_rejects \
  "flock dependency" \
  env PATH="$NO_FLOCK_BIN" \
  "$BASH_EXECUTABLE" -c "$RUN_OWNED_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project

FAILING_SHA256SUM_BIN="$TEST_ROOT/failing-sha256sum-bin"
FAILING_OWNERSHIP_DIR="$TEST_ROOT/failing-ownership"
mkdir -p "$FAILING_SHA256SUM_BIN"
for dependency in uname stat readlink id mktemp awk flock; do
  ln -s "$(command -v "$dependency")" "$FAILING_SHA256SUM_BIN/$dependency"
done
cat >"$FAILING_SHA256SUM_BIN/sha256sum" <<'FAILING_SHA256SUM'
#!/usr/bin/env bash
exit 42
FAILING_SHA256SUM
chmod 700 "$FAILING_SHA256SUM_BIN/sha256sum"
assert_command_rejects \
  "sha256sum command failed" \
  env PATH="$FAILING_SHA256SUM_BIN:$PATH" \
  FIREMUD_SMOKE_OWNERSHIP_DIR="$FAILING_OWNERSHIP_DIR" \
  "$BASH_EXECUTABLE" -c "$RUN_OWNED_NO_PIPEFAIL_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project
[[ -z "$(find "$FAILING_OWNERSHIP_DIR" -maxdepth 1 -type f -name '*.marker' -print -quit)" ]]

bash -c "$RUN_OWNED_UMASK_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project

run_owned_helper claim_run_owned_compose_project
marker_path="$(find "$OWNERSHIP_TEST_DIR" -maxdepth 1 -type f -name '*.marker' -print -quit)"
[[ -n "$marker_path" ]]
expected_digest="$(printf '%s' "$OWNERSHIP_TOKEN" | sha256sum | awk '{print $1}')"
[[ "$(cat "$marker_path")" == "$expected_digest" ]]
[[ "$(stat -Lc '%a' "$marker_path")" == 600 ]]

lock_ready="$TEST_ROOT/lock-ready"
bash -c "$RUN_OWNED_HOLD_LOCK_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" "$lock_ready" &
lock_holder_pid=$!
for _ in $(seq 1 200); do
  [[ -e "$lock_ready" ]] && break
  sleep 0.02
done
[[ -e "$lock_ready" ]]
assert_command_rejects \
  "another smoke invocation holds the project lock" \
  run_owned_helper claim_run_owned_compose_project
kill "$lock_holder_pid"
wait "$lock_holder_pid" 2>/dev/null || true

assert_command_rejects \
  "FIREMUD_SMOKE_OWNERSHIP_TOKEN" \
  bash -c "$RUN_OWNED_UNSET_TOKEN_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project
for invalid_token in abc "${OWNERSHIP_TOKEN^^}"; do
  assert_command_rejects \
    "FIREMUD_SMOKE_OWNERSHIP_TOKEN" \
    env FIREMUD_SMOKE_OWNERSHIP_TOKEN="$invalid_token" \
    bash -c "$RUN_OWNED_CHILD_BASH" _ \
    "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project
done
assert_command_rejects \
  "ownership marker" \
  env FIREMUD_SMOKE_OWNERSHIP_TOKEN=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff \
  bash -c "$RUN_OWNED_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project

rm -f "$marker_path"
ln -s "$TEST_ROOT/missing-marker-target" "$marker_path"
assert_command_rejects \
  "ownership marker" \
  run_owned_helper require_run_owned_compose_project
rm -f "$marker_path"
run_owned_helper claim_run_owned_compose_project

rm -f "$marker_path"
assert_command_rejects \
  "ownership marker" \
  run_owned_helper require_run_owned_compose_project
run_owned_helper claim_run_owned_compose_project

rm -f "$FAKE_DOCKER_STATE/containers" "$FAKE_DOCKER_STATE/networks" \
  "$FAKE_DOCKER_STATE/volumes"
assert_command_rejects \
  "no standard Compose-labelled project resource" \
  run_owned_helper require_run_owned_compose_project

rm -f "$OWNERSHIP_TEST_DIR"/*.marker
export GITHUB_ACTIONS=true GITHUB_RUN_ID=123 GITHUB_RUN_ATTEMPT=2 GITHUB_JOB=smoke
export COMPOSE_PROJECT_NAME=smoke-full-123-2
run_owned_helper claim_run_owned_compose_project
assert_command_rejects \
  "ownership marker" \
  env GITHUB_JOB=other-smoke \
  bash -c "$RUN_OWNED_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project
rm -f "$OWNERSHIP_TEST_DIR"/*.marker
export GITHUB_ACTIONS=false FIREMUD_SMOKE_RUN_ID=contract-local
export COMPOSE_PROJECT_NAME=firemud-smoke-contract-local
run_owned_helper claim_run_owned_compose_project

rm -f "$marker_path"
printf 'collision|%s|other|exited|\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"
assert_command_rejects \
  "standard Compose-labelled resources already exist without an ownership marker" \
  run_owned_helper claim_run_owned_compose_project
rm -f "$FAKE_DOCKER_STATE/containers"
run_owned_helper claim_run_owned_compose_project

printf 'game-session|%s|game-session-service|running|8080/tcp=0.0.0.0:8086\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"
printf 'network|%s\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/networks"
printf 'volume|%s\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/volumes"
run_owned_helper require_run_owned_compose_project
run_owned_helper require_run_owned_compose_service game-session-service 8080 8086

rm -f "$FAKE_DOCKER_STATE/containers"
assert_command_rejects \
  "expected exactly one running Compose service game-session-service" \
  run_owned_helper require_run_owned_compose_service game-session-service 8080 8086
printf 'game-session|%s|game-session-service|exited|8080/tcp=0.0.0.0:8086\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"
assert_command_rejects \
  "expected exactly one running Compose service game-session-service" \
  run_owned_helper require_run_owned_compose_service game-session-service 8080 8086
printf 'game-session|%s|game-session-service|running|8080/tcp=0.0.0.0:8085\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"
assert_command_rejects \
  "does not publish 8080/tcp on host port 8086" \
  run_owned_helper require_run_owned_compose_service game-session-service 8080 8086
printf 'game-session|%s|game-session-service|running|8080/tcp=0.0.0.0:8086\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"
run_owned_helper require_run_owned_compose_service game-session-service 8080 8086

printf 'tcp-proxy|%s|tcp-proxy-service|running|2323/tcp=0.0.0.0:2323\n' "$COMPOSE_PROJECT_NAME" >>"$FAKE_DOCKER_STATE/containers"
run_owned_helper require_run_owned_compose_service tcp-proxy-service 2323 2323

assert_command_rejects \
  "SMOKE_GAME_SESSION_WS_URL" \
  env SMOKE_MUTATION_EXTENSION=true \
  SMOKE_MUTATION_BOUNDARY=run-owned-compose \
  SMOKE_GAME_SESSION_WS_URL=ws://localhost:8085/ws \
  bash "$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh"
assert_command_rejects \
  "canonical Telnet endpoint" \
  env SMOKE_MUTATION_EXTENSION=true \
  SMOKE_MUTATION_BOUNDARY=run-owned-compose \
  SMOKE_TELNET_HOST=remotehost \
  bash "$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"
assert_command_rejects \
  "canonical Telnet endpoint" \
  env SMOKE_MUTATION_EXTENSION=true \
  SMOKE_MUTATION_BOUNDARY=run-owned-compose \
  TCP_PROXY_PORT=2324 \
  bash "$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"

assert_command_rejects \
  "cannot release ownership while standard Compose-labelled project resources remain" \
  run_owned_helper release_run_owned_compose_project
[[ -e "$marker_path" ]]

printf 'game-session-2|%s|game-session-service|running|8080/tcp=0.0.0.0:8086\n' "$COMPOSE_PROJECT_NAME" >>"$FAKE_DOCKER_STATE/containers"
assert_command_rejects \
  "expected exactly one running Compose service game-session-service" \
  run_owned_helper require_run_owned_compose_service game-session-service 8080 8086
printf 'game-session|%s|game-session-service|running|8080/tcp=0.0.0.0:8086\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"

rm -f "$FAKE_DOCKER_STATE/networks" "$FAKE_DOCKER_STATE/volumes"
rm -f "$FAKE_DOCKER_STATE/containers"
run_owned_helper release_run_owned_compose_project
[[ ! -e "$marker_path" ]]

echo "smoke script boundary contract checks passed"
