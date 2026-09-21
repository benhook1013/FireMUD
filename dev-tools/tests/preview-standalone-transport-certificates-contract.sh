#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/hosted/preview/ensure-standalone-transport-certificates.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT

FAKE_BIN="$TEMP_DIR/bin"
STATE_DIR="$TEMP_DIR/state"
mkdir -p "$FAKE_BIN" "$STATE_DIR"

cat >"$FAKE_BIN/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

state_dir="${FAKE_KUBECTL_STATE:?FAKE_KUBECTL_STATE is required}"
printf '%s\n' "$*" >>"$state_dir/calls"

case " $* " in
  *' apply -f - '*)
    cat >"$state_dir/applied.yaml"
    ;;
  *' wait '*)
    if [[ "${FAKE_KUBECTL_FAIL_WAIT:-false}" == true ]]; then
      exit 1
    fi
    ;;
  *' get secret '*)
    secret_name=""
    previous=''
    for argument in "$@"; do
      if [[ "$previous" == secret ]]; then
        secret_name="$argument"
        break
      fi
      previous="$argument"
    done
    case "$secret_name" in
      pr-42-telnet-tls)
        if [[ "${FAKE_KUBECTL_INCOMPLETE:-false}" == true ]]; then
          printf '%s' '{"metadata":{"name":"pr-42-telnet-tls"},"data":{"tls.crt":"cert"}}'
        else
          printf '%s' '{"metadata":{"name":"pr-42-telnet-tls"},"data":{"tls.crt":"cert","tls.key":"key"}}'
        fi
        ;;
      pr-42-gateway-internal-ws)
        printf '%s' '{"metadata":{"name":"pr-42-gateway-internal-ws"},"data":{"tls.crt":"cert","tls.key":"key","ca.crt":"ca"}}'
        ;;
      pr-42-tcp-proxy-bridge)
        printf '%s' '{"metadata":{"name":"pr-42-tcp-proxy-bridge"},"data":{"tls.crt":"cert","tls.key":"key","ca.crt":"ca"}}'
        ;;
      *)
        echo "unexpected Secret lookup: $secret_name" >&2
        exit 2
        ;;
    esac
    ;;
  *)
    echo "unexpected kubectl invocation: $*" >&2
    exit 2
    ;;
esac
EOF
chmod 700 "$FAKE_BIN/kubectl"

assert_rejected() {
  local expected="$1"
  shift
  if PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
    "$SCRIPT" "$@" >"$TEMP_DIR/rejected.out" 2>"$TEMP_DIR/rejected.err"; then
    echo "accepted invalid standalone certificate request: $*" >&2
    exit 1
  fi
  grep -Fq "$expected" "$TEMP_DIR/rejected.err" || {
    echo "missing rejection '$expected' for request: $*" >&2
    cat "$TEMP_DIR/rejected.err" >&2
    exit 1
  }
}

assert_rejected 'runtime namespace must be canonical pr-N' pr-0
assert_rejected 'runtime namespace must be canonical pr-N' pr-42-identity
assert_rejected 'runtime namespace must be canonical pr-N' dev
assert_rejected 'usage:' pr-42 ignored-override
assert_rejected 'usage:' --bogus pr-42
assert_rejected 'usage:' --wait
test ! -e "$STATE_DIR/applied.yaml"

if PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
  CERTIFICATE_WAIT_TIMEOUT_SECONDS=090 \
  "$SCRIPT" pr-42 >"$TEMP_DIR/leading-zero.out" 2>"$TEMP_DIR/leading-zero.err"; then
  echo "accepted leading-zero certificate timeout" >&2
  exit 1
fi
grep -Fq 'CERTIFICATE_WAIT_TIMEOUT_SECONDS must be an integer between 1 and 3600' \
  "$TEMP_DIR/leading-zero.err"
test ! -e "$STATE_DIR/applied.yaml"

if PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
  CERTIFICATE_WAIT_TIMEOUT_SECONDS=999999999999999999999999999999999 \
  "$SCRIPT" pr-42 >"$TEMP_DIR/timeout.out" 2>"$TEMP_DIR/timeout.err"; then
  echo "accepted overflowing certificate timeout" >&2
  exit 1
fi
grep -Fq 'CERTIFICATE_WAIT_TIMEOUT_SECONDS must be an integer between 1 and 3600' \
  "$TEMP_DIR/timeout.err"
test ! -e "$STATE_DIR/applied.yaml"

if [[ -f "$STATE_DIR/calls" ]]; then
  runtime_calls_start="$(wc -l <"$STATE_DIR/calls")"
else
  runtime_calls_start=0
fi
PATH="$FAKE_BIN:$PATH" \
FAKE_KUBECTL_STATE="$STATE_DIR" \
"$SCRIPT" pr-42 >"$TEMP_DIR/success.out"

runtime_calls="$(tail -n +$((runtime_calls_start + 1)) "$STATE_DIR/calls")"
if grep -Fq 'get secret' <<<"$runtime_calls"; then
  echo "standalone certificate writer read a Secret" >&2
  exit 1
fi

python3 - "$STATE_DIR/applied.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

documents = list(yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8")))
if len(documents) != 3:
    raise SystemExit(f"expected three Certificate documents, got {len(documents)}")
if any(document.get("kind") != "Certificate" for document in documents):
    raise SystemExit("standalone transport activation must apply Certificates only")

by_name = {document["metadata"]["name"]: document for document in documents}
expected = {
    "pr-42-telnet-tls": {
        "issuer": "letsencrypt-prod",
        "dnsNames": ["pr-42.preview.firedevops.net"],
        "usages": ["digital signature", "key encipherment", "server auth"],
    },
    "pr-42-gateway-internal-ws": {
        "issuer": "firemud-ca-issuer",
        "dnsNames": ["spring-cloud-gateway-mtls.pr-42.svc.cluster.local"],
        "usages": ["digital signature", "key encipherment", "server auth"],
    },
    "pr-42-tcp-proxy-bridge": {
        "issuer": "firemud-ca-issuer",
        "uris": ["spiffe://firemud/ns/pr-42/sa/tcp-proxy-service"],
        "usages": ["digital signature", "key encipherment", "client auth"],
    },
}
if set(by_name) != set(expected):
    raise SystemExit(f"unexpected Certificate identities: {sorted(by_name)}")
for name, contract in expected.items():
    document = by_name[name]
    if document["metadata"].get("namespace") != "pr-42":
        raise SystemExit(f"{name} has the wrong namespace")
    spec = document["spec"]
    if spec["secretName"] != name:
        raise SystemExit(f"{name} does not project to its canonical Secret")
    if spec["issuerRef"] != {
        "name": contract["issuer"],
        "kind": "ClusterIssuer",
        "group": "cert-manager.io",
    }:
        raise SystemExit(f"{name} has an unexpected issuerRef")
    for field in ("dnsNames", "uris"):
        if field in contract and spec.get(field) != contract[field]:
            raise SystemExit(f"{name} has an unexpected {field}")
        if field not in contract and field in spec:
            raise SystemExit(f"{name} unexpectedly sets {field}")
    if spec["usages"] != contract["usages"]:
        raise SystemExit(f"{name} has an unexpected usage profile")
    if spec["privateKey"] != {
        "algorithm": "RSA",
        "size": 2048,
        "encoding": "PKCS8",
        "rotationPolicy": "Always",
    }:
        raise SystemExit(f"{name} has an unexpected private-key profile")
PY

if PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
  FAKE_KUBECTL_FAIL_WAIT=true "$SCRIPT" pr-42 \
  >"$TEMP_DIR/failed.out" 2>"$TEMP_DIR/failed.err"; then
  echo "certificate issuance failure was accepted" >&2
  exit 1
fi
grep -Fq 'did not become Ready' "$TEMP_DIR/failed.err" || {
  echo "certificate issuance failure was not reported" >&2
  exit 1
}

runtime_calls_start="$(wc -l <"$STATE_DIR/calls")"
PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
  "$SCRIPT" --wait pr-42 >"$TEMP_DIR/secret-success.out"

runtime_calls="$(tail -n +$((runtime_calls_start + 1)) "$STATE_DIR/calls")"
if grep -Fq 'apply -f -' <<<"$runtime_calls"; then
  echo "runtime Secret waiter applied a Certificate" >&2
  exit 1
fi

if PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
  FAKE_KUBECTL_INCOMPLETE=true CERTIFICATE_WAIT_TIMEOUT_SECONDS=10 "$SCRIPT" --wait pr-42 \
  >"$TEMP_DIR/incomplete.out" 2>"$TEMP_DIR/incomplete.err"; then
  echo "incomplete projected Secret was accepted" >&2
  exit 1
fi
grep -Fq 'did not become key-complete' "$TEMP_DIR/incomplete.err" || {
  echo "incomplete projected Secret was not reported" >&2
  exit 1
}

echo "preview standalone transport certificate contract passed"
