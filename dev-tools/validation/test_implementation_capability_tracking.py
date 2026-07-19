#!/usr/bin/env python3
"""Regression checks for capability summaries and evidence-anchor categories."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import textwrap
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "dev-tools/validation/check-implementation-capability-tracking.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("implementation_capability_validator", SCRIPT)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load implementation capability validator")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def expect_failure(label: str, call, expected: str) -> None:
    try:
        call()
    except SystemExit as error:
        if expected not in str(error):
            raise AssertionError(f"{label}: unexpected failure: {error}") from error
    else:
        raise AssertionError(f"{label}: invalid fixture unexpectedly passed")


def main() -> None:
    validator = load_validator()
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        owner = root / "design/project-management/implementation-tracking/tracker.md"
        owner.parent.mkdir(parents=True)
        owner.write_text("# tracker\n", encoding="utf-8")
        for relative in (
            "design/architecture/canonical.md",
            "services/example/src/main/Production.java",
            "services/example/src/test/ProductionTest.java",
            "dev-tools/tests/architecture-doc-contracts.sh",
            "dev-tools/validation/validate-helm.sh",
            "dev-tools/validation/check-example.py",
            "dev-tools/validation/unrelated-helper.py",
            "dev-tools/README.md",
            "dev-tools/verify-restart-state.sh",
            ".github/workflows/ci.yml",
            "k8s/velero/verify-backups-cronjob.yaml",
            "web-client/README.md",
            "web-client/package.json",
        ):
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("# fixture\n", encoding="utf-8")

        validator.validate_evidence_anchor(
            root,
            owner,
            "../../architecture/canonical.md",
            "design",
            "proven",
            "[design](x)",
            "design-positive",
        )
        validator.validate_evidence_anchor(
            root,
            owner,
            "../../../services/example/src/main/Production.java",
            "production",
            "proven",
            "[production](x)",
            "production-positive",
        )
        validator.validate_evidence_anchor(
            root,
            owner,
            "../../../services/example/src/test/ProductionTest.java",
            "proof",
            "proven",
            "[test](x)",
            "proof-positive",
        )
        validator.validate_evidence_anchor(
            root,
            owner,
            "../../../web-client/package.json",
            "proof",
            "audited",
            "No focused executable proof: the package exposes scripts only.",
            "audit-context-positive",
        )
        validator.validate_evidence_anchor(
            root,
            owner,
            "../../../web-client/package.json",
            "proof",
            "unverified",
            "No focused executable proof: the package provides context only.",
            "unverified-context-positive",
        )
        expect_failure(
            "audit context without absence marker",
            lambda: validator.validate_evidence_anchor(
                root,
                owner,
                "../../../web-client/package.json",
                "proof",
                "audited",
                "The package exposes scripts only.",
                "audit-context-negative",
            ),
            "tests or canonical validation/smoke tooling",
        )
        for target, context in (
            ("../../../dev-tools/tests/architecture-doc-contracts.sh", "validation-tool-positive"),
            ("../../../dev-tools/validation/validate-helm.sh", "validation-script-positive"),
            ("../../../dev-tools/verify-restart-state.sh", "smoke-entrypoint-positive"),
            ("../../../.github/workflows/ci.yml", "workflow-positive"),
            ("../../../k8s/velero/verify-backups-cronjob.yaml", "velero-positive"),
        ):
            validator.validate_evidence_anchor(
                root, owner, target, "proof", "proven", "[proof](x)", context
            )
        expect_failure(
            "external evidence",
            lambda: validator.validate_evidence_anchor(
                root, owner, "https://example.com/proof", "proof", "proven", "[proof](x)", "external"
            ),
            "repository-local",
        )
        expect_failure(
            "missing evidence",
            lambda: validator.validate_evidence_anchor(
                root, owner, "../../../services/example/src/main/Missing.java", "production", "proven", "[x](x)", "missing"
            ),
            "missing evidence anchor target",
        )
        expect_failure(
            "production test evidence",
            lambda: validator.validate_evidence_anchor(
                root, owner, "../../../services/example/src/test/ProductionTest.java", "production", "proven", "[x](x)", "production-test"
            ),
            "test-only/docs-only",
        )
        expect_failure(
            "proof design evidence",
            lambda: validator.validate_evidence_anchor(
                root, owner, "../../architecture/canonical.md", "proof", "proven", "[x](x)", "proof-design"
            ),
            "tests or canonical validation/smoke tooling",
        )
        for label, target in (
            (
                "external tracker URL",
                "https://example.test/player-access-and-session.md",
            ),
            (
                "aliased tracker path",
                "../implementation-tracking/player-access-and-session.md",
            ),
        ):
            expect_failure(
                label,
                lambda target=target: validator.linked_tracker_names(f"[tracker]({target})"),
                "canonical relative target",
            )
        for label, target in (
            ("unrelated dev-tools helper", "../../../dev-tools/validation/unrelated-helper.py"),
            ("dev-tools README", "../../../dev-tools/README.md"),
        ):
            expect_failure(
                label,
                lambda target=target, label=label: validator.validate_evidence_anchor(
                    root, owner, target, "proof", "proven", "[proof](x)", label
                ),
                "tests or canonical validation/smoke tooling",
            )

        allocation_path = root / "design/project-management/implementation-tracking/capability-allocation.md"
        allocations = validator.parse_allocations(
            root,
            allocation_path,
            textwrap.dedent(
                """
                | `AA-1.1` | [player-access-and-session.md](./player-access-and-session.md) | [shared-runtime-contracts-and-persistence.md](./shared-runtime-contracts-and-persistence.md); [realm-routing-and-playable-state.md](./realm-routing-and-playable-state.md) | rationale |
                | `AA-1.2` | [player-access-and-session.md](./player-access-and-session.md) |  | rationale |
                """
            ),
        )
        for label, primary, secondary in (
            (
                "primary with unknown local link",
                "[player-access-and-session.md](./player-access-and-session.md); [unknown.md](./unknown.md)",
                "",
            ),
            (
                "primary with external link",
                "[player-access-and-session.md](./player-access-and-session.md); [external](https://example.test/not-declared)",
                "",
            ),
            (
                "secondary with unknown local link",
                "[player-access-and-session.md](./player-access-and-session.md)",
                "[shared-runtime-contracts-and-persistence.md](./shared-runtime-contracts-and-persistence.md); [unknown.md](./unknown.md)",
            ),
            (
                "secondary with external link",
                "[player-access-and-session.md](./player-access-and-session.md)",
                "[shared-runtime-contracts-and-persistence.md](./shared-runtime-contracts-and-persistence.md); [external](https://example.test/not-declared)",
            ),
        ):
            expect_failure(
                label,
                lambda primary=primary, secondary=secondary: validator.parse_allocations(
                    root,
                    allocation_path,
                    f"| `AA-1.1` | {primary} | {secondary} | rationale |",
                ),
                "canonical relative target",
            )
        expect_failure(
            "primary repeated as secondary",
            lambda: validator.parse_allocations(
                root,
                allocation_path,
                "| `AA-1.1` | [player-access-and-session.md](./player-access-and-session.md) "
                "| [player-access-and-session.md](./player-access-and-session.md) | rationale |",
            ),
            "primary tracker must not be repeated as a secondary tracker",
        )
        validator.validate_evidence_presence(
            "not-implemented",
            "unverified",
            ["[design](x)", "No production anchor: no implementation exists.", "[proof](x)"],
            "absent-production-positive",
        )
        validator.validate_evidence_presence(
            "not-implemented",
            "unverified",
            [
                "[design](x)",
                "No production anchor: adjacent [implementation](x) is context only.",
                "No focused executable proof: no production boundary exists.",
            ],
            "absent-production-with-context-positive",
        )
        validator.validate_evidence_presence(
            "partial",
            "audited",
            [
                "[design](x)",
                "[implementation](x)",
                "No focused executable proof: [package](../../../web-client/package.json) is audit context only.",
            ],
            "audited-proof-absence-positive",
        )
        expect_failure(
            "audit context does not satisfy proof presence",
            lambda: validator.validate_evidence_presence(
                "partial",
                "audited",
                [
                    "[design](x)",
                    "[implementation](x)",
                    "[package](../../../web-client/package.json) is audit context only.",
                ],
                "audited-proof-absence-negative",
            ),
            "audited or unverified rows without one must use No focused executable proof:",
        )
        validator.validate_evidence_presence(
            "not-implemented",
            "unverified",
            [
                "[design](x)",
                "No production anchor: no implementation exists.",
                "No focused executable proof: no production boundary exists.",
            ],
            "unverified-proof-absence-positive",
        )
        expect_failure(
            "unverified row without explicit proof absence",
            lambda: validator.validate_evidence_presence(
                "not-implemented",
                "unverified",
                [
                    "[design](x)",
                    "No production anchor: no implementation exists.",
                    "No focused proof exists.",
                ],
                "unverified-proof-absence-negative",
            ),
            "audited or unverified rows without one must use No focused executable proof:",
        )
        expect_failure(
            "missing production anchor without explicit rationale",
            lambda: validator.validate_evidence_presence(
                "not-implemented",
                "unverified",
                ["[design](x)", "No implementation exists.", "[proof](x)"],
                "absent-production-negative",
            ),
            "not-implemented rows must use an explicit No production anchor: rationale",
        )
        expect_failure(
            "not-implemented context links without explicit rationale",
            lambda: validator.validate_evidence_presence(
                "not-implemented",
                "unverified",
                ["[design](x)", "Adjacent [implementation](x) is context only.", "[proof](x)"],
                "absent-production-context-negative",
            ),
            "not-implemented rows must use an explicit No production anchor: rationale",
        )
        expect_failure(
            "implemented row without production anchor",
            lambda: validator.validate_evidence_presence(
                "implemented",
                "proven",
                ["[design](x)", "No production anchor: missing.", "[proof](x)"],
                "implemented-production-negative",
            ),
            "implementation evidence must include a repository link",
        )
        allowed_handoffs = allocations["AA-1.1"][1]
        validator.validate_status_row_handoffs(
            "player-access-and-session.md",
            "AA-1.1",
            allowed_handoffs,
            allocations,
            "AA-1.1 handoff: [shared-runtime-contracts-and-persistence.md](./shared-runtime-contracts-and-persistence.md)",
        )
        validator.validate_status_row_handoffs(
            "player-access-and-session.md",
            "AA-1.2",
            allocations["AA-1.2"][1],
            allocations,
            "",
        )
        validator.validate_status_row_handoffs(
            "player-access-and-session.md",
            "AA-1.2",
            allocations["AA-1.2"][1],
            allocations,
            "No secondary tracker handoff applies.",
        )
        validator.validate_status_row_handoffs(
            "player-access-and-session.md",
            "AA-1.1",
            allowed_handoffs,
            allocations,
            "Related [AA-1.2](./player-access-and-session.md) capability in this tracker.",
        )
        allocations["ZZ-1.1"] = ("gameplay-rules-entities-and-effects.md", set())
        validator.validate_status_row_handoffs(
            "player-access-and-session.md",
            "AA-1.1",
            allowed_handoffs,
            allocations,
            "Related [ZZ-1.1](./gameplay-rules-entities-and-effects.md) capability in another tracker.",
        )
        expect_failure(
            "extra status-row handoff",
            lambda: validator.validate_status_row_handoffs(
                "player-access-and-session.md",
                "AA-1.2",
                allocations["AA-1.2"][1],
                allocations,
                "[shared-runtime-contracts-and-persistence.md](./shared-runtime-contracts-and-persistence.md)",
            ),
            "unexpected status-row secondary handoffs",
        )
        expect_failure(
            "wrong status-row handoff",
            lambda: validator.validate_status_row_handoffs(
                "player-access-and-session.md",
                "AA-1.1",
                allowed_handoffs,
                allocations,
                "[gameplay-rules-entities-and-effects.md](./gameplay-rules-entities-and-effects.md)",
            ),
            "unexpected status-row secondary handoffs",
        )
        expect_failure(
            "unknown related capability",
            lambda: validator.validate_status_row_handoffs(
                "player-access-and-session.md",
                "AA-1.1",
                allowed_handoffs,
                allocations,
                "Related `ZZ-9.9` capability.",
            ),
            "unknown capability handoffs",
        )
        for label, handoff in (
            (
                "external non-tracker status-row link",
                "[external](https://example.test/not-declared)",
            ),
            (
                "existing local non-tracker status-row link",
                "[allocation](./capability-allocation.md)",
            ),
        ):
            expect_failure(
                label,
                lambda handoff=handoff: validator.validate_status_row_handoffs(
                    "player-access-and-session.md",
                    "AA-1.1",
                    allowed_handoffs,
                    allocations,
                    handoff,
                ),
                "canonical relative target",
            )

    summary = textwrap.dedent(
        """
        ## Coverage Summary

        | Measure | Result |
        | --- | ---: |
        | Taxonomy leaf capabilities | 2 |
        | Unique allocated capability IDs | 2 |
        | Primary tracker files represented | 2 of 2 |
        | Missing or unassigned leaves | 0 |
        | Duplicate primary allocations | 0 |

        | Primary tracker | Primary leaves |
        | --- | ---: |
        | [a](./a.md) | 1 |
        | [b](./b.md) | 1 |
        | **Total** | **2** |
        """
    )
    allocations = {"AA-1.1": ("a.md", set()), "AA-1.2": ("b.md", set())}
    summary_path = Path("design/project-management/implementation-tracking/capability-allocation.md")
    validator.validate_coverage_summary(summary_path, summary, {"AA-1.1", "AA-1.2"}, allocations, {"a.md", "b.md"})
    expect_failure(
        "duplicate Coverage Summary section",
        lambda: validator.validate_coverage_summary(
            summary_path,
            summary + summary,
            {"AA-1.1", "AA-1.2"},
            allocations,
            {"a.md", "b.md"},
        ),
        "expected exactly one Coverage Summary section",
    )
    duplicate_measure_table = summary.replace(
        "| Primary tracker | Primary leaves |",
        "| Measure | Result |\n| --- | ---: |\n| Duplicate | 0 |\n\n"
        "| Primary tracker | Primary leaves |",
    )
    expect_failure(
        "duplicate Coverage Summary measure table",
        lambda: validator.validate_coverage_summary(
            summary_path,
            duplicate_measure_table,
            {"AA-1.1", "AA-1.2"},
            allocations,
            {"a.md", "b.md"},
        ),
        "must contain exactly one measure table",
    )
    duplicate_tracker_table = summary + """
| Primary tracker | Primary leaves |
| --- | ---: |
| [a](./a.md) | 1 |
| [b](./b.md) | 1 |
| **Total** | **2** |
"""
    expect_failure(
        "duplicate Coverage Summary tracker table",
        lambda: validator.validate_coverage_summary(
            summary_path,
            duplicate_tracker_table,
            {"AA-1.1", "AA-1.2"},
            allocations,
            {"a.md", "b.md"},
        ),
        "one primary-tracker table",
    )
    expect_failure(
        "duplicate Capability Status section",
        lambda: validator.level_two_section(
            "## Capability Status\n\n## Capability Status\n",
            "Capability Status",
            "tracker.md",
        ),
        "expected exactly one Capability Status section",
    )
    expect_failure(
        "non-exact Capability Status section",
        lambda: validator.level_two_section(
            "### Capability Status\n",
            "Capability Status",
            "tracker.md",
        ),
        "expected exactly one Capability Status section",
    )
    expect_failure(
        "duplicate coverage-summary measure",
        lambda: validator.validate_coverage_summary(
            summary_path,
            summary.replace(
                "| Taxonomy leaf capabilities | 2 |",
                "| Taxonomy leaf capabilities | 999 |\n| Taxonomy leaf capabilities | 2 |",
            ),
            {"AA-1.1", "AA-1.2"},
            allocations,
            {"a.md", "b.md"},
        ),
        "duplicate Coverage Summary measure Taxonomy leaf capabilities",
    )
    expect_failure(
        "per-tracker summary drift",
        lambda: validator.validate_coverage_summary(
            summary_path, summary.replace("| [a](./a.md) | 1 |", "| [a](./a.md) | 2 |"), {"AA-1.1", "AA-1.2"}, allocations, {"a.md", "b.md"}
        ),
        "per-tracker allocation totals drifted",
    )
    for label, target in (
        ("external Coverage Summary tracker", "https://example.test/a.md"),
        ("aliased Coverage Summary tracker", "../implementation-tracking/a.md"),
        ("anchored Coverage Summary tracker", "./a.md#capability-status"),
    ):
        expect_failure(
            label,
            lambda target=target: validator.validate_coverage_summary(
                summary_path,
                summary.replace("./a.md", target),
                {"AA-1.1", "AA-1.2"},
                allocations,
                {"a.md", "b.md"},
            ),
            "canonical relative target",
        )
    print("implementation capability tracking regression tests passed")


if __name__ == "__main__":
    main()
