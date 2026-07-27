#!/usr/bin/env bash
# Lightweight contracts for architecture/process docs that are easy to drift.
set -euo pipefail

python3 - <<'PY'
import pathlib

root = pathlib.Path(".")

def require_contains(path, snippets):
    text = (root / path).read_text(encoding="utf-8")
    missing = [snippet for snippet in snippets if snippet not in text]
    if missing:
        raise SystemExit(f"{path}: missing required snippets: {missing}")

for path in (root / "design").rglob("*.md"):
    text = path.read_text(encoding="utf-8")
    if "<deployment-event-id>" in text:
        raise SystemExit(f"{path}: use the canonical <deploymentEventId> path placeholder")

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

operations_text = (root / "design/architecture/system-architecture-redis-operations.md").read_text(encoding="utf-8")
canonical_reset_heading = "## Canonical Coordination Reset Sequence"
canonical_reset_start = operations_text.find(canonical_reset_heading)
if canonical_reset_start == -1:
    raise SystemExit(
        "design/architecture/system-architecture-redis-operations.md: canonical reset section missing"
    )
canonical_reset_end = operations_text.find("\n## ", canonical_reset_start + len(canonical_reset_heading))
canonical_reset_text = operations_text[
    canonical_reset_start:
    canonical_reset_end if canonical_reset_end != -1 else len(operations_text)
]
required_reset_contract = [
    "Canonical public operation:",
    "`coordination-maintenance recover --mode reset --scope ... [--preserve-sessions]`",
    "1. internal pause-and-lock phase",
    "2. internal epoch-bump and coordination-reset phase",
    "3. internal ledger-reconciliation phase",
    "4. internal command-convergence phase",
    "5. internal metadata-initialization phase",
    "6. internal session-policy phase",
    "7. internal Account authority and issued-token projection-rebuild phase",
    "8. internal post-reset smoke-check phase",
    "9. public `resume(operationId, expectedPhase, scope, maintenanceLockToken, evidenceRef)` safety gate",
    "10. internal resume-and-success-release phase",
]
cursor = -1
previous = "<start of canonical reset contract>"
for clause in required_reset_contract:
    first_position = canonical_reset_text.find(clause)
    position = canonical_reset_text.find(clause, cursor + 1)
    if position == -1:
        if first_position == -1:
            raise SystemExit(
                "design/architecture/system-architecture-redis-operations.md: canonical reset contract missing: "
                f"[{clause!r}] after {previous!r}"
            )
        raise SystemExit(
            "design/architecture/system-architecture-redis-operations.md: canonical reset contract out of order: "
            f"expected {clause!r} after {previous!r}"
        )
    cursor = position + len(clause)
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

require_contains(
    "design/architecture/system-architecture-redis-ops-access.md",
    [
        "`continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`",
        "A phase failure retains the lock and paused fence.",
        "abandonment does not authorize resume",
    ],
)

print("architecture doc contracts passed")
PY

python3 dev-tools/validation/check-design-capability-allocation.py
python3 dev-tools/validation/test_design_capability_allocation.py
python3 dev-tools/validation/check-adr-review-status.py
python3 dev-tools/validation/test_adr_review_status.py
python3 dev-tools/validation/check-implementation-capability-tracking.py
python3 dev-tools/validation/test_implementation_capability_tracking.py
python3 dev-tools/validation/check-authz-route-matrix.py
python3 dev-tools/validation/test_check_authz_route_matrix.py
