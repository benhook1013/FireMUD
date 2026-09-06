#!/usr/bin/env bash
# Contract checks for dev-tools/deploy/preflight.py report shape and policy IDs.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECKED_OUT_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)"
SCRIPT="$ROOT_DIR/dev-tools/deploy/preflight.py"
WRITER="$ROOT_DIR/dev-tools/deploy/write-traffic-open-evidence.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RENDERED_MANIFEST="$TMP_DIR/hobby-rendered.yaml"
MIGRATED_RENDERED_MANIFEST="$TMP_DIR/hobby-rendered-configmap.yaml"
REPORT_PATH="$TMP_DIR/preflight-report.json"
MIGRATED_REPORT_PATH="$TMP_DIR/preflight-migrated-report.json"
OPERATOR_REPORT_PATH="$TMP_DIR/operator-preflight-report.json"
TRAFFIC_EVIDENCE="$TMP_DIR/traffic-open.json"
PRODUCTION_REPORT="$TMP_DIR/preflight-production.json"
LEGACY_PRODUCTION_TRAFFIC_EVIDENCE="$TMP_DIR/production-traffic-open.json"
PRODUCTION_WAIVER="$TMP_DIR/contract-production.waiver.json"
LEGACY_HOBBY_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract.out"
MIGRATED_HOBBY_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract-migrated.out"
TRAFFIC_HOBBY_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract-traffic.out"
LEGACY_PRODUCTION_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract-production-traffic.out"
GATED_PRODUCTION_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract-production-traffic-gated.out"
WAIVER_PRODUCTION_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract-production-traffic-waiver.out"
HOBBY_TRAFFIC_WRITER_OUTPUT="$TMP_DIR/firemud-preflight-write-traffic-hobby.out"
PRODUCTION_TRAFFIC_WRITER_OUTPUT="$TMP_DIR/firemud-preflight-write-traffic-production.out"

python3 - <<'PY' "$ROOT_DIR"
import copy
import pathlib
import sys
import yaml

root = pathlib.Path(sys.argv[1])
required_paths = [
    "internalBindings.postgres.endpoint",
    "internalBindings.postgres.credentialsRef",
    "internalBindings.redis.coordination.endpoint",
    "internalBindings.redis.cache.endpoint",
    "internalBindings.jwt.custodyMode",
    "internalBindings.jwt.signingKeysRef",
    "internalBindings.jwt.jwksRef",
    "internalBindings.certificates.issuerRef",
    "internalBindings.certificates.workloadMtlsRef",
    "internalBindings.certificates.gatewayInternalWsListenerRef",
    "internalBindings.certificates.tcpProxyBridgeClientRef",
    "internalBindings.registry.imagePullSecretRef",
    "assetStorage.enabled",
    "backupStorage.enabled",
    "backupStorage.bucket",
    "backupStorage.bindingRef",
    "assetStorage.bucket",
    "assetStorage.bindingRef",
    "outboundComms.enabled",
    "outboundComms.smtpHost",
    "operatorCredentials.bindingRef",
    "serviceDiscovery.mode",
]

def get(data, dotted):
    cur = data
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur

def checked_in_manifest_error(ref, data, expected_environment):
    if data.get("environment") != expected_environment:
        return f"{ref}: environment mismatch"
    missing = [
        path
        for path in required_paths
        if (get(data, path) is None if path.endswith(".enabled") else not get(data, path))
    ]
    if "assetStorage" in data:
        missing.extend(
            path
            for path in (
                "assetStorage.bucket",
                "assetStorage.endpoint",
                "assetStorage.bindingRef",
            )
            if not get(data, path)
        )
    if missing:
        return f"{ref}: missing required binding paths: {missing}"
    backup_storage = data.get("backupStorage")
    if not isinstance(backup_storage, dict):
        return f"{ref}: backupStorage must be a mapping"
    if not isinstance(backup_storage.get("enabled"), bool):
        return f"{ref}: backupStorage.enabled must be a boolean"
    if backup_storage["enabled"] is not True:
        return f"{ref}: checked-in backupStorage.enabled must be true"
    return None

for env in ("production", "staging", "hobby-self-hosted"):
    ref = pathlib.Path(f"design/operations/environments/{env}/expected-bindings.yaml")
    data = yaml.safe_load((root / ref).read_text(encoding="utf-8"))
    if data.get("environment") != env:
        raise SystemExit(f"{ref}: environment mismatch")
    missing = [path for path in required_paths if not get(data, path)]
    if missing:
        raise SystemExit(f"{ref}: missing required binding paths: {missing}")
    backup_storage = data.get("backupStorage")
    if not isinstance(backup_storage, dict):
        raise SystemExit(f"{ref}: backupStorage must be a mapping")
    backup_enabled = backup_storage.get("enabled")
    if not isinstance(backup_enabled, bool):
        raise SystemExit(f"{ref}: backupStorage.enabled must be a boolean")
    if backup_enabled:
        backup_missing = []
        if not backup_storage.get("bucket"):
            backup_missing.append("backupStorage.bucket")
        if not backup_storage.get("bindingRef") and not backup_storage.get("fingerprint"):
            backup_missing.append("backupStorage.bindingRef or backupStorage.fingerprint")
        if backup_missing:
            raise SystemExit(f"{ref}: missing enabled backup storage paths: {backup_missing}")
    for section in ("assetStorage", "outboundComms"):
        optional = data.get(section)
        if not isinstance(optional, dict):
            raise SystemExit(f"{ref}: {section} must be an object")
        if not isinstance(optional.get("enabled"), bool):
            raise SystemExit(f"{ref}: {section}.enabled must be a boolean")
        if not optional["enabled"]:
            continue
        if section == "assetStorage":
            if not optional.get("bucket") or not optional.get("endpoint"):
                raise SystemExit(f"{ref}: enabled assetStorage needs bucket and endpoint")
            if not optional.get("bindingRef") and not optional.get("fingerprint"):
                raise SystemExit(f"{ref}: enabled assetStorage needs bindingRef or fingerprint")
        elif not optional.get("smtpHost") and not optional.get("webhookTargets"):
            raise SystemExit(f"{ref}: enabled outboundComms needs smtpHost or webhookTargets")
    error = checked_in_manifest_error(ref, data, env)
    if error:
        raise SystemExit(error)

checked_in_hobby = yaml.safe_load(
    (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(
        encoding="utf-8"
    )
)
for case_name, backup_storage in (("absent", "absent"), ("null", None)):
    case_data = dict(checked_in_hobby)
    if backup_storage == "absent":
        case_data.pop("backupStorage", None)
    else:
        case_data["backupStorage"] = backup_storage
    error = checked_in_manifest_error(
        pathlib.Path(f"synthetic-{case_name}-backup-storage.yaml"),
        case_data,
        "hobby-self-hosted",
    )
    if error is None or "missing required binding paths" not in error or "backupStorage" not in error:
        raise SystemExit(f"{case_name} backupStorage did not report missing paths: {error}")

disabled_data = dict(checked_in_hobby)
disabled_data["backupStorage"] = dict(checked_in_hobby["backupStorage"])
disabled_data["backupStorage"]["enabled"] = False
disabled_error = checked_in_manifest_error(
    pathlib.Path("synthetic-disabled-backup-storage.yaml"),
    disabled_data,
    "hobby-self-hosted",
)
if disabled_error is None or "checked-in backupStorage.enabled must be true" not in disabled_error:
    raise SystemExit(f"explicitly disabled backupStorage changed its diagnostic: {disabled_error}")

falsey_data = copy.deepcopy(checked_in_hobby)
falsey_data["internalBindings"]["postgres"]["endpoint"] = ""
falsey_error = checked_in_manifest_error(
    pathlib.Path("synthetic-empty-postgres-endpoint.yaml"),
    falsey_data,
    "hobby-self-hosted",
)
if falsey_error is None or "internalBindings.postgres.endpoint" not in falsey_error:
    raise SystemExit(f"falsey required binding changed its diagnostic: {falsey_error}")
PY

python3 - <<'PY' "$ROOT_DIR" "$TMP_DIR"
import json
import os
import pathlib
import subprocess
import sys

import yaml

root = pathlib.Path(sys.argv[1])
tmp = pathlib.Path(sys.argv[2]) / "backup-selection-contract"
tmp.mkdir(parents=True, exist_ok=True)
fake_bin = tmp / "bin"
fake_bin.mkdir()

(fake_bin / "aws").write_text(
    '''#!/usr/bin/env python3
import gzip
import json
import os
import pathlib
import sys

args = sys.argv[1:]
objects = json.loads(os.environ["FAKE_OBJECTS"])
if args[:2] == ["s3api", "list-objects-v2"]:
    query = args[args.index("--query") + 1]
    pathlib.Path(os.environ["FAKE_QUERY_LOG"]).write_text(query, encoding="utf-8")
    if "starts_with" not in query or "15min/firemud_" not in query or "ends_with" not in query or ".sql.gz" not in query:
        raise SystemExit("selection query did not filter for scheduled firemud .sql.gz artifacts")
    if os.environ.get("FAKE_LIST_FAILURE") == "1":
        print("Unable to reach object storage", file=sys.stderr)
        raise SystemExit(42)
    candidates = [
        item
        for item in objects
        if item["Key"].startswith("15min/firemud_") and item["Key"].endswith(".sql.gz")
    ]
    if not candidates:
        print("None")
    elif ".[LastModified, Key]" in query:
        for item in candidates:
            print(f'{item["LastModified"]}\t{item["Key"]}')
    else:
        print(max(candidates, key=lambda item: item["LastModified"])["Key"])
elif args[:2] == ["s3", "cp"]:
    source = args[2]
    destination = args[3]
    if not source.endswith(".sql.gz"):
        raise SystemExit("restore attempted to copy a non-.sql.gz object")
    pathlib.Path(destination).write_bytes(gzip.compress(b"-- selected valid hosted artifact\\n"))
else:
    raise SystemExit(f"unexpected aws invocation: {args}")
''',
    encoding="utf-8",
)
(fake_bin / "velero").write_text(
    "#!/usr/bin/env python3\nprint('NAME STATUS')\nprint('backup-1 Completed')\n",
    encoding="utf-8",
)
(fake_bin / "psql").write_text(
    '''#!/usr/bin/env python3
import json
import os
import pathlib
import sys

pathlib.Path(os.environ["PSQL_CAPTURE"]).write_bytes(sys.stdin.buffer.read())
pathlib.Path(os.environ["PSQL_ARGS_CAPTURE"]).write_text(json.dumps(sys.argv[1:]), encoding="utf-8")
''',
    encoding="utf-8",
)
for tool in ("aws", "velero", "psql"):
    (fake_bin / tool).chmod(0o755)

objects_with_legacy_newer = [
    {"Key": "15min/firemud_20260824120000.sql.gz", "LastModified": "2026-08-24T12:00:00Z"},
    {"Key": "15min/unrelated-newer.sql.gz", "LastModified": "2026-08-24T12:00:30Z"},
    {"Key": "15min/legacy-newer.dump", "LastModified": "2026-08-24T12:01:00Z"},
    {"Key": "15min/arbitrary-newer-object", "LastModified": "2026-08-24T12:02:00Z"},
]
objects_without_valid = [
    {"Key": "15min/legacy-newer.dump", "LastModified": "2026-08-24T12:01:00Z"},
    {"Key": "15min/arbitrary-object", "LastModified": "2026-08-24T12:02:00Z"},
    {"Key": "15min/firemud_2026082412000.sql.gz", "LastModified": "2026-08-24T12:03:00Z"},
    {"Key": "15min/firemud_202608241200000.sql.gz", "LastModified": "2026-08-24T12:04:00Z"},
]
objects_with_overlapping_runs = [
    {"Key": "15min/firemud_20260824120000.sql.gz", "LastModified": "2026-08-24T12:05:00Z"},
    {"Key": "15min/firemud_20260824120100.sql.gz", "LastModified": "2026-08-24T12:04:00Z"},
]

def run(script, objects, *, expect_success, list_failure=False):
    query_log = tmp / (pathlib.Path(script).stem + "-query.txt")
    capture = tmp / (pathlib.Path(script).stem + "-psql.sql")
    args_capture = tmp / (pathlib.Path(script).stem + "-psql-args.json")
    env = os.environ.copy()
    env.update(
        {
            "PATH": f"{fake_bin}:{env['PATH']}",
            "PG_DUMP_BUCKET": "firemud-test",
            "FIREMUD_POSTGRES_HOST": "localhost",
            "FIREMUD_POSTGRES_USER": "firemud",
            "FIREMUD_POSTGRES_DB": "firemud",
            "FAKE_OBJECTS": json.dumps(objects),
            "FAKE_QUERY_LOG": str(query_log),
            "PSQL_CAPTURE": str(capture),
            "PSQL_ARGS_CAPTURE": str(args_capture),
            "FAKE_LIST_FAILURE": "1" if list_failure else "0",
        }
    )
    try:
        result = subprocess.run(
            [str(script)], env=env, text=True, capture_output=True, timeout=60
        )
    except subprocess.TimeoutExpired as error:
        raise SystemExit(f"{script} selection test timed out after 60 seconds") from error
    if (result.returncode == 0) != expect_success:
        raise SystemExit(
            f"{script} selection outcome was unexpected: rc={result.returncode}, "
            f"stdout={result.stdout!r}, stderr={result.stderr!r}"
        )
    query = query_log.read_text(encoding="utf-8")
    if "starts_with" not in query or "15min/firemud_" not in query or "ends_with" not in query or ".sql.gz" not in query:
        raise SystemExit(f"{script} did not retain the scheduled firemud .sql.gz selection query: {query!r}")
    return result, capture

restore_script = root / "dev-tools/restores/restore-latest-db.sh"
verify_script = root / "dev-tools/backups/verify-backups.sh"

for script in (restore_script, verify_script):
    result, capture = run(script, objects_with_legacy_newer, expect_success=True)
    if script == restore_script:
        if "firemud_20260824120000.sql.gz" not in result.stdout or "legacy-newer.dump" in result.stdout:
            raise SystemExit(f"{script} selected the wrong scheduled object: {result.stdout!r}")
        if capture.read_bytes() != b"-- selected valid hosted artifact\n":
            raise SystemExit(f"{script} streamed unexpected artifact bytes")
        psql_args = json.loads((tmp / "restore-latest-db-psql-args.json").read_text(encoding="utf-8"))
        if ["-v", "ON_ERROR_STOP=1", "--single-transaction"] != psql_args[:3]:
            raise SystemExit(
                f"{script} did not enable fail-closed transactional psql restore: {psql_args!r}"
            )
    elif "firemud_20260824120000.sql.gz" not in result.stdout or "legacy-newer.dump" in result.stdout:
        raise SystemExit(f"{script} verified the wrong scheduled object: {result.stdout!r}")

for script in (restore_script, verify_script):
    overlap_result, _ = run(script, objects_with_overlapping_runs, expect_success=True)
    if "firemud_20260824120100.sql.gz" not in overlap_result.stdout:
        raise SystemExit(
            f"{script} prioritized object LastModified over capture timestamp: "
            f"{overlap_result.stdout!r}"
        )

for script in (restore_script, verify_script):
    result, _ = run(script, objects_without_valid, expect_success=False)
    if ".sql.gz" not in result.stderr:
        raise SystemExit(f"{script} did not report missing valid .sql.gz artifact: {result.stderr!r}")

listing_failure, _ = run(restore_script, objects_without_valid, expect_success=False, list_failure=True)
if "Unable to list pg_dump objects" not in listing_failure.stderr or "AWS credentials" not in listing_failure.stderr \
        or "endpoint" not in listing_failure.stderr or "network access" not in listing_failure.stderr:
    raise SystemExit(f"{restore_script} did not report an explicit AWS listing diagnostic: {listing_failure.stderr!r}")
PY

python3 - <<'PY' "$ROOT_DIR" "$TMP_DIR"
import json
import os
import pathlib
import subprocess
import sys

root = pathlib.Path(sys.argv[1])
tmp = pathlib.Path(sys.argv[2]) / "pg-dump-publication-contract"
fake_bin = tmp / "bin"
fake_bin.mkdir(parents=True)
script = root / "dev-tools/backups/pg-dump-rotate.sh"

(fake_bin / "date").write_text(
    """#!/usr/bin/env python3
import sys

values = {
    "+%Y%m%d%H%M%S": "20260801001234",
    "+%H": "00",
    "+%u": "7",
    "+%d": "01",
}
if sys.argv[1] != "-u" or sys.argv[2] not in values:
    raise SystemExit(f"date was not invoked in UTC: {sys.argv[1:]!r}")
print(values[sys.argv[2]])
""",
    encoding="utf-8",
)
(fake_bin / "pg_dump").write_text(
    """#!/usr/bin/env python3
import os
import sys
import time

time.sleep(0.15)
sys.stdout.write("dump-" + os.environ.get("FAKE_RUN", "unknown"))
if os.environ.get("FAKE_MODE") == "fail_pg_dump":
    sys.exit(17)
""",
    encoding="utf-8",
)
(fake_bin / "gzip").write_text(
    """#!/usr/bin/env python3
import os
import sys
import time

payload = sys.stdin.buffer.read()
time.sleep(0.15)
sys.stdout.buffer.write(payload)
if os.environ.get("FAKE_MODE") == "fail_gzip":
    sys.exit(23)
""",
    encoding="utf-8",
)
(fake_bin / "aws").write_text(
    """#!/usr/bin/env python3
import json
import os
import pathlib
import shutil
import sys

args = sys.argv[1:]
if args[:2] != ["s3api", "put-object"]:
    raise SystemExit(f"unexpected aws command: {args!r}")

def option(name):
    index = args.index(name)
    return args[index + 1]

bucket = option("--bucket")
key = option("--key")
body = pathlib.Path(option("--body"))
if_none_match = option("--if-none-match")
endpoint = option("--endpoint-url") if "--endpoint-url" in args else None
state_dir = pathlib.Path(os.environ["FAKE_AWS_STATE"])
state_dir.mkdir(parents=True, exist_ok=True)
object_path = state_dir / key.replace("/", "__")
log_path = pathlib.Path(os.environ["FAKE_AWS_LOG"])
with log_path.open("a", encoding="utf-8") as log:
    log.write(json.dumps({
        "args": args,
        "bucket": bucket,
        "key": key,
        "body": str(body),
        "if_none_match": if_none_match,
        "endpoint": endpoint,
    }) + "\\n")
if object_path.exists() and if_none_match == "*":
    print(f"Precondition failed for s3://{bucket}/{key}", file=sys.stderr)
    raise SystemExit(412)
shutil.copyfile(body, object_path)
""",
    encoding="utf-8",
)
for tool in ("date", "pg_dump", "gzip", "aws"):
    (fake_bin / tool).chmod(0o755)

def run(mode, backup_dir, run_id):
    env = os.environ.copy()
    env.update(
        {
            "PATH": f"{fake_bin}:{env['PATH']}",
            "BACKUP_DIR": str(backup_dir),
            "FIREMUD_POSTGRES_HOST": "localhost",
            "FIREMUD_POSTGRES_USER": "firemud",
            "FIREMUD_POSTGRES_DB": "firemud",
            "FAKE_MODE": mode,
            "FAKE_RUN": run_id,
        }
    )
    return subprocess.run([str(script)], env=env, text=True, capture_output=True, timeout=30)

for mode in ("fail_pg_dump", "fail_gzip"):
    backup_dir = tmp / mode
    result = run(mode, backup_dir, mode)
    if result.returncode == 0:
        raise SystemExit(f"{mode} unexpectedly succeeded: {result.stdout!r} {result.stderr!r}")
    final = backup_dir / "15min/firemud_20260801001234.sql.gz"
    partials = list((backup_dir / "15min").glob(".firemud_*.sql.gz"))
    if final.exists() or partials:
        raise SystemExit(f"{mode} left a published or partial artifact: {final} {partials}")

backup_dir = tmp / "same-second-successes"
envs = []
for run_id in ("one", "two"):
    env = os.environ.copy()
    env.update(
        {
            "PATH": f"{fake_bin}:{env['PATH']}",
            "BACKUP_DIR": str(backup_dir),
            "FIREMUD_POSTGRES_HOST": "localhost",
            "FIREMUD_POSTGRES_USER": "firemud",
            "FIREMUD_POSTGRES_DB": "firemud",
            "FAKE_MODE": "success",
            "FAKE_RUN": run_id,
        }
    )
    envs.append(env)
processes = [subprocess.Popen([str(script)], env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE) for env in envs]
results = [process.communicate(timeout=30) for process in processes]
statuses = [process.returncode for process in processes]
if sorted(statuses) != [0, 1]:
    raise SystemExit(f"same-second successes did not produce one winner and one loser: {statuses} {results}")
final = backup_dir / "15min/firemud_20260801001234.sql.gz"
partials = list((backup_dir / "15min").glob(".firemud_*.sql.gz"))
if not final.exists() or partials:
    raise SystemExit(f"same-second publication did not leave exactly one final artifact: {final} {partials}")
if final.read_text(encoding="utf-8") not in ("dump-one", "dump-two"):
    raise SystemExit(f"same-second winner contains unexpected bytes: {final.read_text(encoding='utf-8')!r}")

def run_upload(backup_dir, run_id, state_dir, log_path, endpoint_capability=False):
    env = os.environ.copy()
    env.update(
        {
            "PATH": f"{fake_bin}:{env['PATH']}",
            "BACKUP_DIR": str(backup_dir),
            "FIREMUD_POSTGRES_HOST": "localhost",
            "FIREMUD_POSTGRES_USER": "firemud",
            "FIREMUD_POSTGRES_DB": "firemud",
            "FAKE_MODE": "success",
            "FAKE_RUN": run_id,
            "PG_DUMP_BUCKET": "firemud-backups",
            "PG_DUMP_ENDPOINT": "http://minio.example.test:9000",
            "PG_DUMP_ENDPOINT_IF_NONE_MATCH_CONFIRMED": "true" if endpoint_capability else "false",
            "FAKE_AWS_STATE": str(state_dir),
            "FAKE_AWS_LOG": str(log_path),
        }
    )
    return subprocess.run([str(script)], env=env, text=True, capture_output=True, timeout=30)

upload_root = tmp / "s3-publication"
state_dir = upload_root / "objects"
log_path = upload_root / "aws.jsonl"
unsafe_upload = run_upload(upload_root / "unconfirmed", "unconfirmed", state_dir, log_path)
if unsafe_upload.returncode == 0 or "PG_DUMP_ENDPOINT_IF_NONE_MATCH_CONFIRMED=true" not in unsafe_upload.stderr:
    raise SystemExit(
        "custom S3 endpoint without immutable-publication capability proof did not fail closed: "
        f"{unsafe_upload.returncode} {unsafe_upload.stdout!r} {unsafe_upload.stderr!r}"
    )
first_upload = run_upload(upload_root / "first", "first", state_dir, log_path, endpoint_capability=True)
if first_upload.returncode != 0:
    raise SystemExit(f"S3 publication unexpectedly failed: {first_upload.stdout!r} {first_upload.stderr!r}")
calls = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines()]
expected_key_suffix = "firemud_20260801001234.sql.gz"
expected_keys = {
    "15min/" + expected_key_suffix,
    "daily/" + expected_key_suffix,
    "weekly/" + expected_key_suffix,
    "monthly/" + expected_key_suffix,
}
if {call["key"] for call in calls} != expected_keys or len(calls) != 4:
    raise SystemExit(f"S3 publication used unexpected keys or count: {calls!r}")
for call in calls:
    if call["bucket"] != "firemud-backups":
        raise SystemExit(f"S3 publication used the wrong bucket: {call!r}")
    if call["if_none_match"] != "*":
        raise SystemExit(f"S3 publication omitted If-None-Match '*': {call!r}")
    if call["endpoint"] != "http://minio.example.test:9000":
        raise SystemExit(f"S3 publication omitted the configured endpoint: {call!r}")
    if pathlib.Path(call["body"]).read_text(encoding="utf-8") != "dump-first":
        raise SystemExit(f"S3 publication used the wrong body: {call!r}")

second_upload = run_upload(upload_root / "second", "second", state_dir, log_path, endpoint_capability=True)
if second_upload.returncode == 0 or "Failed to upload 15min dump" not in second_upload.stderr:
    raise SystemExit(
        "an existing S3 object did not fail closed: "
        f"{second_upload.returncode} {second_upload.stdout!r} {second_upload.stderr!r}"
    )
if (state_dir / ("15min__" + expected_key_suffix)).read_text(encoding="utf-8") != "dump-first":
    raise SystemExit("an S3 precondition conflict silently overwrote the existing object")
PY

python3 - <<'PY' "$ROOT_DIR" "$OPERATOR_REPORT_PATH"
import copy
import importlib.util
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("preflight_operator_report_contract", root / "dev-tools/deploy/preflight.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

expected_rfc8785_digest = "sha256:fd8b688bfa8b71822975ab3519e20b09e43b67d382a9f32831bfa384df21a82d"
actual_rfc8785_digest = module.canonical_evidence_digest({"\ufffd": 2, "\U0001f600": 1})
if actual_rfc8785_digest != expected_rfc8785_digest:
    raise SystemExit(f"RFC 8785 UTF-16 key ordering drifted: {actual_rfc8785_digest}")
for invalid_record in ({"value": 9_007_199_254_740_992}, {"value": 1.5}):
    try:
        module.canonical_evidence_digest(invalid_record)
    except TypeError:
        pass
    else:
        raise SystemExit(f"non-interoperable evidence number was accepted: {invalid_record}")

requirements = module.expected_preflight_policy_requirements("hobby-self-hosted", None)
checks = [
    module.CheckResult(
        policy_id,
        required,
        "pass" if required or policy_id == "PREFLIGHT-DIGEST-002" else "not_applicable",
        "contract evidence",
    )
    for policy_id, required in requirements.items()
]
report_timestamp = module.utc_now()
module.write_report(
    output,
    "hobby-self-hosted",
    "contract-hobby",
    report_timestamp,
    report_timestamp,
    checks,
    "operator",
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "66666666-6666-4666-8666-666666666666",
    "",
    module.immutable_file_digest(root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"),
)
try:
    module.write_report(
        output,
        "hobby-self-hosted",
        "contract-hobby",
        report_timestamp,
        report_timestamp,
        checks,
        "operator",
        "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
        "66666666-6666-4666-8666-666666666666",
        "",
        module.immutable_file_digest(root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"),
    )
except SystemExit as exc:
    if exc.code != 1:
        raise SystemExit(f"existing report was rejected for the wrong reason: {exc}") from exc
else:
    raise SystemExit("write_report overwrote an existing output")
for invalid_ref in ("../escape", "UpperCase", "contains/slash", "contains_underscore"):
    if module.DEPLOYMENT_REF_RE.fullmatch(invalid_ref):
        raise SystemExit(f"invalid deployment ref was accepted: {invalid_ref}")
if module.operator_deployment_ref_is_current("staging", "contract-staging", "a" * 40):
    raise SystemExit("arbitrary staging deployment ref was accepted")
if not module.operator_deployment_ref_is_current("staging", "a" * 40, "a" * 40):
    raise SystemExit("current full staging commit SHA was rejected")
if not module.operator_deployment_ref_is_current("hobby-self-hosted", "contract-hobby", "a" * 40):
    raise SystemExit("hobby deployment ref compatibility was lost")
attestation_sha = "0123456789abcdef" * 2 + "01234567"
canonical_attestation = module.canonical_promotion_attestation_ref(attestation_sha)
if canonical_attestation != f"design/operations/deployments/production/attestations/{attestation_sha}.json":
    raise SystemExit(f"canonical promotion attestation ref drifted: {canonical_attestation}")
if not module.is_canonical_promotion_attestation_ref(canonical_attestation, attestation_sha, root_dir=root):
    raise SystemExit("canonical promotion attestation ref was rejected")
for external_ref in (
    "/tmp/promotion-attestation.json",
    "design/operations/deployments/production/attestations/other.json",
    "../production-attestation.json",
):
    if module.is_canonical_promotion_attestation_ref(external_ref, attestation_sha, root_dir=root):
        raise SystemExit(f"external promotion attestation ref was accepted: {external_ref}")
for malformed_sha in ("0123456789abcdef", "../" + "a" * 39, "a" * 41):
    if module.canonical_promotion_attestation_ref(malformed_sha) is not None:
        raise SystemExit(f"malformed promotion deployment ref was canonicalized: {malformed_sha}")
owned_document = {
    "kind": "Deployment",
    "metadata": {"name": "account-service", "namespace": "firemud"},
    "spec": {
        "template": {
            "spec": {
                "volumes": [
                    {"name": "jwt-signing-keys", "secret": {"secretName": "jwt-signing-keys"}},
                    {"name": "jwt-jwks", "secret": {"secretName": "jwt-jwks"}},
                ],
                "containers": [
                    {
                        "name": "account-service",
                        "envFrom": [{"secretRef": {"name": "postgres-credentials"}}],
                        "volumeMounts": [
                            {"name": "jwt-signing-keys", "mountPath": "/var/run/secrets/firemud/jwt", "readOnly": True},
                            {"name": "jwt-jwks", "mountPath": "/var/run/secrets/firemud/jwks", "readOnly": True},
                        ],
                    }
                ],
            }
        }
    },
}
if not module.rendered_secret_binding_is_owned(
    [owned_document], "postgres-credentials", "firemud", "internalBindings.postgres.credentialsRef"
):
    raise SystemExit("owning workload postgres binding was not proven")
if not module.rendered_secret_binding_is_owned(
    [owned_document], "jwt-signing-keys", "firemud", "internalBindings.jwt.signingKeysRef"
):
    raise SystemExit("owning workload JWT signing binding was not proven")
unrelated_document = copy.deepcopy(owned_document)
unrelated_document["spec"]["template"]["spec"]["containers"][0]["volumeMounts"][0]["mountPath"] = "/unrelated"
if module.rendered_secret_binding_is_owned(
    [unrelated_document], "jwt-signing-keys", "firemud", "internalBindings.jwt.signingKeysRef"
):
    raise SystemExit("unrelated Secret reference incorrectly satisfied a JWT binding")
for binding_path, secret_name in (
    ("internalBindings.postgres.credentialsRef", "postgres-credentials"),
    ("internalBindings.jwt.signingKeysRef", "jwt-signing-keys"),
):
    unrelated = copy.deepcopy(owned_document)
    unrelated["metadata"]["name"] = "unrelated-service"
    unrelated["spec"]["template"]["spec"]["containers"][0]["name"] = "unrelated-service"
    if module.rendered_secret_binding_is_owned([unrelated], secret_name, "firemud", binding_path):
        raise SystemExit(f"unrelated workload satisfied {binding_path}")
missing_name = copy.deepcopy(owned_document)
missing_name["metadata"].pop("name")
if module.rendered_secret_binding_is_owned(
    [missing_name], "postgres-credentials", "firemud", "internalBindings.postgres.credentialsRef"
):
    raise SystemExit("workload with missing metadata.name satisfied a Secret binding")
missing_namespace = copy.deepcopy(owned_document)
missing_namespace["metadata"].pop("namespace")
if module.rendered_secret_binding_is_owned(
    [missing_namespace], "postgres-credentials", "firemud", "internalBindings.postgres.credentialsRef"
):
    raise SystemExit("workload with missing metadata.namespace satisfied a Secret binding")
duplicate_workload = copy.deepcopy(owned_document)
if module.rendered_secret_binding_is_owned(
    [owned_document, duplicate_workload],
    "postgres-credentials",
    "firemud",
    "internalBindings.postgres.credentialsRef",
):
    raise SystemExit("duplicate owning workloads satisfied a Secret binding")
duplicate_container = copy.deepcopy(owned_document)
primary_spec = duplicate_container["spec"]["template"]["spec"]
primary_spec["containers"].append(copy.deepcopy(primary_spec["containers"][0]))
if module.rendered_secret_binding_is_owned(
    [duplicate_container],
    "postgres-credentials",
    "firemud",
    "internalBindings.postgres.credentialsRef",
):
    raise SystemExit("duplicate primary containers satisfied a Secret binding")
wrong_env = copy.deepcopy(owned_document)
wrong_env["spec"]["template"]["spec"]["containers"][0]["envFrom"] = [
    {"configMapRef": {"name": "firemud-config"}}
]
wrong_env["spec"]["template"]["spec"]["containers"][0]["env"] = [
    {"name": "POSTGRES_USER", "valueFrom": {"secretKeyRef": {"name": "postgres-credentials", "key": "user"}}}
]
if module.rendered_secret_binding_is_owned(
    [wrong_env], "postgres-credentials", "firemud", "internalBindings.postgres.credentialsRef"
):
    raise SystemExit("wrong Secret env binding was accepted")

def assert_binding_rejected(document, secret_name, binding_path, label):
    if module.rendered_secret_binding_is_owned([document], secret_name, "firemud", binding_path):
        raise SystemExit(f"{label} incorrectly satisfied {binding_path}")


postgres_mount_probe = copy.deepcopy(owned_document)
postgres_mount_probe["spec"]["template"]["spec"]["volumes"].append(
    {"name": "postgres-credentials", "secret": {"secretName": "postgres-credentials"}}
)
postgres_mount_probe["spec"]["template"]["spec"]["containers"][0]["volumeMounts"].append(
    {"name": "postgres-credentials", "mountPath": "/var/run/secrets/firemud/postgres", "readOnly": True}
)
assert_binding_rejected(
    postgres_mount_probe,
    "postgres-credentials",
    "internalBindings.postgres.credentialsRef",
    "PostgreSQL envFrom plus same-Secret mount",
)
postgres_key_probe = copy.deepcopy(owned_document)
postgres_key_probe["spec"]["template"]["spec"]["containers"][0].setdefault("env", []).append(
    {"name": "POSTGRES_PASSWORD", "valueFrom": {"secretKeyRef": {"name": "postgres-credentials", "key": "password"}}}
)
assert_binding_rejected(
    postgres_key_probe,
    "postgres-credentials",
    "internalBindings.postgres.credentialsRef",
    "PostgreSQL envFrom plus same-Secret secretKeyRef",
)
signing_env_probe = copy.deepcopy(owned_document)
signing_env_probe["spec"]["template"]["spec"]["containers"][0]["envFrom"].append(
    {"secretRef": {"name": "jwt-signing-keys"}}
)
assert_binding_rejected(
    signing_env_probe,
    "jwt-signing-keys",
    "internalBindings.jwt.signingKeysRef",
    "JWT signing mount plus same-Secret envFrom",
)
signing_key_probe = copy.deepcopy(owned_document)
signing_key_probe["spec"]["template"]["spec"]["containers"][0].setdefault("env", []).append(
    {"name": "JWT_KEY", "valueFrom": {"secretKeyRef": {"name": "jwt-signing-keys", "key": "current.key"}}}
)
assert_binding_rejected(
    signing_key_probe,
    "jwt-signing-keys",
    "internalBindings.jwt.signingKeysRef",
    "JWT signing mount plus same-Secret secretKeyRef",
)
signing_wrong_mount_probe = copy.deepcopy(owned_document)
signing_wrong_mount_probe["spec"]["template"]["spec"]["containers"][0]["volumeMounts"].append(
    {"name": "jwt-signing-keys", "mountPath": "/var/run/secrets/firemud/other", "readOnly": True}
)
assert_binding_rejected(
    signing_wrong_mount_probe,
    "jwt-signing-keys",
    "internalBindings.jwt.signingKeysRef",
    "JWT signing plus same-Secret wrong mount path",
)
jwks_env_probe = copy.deepcopy(owned_document)
jwks_env_probe["spec"]["template"]["spec"]["containers"][0]["envFrom"].append(
    {"secretRef": {"name": "jwt-jwks"}}
)
assert_binding_rejected(
    jwks_env_probe,
    "jwt-jwks",
    "internalBindings.jwt.jwksRef",
    "JWKS mount plus same-Secret envFrom",
)
jwks_key_probe = copy.deepcopy(owned_document)
jwks_key_probe["spec"]["template"]["spec"]["containers"][0].setdefault("env", []).append(
    {"name": "JWKS", "valueFrom": {"secretKeyRef": {"name": "jwt-jwks", "key": "jwks.json"}}}
)
assert_binding_rejected(
    jwks_key_probe,
    "jwt-jwks",
    "internalBindings.jwt.jwksRef",
    "JWKS mount plus same-Secret secretKeyRef",
)
jwks_wrong_mount_probe = copy.deepcopy(owned_document)
jwks_wrong_mount_probe["spec"]["template"]["spec"]["containers"][0]["volumeMounts"].append(
    {"name": "jwt-jwks", "mountPath": "/var/run/secrets/firemud/other-jwks", "readOnly": True}
)
assert_binding_rejected(
    jwks_wrong_mount_probe,
    "jwt-jwks",
    "internalBindings.jwt.jwksRef",
    "JWKS plus same-Secret wrong mount path",
)
PY

cat >"$RENDERED_MANIFEST" <<'YAML'
apiVersion: v1
kind: ConfigMap
metadata:
  name: firemud-config
data:
  FIREMUD_AUTH_JWT_SECRET_PATH: /var/run/secrets/firemud/jwt/current.key
  FIREMUD_AUTH_JWKS_PATH: /var/run/secrets/firemud/jwks/jwks.json
  FIREMUD_REDIS_COORD_HOST: redis-coord
  FIREMUD_REDIS_COORD_PORT: "6379"
  FIREMUD_REDIS_CACHE_HOST: redis-cache
  FIREMUD_REDIS_CACHE_PORT: "6379"
---
apiVersion: v1
kind: Service
metadata:
  namespace: firemud
  name: spring-cloud-gateway-mtls
spec:
  type: ClusterIP
  ports:
    - port: 443
---
apiVersion: v1
kind: Secret
metadata:
  name: postgres-credentials
type: Opaque
stringData:
  FIREMUD_POSTGRES_USER: firemud
  FIREMUD_POSTGRES_PASSWORD: firemud
---
apiVersion: v1
kind: Secret
metadata:
  name: jwt-signing-keys
type: Opaque
stringData:
  current.key: changeit-changeit-changeit-changeit
---
apiVersion: v1
kind: Secret
metadata:
  name: jwt-jwks
type: Opaque
stringData:
  jwks.json: '{"keys":[]}'
---
apiVersion: v1
kind: Secret
metadata:
  name: hobby-tcp-proxy-bridge
type: Opaque
stringData:
  client.crt: bridge-client
  client.key: bridge-key
  ca.crt: bridge-ca
---
apiVersion: v1
kind: Secret
metadata:
  name: grpc-tls
type: Opaque
stringData:
  client.crt: grpc-client
  client.key: grpc-key
  ca.crt: grpc-ca
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: firemud-app
imagePullSecrets:
  - name: ghcr-pull-hobby
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tcp-proxy-service
spec:
  template:
    spec:
      serviceAccountName: firemud-app
      containers:
        - name: tcp-proxy-service
          image: ghcr.io/benhook1013/tcp-proxy-service:latest
          env:
            - name: FIREMUD_GRPC_CERT_CHAIN_PATH
              value: /grpc-tls/client.crt
            - name: FIREMUD_GRPC_PRIVATE_KEY_PATH
              value: /grpc-tls/client.key
            - name: FIREMUD_GRPC_CA_CERT_PATH
              value: /grpc-tls/ca.crt
            - name: GATEWAY_WS_URL
              value: wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local/ws/game
            - name: FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH
              value: /tls/client.crt
            - name: FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH
              value: /tls/client.key
            - name: FIREMUD_GATEWAY_WS_CA_CERT_PATH
              value: /tls/ca.crt
          envFrom:
            - secretRef:
                name: postgres-credentials
            - configMapRef:
                name: firemud-config
          volumeMounts:
            - name: hobby-tcp-proxy-bridge
              mountPath: /tls
              readOnly: true
            - name: grpc-tls
              mountPath: /grpc-tls
              readOnly: true
            - name: jwt-signing-keys
              mountPath: /var/run/secrets/firemud/jwt
              readOnly: true
      volumes:
        - name: hobby-tcp-proxy-bridge
          secret:
            secretName: hobby-tcp-proxy-bridge
            items:
              - key: client.crt
                path: client.crt
              - key: client.key
                path: client.key
              - key: ca.crt
                path: ca.crt
        - name: grpc-tls
          secret:
            secretName: grpc-tls
        - name: jwt-signing-keys
          secret:
            secretName: jwt-signing-keys
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: account-service
spec:
  template:
    spec:
      serviceAccountName: firemud-app
      containers:
        - name: account-service
          image: ghcr.io/benhook1013/account-service:latest
          envFrom:
            - secretRef:
                name: postgres-credentials
            - configMapRef:
                name: firemud-config
          volumeMounts:
            - name: jwt-signing-keys
              mountPath: /var/run/secrets/firemud/jwt
              readOnly: true
            - name: jwt-jwks
              mountPath: /var/run/secrets/firemud/jwks
              readOnly: true
      volumes:
        - name: jwt-signing-keys
          secret:
            secretName: jwt-signing-keys
        - name: jwt-jwks
          secret:
            secretName: jwt-jwks
YAML

python3 - <<'PY' "$RENDERED_MANIFEST"
import pathlib
import sys

import yaml

path = pathlib.Path(sys.argv[1])
documents = [
    document
    for document in yaml.safe_load_all(path.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
for document in documents:
    document.setdefault("metadata", {})["namespace"] = "firemud"
path.write_text(yaml.safe_dump_all(documents, sort_keys=False), encoding="utf-8")
PY

python3 - <<'PY' "$RENDERED_MANIFEST" "$MIGRATED_RENDERED_MANIFEST"
import pathlib
import sys

import yaml

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
documents = [
    document
    for document in yaml.safe_load_all(source.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
found_jwks_resource = False
found_account_wiring = False
for document in documents:
    metadata = document.get("metadata") or {}
    if document.get("kind") == "Secret" and metadata.get("name") == "jwt-jwks":
        document["kind"] = "ConfigMap"
        document.pop("type", None)
        document.pop("stringData", None)
        document["data"] = {
            "jwks.json": '{"keys":[{"kty":"RSA","kid":"contract-jwks"}]}'
        }
        found_jwks_resource = True
    if document.get("kind") != "Deployment" or metadata.get("name") != "account-service":
        continue
    pod_spec = document["spec"]["template"]["spec"]
    jwks_volume = next(
        (
            volume
            for volume in pod_spec.get("volumes", [])
            if volume.get("name") == "jwt-jwks"
        ),
        None,
    )
    if jwks_volume is None:
        raise SystemExit("migrated ConfigMap fixture is missing the Account jwt-jwks volume")
    jwks_volume.pop("secret", None)
    jwks_volume["configMap"] = {"name": "jwt-jwks"}
    account_container = next(
        container
        for container in pod_spec.get("containers", [])
        if container.get("name") == "account-service"
    )
    jwks_mount = next(
        (
            mount
            for mount in account_container.get("volumeMounts", [])
            if mount.get("name") == "jwt-jwks"
        ),
        None,
    )
    if jwks_mount is None:
        raise SystemExit("migrated ConfigMap fixture is missing the Account jwt-jwks mount")
    if jwks_mount.get("mountPath") != "/var/run/secrets/firemud/jwks":
        raise SystemExit("migrated ConfigMap fixture has an unexpected Account JWKS mount path")
    found_account_wiring = True

if not found_jwks_resource or not found_account_wiring:
    raise SystemExit("migrated ConfigMap fixture did not contain the expected JWKS resource and Account wiring")

destination.write_text(yaml.safe_dump_all(documents, sort_keys=False), encoding="utf-8")
PY

python3 - <<'PY' "$RENDERED_MANIFEST" "$SCRIPT"
import copy
import importlib.util
import pathlib
import sys

import yaml

rendered_path = pathlib.Path(sys.argv[1])
preflight_path = pathlib.Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("preflight_jwt_jwks_contract", preflight_path)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

helper_root = rendered_path.parent / "attestation-helper-root"
attestation_dir = helper_root / "design/operations/deployments/production/attestations"
attestation_dir.mkdir(parents=True)
helper_sha = "a" * 40
(helper_root / "outside.json").write_text("{}", encoding="utf-8")
(attestation_dir / f"{helper_sha}.json").symlink_to(helper_root / "outside.json")
canonical_helper_ref = module.canonical_promotion_attestation_ref(helper_sha)
if module.is_canonical_promotion_attestation_ref(
    canonical_helper_ref, helper_sha, root_dir=helper_root
):
    raise SystemExit("symlinked promotion attestation escaped canonical ownership checks")
if module.is_canonical_promotion_attestation_ref(
    "design/operations/deployments/production/attestations/../attestations/" + f"{helper_sha}.json",
    helper_sha,
    root_dir=helper_root,
):
    raise SystemExit("traversal promotion attestation ref was accepted")

legacy_documents = [
    document
    for document in yaml.safe_load_all(rendered_path.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]


def account_deployment(documents):
    for document in documents:
        if document.get("kind") != "Deployment" or document.get("metadata", {}).get("name") != "account-service":
            continue
        return document
    raise SystemExit("JWT/JWKS fixture is missing account-service deployment")


def account_container(document):
    containers = document["spec"]["template"]["spec"].get("containers") or []
    for container in containers:
        if container.get("name") == "account-service":
            return container
    raise SystemExit("JWT/JWKS fixture is missing account-service container")


def public_config_map_documents():
    documents = copy.deepcopy(legacy_documents)
    jwks = next(
        document
        for document in documents
        if document.get("metadata", {}).get("name") == "jwt-jwks"
    )
    jwks["kind"] = "ConfigMap"
    jwks.pop("type", None)
    string_data = jwks.pop("stringData", None)
    secret_data = jwks.pop("data", None)
    config_map_data = string_data if string_data else secret_data
    if not isinstance(config_map_data, dict) or not config_map_data:
        raise SystemExit("JWT/JWKS Secret fixture is missing usable source data")
    jwks["data"] = config_map_data
    account = account_deployment(documents)
    pod_spec = account["spec"]["template"]["spec"]
    jwks_volume = next(volume for volume in pod_spec["volumes"] if volume.get("name") == "jwt-jwks")
    jwks_volume.pop("secret", None)
    jwks_volume["configMap"] = {"name": "jwt-jwks"}
    return documents


def jwks_result(documents):
    results = {
        result.policy_id: result
        for result in module.jwt_jwks_checks(documents, "firemud")
    }
    return results["PREFLIGHT-JWKS-001"]


public_documents = public_config_map_documents()
public_result = jwks_result(public_documents)
if public_result.status != "fail" or "configured as a ConfigMap" not in public_result.message:
    raise SystemExit(f"public ConfigMap unexpectedly satisfied the legacy Secret contract: {public_result.message}")

missing_mount_documents = copy.deepcopy(legacy_documents)
missing_account = account_deployment(missing_mount_documents)
missing_account_container = account_container(missing_account)
missing_account_container["volumeMounts"] = [
    mount
    for mount in missing_account_container["volumeMounts"]
    if mount.get("name") != "jwt-jwks"
]
missing_mount_result = jwks_result(missing_mount_documents)
if missing_mount_result.status != "fail" or "does not mount" not in missing_mount_result.message:
    raise SystemExit(f"missing Account jwt-jwks mount did not fail closed: {missing_mount_result.message}")

wrong_mount_documents = copy.deepcopy(legacy_documents)
wrong_account = account_deployment(wrong_mount_documents)
wrong_account_container = account_container(wrong_account)
wrong_mount = next(
    mount
    for mount in wrong_account_container["volumeMounts"]
    if mount.get("name") == "jwt-jwks"
)
wrong_mount["mountPath"] = "/var/run/secrets/firemud/wrong-jwks"
wrong_mount_result = jwks_result(wrong_mount_documents)
if wrong_mount_result.status != "fail" or "does not mount" not in wrong_mount_result.message:
    raise SystemExit(f"wrong Account jwt-jwks mount did not fail closed: {wrong_mount_result.message}")

secret_documents = copy.deepcopy(legacy_documents)
secret_result = jwks_result(secret_documents)
if secret_result.status != "pass":
    raise SystemExit(f"legacy Secret jwt-jwks did not satisfy the current contract: {secret_result.message}")

expected_bindings_path = pathlib.Path(
    preflight_path.parents[2],
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
)
expected_bindings = yaml.safe_load(expected_bindings_path.read_text(encoding="utf-8"))
mutated_expected_bindings = copy.deepcopy(expected_bindings)
mutated_expected_bindings["internalBindings"]["jwt"]["jwksRef"] = (
    "secret://firemud/renamed-jwt-jwks"
)
mutated_documents = copy.deepcopy(legacy_documents)


def rename_jwks_references(node):
    if isinstance(node, dict):
        for key, value in node.items():
            if isinstance(value, str) and value == "jwt-jwks":
                node[key] = "renamed-jwt-jwks"
            else:
                rename_jwks_references(value)
    elif isinstance(node, list):
        for value in node:
            rename_jwks_references(value)


rename_jwks_references(mutated_documents)
mutated_binding_results = module.expected_binding_checks(
    expected_bindings_path,
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "hobby-self-hosted",
    mutated_documents,
    context="ci-static",
    expected_bindings=mutated_expected_bindings,
)
mutated_binding_result = next(
    result
    for result in mutated_binding_results
    if result.policy_id == "PREFLIGHT-SECRETS-002"
)
if mutated_binding_result.status != "fail" or "must be exactly" not in mutated_binding_result.message:
    raise SystemExit(
        "noncanonical legacy jwt-jwks binding was accepted: "
        f"{mutated_binding_result.message}"
    )

namespace_less_documents = copy.deepcopy(legacy_documents)
for document in namespace_less_documents:
    document.get("metadata", {}).pop("namespace", None)
if module.rendered_secret_binding_is_owned(
    namespace_less_documents,
    "jwt-jwks",
    "firemud",
    "internalBindings.jwt.jwksRef",
):
    raise SystemExit("namespace-less jwt-jwks workload incorrectly satisfied the binding contract")

wrong_namespace_documents = copy.deepcopy(legacy_documents)
wrong_namespace_secret = next(
    document
    for document in wrong_namespace_documents
    if document.get("kind") == "Secret"
    and document.get("metadata", {}).get("name") == "jwt-jwks"
)
wrong_namespace_secret["metadata"]["namespace"] = "other"
wrong_namespace_result = jwks_result(wrong_namespace_documents)
if wrong_namespace_result.status != "fail" or "expected namespace" not in wrong_namespace_result.message:
    raise SystemExit(
        f"same-name jwt-jwks Secret in the wrong namespace was accepted: {wrong_namespace_result.message}"
    )

namespace_reference_document = {
    "kind": "Deployment",
    "metadata": {"name": "account-service"},
    "spec": {
        "template": {
            "spec": {
                "containers": [
                    {
                        "name": "contract",
                        "envFrom": [{"secretRef": {"name": "postgres-credentials"}}],
                    }
                ]
            }
        }
    },
}
if module.rendered_secret_binding_is_owned(
    [namespace_reference_document],
    "postgres-credentials",
    "firemud",
    "internalBindings.postgres.credentialsRef",
):
    raise SystemExit("namespace-less Secret reference incorrectly satisfied the ownership contract")
namespace_reference_document["metadata"]["namespace"] = "other"
if module.rendered_secret_binding_is_owned(
    [namespace_reference_document],
    "postgres-credentials",
    "firemud",
    "internalBindings.postgres.credentialsRef",
):
    raise SystemExit("Secret reference with an explicit wrong namespace was accepted")

image_pull_namespace_reference_document = {
    "kind": "ServiceAccount",
    "metadata": {"name": "image-pull-namespace-reference"},
    "imagePullSecrets": [{"name": "ghcr-pull-hobby"}],
}
if not module.rendered_references_image_pull_secret(
    [image_pull_namespace_reference_document],
    "ghcr-pull-hobby",
    "firemud",
):
    raise SystemExit("namespace-less image pull Secret reference did not inherit the expected namespace")
image_pull_namespace_reference_document["metadata"]["namespace"] = "other"
if module.rendered_references_image_pull_secret(
    [image_pull_namespace_reference_document],
    "ghcr-pull-hobby",
    "firemud",
):
    raise SystemExit("image pull Secret reference with an explicit wrong namespace was accepted")
PY

# Legacy Secret-backed hobby fixture is the current player-facing contract.
# The checked-in player-facing hobby fixture remains legacy Secret-backed. Keep
# its migration-gap failure explicit while focused cases prove the alternate
# ConfigMap shape is not accepted by the current contract.
set +e
rm -f "$REPORT_PATH"
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby \
  FIREMUD_PREFLIGHT_RENDER_PATH="$RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$REPORT_PATH" \
  python3 "$SCRIPT" hobby-self-hosted >"$LEGACY_HOBBY_PREFLIGHT_OUTPUT"
preflight_status=$?
set -e
if [ "$preflight_status" -ne 0 ]; then
  echo "static CI must not block on the legacy Secret jwt-jwks diagnostic" >&2
  exit 1
fi

python3 - <<'PY' "$ROOT_DIR" "$REPORT_PATH"
import importlib.util
import json
import pathlib
import sys
import uuid

root = pathlib.Path(sys.argv[1])
report = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
spec = importlib.util.spec_from_file_location("preflight_report_contract", root / "dev-tools/deploy/preflight.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)
expected_ids = {
    "PREFLIGHT-DIGEST-001",
    "PREFLIGHT-DIGEST-002",
    "PREFLIGHT-SECRETS-001",
    "PREFLIGHT-SECRETS-002",
    "PREFLIGHT-JWT-001",
    "PREFLIGHT-JWKS-001",
    "PREFLIGHT-BRIDGE-001",
    "PREFLIGHT-REDIS-001",
    "PREFLIGHT-BOOTSTRAP-001",
    "PREFLIGHT-EXTERNAL-001",
    "PREFLIGHT-SERVICES-001",
    "PREFLIGHT-PROMOTION-001",
    "PREFLIGHT-BACKUP-001",
    "PREFLIGHT-BACKUP-002",
    "PREFLIGHT-BACKUP-003",
}
actual_ids = {check["policyId"] for check in report["checkResults"]}
missing = sorted(expected_ids - actual_ids)
if missing:
    raise SystemExit(f"preflight report missing policy IDs: {missing}")
if actual_ids != expected_ids or len(report["checkResults"]) != len(expected_ids):
    raise SystemExit("preflight report did not emit exactly the complete expected policy set")
if report.get("expectedBindingsRef") != "design/operations/environments/hobby-self-hosted/expected-bindings.yaml":
    raise SystemExit("preflight report missing expectedBindingsRef")
if report.get("policyCatalogVersion") != module.PREFLIGHT_POLICY_CATALOG_VERSION:
    raise SystemExit("preflight report missing or mismatched policyCatalogVersion")
try:
    uuid.UUID(report["deploymentEventId"])
except (KeyError, ValueError) as exc:
    raise SystemExit(f"preflight report missing canonical deploymentEventId: {exc}") from exc
if report.get("trafficOpenEvent") is not None:
    raise SystemExit("general preflight report unexpectedly recorded a traffic-open event")
if any(not isinstance(check.get("required"), bool) for check in report["checkResults"]):
    raise SystemExit("preflight report did not emit required applicability for every policy")
if any(
    check.get("category") != module.PREFLIGHT_POLICY_CATALOG.get(check.get("policyId"))
    for check in report["checkResults"]
):
    raise SystemExit("preflight report did not emit the catalogue category for every policy")
failures = [
    check
    for check in report["checkResults"]
    if check["status"] == "fail"
    and check["policyId"] not in {"PREFLIGHT-DIGEST-002", "PREFLIGHT-JWT-001", "PREFLIGHT-JWKS-001"}
]
if failures:
    raise SystemExit(f"unexpected required preflight failures: {failures}")
legacy_jwks = [
    check
    for check in report["checkResults"]
    if check["policyId"] == "PREFLIGHT-JWKS-001"
]
if len(legacy_jwks) != 1 or legacy_jwks[0]["status"] != "pass":
    raise SystemExit(f"legacy Secret jwt-jwks fixture did not pass the current diagnostic: {legacy_jwks}")
for policy_id in ("PREFLIGHT-JWT-001", "PREFLIGHT-JWKS-001"):
    diagnostic = [check for check in report["checkResults"] if check["policyId"] == policy_id]
    if len(diagnostic) != 1 or diagnostic[0]["required"]:
        raise SystemExit(f"{policy_id} diagnostic was incorrectly apply-blocking: {diagnostic}")
PY

# A ConfigMap-backed player-facing fixture remains deferred and must fail required binding checks.
set +e
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby-migrated \
  FIREMUD_PREFLIGHT_RENDER_PATH="$MIGRATED_RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$MIGRATED_REPORT_PATH" \
  python3 "$SCRIPT" hobby-self-hosted >"$MIGRATED_HOBBY_PREFLIGHT_OUTPUT"
migrated_preflight_status=$?
set -e
if [ "$migrated_preflight_status" -eq 0 ]; then
  echo "deferred ConfigMap-backed hobby fixture unexpectedly passed preflight" >&2
  exit 1
fi

python3 - <<'PY' "$MIGRATED_REPORT_PATH"
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
required_failures = [
    check
    for check in report["checkResults"]
    if check["required"] and check["status"] == "fail"
]
if not any(check["policyId"] == "PREFLIGHT-SECRETS-001" for check in required_failures):
    raise SystemExit(f"deferred ConfigMap fixture did not fail the required Secret check: {required_failures}")
migrated_jwks = [
    check
    for check in report["checkResults"]
    if check["policyId"] == "PREFLIGHT-JWKS-001"
]
if len(migrated_jwks) != 1 or migrated_jwks[0]["status"] != "fail":
    raise SystemExit(f"deferred ConfigMap fixture did not retain the advisory diagnostic: {migrated_jwks}")
PY

python3 "$WRITER" hobby-self-hosted contract-hobby first-live \
  --assessed-by preflight-contract \
  --preflight-report "$OPERATOR_REPORT_PATH" \
  --evidence-ref contract-test \
  --output "$TRAFFIC_EVIDENCE" >"$HOBBY_TRAFFIC_WRITER_OUTPUT"

python3 - <<'PY' "$OPERATOR_REPORT_PATH" "$TRAFFIC_EVIDENCE"
import json
import pathlib
import sys
import uuid

preflight = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
traffic = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
preflight_event_id = preflight.get("deploymentEventId")
traffic_event_id = traffic.get("deploymentEventId")
for label, event_id in (("preflight", preflight_event_id), ("traffic-open", traffic_event_id)):
    if not isinstance(event_id, str):
        raise SystemExit(f"{label} deploymentEventId is missing")
    try:
        parsed_event_id = uuid.UUID(event_id)
    except ValueError as exc:
        raise SystemExit(f"{label} deploymentEventId is not a UUID: {exc}") from exc
    if str(parsed_event_id) != event_id:
        raise SystemExit(f"{label} deploymentEventId is not canonical")
if traffic_event_id != preflight_event_id:
    raise SystemExit("traffic-open writer did not preserve the preflight deploymentEventId")
PY

rm -f "$REPORT_PATH"
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby \
  FIREMUD_PREFLIGHT_RENDER_PATH="$RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$REPORT_PATH" \
  FIREMUD_TRAFFIC_OPEN_EVENT=first-live \
  python3 "$SCRIPT" hobby-self-hosted >"$TRAFFIC_HOBBY_PREFLIGHT_OUTPUT" 2>&1 && {
    echo "expected hobby first-live preflight without controller authority to fail" >&2
    exit 1
  }

python3 - <<'PY' "$REPORT_PATH"
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
backup_003 = [
    check
    for check in report["checkResults"]
    if check["policyId"] == "PREFLIGHT-BACKUP-003"
]
if len(backup_003) != 1 or backup_003[0]["status"] != "fail":
    raise SystemExit(f"PREFLIGHT-BACKUP-003 did not fail closed: {backup_003}")
if "durable environment-wide recovery-controller authority is not implemented" not in backup_003[0]["message"]:
    raise SystemExit(f"PREFLIGHT-BACKUP-003 failed for the wrong reason: {backup_003}")
PY

python3 - <<'PY' "$ROOT_DIR" "$TMP_DIR"
import ast
import hashlib
import importlib.util
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
tmp = pathlib.Path(sys.argv[2])
preflight_path = root / "dev-tools/deploy/preflight.py"
preflight_source = preflight_path.read_text(encoding="utf-8")
try:
    preflight_tree = ast.parse(preflight_source, filename=str(preflight_path))
except SyntaxError as exc:
    raise SystemExit(f"could not parse preflight.py for main contract: {exc}") from exc

main_nodes = [
    node
    for node in preflight_tree.body
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == "main"
]
if len(main_nodes) != 1:
    raise SystemExit(f"expected exactly one top-level main function in preflight.py, found {len(main_nodes)}")


def called_name(node):
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return node.attr
    return None


class MainContractVisitor(ast.NodeVisitor):
    promotion_attestation_reload = False
    recovery_compatibility_evaluations = 0

    def visit_Call(self, node):
        if called_name(node.func) == "load_json" and any(
            isinstance(child, ast.Name) and child.id == "promotion_attestation"
            for argument in node.args
            for child in ast.walk(argument)
        ):
            self.promotion_attestation_reload = True
        if called_name(node.func) == "recovery_compatibility_check":
            self.recovery_compatibility_evaluations += 1
        self.generic_visit(node)


main_contract = MainContractVisitor()
main_contract.visit(main_nodes[0])
if main_contract.promotion_attestation_reload:
    raise SystemExit("production preflight reloaded the promotion attestation inside main")
if main_contract.recovery_compatibility_evaluations:
    raise SystemExit("production preflight duplicated recovery compatibility evaluation inside main")

spec = importlib.util.spec_from_file_location("preflight_hobby_contract", root / "dev-tools/deploy/preflight.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

deployment_event_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
validation_now = module.dt.datetime(2026, 1, 1, 0, 5, tzinfo=module.dt.timezone.utc)


def validate_report(report, environment, deployment_ref):
    return module.validate_preflight_report(
        report,
        environment,
        f"design/operations/environments/{environment}/expected-bindings.yaml",
        deployment_ref,
        now_dt=validation_now,
    )

def report_results(environment, traffic_open_event=None):
    requirements = module.expected_preflight_policy_requirements(environment, traffic_open_event)
    return [
        {
            "policyId": policy_id,
            "category": module.PREFLIGHT_POLICY_CATALOG[policy_id],
            "required": requirements[policy_id],
            "status": (
                "pass"
                if requirements[policy_id] or (environment == "hobby-self-hosted" and policy_id == "PREFLIGHT-DIGEST-002")
                else "not_applicable"
            ),
            "message": "contract evidence",
        }
        for policy_id in module.EXPECTED_PREFLIGHT_POLICY_IDS
    ]

complete_report = {
    "environment": "hobby-self-hosted",
    "expectedBindingsRef": "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "deploymentRef": {"manifestRef": "contract-hobby"},
    "deploymentEventId": deployment_event_id,
    "trafficOpenEvent": None,
    "policyCatalogVersion": module.PREFLIGHT_POLICY_CATALOG_VERSION,
    "startedAt": "2026-01-01T00:00:00Z",
    "completedAt": "2026-01-01T00:00:01Z",
    "toolVersion": "preflight.py-v1",
    "context": "operator",
    "checkResults": report_results("hobby-self-hosted"),
}
complete_status, complete_message = validate_report(
    complete_report, "hobby-self-hosted", "contract-hobby"
)
if complete_status != "pass":
    raise SystemExit(f"complete preflight policy set did not pass: {complete_message}")
ordinary_supplemental_report = {
    **complete_report,
    "checkResults": complete_report["checkResults"]
    + [
        {
            "policyId": "PREFLIGHT-JWT-ROTATION-001",
            "category": module.PREFLIGHT_POLICY_CATALOG["PREFLIGHT-JWT-ROTATION-001"],
            "required": True,
            "status": "pass",
            "message": "ordinary report must not authorize rotation evidence",
        }
    ],
}
ordinary_supplemental_status, ordinary_supplemental_message = validate_report(
    ordinary_supplemental_report, "hobby-self-hosted", "contract-hobby"
)
if ordinary_supplemental_status != "fail" or "unknown policy IDs" not in ordinary_supplemental_message:
    raise SystemExit(
        "ordinary preflight validation accepted supplemental JWT rotation evidence: "
        + ordinary_supplemental_message
    )
for advisory_status in ("pass", "fail"):
    advisory_report = {
        **complete_report,
        "checkResults": [
            (
                {**check, "status": advisory_status}
                if check["policyId"] == "PREFLIGHT-JWT-001"
                else check
            )
            for check in complete_report["checkResults"]
        ],
    }
    advisory_result, advisory_message = validate_report(
        advisory_report, "hobby-self-hosted", "contract-hobby"
    )
    if advisory_result != "pass":
        raise SystemExit(
            f"advisory executable status {advisory_status} was rejected: {advisory_message}"
        )
fixture_policy_id = "PREFLIGHT-BACKUP-003"
if fixture_policy_id not in module.PREFLIGHT_POLICY_CATALOG:
    raise SystemExit(f"invalid preflight policy catalogue fixture ID is missing: {fixture_policy_id}")
for invalid_catalog, expected_fragment in (
    (
        {
            policy_id: category
            for policy_id, category in module.PREFLIGHT_POLICY_CATALOG.items()
            if policy_id != fixture_policy_id
        },
        f"missing policy IDs: {fixture_policy_id}",
    ),
    (
        {**module.PREFLIGHT_POLICY_CATALOG, "PREFLIGHT-UNKNOWN-001": "apply-blocking"},
        "unknown policy IDs: PREFLIGHT-UNKNOWN-001",
    ),
    (
        {**module.PREFLIGHT_POLICY_CATALOG, fixture_policy_id: "invalid"},
        f"invalid preflight policy catalogue categories for policy IDs: {fixture_policy_id}",
    ),
):
    catalog_message = module.validate_preflight_policy_catalog(invalid_catalog)
    if catalog_message is None or expected_fragment not in catalog_message:
        raise SystemExit(
            "invalid preflight policy catalogue failed for the wrong reason: "
            f"expected '{expected_fragment}', got {catalog_message!r}"
        )
for invalid_version in (None, "preflight-policy-v0"):
    invalid_version_report = {**complete_report}
    if invalid_version is None:
        invalid_version_report.pop("policyCatalogVersion")
    else:
        invalid_version_report["policyCatalogVersion"] = invalid_version
    version_status, version_message = validate_report(
        invalid_version_report, "hobby-self-hosted", "contract-hobby"
    )
    if version_status != "fail" or "policyCatalogVersion" not in version_message:
        raise SystemExit(f"invalid policy catalogue version was accepted: {version_message}")
missing_category_report = {
    **complete_report,
    "checkResults": [
        ({key: value for key, value in check.items() if key != "category"} if index == 0 else check)
        for index, check in enumerate(complete_report["checkResults"])
    ],
}
missing_category_status, missing_category_message = validate_report(
    missing_category_report, "hobby-self-hosted", "contract-hobby"
)
if missing_category_status != "fail" or "malformed checkResults" not in missing_category_message:
    raise SystemExit(f"missing policy result category was accepted: {missing_category_message}")
invalid_category_report = {
    **complete_report,
    "checkResults": [
        ({**check, "category": "invalid"} if index == 0 else check)
        for index, check in enumerate(complete_report["checkResults"])
    ],
}
invalid_category_status, invalid_category_message = validate_report(
    invalid_category_report, "hobby-self-hosted", "contract-hobby"
)
if invalid_category_status != "fail" or "malformed checkResults" not in invalid_category_message:
    raise SystemExit(f"invalid policy result category was accepted: {invalid_category_message}")
mismatched_category_report = {
    **complete_report,
    "checkResults": [
        (
            {
                **check,
                "category": (
                    "advisory"
                    if module.PREFLIGHT_POLICY_CATALOG[check["policyId"]] != "advisory"
                    else "apply-blocking"
                ),
            }
            if index == 0
            else check
        )
        for index, check in enumerate(complete_report["checkResults"])
    ],
}
mismatched_category_status, mismatched_category_message = validate_report(
    mismatched_category_report, "hobby-self-hosted", "contract-hobby"
)
if mismatched_category_status != "fail" or "mismatched policy categories" not in mismatched_category_message:
    raise SystemExit(f"mismatched policy result category was accepted: {mismatched_category_message}")
for invalid_event_id in (None, "not-a-uuid", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"):
    invalid_report = {**complete_report, "deploymentEventId": invalid_event_id}
    invalid_status, invalid_message = validate_report(
        invalid_report, "hobby-self-hosted", "contract-hobby"
    )
    if invalid_status != "fail" or "deploymentEventId" not in invalid_message:
        raise SystemExit(
            f"invalid preflight deployment event ID was accepted: {invalid_event_id!r}, {invalid_message}"
        )
forged_all_pass_report = {
    **complete_report,
    "checkResults": [
        {**check, "status": "pass"}
        for check in complete_report["checkResults"]
    ],
}
forged_status, forged_message = validate_report(
    forged_all_pass_report, "hobby-self-hosted", "contract-hobby"
)
if forged_status != "fail" or "non-applicable policy IDs" not in forged_message:
    raise SystemExit(f"synthetic all-pass report was accepted: {forged_message}")
wrong_requirement_report = {
    **complete_report,
    "checkResults": [
        ({**check, "required": not check["required"]} if check["policyId"] == "PREFLIGHT-DIGEST-001" else check)
        for check in complete_report["checkResults"]
    ],
}
wrong_requirement_status, wrong_requirement_message = validate_report(
    wrong_requirement_report, "hobby-self-hosted", "contract-hobby"
)
if wrong_requirement_status != "fail" or "incorrect required applicability" not in wrong_requirement_message:
    raise SystemExit(f"incorrect report applicability was accepted: {wrong_requirement_message}")

staging_report = {
    **complete_report,
    "environment": "staging",
    "expectedBindingsRef": "design/operations/environments/staging/expected-bindings.yaml",
    "deploymentRef": {"overlayCommitSha": "contract-staging"},
    "checkResults": report_results("staging"),
}
for executable_status in ("pass", "fail"):
    staging_digest_report = {
        **staging_report,
        "checkResults": [
            (
                {**check, "status": executable_status}
                if check["policyId"] == "PREFLIGHT-DIGEST-002"
                else check
            )
            for check in staging_report["checkResults"]
        ],
    }
    digest_status, digest_message = validate_report(
        staging_digest_report, "staging", "contract-staging"
    )
    if digest_status != "fail" or "non-applicable policy IDs" not in digest_message:
        raise SystemExit(
            f"staging hobby digest status {executable_status} was accepted: {digest_message}"
        )

stale_report = {
    **complete_report,
    "startedAt": "2025-12-31T23:20:00Z",
    "completedAt": "2025-12-31T23:30:00Z",
}
stale_status, stale_message = validate_report(
    stale_report, "hobby-self-hosted", "contract-hobby"
)
if stale_status != "fail" or "older than the 30-minute consumption window" not in stale_message:
    raise SystemExit(f"stale generally consumed preflight report was accepted: {stale_message}")

static_report = {**complete_report, "context": "ci-static"}
static_status, static_message = validate_report(
    static_report, "hobby-self-hosted", "contract-hobby"
)
if static_status != "fail" or "operator context" not in static_message:
    raise SystemExit(f"static preflight report was accepted as operator evidence: {static_message}")
incomplete_report = {**complete_report, "checkResults": complete_report["checkResults"][1:]}
incomplete_status, incomplete_message = validate_report(
    incomplete_report, "hobby-self-hosted", "contract-hobby"
)
if incomplete_status != "fail" or "missing expected policy IDs" not in incomplete_message:
    raise SystemExit(f"incomplete preflight policy set did not fail closed: {incomplete_message}")

not_applicable_report = {
    **complete_report,
    "checkResults": [
        {**check, "status": "not_applicable", "message": "synthetic evidence"}
        for check in complete_report["checkResults"]
    ],
}
not_applicable_status, not_applicable_message = validate_report(
    not_applicable_report, "hobby-self-hosted", "contract-hobby"
)
if not_applicable_status != "fail" or "non-passing required policy IDs" not in not_applicable_message:
    raise SystemExit(f"all-not-applicable preflight report did not fail closed: {not_applicable_message}")

PY

# The checked-in production overlay lacks the dedicated bridge client wiring,
# so static preflight must fail closed rather than authorize URL-only wiring.
set +e
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF="$CHECKED_OUT_SHA" \
  FIREMUD_PREFLIGHT_OUTPUT="$PRODUCTION_REPORT" \
  python3 "$SCRIPT" production >"$LEGACY_PRODUCTION_PREFLIGHT_OUTPUT"
production_preflight_status=$?
set -e
if [ "$production_preflight_status" -eq 0 ]; then
  echo "production preflight unexpectedly authorized URL-only bridge wiring" >&2
  exit 1
fi
python3 - "$PRODUCTION_REPORT" <<'PY'
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
bridge = next(check for check in report["checkResults"] if check["policyId"] == "PREFLIGHT-BRIDGE-001")
if bridge["status"] != "fail" or bridge["required"] is not True:
    raise SystemExit(f"production bridge gap was not an explicit required failure: {bridge}")
PY

cat >"$LEGACY_PRODUCTION_TRAFFIC_EVIDENCE" <<'JSON'
{
  "schemaVersion": "traffic-open-record/v1",
  "environment": "production",
  "eventType": "reopen",
  "deploymentRef": "contract-production",
  "assessedAt": "2026-07-22T00:00:00Z",
  "assessedBy": "preflight-contract",
  "preflightReportPath": "design/operations/deployments/production/preflight/contract-production.json",
  "backupLastSuccessAt": "2026-07-22T00:00:00Z",
  "backupVerifyLastSuccessAt": "2026-07-22T00:00:00Z",
  "restoreDrillLastSuccessAt": "2026-07-22T00:00:00Z",
  "coordinatedBackupScope": {
    "type": "tenant_region",
    "tenantId": "tenant-1",
    "regionId": "region-1"
  },
  "evidenceRefs": ["caller-supplied-legacy-evidence"]
}
JSON

if python3 "$WRITER" production contract-production reopen \
  --assessed-by preflight-contract \
  --preflight-report "$PRODUCTION_REPORT" \
  --evidence-ref contract-test \
  --output "$LEGACY_PRODUCTION_TRAFFIC_EVIDENCE" >"$PRODUCTION_TRAFFIC_WRITER_OUTPUT" 2>&1; then
  echo "legacy production traffic-open writer unexpectedly succeeded" >&2
  exit 1
fi
grep -Fq "hobby-self-hosted" "$PRODUCTION_TRAFFIC_WRITER_OUTPUT"

rm -f "$PRODUCTION_REPORT"
if FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF="$CHECKED_OUT_SHA" \
  FIREMUD_PREFLIGHT_OUTPUT="$PRODUCTION_REPORT" \
  FIREMUD_TRAFFIC_OPEN_EVENT=reopen \
  python3 "$SCRIPT" production >"$GATED_PRODUCTION_PREFLIGHT_OUTPUT" 2>&1; then
  echo "production traffic-open preflight unexpectedly passed without controller authority" >&2
  exit 1
fi

python3 - <<'PY' "$PRODUCTION_REPORT"
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
backup_002 = [
    check
    for check in report["checkResults"]
    if check["policyId"] == "PREFLIGHT-BACKUP-002"
]
if len(backup_002) != 1 or backup_002[0]["status"] != "fail":
    raise SystemExit(f"PREFLIGHT-BACKUP-002 did not fail closed: {backup_002}")
if "durable environment-wide recovery-controller authority is not implemented" not in backup_002[0]["message"]:
    raise SystemExit(f"PREFLIGHT-BACKUP-002 did not report controller unavailability: {backup_002}")
PY

cat >"$PRODUCTION_WAIVER" <<JSON
{
  "environment": "production",
  "deploymentRef": "contract-production",
  "deploymentEventId": "77777777-7777-4777-8777-777777777777",
  "expiration": "deployment-event",
  "recordedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "approver": "preflight-contract",
  "ticket": "contract-ticket",
  "waivedPolicyIds": ["PREFLIGHT-BACKUP-002"]
}
JSON

rm -f "$PRODUCTION_REPORT"
if FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF="$CHECKED_OUT_SHA" \
  FIREMUD_PREFLIGHT_OUTPUT="$PRODUCTION_REPORT" \
  FIREMUD_PREFLIGHT_WAIVER="$PRODUCTION_WAIVER" \
  FIREMUD_TRAFFIC_OPEN_EVENT=reopen \
  python3 "$SCRIPT" production >"$WAIVER_PRODUCTION_PREFLIGHT_OUTPUT" 2>&1; then
  echo "production traffic-open gate unexpectedly accepted a waiver" >&2
  exit 1
fi

if ! grep -q "waiver execution remains blocked" "$WAIVER_PRODUCTION_PREFLIGHT_OUTPUT"; then
  echo "production waiver failed for the wrong reason" >&2
  cat "$WAIVER_PRODUCTION_PREFLIGHT_OUTPUT" >&2
  exit 1
fi
if [[ -e "$PRODUCTION_REPORT" ]]; then
  echo "blocked waiver unexpectedly produced an authoritative report" >&2
  exit 1
fi

for env in staging production; do
  REPORT="$TMP_DIR/preflight-$env.json"
  # These checked-in overlays lack the dedicated bridge client wiring; static
  # CI must record that required gap rather than authorize URL-only wiring.
  set +e
  FIREMUD_PREFLIGHT_CONTEXT=ci-static \
    FIREMUD_DEPLOYMENT_REF="$CHECKED_OUT_SHA" \
    FIREMUD_PREFLIGHT_OUTPUT="$REPORT" \
    python3 "$SCRIPT" "$env" >"$TMP_DIR/firemud-preflight-contract-$env.out"
  preflight_status=$?
  set -e
  if [ "$preflight_status" -eq 0 ]; then
    echo "$env: URL-only bridge wiring unexpectedly passed static CI" >&2
    exit 1
  fi

  python3 - <<'PY' "$REPORT" "$env"
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
env = sys.argv[2]
expected_ref = f"design/operations/environments/{env}/expected-bindings.yaml"
if report.get("expectedBindingsRef") != expected_ref:
    raise SystemExit(f"{env}: expectedBindingsRef mismatch: {report.get('expectedBindingsRef')}")
failures = [
    check
    for check in report["checkResults"]
    if check["status"] == "fail" and check["policyId"] not in {"PREFLIGHT-JWT-001", "PREFLIGHT-JWKS-001", "PREFLIGHT-BRIDGE-001", "PREFLIGHT-SERVICES-001", "PREFLIGHT-REDIS-001"}
]
if failures:
    raise SystemExit(f"{env}: unexpected preflight failures: {failures}")
bridge = [check for check in report["checkResults"] if check["policyId"] == "PREFLIGHT-BRIDGE-001"]
if len(bridge) != 1 or bridge[0]["status"] != "fail" or bridge[0]["required"] is not True:
    raise SystemExit(f"{env}: missing explicit required bridge-client failure: {bridge}")
for policy_id in ("PREFLIGHT-SERVICES-001", "PREFLIGHT-REDIS-001"):
    effective_check = [check for check in report["checkResults"] if check["policyId"] == policy_id]
    if len(effective_check) != 1 or effective_check[0]["status"] != "pass" or effective_check[0]["required"] is not True:
        raise SystemExit(f"{env}: unrelated external Secret absence contaminated {policy_id}: {effective_check}")
jwks = [check for check in report["checkResults"] if check["policyId"] == "PREFLIGHT-JWKS-001"]
if len(jwks) != 1 or jwks[0]["status"] != "pass":
    raise SystemExit(f"{env}: legacy Secret jwt-jwks fixture did not pass the current diagnostic: {jwks}")
for policy_id in ("PREFLIGHT-JWT-001", "PREFLIGHT-JWKS-001"):
    diagnostic = [check for check in report["checkResults"] if check["policyId"] == policy_id]
    if len(diagnostic) != 1 or diagnostic[0]["required"]:
        raise SystemExit(f"{env}: {policy_id} diagnostic was incorrectly apply-blocking: {diagnostic}")
PY
done

python3 - <<'PY' "$ROOT_DIR" "$TMP_DIR"
import copy
import hashlib
import json
import importlib.util
import pathlib
import sys
import yaml

root = pathlib.Path(sys.argv[1])
tmp = pathlib.Path(sys.argv[2])
env_root = tmp / "envs"
for env in ("staging", "production", "hobby-self-hosted"):
    (env_root / env).mkdir(parents=True, exist_ok=True)

base = {
    "internalBindings": {},
    "backupStorage": {
        "enabled": True,
        "bucket": "unique-backups",
        "bindingRef": "secret://firemud/unique-backup",
    },
    "assetStorage": {
        "enabled": True,
        "bucket": "unique-assets",
        "endpoint": "https://assets.unique.internal",
        "bindingRef": "secret://firemud/unique-assets",
    },
    "outboundComms": {
        "enabled": True,
        "smtpHost": "smtp.unique.internal",
        "webhookTargets": {"accountNotifications": "unique-only"},
    },
    "operatorCredentials": {"bindingRef": "cert-manager://firemud/unique-operator"},
    "serviceDiscovery": {"mode": "kubernetes-dns-default"},
}

staging = {"environment": "staging", **base}
production = {"environment": "production", **base}
hobby = {"environment": "hobby-self-hosted", **base}

staging["backupStorage"] = {
    "enabled": True,
    "bucket": "dup-backups",
    "bindingRef": "secret://firemud/staging-backup",
}
production["backupStorage"] = {
    "enabled": True,
    "bucket": "dup-backups",
    "bindingRef": "secret://firemud/production-backup",
}
hobby["backupStorage"] = {
    "enabled": True,
    "bucket": "hobby-backups",
    "bindingRef": "secret://firemud/hobby-backup",
}

for env, data in (("staging", staging), ("production", production), ("hobby-self-hosted", hobby)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")

spec = importlib.util.spec_from_file_location("preflight", root / "dev-tools/deploy/preflight.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

overflow_timestamp = "9999-12-31T23:59:59-14:00"
try:
    module.parse_timestamp(overflow_timestamp, "overflow timestamp")
except module.TIMESTAMP_ERRORS as exc:
    if not isinstance(exc, OverflowError):
        raise SystemExit(f"unexpected overflow fixture exception: {exc!r}")
else:
    raise SystemExit("offset-aware overflow timestamp unexpectedly normalized")
try:
    module.parse_timestamp("2026-01-01T00:00:00", "naive timestamp")
except module.TIMESTAMP_ERRORS as exc:
    if "timezone" not in str(exc):
        raise SystemExit(f"naive timestamp failed for the wrong reason: {exc}")
else:
    raise SystemExit("naive timestamp unexpectedly accepted")

original_subprocess_run = module.subprocess.run
try:
    def not_found_lookup(*args, **kwargs):
        if kwargs.get("timeout") != module.SECRET_LOOKUP_TIMEOUT_SECONDS:
            raise SystemExit("Secret lookup did not receive its deployment timeout")
        return module.subprocess.CompletedProcess(
            args, 1, "", 'Error from server (NotFound): secrets "missing" not found'
        )

    module.subprocess.run = not_found_lookup
    not_found_message = module.secret_lookup_failure("missing")
    if not_found_message != "Missing required Secret in cluster: firemud/missing":
        raise SystemExit(f"NotFound Secret lookup reported incorrectly: {not_found_message}")
    namespaced_message = module.secret_lookup_failure("missing", "other")
    if namespaced_message != "Missing required Secret in cluster: other/missing":
        raise SystemExit(f"namespaced Secret lookup reported incorrectly: {namespaced_message}")

    forbidden_stderr = 'Error from server (Forbidden): secrets is forbidden'
    module.subprocess.run = lambda *args, **kwargs: module.subprocess.CompletedProcess(
        args, 1, "", forbidden_stderr
    )
    forbidden_message = module.secret_lookup_failure("forbidden")
    expected_forbidden = (
        "Secret lookup could not be verified for firemud/forbidden: "
        + forbidden_stderr
    )
    if forbidden_message != expected_forbidden:
        raise SystemExit(f"non-NotFound Secret lookup reported incorrectly: {forbidden_message}")

    def raise_lookup_timeout(*args, **kwargs):
        raise module.subprocess.TimeoutExpired(
            args, module.SECRET_LOOKUP_TIMEOUT_SECONDS
        )

    module.subprocess.run = raise_lookup_timeout
    timeout_message = module.secret_lookup_failure("timed-out")
    if (
        timeout_message is None
        or not timeout_message.startswith(
            "Secret lookup could not be verified for firemud/timed-out:"
        )
        or "timed out" not in timeout_message
    ):
        raise SystemExit(f"Timeout Secret lookup reported incorrectly: {timeout_message}")

    def raise_lookup_unicode_error(*args, **kwargs):
        raise UnicodeDecodeError("utf-8", b"\xff", 0, 1, "invalid start byte")

    module.subprocess.run = raise_lookup_unicode_error
    unicode_error_message = module.secret_lookup_failure("undecodable")
    if (
        unicode_error_message is None
        or not unicode_error_message.startswith(
            "Secret lookup could not be verified for firemud/undecodable:"
        )
        or "codec can't decode" not in unicode_error_message
    ):
        raise SystemExit(
            f"Unicode decoding Secret lookup reported incorrectly: {unicode_error_message}"
        )

    def raise_lookup_os_error(*args, **kwargs):
        raise OSError("kubectl unavailable")

    module.subprocess.run = raise_lookup_os_error
    os_error_message = module.secret_lookup_failure("unavailable")
    expected_os_error = (
        "Secret lookup could not be verified for firemud/unavailable: "
        "kubectl unavailable"
    )
    if os_error_message != expected_os_error:
        raise SystemExit(f"OSError Secret lookup reported incorrectly: {os_error_message}")

finally:
    module.subprocess.run = original_subprocess_run

issues = module.external_binding_uniqueness_issues(env_root, "staging", staging)
if not any("backupStorage.bucket matches production" in issue for issue in issues):
    raise SystemExit(f"expected duplicate backupStorage.bucket issue, got: {issues}")

staging["observability"] = {"otelCollectorEndpoint": "https://otel.shared.internal:4317"}
production["observability"] = {"otelCollectorEndpoint": "https://otel.shared.internal:4317"}
for env, data in (("staging", staging), ("production", production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
issues = module.external_binding_uniqueness_issues(env_root, "staging", staging)
if not any("observability.otelCollectorEndpoint matches production" in issue for issue in issues):
    raise SystemExit(f"undeclared shared OTEL endpoint was accepted: {issues}")
shared_otel = {
    "value": "https://otel.shared.internal:4317",
    "shared": True,
    "sharedRationale": "shared collector endpoint",
}
staging["observability"]["otelCollectorEndpoint"] = shared_otel
production["observability"]["otelCollectorEndpoint"] = shared_otel
for env, data in (("staging", staging), ("production", production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
issues = module.external_binding_uniqueness_issues(env_root, "staging", staging)
if any("observability.otelCollectorEndpoint" in issue for issue in issues):
    raise SystemExit(f"declared shared OTEL endpoint was rejected: {issues}")

disabled_staging = copy.deepcopy(staging)
disabled_production = copy.deepcopy(production)
disabled_staging["backupStorage"] = {"enabled": False}
disabled_staging["assetStorage"] = {"enabled": False}
for env, data in (("staging", disabled_staging), ("production", disabled_production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")

issues = module.external_binding_uniqueness_issues(
    env_root, "staging", disabled_staging
)
if any(issue.startswith("backupStorage.") for issue in issues):
    raise SystemExit(f"disabled backup storage must be excluded from uniqueness checks: {issues}")
if any(issue.startswith("assetStorage.") for issue in issues):
    raise SystemExit(f"disabled asset storage must be excluded from uniqueness checks: {issues}")

active_staging = copy.deepcopy(staging)
active_production = copy.deepcopy(production)
active_staging["assetStorage"]["bucket"] = "dup-assets"
active_production["assetStorage"]["bucket"] = "dup-assets"
for env, data in (("staging", active_staging), ("production", active_production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
issues = module.external_binding_uniqueness_issues(env_root, "staging", active_staging)
if not any("assetStorage.bucket matches production" in issue for issue in issues):
    raise SystemExit(f"enabled asset storage must participate in uniqueness checks: {issues}")

shared_value = {"value": "smtp.shared.internal", "shared": True, "sharedRationale": "shared relay"}
staging["outboundComms"]["smtpHost"] = shared_value
production["outboundComms"]["smtpHost"] = shared_value
for env, data in (("staging", staging), ("production", production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")

issues = module.external_binding_uniqueness_issues(env_root, "staging", staging)
if any("outboundComms.smtpHost" in issue for issue in issues):
    raise SystemExit(f"shared smtpHost should be allowed, got: {issues}")

exclusive_shared = copy.deepcopy(staging)
exclusive_shared["operatorCredentials"]["bindingRef"] = {
    "value": "cert-manager://firemud/shared-operator",
    "shared": True,
    "sharedRationale": "incorrectly shared operator identity",
}
if not any(
    "operatorCredentials.bindingRef is environment-exclusive" in issue
    for issue in module.external_binding_uniqueness_issues(env_root, "staging", exclusive_shared)
):
    raise SystemExit("environment-exclusive operator identity sharing was accepted")

missing_shared_rationale = copy.deepcopy(staging)
missing_shared_rationale["assetStorage"]["bucket"] = {
    "value": "assets.shared.internal",
    "shared": True,
}
if not any(
    "assetStorage.bucket is marked shared but missing sharedRationale" in issue
    for issue in module.external_binding_uniqueness_issues(
        env_root, "staging", missing_shared_rationale
    )
):
    raise SystemExit("conditional shared asset target without rationale was accepted")

credential_shaped_target = copy.deepcopy(staging)
credential_shaped_target["assetStorage"]["bucket"] = {
    "bindingRef": "secret://firemud/shared-asset-credentials",
    "shared": True,
    "sharedRationale": "invalid credential-shaped target",
}
if not any(
    "assetStorage.bucket is a non-sensitive target and cannot use credential fields: bindingRef"
    in issue
    for issue in module.external_binding_uniqueness_issues(
        env_root, "staging", credential_shaped_target
    )
):
    raise SystemExit("credential-shaped shared asset target was accepted")
disabled_asset_expected = copy.deepcopy(hobby)
disabled_asset_expected.pop("assetStorage")
disabled_asset_expected["outboundComms"]["webhookTargets"]["accountNotifications"] = "hobby-disabled-only"
disabled_asset_expected["operatorCredentials"]["bindingRef"] = "cert-manager://firemud/hobby-disabled-operator"
disabled_asset_path = env_root / "hobby-self-hosted" / "asset-storage-disabled.yaml"
disabled_asset_path.write_text(yaml.safe_dump(disabled_asset_expected, sort_keys=False), encoding="utf-8")
disabled_asset_results = module.expected_binding_checks(
    disabled_asset_path,
    "design/operations/environments/hobby-self-hosted/asset-storage-disabled.yaml",
    "hobby-self-hosted",
    [],
)
disabled_asset_external = next(
    result for result in disabled_asset_results if result.policy_id == "PREFLIGHT-EXTERNAL-001"
)
if disabled_asset_external.status != "pass":
    raise SystemExit(
        "disabled external asset storage should not require assetStorage keys: "
        + disabled_asset_external.message
    )


def verify_service_override_contract(case_name, rendered_overrides, allowed_overrides, expected_status, expected_fragment):
    base_expected = yaml.safe_load((root / "design/operations/environments/staging/expected-bindings.yaml").read_text(encoding="utf-8"))
    base_expected["environment"] = "staging"
    base_expected["serviceDiscovery"] = {
        "mode": "explicit-overrides",
        "allowedOverrides": allowed_overrides,
    }
    expected_path = tmp / f"{case_name}-expected-bindings.yaml"
    expected_path.write_text(yaml.safe_dump(base_expected, sort_keys=False), encoding="utf-8")

    rendered_payload = pathlib.Path(f"{tmp}/hobby-rendered.yaml").read_text(encoding="utf-8")
    documents = module.parse_documents(rendered_payload)
    config_maps = [
        document
        for document in documents
        if document.get("kind") == "ConfigMap"
        and document.get("metadata", {}).get("name") == "firemud-config"
        and document.get("metadata", {}).get("namespace") == "firemud"
    ]
    if len(config_maps) != 1:
        raise SystemExit(f"{case_name}: expected exactly one referenced firemud-config ConfigMap")
    config_maps[0]["data"] = rendered_overrides
    results = module.expected_binding_checks(
        expected_path, f"design/operations/environments/{case_name}-explicit-overrides.yaml", "staging", documents
    )
    service_check = next(
        (result for result in results if result.policy_id == "PREFLIGHT-SERVICES-001"),
        None,
    )
    if service_check is None:
        raise SystemExit(f"{case_name}: missing PREFLIGHT-SERVICES-001 result")
    if service_check.status != expected_status:
        raise SystemExit(
            f"{case_name}: expected PREFLIGHT-SERVICES-001 {expected_status}, got {service_check.status}: {service_check.message}"
        )
    if expected_fragment and expected_fragment not in service_check.message:
        raise SystemExit(
            f"{case_name}: expected services message fragment '{expected_fragment}', got '{service_check.message}'"
        )


verify_service_override_contract(
    "pass",
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    "pass",
    "match expected explicit contract",
)
verify_service_override_contract(
    "undeclared",
    {"FIREMUD_SERVICES_UNKNOWN_SERVICE": "mystery.firemud.svc.cluster.local"},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    "fail",
    "not declared",
)
verify_service_override_contract(
    "mismatch",
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "mismatch.firemud.svc.cluster.local"},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    "fail",
    "does not match allowed value",
)
verify_service_override_contract(
    "external-host",
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.evil.example:6565"},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local:6565"},
    "fail",
    "Kubernetes environment",
)
verify_service_override_contract(
    "missing",
    {},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    "fail",
    "missing from effective workloads",
)
verify_service_override_contract(
    "partial-missing",
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    {
        "FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local",
        "FIREMUD_SERVICES_GAME_SESSION_SERVICE": "game-session-service.firemud.svc.cluster.local",
    },
    "fail",
    "FIREMUD_SERVICES_GAME_SESSION_SERVICE",
)
verify_service_override_contract(
    "malformed-rendered-key",
    {"FIREMUD_SERVICES_account_SERVICE": "account-service.firemud.svc.cluster.local"},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    "fail",
    "not declared",
)
verify_service_override_contract(
    "invalid-unused-entry",
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    {
        "FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local",
        "FIREMUD_SERVICES_UNUSED": 7,
    },
    "fail",
    "must be a string",
)

rendered_documents = module.parse_documents(pathlib.Path(tmp / "hobby-rendered.yaml").read_text(encoding="utf-8"))

for env in ("production", "staging", "hobby-self-hosted"):
    expected = yaml.safe_load(
        (root / f"design/operations/environments/{env}/expected-bindings.yaml").read_text(encoding="utf-8")
    )
    custody_mode = expected.get("internalBindings", {}).get("jwt", {}).get("custodyMode")
    if custody_mode != module.IMPLEMENTED_JWT_CUSTODY_MODE:
        raise SystemExit(f"{env}: checked-in manifest selected unexpected JWT custody mode: {custody_mode}")

current_expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
unreferenced_decoy_documents = copy.deepcopy(rendered_documents)
unreferenced_decoy_documents.append(
    {
        "apiVersion": "v1",
        "kind": "ConfigMap",
        "metadata": {"name": "unreferenced-decoy", "namespace": "firemud"},
        "data": {
            "FIREMUD_SERVICES_DECOY": "evil.firemud.svc.cluster.local",
        },
    }
)
unreferenced_decoy_results = module.expected_binding_checks(
    current_expected_path,
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "hobby-self-hosted",
    unreferenced_decoy_documents,
)
unreferenced_decoy_service = next(
    result
    for result in unreferenced_decoy_results
    if result.policy_id == "PREFLIGHT-SERVICES-001"
)
if unreferenced_decoy_service.status != "pass":
    raise SystemExit(
        "unreferenced service-discovery ConfigMap decoy affected preflight: "
        + unreferenced_decoy_service.message
    )

current_results = module.expected_binding_checks(
    current_expected_path,
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "hobby-self-hosted",
    rendered_documents,
)
current_secrets = next(result for result in current_results if result.policy_id == "PREFLIGHT-SECRETS-002")
if current_secrets.status != "pass":
    raise SystemExit(f"checked-in legacy JWT custody selector did not pass current wiring validation: {current_secrets.message}")

custom_secret_expected = copy.deepcopy(
    yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
custom_secret_expected["internalBindings"]["postgres"]["credentialsRef"] = (
    "secret://staging/custom-postgres-credentials"
)
custom_secret_expected["internalBindings"]["jwt"]["signingKeysRef"] = (
    "secret://staging/custom-jwt-signing-keys"
)
custom_secret_bindings = module.expected_player_secret_bindings(custom_secret_expected)
if custom_secret_bindings[:2] != (
    ("custom-postgres-credentials", "staging", "internalBindings.postgres.credentialsRef"),
    ("custom-jwt-signing-keys", "staging", "internalBindings.jwt.signingKeysRef"),
):
    raise SystemExit(f"operator Secret lookup did not derive exact expected binding identities: {custom_secret_bindings}")


def effective_env_documents(*, config_map=None, config_map_namespace="firemud", secret=None, secret_namespace="firemud", workload_namespace="firemud", env=None, env_from=None):
    workload = {
        "kind": "Deployment",
        "metadata": {"name": "account-service", "namespace": workload_namespace},
        "spec": {
            "template": {
                "spec": {
                    "containers": [
                        {
                            "name": "account-service",
                            "env": env or [],
                            "envFrom": env_from or [],
                        }
                    ]
                }
            }
        },
    }
    documents = [workload]
    if config_map is not None:
        documents.insert(
            0,
            {
                "kind": "ConfigMap",
                "metadata": {"name": "cfg", "namespace": config_map_namespace},
                "data": config_map,
            },
        )
    if secret is not None:
        documents.insert(
            0,
            {
                "kind": "Secret",
                "metadata": {"name": "cfg", "namespace": secret_namespace},
                "stringData": secret,
            },
        )
    return documents, workload, workload["spec"]["template"]["spec"]["containers"][0]


precedence_documents, precedence_workload, precedence_container = effective_env_documents(
    config_map={"FIREMUD_SERVICES_ACCOUNT_SERVICE": "from-config-map"},
    env=[{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "value": "direct-env"}],
    env_from=[{"configMapRef": {"name": "cfg"}}],
)
precedence_values, precedence_issues = module.effective_container_env(
    precedence_documents, precedence_workload, precedence_container
)
if precedence_issues or precedence_values.get("FIREMUD_SERVICES_ACCOUNT_SERVICE") != "direct-env":
    raise SystemExit(f"direct env did not override envFrom: {precedence_values}, {precedence_issues}")

for case_name, config_map, config_map_namespace, env, env_from, expects_issue in (
    ("required-configmap", None, "firemud", [], [{"configMapRef": {"name": "cfg"}}], True),
    ("optional-configmap", None, "firemud", [], [{"configMapRef": {"name": "cfg", "optional": True}}], False),
    ("malformed-configmap-optional", None, "firemud", [], [{"configMapRef": {"name": "cfg", "optional": "true"}}], True),
    (
        "required-configmap-key",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"configMapKeyRef": {"name": "cfg", "key": "missing"}}}],
        [],
        True,
    ),
    (
        "optional-configmap-key",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"configMapKeyRef": {"name": "cfg", "key": "missing", "optional": True}}}],
        [],
        False,
    ),
    (
        "malformed-configmap-key-optional",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"configMapKeyRef": {"name": "cfg", "key": "missing", "optional": "false"}}}],
        [],
        True,
    ),
    ("namespace-decoy-configmap", {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "decoy"}, "other", [], [{"configMapRef": {"name": "cfg"}}], True),
):
    documents, workload, container = effective_env_documents(
        config_map=config_map,
        config_map_namespace=config_map_namespace,
        env=env,
        env_from=env_from,
    )
    _, issues = module.effective_container_env(documents, workload, container)
    if bool(issues) != expects_issue:
        raise SystemExit(f"{case_name}: unexpected ConfigMap optional/namespace result: {issues}")

for case_name, secret, secret_namespace, env, env_from, expects_issue in (
    ("required-secret", None, "firemud", [], [{"secretRef": {"name": "cfg"}}], True),
    ("optional-secret", None, "firemud", [], [{"secretRef": {"name": "cfg", "optional": True}}], False),
    ("malformed-secret-optional", None, "firemud", [], [{"secretRef": {"name": "cfg", "optional": 1}}], True),
    (
        "required-secret-key",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"secretKeyRef": {"name": "cfg", "key": "missing"}}}],
        [],
        True,
    ),
    (
        "optional-secret-key",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"secretKeyRef": {"name": "cfg", "key": "missing", "optional": True}}}],
        [],
        False,
    ),
    (
        "malformed-secret-key-optional",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"secretKeyRef": {"name": "cfg", "key": "missing", "optional": 0}}}],
        [],
        True,
    ),
    ("namespace-decoy-secret", {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "decoy"}, "other", [], [{"secretRef": {"name": "cfg"}}], True),
):
    documents, workload, container = effective_env_documents(
        secret=secret,
        secret_namespace=secret_namespace,
        env=env,
        env_from=env_from,
    )
    _, issues = module.effective_container_env(documents, workload, container)
    if bool(issues) != expects_issue:
        raise SystemExit(f"{case_name}: unexpected Secret optional/namespace result: {issues}")

secret_documents, secret_workload, secret_container = effective_env_documents(
    env_from=[{"secretRef": {"name": "bridge-config"}}],
)
secret_documents.append(
    {
        "kind": "Secret",
        "metadata": {"name": "bridge-config", "namespace": "firemud"},
        "stringData": {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "secret-backed"},
    }
)
secret_values, secret_issues = module.effective_container_env(
    secret_documents, secret_workload, secret_container
)
if (
    not secret_issues
    or secret_values.get("FIREMUD_SERVICES_ACCOUNT_SERVICE") is not None
    or any("secret-backed" in issue for issue in secret_issues)
):
    raise SystemExit(f"Secret-backed service override was not rejected safely: {secret_values}, {secret_issues}")
secret_overrides, secret_override_issues = module.extract_service_discovery_overrides(secret_documents)
if not secret_override_issues or secret_overrides:
    raise SystemExit(f"Secret-backed service override was not rejected: {secret_overrides}, {secret_override_issues}")

secret_key_documents, secret_key_workload, secret_key_container = effective_env_documents(
    env=[
        {
            "name": "FIREMUD_SERVICES_ACCOUNT_SERVICE",
            "valueFrom": {"secretKeyRef": {"name": "bridge-config", "key": "FIREMUD_SERVICES_ACCOUNT_SERVICE"}},
        }
    ],
)
secret_key_documents.append(
    {
        "kind": "Secret",
        "metadata": {"name": "bridge-config", "namespace": "firemud"},
        "stringData": {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "secret-key-backed"},
    }
)
secret_key_values, secret_key_issues = module.effective_container_env(
    secret_key_documents, secret_key_workload, secret_key_container
)
if (
    not secret_key_issues
    or secret_key_values
    or any("secret-key-backed" in issue for issue in secret_key_issues)
):
    raise SystemExit(f"Secret-backed service valueFrom was not rejected: {secret_key_values}, {secret_key_issues}")

uninspectable_secret_documents, uninspectable_secret_workload, uninspectable_secret_container = effective_env_documents(
    env_from=[{"secretRef": {"name": "external-bridge-config"}}],
)
_, uninspectable_secret_issues = module.effective_container_env(
    uninspectable_secret_documents,
    uninspectable_secret_workload,
    uninspectable_secret_container,
    relevant_prefixes=("FIREMUD_SERVICES_",),
)
if uninspectable_secret_issues:
    raise SystemExit(f"unrelated missing external Secret contaminated service checks: {uninspectable_secret_issues}")

external_secret_documents, external_secret_workload, external_secret_container = effective_env_documents(
    config_map={"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    env_from=[
        {"configMapRef": {"name": "cfg"}},
        {"secretRef": {"name": "postgres-credentials"}},
    ],
)
external_secret_documents.append(
    {
        "kind": "Secret",
        "metadata": {"name": "postgres-credentials", "namespace": "firemud"},
        "data": "externally-managed-and-not-rendered",
    }
)
external_values, external_issues = module.effective_container_env(
    external_secret_documents,
    external_secret_workload,
    external_secret_container,
    relevant_prefixes=("FIREMUD_SERVICES_",),
)
if external_issues or external_values.get("FIREMUD_SERVICES_ACCOUNT_SERVICE") != "account-service.firemud.svc.cluster.local":
    raise SystemExit(f"unrelated external Secret contaminated effective service config: {external_values}, {external_issues}")

malformed_configmap_documents, malformed_configmap_workload, malformed_configmap_container = effective_env_documents(
    config_map="not-a-mapping",
    env_from=[{"configMapRef": {"name": "cfg"}}],
)
_, malformed_configmap_issues = module.effective_container_env(
    malformed_configmap_documents,
    malformed_configmap_workload,
    malformed_configmap_container,
    relevant_prefixes=("FIREMUD_SERVICES_",),
)
if not any("data must be a mapping" in issue for issue in malformed_configmap_issues):
    raise SystemExit(f"malformed ConfigMap data did not fail closed: {malformed_configmap_issues}")

malformed_secret_documents, malformed_secret_workload, malformed_secret_container = effective_env_documents(
    env_from=[{"secretRef": {"name": "malformed-secret"}}],
)
malformed_secret_documents.append(
    {
        "kind": "Secret",
        "metadata": {"name": "malformed-secret", "namespace": "firemud"},
        "data": {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "%%%not-base64%%%"},
    }
)
_, malformed_secret_issues = module.effective_container_env(
    malformed_secret_documents,
    malformed_secret_workload,
    malformed_secret_container,
)
if not malformed_secret_issues or any("%%%not-base64%%%" in issue for issue in malformed_secret_issues):
    raise SystemExit(f"invalid Secret data was not rejected without leakage: {malformed_secret_issues}")

bridge_values, bridge_issues = module.validate_gateway_ws_values(
    rendered_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if bridge_issues or not bridge_values:
    raise SystemExit(f"canonical bridge fixture did not pass: {bridge_issues}")
invalid_listener_expected = yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
invalid_listener_expected["internalBindings"]["certificates"]["gatewayInternalWsListenerRef"] = "secret://firemud/not-a-cert-manager-binding"
_, invalid_listener_issues = module.validate_gateway_ws_values(rendered_documents, invalid_listener_expected)
if not any("gatewayInternalWsListenerRef must be a cert-manager binding" in issue for issue in invalid_listener_issues):
    raise SystemExit(f"malformed Gateway listener binding was not rejected explicitly: {invalid_listener_issues}")
bridge_mutation = copy.deepcopy(rendered_documents)
for document in bridge_mutation:
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service":
        for container in document["spec"]["template"]["spec"]["containers"]:
            for entry in container.get("env", []):
                if entry.get("name") == "GATEWAY_WS_URL":
                    entry["value"] = "wss://evil-spring-cloud-gateway-mtls.firemud.svc.cluster.local/ws/game"
_, bridge_mutation_issues = module.validate_gateway_ws_values(
    bridge_mutation, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("does not match canonical" in issue for issue in bridge_mutation_issues):
    raise SystemExit("bridge host decoy was accepted")


def set_bridge_url(documents, value):
    for document in documents:
        if document.get("kind") != "Deployment" or document.get("metadata", {}).get("name") != "tcp-proxy-service":
            continue
        for container in document.get("spec", {}).get("template", {}).get("spec", {}).get("containers", []):
            for entry in container.get("env", []):
                if entry.get("name") == "GATEWAY_WS_URL":
                    entry["value"] = value


canonical_bridge_url = "wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local/ws/game"
for case_name, value, expected_fragment in (
    ("scheme", "ws://spring-cloud-gateway-mtls.firemud.svc.cluster.local/ws/game", "does not match canonical"),
    ("port", "wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local:444/ws/game", "does not match canonical"),
    ("port-zero", "wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local:0/ws/game", "has an invalid port"),
    ("path", "wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local/wrong", "does not match canonical"),
):
    documents = copy.deepcopy(rendered_documents)
    set_bridge_url(documents, value)
    _, issues = module.validate_gateway_ws_values(
        documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
    )
    if not any(expected_fragment in issue for issue in issues):
        raise SystemExit(f"bridge {case_name} decoy was accepted: {issues}")

duplicate_service_documents = copy.deepcopy(rendered_documents)
duplicate_service_documents.append(
    copy.deepcopy(
        next(
            document
            for document in duplicate_service_documents
            if document.get("kind") == "Service"
            and document.get("metadata", {}).get("name") == "spring-cloud-gateway-mtls"
        )
    )
)
_, duplicate_service_issues = module.validate_gateway_ws_values(
    duplicate_service_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("exactly one rendered internal Gateway mTLS Service" in issue for issue in duplicate_service_issues):
    raise SystemExit(f"duplicate internal Gateway Service was accepted: {duplicate_service_issues}")

conflicting_bridge_documents = copy.deepcopy(rendered_documents)
account_deployment = next(
    document
    for document in conflicting_bridge_documents
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "account-service"
)
account_container = next(
    container
    for container in account_deployment["spec"]["template"]["spec"]["containers"]
    if container.get("name") == "account-service"
)
account_container.setdefault("env", []).extend(
    [
        {"name": "GATEWAY_WS_URL", "value": "wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local:443/ws/game"},
        {"name": "FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH", "value": "/tls/client.crt"},
        {"name": "FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH", "value": "/tls/client.key"},
        {"name": "FIREMUD_GATEWAY_WS_CA_CERT_PATH", "value": "/tls/ca.crt"},
    ]
)
account_container.setdefault("volumeMounts", []).append(
    {"name": "hobby-tcp-proxy-bridge", "mountPath": "/tls", "readOnly": True}
)
account_deployment["spec"]["template"]["spec"].setdefault("volumes", []).append(
    {"name": "hobby-tcp-proxy-bridge", "secret": {"secretName": "hobby-tcp-proxy-bridge"}}
)
_, conflicting_bridge_issues = module.validate_gateway_ws_values(
    conflicting_bridge_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("effective GATEWAY_WS_URL values conflict across workloads" in issue for issue in conflicting_bridge_issues):
    raise SystemExit(f"conflicting applicable bridge value was accepted: {conflicting_bridge_issues}")

def set_bridge_env(documents, name, value):
    for document in documents:
        if document.get("kind") != "Deployment" or document.get("metadata", {}).get("name") != "tcp-proxy-service":
            continue
        for container in document.get("spec", {}).get("template", {}).get("spec", {}).get("containers", []):
            if container.get("name") != "tcp-proxy-service":
                continue
            for entry in container.get("env", []):
                if entry.get("name") == name:
                    entry["value"] = value


for path_name, expected_path in (
    ("FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH", "/tls/client.crt"),
    ("FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH", "/tls/client.key"),
    ("FIREMUD_GATEWAY_WS_CA_CERT_PATH", "/tls/ca.crt"),
):
    bridge_path_documents = copy.deepcopy(rendered_documents)
    set_bridge_url(bridge_path_documents, canonical_bridge_url)
    set_bridge_env(bridge_path_documents, path_name, "/wrong/bridge-file")
    _, bridge_path_issues = module.validate_gateway_ws_values(
        bridge_path_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
    )
    if not any(f"must be exactly {expected_path!r}" in issue for issue in bridge_path_issues):
        raise SystemExit(f"noncanonical bridge path {path_name} was accepted: {bridge_path_issues}")

bridge_mount_documents = copy.deepcopy(rendered_documents)
bridge_mount_container = next(
    container
    for document in bridge_mount_documents
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for container in document["spec"]["template"]["spec"]["containers"]
    if container.get("name") == "tcp-proxy-service"
)
bridge_mount_container["volumeMounts"] = [
    mount for mount in bridge_mount_container["volumeMounts"] if mount.get("name") != "hobby-tcp-proxy-bridge"
]
_, bridge_mount_issues = module.validate_gateway_ws_values(
    bridge_mount_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("dedicated read-only Secret-backed /tls mount" in issue for issue in bridge_mount_issues):
    raise SystemExit(f"missing bridge client mount was accepted: {bridge_mount_issues}")

bridge_readonly_documents = copy.deepcopy(rendered_documents)
bridge_readonly_container = next(
    container
    for document in bridge_readonly_documents
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for container in document["spec"]["template"]["spec"]["containers"]
    if container.get("name") == "tcp-proxy-service"
)
next(
    mount for mount in bridge_readonly_container["volumeMounts"] if mount.get("name") == "hobby-tcp-proxy-bridge"
)["readOnly"] = False
_, bridge_readonly_issues = module.validate_gateway_ws_values(
    bridge_readonly_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("dedicated read-only Secret-backed /tls mount" in issue for issue in bridge_readonly_issues):
    raise SystemExit(f"writable bridge client mount was accepted: {bridge_readonly_issues}")

bridge_secret_documents = copy.deepcopy(rendered_documents)
bridge_secret_deployment = next(
    document
    for document in bridge_secret_documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
next(
    volume
    for volume in bridge_secret_deployment["spec"]["template"]["spec"]["volumes"]
    if volume.get("name") == "hobby-tcp-proxy-bridge"
)["secret"]["secretName"] = "wrong-bridge-secret"
_, bridge_secret_issues = module.validate_gateway_ws_values(
    bridge_secret_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("must reference Secret firemud/hobby-tcp-proxy-bridge" in issue for issue in bridge_secret_issues):
    raise SystemExit(f"wrong bridge Secret identity was accepted: {bridge_secret_issues}")

bridge_subpath_documents = copy.deepcopy(rendered_documents)
bridge_subpath_container = next(
    container
    for document in bridge_subpath_documents
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for container in document["spec"]["template"]["spec"]["containers"]
    if container.get("name") == "tcp-proxy-service"
)
next(
    mount for mount in bridge_subpath_container["volumeMounts"] if mount.get("name") == "hobby-tcp-proxy-bridge"
)["subPath"] = "client.crt"
_, bridge_subpath_issues = module.validate_gateway_ws_values(
    bridge_subpath_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("must not use subPath" in issue for issue in bridge_subpath_issues):
    raise SystemExit(f"bridge Secret subPath was accepted: {bridge_subpath_issues}")

bridge_items_documents = copy.deepcopy(rendered_documents)
bridge_items_volume = next(
    volume
    for document in bridge_items_documents
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for volume in document["spec"]["template"]["spec"]["volumes"]
    if volume.get("name") == "hobby-tcp-proxy-bridge"
)
bridge_items_volume["secret"]["items"] = [{"key": "client.crt"}]
_, bridge_items_issues = module.validate_gateway_ws_values(
    bridge_items_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("items must select exactly" in issue for issue in bridge_items_issues):
    raise SystemExit(f"restrictive bridge Secret items were accepted: {bridge_items_issues}")

for case_name, mutate in (
    (
        "bridge-items-extra",
        lambda volume: volume["secret"].__setitem__(
            "items",
            [
                {"key": "client.crt", "path": "client.crt"},
                {"key": "client.key", "path": "client.key"},
                {"key": "ca.crt", "path": "ca.crt"},
                {"key": "extra", "path": "extra"},
            ],
        ),
    ),
    (
        "bridge-items-wrong-path",
        lambda volume: volume["secret"]["items"][0].__setitem__("path", "wrong.crt"),
    ),
    (
        "bridge-items-omitted",
        lambda volume: volume["secret"].pop("items"),
    ),
    (
        "bridge-items-unhashable",
        lambda volume: volume["secret"].__setitem__(
            "items",
            [
                {"key": ["client.crt"], "path": "client.crt"},
                {"key": "client.key", "path": "client.key"},
                {"key": "ca.crt", "path": "ca.crt"},
            ],
        ),
    ),
):
    documents = copy.deepcopy(rendered_documents)
    bridge_volume = next(
        volume
        for document in documents
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
        for volume in document["spec"]["template"]["spec"]["volumes"]
        if volume.get("name") == "hobby-tcp-proxy-bridge"
    )
    mutate(bridge_volume)
    _, issues = module.validate_gateway_ws_values(
        documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
    )
    if not any("items must select exactly" in issue for issue in issues):
        raise SystemExit(f"{case_name} was accepted: {issues}")

bridge_identity_documents = copy.deepcopy(rendered_documents)
next(
    volume for volume in next(
        document for document in bridge_identity_documents
        if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    )["spec"]["template"]["spec"]["volumes"]
    if volume.get("name") == "hobby-tcp-proxy-bridge"
)["secret"]["secretName"] = "grpc-tls"
_, bridge_identity_issues = module.validate_gateway_ws_values(
    bridge_identity_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("dedicated read-only Secret-backed /tls mount" in issue for issue in bridge_identity_issues):
    raise SystemExit(f"bridge client reused the gRPC Secret identity without failing: {bridge_identity_issues}")

bridge_same_identity_documents = copy.deepcopy(rendered_documents)
bridge_same_identity_deployment = next(
    document
    for document in bridge_same_identity_documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
next(
    volume for volume in bridge_same_identity_deployment["spec"]["template"]["spec"]["volumes"]
    if volume.get("name") == "grpc-tls"
)["secret"]["secretName"] = "hobby-tcp-proxy-bridge"
_, bridge_same_identity_issues = module.validate_gateway_ws_values(
    bridge_same_identity_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("dedicated read-only Secret-backed /tls mount" in issue for issue in bridge_same_identity_issues):
    raise SystemExit(f"bridge client reused the gRPC Secret identity without failing: {bridge_same_identity_issues}")

bridge_missing_grpc_path_documents = copy.deepcopy(rendered_documents)
bridge_missing_grpc_path_container = next(
    container
    for document in bridge_missing_grpc_path_documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for container in document["spec"]["template"]["spec"]["containers"]
    if container.get("name") == "tcp-proxy-service"
)
bridge_missing_grpc_path_container["env"] = [
    entry
    for entry in bridge_missing_grpc_path_container["env"]
    if entry.get("name") != "FIREMUD_GRPC_CA_CERT_PATH"
]
_, bridge_missing_grpc_path_issues = module.validate_gateway_ws_values(
    bridge_missing_grpc_path_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("all canonical gRPC TLS path variables" in issue for issue in bridge_missing_grpc_path_issues):
    raise SystemExit(f"missing gRPC TLS path was accepted: {bridge_missing_grpc_path_issues}")

bridge_missing_grpc_mount_documents = copy.deepcopy(rendered_documents)
bridge_missing_grpc_mount_container = next(
    container
    for document in bridge_missing_grpc_mount_documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for container in document["spec"]["template"]["spec"]["containers"]
    if container.get("name") == "tcp-proxy-service"
)
bridge_missing_grpc_mount_container["volumeMounts"] = [
    mount
    for mount in bridge_missing_grpc_mount_container["volumeMounts"]
    if mount.get("name") != "grpc-tls"
]
_, bridge_missing_grpc_mount_issues = module.validate_gateway_ws_values(
    bridge_missing_grpc_mount_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("dedicated read-only Secret-backed gRPC TLS mount" in issue for issue in bridge_missing_grpc_mount_issues):
    raise SystemExit(f"missing gRPC TLS mount was accepted: {bridge_missing_grpc_mount_issues}")

bridge_writable_grpc_mount_documents = copy.deepcopy(rendered_documents)
bridge_writable_grpc_mount = next(
    mount
    for document in bridge_writable_grpc_mount_documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for container in document["spec"]["template"]["spec"]["containers"]
    if container.get("name") == "tcp-proxy-service"
    for mount in container["volumeMounts"]
    if mount.get("name") == "grpc-tls"
)
bridge_writable_grpc_mount["readOnly"] = False
_, bridge_writable_grpc_mount_issues = module.validate_gateway_ws_values(
    bridge_writable_grpc_mount_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("dedicated read-only Secret-backed gRPC TLS mount" in issue for issue in bridge_writable_grpc_mount_issues):
    raise SystemExit(f"writable gRPC TLS mount was accepted: {bridge_writable_grpc_mount_issues}")

bridge_namespace_documents = copy.deepcopy(rendered_documents)
bridge_namespace_deployment = next(
    document
    for document in bridge_namespace_documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
bridge_namespace_deployment["metadata"]["namespace"] = "other"
_, bridge_namespace_issues = module.validate_gateway_ws_values(
    bridge_namespace_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("namespace does not match" in issue for issue in bridge_namespace_issues):
    raise SystemExit(f"bridge workload namespace mismatch was accepted: {bridge_namespace_issues}")

telnet_documents = [
    {
        "kind": "Service",
        "metadata": {"name": "tcp-proxy-service", "namespace": "firemud"},
        "spec": {"type": "NodePort"},
    },
    {
        "kind": "Certificate",
        "metadata": {"name": "hobby-telnet-tls", "namespace": "firemud"},
        "spec": {"secretName": "hobby-telnet-tls"},
    },
    {
        "kind": "Ingress",
        "metadata": {"name": "hobby-ingress", "namespace": "firemud"},
        "spec": {"tls": [{"secretName": "hobby-http-tls"}]},
    },
    {
        "kind": "Deployment",
        "metadata": {"name": "tcp-proxy-service", "namespace": "firemud"},
        "spec": {
            "template": {
                "spec": {
                    "containers": [
                        {
                            "name": "tcp-proxy-service",
                            "env": [
                                {"name": "TCP_PROXY_TLS_ENABLED", "value": "true"},
                                {"name": "TCP_PROXY_TLS_CERT", "value": "/telnet-tls/tls.crt"},
                                {"name": "TCP_PROXY_TLS_KEY", "value": "/telnet-tls/tls.key"},
                            ],
                            "volumeMounts": [
                                {"name": "telnet-tls", "mountPath": "/telnet-tls", "readOnly": True}
                            ],
                        }
                    ],
                    "volumes": [
                        {"name": "telnet-tls", "secret": {"secretName": "hobby-telnet-tls"}}
                    ],
                }
            }
        },
    },
]
if module.validate_hosted_telnet_tls_values(telnet_documents):
    raise SystemExit("canonical hosted Telnet TLS fixture did not pass")
telnet_cross_namespace_decoys = copy.deepcopy(telnet_documents)
telnet_cross_namespace_decoys.extend(
    [
        {
            "kind": "Certificate",
            "metadata": {"name": "hobby-telnet-tls", "namespace": "other"},
            "spec": {"secretName": "wrong-cross-namespace-secret"},
        },
        {
            "kind": "Ingress",
            "metadata": {"name": "other-ingress", "namespace": "other"},
            "spec": {"tls": [{"secretName": "hobby-telnet-tls"}]},
        },
        {
            "kind": "Deployment",
            "metadata": {"name": "tcp-proxy-service", "namespace": "other"},
            "spec": {"template": {"spec": {"containers": []}}},
        },
    ]
)
if module.validate_hosted_telnet_tls_values(telnet_cross_namespace_decoys):
    raise SystemExit("same-name Telnet TLS resources in another namespace affected validation")
telnet_ambiguous_nodeports = copy.deepcopy(telnet_documents)
telnet_ambiguous_nodeports.append(
    {
        "kind": "Service",
        "metadata": {"name": "tcp-proxy-service", "namespace": "other"},
        "spec": {"type": "NodePort"},
    }
)
telnet_ambiguity_issues = module.validate_hosted_telnet_tls_values(telnet_ambiguous_nodeports)
if not any(
    "exactly one tcp-proxy-service NodePort Service" in issue
    for issue in telnet_ambiguity_issues
):
    raise SystemExit("multiple cross-namespace tcp-proxy-service NodePorts were accepted")

redis_endpoints, redis_issues = module.effective_redis_endpoints(
    rendered_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if redis_issues or redis_endpoints != {
    "redis-coord.firemud.svc.cluster.local:6379",
    "redis-cache.firemud.svc.cluster.local:6379",
}:
    raise SystemExit(f"canonical Redis fixture did not pass: {redis_issues}, {redis_endpoints}")
redis_mutation = copy.deepcopy(rendered_documents)
for document in redis_mutation:
    if document.get("kind") == "ConfigMap" and document.get("metadata", {}).get("name") == "firemud-config":
        document["data"]["FIREMUD_REDIS_COORD_HOST"] = "redis-cache"
_, redis_mutation_issues = module.effective_redis_endpoints(
    redis_mutation, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("does not match expected" in issue for issue in redis_mutation_issues):
    raise SystemExit("referenced Redis ConfigMap mismatch was accepted")

redis_url_documents = copy.deepcopy(rendered_documents)
redis_url_config_map = next(
    document
    for document in redis_url_documents
    if document.get("kind") == "ConfigMap" and document.get("metadata", {}).get("name") == "firemud-config"
)
for key in (
    "FIREMUD_REDIS_COORD_HOST",
    "FIREMUD_REDIS_COORD_PORT",
    "FIREMUD_REDIS_CACHE_HOST",
    "FIREMUD_REDIS_CACHE_PORT",
):
    redis_url_config_map["data"].pop(key)
redis_url_config_map["data"].update(
    {
        "FIREMUD_REDIS_COORD_URL": "redis://redis-coord.firemud.svc.cluster.local:6379",
        "FIREMUD_REDIS_CACHE_URL": "redis://redis-cache.firemud.svc.cluster.local:6379",
    }
)
redis_url_endpoints, redis_url_issues = module.effective_redis_endpoints(
    redis_url_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if redis_url_issues or redis_url_endpoints != {
    "redis-coord.firemud.svc.cluster.local:6379",
    "redis-cache.firemud.svc.cluster.local:6379",
}:
    raise SystemExit(f"Redis URL-only configuration did not pass: {redis_url_issues}, {redis_url_endpoints}")

redis_precedence_documents = copy.deepcopy(rendered_documents)
redis_precedence_config_map = next(
    document
    for document in redis_precedence_documents
    if document.get("kind") == "ConfigMap" and document.get("metadata", {}).get("name") == "firemud-config"
)
redis_precedence_config_map["data"].update(
    {
        "FIREMUD_REDIS_COORD_URL": "redis://redis-coord.firemud.svc.cluster.local:6379",
        "FIREMUD_REDIS_CACHE_URL": "redis://redis-cache.firemud.svc.cluster.local:6379",
        "FIREMUD_REDIS_COORD_HOST": "redis-cache",
        "FIREMUD_REDIS_CACHE_HOST": "redis-coord",
    }
)
_, redis_precedence_issues = module.effective_redis_endpoints(
    redis_precedence_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if redis_precedence_issues:
    raise SystemExit(f"Redis URL precedence was not honored: {redis_precedence_issues}")

for case_name, field, value in (
    ("cache-host", "FIREMUD_REDIS_CACHE_HOST", "redis-coord"),
    ("cache-port", "FIREMUD_REDIS_CACHE_PORT", "6380"),
):
    documents = copy.deepcopy(rendered_documents)
    config_map = next(
        document
        for document in documents
        if document.get("kind") == "ConfigMap" and document.get("metadata", {}).get("name") == "firemud-config"
    )
    config_map["data"][field] = value
    _, issues = module.effective_redis_endpoints(
        documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
    )
    if not any("does not match expected" in issue for issue in issues):
        raise SystemExit(f"Redis {case_name} mismatch was accepted: {issues}")

collision_expected = yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
collision_expected["internalBindings"]["redis"]["cache"]["endpoint"] = collision_expected["internalBindings"]["redis"]["coordination"]["endpoint"]
collision_documents = copy.deepcopy(rendered_documents)
collision_config_map = next(
    document
    for document in collision_documents
    if document.get("kind") == "ConfigMap" and document.get("metadata", {}).get("name") == "firemud-config"
)
collision_config_map["data"]["FIREMUD_REDIS_CACHE_HOST"] = "redis-coord"
_, collision_issues = module.effective_redis_endpoints(collision_documents, collision_expected)
if not any("same host:port" in issue for issue in collision_issues):
    raise SystemExit(f"Redis coordination/cache endpoint collision was accepted: {collision_issues}")

for case_name, mutate, expected_fragment in (
    (
        "unknown-top-level",
        lambda data: data.__setitem__("typoSection", {"enabled": True}),
        "unknown top-level key",
    ),
    (
        "unknown-nested-field",
        lambda data: data["internalBindings"]["jwt"].__setitem__("jwksReff", "secret://firemud/jwt-jwks"),
        "internalBindings.jwt.jwksReff",
    ),
    (
        "missing-queryability",
        lambda data: data["observability"].pop("logPipelineQueryability"),
        "observability.logPipelineQueryability must contain exactly",
    ),
    (
        "malformed-queryability-capability",
        lambda data: data["observability"]["logPipelineQueryability"].__setitem__("capability", []),
        "observability.logPipelineQueryability.capability must be a string",
    ),
):
    malformed = yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
    mutate(malformed)
    malformed_path = tmp / f"{case_name}-expected-bindings.yaml"
    malformed_path.write_text(yaml.safe_dump(malformed, sort_keys=False), encoding="utf-8")
    malformed_results = module.expected_binding_checks(
        malformed_path,
        f"synthetic-{case_name}-expected-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    malformed_secrets = next(
        result for result in malformed_results if result.policy_id == "PREFLIGHT-SECRETS-002"
    )
    if malformed_secrets.status != "fail" or expected_fragment not in malformed_secrets.message:
        raise SystemExit(
            f"{case_name}: invalid expected-bindings schema was accepted: {malformed_secrets.message}"
        )

requirements = module.expected_preflight_policy_requirements("hobby-self-hosted", None)
if requirements["PREFLIGHT-JWT-001"] or requirements["PREFLIGHT-JWKS-001"]:
    raise SystemExit("JWT/JWKS diagnostics must be catalogued as advisory applicability")
diagnostic_results = []
if module.append_result(
    diagnostic_results,
    "PREFLIGHT-JWT-001",
    True,
    "fail",
    "synthetic JWT diagnostic",
):
    raise SystemExit("advisory JWT diagnostic incorrectly blocked apply")
if diagnostic_results[0].required:
    raise SystemExit("append_result did not normalize advisory required applicability")

operator_results = module.expected_binding_checks(
    current_expected_path,
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "hobby-self-hosted",
    rendered_documents,
    context="operator",
)
operator_bootstrap = next(
    result for result in operator_results if result.policy_id == "PREFLIGHT-BOOTSTRAP-001"
)
if (
    operator_bootstrap.status != "fail"
    or not operator_bootstrap.required
    or "accepted player-facing JWT custody proof" not in operator_bootstrap.message
):
    raise SystemExit(
        "operator custody gate did not fail closed without accepted proof: "
        + operator_bootstrap.message
    )
static_bootstrap = next(
    result for result in current_results if result.policy_id == "PREFLIGHT-BOOTSTRAP-001"
)
if static_bootstrap.status != "pass":
    raise SystemExit("ci-static bootstrap diagnostics unexpectedly became an operator custody gate")


def verify_jwt_custody_selector(case_name, mutate, expected_fragment):
    expected = yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = tmp / f"{case_name}-jwt-custody-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"synthetic-{case_name}-jwt-custody-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    secrets = next(result for result in results if result.policy_id == "PREFLIGHT-SECRETS-002")
    if secrets.status != "fail" or expected_fragment not in secrets.message:
        raise SystemExit(
            f"{case_name}: JWT custody selector did not fail as expected: {secrets.message}"
        )


verify_jwt_custody_selector(
    "missing",
    lambda data: data["internalBindings"]["jwt"].pop("custodyMode"),
    "internalBindings.jwt.custodyMode",
)
verify_jwt_custody_selector(
    "unknown",
    lambda data: data["internalBindings"]["jwt"].__setitem__("custodyMode", "UNKNOWN_MODE"),
    "must be one of:",
)
for target_mode in (
    "INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK",
    "TARGET_NON_EXPORTABLE_SIGNER",
):
    verify_jwt_custody_selector(
        target_mode.lower(),
        lambda data, mode=target_mode: data["internalBindings"]["jwt"].__setitem__("custodyMode", mode),
        "not currently implemented",
    )

def verify_binding_ref_contract(case_name, mutate, policy_id, expected_fragment):
    expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = tmp / f"{case_name}-expected-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"design/operations/environments/{case_name}-expected-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    policy = next((result for result in results if result.policy_id == policy_id), None)
    if policy is None:
        raise SystemExit(f"{case_name}: missing {policy_id} result")
    if policy.status != "fail":
        raise SystemExit(f"{case_name}: expected {policy_id} fail, got {policy.status}: {policy.message}")
    if expected_fragment not in policy.message:
        raise SystemExit(
            f"{case_name}: expected {policy_id} message to include '{expected_fragment}', got '{policy.message}'"
        )

def verify_operator_credentials_fingerprint_pass():
    expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    expected["operatorCredentials"].pop("bindingRef")
    expected["operatorCredentials"]["fingerprint"] = "sha256:operator-identity"
    case_path = env_root / "hobby-self-hosted" / "fingerprint-only-operator-credentials.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        "synthetic-fingerprint-only-operator-credentials.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    policy = next(result for result in results if result.policy_id == "PREFLIGHT-EXTERNAL-001")
    if policy.status != "pass":
        raise SystemExit(
            "fingerprint-only operator credentials should pass external preflight: "
            + policy.message
        )

verify_operator_credentials_fingerprint_pass()

def verify_backup_storage_failure(case_name, mutate, expected_fragment, env_class="hobby-self-hosted"):
    expected_path = root / f"design/operations/environments/{env_class}/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = env_root / env_class / f"{case_name}-expected-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"synthetic-{case_name}-expected-bindings.yaml",
        env_class,
        rendered_documents,
    )
    policy = next(result for result in results if result.policy_id == "PREFLIGHT-EXTERNAL-001")
    if policy.status != "fail" or expected_fragment not in policy.message:
        raise SystemExit(
            f"{case_name}: expected backup-storage failure containing '{expected_fragment}', got {policy.status}: {policy.message}"
        )

def verify_backup_storage_pass(case_name, mutate):
    expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = env_root / "hobby-self-hosted" / f"{case_name}-expected-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"synthetic-{case_name}-expected-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    policy = next(result for result in results if result.policy_id == "PREFLIGHT-EXTERNAL-001")
    if policy.status != "pass":
        raise SystemExit(f"{case_name}: expected backup-storage validation to pass: {policy.message}")

def verify_optional_integration_failure(case_name, mutate, expected_fragment):
    expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = env_root / "hobby-self-hosted" / f"{case_name}-expected-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"synthetic-{case_name}-expected-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    policy = next(result for result in results if result.policy_id == "PREFLIGHT-EXTERNAL-001")
    if policy.status != "fail" or expected_fragment not in policy.message:
        raise SystemExit(
            f"{case_name}: expected optional integration failure containing '{expected_fragment}', "
            f"got {policy.status}: {policy.message}"
        )

def verify_optional_integration_pass(case_name, mutate):
    expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = env_root / "hobby-self-hosted" / f"{case_name}-expected-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"synthetic-{case_name}-expected-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    policy = next(result for result in results if result.policy_id == "PREFLIGHT-EXTERNAL-001")
    if policy.status != "pass":
        raise SystemExit(f"{case_name}: expected optional integration validation to pass: {policy.message}")

verify_backup_storage_failure(
    "missing-backup-enabled",
    lambda data: data["backupStorage"].pop("enabled"),
    "backupStorage.enabled must be a boolean",
)
verify_backup_storage_failure(
    "wrong-type-backup-enabled",
    lambda data: data["backupStorage"].__setitem__("enabled", "true"),
    "backupStorage.enabled must be a boolean",
)
verify_backup_storage_failure(
    "enabled-missing-backup-bucket",
    lambda data: data["backupStorage"].pop("bucket"),
    "backupStorage.bucket",
)
verify_backup_storage_failure(
    "enabled-missing-backup-binding",
    lambda data: data["backupStorage"].pop("bindingRef"),
    "backupStorage.bindingRef or backupStorage.fingerprint",
)
verify_backup_storage_failure(
    "disabled-populated-backup",
    lambda data: (
        data["backupStorage"].__setitem__("enabled", False),
        data["backupStorage"].__setitem__("fingerprint", "sha256:stale-backup-identity"),
    ),
    "backupStorage fields must be omitted when disabled",
)
verify_backup_storage_failure(
    "production-disabled-backup",
    lambda data: data.__setitem__("backupStorage", {"enabled": False}),
    "backupStorage.enabled must be true for production",
    "production",
)
verify_backup_storage_pass(
    "enabled-fingerprint-backup",
    lambda data: (
        data["backupStorage"].pop("bindingRef"),
        data["backupStorage"].__setitem__("fingerprint", "sha256:backup-identity"),
    ),
)
verify_backup_storage_pass(
    "disabled-omitted-backup",
    lambda data: (
        data.__setitem__("backupStorage", {"enabled": False}),
    ),
)

verify_optional_integration_failure(
    "missing-asset-enabled",
    lambda data: data["assetStorage"].pop("enabled"),
    "assetStorage.enabled must be a boolean",
)
verify_optional_integration_failure(
    "wrong-asset-enabled",
    lambda data: data["assetStorage"].__setitem__("enabled", "true"),
    "assetStorage.enabled must be a boolean",
)
verify_optional_integration_failure(
    "null-asset-section",
    lambda data: data.__setitem__("assetStorage", None),
    "assetStorage must be an object",
)
verify_optional_integration_failure(
    "malformed-asset-bucket-binding",
    lambda data: data["assetStorage"].__setitem__("bucket", {"shared": True}),
    "assetStorage.bucket",
)
verify_optional_integration_failure(
    "missing-asset-bucket",
    lambda data: data["assetStorage"].pop("bucket"),
    "assetStorage.bucket",
)
verify_optional_integration_failure(
    "missing-asset-binding",
    lambda data: data["assetStorage"].pop("bindingRef"),
    "assetStorage.bindingRef or assetStorage.fingerprint",
)
verify_optional_integration_failure(
    "disabled-populated-asset",
    lambda data: (
        data["assetStorage"].__setitem__("enabled", False),
        data["assetStorage"].__setitem__("bucket", "stale-assets"),
    ),
    "assetStorage fields must be omitted when disabled",
)
verify_optional_integration_failure(
    "missing-outbound-enabled",
    lambda data: data["outboundComms"].pop("enabled"),
    "outboundComms.enabled must be a boolean",
)
verify_optional_integration_failure(
    "null-outbound-section",
    lambda data: data.__setitem__("outboundComms", None),
    "outboundComms must be an object",
)
verify_optional_integration_failure(
    "enabled-empty-outbound",
    lambda data: data.__setitem__("outboundComms", {"enabled": True}),
    "Enabled outbound communications require smtpHost or webhookTargets",
)
verify_optional_integration_failure(
    "wrong-type-outbound-targets",
    lambda data: data["outboundComms"].__setitem__("webhookTargets", ["invalid"]),
    "outboundComms.webhookTargets must be a non-empty mapping when present",
)
verify_optional_integration_failure(
    "malformed-outbound-target-binding",
    lambda data: data["outboundComms"].__setitem__(
        "webhookTargets", {"accountNotifications": {"shared": True}}
    ),
    "outboundComms.webhookTargets entries must be non-empty binding values",
)
verify_optional_integration_failure(
    "disabled-populated-outbound",
    lambda data: (
        data["outboundComms"].__setitem__("enabled", False),
        data["outboundComms"].__setitem__("smtpHost", "stale-smtp"),
    ),
    "outboundComms fields must be omitted when disabled",
)
verify_optional_integration_pass(
    "omitted-optional-integrations",
    lambda data: (data.pop("assetStorage"), data.pop("outboundComms")),
)

verify_binding_ref_contract(
    "invalid-internal-binding-ref",
    lambda data: data["internalBindings"]["certificates"].__setitem__("issuerRef", "cert-manager://firemud/not-a-kind/firemud-hobby"),
    "PREFLIGHT-SECRETS-002",
    "internalBindings.certificates.issuerRef must use one of the allowed binding kinds",
)
verify_binding_ref_contract(
    "configmap-jwks-binding-ref",
    lambda data: data["internalBindings"]["jwt"].__setitem__("jwksRef", "configmap://firemud/jwt-jwks"),
    "PREFLIGHT-SECRETS-002",
    "internalBindings.jwt.jwksRef must use one of the allowed schemes: secret",
)
verify_binding_ref_contract(
    "invalid-external-binding-ref",
    lambda data: data["backupStorage"].__setitem__("bindingRef", "not-a-binding-ref"),
    "PREFLIGHT-EXTERNAL-001",
    "backupStorage.bindingRef must use <scheme>://<namespace>/<binding> format",
)
verify_binding_ref_contract(
    "invalid-operator-credentials-binding-ref",
    lambda data: data["operatorCredentials"].__setitem__("bindingRef", "not-a-binding-ref"),
    "PREFLIGHT-EXTERNAL-001",
    "operatorCredentials.bindingRef must use <scheme>://<namespace>/<binding> format",
)
verify_binding_ref_contract(
    "wrong-secret-namespace",
    lambda data: data["internalBindings"]["postgres"].__setitem__(
        "credentialsRef", "secret://other/postgres-credentials"
    ),
    "PREFLIGHT-SECRETS-002",
    "Rendered workloads do not reference expected Secret bindings",
)

fingerprint_only_external = copy.deepcopy(
    yaml.safe_load(
        (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(
            encoding="utf-8"
        )
    )
)
fingerprint_only_external["assetStorage"].pop("bindingRef")
fingerprint_only_external["assetStorage"]["fingerprint"] = "sha256:asset-fingerprint"
fingerprint_only_external["operatorCredentials"].pop("bindingRef")
fingerprint_only_external["operatorCredentials"]["fingerprint"] = "sha256:operator-fingerprint"
fingerprint_only_path = env_root / "hobby-self-hosted" / "fingerprint-only-external-bindings.yaml"
fingerprint_only_path.write_text(yaml.safe_dump(fingerprint_only_external, sort_keys=False), encoding="utf-8")
fingerprint_only_results = module.expected_binding_checks(
    fingerprint_only_path,
    "synthetic-fingerprint-only-external-bindings",
    "hobby-self-hosted",
    rendered_documents,
)
fingerprint_only_external_result = next(
    result for result in fingerprint_only_results if result.policy_id == "PREFLIGHT-EXTERNAL-001"
)
if fingerprint_only_external_result.status != "pass":
    raise SystemExit(
        "fingerprint-only asset/operator bindings should pass external validation: "
        + fingerprint_only_external_result.message
    )

verify_binding_ref_contract(
    "invalid-asset-binding-ref",
    lambda data: data["assetStorage"].__setitem__("bindingRef", "not-a-binding-ref"),
    "PREFLIGHT-EXTERNAL-001",
    "assetStorage.bindingRef must use <scheme>://<namespace>/<binding> format",
)
verify_binding_ref_contract(
    "invalid-operator-binding-ref",
    lambda data: data["operatorCredentials"].__setitem__("bindingRef", "not-a-binding-ref"),
    "PREFLIGHT-EXTERNAL-001",
    "operatorCredentials.bindingRef must use <scheme>://<namespace>/<binding> format",
)

def verify_backup_storage_contract(case_name, env_class, mutate, expected_status, expected_fragment):
    source_path = root / f"design/operations/environments/{env_class}/expected-bindings.yaml"
    expected = yaml.safe_load(source_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = env_root / env_class / f"{case_name}-expected-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"design/operations/environments/{env_class}/{case_name}-expected-bindings.yaml",
        env_class,
        rendered_documents,
    )
    external = next(result for result in results if result.policy_id == "PREFLIGHT-EXTERNAL-001")
    if external.status != expected_status or expected_fragment not in external.message:
        raise SystemExit(
            f"{case_name}: expected {expected_status} with '{expected_fragment}', "
            f"got {external.status}: {external.message}"
        )

verify_backup_storage_contract(
    "missing-backup-enablement",
    "hobby-self-hosted",
    lambda data: data["backupStorage"].pop("enabled"),
    "fail",
    "backupStorage.enabled must be a boolean",
)
verify_backup_storage_contract(
    "nonboolean-backup-enablement",
    "hobby-self-hosted",
    lambda data: data["backupStorage"].__setitem__("enabled", "true"),
    "fail",
    "backupStorage.enabled must be a boolean",
)
verify_backup_storage_contract(
    "production-backup-disabled",
    "production",
    lambda data: (
        data["backupStorage"].__setitem__("enabled", False),
        [data["backupStorage"].pop(field, None) for field in ("bucket", "endpoint", "bindingRef", "fingerprint")],
    ),
    "fail",
    "backupStorage.enabled must be true for production",
)
verify_backup_storage_contract(
    "enabled-backup-missing-binding",
    "hobby-self-hosted",
    lambda data: data["backupStorage"].pop("bindingRef"),
    "fail",
    "enabled backup storage missing keys",
)
verify_backup_storage_contract(
    "disabled-backup-placeholder",
    "hobby-self-hosted",
    lambda data: data["backupStorage"].__setitem__("enabled", False),
    "fail",
    "enabled=false must omit backup binding fields",
)
verify_backup_storage_contract(
    "disabled-backup-valid",
    "hobby-self-hosted",
    lambda data: (
        data["backupStorage"].__setitem__("enabled", False),
        [data["backupStorage"].pop(field, None) for field in ("bucket", "endpoint", "bindingRef", "fingerprint")],
    ),
    "pass",
    "External bindings are environment-scoped",
)

disabled_backup = copy.deepcopy(hobby)
disabled_backup["backupStorage"] = {"enabled": False}
disabled_backup_issues = module.external_binding_uniqueness_issues(
    env_root, "hobby-self-hosted", disabled_backup
)
if any("backupStorage." in issue for issue in disabled_backup_issues):
    raise SystemExit(
        "disabled backup storage should be ignored for uniqueness: " + "; ".join(disabled_backup_issues)
    )

routine_expected = yaml.safe_load(
    (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(encoding="utf-8")
)
routine_path = tmp / "routine-online-backup-bindings.yaml"
routine_path.write_text(yaml.safe_dump(routine_expected, sort_keys=False), encoding="utf-8")
routine_results = module.expected_binding_checks(
    routine_path,
    "synthetic-routine-online-backup-bindings",
    "hobby-self-hosted",
    rendered_documents,
)
routine_secrets = next(result for result in routine_results if result.policy_id == "PREFLIGHT-SECRETS-002")
if routine_secrets.status != "pass":
    raise SystemExit(f"routine online backup must not require backup control-plane identity: {routine_secrets.message}")

exceptional_expected = yaml.safe_load(
    (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(encoding="utf-8")
)
exceptional_expected["backupMaintenancePause"] = {"enabled": True}
exceptional_expected["internalBindings"]["certificates"]["backupControlPlaneClientRef"] = "not-a-binding-ref"
exceptional_path = tmp / "exceptional-backup-pause-bindings.yaml"
exceptional_path.write_text(yaml.safe_dump(exceptional_expected, sort_keys=False), encoding="utf-8")
exceptional_results = module.expected_binding_checks(
    exceptional_path,
    "synthetic-exceptional-backup-pause-bindings",
    "hobby-self-hosted",
    rendered_documents,
)
exceptional_secrets = next(result for result in exceptional_results if result.policy_id == "PREFLIGHT-SECRETS-002")
if exceptional_secrets.status != "fail" or "backupControlPlaneClientRef" not in exceptional_secrets.message:
    raise SystemExit(f"explicit backup maintenance pause opt-in did not validate its client binding: {exceptional_secrets.message}")

valid_exceptional_expected = yaml.safe_load(
    (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(encoding="utf-8")
)
valid_exceptional_expected["backupMaintenancePause"] = {"enabled": True}
valid_exceptional_expected["internalBindings"]["certificates"]["backupControlPlaneClientRef"] = (
    "cert-manager://firemud/hobby-backup-control-plane"
)
valid_exceptional_path = tmp / "valid-exceptional-backup-pause-bindings.yaml"
valid_exceptional_path.write_text(yaml.safe_dump(valid_exceptional_expected, sort_keys=False), encoding="utf-8")
valid_exceptional_results = module.expected_binding_checks(
    valid_exceptional_path,
    "synthetic-valid-exceptional-backup-pause-bindings",
    "hobby-self-hosted",
    rendered_documents,
)
valid_exceptional_secrets = next(
    result for result in valid_exceptional_results if result.policy_id == "PREFLIGHT-SECRETS-002"
)
if valid_exceptional_secrets.status != "pass":
    raise SystemExit(f"valid explicit backup maintenance pause configuration did not pass: {valid_exceptional_secrets.message}")

malformed_pause_expected = yaml.safe_load(
    (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(encoding="utf-8")
)
malformed_pause_expected["backupMaintenancePause"] = {"enabled": "true"}
malformed_pause_path = tmp / "malformed-backup-pause-bindings.yaml"
malformed_pause_path.write_text(yaml.safe_dump(malformed_pause_expected, sort_keys=False), encoding="utf-8")
malformed_pause_results = module.expected_binding_checks(
    malformed_pause_path,
    "synthetic-malformed-backup-pause-bindings",
    "hobby-self-hosted",
    rendered_documents,
)
malformed_pause_secrets = next(
    result for result in malformed_pause_results if result.policy_id == "PREFLIGHT-SECRETS-002"
)
if malformed_pause_secrets.status != "fail" or "must be a boolean" not in malformed_pause_secrets.message:
    raise SystemExit(f"malformed backup maintenance pause opt-in did not fail closed: {malformed_pause_secrets.message}")

undeclared_pause_expected = yaml.safe_load(
    (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(encoding="utf-8")
)
undeclared_pause_expected["internalBindings"]["certificates"]["backupControlPlaneClientRef"] = (
    "cert-manager://firemud/hobby-backup-control-plane"
)
undeclared_pause_path = tmp / "undeclared-backup-pause-bindings.yaml"
undeclared_pause_path.write_text(yaml.safe_dump(undeclared_pause_expected, sort_keys=False), encoding="utf-8")
undeclared_pause_results = module.expected_binding_checks(
    undeclared_pause_path,
    "synthetic-undeclared-backup-pause-bindings",
    "hobby-self-hosted",
    rendered_documents,
)
undeclared_pause_secrets = next(
    result for result in undeclared_pause_results if result.policy_id == "PREFLIGHT-SECRETS-002"
)
if undeclared_pause_secrets.status != "fail" or "must be omitted" not in undeclared_pause_secrets.message:
    raise SystemExit(f"undeclared backup maintenance identity did not fail closed: {undeclared_pause_secrets.message}")

now = module.dt.datetime.now(module.dt.timezone.utc).replace(microsecond=0)
current_timestamp = now.isoformat().replace("+00:00", "Z")
current_epoch = int(now.timestamp())
past_time = now - module.dt.timedelta(minutes=5)
past_timestamp = past_time.isoformat().replace("+00:00", "Z")
past_epoch = int(past_time.timestamp())
future_timestamp = (now + module.dt.timedelta(hours=1)).isoformat().replace("+00:00", "Z")
freshness_timestamp = (now - module.dt.timedelta(minutes=15)).isoformat().replace("+00:00", "Z")
older_freshness_timestamp = (now - module.dt.timedelta(minutes=20)).isoformat().replace("+00:00", "Z")
# Recovery validation runs against this isolated repository root, so retain the
# production expected-bindings manifest as the authoritative target binding.
production_expected_bindings_path = tmp / "design/operations/environments/production/expected-bindings.yaml"
production_expected_bindings_path.parent.mkdir(parents=True, exist_ok=True)
production_expected_bindings_path.write_text(
    (root / "design/operations/environments/production/expected-bindings.yaml").read_text(encoding="utf-8"),
    encoding="utf-8",
)
smoke_evidence_ref = "evidence/player-experience-smoke.json"
smoke_evidence_path = tmp / smoke_evidence_ref
smoke_evidence_path.parent.mkdir(parents=True)
smoke_evidence_path.write_text(
    json.dumps(
        {
            "deploymentRef": "staging-contract",
            "verifiedAt": current_timestamp,
            "verifiedBy": "preflight-contract",
            "preflightEvidenceRef": "ci://preflight-contract",
            "executionMode": "live",
            "externalAuthorityProvenance": "retained-external",
            "logPipelineQueryability": {
                "selectedProfile": "production",
                "capability": "indexed-log-observability",
                "backend": "elasticsearch",
                "storageTarget": "firemud-logs-*",
                "recordId": "preflight-contract-log-smoke-11111111-2222-4333-8444-555555555555",
                "service": "game-session-service",
                "traceId": "preflight-contract-trace-9c8d7e6f5a4b3210",
                "queryPath": "kibana-saved-search:player-incident-drilldown",
                "configuredDelayTargetSeconds": 120,
                "emittedAt": (now - module.dt.timedelta(seconds=120)).isoformat().replace("+00:00", "Z"),
                "retrievedAt": (now - module.dt.timedelta(seconds=60)).isoformat().replace("+00:00", "Z"),
                "observedDelaySeconds": 60,
                "result": "passed",
                "evidenceObservedAt": current_timestamp,
                "evidenceFreshnessBudgetSeconds": 7200,
                "evidenceExpiresAt": (now + module.dt.timedelta(hours=2)).isoformat().replace("+00:00", "Z"),
                "evidenceRef": "query-proof://preflight-contract/log-smoke-11111111-2222-4333-8444-555555555555",
                "verifiedFields": ["recordId", "service", "traceId"],
            },
            "capabilities": {
                "prometheusMirrors": "published",
                "playerFlowCanary": "omitted",
            },
            "externalAuthority": {
                "profile": "independent-required",
                "exposedPublicPlayerPaths": ["websocket", "telnet"],
                "detectionBudgetSeconds": 195,
                "staleThresholdSeconds": 180,
                "evidenceObservedAt": current_timestamp,
                "lastSuccessfulHeartbeatObservedAt": current_timestamp,
                "observedStalenessSeconds": 0,
                "deadmanAuthority": {
                    "status": "green",
                    "evidenceRef": "pager://staging-contract/deadman",
                    "pageEvidenceRef": "pager://staging-contract/deadman/page",
                    "target": "staging-contract-deadman",
                    "checkRef": "check://staging-contract/deadman",
                },
                "publicPathChecks": {
                    "websocket": {
                        "status": "green",
                        "evidenceRef": "probe://staging-contract/websocket",
                        "pageEvidenceRef": "pager://staging-contract/websocket/page",
                        "target": "staging-contract-websocket",
                        "lastSuccessfulProbeObservedAt": current_timestamp,
                        "observedProbeAgeSeconds": 0,
                    },
                    "telnet": {
                        "status": "green",
                        "evidenceRef": "probe://staging-contract/telnet",
                        "pageEvidenceRef": "pager://staging-contract/telnet/page",
                        "target": "staging-contract-telnet",
                        "lastSuccessfulProbeObservedAt": current_timestamp,
                        "observedProbeAgeSeconds": 0,
                    },
                },
            },
            "mirroredSignals": {
                "entrypath_blackbox_probe_success": [
                    {"path": "websocket", "target": "gateway", "value": 1},
                    {"path": "telnet", "target": "tcp_proxy", "value": 1},
                ],
                "observability_deadman_heartbeat_timestamp_seconds": {
                    "source": "staging-contract",
                    "value": current_epoch,
                },
            },
        }
    ),
    encoding="utf-8",
)
recovery_smoke_entry = {
    "ref": smoke_evidence_ref,
    "contentDigest": "sha256:" + hashlib.sha256(smoke_evidence_path.read_bytes()).hexdigest(),
}

original_subprocess_run = module.subprocess.run


def timed_out_smoke_validator(*args, **kwargs):
    raise module.subprocess.TimeoutExpired(args[0], kwargs.get("timeout"))


module.subprocess.run = timed_out_smoke_validator
try:
    timeout_status, timeout_message = module.validate_retained_smoke_evidence(
        tmp,
        [smoke_evidence_ref],
        "Contract smokeEvidence",
    )
finally:
    module.subprocess.run = original_subprocess_run
if timeout_status != "fail" or "validation timed out" not in timeout_message:
    raise SystemExit(f"smoke evidence validator timeout did not fail closed: {timeout_message}")


def unavailable_smoke_validator(*args, **kwargs):
    raise OSError("validator executable missing")


module.subprocess.run = unavailable_smoke_validator
try:
    unavailable_status, unavailable_message = module.validate_retained_smoke_evidence(
        tmp,
        [smoke_evidence_ref],
        "Contract smokeEvidence",
    )
finally:
    module.subprocess.run = original_subprocess_run
if unavailable_status != "fail" or "could not run: validator executable missing" not in unavailable_message:
    raise SystemExit(f"smoke evidence validator launch failure did not fail closed: {unavailable_message}")

missing_queryability_evidence = json.loads(smoke_evidence_path.read_text(encoding="utf-8"))
missing_queryability_evidence.pop("logPipelineQueryability", None)
original_smoke_evidence_bytes = smoke_evidence_path.read_bytes()
smoke_evidence_path.write_text(json.dumps(missing_queryability_evidence), encoding="utf-8")
try:
    missing_queryability_status, missing_queryability_message = module.validate_retained_smoke_evidence(
        tmp,
        [smoke_evidence_ref],
        "Contract smokeEvidence",
    )
finally:
    smoke_evidence_path.write_bytes(original_smoke_evidence_bytes)
if missing_queryability_status != "fail" or "logPipelineQueryability is required" not in missing_queryability_message:
    raise SystemExit(
        "missing queryability posture evidence did not fail closed: "
        + missing_queryability_message
    )

recovery_dir = tmp / "design/operations/deployments/production/recovery"
recovery_dir.mkdir(parents=True)

first_event_id = "11111111-1111-4111-8111-111111111111"
second_event_id = "22222222-2222-4222-8222-222222222222"
first_output_path = module.default_preflight_output_path(
    tmp,
    "staging",
    "contract-overlay",
    first_event_id,
)
second_output_path = module.default_preflight_output_path(
    tmp,
    "staging",
    "contract-overlay",
    second_event_id,
)
expected_first_path = (
    tmp
    / "design/operations/deployments/staging/preflight"
    / "contract-overlay"
    / f"{first_event_id}.json"
)
if first_output_path != expected_first_path or first_output_path == second_output_path:
    raise SystemExit("preflight default output paths are not immutable per deployment event")

def write_json(name, data):
    path = tmp / name
    path.write_text(json.dumps(data), encoding="utf-8")
    return path

def timestamp(value):
    return value.isoformat().replace("+00:00", "Z")

verified_point = {
    "schemaVersion": "verified-restorable-point/v1",
    "environment": "production",
    "databaseIdentity": {
        "clusterIdentity": "production-postgres-cluster-20260824",
        "databaseName": "firemud",
    },
    "backupArtifact": {
        "artifactRef": "s3://firemud-production/backups/20260824T120000Z.sql.gz",
        "artifactIdentity": "snapshot-production-20260824T120000Z",
        "artifactDigest": "sha256:" + "a" * 64,
        "lineageRef": "lineage/production-20260824T120000Z",
        "snapshotAt": "2026-08-24T12:00:00Z",
    },
    "verification": {
        "operationId": "verify-production-20260824T120500Z",
        "verifiedAt": "2026-08-24T12:05:00Z",
        "restoreToolIdentity": {
            "name": "psql",
            "version": "16.4",
            "digest": "sha256:" + "b" * 64,
        },
    },
    "recordDigest": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
}
scheduled_backup_script = (root / "dev-tools/backups/pg-dump-rotate.sh").read_text(encoding="utf-8")
scheduled_cronjob = (root / "k8s/postgres/pg-dump-cronjob.yaml").read_text(encoding="utf-8")
compose_file = (root / "docker/docker-compose.yml").read_text(encoding="utf-8")
scheduled_restore_script = (root / "dev-tools/restores/restore-latest-db.sh").read_text(encoding="utf-8")
local_backup_script = (root / "dev-tools/backups/backup-db.sh").read_text(encoding="utf-8")
local_restore_script = (root / "dev-tools/restores/restore-db.sh").read_text(encoding="utf-8")
if "pg_dump" not in scheduled_cronjob or ".sql.gz" not in scheduled_cronjob or 'gzip > "$DUMP"' not in scheduled_cronjob:
    raise SystemExit("scheduled Kubernetes backup manifest does not retain the pg_dump -> gzip -> .sql.gz producer pair")
if "pg_restore" in scheduled_cronjob:
    raise SystemExit("scheduled Kubernetes backup manifest must not select pg_restore for the hosted plain-SQL artifact")
if "PG_DUMP_ENDPOINT_IF_NONE_MATCH_CONFIRMED: ${PG_DUMP_ENDPOINT_IF_NONE_MATCH_CONFIRMED:-false}" not in compose_file:
    raise SystemExit("Docker Compose pg-dump-cron must pass the endpoint immutable-publication capability marker")
for label, content in (("scheduled backup script", scheduled_backup_script),):
    if (
        "pg_dump -Fp" not in content
        or ".sql.gz" not in content
        or 'gzip > "$PARTIAL_DUMP"' not in content
        or 'ln -- "$PARTIAL_DUMP" "$DUMP"' not in content
    ):
        raise SystemExit(
            f"{label} does not declare the hosted pg_dump -Fp -> gzip -> atomic no-clobber .sql.gz producer pair"
        )
    if "mv -f" in content:
        raise SystemExit(f"{label} must not replace a same-second artifact with mv -f")
    if "pg_restore" in content:
        raise SystemExit(f"{label} must not select pg_restore for the hosted plain-SQL artifact")
    for required in (
        "TS=$(date -u +%Y%m%d%H%M%S)",
        "HOUR=$(date -u +%H)",
        "DOW=$(date -u +%u)",
        "DOM=$(date -u +%d)",
        "ENDPOINT_IF_NONE_MATCH_CONFIRMED=${PG_DUMP_ENDPOINT_IF_NONE_MATCH_CONFIRMED:-false}",
        'Refusing custom pg_dump endpoint without PG_DUMP_ENDPOINT_IF_NONE_MATCH_CONFIRMED=true',
        "aws s3api put-object",
        '--if-none-match \'*\'',
        '--bucket "$BUCKET"',
        '--key "$key"',
        '--body "$DUMP"',
    ):
        if required not in content:
            raise SystemExit(f"{label} does not declare required immutable UTC S3 publication contract: {required}")
    if "aws s3 cp" in content:
        raise SystemExit(f"{label} must not use overwrite-capable aws s3 cp publication")
if 'gunzip -c "$FILE" | psql' not in scheduled_restore_script:
    raise SystemExit("hosted scheduled restore script does not consume .sql.gz with gunzip | psql")
if "--single-transaction" not in scheduled_restore_script:
    raise SystemExit("hosted scheduled restore script must keep the plain-SQL restore atomic")
if "pg_dump" not in local_backup_script or "-Fc" not in local_backup_script or "pg_restore" not in local_restore_script:
    raise SystemExit("local custom-format .dump/pg_restore lane is not preserved")
expected_verified_point_bytes = (
    b'{"backupArtifact":{"artifactDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",'
    b'"artifactIdentity":"snapshot-production-20260824T120000Z","artifactRef":"s3://firemud-production/backups/20260824T120000Z.sql.gz",'
    b'"lineageRef":"lineage/production-20260824T120000Z","snapshotAt":"2026-08-24T12:00:00Z"},"databaseIdentity":{"clusterIdentity":"production-postgres-cluster-20260824",'
    b'"databaseName":"firemud"},"environment":"production","schemaVersion":"verified-restorable-point/v1",'
    b'"verification":{"operationId":"verify-production-20260824T120500Z","restoreToolIdentity":{"digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",'
    b'"name":"psql","version":"16.4"},"verifiedAt":"2026-08-24T12:05:00Z"}}'
)
if module.canonical_verified_restorable_point_bytes(verified_point) != expected_verified_point_bytes:
    raise SystemExit("verified-point producer did not emit the canonical golden-vector bytes")
verified_point["recordDigest"] = "sha256:6c569a3c7276f3bce99746d07a258e960bf869f4f7baa35a8c4c576f7904e0b0"
verified_point_dir = tmp / module.VERIFIED_RESTORABLE_POINT_DIRECTORY
verified_point_dir.mkdir(parents=True)
verified_point_path = verified_point_dir / "20260824T120500Z.json"
verified_point_path.write_text(json.dumps(verified_point), encoding="utf-8")
point_status, point_message = module.validate_verified_restorable_point(
    verified_point,
    "production",
    "2026-08-24T12:00:00Z",
    "2026-08-24T12:05:00Z",
    verified_point["recordDigest"],
    verified_point["backupArtifact"]["artifactRef"],
)
if point_status != "pass":
    raise SystemExit(f"verified-point consumer rejected the golden vector: {point_message}")
per_point_verified = copy.deepcopy(verified_point)
per_point_verified["verification"]["verifiedAt"] = timestamp(now - module.dt.timedelta(hours=10))
per_point_verified["recordDigest"] = "sha256:" + hashlib.sha256(
    module.canonical_verified_restorable_point_bytes(per_point_verified)
).hexdigest()
per_point_verified_path = verified_point_dir / "per-point-verification.json"
per_point_verified_path.write_text(json.dumps(per_point_verified), encoding="utf-8")
per_point_status, per_point_message = module._validate_verified_restorable_point_reference(
    tmp,
    str(per_point_verified_path.relative_to(tmp)),
    "production",
    "2026-08-24T12:00:00Z",
    None,
    per_point_verified["recordDigest"],
    per_point_verified["backupArtifact"]["artifactRef"],
    context="Per-point verified-point",
    assessed_at=now,
)
if per_point_status != "pass":
    raise SystemExit(
        "verified-point consumer rejected an independently fresh per-point verification timestamp: "
        + per_point_message
    )
future_verified_point = copy.deepcopy(verified_point)
future_verified_point["verification"]["verifiedAt"] = "2026-08-24T12:05:01Z"
future_verified_point["recordDigest"] = "sha256:" + hashlib.sha256(
    module.canonical_verified_restorable_point_bytes(future_verified_point)
).hexdigest()
future_verified_status, future_verified_message = module.validate_verified_restorable_point(
    future_verified_point,
    "production",
    "2026-08-24T12:00:00Z",
    "2026-08-24T12:05:01Z",
    future_verified_point["recordDigest"],
    future_verified_point["backupArtifact"]["artifactRef"],
    assessed_at=module.dt.datetime.fromisoformat("2026-08-24T12:05:00+00:00"),
)
if (
    future_verified_status != "fail"
    or "verifiedAt must not be later than the assessment/evaluation time" not in future_verified_message
):
    raise SystemExit(
        "verified-point consumer accepted verification after assessment time: "
        + future_verified_message
    )
tampered_point = {
    **verified_point,
    "backupArtifact": {
        **verified_point["backupArtifact"],
        "artifactDigest": "sha256:" + "c" * 64,
    },
}
tampered_status, tampered_message = module.validate_verified_restorable_point(
    tampered_point,
    "production",
    "2026-08-24T12:00:00Z",
    "2026-08-24T12:05:00Z",
    verified_point["recordDigest"],
    verified_point["backupArtifact"]["artifactRef"],
)
if tampered_status != "fail" or "recordDigest" not in tampered_message:
    raise SystemExit(f"verified-point consumer accepted a tampered golden vector: {tampered_message}")
changed_time_point = copy.deepcopy(verified_point)
changed_time_point["verification"]["verifiedAt"] = "2026-08-24T12:06:00Z"
changed_time_status, changed_time_message = module.validate_verified_restorable_point(
    changed_time_point,
    "production",
    "2026-08-24T12:00:00Z",
    "2026-08-24T12:05:00Z",
    verified_point["recordDigest"],
    verified_point["backupArtifact"]["artifactRef"],
)
if changed_time_status != "fail" or "verifiedAt" not in changed_time_message:
    raise SystemExit(f"verified-point consumer accepted changed verification time: {changed_time_message}")
changed_snapshot_point = copy.deepcopy(verified_point)
changed_snapshot_point["backupArtifact"]["snapshotAt"] = "2026-08-24T12:01:00Z"
changed_snapshot_status, changed_snapshot_message = module.validate_verified_restorable_point(
    changed_snapshot_point,
    "production",
    "2026-08-24T12:00:00Z",
    "2026-08-24T12:05:00Z",
    verified_point["recordDigest"],
    verified_point["backupArtifact"]["artifactRef"],
)
if changed_snapshot_status != "fail" or "snapshotAt" not in changed_snapshot_message:
    raise SystemExit(f"verified-point consumer accepted changed snapshot time: {changed_snapshot_message}")
duplicate_point_path = verified_point_dir / "duplicate.json"
duplicate_point_path.write_text(
    '{"schemaVersion":"verified-restorable-point/v1","schemaVersion":"verified-restorable-point/v1"}',
    encoding="utf-8",
)
try:
    module.load_verified_restorable_point(duplicate_point_path)
except ValueError:
    pass
else:
    raise SystemExit("verified-point consumer accepted duplicate JSON members")

readiness_point = copy.deepcopy(verified_point)
readiness_point["backupArtifact"]["snapshotAt"] = past_timestamp
readiness_point["verification"]["verifiedAt"] = past_timestamp
readiness_point["recordDigest"] = "sha256:" + hashlib.sha256(
    module.canonical_verified_restorable_point_bytes(readiness_point)
).hexdigest()
readiness_point_path = verified_point_dir / "current.json"
readiness_point_path.write_text(json.dumps(readiness_point), encoding="utf-8")

def canonical_recovery_record(finalized_at):
    quarantine_started_at = finalized_at - module.dt.timedelta(minutes=25)
    restored_at = finalized_at - module.dt.timedelta(minutes=20)
    ready_to_reopen_at = finalized_at - module.dt.timedelta(minutes=15)
    quarantine_released_at = finalized_at - module.dt.timedelta(minutes=5)
    credential_validated_at = timestamp(restored_at)
    credential_records = {}
    for class_name in ("backup-storage", "asset-storage", "outbound-comms", "operator-credentials"):
        credential_records[class_name] = {
            "status": "pass",
            "evidenceRef": f"evidence/{class_name}.json",
            "isolationAssertion": "production-equivalent drill boundary",
            "validationMethod": "contract-test",
            "validatedAt": credential_validated_at,
            "validatedBy": "preflight-contract",
            "observedValue": "redacted",
        }
    return {
        "schemaVersion": "recovery-record/v1",
        "environment": "production",
        "recoveryRef": "baseline",
        "operationId": "recovery-operation",
        "recoveryStatus": "finalized",
        "recoveryPurpose": "production-equivalent-drill",
        "sourceEnvironmentBinding": {"environment": "production", "bindingRef": "production"},
        "targetBoundary": {"environment": "production", "boundary": "isolated-drill"},
        "trafficExposure": "isolated-drill",
        "restoreSource": {"type": "current-production-lineage", "artifactRef": "backup-artifact"},
        "restoreSafeMode": {"status": "pass", "playerIngress": "disabled"},
        "coordinationRecoveryMode": "cold_start_restore",
        "backupArtifactRef": "backups/artifact",
        "artifactErasureHighWater": {"stream": "erasures", "sequence": 10},
        "initialCatchupHighWater": {"stream": "erasures", "sequence": 11},
        "restoreHighWater": {"stream": "erasures", "sequence": 12},
        "erasureReplay": {
            "ledgerRef": "erasures",
            "exclusiveStart": 10,
            "initialCatchupThrough": 11,
            "inclusiveEnd": 12,
            "replayedThrough": 12,
            "gapFree": True,
        },
        "erasureOverlayReconciliation": {
            "stream": "erasures",
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 10},
            "initialCatchupHighWater": {"stream": "erasures", "sequence": 11},
            "restoreHighWater": {"stream": "erasures", "sequence": 12},
            "sequenceVerification": {
                "status": "pass",
                "exclusiveStart": 10,
                "inclusiveEnd": 11,
                "ordered": True,
                "contiguous": True,
                "complete": True,
                "gapFree": True,
                "duplicateFree": True,
            },
            "integrityVerification": {"status": "pass", "verified": True},
            "sequenceDispositions": [
                {
                    "stream": "erasures",
                    "sequence": 12,
                    "owner": "account-service",
                    "disposition": "invalidated",
                    "integrityVerified": True,
                },
            ],
        },
        "backupArtifactLineage": {
            "databaseIdentity": "production",
            "snapshotIdentity": "snapshot-production-1",
            "snapshotAt": credential_validated_at,
            "preSnapshotJournalHighWater": {
                "stream": "erasures",
                "sequence": 10,
                "observationId": "observation-1",
                "observedAt": timestamp(restored_at - module.dt.timedelta(minutes=1)),
                "observationDigest": "sha256:observation-1",
            },
            "preSnapshotJournalBoundaryWitness": {
                "observationId": "observation-1",
                "observationDigest": "sha256:observation-1",
                "snapshotIdentity": "snapshot-production-1",
                "snapshotOpenedAt": credential_validated_at,
                "evidenceRef": "evidence/pre-snapshot-journal-boundary.json",
            },
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 10},
            "erasureHighWaterSnapshotBound": True,
        },
        "backupToolDigest": "sha256:backup-tool",
        "recoveryToolDigest": "sha256:recovery-tool",
        "recoveryContractFingerprint": "sha256:recovery-contract",
        "recoveryParticipantInventoryRef": "inventories/participants.json",
        "validatorInventoryRef": "inventories/validators.json",
        "externalEffectInventoryRef": "inventories/external-effects.json",
        "quarantineStartedAt": timestamp(quarantine_started_at),
        "readyToReopenAt": timestamp(ready_to_reopen_at),
        "quarantineReleasedAt": timestamp(quarantine_released_at),
        "finalizedAt": timestamp(finalized_at),
        "restoredAt": timestamp(restored_at),
        "restoredBy": "preflight-contract",
        "recoveryControllerLineage": {
            "recoveryStatus": "finalized",
            "scope": "environment-wide",
            "finalizedReleaseIdentity": "release-1",
        },
        "expectedBindingsRef": "design/operations/environments/production/expected-bindings.yaml",
        "coordinationRecoveryEvidence": {
            "mode": "cold_start_restore",
            "coordinationRedis": "empty-before-rebuild",
            "credentialBinding": "rotated-or-rebound",
            "targetEnvironmentBound": True,
            "snapshotCredentialsRejected": True,
            "regionEpochFences": "advanced-or-recreated",
            "accountAuthorityProjections": "rebuilt-and-verified",
            "accountAuthorityProjectionEvidenceRef": "evidence/account-authority-projections.json",
            "replayAdmissionFence": "advanced",
            "replayQuarantine": "lifetime-plus-skew-observed",
            "replayConsumeEvidenceRef": "evidence/replay-consume.json",
        },
        "backupConfidentialityEvidence": {"status": "pass", "transport": "encrypted", "storage": "encrypted"},
        "durableParticipantConvergence": {"gameplay": {"disposition": "converged"}},
        "externalEffectReconciliation": {"mail": {"disposition": "invalidated"}},
        "sessionRecovery": {"gameSessionHandling": "invalidated", "authSessionHandling": "invalidated"},
        "credentialApplicability": {
            class_name: "applicable"
            for class_name in (
                "jwt-signing-keys-jwks",
                "postgres-application-credentials",
                "workload-leaf",
                "bridge-leaf",
                "operator-leaf",
                "backup-storage",
                "asset-storage",
                "outbound-comms",
                "operator-credentials",
            )
        },
        "credentialDispositions": {
            "jwt-signing-keys-jwks": "rotated",
            "postgres-application-credentials": "rebound",
            "workload-leaf": "reissued",
            "bridge-leaf": "reissued",
            "operator-leaf": "verified_not_restored",
            "backup-storage": "rebound",
            "asset-storage": "verified_not_restored",
            "outbound-comms": "rotated",
            "operator-credentials": "reissued",
        },
        "jwtHardening": {
            "rotationJobRef": "jobs/jwt-rotation",
            "resultingKeyIds": ["kid-1"],
            "revocationWatermarkEvidence": "evidence/jwt-revocation",
            "validatorConvergenceEvidence": "evidence/jwt-validators",
            "compromiseClassified": False,
            "oldOrRestoredKeyIds": ["kid-old"],
            "replacementEvidence": [{
                "oldKid": "kid-old",
                "candidateKid": "kid-1",
                "oldKidRejected": True,
                "candidateKidAccepted": True,
                "validatorEvidenceRef": "evidence/jwt-validators",
            }],
        },
        "databaseCredentialRotation": {
            "rotationJobRef": "jobs/postgres-rotation",
            "affectedSecretRefs": ["secret://firemud/postgres"],
            "rolloutRestartEvidence": "evidence/postgres-rollout",
        },
        "certificateReissuance": {
            "workloadLeafEvidence": "evidence/workload-certificates",
            "bridgeLeafEvidence": "evidence/bridge-certificates",
            "operatorLeafEvidence": "evidence/operator-certificates",
            "peerConvergenceEvidence": "evidence/certificate-peers",
        },
        "externalCredentialValidation": {"records": credential_records},
        "secretComplianceRefresh": {
            "recordRef": "design/operations/secret-compliance/production.yaml",
            "evidenceRef": "evidence/secret-compliance",
            "credentialClasses": [
                "jwt-signing-keys-jwks",
                "postgres-application-credentials",
                "backup-storage",
                "asset-storage",
                "outbound-comms",
                "operator-credentials",
            ],
            "freshness": {
                "jwt-signing-keys-jwks": {
                    "lineage": "existing",
                    "field": "lastRotationAt",
                    "value": freshness_timestamp,
                    "previousField": "lastProvisionedAt",
                    "previousValue": "2026-03-01T00:00:00Z",
                },
                "postgres-application-credentials": {
                    "lineage": "existing",
                    "field": "lastRotationAt",
                    "value": freshness_timestamp,
                    "previousField": "lastRotationAt",
                    "previousValue": freshness_timestamp,
                },
                "backup-storage": {
                    "lineage": "existing",
                    "field": "lastRotationAt",
                    "value": freshness_timestamp,
                    "previousField": "lastRotationAt",
                    "previousValue": freshness_timestamp,
                },
                "asset-storage": {
                    "lineage": "existing",
                    "field": "lastRotationAt",
                    "value": freshness_timestamp,
                    "previousField": "lastRotationAt",
                    "previousValue": freshness_timestamp,
                },
                "outbound-comms": {
                    "lineage": "existing",
                    "field": "lastRotationAt",
                    "value": freshness_timestamp,
                    "previousField": "lastRotationAt",
                    "previousValue": "2026-03-01T00:00:00Z",
                },
                "operator-credentials": {
                    "lineage": "existing",
                    "field": "lastRotationAt",
                    "value": freshness_timestamp,
                    "previousField": "lastRotationAt",
                    "previousValue": "2026-03-01T00:00:00Z",
                },
            },
        },
        "smokeStatus": "pass",
        "smokeEvidence": [recovery_smoke_entry],
        "evidenceRefs": ["evidence/recovery-baseline.json"],
        "reopenApprovedBy": "preflight-contract",
    }

def compatibility_result(status):
    return {
        "baselineRecoveryRecordRef": "design/operations/deployments/production/recovery/baseline.json",
        "baselineRecoveryContractFingerprint": "sha256:recovery-contract",
        "candidateRecoveryContractFingerprint": "sha256:recovery-contract",
        "changedDimensions": [],
        "compatibilityStatus": status,
        "compatibilityRationale": "contract test",
        "evaluatedAt": past_timestamp,
        "evaluatorToolDigest": "sha256:evaluator",
        "newDrillRequired": False,
        "newestVerifiedRestorablePointRef": str(readiness_point_path.relative_to(tmp)),
        "newestVerifiedRestorablePointDigest": readiness_point["recordDigest"],
        "newestVerifiedRestorablePointAt": past_timestamp,
    }

stub_baseline = {
    "environment": "production",
    "recoveryStatus": "finalized",
    "recoveryPurpose": "production-equivalent-drill",
    "trafficExposure": "isolated-drill",
    "coordinationRecoveryMode": "cold_start_restore",
    "recoveryContractFingerprint": "sha256:recovery-contract",
    "finalizedAt": timestamp(now - module.dt.timedelta(minutes=10)),
}
stub_path = recovery_dir / "stub-baseline.json"
stub_path.write_text(json.dumps(stub_baseline), encoding="utf-8")
stub_status, stub_message = module.validate_recovery_baseline(
    tmp,
    str(stub_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if stub_status != "fail" or "canonical finalized projection fields" not in stub_message:
    raise SystemExit(f"seven-field recovery baseline was accepted: {stub_message}")

valid_baseline = canonical_recovery_record(now - module.dt.timedelta(minutes=10))
malformed_recovery_smoke_path = recovery_dir / "malformed-smoke-baseline.json"
malformed_recovery_smoke_path.write_text(
    json.dumps({**valid_baseline, "smokeEvidence": [smoke_evidence_ref]}),
    encoding="utf-8",
)
malformed_recovery_smoke_status, malformed_recovery_smoke_message = module.validate_recovery_baseline(
    tmp,
    str(malformed_recovery_smoke_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if (
    malformed_recovery_smoke_status != "fail"
    or "exactly ref and contentDigest" not in malformed_recovery_smoke_message
):
    raise SystemExit(
        "string recovery smoke evidence was accepted: " + malformed_recovery_smoke_message
    )

wrong_recovery_digest_path = recovery_dir / "wrong-digest-smoke-baseline.json"
wrong_recovery_digest_path.write_text(
    json.dumps(
        {
            **valid_baseline,
            "smokeEvidence": [
                {**recovery_smoke_entry, "contentDigest": "sha256:" + "0" * 64}
            ],
        }
    ),
    encoding="utf-8",
)
wrong_recovery_digest_status, wrong_recovery_digest_message = module.validate_recovery_baseline(
    tmp,
    str(wrong_recovery_digest_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if (
    wrong_recovery_digest_status != "fail"
    or "contentDigest does not match" not in wrong_recovery_digest_message
):
    raise SystemExit(
        "recovery smoke evidence digest mismatch was accepted: " + wrong_recovery_digest_message
    )

baseline_path = recovery_dir / "baseline.json"
baseline_path.write_text(json.dumps(valid_baseline), encoding="utf-8")
baseline_status, baseline_message = module.validate_recovery_baseline(
    tmp,
    str(baseline_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if baseline_status != "pass":
    raise SystemExit(f"valid recovery baseline did not pass: {baseline_message}")

original_load_yaml = module.load_yaml
try:
    for binding_load_error in (
        UnicodeError("invalid expected-bindings text"),
        yaml.YAMLError("malformed expected-bindings YAML"),
    ):
        def raise_binding_load_error(path, error=binding_load_error):
            raise error

        module.load_yaml = raise_binding_load_error
        binding_error_status, binding_error_message = module.validate_recovery_baseline(
            tmp,
            str(baseline_path.relative_to(tmp)),
            "sha256:recovery-contract",
            now,
            now,
        )
        if (
            binding_error_status != "fail"
            or "production queryability binding could not be loaded" not in binding_error_message
        ):
            raise SystemExit(
                "recovery baseline binding read error did not fail closed: "
                + binding_error_message
            )
finally:
    module.load_yaml = original_load_yaml

if set(valid_baseline["secretComplianceRefresh"]["credentialClasses"]) != {
    "jwt-signing-keys-jwks",
    "postgres-application-credentials",
    "backup-storage",
    "asset-storage",
    "outbound-comms",
    "operator-credentials",
}:
    raise SystemExit(
        "valid recovery baseline did not use the exact secret-compliance projection class set"
    )

if set(module.canonical_recovery_allowed_not_applicable_classes("production")) != {
    "asset-storage",
    "outbound-comms",
}:
    raise SystemExit("production recovery applicability profile allowed the wrong not-applicable classes")
for non_production_environment in ("staging", "hobby-self-hosted"):
    if set(module.canonical_recovery_allowed_not_applicable_classes(non_production_environment)) != {
        "backup-storage",
        "asset-storage",
        "outbound-comms",
        "operator-credentials",
    }:
        raise SystemExit(
            f"{non_production_environment} recovery applicability profile did not allow absent external bindings"
        )

legacy_refresh_class_names = copy.deepcopy(valid_baseline)
legacy_refresh_class_names["secretComplianceRefresh"]["credentialClasses"] = [
    "backupStorage",
    "assetStorage",
]
legacy_refresh_class_names["secretComplianceRefresh"]["freshness"] = {
    "backupStorage": legacy_refresh_class_names["secretComplianceRefresh"]["freshness"].pop(
        "backup-storage"
    ),
    "assetStorage": legacy_refresh_class_names["secretComplianceRefresh"]["freshness"].pop(
        "asset-storage"
    ),
}
legacy_refresh_class_names_path = recovery_dir / "legacy-refresh-class-names-baseline.json"
legacy_refresh_class_names_path.write_text(json.dumps(legacy_refresh_class_names), encoding="utf-8")
legacy_refresh_class_names_status, legacy_refresh_class_names_message = module.validate_recovery_baseline(
    tmp,
    str(legacy_refresh_class_names_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if (
    legacy_refresh_class_names_status != "fail"
    or "credentialClasses must exactly cover applicable secret-compliance projection classes" not in legacy_refresh_class_names_message
):
    raise SystemExit(
        "legacy refresh class aliases were accepted: " + legacy_refresh_class_names_message
    )

not_applicable_internal_credential = copy.deepcopy(valid_baseline)
not_applicable_internal_credential["credentialApplicability"]["jwt-signing-keys-jwks"] = "not_applicable"
del not_applicable_internal_credential["credentialDispositions"]["jwt-signing-keys-jwks"]
not_applicable_internal_credential_path = recovery_dir / "not-applicable-internal-credential-baseline.json"
not_applicable_internal_credential_path.write_text(
    json.dumps(not_applicable_internal_credential),
    encoding="utf-8",
)
not_applicable_internal_credential_status, not_applicable_internal_credential_message = (
    module.validate_recovery_baseline(
        tmp,
        str(not_applicable_internal_credential_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
)
if (
    not_applicable_internal_credential_status != "fail"
    or "required credential classes must be applicable" not in not_applicable_internal_credential_message
    or "jwt-signing-keys-jwks" not in not_applicable_internal_credential_message
):
    raise SystemExit(
        "not-applicable internal credential class was accepted: "
        + not_applicable_internal_credential_message
    )

not_applicable_backup_storage = copy.deepcopy(valid_baseline)
not_applicable_backup_storage["credentialApplicability"]["backup-storage"] = "not_applicable"
del not_applicable_backup_storage["credentialDispositions"]["backup-storage"]
not_applicable_backup_storage["secretComplianceRefresh"]["credentialClasses"].remove("backup-storage")
del not_applicable_backup_storage["secretComplianceRefresh"]["freshness"]["backup-storage"]
not_applicable_backup_storage["externalCredentialValidation"]["records"]["backup-storage"] = {
    "status": "not_applicable",
    "reason": "credential-class-not-present",
    "evidenceRef": "evidence/backup-storage-not-applicable.json",
}
not_applicable_backup_storage_path = recovery_dir / "not-applicable-backup-storage-baseline.json"
not_applicable_backup_storage_path.write_text(
    json.dumps(not_applicable_backup_storage),
    encoding="utf-8",
)
not_applicable_backup_storage_status, not_applicable_backup_storage_message = (
    module.validate_recovery_baseline(
        tmp,
        str(not_applicable_backup_storage_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
)
if (
    not_applicable_backup_storage_status != "fail"
    or "required credential classes must be applicable" not in not_applicable_backup_storage_message
    or "backup-storage" not in not_applicable_backup_storage_message
):
    raise SystemExit(
        "not-applicable production backup-storage was accepted: "
        + not_applicable_backup_storage_message
    )

not_applicable_operator_credentials = copy.deepcopy(valid_baseline)
not_applicable_operator_credentials["credentialApplicability"]["operator-credentials"] = "not_applicable"
del not_applicable_operator_credentials["credentialDispositions"]["operator-credentials"]
not_applicable_operator_credentials["externalCredentialValidation"]["records"]["operator-credentials"] = {
    "status": "not_applicable",
    "reason": "credential-class-not-present",
    "evidenceRef": "evidence/operator-credentials-not-applicable.json",
}
not_applicable_operator_credentials_path = recovery_dir / "not-applicable-operator-credentials-baseline.json"
not_applicable_operator_credentials_path.write_text(
    json.dumps(not_applicable_operator_credentials),
    encoding="utf-8",
)
not_applicable_operator_credentials_status, not_applicable_operator_credentials_message = (
    module.validate_recovery_baseline(
        tmp,
        str(not_applicable_operator_credentials_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
)
if (
    not_applicable_operator_credentials_status != "fail"
    or "required credential classes must be applicable" not in not_applicable_operator_credentials_message
    or "operator-credentials" not in not_applicable_operator_credentials_message
):
    raise SystemExit(
        "not-applicable production operator-credentials was accepted: "
        + not_applicable_operator_credentials_message
    )

missing_recovery_evidence_refs = copy.deepcopy(valid_baseline)
del missing_recovery_evidence_refs["evidenceRefs"]
missing_recovery_evidence_refs_path = recovery_dir / "missing-recovery-evidence-refs-baseline.json"
missing_recovery_evidence_refs_path.write_text(
    json.dumps(missing_recovery_evidence_refs),
    encoding="utf-8",
)
missing_recovery_evidence_refs_status, missing_recovery_evidence_refs_message = module.validate_recovery_baseline(
    tmp,
    str(missing_recovery_evidence_refs_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if missing_recovery_evidence_refs_status != "fail" or "evidenceRefs" not in missing_recovery_evidence_refs_message:
    raise SystemExit(
        "missing recovery evidenceRefs did not fail closed: "
        + missing_recovery_evidence_refs_message
    )

empty_recovery_evidence_refs = copy.deepcopy(valid_baseline)
empty_recovery_evidence_refs["evidenceRefs"] = []
empty_recovery_evidence_refs_path = recovery_dir / "empty-recovery-evidence-refs-baseline.json"
empty_recovery_evidence_refs_path.write_text(
    json.dumps(empty_recovery_evidence_refs),
    encoding="utf-8",
)
empty_recovery_evidence_refs_status, empty_recovery_evidence_refs_message = module.validate_recovery_baseline(
    tmp,
    str(empty_recovery_evidence_refs_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if empty_recovery_evidence_refs_status != "fail" or "evidenceRefs" not in empty_recovery_evidence_refs_message:
    raise SystemExit(
        "empty recovery evidenceRefs did not fail closed: "
        + empty_recovery_evidence_refs_message
    )

malformed_recovery_evidence_refs = copy.deepcopy(valid_baseline)
malformed_recovery_evidence_refs["evidenceRefs"] = ["evidence/recovery-baseline.json", 7]
malformed_recovery_evidence_refs_path = recovery_dir / "malformed-recovery-evidence-refs-baseline.json"
malformed_recovery_evidence_refs_path.write_text(
    json.dumps(malformed_recovery_evidence_refs),
    encoding="utf-8",
)
malformed_recovery_evidence_refs_status, malformed_recovery_evidence_refs_message = module.validate_recovery_baseline(
    tmp,
    str(malformed_recovery_evidence_refs_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if (
    malformed_recovery_evidence_refs_status != "fail"
    or "evidenceRefs must contain only non-empty strings" not in malformed_recovery_evidence_refs_message
):
    raise SystemExit(
        "malformed recovery evidenceRefs did not fail closed: "
        + malformed_recovery_evidence_refs_message
    )

object_recovery_evidence_refs = copy.deepcopy(valid_baseline)
object_recovery_evidence_refs["evidenceRefs"] = {"baseline": "evidence/recovery-baseline.json"}
object_recovery_evidence_refs_path = recovery_dir / "object-recovery-evidence-refs-baseline.json"
object_recovery_evidence_refs_path.write_text(
    json.dumps(object_recovery_evidence_refs),
    encoding="utf-8",
)
object_recovery_evidence_refs_status, object_recovery_evidence_refs_message = module.validate_recovery_baseline(
    tmp,
    str(object_recovery_evidence_refs_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if (
    object_recovery_evidence_refs_status != "fail"
    or "evidenceRefs must be a non-empty list" not in object_recovery_evidence_refs_message
):
    raise SystemExit(
        "object-shaped recovery evidenceRefs did not fail closed: "
        + object_recovery_evidence_refs_message
    )

missing_applicable_credential_disposition = copy.deepcopy(valid_baseline)
del missing_applicable_credential_disposition["credentialDispositions"]["operator-credentials"]
missing_applicable_credential_disposition_path = recovery_dir / "missing-applicable-credential-disposition-baseline.json"
missing_applicable_credential_disposition_path.write_text(
    json.dumps(missing_applicable_credential_disposition),
    encoding="utf-8",
)
missing_applicable_credential_disposition_status, missing_applicable_credential_disposition_message = (
    module.validate_recovery_baseline(
        tmp,
        str(missing_applicable_credential_disposition_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
)
if (
    missing_applicable_credential_disposition_status != "fail"
    or "missing: operator-credentials" not in missing_applicable_credential_disposition_message
):
    raise SystemExit(
        "applicable credential class without a disposition did not fail closed: "
        + missing_applicable_credential_disposition_message
    )

not_applicable_external_credential = copy.deepcopy(valid_baseline)
not_applicable_external_credential["credentialApplicability"]["asset-storage"] = "not_applicable"
del not_applicable_external_credential["credentialDispositions"]["asset-storage"]
not_applicable_external_credential["secretComplianceRefresh"]["credentialClasses"].remove("asset-storage")
del not_applicable_external_credential["secretComplianceRefresh"]["freshness"]["asset-storage"]
not_applicable_external_credential["externalCredentialValidation"]["records"]["asset-storage"] = {
    "status": "not_applicable",
    "reason": "credential-class-not-present",
    "evidenceRef": "evidence/asset-storage-not-applicable.json",
}
not_applicable_external_credential_path = recovery_dir / "not-applicable-external-credential-baseline.json"
not_applicable_external_credential_path.write_text(
    json.dumps(not_applicable_external_credential),
    encoding="utf-8",
)
not_applicable_external_credential_status, not_applicable_external_credential_message = (
    module.validate_recovery_baseline(
        tmp,
        str(not_applicable_external_credential_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
)
if not_applicable_external_credential_status != "pass":
    raise SystemExit(
        "not-applicable external credential class did not pass with explicit evidence shape: "
        + not_applicable_external_credential_message
    )

not_applicable_outbound_credential = copy.deepcopy(valid_baseline)
not_applicable_outbound_credential["credentialApplicability"]["outbound-comms"] = "not_applicable"
del not_applicable_outbound_credential["credentialDispositions"]["outbound-comms"]
not_applicable_outbound_credential["secretComplianceRefresh"]["credentialClasses"].remove(
    "outbound-comms"
)
del not_applicable_outbound_credential["secretComplianceRefresh"]["freshness"]["outbound-comms"]
not_applicable_outbound_credential["externalCredentialValidation"]["records"]["outbound-comms"] = {
    "status": "not_applicable",
    "reason": "credential-class-not-present",
    "evidenceRef": "evidence/outbound-comms-not-applicable.json",
}
not_applicable_outbound_path = recovery_dir / "not-applicable-outbound-credential-baseline.json"
not_applicable_outbound_path.write_text(
    json.dumps(not_applicable_outbound_credential),
    encoding="utf-8",
)
not_applicable_outbound_status, not_applicable_outbound_message = module.validate_recovery_baseline(
    tmp,
    str(not_applicable_outbound_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if not_applicable_outbound_status != "pass":
    raise SystemExit(
        "not-applicable outbound credential class did not pass with explicit evidence shape: "
        + not_applicable_outbound_message
    )

non_applicable_external_disposition = copy.deepcopy(not_applicable_external_credential)
non_applicable_external_disposition["credentialDispositions"]["asset-storage"] = "verified_not_restored"
non_applicable_external_disposition_path = recovery_dir / "non-applicable-external-disposition-baseline.json"
non_applicable_external_disposition_path.write_text(
    json.dumps(non_applicable_external_disposition),
    encoding="utf-8",
)
non_applicable_external_disposition_status, non_applicable_external_disposition_message = (
    module.validate_recovery_baseline(
        tmp,
        str(non_applicable_external_disposition_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
)
if (
    non_applicable_external_disposition_status != "fail"
    or "extra: asset-storage" not in non_applicable_external_disposition_message
):
    raise SystemExit(
        "non-applicable external credential disposition was accepted: "
        + non_applicable_external_disposition_message
    )

non_applicable_external_pass_evidence = copy.deepcopy(not_applicable_external_credential)
non_applicable_external_pass_evidence["externalCredentialValidation"]["records"]["asset-storage"] = copy.deepcopy(
    valid_baseline["externalCredentialValidation"]["records"]["asset-storage"]
)
non_applicable_external_pass_evidence_path = recovery_dir / "non-applicable-external-pass-evidence-baseline.json"
non_applicable_external_pass_evidence_path.write_text(
    json.dumps(non_applicable_external_pass_evidence),
    encoding="utf-8",
)
non_applicable_external_pass_evidence_status, non_applicable_external_pass_evidence_message = (
    module.validate_recovery_baseline(
        tmp,
        str(non_applicable_external_pass_evidence_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
)
if (
    non_applicable_external_pass_evidence_status != "fail"
    or "must be not_applicable for non-applicable class: asset-storage" not in non_applicable_external_pass_evidence_message
):
    raise SystemExit(
        "non-applicable external credential pass evidence was accepted: "
        + non_applicable_external_pass_evidence_message
    )

missing_credential_applicability = copy.deepcopy(valid_baseline)
del missing_credential_applicability["credentialApplicability"]["operator-leaf"]
missing_credential_applicability_path = recovery_dir / "missing-credential-applicability-baseline.json"
missing_credential_applicability_path.write_text(
    json.dumps(missing_credential_applicability),
    encoding="utf-8",
)
missing_credential_applicability_status, missing_credential_applicability_message = module.validate_recovery_baseline(
    tmp,
    str(missing_credential_applicability_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if (
    missing_credential_applicability_status != "fail"
    or "credentialApplicability keys must exactly cover" not in missing_credential_applicability_message
    or "missing: operator-leaf" not in missing_credential_applicability_message
):
    raise SystemExit(
        "missing credential applicability did not fail closed: "
        + missing_credential_applicability_message
    )

unknown_credential_applicability = copy.deepcopy(valid_baseline)
unknown_credential_applicability["credentialApplicability"]["unknown-credential"] = "applicable"
unknown_credential_applicability_path = recovery_dir / "unknown-credential-applicability-baseline.json"
unknown_credential_applicability_path.write_text(
    json.dumps(unknown_credential_applicability),
    encoding="utf-8",
)
unknown_credential_applicability_status, unknown_credential_applicability_message = module.validate_recovery_baseline(
    tmp,
    str(unknown_credential_applicability_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if (
    unknown_credential_applicability_status != "fail"
    or "extra: unknown-credential" not in unknown_credential_applicability_message
):
    raise SystemExit(
        "unknown credential applicability class did not fail closed: "
        + unknown_credential_applicability_message
    )

malformed_credential_applicability = copy.deepcopy(valid_baseline)
malformed_credential_applicability["credentialApplicability"]["operator-leaf"] = "unknown"
malformed_credential_applicability_path = recovery_dir / "malformed-credential-applicability-baseline.json"
malformed_credential_applicability_path.write_text(
    json.dumps(malformed_credential_applicability),
    encoding="utf-8",
)
malformed_credential_applicability_status, malformed_credential_applicability_message = module.validate_recovery_baseline(
    tmp,
    str(malformed_credential_applicability_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if (
    malformed_credential_applicability_status != "fail"
    or "unknown or malformed value for: operator-leaf" not in malformed_credential_applicability_message
):
    raise SystemExit(
        "malformed credential applicability value did not fail closed: "
        + malformed_credential_applicability_message
    )

missing_credential_dispositions = copy.deepcopy(valid_baseline)
del missing_credential_dispositions["credentialDispositions"]
missing_credential_dispositions_path = recovery_dir / "missing-credential-dispositions-baseline.json"
missing_credential_dispositions_path.write_text(
    json.dumps(missing_credential_dispositions),
    encoding="utf-8",
)
missing_credential_dispositions_status, missing_credential_dispositions_message = module.validate_recovery_baseline(
    tmp,
    str(missing_credential_dispositions_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if (
    missing_credential_dispositions_status != "fail"
    or "credentialDispositions" not in missing_credential_dispositions_message
):
    raise SystemExit(
        "missing credentialDispositions did not fail closed: "
        + missing_credential_dispositions_message
    )

missing_credential_class = copy.deepcopy(valid_baseline)
del missing_credential_class["credentialDispositions"]["operator-leaf"]
missing_credential_class_path = recovery_dir / "missing-credential-class-baseline.json"
missing_credential_class_path.write_text(json.dumps(missing_credential_class), encoding="utf-8")
missing_credential_class_status, missing_credential_class_message = module.validate_recovery_baseline(
    tmp,
    str(missing_credential_class_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if missing_credential_class_status != "fail" or "missing: operator-leaf" not in missing_credential_class_message:
    raise SystemExit(
        "missing fixed credential disposition class did not fail closed: "
        + missing_credential_class_message
    )

extra_credential_class = copy.deepcopy(valid_baseline)
extra_credential_class["credentialDispositions"]["unknown-credential"] = "rotated"
extra_credential_class_path = recovery_dir / "extra-credential-class-baseline.json"
extra_credential_class_path.write_text(json.dumps(extra_credential_class), encoding="utf-8")
extra_credential_class_status, extra_credential_class_message = module.validate_recovery_baseline(
    tmp,
    str(extra_credential_class_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if extra_credential_class_status != "fail" or "extra: unknown-credential" not in extra_credential_class_message:
    raise SystemExit(
        "extra credential disposition class did not fail closed: "
        + extra_credential_class_message
    )

unknown_credential_disposition = copy.deepcopy(valid_baseline)
unknown_credential_disposition["credentialDispositions"]["jwt-signing-keys-jwks"] = "unknown"
unknown_credential_disposition_path = recovery_dir / "unknown-credential-disposition-baseline.json"
unknown_credential_disposition_path.write_text(
    json.dumps(unknown_credential_disposition),
    encoding="utf-8",
)
unknown_credential_disposition_status, unknown_credential_disposition_message = module.validate_recovery_baseline(
    tmp,
    str(unknown_credential_disposition_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if unknown_credential_disposition_status != "fail" or "unknown or malformed disposition" not in (
    unknown_credential_disposition_message
):
    raise SystemExit(
        "unknown credential disposition did not fail closed: "
        + unknown_credential_disposition_message
    )

unhashable_credential_disposition = copy.deepcopy(valid_baseline)
unhashable_credential_disposition["credentialDispositions"]["jwt-signing-keys-jwks"] = ["rotated"]
unhashable_credential_disposition_path = recovery_dir / "unhashable-credential-disposition-baseline.json"
unhashable_credential_disposition_path.write_text(
    json.dumps(unhashable_credential_disposition),
    encoding="utf-8",
)
unhashable_credential_disposition_status, unhashable_credential_disposition_message = (
    module.validate_recovery_baseline(
        tmp,
        str(unhashable_credential_disposition_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
)
if unhashable_credential_disposition_status != "fail" or "unknown or malformed disposition" not in (
    unhashable_credential_disposition_message
):
    raise SystemExit(
        "unhashable credential disposition did not fail closed: "
        + unhashable_credential_disposition_message
    )

impossible_credential_dispositions = copy.deepcopy(valid_baseline)
impossible_credential_dispositions["credentialDispositions"] = ["not-an-object"]
impossible_credential_dispositions_path = recovery_dir / "impossible-credential-dispositions-baseline.json"
impossible_credential_dispositions_path.write_text(
    json.dumps(impossible_credential_dispositions),
    encoding="utf-8",
)
impossible_credential_dispositions_status, impossible_credential_dispositions_message = module.validate_recovery_baseline(
    tmp,
    str(impossible_credential_dispositions_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if impossible_credential_dispositions_status != "fail" or "must be an object" not in (
    impossible_credential_dispositions_message
):
    raise SystemExit(
        "impossible credentialDispositions shape did not fail closed: "
        + impossible_credential_dispositions_message
    )

duplicate_credential_dispositions_path = recovery_dir / "duplicate-credential-disposition-baseline.json"
duplicate_credential_dispositions_json = json.dumps(valid_baseline).replace(
    '"credentialDispositions": {',
    '"credentialDispositions": {"jwt-signing-keys-jwks": "rotated",',
    1,
)
duplicate_credential_dispositions_path.write_text(
    duplicate_credential_dispositions_json,
    encoding="utf-8",
)
duplicate_credential_dispositions_status, duplicate_credential_dispositions_message = module.validate_recovery_baseline(
    tmp,
    str(duplicate_credential_dispositions_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if duplicate_credential_dispositions_status != "fail" or "duplicate JSON member" not in (
    duplicate_credential_dispositions_message
):
    raise SystemExit(
        "duplicate credential disposition member did not fail closed: "
        + duplicate_credential_dispositions_message
    )

def validate_recovery_variant(name, data):
    path = recovery_dir / name
    path.write_text(json.dumps(data), encoding="utf-8")
    return module.validate_recovery_baseline(
        tmp,
        str(path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )

certificate_class_in_refresh = copy.deepcopy(valid_baseline)
certificate_class_in_refresh["secretComplianceRefresh"]["credentialClasses"].append(
    "workload-leaf"
)
certificate_class_in_refresh_status, certificate_class_in_refresh_message = validate_recovery_variant(
    "certificate-class-in-refresh-baseline.json",
    certificate_class_in_refresh,
)
if (
    certificate_class_in_refresh_status != "fail"
    or "extra: workload-leaf" not in certificate_class_in_refresh_message
):
    raise SystemExit(
        "certificate class was accepted in the secret-compliance refresh projection: "
        + certificate_class_in_refresh_message
    )

missing_jwt_compromise_classification = copy.deepcopy(valid_baseline)
del missing_jwt_compromise_classification["jwtHardening"]["compromiseClassified"]
missing_jwt_compromise_status, missing_jwt_compromise_message = validate_recovery_variant(
    "missing-jwt-compromise-classification-baseline.json",
    missing_jwt_compromise_classification,
)
if missing_jwt_compromise_status != "fail" or "compromiseClassified" not in missing_jwt_compromise_message:
    raise SystemExit(
        "JWT hardening without an explicit compromise classification was accepted: "
        + missing_jwt_compromise_message
    )

ordinary_jwt_compromise_fields = copy.deepcopy(valid_baseline)
ordinary_jwt_compromise_fields["jwtHardening"]["compromisedKid"] = "kid-compromised"
ordinary_jwt_compromise_status, ordinary_jwt_compromise_message = validate_recovery_variant(
    "ordinary-jwt-compromise-fields-baseline.json",
    ordinary_jwt_compromise_fields,
)
if (
    ordinary_jwt_compromise_status != "fail"
    or "ordinary JWT hardening must not include compromise identity fields" not in ordinary_jwt_compromise_message
):
    raise SystemExit(
        "ordinary JWT replacement with compromise identity evidence was accepted: "
        + ordinary_jwt_compromise_message
    )

missing_jwt_compromise_evidence = copy.deepcopy(valid_baseline)
missing_jwt_compromise_evidence["jwtHardening"]["compromiseClassified"] = True
missing_jwt_compromise_status, missing_jwt_compromise_message = validate_recovery_variant(
    "missing-jwt-compromise-evidence-baseline.json",
    missing_jwt_compromise_evidence,
)
if (
    missing_jwt_compromise_status != "fail"
    or "compromise-classified JWT hardening missing fields" not in missing_jwt_compromise_message
):
    raise SystemExit(
        "compromise-classified JWT hardening without identity evidence was accepted: "
        + missing_jwt_compromise_message
    )

valid_jwt_compromise = copy.deepcopy(valid_baseline)
valid_jwt_compromise["jwtHardening"].update(
    {
        "compromiseClassified": True,
        "resultingKeyIds": ["kid-candidate"],
        "compromisedKid": "kid-compromised",
        "candidateKid": "kid-candidate",
        "compromisedPublicKeyFingerprint": "sha256:" + "a" * 64,
        "candidatePublicKeyFingerprint": "sha256:" + "b" * 64,
    }
)
del valid_jwt_compromise["jwtHardening"]["replacementEvidence"]
del valid_jwt_compromise["jwtHardening"]["oldOrRestoredKeyIds"]
valid_jwt_compromise_status, valid_jwt_compromise_message = validate_recovery_variant(
    "valid-jwt-compromise-baseline.json",
    valid_jwt_compromise,
)
if valid_jwt_compromise_status != "pass":
    raise SystemExit(
        "complete compromise-classified JWT hardening did not pass: "
        + valid_jwt_compromise_message
    )

duplicate_jwt_compromise_identity = copy.deepcopy(valid_jwt_compromise)
duplicate_jwt_compromise_identity["jwtHardening"]["candidateKid"] = "kid-compromised"
duplicate_jwt_compromise_status, duplicate_jwt_compromise_message = validate_recovery_variant(
    "duplicate-jwt-compromise-identity-baseline.json",
    duplicate_jwt_compromise_identity,
)
if (
    duplicate_jwt_compromise_status != "fail"
    or "requires distinct compromisedKid and candidateKid" not in duplicate_jwt_compromise_message
):
    raise SystemExit(
        "compromise-classified JWT hardening with duplicate key identities was accepted: "
        + duplicate_jwt_compromise_message
    )

malformed_jwt_compromise_fingerprint = copy.deepcopy(valid_jwt_compromise)
malformed_jwt_compromise_fingerprint["jwtHardening"]["candidatePublicKeyFingerprint"] = (
    "SHA256:" + "b" * 64
)
malformed_jwt_compromise_status, malformed_jwt_compromise_message = validate_recovery_variant(
    "malformed-jwt-compromise-fingerprint-baseline.json",
    malformed_jwt_compromise_fingerprint,
)
if (
    malformed_jwt_compromise_status != "fail"
    or "fingerprints must use lowercase sha256:<64 hex>" not in malformed_jwt_compromise_message
):
    raise SystemExit(
        "malformed compromise public-key fingerprint was accepted: "
        + malformed_jwt_compromise_message
    )

mismatched_jwt_compromise_resulting_key = copy.deepcopy(valid_jwt_compromise)
mismatched_jwt_compromise_resulting_key["jwtHardening"]["resultingKeyIds"] = ["kid-other"]
mismatched_jwt_compromise_status, mismatched_jwt_compromise_message = validate_recovery_variant(
    "mismatched-jwt-compromise-resulting-key-baseline.json",
    mismatched_jwt_compromise_resulting_key,
)
if (
    mismatched_jwt_compromise_status != "fail"
    or "candidateKid must be present in resultingKeyIds" not in mismatched_jwt_compromise_message
):
    raise SystemExit(
        "compromise candidate key absent from resultingKeyIds was accepted: "
        + mismatched_jwt_compromise_message
    )

compromised_jwt_compromise_resulting_key = copy.deepcopy(valid_jwt_compromise)
compromised_jwt_compromise_resulting_key["jwtHardening"]["resultingKeyIds"] = [
    "kid-candidate",
    "kid-compromised",
]
compromised_jwt_compromise_status, compromised_jwt_compromise_message = validate_recovery_variant(
    "compromised-jwt-compromise-resulting-key-baseline.json",
    compromised_jwt_compromise_resulting_key,
)
if (
    compromised_jwt_compromise_status != "fail"
    or "compromisedKid must be absent from resultingKeyIds" not in compromised_jwt_compromise_message
):
    raise SystemExit(
        "compromised key retained in resultingKeyIds was accepted: "
        + compromised_jwt_compromise_message
    )

missing_replacement_evidence = copy.deepcopy(valid_baseline)
del missing_replacement_evidence["jwtHardening"]["replacementEvidence"]
missing_replacement_status, missing_replacement_message = validate_recovery_variant(
    "missing-replacement-evidence-baseline.json",
    missing_replacement_evidence,
)
if (
    missing_replacement_status != "fail"
    or "replacementEvidence must be a non-empty list" not in missing_replacement_message
):
    raise SystemExit(
        "ordinary JWT hardening without replacement evidence was accepted: "
        + missing_replacement_message
    )

malformed_replacement_evidence = copy.deepcopy(valid_baseline)
malformed_replacement_evidence["jwtHardening"]["replacementEvidence"] = [{"oldKid": "kid-old"}]
malformed_replacement_status, malformed_replacement_message = validate_recovery_variant(
    "malformed-replacement-evidence-baseline.json",
    malformed_replacement_evidence,
)
if (
    malformed_replacement_status != "fail"
    or "replacementEvidence[0] must contain exactly" not in malformed_replacement_message
):
    raise SystemExit(
        "malformed ordinary JWT replacement evidence was accepted: "
        + malformed_replacement_message
    )

mismatched_replacement_evidence = copy.deepcopy(valid_baseline)
mismatched_replacement_evidence["jwtHardening"]["replacementEvidence"][0][
    "validatorEvidenceRef"
] = "evidence/other-jwt-validators"
mismatched_replacement_status, mismatched_replacement_message = validate_recovery_variant(
    "mismatched-replacement-evidence-baseline.json",
    mismatched_replacement_evidence,
)
if (
    mismatched_replacement_status != "fail"
    or "validatorEvidenceRef must match validatorConvergenceEvidence" not in mismatched_replacement_message
):
    raise SystemExit(
        "mismatched ordinary JWT replacement evidence reference was accepted: "
        + mismatched_replacement_message
    )

false_replacement_evidence = copy.deepcopy(valid_baseline)
false_replacement_evidence["jwtHardening"]["replacementEvidence"][0]["oldKidRejected"] = False
false_replacement_status, false_replacement_message = validate_recovery_variant(
    "false-replacement-evidence-baseline.json",
    false_replacement_evidence,
)
if (
    false_replacement_status != "fail"
    or "rejection/acceptance flags must be true" not in false_replacement_message
):
    raise SystemExit(
        "false ordinary JWT replacement proof was accepted: " + false_replacement_message
    )

mismatched_replacement_resulting_key = copy.deepcopy(valid_baseline)
mismatched_replacement_resulting_key["jwtHardening"]["resultingKeyIds"] = ["kid-other"]
mismatched_replacement_resulting_key_status, mismatched_replacement_resulting_key_message = (
    validate_recovery_variant(
        "mismatched-replacement-resulting-key-baseline.json",
        mismatched_replacement_resulting_key,
    )
)
if (
    mismatched_replacement_resulting_key_status != "fail"
    or "replacementEvidence candidateKid must be present in resultingKeyIds"
    not in mismatched_replacement_resulting_key_message
):
    raise SystemExit(
        "ordinary JWT replacement candidate absent from resultingKeyIds was accepted: "
        + mismatched_replacement_resulting_key_message
    )

retained_replacement_resulting_key = copy.deepcopy(valid_baseline)
retained_replacement_resulting_key["jwtHardening"]["resultingKeyIds"] = ["kid-old", "kid-1"]
retained_replacement_status, retained_replacement_message = validate_recovery_variant(
    "retained-replacement-resulting-key-baseline.json",
    retained_replacement_resulting_key,
)
if (
    retained_replacement_status != "fail"
    or "replacementEvidence oldKid must be absent from resultingKeyIds" not in retained_replacement_message
):
    raise SystemExit(
        "ordinary JWT replacement old key retained in resultingKeyIds was accepted: "
        + retained_replacement_message
    )

missing_old_key_replacement = copy.deepcopy(valid_baseline)
missing_old_key_replacement["jwtHardening"]["oldOrRestoredKeyIds"] = [
    "kid-old",
    "kid-restored",
]
missing_old_key_status, missing_old_key_message = validate_recovery_variant(
    "missing-old-key-replacement-evidence-baseline.json",
    missing_old_key_replacement,
)
if (
    missing_old_key_status != "fail"
    or "must cover every old or restored key" not in missing_old_key_message
    or "missing: kid-restored" not in missing_old_key_message
):
    raise SystemExit(
        "ordinary JWT replacement accepted incomplete old/restored-key coverage: "
        + missing_old_key_message
    )

unlisted_old_key_replacement = copy.deepcopy(valid_baseline)
unlisted_old_key_replacement["jwtHardening"]["replacementEvidence"][0]["oldKid"] = (
    "kid-unlisted"
)
unlisted_old_key_status, unlisted_old_key_message = validate_recovery_variant(
    "unlisted-old-key-replacement-evidence-baseline.json",
    unlisted_old_key_replacement,
)
if (
    unlisted_old_key_status != "fail"
    or "must cover every old or restored key" not in unlisted_old_key_message
    or "unlisted: kid-unlisted" not in unlisted_old_key_message
):
    raise SystemExit(
        "ordinary JWT replacement accepted an unlisted old/restored key: "
        + unlisted_old_key_message
    )

compromise_with_replacement_evidence = copy.deepcopy(valid_jwt_compromise)
compromise_with_replacement_evidence["jwtHardening"]["replacementEvidence"] = copy.deepcopy(
    valid_baseline["jwtHardening"]["replacementEvidence"]
)
compromise_with_replacement_evidence["jwtHardening"]["oldOrRestoredKeyIds"] = ["kid-old"]
compromise_with_replacement_status, compromise_with_replacement_message = validate_recovery_variant(
    "compromise-with-replacement-evidence-baseline.json",
    compromise_with_replacement_evidence,
)
if (
    compromise_with_replacement_status != "fail"
    or "replacement evidence is prohibited" not in compromise_with_replacement_message
):
    raise SystemExit(
        "compromise-classified JWT hardening with ordinary replacement evidence was accepted: "
        + compromise_with_replacement_message
    )

verified_with_replacement_evidence = copy.deepcopy(valid_baseline)
verified_with_replacement_evidence["credentialDispositions"]["jwt-signing-keys-jwks"] = (
    "verified_not_restored"
)
verified_with_replacement_status, verified_with_replacement_message = validate_recovery_variant(
    "verified-with-replacement-evidence-baseline.json",
    verified_with_replacement_evidence,
)
if (
    verified_with_replacement_status != "fail"
    or "replacement evidence is prohibited" not in verified_with_replacement_message
):
    raise SystemExit(
        "verified_not_restored JWT hardening with ordinary replacement evidence was accepted: "
        + verified_with_replacement_message
    )

missing_freshness_entry = copy.deepcopy(valid_baseline)
del missing_freshness_entry["secretComplianceRefresh"]["freshness"]["jwt-signing-keys-jwks"]
missing_freshness_status, missing_freshness_message = validate_recovery_variant(
    "missing-freshness-entry-baseline.json",
    missing_freshness_entry,
)
if missing_freshness_status != "fail" or "freshness keys must exactly match credentialClasses" not in missing_freshness_message:
    raise SystemExit(
        "missing recovery freshness entry was accepted: " + missing_freshness_message
    )

reissued_last_provisioned = copy.deepcopy(valid_baseline)
reissued_last_provisioned["credentialDispositions"]["jwt-signing-keys-jwks"] = "reissued"
reissued_last_provisioned["secretComplianceRefresh"]["freshness"]["jwt-signing-keys-jwks"]["field"] = "lastProvisionedAt"
reissued_last_provisioned_status, reissued_last_provisioned_message = validate_recovery_variant(
    "reissued-last-provisioned-baseline.json",
    reissued_last_provisioned,
)
if (
    reissued_last_provisioned_status != "fail"
    or "reissued freshness must use lastRotationAt" not in reissued_last_provisioned_message
):
    raise SystemExit(
        "existing-lineage reissued credential using lastProvisionedAt was accepted: "
        + reissued_last_provisioned_message
    )

for freshness_disposition, selected_timestamp, freshness_relation in (
    ("rotated", "2026-03-01T00:00:00Z", "equal"),
    ("rotated", "2026-02-01T00:00:00Z", "older"),
    ("reissued", "2026-03-01T00:00:00Z", "equal"),
    ("reissued", "2026-02-01T00:00:00Z", "older"),
):
    stale_existing_lineage = copy.deepcopy(valid_baseline)
    stale_existing_lineage["credentialDispositions"]["jwt-signing-keys-jwks"] = (
        freshness_disposition
    )
    stale_existing_lineage["secretComplianceRefresh"]["freshness"][
        "jwt-signing-keys-jwks"
    ]["value"] = selected_timestamp
    stale_existing_lineage_status, stale_existing_lineage_message = (
        validate_recovery_variant(
            f"{freshness_disposition}-{freshness_relation}-existing-lineage-baseline.json",
            stale_existing_lineage,
        )
    )
    if (
        stale_existing_lineage_status != "fail"
        or f"{freshness_disposition} freshness must advance the existing timestamp"
        not in stale_existing_lineage_message
    ):
        raise SystemExit(
            f"existing-lineage {freshness_disposition} {freshness_relation} freshness was accepted: "
            + stale_existing_lineage_message
        )

new_lineage_reissued = copy.deepcopy(valid_baseline)
new_lineage_reissued["credentialDispositions"]["jwt-signing-keys-jwks"] = "reissued"
new_lineage_reissued["secretComplianceRefresh"]["freshness"]["jwt-signing-keys-jwks"] = {
    "lineage": "new",
    "field": "lastProvisionedAt",
    "value": freshness_timestamp,
    "previousField": None,
    "previousValue": None,
}
new_lineage_reissued_status, new_lineage_reissued_message = validate_recovery_variant(
    "new-lineage-reissued-baseline.json",
    new_lineage_reissued,
)
if new_lineage_reissued_status != "pass":
    raise SystemExit(
        "new-lineage first issuance using lastProvisionedAt did not pass: "
        + new_lineage_reissued_message
    )

new_lineage_rotated = copy.deepcopy(valid_baseline)
new_lineage_rotated["credentialDispositions"]["jwt-signing-keys-jwks"] = "rotated"
new_lineage_rotated["secretComplianceRefresh"]["freshness"]["jwt-signing-keys-jwks"] = {
    "lineage": "new",
    "field": "lastProvisionedAt",
    "value": freshness_timestamp,
    "previousField": None,
    "previousValue": None,
}
new_lineage_rotated_status, new_lineage_rotated_message = validate_recovery_variant(
    "new-lineage-rotated-baseline.json",
    new_lineage_rotated,
)
if new_lineage_rotated_status != "pass":
    raise SystemExit(
        "new-lineage first issuance using rotated did not pass: " + new_lineage_rotated_message
    )

rebound_changed_timestamp = copy.deepcopy(valid_baseline)
rebound_changed_timestamp["secretComplianceRefresh"]["freshness"][
    "postgres-application-credentials"
]["value"] = older_freshness_timestamp
rebound_changed_status, rebound_changed_message = validate_recovery_variant(
    "rebound-changed-timestamp-baseline.json",
    rebound_changed_timestamp,
)
if (
    rebound_changed_status != "fail"
    or "rebound freshness must preserve the existing field/value" not in rebound_changed_message
):
    raise SystemExit(
        "rebound credential changing its preserved timestamp was accepted: "
        + rebound_changed_message
    )

future_rebound_preserved_timestamp = copy.deepcopy(valid_baseline)
future_rebound_preserved_timestamp["secretComplianceRefresh"]["freshness"][
    "postgres-application-credentials"
]["value"] = future_timestamp
future_rebound_preserved_timestamp["secretComplianceRefresh"]["freshness"][
    "postgres-application-credentials"
]["previousValue"] = future_timestamp
future_rebound_status, future_rebound_message = validate_recovery_variant(
    "future-rebound-preserved-timestamp-baseline.json",
    future_rebound_preserved_timestamp,
)
if (
    future_rebound_status != "fail"
    or "freshness value must not be later than finalizedAt" not in future_rebound_message
):
    raise SystemExit(
        "future rebound freshness pair was accepted: " + future_rebound_message
    )

wide_restore_high_water = {"stream": "erasures", "sequence": 40}
wide_overlay = copy.deepcopy(valid_baseline["erasureOverlayReconciliation"])
wide_overlay["restoreHighWater"] = wide_restore_high_water
wide_display_limit = module.MISSING_SEQUENCE_DISPLAY_LIMIT
wide_overlay_status, wide_overlay_message = module.validate_erasure_overlay_reconciliation(
    wide_overlay,
    valid_baseline["artifactErasureHighWater"],
    valid_baseline["initialCatchupHighWater"],
    wide_restore_high_water,
    "erasures",
)
if (
    wide_overlay_status != "fail"
    or "missingCount=28" not in wide_overlay_message
    or f"missing={list(range(13, 13 + wide_display_limit))}" not in wide_overlay_message
    or f"omittedCount={28 - wide_display_limit}" not in wide_overlay_message
):
    raise SystemExit(
        "wide missing sequence interval did not report the true count with a truncated display: "
        + wide_overlay_message
    )

unordered_overlay = copy.deepcopy(valid_baseline["erasureOverlayReconciliation"])
unordered_artifact_high_water = {"stream": "erasures", "sequence": 12}
unordered_initial_catchup_high_water = {"stream": "erasures", "sequence": 10}
unordered_restore_high_water = {"stream": "erasures", "sequence": 12}
unordered_overlay["artifactErasureHighWater"] = unordered_artifact_high_water
unordered_overlay["initialCatchupHighWater"] = unordered_initial_catchup_high_water
unordered_overlay["restoreHighWater"] = unordered_restore_high_water
unordered_status, unordered_message = module.validate_erasure_overlay_reconciliation(
    unordered_overlay,
    unordered_artifact_high_water,
    unordered_initial_catchup_high_water,
    unordered_restore_high_water,
    "erasures",
)
if (
    unordered_status != "fail"
    or unordered_message
    != (
        "Recovery compatibility baseline erasureOverlayReconciliation "
        "canonical erasure high-water sequences must be ordered"
    )
):
    raise SystemExit(f"unordered canonical overlay boundaries did not fail at the ordering guard: {unordered_message}")

for boundary_sequence in ("10", True):
    direct_boundary_overlay = copy.deepcopy(valid_baseline["erasureOverlayReconciliation"])
    direct_boundary = {"stream": "erasures", "sequence": boundary_sequence}
    direct_boundary_overlay["artifactErasureHighWater"] = direct_boundary
    direct_boundary_status, direct_boundary_message = module.validate_erasure_overlay_reconciliation(
        direct_boundary_overlay,
        direct_boundary,
        valid_baseline["initialCatchupHighWater"],
        valid_baseline["restoreHighWater"],
        "erasures",
    )
    if (
        direct_boundary_status != "fail"
        or "canonical artifactErasureHighWater.sequence must be an integer" not in direct_boundary_message
    ):
        raise SystemExit(
            "direct overlay canonical boundary type guard did not reject the invalid sequence: "
            + direct_boundary_message
        )

pre_snapshot_after_artifact = copy.deepcopy(valid_baseline)
pre_snapshot_after_artifact["backupArtifactLineage"]["preSnapshotJournalHighWater"] = {
    **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
    "stream": "erasures",
    "sequence": 12,
}
pre_snapshot_after_artifact_path = recovery_dir / "pre-snapshot-after-artifact-baseline.json"
pre_snapshot_after_artifact_path.write_text(json.dumps(pre_snapshot_after_artifact), encoding="utf-8")
pre_snapshot_after_artifact_status, pre_snapshot_after_artifact_message = module.validate_recovery_baseline(
    tmp,
    str(pre_snapshot_after_artifact_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if pre_snapshot_after_artifact_status != "pass":
    raise SystemExit(
        "preSnapshotJournalHighWater above artifact high-water did not pass: "
        + pre_snapshot_after_artifact_message
    )

intervening_coverage_entry = {
    "sequence": 10,
    "snapshotVisibleLedger": {
        "identity": "erasure-10",
        "digest": "sha256:erasure-10",
    },
    "externalJournal": {
        "identity": "erasure-10",
        "digest": "sha256:erasure-10",
    },
}
pre_snapshot_before_artifact = copy.deepcopy(valid_baseline)
pre_snapshot_before_artifact["backupArtifactLineage"]["preSnapshotJournalHighWater"] = {
    **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
    "stream": "erasures",
    "sequence": 9,
}
pre_snapshot_before_artifact["backupArtifactLineage"]["interveningErasureCoverageProof"] = {
    "stream": "erasures",
    "exclusiveStart": 9,
    "inclusiveEnd": 10,
    "snapshotLedgerEvidenceRef": "evidence/snapshot-ledger-9-10.json",
    "externalJournalEvidenceRef": "evidence/external-journal-9-10.json",
    "entries": [intervening_coverage_entry],
}
pre_snapshot_before_artifact_path = recovery_dir / "pre-snapshot-before-artifact-baseline.json"
pre_snapshot_before_artifact_path.write_text(json.dumps(pre_snapshot_before_artifact), encoding="utf-8")
pre_snapshot_before_artifact_status, pre_snapshot_before_artifact_message = module.validate_recovery_baseline(
    tmp,
    str(pre_snapshot_before_artifact_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if pre_snapshot_before_artifact_status != "pass":
    raise SystemExit(
        "complete intervening erasure coverage proof did not pass: "
        + pre_snapshot_before_artifact_message
    )

invalid_intervening_proof_shapes = [
    ("non-list-entries", "entries", "not-a-list", ".entries must be an ordered list"),
    ("empty-entries", "entries", [], ".entries must cover every sequence in order exactly once"),
    (
        "blank-snapshot-ledger-evidence-ref",
        "snapshotLedgerEvidenceRef",
        "   ",
        ".snapshotLedgerEvidenceRef must be a non-empty immutable evidence reference",
    ),
    (
        "blank-external-journal-evidence-ref",
        "externalJournalEvidenceRef",
        "   ",
        ".externalJournalEvidenceRef must be a non-empty immutable evidence reference",
    ),
]
for fixture_name, field, value, expected_message in invalid_intervening_proof_shapes:
    invalid_proof = copy.deepcopy(pre_snapshot_before_artifact)
    invalid_proof["backupArtifactLineage"]["interveningErasureCoverageProof"][field] = value
    invalid_proof_path = recovery_dir / f"{fixture_name}-baseline.json"
    invalid_proof_path.write_text(json.dumps(invalid_proof), encoding="utf-8")
    invalid_proof_status, invalid_proof_message = module.validate_recovery_baseline(
        tmp,
        str(invalid_proof_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
    if invalid_proof_status != "fail" or expected_message not in invalid_proof_message:
        raise SystemExit(
            f"invalid intervening erasure coverage proof {fixture_name} passed: "
            + invalid_proof_message
        )

missing_intervening_proof = copy.deepcopy(pre_snapshot_before_artifact)
del missing_intervening_proof["backupArtifactLineage"]["interveningErasureCoverageProof"]
missing_intervening_proof_path = recovery_dir / "missing-intervening-proof-baseline.json"
missing_intervening_proof_path.write_text(json.dumps(missing_intervening_proof), encoding="utf-8")
missing_intervening_proof_status, missing_intervening_proof_message = module.validate_recovery_baseline(
    tmp,
    str(missing_intervening_proof_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if missing_intervening_proof_status != "fail" or "interveningErasureCoverageProof" not in (
    missing_intervening_proof_message
):
    raise SystemExit(
        "lower pre-snapshot high-water passed without intervening coverage proof: "
        + missing_intervening_proof_message
    )

mismatched_intervening_proof = copy.deepcopy(pre_snapshot_before_artifact)
mismatched_intervening_proof["backupArtifactLineage"]["interveningErasureCoverageProof"]["entries"][0][
    "externalJournal"
]["digest"] = "sha256:mismatch"
mismatched_intervening_proof_path = recovery_dir / "mismatched-intervening-proof-baseline.json"
mismatched_intervening_proof_path.write_text(json.dumps(mismatched_intervening_proof), encoding="utf-8")
mismatched_intervening_proof_status, mismatched_intervening_proof_message = module.validate_recovery_baseline(
    tmp,
    str(mismatched_intervening_proof_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if mismatched_intervening_proof_status != "fail" or "matching identity and digest" not in (
    mismatched_intervening_proof_message
):
    raise SystemExit(
        "mismatched intervening erasure identity/digest proof passed: "
        + mismatched_intervening_proof_message
    )

unneeded_intervening_proof = copy.deepcopy(valid_baseline)
unneeded_intervening_proof["backupArtifactLineage"]["interveningErasureCoverageProof"] = (
    pre_snapshot_before_artifact["backupArtifactLineage"]["interveningErasureCoverageProof"]
)
unneeded_intervening_proof_path = recovery_dir / "unneeded-intervening-proof-baseline.json"
unneeded_intervening_proof_path.write_text(json.dumps(unneeded_intervening_proof), encoding="utf-8")
unneeded_intervening_proof_status, unneeded_intervening_proof_message = module.validate_recovery_baseline(
    tmp,
    str(unneeded_intervening_proof_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if unneeded_intervening_proof_status != "fail" or "must be absent" not in unneeded_intervening_proof_message:
    raise SystemExit(
        "unneeded intervening erasure coverage proof passed: "
        + unneeded_intervening_proof_message
    )

rollback_compatibility_status, rollback_compatibility_message = module.recovery_compatibility_check(
    {
        "generatedAt": past_timestamp,
        "recoveryCompatibility": compatibility_result("compatible"),
    },
    "rollback-compatible",
    tmp,
    now,
)
if rollback_compatibility_status != "pass":
    raise SystemExit(
        "rollback-compatible unchanged recovery compatibility did not pass: "
        + rollback_compatibility_message
    )

invalid_baseline_cases = {
    "controller": {"recoveryControllerLineage": {"recoveryStatus": "collecting", "scope": "environment-wide"}},
    "erasure": {"erasureReplay": {**valid_baseline["erasureReplay"], "gapFree": False}},
    "coordination": {
        "coordinationRecoveryEvidence": {
            **valid_baseline["coordinationRecoveryEvidence"],
            "coordinationRedis": "non-empty",
        }
    },
    "confidentiality": {
        "backupConfidentialityEvidence": {
            **valid_baseline["backupConfidentialityEvidence"],
            "status": "fail",
        }
    },
    "participant": {"durableParticipantConvergence": {"gameplay": {"disposition": "unknown"}}},
    "coordination-account-projections": {
        "coordinationRecoveryEvidence": {
            **valid_baseline["coordinationRecoveryEvidence"],
            "accountAuthorityProjections": "missing",
        }
    },
    "coordination-replay-evidence": {
        "coordinationRecoveryEvidence": {
            **valid_baseline["coordinationRecoveryEvidence"],
            "replayConsumeEvidenceRef": "",
        }
    },
    "canonical-high-water-non-int-sequence": {
        "artifactErasureHighWater": {"stream": "erasures", "sequence": "10"},
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": "10"},
        },
    },
    "overlay-restore-boundary-equality-mismatch": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "restoreHighWater": {"stream": "erasures", "sequence": 11},
        }
    },
    "overlay-artifact-boundary-equality-mismatch": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 9},
        }
    },
    "overlay-initial-catchup-boundary-equality-mismatch": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "initialCatchupHighWater": {"stream": "erasures", "sequence": 10},
        }
    },
    "lineage-pre-snapshot": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": None,
        }
    },
    "lineage-artifact-high-water-mismatch": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 9},
        }
    },
    "lineage-snapshot-bound": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "erasureHighWaterSnapshotBound": False,
        }
    },
    "lineage-pre-snapshot-witness-missing": {
        "backupArtifactLineage": {
            key: value
            for key, value in valid_baseline["backupArtifactLineage"].items()
            if key != "preSnapshotJournalBoundaryWitness"
        }
    },
    "lineage-pre-snapshot-observation-id-mismatch": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalBoundaryWitness": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalBoundaryWitness"],
                "observationId": "observation-other",
            },
        }
    },
    "lineage-pre-snapshot-observation-digest-mismatch": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalBoundaryWitness": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalBoundaryWitness"],
                "observationDigest": "sha256:observation-other",
            },
        }
    },
    "lineage-pre-snapshot-identity-mismatch": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalBoundaryWitness": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalBoundaryWitness"],
                "snapshotIdentity": "snapshot-other",
            },
        }
    },
    "lineage-pre-snapshot-opening-mismatch": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalBoundaryWitness": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalBoundaryWitness"],
                "snapshotOpenedAt": timestamp(
                    now - module.dt.timedelta(minutes=29)
                ),
            },
        }
    },
    "lineage-pre-snapshot-observation-not-before-opening": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
                "observedAt": valid_baseline["backupArtifactLineage"]["snapshotAt"],
            },
        }
    },
    "lineage-pre-snapshot-stream": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
                "stream": "other-stream",
            },
        }
    },
    "lineage-pre-snapshot-sequence": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
                "sequence": "10",
            },
        }
    },
    "lineage-pre-snapshot-below-artifact-high-water": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
                "sequence": 9,
            },
        }
    },
    "lineage-pre-snapshot-above-restore-high-water": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
                "sequence": 13,
            },
        }
    },
    "overlay-stream": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "stream": "other-stream",
        }
    },
    "overlay-entry-stream": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "stream": "other-stream",
                }
            ],
        }
    },
    "overlay-integrity": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "integrityVerification": {"status": "pass", "verified": False},
        }
    },
    "overlay-entry-integrity": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "integrityVerified": False,
                }
            ],
        }
    },
    "overlay-duplicate": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
            ],
        }
    },
    "overlay-missing-entry": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [],
        }
    },
    "overlay-dispositions-type": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": {},
        }
    },
    "overlay-gap": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceVerification": {
                **valid_baseline["erasureOverlayReconciliation"]["sequenceVerification"],
                "gapFree": False,
            },
        }
    },
    "overlay-sequence-verification-absent": {
        "erasureOverlayReconciliation": {
            key: value
            for key, value in valid_baseline["erasureOverlayReconciliation"].items()
            if key != "sequenceVerification"
        }
    },
    "overlay-sequence-verification-type": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceVerification": [],
        }
    },
    "overlay-sequence-verification-status": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceVerification": {
                **valid_baseline["erasureOverlayReconciliation"]["sequenceVerification"],
                "status": "fail",
            },
        }
    },
    "overlay-sequence-verification-unordered": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceVerification": {
                **valid_baseline["erasureOverlayReconciliation"]["sequenceVerification"],
                "ordered": False,
            },
        }
    },
    "overlay-out-of-range": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "sequence": 11,
                }
            ],
        }
    },
    "overlay-owner-missing": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    key: value
                    for key, value in valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0].items()
                    if key != "owner"
                }
            ],
        }
    },
    "overlay-owner-blank": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "owner": "   ",
                }
            ],
        }
    },
    "overlay-disposition": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "disposition": "unknown",
                }
            ],
        }
    },
    "overlay-type": {
        "erasureOverlayReconciliation": ["not-an-object"],
    },
    "overlay-entry-type": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": ["not-an-object"],
        }
    },
    "overlay-entry-bool-sequence": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "sequence": True,
                }
            ],
        }
    },
    "overlay-entry-non-int-sequence": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "sequence": "12",
                }
            ],
        }
    },
    "overlay-object-artifact-boundary-bool-sequence": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": True},
        }
    },
    "overlay-object-restore-boundary-non-int-sequence": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "restoreHighWater": {"stream": "erasures", "sequence": "12"},
        }
    },
    "overlay-verification-bounds": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceVerification": {
                **valid_baseline["erasureOverlayReconciliation"]["sequenceVerification"],
                "inclusiveEnd": 12,
            },
        }
    },
    "overlay-sequence-verification-bool-endpoint": {
        "artifactErasureHighWater": {"stream": "erasures", "sequence": 1},
        "initialCatchupHighWater": {"stream": "erasures", "sequence": 1},
        "erasureReplay": {
            **valid_baseline["erasureReplay"],
            "exclusiveStart": 1,
            "initialCatchupThrough": 1,
        },
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 1},
            "initialCatchupHighWater": {"stream": "erasures", "sequence": 1},
            "sequenceVerification": {
                **valid_baseline["erasureOverlayReconciliation"]["sequenceVerification"],
                "exclusiveStart": True,
                "inclusiveEnd": True,
            },
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "sequence": sequence,
                }
                for sequence in range(2, 13)
            ],
        },
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {"stream": "erasures", "sequence": 1},
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 1},
        },
    },
    "overlay-integrity-failed": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "integrityVerification": {"status": "fail", "verified": False},
        }
    },
    "overlay-artifact-boundary-equality-mismatch-at-restore-boundary": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 12},
            "initialCatchupHighWater": {"stream": "erasures", "sequence": 11},
            "restoreHighWater": {"stream": "erasures", "sequence": 12},
        }
    },
    "retained-backlog": {
        "durableParticipantConvergence": {
            "gameplay": {"disposition": "fenced_disabled_backlog_retained"},
        }
    },
}
expected_invalid_baseline_messages = {
    "controller": "controller lineage must be finalized",
    "erasure": "erasure replay must be gap-free through restoreHighWater",
    "coordination": "coordination recovery must prove",
    "confidentiality": "backup confidentiality evidence must pass",
    "participant": "unsafe or missing disposition: gameplay",
    "coordination-account-projections": "coordination recovery must prove",
    "coordination-replay-evidence": "coordination recovery must prove",
    "canonical-high-water-non-int-sequence": "erasure high-water sequences must be ordered non-boolean integers",
    "overlay-restore-boundary-equality-mismatch": "restoreHighWater must match the canonical bound exactly",
    "overlay-artifact-boundary-equality-mismatch": "artifactErasureHighWater must match the canonical bound exactly",
    "overlay-initial-catchup-boundary-equality-mismatch": "initialCatchupHighWater must match the canonical bound exactly",
    "lineage-pre-snapshot": "artifact lineage must include a valid",
    "lineage-artifact-high-water-mismatch": "artifactErasureHighWater must match the snapshot-bound",
    "lineage-snapshot-bound": "erasureHighWaterSnapshotBound must be true",
    "lineage-pre-snapshot-stream": "preSnapshotJournalHighWater.stream must match",
    "lineage-pre-snapshot-sequence": "preSnapshotJournalHighWater.sequence must be an integer",
    "lineage-pre-snapshot-below-artifact-high-water": "interveningErasureCoverageProof must be an object",
    "lineage-pre-snapshot-above-restore-high-water": "preSnapshotJournalHighWater.sequence must be at or below",
    "lineage-pre-snapshot-witness-missing": "preSnapshotJournalBoundaryWitness must be an object",
    "lineage-pre-snapshot-observation-id-mismatch": "observationId must match preSnapshotJournalHighWater.observationId",
    "lineage-pre-snapshot-observation-digest-mismatch": "observationDigest must match preSnapshotJournalHighWater.observationDigest",
    "lineage-pre-snapshot-identity-mismatch": "snapshotIdentity must match snapshotIdentity",
    "lineage-pre-snapshot-opening-mismatch": "snapshotOpenedAt must exactly equal snapshotAt",
    "lineage-pre-snapshot-observation-not-before-opening": "observedAt must strictly precede snapshot opening",
    "overlay-stream": "stream must match the canonical erasure stream",
    "overlay-entry-stream": "sequenceDispositions[0] stream must match the canonical erasure stream",
    "overlay-integrity": "integrityVerification must be verified with status pass",
    "overlay-entry-integrity": "sequenceDispositions[0] integrity must be verified",
    "overlay-duplicate": "contains duplicate sequence 12",
    "overlay-missing-entry": "missingCount=1, missing=[12]",
    "overlay-dispositions-type": "sequenceDispositions must be a list",
    "overlay-gap": "sequenceVerification must prove the canonical bounds and ordered, contiguous, complete, gap-free, duplicate-free initial catch-up interval",
    "overlay-out-of-range": "sequenceDispositions[0] sequence is outside the final interval",
    "overlay-owner-missing": "sequenceDispositions[0] owner must be non-empty",
    "overlay-owner-blank": "sequenceDispositions[0] owner must be non-empty",
    "overlay-disposition": "has an invalid canonical disposition",
    "overlay-type": "erasureOverlayReconciliation must be an object",
    "overlay-entry-type": "sequenceDispositions[0] must be an object",
    "overlay-entry-bool-sequence": "sequenceDispositions[0] sequence must be an integer",
    "overlay-entry-non-int-sequence": "sequenceDispositions[0] sequence must be an integer",
    "overlay-object-artifact-boundary-bool-sequence": "artifactErasureHighWater must match the canonical bound exactly",
    "overlay-object-restore-boundary-non-int-sequence": "restoreHighWater must match the canonical bound exactly",
    "overlay-verification-bounds": "sequenceVerification must prove the canonical bounds",
    "overlay-sequence-verification-absent": "sequenceVerification must prove the canonical bounds",
    "overlay-sequence-verification-type": "sequenceVerification must prove the canonical bounds",
    "overlay-sequence-verification-status": "sequenceVerification must prove the canonical bounds",
    "overlay-sequence-verification-unordered": "sequenceVerification must prove the canonical bounds",
    "overlay-sequence-verification-bool-endpoint": "sequenceVerification must prove the canonical bounds",
    "overlay-integrity-failed": "integrityVerification must be verified with status pass",
    "overlay-artifact-boundary-equality-mismatch-at-restore-boundary": "artifactErasureHighWater must match the canonical bound exactly",
    "retained-backlog": "unsafe or missing disposition: gameplay",
}
if set(invalid_baseline_cases) != set(expected_invalid_baseline_messages):
    raise SystemExit(
        "invalid recovery baseline cases and expected messages must have the same exact key set: "
        f"cases={sorted(invalid_baseline_cases)}, messages={sorted(expected_invalid_baseline_messages)}"
    )
for case_name, replacement in invalid_baseline_cases.items():
    invalid_baseline = copy.deepcopy(valid_baseline)
    invalid_baseline.update(copy.deepcopy(replacement))
    invalid_path = recovery_dir / f"invalid-{case_name}-baseline.json"
    invalid_path.write_text(json.dumps(invalid_baseline), encoding="utf-8")
    invalid_status, invalid_message = module.validate_recovery_baseline(
        tmp,
        str(invalid_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
    expected_message = expected_invalid_baseline_messages[case_name]
    if invalid_status != "fail" or expected_message not in invalid_message:
        raise SystemExit(
            f"invalid {case_name} recovery baseline failed for the wrong reason: {invalid_message}"
        )

stale_baseline = {
    **canonical_recovery_record(now - module.dt.timedelta(days=31)),
}
stale_baseline_path = recovery_dir / "stale-baseline.json"
stale_baseline_path.write_text(json.dumps(stale_baseline), encoding="utf-8")
stale_status, stale_message = module.validate_recovery_baseline(
    tmp,
    str(stale_baseline_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if stale_status != "fail" or "older than 30 days" not in stale_message:
    raise SystemExit(f"stale recovery baseline did not fail closed: {stale_message}")

missing_baseline_status, missing_baseline_message = module.validate_recovery_baseline(
    tmp,
    "design/operations/deployments/production/recovery/missing-baseline.json",
    "sha256:recovery-contract",
    now,
    now,
)
if missing_baseline_status != "fail" or "not found" not in missing_baseline_message:
    raise SystemExit(f"missing recovery baseline did not fail closed: {missing_baseline_message}")

outside_baseline_path = write_json("outside-baseline.json", valid_baseline)
outside_status, outside_message = module.validate_recovery_baseline(
    tmp,
    outside_baseline_path.name,
    "sha256:recovery-contract",
    now,
    now,
)
if outside_status != "fail" or "production/recovery" not in outside_message:
    raise SystemExit(f"out-of-namespace recovery baseline did not fail closed: {outside_message}")

missing_compatibility_attestation = write_json(
    "missing-recovery-compatibility-attestation.json",
    {
        "attestationVersion": "v1",
        "environment": "staging",
        "generatedAt": past_timestamp,
        "stagingOverlayCommitSha": "deadbeef",
        "stagingDeploymentEventId": "55555555-5555-4555-8555-555555555555",
        "productionOverlayRef": "contract-production",
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "smokeEvidence": [smoke_evidence_ref],
        "approvedBy": "preflight-contract",
        "rollbackMode": "rollback-compatible",
    },
)
missing_status, _, missing_message, missing_recovery_status, missing_recovery_message = module.promotion_check(
    missing_compatibility_attestation,
    [],
    tmp,
)
if (
    missing_status != "fail"
    or "canonical fields" not in missing_message
    or "recoveryCompatibility" not in missing_message
    or missing_recovery_status != "fail"
    or "recoveryCompatibility" not in missing_recovery_message
):
    raise SystemExit(f"missing recoveryCompatibility did not fail closed: {missing_message}")

naive_promotion_status, _, naive_promotion_message, _, _ = module.promotion_check(
    missing_compatibility_attestation,
    [],
    tmp,
    evaluation_time=module.dt.datetime(2026, 3, 19, 10, 55),
)
if naive_promotion_status != "fail" or "evaluation time must include a timezone" not in naive_promotion_message:
    raise SystemExit(
        "naive promotion evaluation time was not rejected before normalization: "
        + naive_promotion_message
    )

def promotion_attestation(compatibility, rollback_mode="rollback-compatible"):
    return {
        "attestationVersion": "v1",
        "environment": "staging",
        "stagingOverlayCommitSha": "deadbeef",
        "stagingDeploymentEventId": "55555555-5555-4555-8555-555555555555",
        "productionOverlayRef": "contract-production",
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "smokeEvidence": [smoke_evidence_ref],
        "generatedAt": past_timestamp,
        "approvedBy": "preflight-contract",
        "rollbackMode": rollback_mode,
        "recoveryCompatibility": compatibility,
    }

for compact_field in (
    "newestVerifiedRestorablePointRef",
    "newestVerifiedRestorablePointDigest",
    "newestVerifiedRestorablePointAt",
):
    missing_compact_point = compatibility_result("compatible")
    del missing_compact_point[compact_field]
    missing_compact_status, missing_compact_message = module.recovery_compatibility_check(
        {"generatedAt": past_timestamp, "recoveryCompatibility": missing_compact_point},
        "rollback-compatible",
        tmp,
        now,
    )
    if missing_compact_status != "fail" or compact_field not in missing_compact_message:
        raise SystemExit(
            f"missing {compact_field} did not fail recovery compatibility closed: "
            + missing_compact_message
        )

future_compact_point = compatibility_result("compatible")
future_compact_point["newestVerifiedRestorablePointAt"] = future_timestamp
future_compact_status, future_compact_message = module.recovery_compatibility_check(
    {"generatedAt": past_timestamp, "recoveryCompatibility": future_compact_point},
    "rollback-compatible",
    tmp,
    now,
)
if future_compact_status != "fail" or "newestVerifiedRestorablePointAt is future-dated" not in future_compact_message:
    raise SystemExit(f"future compact verified point did not fail closed: {future_compact_message}")

as_of_compact_point = compatibility_result("compatible")
as_of_compact_point["evaluatedAt"] = timestamp(now - module.dt.timedelta(minutes=10))
as_of_compact_status, as_of_compact_message = module.recovery_compatibility_check(
    {"generatedAt": past_timestamp, "recoveryCompatibility": as_of_compact_point},
    "rollback-compatible",
    tmp,
    now,
)
if (
    as_of_compact_status != "fail"
    or "must not be later than the assessment/evaluation time" not in as_of_compact_message
):
    raise SystemExit(
        "verified point after recovery evaluation time did not fail closed: "
        + as_of_compact_message
    )

stale_compact_point = compatibility_result("compatible")
stale_compact_point["newestVerifiedRestorablePointAt"] = timestamp(now - module.dt.timedelta(minutes=20))
stale_compact_status, stale_compact_message = module.recovery_compatibility_check(
    {"generatedAt": past_timestamp, "recoveryCompatibility": stale_compact_point},
    "rollback-compatible",
    tmp,
    now,
)
if (
    stale_compact_status != "fail"
    or "newestVerifiedRestorablePointAt older than 15 minutes" not in stale_compact_message
    or str(readiness_point_path.relative_to(tmp)) not in stale_compact_message
    or "generate a new event-scoped preflight report before retrying" not in stale_compact_message
):
    raise SystemExit(f"stale compact verified point did not fail closed: {stale_compact_message}")

bad_compact_digest = compatibility_result("compatible")
bad_compact_digest["newestVerifiedRestorablePointDigest"] = "sha256:" + "c" * 64
bad_compact_digest_status, bad_compact_digest_message = module.recovery_compatibility_check(
    {"generatedAt": past_timestamp, "recoveryCompatibility": bad_compact_digest},
    "rollback-compatible",
    tmp,
    now,
)
if bad_compact_digest_status != "fail" or "does not match the verified-point record" not in bad_compact_digest_message:
    raise SystemExit(f"compact verified-point digest mismatch did not fail closed: {bad_compact_digest_message}")

missing_compact_record = compatibility_result("compatible")
missing_compact_record["newestVerifiedRestorablePointRef"] = (
    "design/operations/deployments/production/verified-restorable-points/missing.json"
)
missing_compact_record_status, missing_compact_record_message = module.recovery_compatibility_check(
    {"generatedAt": past_timestamp, "recoveryCompatibility": missing_compact_record},
    "rollback-compatible",
    tmp,
    now,
)
if missing_compact_record_status != "fail" or "record not found" not in missing_compact_record_message:
    raise SystemExit(f"missing compact verified-point record did not fail closed: {missing_compact_record_message}")

duplicate_compact_record = compatibility_result("compatible")
duplicate_compact_record["newestVerifiedRestorablePointRef"] = str(duplicate_point_path.relative_to(tmp))
duplicate_compact_status, duplicate_compact_message = module.recovery_compatibility_check(
    {"generatedAt": past_timestamp, "recoveryCompatibility": duplicate_compact_record},
    "rollback-compatible",
    tmp,
    now,
)
if duplicate_compact_status != "fail" or "duplicate JSON member" not in duplicate_compact_message:
    raise SystemExit(f"duplicate compact verified-point record did not fail closed: {duplicate_compact_message}")

compact_schema_invalid_path = verified_point_dir / "compact-schema-invalid.json"
compact_schema_invalid_path.write_text(json.dumps({}), encoding="utf-8")
schema_invalid_compact_record = compatibility_result("compatible")
schema_invalid_compact_record["newestVerifiedRestorablePointRef"] = str(
    compact_schema_invalid_path.relative_to(tmp)
)
schema_invalid_compact_status, schema_invalid_compact_message = module.recovery_compatibility_check(
    {"generatedAt": past_timestamp, "recoveryCompatibility": schema_invalid_compact_record},
    "rollback-compatible",
    tmp,
    now,
)
if schema_invalid_compact_status != "fail" or "record is schema-invalid" not in schema_invalid_compact_message:
    raise SystemExit(f"schema-invalid compact verified-point record did not fail closed: {schema_invalid_compact_message}")

for compatibility_status in ("drill_required", "incompatible"):
    status_result = compatibility_result(compatibility_status)
    if compatibility_status == "drill_required":
        status_result.update(
            {"newDrillRequired": True, "backupReadinessRef": "drill-required-readiness.json"}
        )
    status_attestation = write_json(
        f"{compatibility_status}-attestation.json",
        promotion_attestation(status_result),
    )
    promotion_status, _, promotion_message, _, _ = module.promotion_check(status_attestation, [], tmp)
    if promotion_status != "fail" or "compatibilityStatus blocks promotion" not in promotion_message:
        raise SystemExit(
            f"{compatibility_status} recoveryCompatibility did not fail closed: {promotion_message}"
        )

evaluated_after_generated = compatibility_result("compatible")
evaluated_after_generated["evaluatedAt"] = timestamp(now - module.dt.timedelta(minutes=1))
evaluated_after_generated_attestation = promotion_attestation(evaluated_after_generated)
evaluated_after_generated_attestation["generatedAt"] = timestamp(now - module.dt.timedelta(minutes=2))
evaluated_after_generated_path = write_json(
    "evaluated-after-generated-attestation.json",
    evaluated_after_generated_attestation,
)
evaluated_after_generated_status, _, evaluated_after_generated_message, _, _ = module.promotion_check(
    evaluated_after_generated_path, [], tmp
)
if (
    evaluated_after_generated_status != "fail"
    or "must not be after attestation generatedAt" not in evaluated_after_generated_message
):
    raise SystemExit(
        "recovery compatibility evaluated after attestation generation did not fail closed: "
        + evaluated_after_generated_message
    )

rollback_status, rollback_message = module.production_recovery_check(
    "pass", "rollback-compatible", "promotion valid", "", "contract-production", tmp
)
if rollback_status != "pass":
    raise SystemExit(f"valid rollback-compatible recovery gate did not pass: {rollback_message}")

rollback_failure_status, rollback_failure_message = module.production_recovery_check(
    "fail", "rollback-compatible", "baseline invalid", "", "contract-production", tmp
)
if rollback_failure_status != "fail" or "baseline invalid" not in rollback_failure_message:
    raise SystemExit(
        "failed rollback-compatible recovery validation did not fail PREFLIGHT-BACKUP-001: "
        + rollback_failure_message
    )

unknown_mode_status, unknown_mode_message = module.production_recovery_check(
    "fail", "unknown", "invalid attestation", "", "contract-production", tmp
)
if unknown_mode_status != "fail" or "rollbackMode is invalid" not in unknown_mode_message:
    raise SystemExit(f"invalid rollback mode did not fail recovery compatibility: {unknown_mode_message}")

roll_forward_status, roll_forward_message = module.production_recovery_check(
    "pass", "roll-forward-only", "promotion valid", "", "contract-production", tmp
)
if roll_forward_status != "fail" or "FIREMUD_BACKUP_READINESS_EVIDENCE" not in roll_forward_message:
    raise SystemExit(f"roll-forward promotion without readiness evidence did not fail: {roll_forward_message}")

naive_recovery_status, naive_recovery_message = module.production_recovery_check(
    "pass",
    "roll-forward-only",
    "promotion valid",
    str(readiness_point_path),
    "contract-production",
    tmp,
    evaluation_time=module.dt.datetime(2026, 3, 19, 10, 55),
)
if naive_recovery_status != "fail" or "evaluation time must include a timezone" not in naive_recovery_message:
    raise SystemExit(
        "naive roll-forward evaluation time was not rejected before normalization: "
        + naive_recovery_message
    )

captured_recovery_evaluation_times = []
original_backup_readiness_check = module.backup_readiness_check
original_utc_now = module.utc_now

def capture_backup_readiness(path, now_value, deployment_ref, root_dir):
    captured_recovery_evaluation_times.append(now_value)
    return ("pass", "captured")

def unexpected_utc_now():
    raise AssertionError("roll-forward recovery independently sampled utc_now")

module.backup_readiness_check = capture_backup_readiness
module.utc_now = unexpected_utc_now
try:
    captured_status, captured_message = module.production_recovery_check(
        "pass",
        "roll-forward-only",
        "promotion valid",
        str(readiness_point_path),
        "contract-production",
        tmp,
        evaluation_time=module.dt.datetime(
            2026, 3, 19, 10, 55, 0, 123456, tzinfo=module.dt.timezone(module.dt.timedelta(hours=1))
        ),
    )
finally:
    module.backup_readiness_check = original_backup_readiness_check
    module.utc_now = original_utc_now
if captured_status != "pass" or captured_message != "captured":
    raise SystemExit(f"roll-forward recovery capture failed: {captured_status}, {captured_message}")
if captured_recovery_evaluation_times != ["2026-03-19T09:55:00.123456Z"]:
    raise SystemExit(
        "roll-forward recovery did not reuse the normalized evaluation time: "
        + repr(captured_recovery_evaluation_times)
    )

gateway_image = "ghcr.io/benhook1013/spring-cloud-gateway@sha256:gateway"
account_image = "ghcr.io/benhook1013/account-service@sha256:account"
extracted_images = module.extract_service_images(
    "image: " + gateway_image + "\nimage: " + account_image + "\n"
)
if set(extracted_images) != {gateway_image, account_image}:
    raise SystemExit(f"production digest extraction omitted the Gateway image: {extracted_images}")

import subprocess

promotion_root = tmp / "promotion-git-root"
promotion_root.mkdir()
subprocess.run(["git", "-C", str(promotion_root), "init", "-q"], check=True)
subprocess.run(["git", "-C", str(promotion_root), "config", "user.name", "preflight-contract"], check=True)
subprocess.run(["git", "-C", str(promotion_root), "config", "user.email", "preflight-contract@example.test"], check=True)
(promotion_root / "marker").write_text("staging overlay\n", encoding="utf-8")
subprocess.run(["git", "-C", str(promotion_root), "add", "marker"], check=True)
subprocess.run(["git", "-C", str(promotion_root), "commit", "-qm", "staging overlay"], check=True)
staging_sha = subprocess.check_output(
    ["git", "-C", str(promotion_root), "rev-parse", "HEAD"], text=True
).strip()
if not module.git_commit_exists(promotion_root, staging_sha):
    raise SystemExit("valid stagingOverlayCommitSha was not proven to exist in Git")
if module.git_commit_exists(promotion_root, "deadbeef"):
    raise SystemExit("unknown stagingOverlayCommitSha was incorrectly accepted by Git validation")

staging_expected_bindings = (
    promotion_root / "design/operations/environments/staging/expected-bindings.yaml"
)
staging_expected_bindings.parent.mkdir(parents=True)
staging_expected_data = yaml.safe_load(
    (root / "design/operations/environments/staging/expected-bindings.yaml").read_text(
        encoding="utf-8"
    )
)
staging_expected_data["backupStorage"] = {
    "enabled": True,
    "bucket": "firemud-staging-backups",
    "endpoint": "https://minio.staging.internal",
    "bindingRef": "secret://firemud/staging-backup-object-store",
}
staging_expected_data["assetStorage"] = {
    "enabled": True,
    "bucket": "contract-staging-assets",
    "endpoint": "https://assets.staging.internal",
    "bindingRef": "secret://firemud/staging-asset-object-store",
}
staging_expected_bindings.write_text(
    yaml.safe_dump(staging_expected_data, sort_keys=False), encoding="utf-8"
)
expected_bindings_ref = "design/operations/environments/staging/expected-bindings.yaml"
expected_bindings_digest = module.immutable_file_digest(staging_expected_bindings)

if module.canonical_queryability_binding(promotion_root, "staging")[
    "evidenceFreshnessBudgetSeconds"
] != 7200:
    raise SystemExit("valid staging queryability binding was not loaded canonically")
for invalid_budget in (0, -1, 1e-100, 1e308, float("inf"), float("nan")):
    malformed_budget_data = copy.deepcopy(staging_expected_data)
    malformed_budget_data["observability"]["logPipelineQueryability"][
        "evidenceFreshnessBudgetSeconds"
    ] = invalid_budget
    staging_expected_bindings.write_text(
        yaml.safe_dump(malformed_budget_data, sort_keys=False), encoding="utf-8"
    )
    try:
        module.canonical_queryability_binding(promotion_root, "staging")
    except ValueError as exc:
        if "evidenceFreshnessBudgetSeconds must be positive" not in str(exc):
            raise SystemExit(
                "invalid staging queryability budget produced the wrong diagnostic: "
                + str(exc)
            )
    else:
        raise SystemExit(
            f"invalid staging queryability budget was accepted: {invalid_budget!r}"
        )
staging_expected_bindings.write_text(
    yaml.safe_dump(staging_expected_data, sort_keys=False), encoding="utf-8"
)

for env in ("production", "hobby-self-hosted"):
    expected_path = promotion_root / "design/operations/environments" / env / "expected-bindings.yaml"
    expected_path.parent.mkdir(parents=True, exist_ok=True)
    expected_path.write_text(
        (root / f"design/operations/environments/{env}/expected-bindings.yaml").read_text(
            encoding="utf-8"
        ),
        encoding="utf-8",
    )
staging_validation_documents = copy.deepcopy(rendered_documents)
for document in staging_validation_documents:
    if document.get("kind") != "ServiceAccount":
        continue
    for image_pull_secret in document.get("imagePullSecrets") or []:
        if image_pull_secret.get("name") == "ghcr-pull-hobby":
            image_pull_secret["name"] = "ghcr-pull-staging"
staging_binding_results = module.expected_binding_checks(
    staging_expected_bindings,
    "design/operations/environments/staging/expected-bindings.yaml",
    "staging",
    staging_validation_documents,
)
staging_binding_failures = [
    result
    for result in staging_binding_results
    if result.required and result.status == "fail"
]
if staging_binding_failures:
    raise SystemExit(
        "staging promotion expected-bindings fixture failed validation: "
        + "; ".join(result.message for result in staging_binding_failures)
    )

secret_evidence_path = (
    promotion_root / "design/operations/deployments/staging/secret-compliance.json"
)
secret_evidence_path.parent.mkdir(parents=True, exist_ok=True)
def evidence_digest(record):
    return module.canonical_evidence_digest(record)


def make_secret_evidence():
    records = {}
    for class_name in (
        "jwt-signing-keys-jwks",
        "postgres-application-credentials",
        "backup-object-store-credentials",
        "asset-store-credentials",
        "operator-credentials",
    ):
        record = {
            "targetEnvironment": "staging",
            "credentialClass": class_name,
            "evidenceOperationId": f"rotation-staging-{class_name}",
        }
        record["immutableArtifactId"] = evidence_digest(record)
        records[class_name] = record
    return {"environment": "staging", "records": records}


def make_bootstrap_secret_evidence():
    bootstrap_operation_id = "bootstrap-staging-20260825"
    provisioning_generation = 7
    records = {}
    for class_name in (
        "jwt-signing-keys-jwks",
        "postgres-application-credentials",
        "backup-object-store-credentials",
        "asset-store-credentials",
        "operator-credentials",
    ):
        record = {
            "targetEnvironment": "staging",
            "credentialClass": class_name,
            "bootstrapOperationId": bootstrap_operation_id,
            "provisioningGeneration": provisioning_generation,
        }
        record["immutableArtifactId"] = evidence_digest(record)
        records[class_name] = record
    return {
        "environment": "staging",
        "bootstrapOperationId": bootstrap_operation_id,
        "provisioningGeneration": provisioning_generation,
        "records": records,
    }


def write_secret_evidence(evidence, staging_record=None, staging_record_path=None):
    secret_evidence_path.write_text(json.dumps(evidence), encoding="utf-8")
    evidence_ref = (
        str(secret_evidence_path.relative_to(promotion_root))
        + "#"
        + module.canonical_evidence_digest(evidence)
    )
    if staging_record is not None:
        staging_record["secretComplianceEvidenceRef"] = evidence_ref
        if staging_record_path is not None:
            staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")
    return evidence_ref


secret_compliance_ref = write_secret_evidence(make_secret_evidence())
staging_event_id = "55555555-5555-4555-8555-555555555555"
jwt_custody_proof = {
    "proofId": "PREFLIGHT-JWT-INTERIM-001",
    "custodyMode": "INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK",
    "contractVersion": 1,
}
rotation_evidence = {
    "policyId": "PREFLIGHT-JWT-ROTATION-001",
    "status": "pass",
    "deploymentEventId": staging_event_id,
    "jwtCustodyProof": jwt_custody_proof,
}
rotation_evidence_path = (
    promotion_root
    / "design/operations/deployments/staging/jwt-rotation"
    / f"{staging_event_id}.json"
)
rotation_evidence_path.parent.mkdir(parents=True, exist_ok=True)
rotation_evidence_path.write_text(json.dumps(rotation_evidence), encoding="utf-8")
rotation_evidence_ref = (
    str(rotation_evidence_path.relative_to(promotion_root))
    + "#"
    + module.canonical_evidence_digest(rotation_evidence)
)
staging_dir = (
    promotion_root
    / "design/operations/deployments/staging/deployments"
    / staging_sha
)
staging_dir.mkdir(parents=True)
staging_preflight_path = (
    promotion_root
    / "design/operations/deployments/staging/preflight"
    / staging_sha
    / f"{staging_event_id}.json"
)
staging_preflight_path.parent.mkdir(parents=True)
staging_requirements = module.expected_preflight_policy_requirements("staging", None)
staging_preflight_path.write_text(
    json.dumps(
        {
            "environment": "staging",
            "expectedBindingsRef": expected_bindings_ref,
            "expectedBindingsDigest": expected_bindings_digest,
            "deploymentRef": {"overlayCommitSha": staging_sha},
            "deploymentEventId": staging_event_id,
            "trafficOpenEvent": None,
            "policyCatalogVersion": module.PREFLIGHT_POLICY_CATALOG_VERSION,
            "startedAt": past_timestamp,
            "completedAt": past_timestamp,
            "toolVersion": "preflight.py-v1",
            "context": "operator",
            "jwtCustodyProof": jwt_custody_proof,
            "checkResults": [
                {
                    "policyId": policy_id,
                    "category": module.PREFLIGHT_POLICY_CATALOG[policy_id],
                    "required": required,
                    "status": "pass" if required else "not_applicable",
                    "message": "contract evidence",
                }
                for policy_id, required in staging_requirements.items()
            ]
            + [
                {
                    "policyId": "PREFLIGHT-JWT-INTERIM-001",
                    "category": module.PREFLIGHT_POLICY_CATALOG["PREFLIGHT-JWT-INTERIM-001"],
                    "required": True,
                    "status": "pass",
                    "message": "contract evidence",
                },
                {
                    "policyId": "PREFLIGHT-JWT-ROTATION-001",
                    "category": module.PREFLIGHT_POLICY_CATALOG["PREFLIGHT-JWT-ROTATION-001"],
                    "required": True,
                    "status": "pass",
                    "message": "contract evidence",
                }
            ],
        }
    ),
    encoding="utf-8",
)
promotion_smoke_evidence_path = promotion_root / smoke_evidence_ref
promotion_smoke_evidence_path.parent.mkdir(parents=True)
promotion_smoke_evidence = json.loads(smoke_evidence_path.read_text(encoding="utf-8"))
promotion_smoke_evidence["deploymentRef"] = staging_sha
promotion_smoke_evidence["deploymentEventId"] = staging_event_id
promotion_smoke_evidence["logPipelineQueryability"]["selectedProfile"] = "staging"
promotion_smoke_evidence_path.write_text(json.dumps(promotion_smoke_evidence), encoding="utf-8")
promotion_smoke_entry = {
    "ref": smoke_evidence_ref,
    "contentDigest": "sha256:" + hashlib.sha256(promotion_smoke_evidence_path.read_bytes()).hexdigest(),
}
second_promotion_smoke_path = promotion_root / "evidence/player-experience-smoke-second.json"
second_promotion_smoke_path.write_bytes(promotion_smoke_evidence_path.read_bytes())
second_promotion_smoke_entries = [
    promotion_smoke_entry,
    {
        "ref": str(second_promotion_smoke_path.relative_to(promotion_root)),
        "contentDigest": "sha256:" + hashlib.sha256(second_promotion_smoke_path.read_bytes()).hexdigest(),
    },
]
shared_event_status, shared_event_message = module.validate_promotion_smoke_evidence(
    promotion_root,
    second_promotion_smoke_entries,
    "Multiple-event smokeEvidence",
    staging_sha,
    staging_event_id,
)
if shared_event_status != "pass":
    raise SystemExit(
        "multiple smoke artifacts sharing the selected deployment event were rejected: "
        + shared_event_message
    )

malformed_queryability_bindings = copy.deepcopy(staging_expected_data)
malformed_queryability_bindings["observability"]["logPipelineQueryability"]["capability"] = []
staging_expected_bindings.write_text(
    yaml.safe_dump(malformed_queryability_bindings, sort_keys=False), encoding="utf-8"
)
malformed_queryability_status, malformed_queryability_message = module.validate_promotion_smoke_evidence(
    promotion_root,
    [promotion_smoke_entry],
    "Malformed queryability smokeEvidence",
    staging_sha,
    staging_event_id,
)
staging_expected_bindings.write_text(
    yaml.safe_dump(staging_expected_data, sort_keys=False), encoding="utf-8"
)
if (
    malformed_queryability_status != "fail"
    or "capability must be a string" not in malformed_queryability_message
):
    raise SystemExit(
        "unhashable staging queryability capability did not fail closed: "
        + malformed_queryability_message
    )

promotion_baseline_smoke_path = promotion_root / "evidence/recovery-baseline-smoke.json"
promotion_baseline_smoke_path.write_bytes(smoke_evidence_path.read_bytes())
promotion_baseline_smoke_entry = {
    "ref": str(promotion_baseline_smoke_path.relative_to(promotion_root)),
    "contentDigest": "sha256:" + hashlib.sha256(promotion_baseline_smoke_path.read_bytes()).hexdigest(),
}
staging_record = {
    "environment": "staging",
    "overlayCommitSha": staging_sha,
    "deploymentEventId": staging_event_id,
    "appliedAt": past_timestamp,
    "appliedBy": "preflight-contract",
    "deployStatus": "pass",
    "smokeStatus": "pass",
    "serviceDigests": {"spring-cloud-gateway": gateway_image, "account-service": account_image},
    "expectedBindingsRef": expected_bindings_ref,
    "expectedBindingsDigest": expected_bindings_digest,
    "jwtCustodyProof": jwt_custody_proof,
    "jwtRotationEvidenceRef": rotation_evidence_ref,
    "preflightReportPath": str(staging_preflight_path.relative_to(promotion_root)),
    "liveStateEvidence": {
        "status": "pass",
        "observedOverlaySha": staging_sha,
        "observedDigests": {"spring-cloud-gateway": gateway_image, "account-service": account_image},
    },
    "secretComplianceSnapshotAt": past_timestamp,
    "secretComplianceStatus": "pass",
    "secretComplianceEvidenceRef": secret_compliance_ref,
    "smokeEvidence": [promotion_smoke_entry],
}
staging_record_path = staging_dir / f"{staging_event_id}.json"
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")
promotion_recovery_dir = promotion_root / "design/operations/deployments/production/recovery"
promotion_recovery_dir.mkdir(parents=True)
promotion_baseline = {**valid_baseline, "smokeEvidence": [promotion_baseline_smoke_entry]}
(promotion_recovery_dir / "baseline.json").write_text(json.dumps(promotion_baseline), encoding="utf-8")
promotion_verified_point_dir = promotion_root / module.VERIFIED_RESTORABLE_POINT_DIRECTORY
promotion_verified_point_dir.mkdir(parents=True)
promotion_verified_point_path = promotion_verified_point_dir / "current.json"
promotion_verified_point_path.write_text(json.dumps(readiness_point), encoding="utf-8")
promotion_compatibility = compatibility_result("compatible")
promotion_compatibility["newestVerifiedRestorablePointRef"] = str(
    promotion_verified_point_path.relative_to(promotion_root)
)
promotion_attestation_path = promotion_root / "promotion-attestation.json"
promotion_attestation_path.write_text(
    json.dumps(
        {
            "attestationVersion": "v1",
            "environment": "staging",
            "stagingOverlayCommitSha": staging_sha,
            "stagingDeploymentEventId": staging_event_id,
            "jwtCustodyProof": jwt_custody_proof,
            "jwtRotationEvidenceRef": rotation_evidence_ref,
            "productionOverlayRef": "contract-production",
            "serviceDigests": {"spring-cloud-gateway": gateway_image, "account-service": account_image},
            "smokeEvidence": [promotion_smoke_entry],
            "generatedAt": past_timestamp,
            "approvedBy": "preflight-contract",
            "rollbackMode": "rollback-compatible",
            "recoveryCompatibility": promotion_compatibility,
        }
    ),
    encoding="utf-8",
)
promotion_status, promotion_mode, promotion_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if (
    promotion_status != "pass"
    or promotion_mode != "rollback-compatible"
):
    raise SystemExit(f"valid rollback-compatible promotion did not pass: {promotion_message}")
original_record_bytes = staging_record_path.read_bytes()

duplicate_attestation_path = promotion_root / "duplicate-promotion-attestation.json"
duplicate_attestation_path.write_bytes(
    promotion_attestation_path.read_bytes().replace(
        b'"approvedBy": "preflight-contract"',
        b'"approvedBy": "preflight-contract", "approvedBy": "duplicate"',
        1,
    )
)
duplicate_attestation_status, _, duplicate_attestation_message, _, _ = module.promotion_check(
    duplicate_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if duplicate_attestation_status != "fail" or "duplicate JSON member: approvedBy" not in duplicate_attestation_message:
    raise SystemExit(
        "duplicate promotion-attestation member did not fail closed: "
        + duplicate_attestation_message
    )

duplicate_record_path = staging_dir / "duplicate-record.json"
duplicate_record_path.write_bytes(
    staging_record_path.read_bytes().replace(
        b'"appliedBy": "preflight-contract"',
        b'"appliedBy": "preflight-contract", "appliedBy": "duplicate"',
        1,
    )
)
duplicate_record_status, _, duplicate_record_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if duplicate_record_status != "pass":
    raise SystemExit(
        "duplicate staging deployment record fixture setup unexpectedly changed the canonical record: "
        + duplicate_record_message
    )
staging_record_path.write_bytes(duplicate_record_path.read_bytes())
duplicate_record_status, _, duplicate_record_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if duplicate_record_status != "fail" or "duplicate JSON member: appliedBy" not in duplicate_record_message:
    raise SystemExit(
        "duplicate staging deployment-record member did not fail closed: "
        + duplicate_record_message
    )
staging_record_path.write_bytes(original_record_bytes)

original_load_immutable_json_evidence = module.load_immutable_json_evidence
module.load_immutable_json_evidence = lambda *args, **kwargs: (None, None)
missing_secret_status, missing_secret_mode, missing_secret_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if (
    missing_secret_status != "fail"
    or missing_secret_mode != "rollback-compatible"
    or "secretComplianceEvidenceRef loader returned no evidence" not in missing_secret_message
):
    raise SystemExit(
        "missing secret evidence without a loader error did not fail closed: "
        + missing_secret_message
    )

def load_missing_rotation_evidence(*args, **kwargs):
    if args[-1] == "jwtRotationEvidenceRef":
        return None, None
    return original_load_immutable_json_evidence(*args, **kwargs)

module.load_immutable_json_evidence = load_missing_rotation_evidence
missing_rotation_status, missing_rotation_mode, missing_rotation_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
module.load_immutable_json_evidence = original_load_immutable_json_evidence
if (
    missing_rotation_status != "fail"
    or missing_rotation_mode != "rollback-compatible"
    or "jwtRotationEvidenceRef loader returned no evidence" not in missing_rotation_message
):
    raise SystemExit(
        "missing rotation evidence without a loader error did not fail closed: "
        + missing_rotation_message
    )

promotion_attestation_data = json.loads(promotion_attestation_path.read_text(encoding="utf-8"))
valid_smoke_evidence = json.loads(promotion_smoke_evidence_path.read_text(encoding="utf-8"))
valid_promotion_smoke_bytes = promotion_smoke_evidence_path.read_bytes()

independent_omitted_smoke = {
    **valid_smoke_evidence,
    "externalAuthority": {
        "profile": "independent-omitted",
        "reason": "contract test omits independent external authority",
        "exposedPublicPlayerPaths": ["websocket", "telnet"],
    },
}
independent_omitted_smoke["mirroredSignals"] = {
    **independent_omitted_smoke["mirroredSignals"],
}
independent_omitted_smoke["mirroredSignals"].pop(
    "observability_deadman_heartbeat_timestamp_seconds", None
)
promotion_smoke_evidence_path.write_text(json.dumps(independent_omitted_smoke), encoding="utf-8")
independent_omitted_entry = {
    "ref": smoke_evidence_ref,
    "contentDigest": "sha256:" + hashlib.sha256(promotion_smoke_evidence_path.read_bytes()).hexdigest(),
}
independent_omitted_smoke_bytes = promotion_smoke_evidence_path.read_bytes()
promotion_attestation_path.write_text(
    json.dumps({**promotion_attestation_data, "smokeEvidence": [independent_omitted_entry]}),
    encoding="utf-8",
)
staging_record_path.write_text(
    json.dumps({**staging_record, "smokeEvidence": [independent_omitted_entry]}),
    encoding="utf-8",
)
independent_omitted_status, _, independent_omitted_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if (
    independent_omitted_status != "fail"
    or "independent-omitted cannot satisfy promotion smoke evidence" not in independent_omitted_message
):
    raise SystemExit(
        "independent-omitted promotion smoke evidence was accepted: "
        + independent_omitted_message
    )

queryability_omitted_smoke = {
    **valid_smoke_evidence,
    "logPipelineQueryability": {
        "selectedProfile": "staging",
        "capability": "log-queryability-omitted",
        "result": "not_applicable",
        "omissionReason": "contract test omits indexed queryability",
        "evidenceObservedAt": valid_smoke_evidence["verifiedAt"],
        "evidenceFreshnessBudgetSeconds": 7200,
        "evidenceExpiresAt": (
            module.dt.datetime.fromisoformat(
                valid_smoke_evidence["verifiedAt"].replace("Z", "+00:00")
            )
            + module.dt.timedelta(hours=2)
        ).isoformat().replace("+00:00", "Z"),
        "evidenceRef": "query-proof://staging/queryability-omitted",
    },
}
promotion_smoke_evidence_path.write_text(json.dumps(queryability_omitted_smoke), encoding="utf-8")
queryability_omitted_entry = {
    "ref": smoke_evidence_ref,
    "contentDigest": "sha256:" + hashlib.sha256(promotion_smoke_evidence_path.read_bytes()).hexdigest(),
}
promotion_attestation_path.write_text(
    json.dumps({**promotion_attestation_data, "smokeEvidence": [queryability_omitted_entry]}),
    encoding="utf-8",
)
staging_record_path.write_text(
    json.dumps({**staging_record, "smokeEvidence": [queryability_omitted_entry]}),
    encoding="utf-8",
)
queryability_omitted_status, _, queryability_omitted_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if (
    queryability_omitted_status != "fail"
    or "does not match the target environment binding" not in queryability_omitted_message
):
    raise SystemExit(
        "omitted queryability promotion smoke evidence was accepted: "
        + queryability_omitted_message
    )

profile_mismatch_smoke = copy.deepcopy(valid_smoke_evidence)
profile_mismatch_smoke["logPipelineQueryability"]["selectedProfile"] = "hobby-self-hosted"
promotion_smoke_evidence_path.write_text(json.dumps(profile_mismatch_smoke), encoding="utf-8")
profile_mismatch_entry = {
    "ref": smoke_evidence_ref,
    "contentDigest": "sha256:" + hashlib.sha256(promotion_smoke_evidence_path.read_bytes()).hexdigest(),
}
profile_mismatch_status, profile_mismatch_message = module.validate_promotion_smoke_evidence(
    promotion_root,
    [profile_mismatch_entry],
    "Profile-mismatch smokeEvidence",
    staging_sha,
    staging_event_id,
    evaluation_time=now,
)
if profile_mismatch_status != "fail" or "does not match the target environment binding" not in profile_mismatch_message:
    raise SystemExit("queryability profile mismatch was accepted: " + profile_mismatch_message)

stale_promotion_smoke = copy.deepcopy(valid_smoke_evidence)
stale_promotion_smoke["verifiedAt"] = past_timestamp
stale_promotion_smoke["externalAuthority"]["evidenceObservedAt"] = past_timestamp
stale_promotion_smoke["externalAuthority"]["lastSuccessfulHeartbeatObservedAt"] = past_timestamp
stale_promotion_smoke["externalAuthority"]["publicPathChecks"]["websocket"]["lastSuccessfulProbeObservedAt"] = past_timestamp
stale_promotion_smoke["externalAuthority"]["publicPathChecks"]["telnet"]["lastSuccessfulProbeObservedAt"] = past_timestamp
stale_promotion_smoke["logPipelineQueryability"]["evidenceObservedAt"] = past_timestamp
stale_promotion_smoke["logPipelineQueryability"]["evidenceExpiresAt"] = (now - module.dt.timedelta(minutes=1)).isoformat().replace("+00:00", "Z")
stale_promotion_smoke["mirroredSignals"]["observability_deadman_heartbeat_timestamp_seconds"]["value"] = past_epoch
promotion_smoke_evidence_path.write_text(json.dumps(stale_promotion_smoke), encoding="utf-8")
stale_promotion_entry = {
    "ref": smoke_evidence_ref,
    "contentDigest": "sha256:" + hashlib.sha256(promotion_smoke_evidence_path.read_bytes()).hexdigest(),
}
stale_promotion_status, stale_promotion_message = module.validate_promotion_smoke_evidence(
    promotion_root,
    [stale_promotion_entry],
    "Stale smokeEvidence",
    staging_sha,
    staging_event_id,
    evaluation_time=now,
)
if stale_promotion_status != "fail" or "trusted evaluation time" not in stale_promotion_message:
    raise SystemExit("stale promotion smoke evidence was accepted: " + stale_promotion_message)

promotion_smoke_evidence_path.write_bytes(independent_omitted_smoke_bytes)
recovery_independent_omitted_path = promotion_recovery_dir / "independent-omitted-baseline.json"
recovery_independent_omitted_smoke = copy.deepcopy(independent_omitted_smoke)
recovery_independent_omitted_smoke["logPipelineQueryability"]["selectedProfile"] = "production"
recovery_independent_omitted_smoke_path = promotion_root / "evidence/recovery-independent-omitted-smoke.json"
recovery_independent_omitted_smoke_path.write_text(
    json.dumps(recovery_independent_omitted_smoke), encoding="utf-8"
)
recovery_independent_omitted_entry = {
    "ref": str(recovery_independent_omitted_smoke_path.relative_to(promotion_root)),
    "contentDigest": "sha256:" + hashlib.sha256(recovery_independent_omitted_smoke_path.read_bytes()).hexdigest(),
}
recovery_independent_omitted = {**valid_baseline, "smokeEvidence": [recovery_independent_omitted_entry]}
recovery_independent_omitted_path.write_text(json.dumps(recovery_independent_omitted), encoding="utf-8")
recovery_independent_omitted_status, recovery_independent_omitted_message = module.validate_recovery_baseline(
    promotion_root,
    str(recovery_independent_omitted_path.relative_to(promotion_root)),
    "sha256:recovery-contract",
    now,
    now,
)
if recovery_independent_omitted_status != "pass":
    raise SystemExit(
        "independent-omitted recovery-baseline smoke evidence was rejected: "
        + recovery_independent_omitted_message
    )

promotion_smoke_evidence_path.write_bytes(valid_promotion_smoke_bytes)
promotion_attestation_path.write_text(json.dumps(promotion_attestation_data), encoding="utf-8")
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")

malformed_entry_attestation = {
    **promotion_attestation_data,
    "smokeEvidence": [smoke_evidence_ref],
}
malformed_entry_path = promotion_root / "malformed-smoke-entry-attestation.json"
malformed_entry_path.write_text(json.dumps(malformed_entry_attestation), encoding="utf-8")
malformed_entry_status, _, malformed_entry_message, _, _ = module.promotion_check(
    malformed_entry_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if malformed_entry_status != "fail" or "exactly ref and contentDigest" not in malformed_entry_message:
    raise SystemExit(f"string smoke evidence entry was accepted: {malformed_entry_message}")

extra_entry_attestation = {
    **promotion_attestation_data,
    "smokeEvidence": [{**promotion_smoke_entry, "extra": "not-allowed"}],
}
extra_entry_path = promotion_root / "extra-smoke-entry-attestation.json"
extra_entry_path.write_text(json.dumps(extra_entry_attestation), encoding="utf-8")
extra_entry_status, _, extra_entry_message, _, _ = module.promotion_check(
    extra_entry_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if extra_entry_status != "fail" or "exactly ref and contentDigest" not in extra_entry_message:
    raise SystemExit(f"extra smoke evidence entry field was accepted: {extra_entry_message}")

wrong_digest_attestation = {
    **promotion_attestation_data,
    "smokeEvidence": [{**promotion_smoke_entry, "contentDigest": "sha256:" + "0" * 64}],
}
wrong_digest_path = promotion_root / "wrong-smoke-digest-attestation.json"
wrong_digest_path.write_text(json.dumps(wrong_digest_attestation), encoding="utf-8")
wrong_digest_status, _, wrong_digest_message, _, _ = module.promotion_check(
    wrong_digest_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if wrong_digest_status != "fail" or "contentDigest does not match" not in wrong_digest_message:
    raise SystemExit(f"wrong smoke evidence content digest was accepted: {wrong_digest_message}")

promotion_smoke_evidence_path.write_text(
    json.dumps({**valid_smoke_evidence, "verifiedBy": "tampered-smoke-evidence"}),
    encoding="utf-8",
)
tampered_bytes_status, _, tampered_bytes_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if tampered_bytes_status != "fail" or "contentDigest does not match" not in tampered_bytes_message:
    raise SystemExit(f"tampered smoke evidence bytes were accepted: {tampered_bytes_message}")
promotion_smoke_evidence_path.write_bytes(valid_promotion_smoke_bytes)

missing_smoke_attestation = {
    **promotion_attestation_data,
    "smokeEvidence": [
        {"ref": "missing/player-experience-smoke.json", "contentDigest": "sha256:" + "a" * 64}
    ],
}
missing_smoke_path = promotion_root / "missing-smoke-attestation.json"
missing_smoke_path.write_text(json.dumps(missing_smoke_attestation), encoding="utf-8")
missing_smoke_status, _, missing_smoke_message, _, _ = module.promotion_check(
    missing_smoke_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if missing_smoke_status != "fail" or "retained evidence file not found" not in missing_smoke_message:
    raise SystemExit(f"missing smoke evidence was accepted: {missing_smoke_message}")

opaque_smoke_attestation = {
    **promotion_attestation_data,
    "smokeEvidence": [
        {"ref": "https://monitoring.example/smoke/contract", "contentDigest": "sha256:" + "a" * 64}
    ],
}
opaque_smoke_path = promotion_root / "opaque-smoke-attestation.json"
opaque_smoke_path.write_text(json.dumps(opaque_smoke_attestation), encoding="utf-8")
opaque_smoke_status, _, opaque_smoke_message, _, _ = module.promotion_check(
    opaque_smoke_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if opaque_smoke_status != "fail" or "retained evidence file not found" not in opaque_smoke_message:
    raise SystemExit(f"opaque smoke evidence was accepted: {opaque_smoke_message}")
write_secret_evidence(
    make_bootstrap_secret_evidence(),
    staging_record=staging_record,
    staging_record_path=staging_record_path,
)
original_attestation_bytes = promotion_attestation_path.read_bytes()
original_record_bytes = staging_record_path.read_bytes()
original_preflight_bytes = staging_preflight_path.read_bytes()
bootstrap_attestation = json.loads(promotion_attestation_path.read_text(encoding="utf-8"))
bootstrap_record = json.loads(staging_record_path.read_text(encoding="utf-8"))
promotion_attestation_path.write_text(json.dumps(bootstrap_attestation), encoding="utf-8")
staging_record_path.write_text(json.dumps(bootstrap_record), encoding="utf-8")
bootstrap_promotion_status, _, bootstrap_promotion_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if bootstrap_promotion_status != "pass":
    raise SystemExit(
        "bootstrap secret compliance evidence did not coexist with rotation evidence: "
        + bootstrap_promotion_message
    )

bootstrap_attestation.pop("jwtRotationEvidenceRef", None)
promotion_attestation_path.write_text(json.dumps(bootstrap_attestation), encoding="utf-8")
missing_attestation_status, _, missing_attestation_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if (
    missing_attestation_status != "fail"
    or "jwtRotationEvidenceRef" not in missing_attestation_message
):
    raise SystemExit(
        "bootstrap promotion without attestation rotation evidence was accepted: "
        + missing_attestation_message
    )

promotion_attestation_path.write_bytes(original_attestation_bytes)
bootstrap_record.pop("jwtRotationEvidenceRef", None)
staging_record_path.write_text(json.dumps(bootstrap_record), encoding="utf-8")
missing_record_status, _, missing_record_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if (
    missing_record_status != "fail"
    or "Staging deployment record missing required canonical fields: jwtRotationEvidenceRef"
    not in missing_record_message
):
    raise SystemExit(
        "bootstrap staging record without rotation evidence was accepted: "
        + missing_record_message
    )

promotion_attestation_path.write_bytes(original_attestation_bytes)
bootstrap_record["jwtRotationEvidenceRef"] = rotation_evidence_ref + "-mismatch"
staging_record_path.write_text(json.dumps(bootstrap_record), encoding="utf-8")
mismatched_record_status, _, mismatched_record_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if (
    mismatched_record_status != "fail"
    or "jwtRotationEvidenceRef does not match the attestation" not in mismatched_record_message
):
    raise SystemExit(
        "bootstrap staging record with mismatched rotation evidence was accepted: "
        + mismatched_record_message
    )

promotion_attestation_path.write_bytes(original_attestation_bytes)
staging_record_path.write_bytes(original_record_bytes)
staging_record = json.loads(original_record_bytes)
bootstrap_mismatch = make_bootstrap_secret_evidence()
bootstrap_mismatch["records"]["operator-credentials"]["provisioningGeneration"] = 8
bootstrap_mismatch["records"]["operator-credentials"]["immutableArtifactId"] = evidence_digest(
    bootstrap_mismatch["records"]["operator-credentials"]
)
write_secret_evidence(
    bootstrap_mismatch,
    staging_record=staging_record,
    staging_record_path=staging_record_path,
)
bootstrap_mismatch_status, _, bootstrap_mismatch_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if (
    bootstrap_mismatch_status != "fail"
    or "provisioningGeneration mismatch" not in bootstrap_mismatch_message
):
    raise SystemExit(
        "bootstrap provisioning generation mismatch was accepted: "
        + bootstrap_mismatch_message
    )
promotion_attestation_path.write_bytes(original_attestation_bytes)
staging_record_path.write_bytes(original_record_bytes)
staging_preflight_path.write_bytes(original_preflight_bytes)
staging_record = json.loads(original_record_bytes)
write_secret_evidence(
    make_secret_evidence(),
    staging_record=staging_record,
    staging_record_path=staging_record_path,
)

base_attestation = json.loads(promotion_attestation_path.read_text(encoding="utf-8"))
base_preflight_report = json.loads(staging_preflight_path.read_text(encoding="utf-8"))


def verify_jwt_lineage_failure(
    case_name,
    mutate_attestation=None,
    mutate_record=None,
    mutate_rotation=None,
    mutate_preflight=None,
    expected_fragment="",
):
    attestation = copy.deepcopy(base_attestation)
    record = copy.deepcopy(staging_record)
    rotation = copy.deepcopy(rotation_evidence)
    preflight = copy.deepcopy(base_preflight_report)
    if mutate_attestation:
        mutate_attestation(attestation)
    if mutate_record:
        mutate_record(record)
    if mutate_rotation:
        mutate_rotation(rotation)
        rotation_evidence_path.write_text(json.dumps(rotation), encoding="utf-8")
        rotation_ref = (
            str(rotation_evidence_path.relative_to(promotion_root))
            + "#"
            + module.canonical_evidence_digest(rotation)
        )
        attestation["jwtRotationEvidenceRef"] = rotation_ref
        record["jwtRotationEvidenceRef"] = rotation_ref
    else:
        rotation_evidence_path.write_text(json.dumps(rotation), encoding="utf-8")
    if mutate_preflight:
        mutate_preflight(preflight)
    staging_preflight_path.write_text(json.dumps(preflight), encoding="utf-8")
    promotion_attestation_path.write_text(json.dumps(attestation), encoding="utf-8")
    staging_record_path.write_text(json.dumps(record), encoding="utf-8")
    status, _, message, _, _ = module.promotion_check(
        promotion_attestation_path,
        [gateway_image, account_image],
        promotion_root,
        expected_production_overlay_ref="contract-production",
    )
    if status != "fail" or expected_fragment not in message:
        raise SystemExit(f"{case_name}: JWT lineage failure was not enforced: {message}")


verify_jwt_lineage_failure(
    "missing-expected-bindings-digest",
    mutate_record=lambda record: record.pop("expectedBindingsDigest"),
    expected_fragment="Staging deployment record missing required canonical fields",
)
verify_jwt_lineage_failure(
    "mismatched-expected-bindings-digest",
    mutate_record=lambda record: record.__setitem__(
        "expectedBindingsDigest", "sha256:" + "0" * 64
    ),
    expected_fragment="Staging deployment record expectedBindingsDigest mismatch",
)
verify_jwt_lineage_failure(
    "mismatched-preflight-expected-bindings-digest",
    mutate_preflight=lambda preflight: preflight.__setitem__(
        "expectedBindingsDigest", "sha256:" + "1" * 64
    ),
    expected_fragment="preflight report expectedBindingsDigest mismatch",
)
original_expected_bindings_bytes = staging_expected_bindings.read_bytes()
promotion_attestation_path.write_bytes(original_attestation_bytes)
write_secret_evidence(
    make_secret_evidence(),
    staging_record=staging_record,
    staging_record_path=staging_record_path,
)
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")
staging_preflight_path.write_bytes(original_preflight_bytes)
staging_expected_bindings.write_bytes(original_expected_bindings_bytes + b"\n# changed bytes\n")
changed_manifest_status, _, changed_manifest_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if changed_manifest_status != "fail" or "expectedBindingsDigest mismatch" not in changed_manifest_message:
    raise SystemExit(
        "changed expected-bindings bytes were accepted: " + changed_manifest_message
    )
staging_expected_bindings.write_bytes(original_expected_bindings_bytes)


verify_jwt_lineage_failure(
    "missing-attestation-custody-proof",
    mutate_attestation=lambda attestation: attestation.pop("jwtCustodyProof"),
    expected_fragment="Attestation missing required canonical fields",
)
verify_jwt_lineage_failure(
    "missing-record-custody-proof",
    mutate_record=lambda record: record.pop("jwtCustodyProof"),
    expected_fragment="Staging deployment record missing required canonical fields",
)
verify_jwt_lineage_failure(
    "mismatched-custody-proof",
    mutate_attestation=lambda attestation: attestation.__setitem__(
        "jwtCustodyProof",
        {
            "proofId": "PREFLIGHT-JWT-002",
            "custodyMode": "TARGET_NON_EXPORTABLE_SIGNER",
            "contractVersion": 1,
        },
    ),
    expected_fragment="jwtCustodyProof does not match the attestation",
)
verify_jwt_lineage_failure(
    "invalid-custody-proof-tuple",
    mutate_attestation=lambda attestation: attestation.__setitem__(
        "jwtCustodyProof",
        {
            "proofId": "PREFLIGHT-JWT-ROTATION-001",
            "custodyMode": "INVALID",
            "contractVersion": 1,
        },
    ),
    mutate_record=lambda record: record.__setitem__(
        "jwtCustodyProof",
        {
            "proofId": "PREFLIGHT-JWT-ROTATION-001",
            "custodyMode": "INVALID",
            "contractVersion": 1,
        },
    ),
    expected_fragment="does not select an accepted JWT custody proof tuple",
)
verify_jwt_lineage_failure(
    "extra-custody-proof-field",
    mutate_attestation=lambda attestation: attestation["jwtCustodyProof"].__setitem__(
        "unexpected", True
    ),
    expected_fragment="must contain exactly proofId, custodyMode, and contractVersion",
)
for invalid_contract_version in (True, 1.0):
    verify_jwt_lineage_failure(
        f"invalid-contract-version-{invalid_contract_version!r}",
        mutate_attestation=lambda attestation, version=invalid_contract_version: attestation[
            "jwtCustodyProof"
        ].__setitem__("contractVersion", version),
        expected_fragment="contractVersion must be an integer",
    )
verify_jwt_lineage_failure(
    "missing-selected-custody-policy",
    mutate_preflight=lambda preflight: preflight.__setitem__(
        "checkResults",
        [
            check
            for check in preflight["checkResults"]
            if check["policyId"] != "PREFLIGHT-JWT-INTERIM-001"
        ],
    ),
    expected_fragment="one passing required result for the selected JWT custody policy",
)
verify_jwt_lineage_failure(
    "failed-selected-custody-policy",
    mutate_preflight=lambda preflight: next(
        check
        for check in preflight["checkResults"]
        if check["policyId"] == "PREFLIGHT-JWT-INTERIM-001"
    ).__setitem__("status", "fail"),
    expected_fragment="one passing required result for the selected JWT custody policy",
)
verify_jwt_lineage_failure(
    "alternate-custody-policy",
    mutate_preflight=lambda preflight: preflight["checkResults"].append(
        {
            "policyId": "PREFLIGHT-JWT-002",
            "category": module.PREFLIGHT_POLICY_CATALOG["PREFLIGHT-JWT-002"],
            "required": True,
            "status": "pass",
            "message": "alternate custody must be rejected",
        }
    ),
    expected_fragment="unknown policy IDs",
)
verify_jwt_lineage_failure(
    "missing-attestation-rotation-ref",
    mutate_attestation=lambda attestation: attestation.pop("jwtRotationEvidenceRef"),
    expected_fragment="Attestation missing required canonical fields",
)
verify_jwt_lineage_failure(
    "mismatched-rotation-ref",
    mutate_attestation=lambda attestation: attestation.__setitem__(
        "jwtRotationEvidenceRef", rotation_evidence_ref + "-mismatch"
    ),
    expected_fragment="jwtRotationEvidenceRef does not match the attestation",
)
verify_jwt_lineage_failure(
    "wrong-rotation-event",
    mutate_rotation=lambda rotation: rotation.__setitem__(
        "deploymentEventId", "88888888-8888-4888-8888-888888888888"
    ),
    expected_fragment="deploymentEventId does not match the staging event",
)
verify_jwt_lineage_failure(
    "wrong-rotation-policy",
    mutate_rotation=lambda rotation: rotation.__setitem__("policyId", "PREFLIGHT-JWT-002"),
    expected_fragment="policyId must be PREFLIGHT-JWT-ROTATION-001",
)
verify_jwt_lineage_failure(
    "wrong-rotation-status",
    mutate_rotation=lambda rotation: rotation.__setitem__("status", "fail"),
    expected_fragment="evidence status must be pass",
)
verify_jwt_lineage_failure(
    "non-immutable-rotation-ref",
    mutate_attestation=lambda attestation: attestation.__setitem__(
        "jwtRotationEvidenceRef",
        "design/operations/deployments/staging/jwt-rotation/evidence.json#sha256:not-immutable",
    ),
    mutate_record=lambda record: record.__setitem__(
        "jwtRotationEvidenceRef",
        "design/operations/deployments/staging/jwt-rotation/evidence.json#sha256:not-immutable",
    ),
    expected_fragment="must use <repository-path>#sha256:<digest> format",
)
verify_jwt_lineage_failure(
    "non-immutable-secret-compliance-ref",
    mutate_record=lambda record: record.__setitem__(
        "secretComplianceEvidenceRef", secret_evidence_path.name
    ),
    expected_fragment="must use <repository-path>#sha256:<digest> format",
)

rotation_evidence_path.write_text(json.dumps(rotation_evidence), encoding="utf-8")
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")
staging_preflight_path.write_text(json.dumps(base_preflight_report), encoding="utf-8")
promotion_attestation_path.write_text(json.dumps(base_attestation), encoding="utf-8")

malformed_smoke_path = promotion_root / "evidence/malformed-player-experience-smoke.json"
malformed_smoke_path.write_text("{not-json", encoding="utf-8")
malformed_smoke_attestation = {
    **promotion_attestation_data,
    "smokeEvidence": [
        {
            "ref": str(malformed_smoke_path.relative_to(promotion_root)),
            "contentDigest": "sha256:" + hashlib.sha256(malformed_smoke_path.read_bytes()).hexdigest(),
        }
    ],
}
malformed_smoke_attestation_path = promotion_root / "malformed-smoke-attestation.json"
malformed_smoke_attestation_path.write_text(json.dumps(malformed_smoke_attestation), encoding="utf-8")
malformed_smoke_status, _, malformed_smoke_message, _, _ = module.promotion_check(
    malformed_smoke_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if malformed_smoke_status != "fail" or "retained evidence JSON unreadable" not in malformed_smoke_message:
    raise SystemExit(f"malformed smoke evidence was accepted: {malformed_smoke_message}")

simulated_smoke_evidence = copy.deepcopy(valid_smoke_evidence)
simulated_smoke_evidence["executionMode"] = "simulated"
simulated_smoke_evidence["externalAuthorityProvenance"] = "synthetic"
simulated_authority = simulated_smoke_evidence["externalAuthority"]
for authority_key, authority_record in {
    "deadman": simulated_authority["deadmanAuthority"],
    "websocket": simulated_authority["publicPathChecks"]["websocket"],
    "telnet": simulated_authority["publicPathChecks"]["telnet"],
}.items():
    for field in ("evidenceRef", "pageEvidenceRef", "target", "checkRef"):
        if field in authority_record:
            authority_record[field] = f"synthetic://preflight/{authority_key}/{field}"
promotion_smoke_evidence_path.write_text(json.dumps(simulated_smoke_evidence), encoding="utf-8")
simulated_smoke_entry = {
    "ref": smoke_evidence_ref,
    "contentDigest": "sha256:" + hashlib.sha256(promotion_smoke_evidence_path.read_bytes()).hexdigest(),
}
promotion_attestation_path.write_text(
    json.dumps({**promotion_attestation_data, "smokeEvidence": [simulated_smoke_entry]}),
    encoding="utf-8",
)
staging_record_path.write_text(
    json.dumps({**staging_record, "smokeEvidence": [simulated_smoke_entry]}),
    encoding="utf-8",
)
simulated_smoke_status, _, simulated_smoke_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if simulated_smoke_status != "fail" or "executionMode must be live" not in simulated_smoke_message:
    raise SystemExit(f"simulated smoke evidence was accepted for promotion: {simulated_smoke_message}")

simulated_recovery_baseline_path = promotion_recovery_dir / "simulated-baseline.json"
simulated_recovery_baseline_path.write_text(
    json.dumps({**valid_baseline, "smokeEvidence": [simulated_smoke_entry]}),
    encoding="utf-8",
)
simulated_recovery_status, simulated_recovery_message = module.validate_recovery_baseline(
    promotion_root,
    str(simulated_recovery_baseline_path.relative_to(promotion_root)),
    "sha256:recovery-contract",
    now,
    now,
)
if simulated_recovery_status != "fail" or "executionMode must be live" not in simulated_recovery_message:
    raise SystemExit(f"simulated smoke evidence was accepted for recovery: {simulated_recovery_message}")
promotion_smoke_evidence_path.write_bytes(valid_promotion_smoke_bytes)
promotion_attestation_path.write_text(json.dumps(promotion_attestation_data), encoding="utf-8")
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")

wrong_deployment_ref_evidence = {**valid_smoke_evidence, "deploymentRef": "other-staging-event"}
promotion_smoke_evidence_path.write_text(json.dumps(wrong_deployment_ref_evidence), encoding="utf-8")
wrong_deployment_ref_entry = {
    "ref": smoke_evidence_ref,
    "contentDigest": "sha256:" + hashlib.sha256(promotion_smoke_evidence_path.read_bytes()).hexdigest(),
}
promotion_attestation_path.write_text(
    json.dumps({**promotion_attestation_data, "smokeEvidence": [wrong_deployment_ref_entry]}),
    encoding="utf-8",
)
staging_record_path.write_text(
    json.dumps({**staging_record, "smokeEvidence": [wrong_deployment_ref_entry]}),
    encoding="utf-8",
)
wrong_deployment_ref_status, _, wrong_deployment_ref_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if wrong_deployment_ref_status != "fail" or "deploymentRef must match stagingOverlayCommitSha" not in wrong_deployment_ref_message:
    raise SystemExit(f"smoke evidence with wrong deploymentRef was accepted: {wrong_deployment_ref_message}")
promotion_smoke_evidence_path.write_bytes(valid_promotion_smoke_bytes)
promotion_attestation_path.write_text(json.dumps(promotion_attestation_data), encoding="utf-8")
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")

missing_deployment_event_evidence = {**valid_smoke_evidence}
missing_deployment_event_evidence.pop("deploymentEventId")
promotion_smoke_evidence_path.write_text(json.dumps(missing_deployment_event_evidence), encoding="utf-8")
missing_deployment_event_entry = {
    "ref": smoke_evidence_ref,
    "contentDigest": "sha256:" + hashlib.sha256(promotion_smoke_evidence_path.read_bytes()).hexdigest(),
}
promotion_attestation_path.write_text(
    json.dumps({**promotion_attestation_data, "smokeEvidence": [missing_deployment_event_entry]}),
    encoding="utf-8",
)
staging_record_path.write_text(
    json.dumps({**staging_record, "smokeEvidence": [missing_deployment_event_entry]}),
    encoding="utf-8",
)
missing_deployment_event_status, _, missing_deployment_event_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if missing_deployment_event_status != "fail" or "deploymentEventId must match stagingDeploymentEventId" not in missing_deployment_event_message:
    raise SystemExit(f"smoke evidence without deploymentEventId was accepted: {missing_deployment_event_message}")
promotion_smoke_evidence_path.write_bytes(valid_promotion_smoke_bytes)
promotion_attestation_path.write_text(json.dumps(promotion_attestation_data), encoding="utf-8")
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")

wrong_deployment_event_evidence = {**valid_smoke_evidence, "deploymentEventId": "66666666-6666-4666-8666-666666666666"}
promotion_smoke_evidence_path.write_text(json.dumps(wrong_deployment_event_evidence), encoding="utf-8")
wrong_deployment_event_entry = {
    "ref": smoke_evidence_ref,
    "contentDigest": "sha256:" + hashlib.sha256(promotion_smoke_evidence_path.read_bytes()).hexdigest(),
}
promotion_attestation_path.write_text(
    json.dumps({**promotion_attestation_data, "smokeEvidence": [wrong_deployment_event_entry]}),
    encoding="utf-8",
)
staging_record_path.write_text(
    json.dumps({**staging_record, "smokeEvidence": [wrong_deployment_event_entry]}),
    encoding="utf-8",
)
wrong_deployment_event_status, _, wrong_deployment_event_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if wrong_deployment_event_status != "fail" or "deploymentEventId must match stagingDeploymentEventId" not in wrong_deployment_event_message:
    raise SystemExit(f"smoke evidence with wrong deploymentEventId was accepted: {wrong_deployment_event_message}")
promotion_smoke_evidence_path.write_bytes(valid_promotion_smoke_bytes)
promotion_attestation_path.write_text(json.dumps(promotion_attestation_data), encoding="utf-8")
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")

# Exercise staging-lineage failures independently of the deliberately blocked
# recovery-inventory dereference boundary above.
module.validate_recovery_baseline = lambda *args, **kwargs: (
    "pass",
    "contract-only complete recovery evidence",
)

staging_record_path.write_text(
    json.dumps({**staging_record, "preflightReportPath": "staging-preflight.json"}),
    encoding="utf-8",
)
noncanonical_status, _, noncanonical_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if noncanonical_status != "fail" or "canonical preflight report path" not in noncanonical_message:
    raise SystemExit(f"noncanonical staging preflight path was accepted: {noncanonical_message}")

staging_record_path.write_text(
    json.dumps(
        {
            **staging_record,
            "deploymentEventId": "88888888-8888-4888-8888-888888888888",
        }
    ),
    encoding="utf-8",
)
mismatched_event_status, _, mismatched_event_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if mismatched_event_status != "fail" or "deploymentEventId mismatch" not in mismatched_event_message:
    raise SystemExit(f"mismatched preflight deployment event was accepted: {mismatched_event_message}")

staging_record_path.write_text(
    json.dumps(
        {
            **staging_record,
            "appliedAt": timestamp(now - module.dt.timedelta(minutes=10)),
        }
    ),
    encoding="utf-8",
)
late_report_status, _, late_report_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if late_report_status != "fail" or "later than the apply event" not in late_report_message:
    raise SystemExit(f"post-apply preflight report was accepted: {late_report_message}")

unwaived_preflight_report = json.loads(staging_preflight_path.read_text(encoding="utf-8"))
for waiver_value in (
    f"design/operations/deployments/staging/preflight/{staging_sha}/{staging_event_id}.waiver.json",
    None,
):
    waived_preflight_report = {**unwaived_preflight_report, "waiverPath": waiver_value}
    staging_preflight_path.write_text(json.dumps(waived_preflight_report), encoding="utf-8")
    staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")
    waived_report_status, _, waived_report_message, _, _ = module.promotion_check(
        promotion_attestation_path,
        [gateway_image, account_image],
        promotion_root,
        expected_production_overlay_ref="contract-production",
    )
    if waived_report_status != "fail" or "waivers are not consumable" not in waived_report_message:
        raise SystemExit(
            f"preflight report with waiverPath={waiver_value!r} was accepted: {waived_report_message}"
        )
staging_preflight_path.write_text(json.dumps(unwaived_preflight_report), encoding="utf-8")

stale_preflight_report = json.loads(staging_preflight_path.read_text(encoding="utf-8"))
stale_preflight_report["startedAt"] = timestamp(now - module.dt.timedelta(minutes=41))
stale_preflight_report["completedAt"] = timestamp(now - module.dt.timedelta(minutes=40))
staging_preflight_path.write_text(json.dumps(stale_preflight_report), encoding="utf-8")
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")
stale_report_status, _, stale_report_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if stale_report_status != "fail" or "older than the 30-minute apply window" not in stale_report_message:
    raise SystemExit(f"stale preflight report was accepted: {stale_report_message}")
staging_preflight_path.write_text(
    json.dumps(
        {
            **stale_preflight_report,
            "startedAt": past_timestamp,
            "completedAt": past_timestamp,
        }
    ),
    encoding="utf-8",
)

malformed_smoke_record = {**staging_record, "smokeEvidence": [{}]}
staging_record_path.write_text(json.dumps(malformed_smoke_record), encoding="utf-8")
malformed_smoke_status, _, malformed_smoke_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if malformed_smoke_status != "fail" or "exactly ref and contentDigest" not in malformed_smoke_message:
    raise SystemExit(f"malformed staging smoke evidence was accepted: {malformed_smoke_message}")
different_smoke_entry = {**promotion_smoke_entry, "ref": "different-smoke"}
staging_record_path.write_text(
    json.dumps({**staging_record, "smokeEvidence": [different_smoke_entry]}),
    encoding="utf-8",
)
mismatched_smoke_status, _, mismatched_smoke_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if mismatched_smoke_status != "fail" or "does not match" not in mismatched_smoke_message:
    raise SystemExit(f"mismatched staging smoke evidence was accepted: {mismatched_smoke_message}")
duplicate_smoke_record = {
    **staging_record,
    "smokeEvidence": [promotion_smoke_entry, {**promotion_smoke_entry}],
}
staging_record_path.write_text(json.dumps(duplicate_smoke_record), encoding="utf-8")
duplicate_smoke_status, _, duplicate_smoke_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if duplicate_smoke_status != "fail" or "ref must be unique" not in duplicate_smoke_message:
    raise SystemExit(f"duplicate staging smoke evidence refs were accepted: {duplicate_smoke_message}")
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")

malformed_secret_evidence = json.loads(secret_evidence_path.read_text(encoding="utf-8"))
malformed_secret_evidence["records"]["operator-credentials"]["immutableArtifactId"] = {"note": "sha256:"}
write_secret_evidence(
    malformed_secret_evidence,
    staging_record=staging_record,
    staging_record_path=staging_record_path,
)
malformed_immutable_status, _, malformed_immutable_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if malformed_immutable_status != "fail" or "not immutable" not in malformed_immutable_message:
    raise SystemExit(f"malformed immutable evidence identifier was accepted: {malformed_immutable_message}")
write_secret_evidence(
    make_secret_evidence(),
    staging_record=staging_record,
    staging_record_path=staging_record_path,
)

bad_git_attestation = json.loads(promotion_attestation_path.read_text(encoding="utf-8"))
bad_git_attestation["stagingOverlayCommitSha"] = "d" * 40
bad_git_path = promotion_root / "bad-git-attestation.json"
bad_git_path.write_text(json.dumps(bad_git_attestation), encoding="utf-8")
bad_git_status, _, bad_git_message, _, _ = module.promotion_check(
    bad_git_path,
    [gateway_image, account_image],
    promotion_root,
)
if bad_git_status != "fail" or "does not exist in Git" not in bad_git_message:
    raise SystemExit(f"unbound stagingOverlayCommitSha did not fail closed: {bad_git_message}")

legacy_attestation = write_json(
    "legacy-roll-forward-attestation.json",
    {
        "environment": "staging",
        "generatedAt": past_timestamp,
        "rollbackMode": "roll-forward-only",
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "recoveryCompatibility": {
            **compatibility_result("compatible"),
            "newDrillRequired": True,
            "backupReadinessRef": "legacy-roll-forward-readiness.json",
        },
    },
)
legacy_readiness = write_json(
    "legacy-roll-forward-readiness.json",
    {
        "environment": "production",
        "deploymentRef": "contract-roll-forward",
        "rollbackMode": "roll-forward-only",
        "promotionAttestationRef": legacy_attestation.name,
        "restorePlanRef": "legacy-restore-plan",
        "evidenceRefs": ["legacy-evidence"],
        "backupLastSuccessAt": past_timestamp,
        "backupVerifyLastSuccessAt": past_timestamp,
        "restoreDrillLastSuccessAt": past_timestamp,
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
    },
)
legacy_status, legacy_message = module.backup_readiness_check(
    legacy_readiness,
    now.isoformat().replace("+00:00", "Z"),
    "contract-roll-forward",
    tmp,
)
if legacy_status != "fail" or "required target-state fields" not in legacy_message:
    raise SystemExit(f"incomplete legacy roll-forward evidence did not fail closed: {legacy_message}")

roll_forward_deployment_sha = "b" * 40
canonical_attestation_dir = tmp / "design/operations/deployments/production/attestations"
canonical_attestation_dir.mkdir(parents=True)
future_attestation = write_json(
    "future-roll-forward-attestation.json",
    {
        "environment": "staging",
        "generatedAt": past_timestamp,
        "rollbackMode": "roll-forward-only",
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "recoveryCompatibility": {
            **compatibility_result("compatible"),
            "newDrillRequired": True,
            "backupReadinessRef": "future-roll-forward-readiness.json",
        },
    },
)
canonical_future_attestation_path = canonical_attestation_dir / f"{roll_forward_deployment_sha}.json"
canonical_future_attestation_path.write_bytes(future_attestation.read_bytes())
future_readiness = write_json(
    "future-roll-forward-readiness.json",
    {
        "environment": "production",
        "deploymentRef": roll_forward_deployment_sha,
        "promotionAttestationRef": str(canonical_future_attestation_path.relative_to(tmp)),
        "assessedAt": past_timestamp,
        "assessedBy": "preflight-contract",
        "rollbackMode": "roll-forward-only",
        "newestVerifiedRestorablePointAt": past_timestamp,
        "newestVerifiedRestorablePointRef": str(readiness_point_path.relative_to(tmp)),
        "newestVerifiedRestorablePointDigest": readiness_point["recordDigest"],
        "backupLastSuccessAt": future_timestamp,
        "backupVerifyLastSuccessAt": past_timestamp,
        "restoreDrillLastSuccessAt": past_timestamp,
        "restorePlanRef": "restore-plan",
        "restoreRecoveryRecordRef": "recovery/restore.json",
        "baselineRecoveryRecordRef": "design/operations/deployments/production/recovery/baseline.json",
        "recoveryControllerLineage": {"recoveryStatus": "finalized"},
        "backupConfidentialityEvidence": {"status": "pass"},
        "backupCoverage": "environment-wide-postgresql",
        "backupArtifactRef": verified_point["backupArtifact"]["artifactRef"],
        "artifactErasureHighWater": {"stream": "erasures", "sequence": 1},
        "initialCatchupHighWater": {"stream": "erasures", "sequence": 2},
        "restoreHighWater": {"stream": "erasures", "sequence": 3},
        "sourceServiceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:source"},
        "candidateServiceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "candidateMigrationPathRef": "migrations/candidate",
        "backupToolDigest": "sha256:backup-tool",
        "recoveryToolDigest": "sha256:recovery-tool",
        "recoveryContractFingerprint": "sha256:recovery-contract",
        "evidenceRefs": ["contract-evidence"],
    },
)
duplicate_readiness_path = tmp / "duplicate-backup-readiness.json"
duplicate_readiness_path.write_bytes(
    future_readiness.read_bytes().replace(
        b'"environment": "production"',
        b'"environment": "production", "environment": "production"',
        1,
    )
)
duplicate_readiness_status, duplicate_readiness_message = module.backup_readiness_check(
    duplicate_readiness_path,
    now.isoformat().replace("+00:00", "Z"),
    roll_forward_deployment_sha,
    tmp,
)
if duplicate_readiness_status != "fail" or "duplicate JSON member: environment" not in duplicate_readiness_message:
    raise SystemExit(
        "backup readiness accepted duplicate top-level JSON members: "
        + duplicate_readiness_message
    )
noncanonical_attestation_readiness_data = json.loads(future_readiness.read_text(encoding="utf-8"))
noncanonical_attestation_readiness_data["backupLastSuccessAt"] = past_timestamp
noncanonical_attestation_readiness_data["promotionAttestationRef"] = "../nonexistent-attestation.json"
noncanonical_attestation_readiness = write_json(
    "noncanonical-attestation-readiness.json",
    noncanonical_attestation_readiness_data,
)
noncanonical_attestation_status, noncanonical_attestation_message = module.backup_readiness_check(
    noncanonical_attestation_readiness,
    now.isoformat().replace("+00:00", "Z"),
    roll_forward_deployment_sha,
    tmp,
)
if (
    noncanonical_attestation_status != "fail"
    or "promotionAttestationRef must be the canonical repository-relative" not in noncanonical_attestation_message
):
    raise SystemExit(
        "backup readiness accepted a noncanonical promotion-attestation path: "
        + noncanonical_attestation_message
    )
duplicate_attestation_readiness_data = json.loads(future_readiness.read_text(encoding="utf-8"))
duplicate_attestation_readiness_data["backupLastSuccessAt"] = past_timestamp
duplicate_attestation_readiness_data["promotionAttestationRef"] = str(
    canonical_future_attestation_path.relative_to(tmp)
)
duplicate_attestation_readiness = write_json(
    "duplicate-attestation-readiness.json",
    duplicate_attestation_readiness_data,
)
duplicate_attestation_bytes = future_attestation.read_bytes().replace(
    b'"environment": "staging"',
    b'"environment": "staging", "environment": "duplicate"',
    1,
)
canonical_future_attestation_path.write_bytes(duplicate_attestation_bytes)
duplicate_attestation_status, duplicate_attestation_message = module.backup_readiness_check(
    duplicate_attestation_readiness,
    now.isoformat().replace("+00:00", "Z"),
    roll_forward_deployment_sha,
    tmp,
)
if duplicate_attestation_status != "fail" or "duplicate JSON member: environment" not in duplicate_attestation_message:
    raise SystemExit(
        "backup readiness accepted duplicate promotion-attestation JSON members: "
        + duplicate_attestation_message
    )
missing_restore_high_water_data = json.loads(future_readiness.read_text(encoding="utf-8"))
missing_restore_high_water_data.pop("restoreHighWater")
missing_restore_high_water = write_json(
    "missing-restore-high-water-readiness.json",
    missing_restore_high_water_data,
)
missing_restore_status, missing_restore_message = module.backup_readiness_check(
    missing_restore_high_water,
    now.isoformat().replace("+00:00", "Z"),
    roll_forward_deployment_sha,
    tmp,
)
if (
    missing_restore_status != "fail"
    or "required target-state fields" not in missing_restore_message
    or "restoreHighWater" not in missing_restore_message
):
    raise SystemExit(
        f"backup readiness without restoreHighWater did not fail closed: {missing_restore_message}"
    )

future_status, future_message = module.backup_readiness_check(
    future_readiness,
    now.isoformat().replace("+00:00", "Z"),
    roll_forward_deployment_sha,
    tmp,
)
if future_status != "fail" or "future-dated timestamps" not in future_message:
    raise SystemExit(f"future-dated backup readiness did not fail closed: {future_message}")

stale_snapshot_readiness_data = json.loads(future_readiness.read_text(encoding="utf-8"))
stale_snapshot_readiness_data["backupLastSuccessAt"] = past_timestamp
stale_snapshot_readiness_data["newestVerifiedRestorablePointAt"] = (
    now - module.dt.timedelta(minutes=20)
).isoformat().replace("+00:00", "Z")
stale_snapshot_readiness = write_json("stale-snapshot-readiness.json", stale_snapshot_readiness_data)
stale_snapshot_status, stale_snapshot_message = module.backup_readiness_check(
    stale_snapshot_readiness,
    now.isoformat().replace("+00:00", "Z"),
    roll_forward_deployment_sha,
    tmp,
)
if (
    stale_snapshot_status != "fail"
    or "newestVerifiedRestorablePointAt older than 15 minutes" not in stale_snapshot_message
    or str(readiness_point_path.relative_to(tmp)) not in stale_snapshot_message
    or "generate a new event-scoped preflight report before retrying" not in stale_snapshot_message
):
    raise SystemExit(
        "stale snapshot with newer verification did not fail the RPO gate: "
        f"{stale_snapshot_message}"
    )

mismatched_verification_readiness_data = json.loads(future_readiness.read_text(encoding="utf-8"))
mismatched_verification_readiness_data["backupLastSuccessAt"] = past_timestamp
mismatched_verification_readiness_data["backupVerifyLastSuccessAt"] = timestamp(
    now - module.dt.timedelta(minutes=7)
)
mismatched_verification_attestation_data = json.loads(future_attestation.read_text(encoding="utf-8"))
mismatched_verification_attestation_data["recoveryCompatibility"]["backupReadinessRef"] = (
    "mismatched-verification-readiness.json"
)
mismatched_verification_attestation = write_json(
    "mismatched-verification-attestation.json", mismatched_verification_attestation_data
)
mismatched_verification_attestation_path = canonical_attestation_dir / f"{roll_forward_deployment_sha}.json"
mismatched_verification_attestation_path.write_bytes(mismatched_verification_attestation.read_bytes())
mismatched_verification_readiness_data["promotionAttestationRef"] = str(
    mismatched_verification_attestation_path.relative_to(tmp)
)
mismatched_verification_readiness = write_json(
    "mismatched-verification-readiness.json", mismatched_verification_readiness_data
)
mismatched_verification_status, mismatched_verification_message = module.backup_readiness_check(
    mismatched_verification_readiness,
    now.isoformat().replace("+00:00", "Z"),
    roll_forward_deployment_sha,
    tmp,
)
if (
    mismatched_verification_status != "fail"
    or "verifiedAt does not match backupVerifyLastSuccessAt" not in mismatched_verification_message
):
    raise SystemExit(
        "backup readiness accepted a verified point with a mismatched verification timestamp: "
        + mismatched_verification_message
    )

for compact_field, compact_value in (
    (
        "newestVerifiedRestorablePointRef",
        "design/operations/deployments/production/verified-restorable-points/other.json",
    ),
    ("newestVerifiedRestorablePointDigest", "sha256:" + "c" * 64),
    ("newestVerifiedRestorablePointAt", timestamp(now - module.dt.timedelta(minutes=6))),
):
    mismatch_name = f"mismatched-{compact_field}.json"
    mismatch_attestation_data = json.loads(future_attestation.read_text(encoding="utf-8"))
    mismatch_attestation_data["recoveryCompatibility"]["backupReadinessRef"] = mismatch_name
    mismatch_attestation = write_json(
        f"mismatched-{compact_field}-attestation.json",
        mismatch_attestation_data,
    )
    canonical_future_attestation_path.write_bytes(mismatch_attestation.read_bytes())
    mismatched_readiness_data = json.loads(future_readiness.read_text(encoding="utf-8"))
    mismatched_readiness_data["backupLastSuccessAt"] = past_timestamp
    mismatched_readiness_data["promotionAttestationRef"] = str(
        canonical_future_attestation_path.relative_to(tmp)
    )
    mismatched_readiness_data[compact_field] = compact_value
    mismatched_readiness = write_json(
        mismatch_name,
        mismatched_readiness_data,
    )
    mismatched_status, mismatched_message = module.backup_readiness_check(
        mismatched_readiness,
        now.isoformat().replace("+00:00", "Z"),
        roll_forward_deployment_sha,
        tmp,
    )
    if mismatched_status != "fail" or f"{compact_field} does not match the attestation" not in mismatched_message:
        raise SystemExit(
            f"backup readiness {compact_field} mismatch did not fail closed: {mismatched_message}"
        )

blocked_attestation = write_json(
    "blocked-roll-forward-attestation.json",
    {
        "environment": "staging",
        "generatedAt": past_timestamp,
        "rollbackMode": "roll-forward-only",
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "recoveryCompatibility": {
            **compatibility_result("compatible"),
            "newDrillRequired": True,
            "backupReadinessRef": "blocked-roll-forward-readiness.json",
        },
    },
)
blocked_deployment_sha = "a" * 40
canonical_blocked_attestation_path = canonical_attestation_dir / f"{blocked_deployment_sha}.json"
canonical_blocked_attestation_path.write_bytes(blocked_attestation.read_bytes())
blocked_readiness = write_json(
    "blocked-roll-forward-readiness.json",
    {
        **json.loads(future_readiness.read_text(encoding="utf-8")),
        "deploymentRef": blocked_deployment_sha,
        "promotionAttestationRef": str(canonical_blocked_attestation_path.relative_to(tmp)),
        "backupLastSuccessAt": past_timestamp,
    },
)
blocked_status, blocked_message = module.backup_readiness_check(
    blocked_readiness,
    now.isoformat().replace("+00:00", "Z"),
    blocked_deployment_sha,
    tmp,
)
if blocked_status != "fail" or "remains blocked until canonical recovery-controller" not in blocked_message:
    raise SystemExit(f"incomplete nested roll-forward validation did not fail closed: {blocked_message}")
PY

echo "preflight contract checks passed"
