#!/usr/bin/env python3
"""Validate the architecture design-allocation ledgers against repository paths."""

from __future__ import annotations

import argparse
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ALIGNMENT_DIR = Path("design/project-management/design-alignment")
ARCHITECTURE_DIR = Path("design/architecture")
TAXONOMY = Path("design/architecture/product-capability-taxonomy.md")
TOP_ALLOCATION = ALIGNMENT_DIR / "design-capability-allocation.md"
SYSTEM_ALLOCATION = ALIGNMENT_DIR / "design-capability-allocation-system.md"
MICROSERVICE_ALLOCATION = ALIGNMENT_DIR / "design-capability-allocation-microservices.md"
MARKDOWN_LINK_RE = re.compile(r"\[[^]]+\]\(([^)]+)\)")
GROUP_ID_RE = re.compile(r"[A-Z]{2}-\d+")
SYSTEM_CLASSIFICATIONS = {"normative design", "runbook", "reference", "index"}


def adr_allocation(primary: str, classification: str, *secondary: str) -> tuple[str, str, frozenset[str]]:
    return primary, classification, frozenset(secondary)


SYSTEM_ALLOCATION_EXPECTATIONS = {
    # Direct architecture sources.
    "design/architecture/README.md": ("SF-1", "index"),
    "design/architecture/product-capability-taxonomy.md": ("SF-1", "normative design"),
    "design/architecture/repository-structure.md": ("PO-3", "reference"),
    "design/architecture/service-responsibility-matrix.md": ("SF-1", "normative design"),
    "design/architecture/system-architecture-asset-store-runbook.md": ("AR-1", "runbook"),
    "design/architecture/system-architecture-authentication.md": ("AA-2", "normative design"),
    "design/architecture/system-architecture-authz-route-matrix.md": ("SF-1", "normative design"),
    "design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md": (
        "PO-4",
        "normative design",
    ),
    "design/architecture/system-architecture-backup-recovery.md": ("PO-3", "runbook"),
    "design/architecture/system-architecture-cicd.md": ("PO-3", "normative design"),
    "design/architecture/system-architecture-database-migrations.md": ("SF-2", "normative design"),
    "design/architecture/system-architecture-deploy-preflight-policy.md": ("PO-3", "normative design"),
    "design/architecture/system-architecture-deployment-runbook.md": ("PO-3", "runbook"),
    "design/architecture/system-architecture-diagram.md": ("SF-1", "reference"),
    "design/architecture/system-architecture-frontend.md": ("EA-3", "normative design"),
    "design/architecture/system-architecture-game-customization.md": ("AR-1", "normative design"),
    "design/architecture/system-architecture-gateway.md": ("PO-2", "normative design"),
    "design/architecture/system-architecture-grpc.md": ("SF-1", "normative design"),
    "design/architecture/system-architecture-identifier-glossary.md": ("SF-1", "reference"),
    "design/architecture/system-architecture-input-output-and-presentation.md": ("EA-1", "normative design"),
    "design/architecture/system-architecture-jwt-and-token-contracts.md": ("SF-1", "normative design"),
    "design/architecture/system-architecture-jwt-compromise-runbook.md": ("SF-1", "runbook"),
    "design/architecture/system-architecture-llm-content-tools.md": ("AR-1", "normative design"),
    "design/architecture/system-architecture-logging-monitoring.md": ("PO-4", "normative design"),
    "design/architecture/system-architecture-mud-client-protocol.md": ("PO-2", "normative design"),
    "design/architecture/system-architecture-multi-tenancy.md": ("AA-3", "normative design"),
    "design/architecture/system-architecture-observability-incident-runbook.md": ("PO-4", "runbook"),
    "design/architecture/system-architecture-operator-credentials-runbook.md": ("SF-1", "runbook"),
    "design/architecture/system-architecture-overview.md": ("SF-1", "normative design"),
    "design/architecture/system-architecture-player-command-model.md": ("EA-1", "normative design"),
    "design/architecture/system-architecture-player-experience-incident-runbook.md": ("PO-4", "runbook"),
    "design/architecture/system-architecture-post-restore-hardening.md": ("PO-3", "runbook"),
    "design/architecture/system-architecture-procedural-generation.md": ("AR-1", "normative design"),
    "design/architecture/system-architecture-promotion-attestation.md": ("PO-3", "normative design"),
    "design/architecture/system-architecture-protocol-bridging.md": ("PO-2", "normative design"),
    "design/architecture/system-architecture-reconnection.md": ("AA-2", "normative design"),
    "design/architecture/system-architecture-redis-cache-reference.md": ("SF-2", "reference"),
    "design/architecture/system-architecture-redis-cache.md": ("SF-2", "normative design"),
    "design/architecture/system-architecture-redis-cheatsheet.md": ("SF-2", "reference"),
    "design/architecture/system-architecture-redis-design-checklist.md": ("SF-2", "normative design"),
    "design/architecture/system-architecture-redis-incident-runbook.md": ("PO-4", "runbook"),
    "design/architecture/system-architecture-redis-lua-patterns.md": ("SF-2", "normative design"),
    "design/architecture/system-architecture-redis-metrics-catalog.md": ("PO-4", "reference"),
    "design/architecture/system-architecture-redis-operations.md": ("SF-2", "runbook"),
    "design/architecture/system-architecture-redis-ops-access.md": ("PO-1", "normative design"),
    "design/architecture/system-architecture-redis-reset-and-recovery.md": ("SF-2", "runbook"),
    "design/architecture/system-architecture-redis-script-rollout-and-compatibility.md": ("SF-2", "runbook"),
    "design/architecture/system-architecture-redis-usage-and-profiles.md": ("SF-2", "normative design"),
    "design/architecture/system-architecture-redis.md": ("SF-2", "normative design"),
    "design/architecture/system-architecture-runbooks.md": ("PO-3", "index"),
    "design/architecture/system-architecture-scaling-runbook.md": ("PO-4", "runbook"),
    "design/architecture/system-architecture-scripting-contracts.md": ("AS-1", "normative design"),
    "design/architecture/system-architecture-scripting-control-plane-api.md": ("AS-1", "normative design"),
    "design/architecture/system-architecture-scripting-control-plane-events.md": ("SF-1", "normative design"),
    "design/architecture/system-architecture-scripting-control-plane-operations.md": ("AR-3", "normative design"),
    "design/architecture/system-architecture-scripting-dsl-and-lifecycle.md": ("AS-1", "index"),
    "design/architecture/system-architecture-scripting-dsl-for-designers.md": ("AR-1", "reference"),
    "design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md": (
        "AS-1",
        "normative design",
    ),
    "design/architecture/system-architecture-scripting-event-registry.md": ("AS-1", "normative design"),
    "design/architecture/system-architecture-scripting-examples-and-patterns.md": ("AS-1", "reference"),
    "design/architecture/system-architecture-scripting-normative-contract-tables.md": (
        "SF-1",
        "normative design",
    ),
    "design/architecture/system-architecture-scripting-observability-contract.md": ("PO-4", "normative design"),
    "design/architecture/system-architecture-scripting-operations-cookbook.md": ("PO-1", "runbook"),
    "design/architecture/system-architecture-scripting-quotas-and-operations.md": ("AS-1", "normative design"),
    "design/architecture/system-architecture-scripting-rollout-and-rollback.md": ("AR-3", "runbook"),
    "design/architecture/system-architecture-scripting-runtime-execution.md": ("AS-1", "normative design"),
    "design/architecture/system-architecture-scripting-scheduler-and-timers.md": ("AS-1", "normative design"),
    "design/architecture/system-architecture-scripting.md": ("AS-1", "index"),
    "design/architecture/system-architecture-security.md": ("SF-1", "normative design"),
    "design/architecture/system-architecture-session-behavior.md": ("AA-2", "normative design"),
    "design/architecture/system-architecture-settings-model.md": ("AR-2", "normative design"),
    "design/architecture/system-architecture-shared-libraries.md": ("SF-1", "reference"),
    "design/architecture/system-architecture-spatial-and-ambient-effects-catalog.md": ("GR-2", "normative design"),
    "design/architecture/system-architecture-telnet-degraded-runbook.md": ("PO-2", "runbook"),
    "design/architecture/system-architecture-temporal-workflows.md": ("SF-2", "normative design"),
    "design/architecture/system-architecture-testing.md": ("PO-4", "normative design"),
    "design/architecture/system-architecture-tick-concepts-and-invariants.md": ("GR-1", "normative design"),
    "design/architecture/system-architecture-tick-execution-flows.md": ("GR-1", "normative design"),
    "design/architecture/system-architecture-tick-failures-and-operations.md": ("PO-4", "normative design"),
    "design/architecture/system-architecture-tick-incident-runbook.md": ("PO-4", "runbook"),
    "design/architecture/system-architecture-ticks.md": ("GR-1", "normative design"),
    "design/architecture/system-architecture-tracing.md": ("PO-4", "normative design"),
    "design/architecture/system-architecture-transactions.md": ("SF-2", "normative design"),
    "design/architecture/system-architecture-versioning-runtime.md": ("AR-3", "normative design"),
    "design/architecture/system-context-diagram.md": ("SF-1", "index"),
    "design/architecture/user-journeys-creators.md": ("AR-1", "reference"),
    "design/architecture/user-journeys-operators.md": ("PO-1", "reference"),
    "design/architecture/user-journeys-players.md": ("AA-2", "reference"),
    "design/architecture/user-journeys.md": ("EA-3", "index"),
    # Infrastructure and generated sources.
    "design/architecture/infrastructure/README.md": ("PO-3", "index"),
    "design/architecture/infrastructure/deployment-environments.md": ("PO-3", "normative design"),
    "design/architecture/infrastructure/environment-and-secrets-catalog.md": ("SF-1", "reference"),
    "design/architecture/infrastructure/environment-and-secrets-overview.md": ("SF-1", "normative design"),
    "design/architecture/infrastructure/environment-and-secrets.md": ("SF-1", "index"),
    "design/architecture/infrastructure/schedule.md": ("PO-3", "reference"),
    "design/architecture/generated/platform-settings-reference.md": ("AR-2", "generated"),
}
# Secondary sets are copied from the allocation registry and intentionally locked here so registry drift fails validation.
ADR_ALLOCATION_EXPECTATIONS = {
    "design/architecture/decisions/README.md": ("Exempt", "Decision registry/index"),
    "design/architecture/decisions/adr-0001-scripting-event-ingress-idempotency-identity.md": adr_allocation(
        "AS-1", "Accepted", "SF-1", "SF-2"
    ),
    "design/architecture/decisions/adr-0002-automation-handoff-reliability-and-success-semantics.md": adr_allocation(
        "AS-1", "Accepted", "GR-1", "SF-2", "PO-4"
    ),
    "design/architecture/decisions/adr-0003-reload-backpressure-and-retry-contract.md": adr_allocation(
        "AS-1", "Accepted", "AR-3", "GR-1", "PO-4"
    ),
    "design/architecture/decisions/adr-0004-gameplay-reroute-vs-backend-unavailable.md": adr_allocation(
        "PO-2", "Superseded by ADR 0007", "AA-2", "GR-1", "PO-4"
    ),
    "design/architecture/decisions/adr-0005-tenant-identifiers-in-gameplay-protocol.md": adr_allocation(
        "AA-3", "Accepted", "EA-1", "SF-1"
    ),
    "design/architecture/decisions/adr-0006-gameplay-shard-routing-key-transport.md": adr_allocation(
        "PO-2", "Withdrawn; superseded by ADR 0007", "AA-3", "GR-1", "SF-1"
    ),
    "design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md": adr_allocation(
        "PO-2", "Accepted", "AA-2", "GR-1", "PO-4"
    ),
    "design/architecture/decisions/adr-0008-multi-cluster-gameplay-sharding-scope.md": adr_allocation(
        "GR-1", "Accepted", "PO-2", "PO-3", "SF-2"
    ),
    "design/architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md": adr_allocation(
        "SF-2", "Accepted", "AA-2", "GR-1", "AS-1"
    ),
    "design/architecture/decisions/adr-0010-tcp-proxy-identity-canonicalization.md": adr_allocation(
        "SF-1", "Accepted", "PO-2", "PO-3"
    ),
    "design/architecture/decisions/adr-0011-gameplay-session-front-end-and-region-execution.md": adr_allocation(
        "GR-1", "Accepted", "AA-2", "SF-1", "SF-2", "PO-2"
    ),
    "design/architecture/decisions/adr-0012-settings-value-precedence-and-constraints.md": adr_allocation(
        "AR-2", "Accepted", "EA-1", "GR-1", "SF-2"
    ),
    "design/architecture/decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md": adr_allocation(
        "GR-1", "Accepted", "AA-2", "PO-2", "PO-4", "SF-2"
    ),
    "design/architecture/decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md": adr_allocation(
        "SF-1", "Accepted", "AA-1", "PO-1", "PO-3", "PO-4"
    ),
    "design/architecture/decisions/adr-0015-online-backup-and-environment-wide-cold-start-recovery.md": adr_allocation(
        "PO-3", "Accepted", "GR-1", "PO-1", "PO-4", "SF-2"
    ),
    "design/architecture/decisions/adr-0016-canonical-gameplay-command-status-lifecycle.md": adr_allocation(
        "GR-1", "Accepted", "AA-2", "PO-4", "SF-2"
    ),
    "design/architecture/decisions/adr-0017-capability-gated-operational-tracing.md": adr_allocation(
        "PO-4", "Accepted", "AA-2", "GR-1", "SF-1"
    ),
    "design/architecture/decisions/adr-0018-declarative-production-gateway-routes.md": adr_allocation(
        "PO-2", "Accepted", "AA-3", "PO-1", "PO-3", "SF-2"
    ),
    "design/architecture/decisions/adr-0019-separate-active-session-resume-and-transcript-lifetimes.md": adr_allocation(
        "AA-2", "Accepted", "AR-2", "EA-3", "GR-1", "SF-1"
    ),
    "design/architecture/decisions/adr-0020-scoped-domain-and-operational-identifiers.md": adr_allocation(
        "SF-1", "Accepted", "AR-1", "GR-2", "GR-3"
    ),
    "design/architecture/decisions/adr-0021-staged-player-authentication-and-gameplay-binding.md": adr_allocation(
        "AA-2", "Accepted", "EA-3", "PO-2", "SF-1"
    ),
    "design/architecture/decisions/adr-0022-account-authority-and-gameplay-session-ownership.md": adr_allocation(
        "AA-1", "Accepted", "AA-2", "SF-1", "SF-2"
    ),
    "design/architecture/decisions/adr-0023-central-route-authorization-governance.md": adr_allocation(
        "SF-1", "Accepted", "AA-1", "PO-1", "PO-2", "PO-4"
    ),
    "design/architecture/decisions/adr-0024-trusted-gameplay-workload-delegation.md": adr_allocation(
        "SF-1", "Accepted", "GR-1", "PO-3"
    ),
    "design/architecture/decisions/adr-0025-explicit-open-enrollment-membership.md": adr_allocation(
        "AA-1", "Accepted", "AA-2", "AA-3", "EA-3"
    ),
    "design/architecture/decisions/adr-0026-global-roles-do-not-grant-gameplay-authority.md": adr_allocation(
        "AA-1", "Accepted", "AA-2", "EA-3", "PO-1"
    ),
    "design/architecture/decisions/adr-0027-single-realm-admission-target.md": adr_allocation(
        "AA-3", "Accepted", "AR-3", "GR-1", "GR-2"
    ),
    "design/architecture/decisions/adr-0028-differentiated-entitlement-freshness.md": adr_allocation(
        "AA-1", "Accepted", "AA-2", "AA-3", "PO-4", "SF-1"
    ),
    "design/architecture/decisions/adr-0029-single-use-gameplay-connect-token-carriage.md": adr_allocation(
        "PO-2", "Accepted", "AA-2", "SF-1"
    ),
    "design/architecture/decisions/adr-0030-risk-based-active-session-revocation.md": adr_allocation(
        "AA-1", "Accepted", "AA-2", "PO-1", "GR-1"
    ),
    "design/architecture/decisions/adr-0031-revocation-safe-session-token-rotation-and-logout.md": adr_allocation(
        "AA-2", "Accepted", "AA-1", "GR-1", "SF-1"
    ),
    "design/architecture/decisions/adr-0032-kubernetes-native-secret-delivery-without-mandatory-vault.md": adr_allocation(
        "SF-1", "Accepted", "PO-1", "PO-2", "PO-3"
    ),
    "design/architecture/decisions/adr-0033-public-player-facing-telnet-requires-tls.md": adr_allocation(
        "PO-2", "Accepted", "AA-1", "AA-2", "EA-3", "SF-1"
    ),
    "design/architecture/decisions/adr-0034-layered-abuse-controls-without-attacker-triggered-account-locks.md": adr_allocation(
        "SF-1", "Accepted", "AA-1", "AA-2", "PO-1", "PO-2"
    ),
    "design/architecture/decisions/adr-0035-single-record-issued-token-registry.md": adr_allocation(
        "SF-1", "Accepted", "AA-1", "AA-2", "SF-2"
    ),
}
MICROSERVICE_STANDARD_CLASSIFICATIONS = {
    "README.md": "Service overview",
    "api-contracts.md": "API contract",
    "configuration.md": "Configuration contract",
    "operations.md": "Operations contract",
    "runtime-and-data.md": "Runtime/data contract",
}
MICROSERVICE_STANDARD_OVERRIDES = {
    "design/architecture/microservices/game-logic-service/configuration.md": (
        "AR-2",
        "Runtime-policy/configuration contract",
    ),
}
MICROSERVICE_SERVICE_PRIMARY = {
    "account-service": "AA-1",
    "automation-scripting-service": "AS-1",
    "entity-management-service": "GR-3",
    "game-design-service": "AR-1",
    "game-logic-service": "GR-4",
    "game-session-service": "AA-2",
    "logging-admin-service": "PO-1",
    "social-groups-service": "EA-2",
    "spring-cloud-gateway": "PO-2",
    "tcp-proxy-service": "PO-2",
    "world-management-service": "GR-2",
}
MICROSERVICE_APPENDIX_ALLOCATIONS = {
    "design/architecture/microservices/account-service/stripe-integration.md": ("AA-1", "Commerce design"),
    "design/architecture/microservices/account-service/subscription-management.md": ("AA-1", "Entitlement design"),
    "design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md": (
        "AS-1",
        "Sandbox/runtime design",
    ),
    "design/architecture/microservices/game-design-service/ability-action-tools.md": ("AR-1", "Authoring design"),
    "design/architecture/microservices/game-design-service/asset-storage.md": ("AR-1", "Release-asset design"),
    "design/architecture/microservices/game-design-service/feature-flags.md": ("AR-2", "Runtime-policy design"),
    "design/architecture/microservices/game-design-service/game-templates.md": ("AR-1", "Template/launch design"),
    "design/architecture/microservices/game-design-service/item-equipment-balancing.md": (
        "AR-1",
        "Authoring design",
    ),
    "design/architecture/microservices/game-design-service/modding-framework.md": ("AR-1", "Plugin/mod design"),
    "design/architecture/microservices/game-design-service/version-control.md": ("AR-1", "Version/publish design"),
    "design/architecture/microservices/game-design-service/web-visual-interface.md": (
        "EA-3",
        "First-party creator UX",
    ),
    "design/architecture/microservices/game-design-service/world-editing-tools.md": ("AR-1", "Authoring workflow"),
    "design/architecture/microservices/game-session-service/protocols.md": (
        "EA-1",
        "Gameplay protocol contract",
    ),
    "design/architecture/microservices/logging-admin-service/admin-ui.md": ("EA-3", "First-party operator UX"),
    "design/architecture/microservices/logging-admin-service/analytics-dashboards.md": (
        "PO-4",
        "Observability design",
    ),
    "design/architecture/microservices/logging-admin-service/moderation-policies.md": (
        "PO-1",
        "Moderation-policy design",
    ),
    "design/architecture/microservices/spring-cloud-gateway/client-behavior.md": (
        "PO-2",
        "Edge client-behavior contract",
    ),
    "design/architecture/microservices/tcp-proxy-service/protocols.md": ("PO-2", "Edge protocol contract"),
    "design/architecture/microservices/world-management-service/procedural-generation-control.md": (
        "AR-1",
        "Procedural-authoring design",
    ),
    "design/architecture/microservices/world-management-service/world-creation-workflow.md": (
        "AR-3",
        "Activation workflow",
    ),
}
MICROSERVICE_EXEMPT_ALLOCATIONS = {
    "design/architecture/microservices/service-documentation-structure.md": ("Exempt", "Governance guide"),
    "design/architecture/microservices/service-template.md": ("Exempt", "Template"),
}
@dataclass(frozen=True)
class LedgerRow:
    path: str
    primary: str
    classification: str


def fail(message: str) -> None:
    raise SystemExit(message)


def table_cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def is_table_separator(line: str) -> bool:
    cells = table_cells(line)
    return bool(cells) and all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells)


def markdown_tables(text: str) -> list[tuple[list[str], list[list[str]]]]:
    tables: list[tuple[list[str], list[list[str]]]] = []
    lines = text.splitlines()
    index = 0
    while index < len(lines):
        if not lines[index].strip().startswith("|"):
            index += 1
            continue
        block: list[str] = []
        while index < len(lines) and lines[index].strip().startswith("|"):
            block.append(lines[index])
            index += 1
        if len(block) < 2 or not is_table_separator(block[1]):
            continue
        headers = table_cells(block[0])
        rows = [table_cells(line) for line in block[2:]]
        if any(len(row) != len(headers) for row in rows):
            fail(f"malformed Markdown table with headers {headers}")
        tables.append((headers, rows))
    return tables


def section(text: str, heading: str) -> str:
    matches = list(re.finditer(rf"^## {re.escape(heading)}\s*$", text, re.MULTILINE))
    if len(matches) != 1:
        fail(f"expected exactly one section {heading!r}, found {len(matches)}")
    match = matches[0]
    following = re.search(r"^## ", text[match.end() :], re.MULTILINE)
    end = match.end() + following.start() if following else len(text)
    return text[match.end() : end]


def table_in_section(text: str, heading: str, required_headers: set[str]) -> tuple[list[str], list[list[str]]]:
    matches = [
        (headers, rows)
        for headers, rows in markdown_tables(section(text, heading))
        if required_headers <= set(headers)
    ]
    if len(matches) != 1:
        fail(
            f"{heading}: expected exactly one table with headers "
            f"{sorted(required_headers)}, found {len(matches)}"
        )
    return matches[0]


def clean_cell(cell: str) -> str:
    return cell.strip().strip("*").strip().strip("`").strip("*").strip()


def capability_set_from_cell(cell: str, context: str, groups: set[str]) -> frozenset[str]:
    values = frozenset(clean_cell(value) for value in cell.split(",") if value.strip())
    if not values or any(value not in groups for value in values):
        fail(f"{context}: expected comma-separated capability IDs, got {cell!r}")
    return values


def declared_int(cell: str, context: str) -> int:
    match = re.fullmatch(r"\*{0,2}(\d+)\*{0,2}", cell.strip())
    if not match:
        fail(f"{context}: expected an integer, got {cell!r}")
    return int(match.group(1))


def declared_coverage(cell: str, context: str, expected: str) -> None:
    actual = clean_cell(cell)
    if actual != expected:
        fail(f"{context}: expected coverage {expected!r}, got {actual!r}")


def repository_files(root: Path) -> dict[str, set[str]]:
    root = root.resolve()
    sets = {
        "Top-level architecture": {
            path.relative_to(root).as_posix()
            for path in (root / ARCHITECTURE_DIR).glob("*.md")
            if path.is_file()
        },
        "Infrastructure": {
            path.relative_to(root).as_posix()
            for path in (root / ARCHITECTURE_DIR / "infrastructure").rglob("*.md")
            if path.is_file()
        },
        "Generated references": {
            path.relative_to(root).as_posix()
            for path in (root / ARCHITECTURE_DIR / "generated").rglob("*.md")
            if path.is_file()
        },
        "Microservice architecture": {
            path.relative_to(root).as_posix()
            for path in (root / ARCHITECTURE_DIR / "microservices").rglob("*.md")
            if path.is_file()
        },
        "Architecture decisions": {
            path.relative_to(root).as_posix()
            for path in (root / ARCHITECTURE_DIR / "decisions").rglob("*.md")
            if path.is_file()
        },
    }
    all_paths: list[str] = []
    for name, paths in sets.items():
        all_paths.extend(paths)
        if len(paths) != len(set(paths)):
            fail(f"{name}: duplicate repository path")
    duplicates = [path for path, count in Counter(all_paths).items() if count > 1]
    if duplicates:
        fail(f"architecture source sets overlap: {sorted(duplicates)}")
    return sets


def group_ids(root: Path) -> set[str]:
    text = (root / TAXONOMY).read_text(encoding="utf-8")
    groups = set(re.findall(r"^#### `([A-Z]{2}-\d+)`", text, re.MULTILINE))
    if not groups:
        fail(f"{TAXONOMY}: no capability groups found")
    return groups


def relative_target(owner: Path, target: str, root: Path) -> str:
    target = target.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1]
    if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", target) or target.startswith("//"):
        fail(f"{owner.relative_to(root)}: external allocation target {target!r}")
    bare_target = target.split("#", 1)[0]
    resolved = owner.resolve() if not bare_target else (owner.parent / bare_target).resolve()
    try:
        relative = resolved.relative_to(root.resolve()).as_posix()
    except ValueError:
        fail(f"{owner.relative_to(root)}: allocation target escapes repository: {target}")
    if not resolved.is_file():
        fail(f"{owner.relative_to(root)}: missing allocation target {target}")
    return relative


def path_from_code_cell(owner: Path, cell: str, root: Path) -> str:
    match = re.fullmatch(r"`([^`]+)`", cell.strip())
    if not match:
        fail(f"{owner.relative_to(root)}: path cell must contain one repository-relative code path: {cell!r}")
    value = match.group(1)
    resolved = (root / value).resolve() if value.startswith("design/") else (owner.parent / value).resolve()
    try:
        relative = resolved.relative_to(root.resolve()).as_posix()
    except ValueError:
        fail(f"{owner.relative_to(root)}: declared path escapes repository: {value}")
    if not resolved.is_file():
        fail(f"{owner.relative_to(root)}: missing declared source {value}")
    return relative


def primary_from_cell(owner: Path, cell: str, groups: set[str], root: Path) -> str:
    primary = clean_cell(cell)
    if primary not in groups:
        fail(f"{owner.relative_to(root)}: invalid primary capability {cell!r}")
    return primary


def validate_expected_allocation(
    root: Path,
    document: Path,
    source_path: str,
    primary: str,
    classification: str,
    expected_allocations: dict[str, tuple],
    secondary: frozenset[str] | None = None,
) -> None:
    expected = expected_allocations.get(source_path)
    if expected is None:
        fail(f"{document.relative_to(root)}:{source_path}: no expected allocation")
    expected_primary, expected_classification = expected[:2]
    if primary != expected_primary:
        fail(
            f"{document.relative_to(root)}:{source_path}: unexpected primary capability "
            f"{primary!r}; expected {expected_primary!r}"
        )
    if classification != expected_classification:
        fail(
            f"{document.relative_to(root)}:{source_path}: unexpected source classification "
            f"{classification!r}; expected {expected_classification!r}"
        )
    expected_secondary = expected[2] if len(expected) == 3 else None
    if expected_secondary is not None and secondary != expected_secondary:
        fail(
            f"{document.relative_to(root)}:{source_path}: unexpected secondary capabilities "
            f"{sorted(secondary or [])!r}; expected {sorted(expected_secondary)!r}"
        )


def expected_microservice_allocation(source_path: str) -> tuple[str, str]:
    if source_path in MICROSERVICE_EXEMPT_ALLOCATIONS:
        return MICROSERVICE_EXEMPT_ALLOCATIONS[source_path]
    if source_path in MICROSERVICE_APPENDIX_ALLOCATIONS:
        return MICROSERVICE_APPENDIX_ALLOCATIONS[source_path]
    if source_path in MICROSERVICE_STANDARD_OVERRIDES:
        return MICROSERVICE_STANDARD_OVERRIDES[source_path]

    path = Path(source_path)
    if path.parent.name == "microservices" and path.name == "README.md":
        return "PO-2", MICROSERVICE_STANDARD_CLASSIFICATIONS[path.name]
    if path.parent.name not in MICROSERVICE_SERVICE_PRIMARY:
        fail(f"{source_path}: no expected microservice allocation")
    if path.name not in MICROSERVICE_STANDARD_CLASSIFICATIONS:
        fail(f"{source_path}: no expected microservice allocation")

    primary = MICROSERVICE_SERVICE_PRIMARY[path.parent.name]
    if path.name == "configuration.md":
        primary = "AR-2" if path.parent.name == "game-logic-service" else "PO-3"
    elif path.name == "operations.md":
        primary = "PO-4"
    elif path.name == "runtime-and-data.md" and path.parent.name == "game-session-service":
        primary = "GR-1"
    return primary, MICROSERVICE_STANDARD_CLASSIFICATIONS[path.name]


def parse_linked_ledger(
    root: Path,
    document: Path,
    heading: str,
    expected_paths: set[str],
    groups: set[str],
    allowed_classifications: set[str],
    expected_allocations: dict[str, tuple[str, str]],
) -> list[LedgerRow]:
    text = document.read_text(encoding="utf-8")
    headers, rows = table_in_section(text, heading, {"Path", "Primary", "Classification"})
    path_index = headers.index("Path")
    primary_index = headers.index("Primary")
    classification_index = headers.index("Classification")
    parsed: list[LedgerRow] = []
    for row in rows:
        path_cell = row[path_index]
        targets = MARKDOWN_LINK_RE.findall(path_cell)
        if len(targets) != 1:
            fail(f"{document.relative_to(root)}: path cell must contain one local link: {path_cell!r}")
        source_path = relative_target(document, targets[0], root)
        primary = primary_from_cell(document, row[primary_index], groups, root)
        classification = row[classification_index].strip()
        if classification not in allowed_classifications:
            fail(
                f"{document.relative_to(root)}:{source_path}: invalid source classification "
                f"{classification!r}; expected one of {sorted(allowed_classifications)}"
            )
        validate_expected_allocation(
            root,
            document,
            source_path,
            primary,
            classification,
            expected_allocations,
        )
        parsed.append(LedgerRow(source_path, primary, classification))
    paths = [row.path for row in parsed]
    if len(paths) != len(set(paths)):
        duplicates = sorted(path for path, count in Counter(paths).items() if count > 1)
        fail(f"{document.relative_to(root)}: duplicate allocation rows: {duplicates}")
    actual = set(paths)
    if actual != expected_paths:
        fail(
            f"{document.relative_to(root)}: source manifest mismatch: "
            f"missing={sorted(expected_paths - actual)}, extra={sorted(actual - expected_paths)}"
        )
    return parsed


def parse_microservice_ledger(root: Path, groups: set[str], expected_paths: set[str]) -> list[LedgerRow]:
    document = root / MICROSERVICE_ALLOCATION
    text = document.read_text(encoding="utf-8")
    parsed: list[LedgerRow] = []
    for headers, rows in markdown_tables(text):
        if {"Design source", "Primary capability", "Source classification"} - set(headers):
            continue
        path_index = headers.index("Design source")
        primary_index = headers.index("Primary capability")
        classification_index = headers.index("Source classification")
        for row in rows:
            source_path = path_from_code_cell(document, row[path_index], root)
            primary_cell = clean_cell(row[primary_index])
            if primary_cell == "Exempt":
                primary = primary_cell
            else:
                primary = primary_from_cell(document, row[primary_index], groups, root)
            classification = row[classification_index].strip()
            expected_primary, expected_classification = expected_microservice_allocation(source_path)
            if primary != expected_primary:
                fail(
                    f"{document.relative_to(root)}:{source_path}: unexpected primary capability "
                    f"{primary!r}; expected {expected_primary!r}"
                )
            if classification != expected_classification:
                fail(
                    f"{document.relative_to(root)}:{source_path}: unexpected source classification "
                    f"{classification!r}; expected {expected_classification!r}"
                )
            parsed.append(LedgerRow(source_path, primary, classification))
    if not parsed:
        fail(f"{document.relative_to(root)}: no allocation rows found")
    paths = [row.path for row in parsed]
    if len(paths) != len(set(paths)):
        duplicates = sorted(path for path, count in Counter(paths).items() if count > 1)
        fail(f"{document.relative_to(root)}: duplicate allocation rows: {duplicates}")
    actual = set(paths)
    if actual != expected_paths:
        fail(
            f"{document.relative_to(root)}: source manifest mismatch: "
            f"missing={sorted(expected_paths - actual)}, extra={sorted(actual - expected_paths)}"
        )
    return parsed


def validate_system(root: Path, source_sets: dict[str, set[str]], groups: set[str]) -> list[LedgerRow]:
    document = root / SYSTEM_ALLOCATION
    expected_paths = (
        source_sets["Top-level architecture"]
        | source_sets["Infrastructure"]
        | source_sets["Generated references"]
    )
    if set(SYSTEM_ALLOCATION_EXPECTATIONS) != expected_paths:
        fail(f"{document.relative_to(root)}: system allocation expectations drifted")
    direct = parse_linked_ledger(
        root,
        document,
        "Direct Architecture Ledger",
        source_sets["Top-level architecture"],
        groups,
        SYSTEM_CLASSIFICATIONS,
        SYSTEM_ALLOCATION_EXPECTATIONS,
    )
    infrastructure = parse_linked_ledger(
        root,
        document,
        "Infrastructure Ledger",
        source_sets["Infrastructure"],
        groups,
        SYSTEM_CLASSIFICATIONS,
        SYSTEM_ALLOCATION_EXPECTATIONS,
    )
    generated = parse_linked_ledger(
        root,
        document,
        "Generated Ledger",
        source_sets["Generated references"],
        groups,
        {"generated"},
        SYSTEM_ALLOCATION_EXPECTATIONS,
    )
    rows_by_name = {
        "Direct architecture": direct,
        "Infrastructure": infrastructure,
        "Generated": generated,
    }
    headers, rows = table_in_section(document.read_text(encoding="utf-8"), "Coverage Proof", {"Set", "Source files", "Ledger rows"})
    name_index = headers.index("Set")
    source_index = headers.index("Source files")
    ledger_index = headers.index("Ledger rows")
    expected_names = set(rows_by_name)
    declared_names: set[str] = set()
    for row in rows:
        name = clean_cell(row[name_index])
        if name not in rows_by_name:
            if name == "Total":
                continue
            fail(f"{document.relative_to(root)}: unknown coverage set {name!r}")
        if name in declared_names:
            fail(f"{document.relative_to(root)}: duplicate coverage set {name}")
        declared_names.add(name)
        expected_count = len(rows_by_name[name])
        if declared_int(row[source_index], f"{document.relative_to(root)}:{name}: source files") != expected_count:
            fail(f"{document.relative_to(root)}:{name}: source-file summary drift")
        if declared_int(row[ledger_index], f"{document.relative_to(root)}:{name}: ledger rows") != len(rows_by_name[name]):
            fail(f"{document.relative_to(root)}:{name}: ledger-row summary drift")
    if declared_names != expected_names:
        fail(f"{document.relative_to(root)}: coverage proof sets mismatch")
    total = sum(len(rows) for rows in rows_by_name.values())
    total_rows = [row for row in rows if clean_cell(row[name_index]) == "Total"]
    if len(total_rows) != 1:
        fail(f"{document.relative_to(root)}: coverage proof must contain one Total row")
    if declared_int(total_rows[0][source_index], f"{document.relative_to(root)}: total source files") != total:
        fail(f"{document.relative_to(root)}: total source-file summary drift")
    if declared_int(total_rows[0][ledger_index], f"{document.relative_to(root)}: total ledger rows") != total:
        fail(f"{document.relative_to(root)}: total ledger-row summary drift")

    text = document.read_text(encoding="utf-8")
    for pattern in (r"\|P_ledger\|\s*=\s*(\d+)", r"\|unique\(P_ledger\)\|\s*=\s*(\d+)", r"The\s+(\d+)\s+source files"):
        match = re.search(pattern, text)
        if not match or int(match.group(1)) != total:
            fail(f"{document.relative_to(root)}: source-count prose claim does not match {total}")
    primary_counts = Counter(row.primary for rows in rows_by_name.values() for row in rows)
    primary_claim = re.search(r"Primary allocation counts are:\s*(.*?)\.\s+These sum", text, re.DOTALL)
    if not primary_claim:
        fail(f"{document.relative_to(root)}: missing primary allocation count claim")
    primary_pairs = re.findall(r"([A-Z]{2}-\d+)\s+(\d+)", primary_claim.group(1))
    primary_capabilities = [capability for capability, _ in primary_pairs]
    duplicate_capabilities = sorted(
        capability for capability, count in Counter(primary_capabilities).items() if count > 1
    )
    if duplicate_capabilities:
        fail(
            f"{document.relative_to(root)}: duplicate primary allocation count claim entries: "
            f"{duplicate_capabilities}"
        )
    claimed_pairs = {capability: int(count) for capability, count in primary_pairs}
    expected_primary_counts = {group: primary_counts.get(group, 0) for group in groups}
    sum_match = re.search(r"These sum to\s+(\d+)", text)
    if claimed_pairs != expected_primary_counts or sum(claimed_pairs.values()) != total or not sum_match or int(sum_match.group(1)) != total:
        fail(f"{document.relative_to(root)}: primary allocation count claim does not match ledger rows")

    classification_claim = re.search(r"Classification counts are:\s*(.*?)\.\s+Primary allocation", text, re.DOTALL)
    if not classification_claim:
        fail(f"{document.relative_to(root)}: missing classification count claim")
    classification_pairs = [
        (label.strip().removeprefix("and "), int(count))
        for count, label in re.findall(r"`(\d+)`\s+([^,]+)", classification_claim.group(1))
    ]
    classification_labels = [label for label, _ in classification_pairs]
    duplicate_classifications = sorted(
        label for label, count in Counter(classification_labels).items() if count > 1
    )
    if duplicate_classifications:
        fail(
            f"{document.relative_to(root)}: duplicate classification count claim entries: "
            f"{duplicate_classifications}"
        )
    claimed_classifications = dict(classification_pairs)
    actual_classifications = Counter(
        row.classification for ledger_rows in rows_by_name.values() for row in ledger_rows
    )
    if claimed_classifications != dict(actual_classifications):
        fail(f"{document.relative_to(root)}: classification count claim does not match ledger rows")
    return [*direct, *infrastructure, *generated]


def parse_summary_table(
    root: Path,
    document: Path,
    heading: str,
    required_headers: set[str],
) -> tuple[list[str], dict[str, list[str]]]:
    headers, rows = table_in_section(document.read_text(encoding="utf-8"), heading, required_headers)
    key_index = 0
    values: dict[str, list[str]] = {}
    for row in rows:
        key = clean_cell(row[key_index])
        if key in values:
            fail(f"{document.relative_to(root)}:{heading}: duplicate summary row {key}")
        values[key] = row
    return headers, values


def validate_microservice_summary(root: Path, rows: list[LedgerRow], expected_paths: set[str]) -> None:
    document = root / MICROSERVICE_ALLOCATION
    headers, measures = parse_summary_table(root, document, "Coverage Summary", {"Measure", "Count"})
    count_index = headers.index("Count")
    expected_measures = {
        "Markdown sources discovered": len(expected_paths),
        "Capability-allocated sources": sum(row.primary != "Exempt" for row in rows),
        "Explicitly exempt artifacts": sum(row.primary == "Exempt" for row in rows),
        "Unallocated sources": len(expected_paths) - len(rows),
        "Taxonomy gaps": 0,
    }
    if set(measures) != set(expected_measures) | {"Coverage"}:
        fail(f"{document.relative_to(root)}: coverage summary measure rows drifted")
    for name, expected in expected_measures.items():
        if declared_int(measures[name][count_index], f"{document.relative_to(root)}:{name}") != expected:
            fail(f"{document.relative_to(root)}:{name}: coverage summary drift")
    declared_coverage(measures["Coverage"][count_index], f"{document.relative_to(root)}:Coverage", "100%")

    headers, classifications = parse_summary_table(root, document, "Coverage Summary", {"Source classification", "Count"})
    count_index = headers.index("Count")
    actual = Counter(
        {
            "Service overview": sum(
                row.primary != "Exempt" and Path(row.path).name == "README.md" for row in rows
            ),
            "API contract": sum(
                row.primary != "Exempt" and Path(row.path).name == "api-contracts.md" for row in rows
            ),
            "Configuration contract": sum(
                row.primary != "Exempt" and Path(row.path).name == "configuration.md" for row in rows
            ),
            "Operations contract": sum(
                row.primary != "Exempt" and Path(row.path).name == "operations.md" for row in rows
            ),
            "Runtime/data contract": sum(
                row.primary != "Exempt" and Path(row.path).name == "runtime-and-data.md" for row in rows
            ),
        }
    )
    expected_classifications = {
        "Exempt governance/template artifacts": sum(row.primary == "Exempt" for row in rows),
        "Service overviews": actual["Service overview"],
        "API contracts": actual["API contract"],
        "Configuration contracts": actual["Configuration contract"],
        "Operations contracts": actual["Operations contract"],
        "Runtime/data contracts": actual["Runtime/data contract"],
        "Service-specific design, UX, protocol, and workflow appendices": sum(
            1
            for row in rows
            if row.primary != "Exempt"
            and Path(row.path).name
            not in {"README.md", "api-contracts.md", "configuration.md", "operations.md", "runtime-and-data.md"}
        ),
    }
    if set(classifications) != set(expected_classifications) | {"Total"}:
        fail(f"{document.relative_to(root)}: source-classification summary rows drifted")
    for name, expected in expected_classifications.items():
        if declared_int(classifications[name][count_index], f"{document.relative_to(root)}:{name}") != expected:
            fail(f"{document.relative_to(root)}:{name}: classification summary drift")
    if declared_int(classifications["Total"][count_index], f"{document.relative_to(root)}: classification total") != len(rows):
        fail(f"{document.relative_to(root)}: classification total drift")


def expected_top_allocation_rows(
    source_sets: dict[str, set[str]], expected_decisions: set[str]
) -> dict[str, tuple[str, str]]:
    adr_count = len(expected_decisions) - 1
    generated_count = len(source_sets["Generated references"])
    generated_label = "source" if generated_count == 1 else "sources"
    adr_label = "ADR" if adr_count == 1 else "ADRs"
    return {
        "design/project-management/design-alignment/design-capability-allocation-microservices.md": (
            f"All {len(source_sets['Microservice architecture'])} files under `design/architecture/microservices/**`",
            "Per-source allocation",
        ),
        "design/architecture/decisions/README.md": (
            f"Registry plus {adr_count} {adr_label}",
            "Per-record allocation",
        ),
        "design/project-management/design-alignment/design-capability-allocation-system.md": (
            f"All {len(source_sets['Top-level architecture'])} direct architecture, "
            f"{len(source_sets['Infrastructure'])} infrastructure, and {generated_count} generated {generated_label}",
            "Per-source allocation",
        ),
    }


def validate_top_allocation_ledger(
    root: Path,
    groups: set[str],
    expected_decisions: set[str],
    source_sets: dict[str, set[str]],
) -> list[LedgerRow]:
    document = root / TOP_ALLOCATION
    text = document.read_text(encoding="utf-8")
    expected_rows = expected_top_allocation_rows(source_sets, expected_decisions)
    headers, rows = table_in_section(text, "Allocation Ledger", {"Design source", "Heading or scope", "Primary capability"})
    source_index = headers.index("Design source")
    heading_index = headers.index("Heading or scope")
    primary_index = headers.index("Primary capability")
    identities: list[str] = []
    for row in rows:
        targets = MARKDOWN_LINK_RE.findall(row[source_index])
        if len(targets) != 1:
            fail(f"{document.relative_to(root)}: allocation ledger source must have one link")
        source_path = relative_target(document, targets[0], root)
        if source_path in identities:
            fail(f"{document.relative_to(root)}: duplicate allocation ledger row {source_path}")
        identities.append(source_path)
        expected = expected_rows.get(source_path)
        if expected is None:
            fail(f"{document.relative_to(root)}: unexpected allocation ledger row {source_path}")
        if (row[heading_index].strip(), clean_cell(row[primary_index])) != expected:
            fail(
                f"{document.relative_to(root)}:{source_path}: allocation row drift; "
                f"expected heading/primary {expected!r}"
            )
    if set(identities) != set(expected_rows) or len(identities) != len(expected_rows):
        fail(f"{document.relative_to(root)}: allocation ledger references drifted")

    if set(ADR_ALLOCATION_EXPECTATIONS) != expected_decisions:
        fail(f"{document.relative_to(root)}: ADR allocation expectations drifted")
    headers, rows = table_in_section(
        text,
        "Architecture Decision Allocation",
        {"Design source", "Primary capability", "Secondary handoffs", "Status or classification"},
    )
    source_index = headers.index("Design source")
    primary_index = headers.index("Primary capability")
    secondary_index = headers.index("Secondary handoffs")
    classification_index = headers.index("Status or classification")
    parsed: list[LedgerRow] = []
    for row in rows:
        source_path = path_from_code_cell(document, row[source_index], root)
        primary_cell = clean_cell(row[primary_index])
        primary = "Exempt" if primary_cell == "Exempt" else primary_from_cell(document, row[primary_index], groups, root)
        classification = row[classification_index].strip()
        expected = ADR_ALLOCATION_EXPECTATIONS.get(source_path)
        secondary = (
            capability_set_from_cell(
                row[secondary_index],
                f"{document.relative_to(root)}:{source_path}: secondary handoffs",
                groups,
            )
            if expected is not None and len(expected) == 3
            else None
        )
        validate_expected_allocation(
            root,
            document,
            source_path,
            primary,
            classification,
            ADR_ALLOCATION_EXPECTATIONS,
            secondary,
        )
        parsed.append(LedgerRow(source_path, primary, classification))
    paths = [row.path for row in parsed]
    if len(paths) != len(set(paths)) or set(paths) != expected_decisions:
        fail(f"{document.relative_to(root)}: decision allocation manifest mismatch")
    exemptions = [row.path for row in parsed if row.primary == "Exempt"]
    if exemptions != ["design/architecture/decisions/README.md"]:
        fail(f"{document.relative_to(root)}: decision allocation exemptions drifted: {exemptions}")
    return parsed


def parse_explicit_exemptions(cell: str, context: str) -> tuple[int, int]:
    match = re.fullmatch(
        r"(?P<gaps>\d+)(?:;\s*(?P<exemptions>\d+)\s+"
        r"(?:explicit(?:\s+[A-Za-z][A-Za-z0-9/-]*)*|registry)\s+exemptions?)?",
        clean_cell(cell),
    )
    if not match:
        fail(f"{context}: malformed gap/exemption count {cell!r}")
    gap_count = int(match.group("gaps"))
    exemption_count = int(match.group("exemptions") or 0)
    return gap_count, exemption_count


def validate_top_summary(
    root: Path,
    source_sets: dict[str, set[str]],
    system_rows: list[LedgerRow],
    micro_rows: list[LedgerRow],
    decision_rows: list[LedgerRow],
) -> None:
    document = root / TOP_ALLOCATION
    headers, values = parse_summary_table(root, document, "Coverage Summary", {"Source class", "Discovered", "Allocated", "Ambiguous or gap", "Coverage"})
    discovered_index = headers.index("Discovered")
    allocated_index = headers.index("Allocated")
    gap_index = headers.index("Ambiguous or gap")
    coverage_index = headers.index("Coverage")
    allocated_counts = {
        "Top-level architecture": sum(row.primary != "Exempt" for row in system_rows if row.path in source_sets["Top-level architecture"]),
        "Infrastructure": sum(row.primary != "Exempt" for row in system_rows if row.path in source_sets["Infrastructure"]),
        "Generated references": sum(row.primary != "Exempt" for row in system_rows if row.path in source_sets["Generated references"]),
        "Microservice architecture": sum(row.primary != "Exempt" for row in micro_rows),
        "Architecture decisions": sum(row.primary != "Exempt" for row in decision_rows),
    }
    exemption_counts = {
        "Top-level architecture": 0,
        "Infrastructure": 0,
        "Generated references": 0,
        "Microservice architecture": sum(row.primary == "Exempt" for row in micro_rows),
        "Architecture decisions": sum(row.primary == "Exempt" for row in decision_rows),
    }
    if set(values) != set(source_sets) | {"Total"}:
        fail(f"{document.relative_to(root)}: top-level coverage summary rows drifted")
    for name in source_sets:
        expected_discovered = len(source_sets[name])
        actual_discovered = declared_int(values[name][discovered_index], f"{document.relative_to(root)}:{name}: discovered")
        actual_allocated = declared_int(values[name][allocated_index], f"{document.relative_to(root)}:{name}: allocated")
        if actual_discovered != expected_discovered or actual_allocated != allocated_counts[name]:
            fail(f"{document.relative_to(root)}:{name}: coverage summary drift")
        gap_count, exemption_count = parse_explicit_exemptions(values[name][gap_index], f"{document.relative_to(root)}:{name}")
        if gap_count != 0 or exemption_count != exemption_counts[name]:
            fail(f"{document.relative_to(root)}:{name}: gap/exemption summary drift")
        declared_coverage(values[name][coverage_index], f"{document.relative_to(root)}:{name}: coverage", "100% classified")

    total = values["Total"]
    discovered = sum(len(paths) for paths in source_sets.values())
    allocated = sum(allocated_counts.values())
    exemptions = sum(exemption_counts.values())
    if declared_int(total[discovered_index], f"{document.relative_to(root)}: total discovered") != discovered:
        fail(f"{document.relative_to(root)}: total discovered summary drift")
    if declared_int(total[allocated_index], f"{document.relative_to(root)}: total allocated") != allocated:
        fail(f"{document.relative_to(root)}: total allocated summary drift")
    gap_count, exemption_count = parse_explicit_exemptions(total[gap_index], f"{document.relative_to(root)}: total")
    if gap_count != 0 or exemption_count != exemptions:
        fail(f"{document.relative_to(root)}: total gap/exemption summary drift")
    declared_coverage(total[coverage_index], f"{document.relative_to(root)}: total coverage", "100% classified")


def validate(root: Path = ROOT) -> None:
    root = root.resolve()
    source_sets = repository_files(root)
    groups = group_ids(root)
    system_rows = validate_system(root, source_sets, groups)
    micro_rows = parse_microservice_ledger(root, groups, source_sets["Microservice architecture"])
    validate_microservice_summary(root, micro_rows, source_sets["Microservice architecture"])
    decision_rows = validate_top_allocation_ledger(
        root, groups, source_sets["Architecture decisions"], source_sets
    )
    validate_top_summary(root, source_sets, system_rows, micro_rows, decision_rows)
    print(
        "design capability allocation passed: "
        f"{sum(len(paths) for paths in source_sets.values())} sources "
        f"({sum(row.primary != 'Exempt' for row in [*system_rows, *micro_rows, *decision_rows])} allocated, "
        f"{sum(row.primary == 'Exempt' for row in [*system_rows, *micro_rows, *decision_rows])} explicit exemptions)"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root to validate")
    args = parser.parse_args()
    validate(args.root)


if __name__ == "__main__":
    main()
