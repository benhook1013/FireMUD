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
      firemud-grpc-tls)
        if [[ -z "${FAKE_KUBECTL_TRUST_CA:-}" ]]; then
          echo "canonical CA fixture is required" >&2
          exit 2
        fi
        base64 -w0 "$FAKE_KUBECTL_TRUST_CA"
        ;;
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
      firemud-grpc-game-design-baseline-migrator|firemud-grpc-account-tenant-migrator|firemud-grpc-game-design-tenant-migrator)
        if [[ -z "${FAKE_KUBECTL_SECRET_JSON:-}" ]]; then
          echo "migrator Secret fixture is required" >&2
          exit 2
        fi
        cat "$FAKE_KUBECTL_SECRET_JSON"
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
assert_rejected 'runtime namespace must be dev or canonical pr-N' --migrator dev-identity
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

for migrator_identity in account-tenant-migrator game-design-tenant-migrator; do
  runtime_calls_start="$(wc -l <"$STATE_DIR/calls")"
  PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
    "$SCRIPT" "--$migrator_identity" dev >"$TEMP_DIR/$migrator_identity-issued.out"
  runtime_calls="$(tail -n +$((runtime_calls_start + 1)) "$STATE_DIR/calls")"
  if grep -Fq 'get secret' <<<"$runtime_calls"; then
    echo "$migrator_identity certificate writer read a Secret" >&2
    exit 1
  fi
  cp "$STATE_DIR/applied.yaml" "$TEMP_DIR/$migrator_identity-certificate.yaml"
done
python3 - "$TEMP_DIR" <<'PY'
import sys
from pathlib import Path

import yaml

root = Path(sys.argv[1])
for identity in ("account-tenant-migrator", "game-design-tenant-migrator"):
    document = yaml.safe_load((root / f"{identity}-certificate.yaml").read_text(encoding="utf-8"))
    if document.get("kind") != "Certificate":
        raise SystemExit(f"{identity} opt-in did not apply one Certificate")
    namespace = document["metadata"].get("namespace")
    if namespace != "dev" or document["metadata"].get("name") != f"{namespace}-grpc-{identity}":
        raise SystemExit(f"{identity} Certificate name is not stable and namespace-bound")
    spec = document["spec"]
    if spec.get("secretName") != f"firemud-grpc-{identity}":
        raise SystemExit(f"{identity} Certificate does not use its dedicated Secret")
    if spec.get("uris") != [f"spiffe://firemud/ns/{namespace}/sa/{identity}"]:
        raise SystemExit(f"{identity} Certificate URI SAN is not exact")
    if "dnsNames" in spec or spec.get("usages") != ["digital signature", "key encipherment", "client auth"]:
        raise SystemExit(f"{identity} Certificate is not URI-only clientAuth")
    if spec.get("issuerRef") != {
        "name": "firemud-ca-issuer",
        "kind": "ClusterIssuer",
        "group": "cert-manager.io",
    }:
        raise SystemExit(f"{identity} Certificate must use the internal ClusterIssuer")
    if spec.get("secretTemplate", {}).get("metadata", {}).get("labels") != {
        "firemud.dev/managed-by": "tenant-association-migration",
        "firemud.dev/role": f"grpc-{identity}",
        "firemud.dev/retention": "ephemeral",
    }:
        raise SystemExit(f"{identity} Secret labels do not match the tenant-migration profile")
PY

PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
  "$SCRIPT" --account-tenant-migrator dev >"$TEMP_DIR/account-tenant-migrator-repeat.out"
python3 - "$TEMP_DIR/account-tenant-migrator-certificate.yaml" "$STATE_DIR/applied.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

first = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
second = yaml.safe_load(Path(sys.argv[2]).read_text(encoding="utf-8"))
if first["metadata"]["name"] != second["metadata"]["name"]:
    raise SystemExit("tenant migrator Certificate name changed on repeat issuance")
if first["spec"] != second["spec"]:
    raise SystemExit("tenant migrator Certificate spec changed on repeat issuance")
PY

runtime_calls_start="$(wc -l <"$STATE_DIR/calls")"
PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
  "$SCRIPT" --migrator dev >"$TEMP_DIR/migrator-issued.out"
runtime_calls="$(tail -n +$((runtime_calls_start + 1)) "$STATE_DIR/calls")"
if grep -Fq 'get secret' <<<"$runtime_calls"; then
  echo "migrator certificate writer read a Secret" >&2
  exit 1
fi
python3 - "$STATE_DIR/applied.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

document = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
if document.get("kind") != "Certificate":
    raise SystemExit("migrator opt-in did not apply one Certificate")
if document["metadata"].get("name") != "dev-grpc-game-design-baseline-migrator":
    raise SystemExit("migrator Certificate name is not namespace-bound")
spec = document["spec"]
if spec.get("secretName") != "firemud-grpc-game-design-baseline-migrator":
    raise SystemExit("migrator Certificate does not use the dedicated Secret")
if spec.get("uris") != ["spiffe://firemud/ns/dev/sa/game-design-baseline-migrator"]:
    raise SystemExit("migrator Certificate URI SAN is not exact")
if "dnsNames" in spec or spec.get("usages") != ["digital signature", "key encipherment", "client auth"]:
    raise SystemExit("migrator Certificate is not URI-only clientAuth")
if spec.get("issuerRef") != {
    "name": "firemud-ca-issuer",
    "kind": "ClusterIssuer",
    "group": "cert-manager.io",
}:
    raise SystemExit("migrator Certificate must use the internal ClusterIssuer")
if spec.get("secretTemplate", {}).get("metadata", {}).get("labels") != {
    "firemud.dev/managed-by": "entity-baseline-migration",
    "firemud.dev/role": "grpc-game-design-baseline-migrator",
    "firemud.dev/retention": "ephemeral",
}:
    raise SystemExit("migrator Secret labels do not match the exact admission profile")
PY

openssl req -x509 -newkey rsa:2048 -nodes -keyout "$TEMP_DIR/ca.key" \
  -out "$TEMP_DIR/ca.crt" -subj '/CN=FireMUD test CA' -days 1 >/dev/null 2>&1
openssl req -new -newkey rsa:2048 -nodes -keyout "$TEMP_DIR/leaf.key" \
  -out "$TEMP_DIR/leaf.csr" -subj '/CN=baseline migrator' \
  -addext 'subjectAltName=URI:spiffe://firemud/ns/dev/sa/game-design-baseline-migrator' \
  -addext 'extendedKeyUsage=clientAuth' \
  -addext 'keyUsage=digitalSignature,keyEncipherment' >/dev/null 2>&1
openssl x509 -req -in "$TEMP_DIR/leaf.csr" -CA "$TEMP_DIR/ca.crt" \
  -CAkey "$TEMP_DIR/ca.key" -CAcreateserial -days 1 -copy_extensions copy \
  -out "$TEMP_DIR/leaf.crt" >/dev/null 2>&1
jq -n \
  --arg name firemud-grpc-game-design-baseline-migrator \
  --arg leaf "$(base64 -w0 "$TEMP_DIR/leaf.crt")" \
  --arg ca "$(base64 -w0 "$TEMP_DIR/ca.crt")" \
  '{metadata:{name:$name},type:"kubernetes.io/tls",data:{"tls.crt":$leaf,"tls.key":"c2VjcmV0","ca.crt":$ca}}' \
  >"$TEMP_DIR/migrator-secret.json"
PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
  FAKE_KUBECTL_SECRET_JSON="$TEMP_DIR/migrator-secret.json" \
  FAKE_KUBECTL_TRUST_CA="$TEMP_DIR/ca.crt" \
  "$SCRIPT" --verify-migrator dev >"$TEMP_DIR/migrator-verified.out"
grep -Fq 'leaf-ca-match=true' "$TEMP_DIR/migrator-verified.out"
grep -Fq 'namespace-trust-match=true' "$TEMP_DIR/migrator-verified.out"
grep -Fq 'client-auth-only=true' "$TEMP_DIR/migrator-verified.out"
if grep -Fq 'tls.key' "$TEMP_DIR/migrator-verified.out"; then
  echo "migrator verification output disclosed a private-key field" >&2
  exit 1
fi

for migrator_identity in account-tenant-migrator game-design-tenant-migrator; do
  openssl req -new -newkey rsa:2048 -nodes -keyout "$TEMP_DIR/$migrator_identity.key" \
    -out "$TEMP_DIR/$migrator_identity.csr" -subj "/CN=$migrator_identity" \
    -addext "subjectAltName=URI:spiffe://firemud/ns/dev/sa/$migrator_identity" \
    -addext 'extendedKeyUsage=clientAuth' \
    -addext 'keyUsage=digitalSignature,keyEncipherment' >/dev/null 2>&1
  openssl x509 -req -in "$TEMP_DIR/$migrator_identity.csr" -CA "$TEMP_DIR/ca.crt" \
    -CAkey "$TEMP_DIR/ca.key" -CAcreateserial -days 1 -copy_extensions copy \
    -out "$TEMP_DIR/$migrator_identity.crt" >/dev/null 2>&1
  jq -n \
    --arg name "firemud-grpc-$migrator_identity" \
    --arg leaf "$(base64 -w0 "$TEMP_DIR/$migrator_identity.crt")" \
    --arg ca "$(base64 -w0 "$TEMP_DIR/ca.crt")" \
    '{metadata:{name:$name},type:"kubernetes.io/tls",data:{"tls.crt":$leaf,"tls.key":"c2VjcmV0","ca.crt":$ca}}' \
    >"$TEMP_DIR/$migrator_identity-secret.json"
  PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
    FAKE_KUBECTL_SECRET_JSON="$TEMP_DIR/$migrator_identity-secret.json" \
    FAKE_KUBECTL_TRUST_CA="$TEMP_DIR/ca.crt" \
    "$SCRIPT" "--verify-$migrator_identity" dev >"$TEMP_DIR/$migrator_identity-verified.out"
  grep -Fq "uri-san=spiffe://firemud/ns/dev/sa/$migrator_identity" \
    "$TEMP_DIR/$migrator_identity-verified.out"
  grep -Fq 'namespace-trust-match=true' "$TEMP_DIR/$migrator_identity-verified.out"
  grep -Fq 'client-auth-only=true' "$TEMP_DIR/$migrator_identity-verified.out"
done

openssl req -new -newkey rsa:2048 -nodes -keyout "$TEMP_DIR/wrong-leaf.key" \
  -out "$TEMP_DIR/wrong-leaf.csr" -subj '/CN=wrong migrator' \
  -addext 'subjectAltName=URI:spiffe://firemud/ns/dev/sa/game-design-service' \
  -addext 'extendedKeyUsage=clientAuth' \
  -addext 'keyUsage=digitalSignature,keyEncipherment' >/dev/null 2>&1
openssl x509 -req -in "$TEMP_DIR/wrong-leaf.csr" -CA "$TEMP_DIR/ca.crt" \
  -CAkey "$TEMP_DIR/ca.key" -CAcreateserial -days 1 -copy_extensions copy \
  -out "$TEMP_DIR/wrong-leaf.crt" >/dev/null 2>&1
jq -n \
  --arg name firemud-grpc-game-design-baseline-migrator \
  --arg leaf "$(base64 -w0 "$TEMP_DIR/wrong-leaf.crt")" \
  --arg ca "$(base64 -w0 "$TEMP_DIR/ca.crt")" \
  '{metadata:{name:$name},type:"kubernetes.io/tls",data:{"tls.crt":$leaf,"tls.key":"c2VjcmV0","ca.crt":$ca}}' \
  >"$TEMP_DIR/wrong-migrator-secret.json"
if PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
  FAKE_KUBECTL_SECRET_JSON="$TEMP_DIR/wrong-migrator-secret.json" \
  FAKE_KUBECTL_TRUST_CA="$TEMP_DIR/ca.crt" \
  "$SCRIPT" --verify-migrator dev >"$TEMP_DIR/wrong-migrator.out" \
  2>"$TEMP_DIR/wrong-migrator.err"; then
  echo "migrator verifier accepted a certificate for another SPIFFE identity" >&2
  exit 1
fi
grep -Fq 'does not contain only the exact migrator SPIFFE URI SAN' \
  "$TEMP_DIR/wrong-migrator.err"

jq -n \
  --arg name firemud-grpc-account-tenant-migrator \
  --arg leaf "$(base64 -w0 "$TEMP_DIR/wrong-leaf.crt")" \
  --arg ca "$(base64 -w0 "$TEMP_DIR/ca.crt")" \
  '{metadata:{name:$name},type:"kubernetes.io/tls",data:{"tls.crt":$leaf,"tls.key":"c2VjcmV0","ca.crt":$ca}}' \
  >"$TEMP_DIR/wrong-account-tenant-migrator-secret.json"
if PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
  FAKE_KUBECTL_SECRET_JSON="$TEMP_DIR/wrong-account-tenant-migrator-secret.json" \
  FAKE_KUBECTL_TRUST_CA="$TEMP_DIR/ca.crt" \
  "$SCRIPT" --verify-account-tenant-migrator dev \
  >"$TEMP_DIR/wrong-account-tenant-migrator.out" \
  2>"$TEMP_DIR/wrong-account-tenant-migrator.err"; then
  echo "Account tenant migrator verifier accepted another workload identity" >&2
  exit 1
fi
grep -Fq 'does not contain only the exact migrator SPIFFE URI SAN' \
  "$TEMP_DIR/wrong-account-tenant-migrator.err"

openssl req -x509 -newkey rsa:2048 -nodes -keyout "$TEMP_DIR/wrong-ca.key" \
  -out "$TEMP_DIR/wrong-ca.crt" -subj '/CN=Untrusted test CA' -days 1 >/dev/null 2>&1
if PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" \
  FAKE_KUBECTL_SECRET_JSON="$TEMP_DIR/migrator-secret.json" \
  FAKE_KUBECTL_TRUST_CA="$TEMP_DIR/wrong-ca.crt" \
  "$SCRIPT" --verify-migrator dev >"$TEMP_DIR/wrong-ca.out" \
  2>"$TEMP_DIR/wrong-ca.err"; then
  echo "migrator verifier accepted a CA not trusted by firemud-grpc-tls" >&2
  exit 1
fi
grep -Fq 'CA chain is not trusted by the namespace firemud-grpc-tls bundle' \
  "$TEMP_DIR/wrong-ca.err"

echo "preview standalone transport certificate contract passed"
