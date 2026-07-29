#!/usr/bin/env bash
# Lightweight contracts for architecture/process docs that are easy to drift.
set -euo pipefail

python3 - <<'PY'
import pathlib
import re

root = pathlib.Path(".")
obsolete_public_resume_signature = "`resume(operationId, expectedPhase, scope, maintenanceLockToken, evidenceRef)`"
maintenance_lock_token_syntax = re.compile(r"--maintenance-lock-token(?![A-Za-z0-9_-])")
maintenance_lock_token_prohibition = re.compile(
    r"(?:"
    r"\b(?:must|may|should|can|does|do|is|are)\s+not\b"
    r"|\bnever\b"
    r"|\b(?:forbid|forbids|forbidden|prohibit|prohibits|prohibited|"
    r"disallow|disallows|disallowed)\b"
    r")(?:\b[eE]\.g\.|[^.!?\r\n])*--maintenance-lock-token(?![A-Za-z0-9_-])"
    r"|--maintenance-lock-token(?![A-Za-z0-9_-])(?:\b[eE]\.g\.|[^.!?\r\n])*"
    r"\b(?:forbidden|prohibited|disallowed)\b"
)
fence_start = re.compile(r"^[ \t]*(`{3,}|~{3,})")


def line_or_paragraph_containing(text, position):
    line_start = text.rfind("\n", 0, position) + 1
    line_end = text.find("\n", position)
    if line_end == -1:
        line_end = len(text)
    paragraph_start = text.rfind("\n\n", 0, line_start) + 2
    paragraph_end = text.find("\n\n", line_end)
    if paragraph_end == -1:
        paragraph_end = len(text)
    return text[paragraph_start:paragraph_end]


def has_forbidden_maintenance_lock_token_syntax(text):
    in_fenced_example = False
    fence_marker = None
    for line in text.splitlines(keepends=True):
        fence = fence_start.match(line)
        if fence:
            marker = fence.group(1)
            if not in_fenced_example:
                in_fenced_example = True
                fence_marker = marker
            elif marker[0] == fence_marker[0] and len(marker) >= len(fence_marker):
                in_fenced_example = False
                fence_marker = None
        if in_fenced_example:
            if maintenance_lock_token_syntax.search(line):
                return True

    for match in maintenance_lock_token_syntax.finditer(text):
        if maintenance_lock_token_prohibition.search(
            line_or_paragraph_containing(text, match.start())
        ) is None:
            return True
    return False

for example in (
    "--maintenance-lock-token <token>",
    "--maintenance-lock-token=<token>",
    "--maintenance-lock-token = <token>",
    "`--maintenance-lock-token`",
    "--maintenance-lock-token,",
):
    if not has_forbidden_maintenance_lock_token_syntax(example):
        raise SystemExit(f"maintenance token syntax fixture was not rejected: {example}")
if has_forbidden_maintenance_lock_token_syntax("--maintenance-lock-token-file <token-file>"):
    raise SystemExit("maintenance token file syntax was incorrectly rejected")
if has_forbidden_maintenance_lock_token_syntax(
    "The public command must not accept `--maintenance-lock-token` as a value."
):
    raise SystemExit("explicit maintenance token prohibition was incorrectly rejected")
if not has_forbidden_maintenance_lock_token_syntax(
    "The option is forbidden. `--maintenance-lock-token`"
):
    raise SystemExit("cross-sentence maintenance token prohibition was incorrectly accepted")
if not has_forbidden_maintenance_lock_token_syntax(
    "```text\n--maintenance-lock-token <token>\n```"
):
    raise SystemExit("fenced maintenance token example was incorrectly accepted")
if has_forbidden_maintenance_lock_token_syntax(
    "```text\nsafe example\n````\nThe `--maintenance-lock-token` option is forbidden."
):
    raise SystemExit("longer CommonMark closing fence was not recognized")
if not has_forbidden_maintenance_lock_token_syntax(
    "````text\n```\nThe `--maintenance-lock-token` option is forbidden.\n````"
):
    raise SystemExit("shorter nested fence incorrectly closed the outer fence")

def require_contains(path, snippets):
    text = (root / path).read_text(encoding="utf-8")
    missing = [snippet for snippet in snippets if snippet not in text]
    if missing:
        raise SystemExit(f"{path}: missing required snippets: {missing}")

for path in (root / "design").rglob("*.md"):
    text = path.read_text(encoding="utf-8")
    if "<deployment-event-id>" in text:
        raise SystemExit(f"{path}: use the canonical <deploymentEventId> path placeholder")
    if obsolete_public_resume_signature in text:
        raise SystemExit(f"{path}: uses obsolete caller-supplied recovery scope")
    if has_forbidden_maintenance_lock_token_syntax(text):
        raise SystemExit(
            f"{path}: recovery command examples must not expose "
            "`--maintenance-lock-token` command-line syntax; explicit prose "
            "prohibitions are allowed"
        )

canonical_world_dynamic = "world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>"
for path in [
    "design/architecture/system-architecture-redis-cache.md",
    "design/architecture/system-architecture-redis-cache-reference.md",
    "design/architecture/system-architecture-redis-cheatsheet.md",
    "design/architecture/microservices/world-management-service/runtime-and-data.md",
]:
    require_contains(path, [canonical_world_dynamic])

require_contains(
    "design/architecture/microservices/game-design-service/asset-storage.md",
    [
        "`EXPORTED_UNATTESTED -> FAILED`",
        "`FAILED -> TOMBSTONED`",
        "`TOMBSTONED -> PURGE_IN_PROGRESS`",
        "`PURGE_IN_PROGRESS -> PURGED`",
        "`PURGE_IN_PROGRESS -> PURGE_FAILED`",
        "`PURGED` is a retained terminal metadata state",
    ],
)
require_contains(
    "design/architecture/system-architecture-asset-store-runbook.md",
    [
        "`EXPORTED_UNATTESTED -> FAILED`",
        "`FAILED -> TOMBSTONED`",
        "`TOMBSTONED -> PURGE_IN_PROGRESS`",
        "`PURGE_IN_PROGRESS -> PURGED`",
        "`PURGE_IN_PROGRESS -> PURGE_FAILED`",
        "`PURGED` remains a retained terminal metadata row",
    ],
)
require_contains(
    "design/architecture/system-architecture-versioning-runtime.md",
    [
        "records the asset artifact as `FAILED`",
        "Moving failed artifact bytes to `TOMBSTONED` remains a separate explicit abandonment/quarantine action",
        "Asset purge must be initiated through CAS-guarded control-plane operations",
    ],
)
require_contains(
    "design/project-management/review-checklists.md",
    [
        "Cross-check findings against relevant domain implementation trackers, canonical design, proto contracts, and current service code",
        "Auth/session reviews must include the gateway, session-behavior, authz route matrix, Account runtime docs, Game Session runtime docs, and the `realm-routing-and-playable-state.md` and `player-access-and-session.md` trackers.",
        "Scripting/runtime reviews must treat `system-architecture-scripting-normative-contract-tables.md` as the first update target",
        "Observability reviews must check architecture docs, reference PromQL, dashboards, and the relevant capability-support docs under `slice-support/` when metric-label policy changes.",
        "## Capability/Tracker Completion Guide",
        "Verify the claimed capability outcome against every public contract it owns: HTTP/OpenAPI, gRPC/proto, event or outbox, and operator-facing contracts where applicable.",
        "Confirm that the named canonical owner in the tracker is the owner in code and that no local fallback or competing authority is silently carrying the behavior.",
        "Prefer narrow unit/integration/cross-service proof over interpreting an unrelated broad test pass as evidence.",
        "If any answer is no, leave the capability incomplete or complete only at its explicitly bounded current boundary.",
    ],
)
require_contains(
    "design/architecture/system-architecture-cicd.md",
    [
        "built and smoke-tested locally without registry credentials",
        "publish-pr-runtime-images.yml",
        "never checks out or executes PR source",
        "never writes shared cache or branch tags",
    ],
)
require_contains(
    "design/architecture/infrastructure/deployment-environments.md",
    [
        "builds and smoke-tests PR-tagged images without registry credentials",
        "trusted default-branch workflow publishes only the successful fixed head-SHA tags",
    ],
)
canonical_reset_anchor = "[Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence)"
for path in [
    "design/architecture/system-architecture-redis-reset-and-recovery.md",
    "design/architecture/system-architecture-redis-incident-runbook.md",
    "design/architecture/system-architecture-backup-recovery.md",
]:
    require_contains(path, [canonical_reset_anchor])

require_contains(
    "design/architecture/system-architecture-redis-reset-and-recovery.md",
    ["Region and tenant resets preserve Account-owned `session:auth:token:<tokenHash>` records"],
)
require_contains(
    "design/architecture/system-architecture-redis-ops-access.md",
    ["Region- and tenant-scoped coordination resets preserve Account-owned `session:auth:token:<tokenHash>` records"],
)
require_contains(
    "design/architecture/system-architecture-redis.md",
    [
        "matches the session's exact `jti`, `tokenGeneration`",
        "verified JWT's required `nbf` also valid",
    ],
)
require_contains(
    "design/architecture/system-architecture-redis-incident-runbook.md",
    [
        "non-admissible provisional renewal",
        "cannot extend the deadline",
    ],
)

operations_text = (root / "design/architecture/system-architecture-redis-operations.md").read_text(encoding="utf-8")
canonical_reset_matches = list(
    re.finditer(
        r"(?ms)^## Canonical Coordination Reset Sequence[ \t]*\n"
        r"(?P<section>.*?)(?=^## |\Z)",
        operations_text,
    )
)
if len(canonical_reset_matches) != 1:
    raise SystemExit(
        "design/architecture/system-architecture-redis-operations.md: expected exactly one canonical reset section, "
        f"found {len(canonical_reset_matches)}"
    )
canonical_reset_text = canonical_reset_matches[0].group("section")
required_reset_contract = [
    "Canonical public operation:",
    "`coordination-maintenance recover --mode reset --scope ... <session-policy-option>`",
    "1. internal pause-and-lock phase",
    "2. internal epoch-bump and scope-safe coordination-reset phase",
    "3. internal ledger-reconciliation phase",
    "4. internal command-convergence phase",
    "5. internal protected-domain cutover-fencing phase",
    "6. external AOF/deployment reset handoff",
    "7. internal metadata-initialization phase",
    "8. internal Account authority and issued-token projection-rebuild phase",
    "9. internal session-policy phase",
    "10. internal post-reset smoke-check phase",
    "11. `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`",
    "12. public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`",
    "13. internal resume-and-success-release phase",
]
cursor = 0
previous = "<start of canonical reset contract>"


def find_clause_matches(text, clause):
    pattern = re.compile(
        rf"(?m)^[ \t]*{re.escape(clause)}(?=[ \t,.;:)]|$).*$"
    )
    return list(pattern.finditer(text))


for clause in required_reset_contract:
    matches = find_clause_matches(canonical_reset_text, clause)
    if not matches:
        raise SystemExit(
            "design/architecture/system-architecture-redis-operations.md: canonical reset contract missing: "
            f"[{clause!r}] after {previous!r}"
        )
    if len(matches) != 1:
        raise SystemExit(
            "design/architecture/system-architecture-redis-operations.md: canonical reset contract clause must match exactly once: "
            f"[{clause!r}], found {len(matches)}"
        )
    match = matches[0]
    if match.start() < cursor:
        raise SystemExit(
            "design/architecture/system-architecture-redis-operations.md: canonical reset contract out of order: "
            f"expected {clause!r} after {previous!r}"
        )
    cursor = match.end()
    previous = clause

for clause in [
    "not a public command",
    "never runs automatically",
    "durable control store outside the target Redis deployment",
]:
    if clause not in canonical_reset_text:
        raise SystemExit(
            "design/architecture/system-architecture-redis-operations.md: "
            f"canonical reset contract missing: [{clause!r}]"
        )

canonical_public_resume_signature = "`resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`"
canonical_public_resume_awaiting_signature = "`resume(operationId, expectedPhase=awaiting_resume, maintenanceLockToken, evidenceRef)`"

for path in [
    "design/architecture/decisions/adr-0015-online-backup-and-environment-wide-cold-start-recovery.md",
    "design/architecture/system-architecture-backup-recovery.md",
    "design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md",
    "design/architecture/system-architecture-deployment-runbook.md",
    "design/architecture/system-architecture-post-restore-hardening.md",
    "design/architecture/system-architecture-redis-operations.md",
    "design/architecture/system-architecture-redis-reset-and-recovery.md",
]:
    require_contains(path, [canonical_public_resume_signature])

for path in (
    "design/operations/deployments/hobby-self-hosted/recovery/README.md",
    "design/operations/deployments/production/recovery/README.md",
    "design/operations/deployments/staging/recovery/README.md",
):
    require_contains(path, [canonical_public_resume_signature])
for path in (
    "design/operations/deployments/production/backup-readiness/README.md",
    "design/operations/deployments/production/traffic-open/README.md",
):
    require_contains(path, [canonical_public_resume_awaiting_signature])

require_contains(
    "design/architecture/system-architecture-redis-ops-access.md",
    [
        "`continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`",
        "A phase failure retains the lock and paused fence.",
        "abandonment does not authorize resume",
    ],
)

require_contains(
    "design/architecture/system-architecture-redis-reset-and-recovery.md",
    [
        "Any smoke tick exercised by the recovery or `continueRecovery` path is synthetic maintenance traffic only.",
        "must not authorize player ingress or real `tickId=0` admission",
        "Through `AWAITING_RESUME`, `RESUME_AUTHORIZED`, `releasing`, and `PARTIAL_RELEASE_RECONCILING`, the traffic fence remains active",
        "Only complete per-effect apply-and-readback verification may clear the fence and reopen normal admission.",
        "persisted as `partial_release_reconciling`",
        "--maintenance-lock-token-file <permissioned-token-file>",
    ],
)
print("architecture doc contracts passed")
PY

python3 dev-tools/validation/check-design-capability-allocation.py
python3 dev-tools/validation/test_design_capability_allocation.py
python3 dev-tools/validation/check-implementation-capability-tracking.py
python3 dev-tools/validation/test_implementation_capability_tracking.py
python3 dev-tools/validation/check-authz-route-matrix.py
python3 dev-tools/validation/test_check_authz_route_matrix.py
