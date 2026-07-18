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
            "No focused browser test is present; the package exposes scripts only.",
            "audit-context-positive",
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
