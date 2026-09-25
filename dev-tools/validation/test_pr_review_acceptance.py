#!/usr/bin/env python3
"""End-to-end contracts for the isolated ``pr-review`` acceptance mode."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli, state
from pr_review.acceptance import AcceptanceFixtureError, FixtureReviewAdapter, load

BASE = "a" * 40
HEAD_1 = "b" * 40
HEAD_2 = "c" * 40
LEGACY_HEAD = "d" * 40


def fixture_payload() -> dict[str, object]:
    return {
        "repository": "fixture/firemud",
        "default_base_ref": "develop",
        "default_base_tip": BASE,
        "ordered_prs": [1, 2],
        "branch_heads": {"develop": BASE, "feature-1": HEAD_1, "feature-2": HEAD_2},
        "ancestors": [[BASE, HEAD_1], [HEAD_1, HEAD_2], [BASE, HEAD_2]],
        "merge_bases": {f"{BASE}...{HEAD_1}": BASE, f"{HEAD_1}...{HEAD_2}": HEAD_1},
        "patch_ids": {f"{BASE}...{HEAD_1}": "patch-1", f"{HEAD_1}...{HEAD_2}": "patch-2"},
        "pull_requests": [
            {
                "number": 1,
                "base_ref": "develop",
                "base_tip": BASE,
                "head": HEAD_1,
                "head_ref": "feature-1",
                "head_repository": "fixture/firemud",
                "changed_files": 3,
            },
            {
                "number": 2,
                "base_ref": "feature-1",
                "base_tip": HEAD_1,
                "head": HEAD_2,
                "head_ref": "feature-2",
                "head_repository": "fixture/firemud",
                "changed_files": 4,
            },
        ],
        "evidence": {
            "1": {
                "hosted": [],
                "cli": [
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": "cli-1",
                        "completed": True,
                        "attributable": True,
                        "anchored": True,
                        "corrected_state": True,
                        "accepted": 0,
                        "raw": 0,
                        "parent_identity": "develop",
                        "parent_head": BASE,
                        "merge_base": BASE,
                        "patch_id": "patch-1",
                    }
                ],
            }
        },
    }


def fixture_payload_with_uppercase_default_sha() -> dict[str, object]:
    payload = fixture_payload()
    payload["default_base_tip"] = BASE
    payload["branch_heads"] = {"develop": BASE.upper(), "feature-1": HEAD_1, "feature-2": HEAD_2}
    return payload


def fixture_payload_for_allocation() -> dict[str, object]:
    payload = fixture_payload()
    payload["ordered_prs"] = [1]
    payload["pull_requests"] = [payload["pull_requests"][0]]
    payload["review_results"] = {
        "1": {
            "hosted": [
                {"status": "productive", "record_evidence": True, "accepted": 1, "raw": 1},
            ],
            "cli": [
                {"status": "rate_limited"},
                {"status": "partial"},
                {"status": "stale"},
                {"status": "duplicate"},
            ],
        }
    }
    return payload


class AcceptanceCliTest(unittest.TestCase):
    def run_cli(self, fixture: Path, isolated: Path, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                str(ROOT / "dev-tools/pr-review"),
                "--acceptance-fixture",
                str(fixture),
                "--state-path",
                str(isolated),
                *args,
            ],
            cwd=ROOT,
            env={**os.environ, "PYTHONPATH": str(ROOT / "dev-tools")},
            capture_output=True,
            text=True,
            check=False,
        )

    def test_live_status_never_calls_an_exhausted_allocation_merge_ready(self):
        report = {
            "pull_request": {"headRefOid": HEAD_1, "baseRefName": "develop", "baseRefOid": BASE},
            "reasons": [],
            "ready": True,
            "verdict": "READY",
            "mergeability": {"clean": True, "diagnosis": "READY"},
        }
        controller = SimpleNamespace(
            repository="fixture/firemud",
            store=SimpleNamespace(load=lambda: SimpleNamespace(summary_dispositions=())),
            status=lambda: {
                "prs": [{
                    "pr": 1, "head": HEAD_1, "base": "develop", "parent_head": BASE,
                    "reconciliation": "COHERENT", "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                    "allocations": {"hosted": {"status": "EXHAUSTED_PENDING", "reason": "handoff pending"}},
                }]
            },
        )
        args = cli._parser().parse_args(["status", "--pr", "1", "--json"])
        with patch("pr_review.cli._controller", return_value=(controller, None)), patch(
            "pr_review.cli.status_module.status", return_value=report
        ):
            result, code = cli._dispatch(args)

        self.assertEqual(code, 0)
        self.assertFalse(result["ready"])
        self.assertIn("hosted review allocation is EXHAUSTED_PENDING", result["reasons"][0])

    def test_public_commands_use_isolated_state_and_simulated_review_adapters(self):
        canonical = state.state_path()
        before = canonical.read_bytes() if canonical.exists() else None
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.json"
            isolated = root / "acceptance-state.json"
            fixture.write_text(json.dumps(fixture_payload()), encoding="utf-8")

            self.assertEqual(self.run_cli(fixture, isolated, "stack", "set", "1", "2").returncode, 0)
            shown = self.run_cli(fixture, isolated, "stack", "show")
            self.assertEqual(shown.returncode, 0, shown.stderr)
            self.assertIn("ordered_prs=[1, 2]", shown.stdout)

            status = self.run_cli(fixture, isolated, "status", "--pr", "1", "--json")
            self.assertEqual(status.returncode, 0, status.stderr)
            status_json = json.loads(status.stdout)
            self.assertTrue(status_json["acceptance_fixture"]["isolated"])
            self.assertEqual(status_json["prs"][0]["parent"], "develop")

            whole_status = self.run_cli(fixture, isolated, "status", "--json")
            self.assertEqual(whole_status.returncode, 0, whole_status.stderr)
            self.assertEqual(
                json.loads(whole_status.stdout)["acceptance_fixture"],
                {"isolated": True, "network": False, "review_quota": False},
            )

            evidence = self.run_cli(fixture, isolated, "evidence", "1", "--json")
            self.assertEqual(evidence.returncode, 0, evidence.stderr)
            self.assertEqual(json.loads(evidence.stdout)["checkpoints"], [])

            hosted = self.run_cli(fixture, isolated, "run", "hosted", "--expect-pr", "1")
            self.assertEqual(hosted.returncode, 0, hosted.stderr)
            self.assertIn("quota_consumed=False", hosted.stdout)
            cli = self.run_cli(fixture, isolated, "run", "cli", "--expect-pr", "1")
            self.assertEqual(cli.returncode, 0, cli.stderr)
            self.assertIn("simulated=True", cli.stdout)

            judgment = self.run_cli(
                fixture,
                isolated,
                "decide",
                "judgment",
                "retain",
                "--pr",
                "1",
                "--channel",
                "cli",
                "--head",
                HEAD_1,
                "--checkpoint",
                "cli-1",
                "--reason",
                "acceptance proof",
            )
            self.assertEqual(judgment.returncode, 0, judgment.stderr)
            persisted = json.loads(isolated.read_text(encoding="utf-8"))
            self.assertEqual(persisted["ordered_prs"], [1, 2])
            self.assertEqual(persisted["judgments"][0]["pr"], 1)
            self.assertEqual(canonical.read_bytes() if canonical.exists() else None, before)

    def test_controller_seeds_initial_stack_only_for_new_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.json"
            fixture.write_text(json.dumps(fixture_payload()), encoding="utf-8")
            fresh_state = root / "fresh-state.json"

            fresh = load(fixture, fresh_state)
            self.assertEqual(fresh.controller().show_stack()["ordered_prs"], [1, 2])
            self.assertEqual(state.StateStore(fresh_state).load().ordered_prs, (1, 2))

            existing_state = root / "existing-state.json"
            state.StateStore(existing_state).save(state.ReviewState(ordered_prs=(2,)))
            existing = load(fixture, existing_state)
            self.assertEqual(existing.controller().show_stack()["ordered_prs"], [2])
            self.assertEqual(state.StateStore(existing_state).load().ordered_prs, (2,))

    def test_second_identical_provisional_cli_run_uses_isolated_evidence_to_fail(self):
        canonical = state.state_path()
        before = canonical.read_bytes() if canonical.exists() else None
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "unreconciled.json"
            isolated = root / "state.json"
            payload = fixture_payload()
            payload["ancestors"] = []
            payload["evidence"]["1"]["cli"] = []
            fixture.write_text(json.dumps(payload), encoding="utf-8")

            self.assertEqual(self.run_cli(fixture, isolated, "stack", "set", "1").returncode, 0)
            command = (
                "run", "cli", "--expect-pr", "1", "--allow-unreconciled", "--reason", "one isolated discovery"
            )
            first = self.run_cli(fixture, isolated, *command)
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertIn("provisional=True", first.stdout)

            evidence = self.run_cli(fixture, isolated, "evidence", "1", "--json")
            self.assertEqual(evidence.returncode, 0, evidence.stderr)
            recorded = json.loads(evidence.stdout)["policy"]["cli"]
            self.assertEqual(len(recorded), 1)
            self.assertTrue(recorded[0]["provisional"])
            self.assertFalse(recorded[0]["completed"])
            self.assertFalse(recorded[0]["attributable"])
            self.assertEqual(recorded[0]["head"], HEAD_1)
            self.assertEqual(recorded[0]["parent_head"], BASE)

            second = self.run_cli(fixture, isolated, *command)
            self.assertEqual(second.returncode, 1)
            self.assertIn("already recorded", second.stderr)
            self.assertEqual(json.loads(isolated.read_text(encoding="utf-8"))["ordered_prs"], [1])
            sidecar = root / "state.json.fixture-evidence.json"
            self.assertEqual(len(json.loads(sidecar.read_text(encoding="utf-8"))["evidence"]), 1)
            self.assertEqual(canonical.read_bytes() if canonical.exists() else None, before)

    def test_legacy_transition_and_fresh_reviews_are_isolated_and_no_quota(self):
        canonical = state.state_path()
        before = canonical.read_bytes() if canonical.exists() else None
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "legacy.json"
            isolated = root / "state.json"
            payload = fixture_payload()
            payload["evidence"]["1"] = {
                "hosted": [
                    {
                        "pr": 1,
                        "head": LEGACY_HEAD,
                        "checkpoint": "trigger-uncheckpointed:42",
                        "held": True,
                    }
                ],
                "cli": [
                    {
                        "pr": 1,
                        "head": LEGACY_HEAD,
                        "checkpoint": "legacy-cli",
                        "completed": True,
                        "attributable": True,
                        "anchored": False,
                        "child_head": LEGACY_HEAD,
                        "corrected_state": True,
                        "accepted": 3,
                        "raw": 4,
                        "parent_head": "9" * 40,
                        "patch_id": "",
                    }
                ],
            }
            fixture.write_text(json.dumps(payload), encoding="utf-8")

            self.assertEqual(self.run_cli(fixture, isolated, "stack", "set", "1").returncode, 0)
            before_transition = self.run_cli(fixture, isolated, "status", "--json")
            self.assertEqual(before_transition.returncode, 0, before_transition.stderr)
            self.assertEqual(before_transition and json.loads(before_transition.stdout)["prs"][0]["reconciliation"], "PARENT_MOVED")

            transition = self.run_cli(
                fixture,
                isolated,
                "decide",
                "transition",
                "--pr",
                "1",
                "--head",
                HEAD_1,
                "--reason",
                "legacy review records lack modern anchors",
            )
            self.assertEqual(transition.returncode, 0, transition.stderr)
            transitioned = json.loads(self.run_cli(fixture, isolated, "status", "--json").stdout)
            self.assertEqual(transitioned["prs"][0]["reconciliation"], "COHERENT")
            self.assertEqual(transitioned["prs"][0]["channels"], {"hosted": "READY", "cli": "READY"})

            hosted = self.run_cli(fixture, isolated, "run", "hosted", "--expect-pr", "1")
            cli = self.run_cli(fixture, isolated, "run", "cli", "--expect-pr", "1")
            self.assertEqual(hosted.returncode, 0, hosted.stderr)
            self.assertEqual(cli.returncode, 0, cli.stderr)
            self.assertIn("quota_consumed=False", hosted.stdout)
            self.assertIn("quota_consumed=False", cli.stdout)

            evidence = json.loads(self.run_cli(fixture, isolated, "evidence", "1", "--json").stdout)
            self.assertEqual(evidence["policy"]["cli"][0]["checkpoint"], "legacy-cli")
            self.assertEqual(json.loads(isolated.read_text(encoding="utf-8"))["legacy_transitions"][0]["pr"], 1)
            self.assertEqual(canonical.read_bytes() if canonical.exists() else None, before)

    def test_legacy_reauthorization_after_fix_head_is_explicit_and_no_quota(self):
        canonical = state.state_path()
        before = canonical.read_bytes() if canonical.exists() else None
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "legacy-reauthorization.json"
            isolated = root / "state.json"
            payload = fixture_payload()
            payload["evidence"]["1"] = {
                "hosted": [
                    {
                        "pr": 1,
                        "head": LEGACY_HEAD,
                        "checkpoint": "trigger-uncheckpointed:42",
                        "held": True,
                    }
                ],
                "cli": [
                    {
                        "pr": 1,
                        "head": LEGACY_HEAD,
                        "checkpoint": "legacy-cli",
                        "completed": True,
                        "attributable": True,
                        "anchored": False,
                        "child_head": LEGACY_HEAD,
                        "corrected_state": True,
                        "accepted": 3,
                        "raw": 4,
                        "parent_head": "9" * 40,
                        "patch_id": "",
                    }
                ],
            }
            fixture.write_text(json.dumps(payload), encoding="utf-8")

            self.assertEqual(self.run_cli(fixture, isolated, "stack", "set", "1").returncode, 0)
            initial = self.run_cli(
                fixture,
                isolated,
                "decide",
                "transition",
                "--pr",
                "1",
                "--head",
                HEAD_1,
                "--reason",
                "retire the unanchored legacy observations",
            )
            self.assertEqual(initial.returncode, 0, initial.stderr)

            payload["evidence"]["1"]["hosted"] = [
                {
                    "pr": 1,
                    "head": LEGACY_HEAD,
                    "checkpoint": "trigger-uncheckpointed:42",
                    "held": True,
                },
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "modern-hosted",
                    "completed": True,
                    "attributable": True,
                    "anchored": True,
                    "corrected_state": True,
                    "accepted": 1,
                    "raw": 1,
                    "child_head": HEAD_1,
                    "parent_identity": "develop",
                    "parent_head": BASE,
                    "merge_base": BASE,
                    "patch_id": "patch-1",
                },
            ]
            payload["branch_heads"]["feature-1"] = HEAD_2
            payload["pull_requests"][0]["head"] = HEAD_2
            payload["merge_bases"][f"{BASE}...{HEAD_2}"] = BASE
            payload["patch_ids"][f"{BASE}...{HEAD_2}"] = "patch-advanced"
            fixture.write_text(json.dumps(payload), encoding="utf-8")

            blocked = self.run_cli(fixture, isolated, "status", "--json")
            self.assertEqual(blocked.returncode, 0, blocked.stderr)
            blocked_status = json.loads(blocked.stdout)["prs"][0]
            self.assertEqual(blocked_status["reconciliation"], "COHERENT")
            self.assertEqual(blocked_status["channels"]["hosted"], "HELD")

            implicit = self.run_cli(
                fixture,
                isolated,
                "decide",
                "transition",
                "--pr",
                "1",
                "--head",
                HEAD_2,
                "--reason",
                "must require explicit reauthorization",
            )
            self.assertEqual(implicit.returncode, 1)
            self.assertIn("explicit reauthorization", implicit.stderr)

            payload["evidence"]["1"]["hosted"].append(
                {
                    "pr": 1,
                    "head": HEAD_2,
                    "checkpoint": "trigger:99",
                    "held": True,
                }
            )
            fixture.write_text(json.dumps(payload), encoding="utf-8")
            active = self.run_cli(
                fixture,
                isolated,
                "decide",
                "transition",
                "--pr",
                "1",
                "--head",
                HEAD_2,
                "--reason",
                "must not dismiss a newer active reservation",
                "--reauthorize",
            )
            self.assertEqual(active.returncode, 1)
            self.assertIn("active or ambiguous", active.stderr)
            payload["evidence"]["1"]["hosted"].pop()
            fixture.write_text(json.dumps(payload), encoding="utf-8")

            reauthorized = self.run_cli(
                fixture,
                isolated,
                "decide",
                "transition",
                "--pr",
                "1",
                "--head",
                HEAD_2,
                "--reason",
                "carry the same immutable legacy observations to the fixed head",
                "--reauthorize",
            )
            self.assertEqual(reauthorized.returncode, 0, reauthorized.stderr)
            transitioned = json.loads(self.run_cli(fixture, isolated, "status", "--json").stdout)
            self.assertEqual(transitioned["prs"][0]["channels"], {"hosted": "READY", "cli": "READY"})

            hosted = self.run_cli(fixture, isolated, "run", "hosted", "--expect-pr", "1")
            cli_run = self.run_cli(fixture, isolated, "run", "cli", "--expect-pr", "1")
            self.assertEqual(hosted.returncode, 0, hosted.stderr)
            self.assertEqual(cli_run.returncode, 0, cli_run.stderr)
            self.assertIn("quota_consumed=False", hosted.stdout)
            self.assertIn("quota_consumed=False", cli_run.stdout)

            evidence = json.loads(self.run_cli(fixture, isolated, "evidence", "1", "--json").stdout)
            self.assertEqual(evidence["policy"]["hosted"][1]["checkpoint"], "modern-hosted")
            self.assertEqual(len(json.loads(isolated.read_text(encoding="utf-8"))["legacy_transitions"]), 2)
            self.assertEqual(canonical.read_bytes() if canonical.exists() else None, before)

    def test_missing_hosted_fingerprint_retirement_is_audited_and_no_quota(self):
        canonical = state.state_path()
        before = canonical.read_bytes() if canonical.exists() else None
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "missing-hosted-retirement.json"
            isolated = root / "state.json"
            payload = fixture_payload()
            legacy_hosted = {
                "pr": 1,
                "head": LEGACY_HEAD,
                "checkpoint": "trigger-uncheckpointed:42",
                "held": True,
            }
            legacy_cli = {
                "pr": 1,
                "head": LEGACY_HEAD,
                "checkpoint": "legacy-cli",
                "completed": True,
                "attributable": True,
                "anchored": False,
                "child_head": LEGACY_HEAD,
                "corrected_state": True,
                "accepted": 3,
                "raw": 4,
                "parent_head": "9" * 40,
                "patch_id": "",
            }
            payload["evidence"]["1"] = {"hosted": [legacy_hosted], "cli": [legacy_cli]}
            fixture.write_text(json.dumps(payload), encoding="utf-8")

            self.assertEqual(self.run_cli(fixture, isolated, "stack", "set", "1").returncode, 0)
            initial = self.run_cli(
                fixture,
                isolated,
                "decide",
                "transition",
                "--pr",
                "1",
                "--head",
                HEAD_1,
                "--reason",
                "retire the unanchored legacy observations",
            )
            self.assertEqual(initial.returncode, 0, initial.stderr)
            fingerprint = state.observation_fingerprint(legacy_hosted)

            payload["evidence"]["1"]["hosted"] = [
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "modern-hosted",
                    "completed": True,
                    "attributable": True,
                    "anchored": True,
                    "corrected_state": True,
                    "accepted": 1,
                    "raw": 1,
                    "child_head": HEAD_1,
                    "parent_identity": "develop",
                    "parent_head": BASE,
                    "merge_base": BASE,
                    "patch_id": "patch-1",
                }
            ]
            payload["branch_heads"]["feature-1"] = HEAD_2
            payload["pull_requests"][0]["head"] = HEAD_2
            payload["merge_bases"][f"{BASE}...{HEAD_2}"] = BASE
            payload["patch_ids"][f"{BASE}...{HEAD_2}"] = "patch-advanced"
            fixture.write_text(json.dumps(payload), encoding="utf-8")

            result = self.run_cli(
                fixture,
                isolated,
                "decide",
                "transition",
                "--pr",
                "1",
                "--head",
                HEAD_2,
                "--reason",
                "retire the exact missing Hosted fingerprint",
                "--reauthorize",
                "--retire-missing-hosted-fingerprint",
                fingerprint,
                "--json",
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            decision = json.loads(result.stdout)
            self.assertEqual(decision["retirement"]["retired_missing_hosted_fingerprints"], [fingerprint])
            status = json.loads(self.run_cli(fixture, isolated, "status", "--json").stdout)
            self.assertEqual(
                status["legacy_transitions"][-1]["retired_hosted_fingerprints"], [fingerprint]
            )

            payload["evidence"]["1"]["hosted"].append(legacy_hosted)
            fixture.write_text(json.dumps(payload), encoding="utf-8")
            raw = json.loads(self.run_cli(fixture, isolated, "evidence", "1", "--json").stdout)
            self.assertIn(legacy_hosted, raw["policy"]["hosted"])
            self.assertEqual(
                json.loads(self.run_cli(fixture, isolated, "status", "--json").stdout)["prs"][0]["channels"],
                {"hosted": "READY", "cli": "READY"},
            )
            for channel in ("hosted", "cli"):
                run = self.run_cli(fixture, isolated, "run", channel, "--expect-pr", "1")
                self.assertEqual(run.returncode, 0, run.stderr)
                self.assertIn("quota_consumed=False", run.stdout)
            self.assertEqual(canonical.read_bytes() if canonical.exists() else None, before)

    def test_default_base_tip_accepts_case_differences_in_fixture_branch_sha(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.json"
            fixture.write_text(json.dumps(fixture_payload_with_uppercase_default_sha()), encoding="utf-8")
            loaded = load(fixture, root / "acceptance-state.json")
            self.assertEqual(loaded.git.branch_head("develop"), BASE)

    def test_foreign_head_repository_is_rejected_before_acceptance_stack_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.json"
            isolated = root / "state.json"
            payload = fixture_payload()
            payload["pull_requests"][0]["head_repository"] = "outside/fork"
            fixture.write_text(json.dumps(payload), encoding="utf-8")

            result = self.run_cli(fixture, isolated, "stack", "set", "1")

            self.assertEqual(result.returncode, 1)
            self.assertIn("unsupported cross-repository head", result.stderr)
            self.assertEqual(state.StateStore(isolated).load().ordered_prs, (1, 2))

    def test_acceptance_fixture_requires_explicit_head_repository_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.json"
            payload = fixture_payload()
            del payload["pull_requests"][0]["head_repository"]
            fixture.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(AcceptanceFixtureError, "head_repository"):
                load(fixture, root / "acceptance-state.json")

    def test_live_whole_stack_status_does_not_claim_fixture_isolation(self):
        args = cli._parser().parse_args(["status", "--json"])
        with patch.object(cli, "default_controller") as factory:
            factory.return_value.status.return_value = {"ordered_prs": [], "status": "EMPTY"}
            report, exit_status = cli._dispatch(args)
        self.assertEqual(exit_status, 0)
        self.assertNotIn("acceptance_fixture", report)

    def test_fixture_options_are_paired_and_canonical_state_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.json"
            fixture.write_text(json.dumps(fixture_payload()), encoding="utf-8")
            with self.assertRaises(AcceptanceFixtureError):
                load(fixture, state.state_path())
            with self.assertRaisesRegex(AcceptanceFixtureError, "separate from canonical"):
                load(fixture, state.state_path().with_name("other-isolated-state.json"))
            with self.assertRaisesRegex(AcceptanceFixtureError, "mode 0700"):
                load(fixture, Path(tempfile.gettempdir()) / "unsafe-shared-state.json")
            missing_pair = subprocess.run(
                [str(ROOT / "dev-tools/pr-review"), "--acceptance-fixture", str(fixture), "stack", "show"],
                cwd=ROOT,
                env={**os.environ, "PYTHONPATH": str(ROOT / "dev-tools")},
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(missing_pair.returncode, 2)
            self.assertIn("supplied together", missing_pair.stderr)

    def test_fixture_review_commands_never_expose_live_trigger_mutations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.json"
            isolated = root / "state.json"
            fixture.write_text(json.dumps(fixture_payload()), encoding="utf-8")
            self.assertEqual(self.run_cli(fixture, isolated, "stack", "set", "1").returncode, 0)
            result = self.run_cli(
                fixture,
                isolated,
                "decide",
                "trigger-retire",
                "--pr",
                "1",
                "--trigger-id",
                "9",
                "--head",
                HEAD_1,
                "--reason",
                "not allowed in fixture mode",
            )
            self.assertEqual(result.returncode, 2)
            self.assertIn("unavailable in acceptance fixture mode", result.stderr)
            fixture_common = root / "fixture-git-common"
            fixture_request_lock = fixture_common / "firemud" / "hosted" / "fixture_firemud" / "pr-1" / "request.lock"
            canonical_request_lock = (
                state.state_path().parent / "hosted" / "fixture_firemud" / "pr-1" / "request.lock"
            )
            self.assertFalse(fixture_request_lock.exists())
            self.assertFalse(canonical_request_lock.exists())

    def test_allocation_fixture_consumes_one_result_and_supports_corrected_head_handoff(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.json"
            isolated = root / "state.json"
            payload = fixture_payload_for_allocation()
            fixture.write_text(json.dumps(payload), encoding="utf-8")

            self.assertEqual(self.run_cli(fixture, isolated, "stack", "set", "1").returncode, 0)
            grant = self.run_cli(
                fixture,
                isolated,
                "decide",
                "allocation",
                "grant",
                "--pr",
                "1",
                "--channel",
                "hosted",
                "--head",
                HEAD_1,
                "--reason",
                "bounded acceptance capacity",
            )
            self.assertEqual(grant.returncode, 0, grant.stderr)

            first = self.run_cli(fixture, isolated, "run", "hosted", "--expect-pr", "1")
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertIn("result=productive", first.stdout)
            self.assertIn("recorded_evidence=True", first.stdout)
            second = self.run_cli(fixture, isolated, "run", "hosted", "--expect-pr", "1")
            self.assertNotEqual(second.returncode, 0)
            self.assertIn("ALLOCATION_EXHAUSTED", second.stderr)

            shown = self.run_cli(fixture, isolated, "status", "--pr", "1", "--json")
            self.assertEqual(shown.returncode, 0, shown.stderr)
            allocation = json.loads(shown.stdout)["prs"][0]["allocations"]["hosted"]
            self.assertEqual(allocation["status"], "EXHAUSTED_PENDING")
            checkpoint = allocation["checkpoint"]
            self.assertIsInstance(checkpoint, str)

            corrected = json.loads(json.dumps(payload))
            corrected["branch_heads"]["feature-1"] = HEAD_2
            corrected["merge_bases"][f"{BASE}...{HEAD_2}"] = BASE
            corrected["patch_ids"][f"{BASE}...{HEAD_2}"] = "patch-corrected"
            corrected["pull_requests"][0]["head"] = HEAD_2
            fixture.write_text(json.dumps(corrected), encoding="utf-8")

            handoff = self.run_cli(
                fixture,
                isolated,
                "decide",
                "allocation",
                "handoff",
                "--pr",
                "1",
                "--channel",
                "hosted",
                "--head",
                HEAD_2,
                "--checkpoint",
                checkpoint,
                "--validation",
                "corrected head and required checks validated in the isolated fixture",
                "--reason",
                "findings corrected and capacity released",
            )
            self.assertEqual(handoff.returncode, 0, handoff.stderr)
            final = self.run_cli(fixture, isolated, "status", "--pr", "1", "--json")
            self.assertEqual(final.returncode, 0, final.stderr)
            self.assertEqual(
                json.loads(final.stdout)["prs"][0]["allocations"]["hosted"]["status"],
                "HANDED_OFF",
            )

    def test_fixture_result_sequence_is_persisted_and_nonterminal_results_do_not_create_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.json"
            isolated = root / "state.json"
            payload = fixture_payload_for_allocation()
            payload["review_results"]["1"]["hosted"] = [
                {"status": "rate_limited"},
                {"status": "partial"},
                {"status": "stale"},
                {"status": "duplicate"},
            ]
            fixture.write_text(json.dumps(payload), encoding="utf-8")
            self.assertEqual(self.run_cli(fixture, isolated, "stack", "set", "1").returncode, 0)
            grant = self.run_cli(
                fixture, isolated, "decide", "allocation", "grant", "--pr", "1", "--channel", "hosted",
                "--head", HEAD_1, "--reason", "nonterminal result sequence",
            )
            self.assertEqual(grant.returncode, 0, grant.stderr)
            for expected in ("rate_limited", "partial", "stale", "duplicate"):
                result = self.run_cli(fixture, isolated, "run", "hosted", "--expect-pr", "1")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(f"result={expected}", result.stdout)
                shown = self.run_cli(fixture, isolated, "status", "--pr", "1", "--json")
                self.assertEqual(shown.returncode, 0, shown.stderr)
                self.assertEqual(
                    json.loads(shown.stdout)["prs"][0]["allocations"]["hosted"]["status"], "PROMISED"
                )
            sidecar = json.loads((root / "state.json.fixture-evidence.json").read_text(encoding="utf-8"))
            self.assertEqual(sidecar["result_positions"]["1:hosted"], 4)
            self.assertEqual(sidecar["evidence"], [])

    def test_dry_fixture_results_get_distinct_defaults_and_duplicate_checkpoint_is_not_recorded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture_path = root / "fixture.json"
            isolated = root / "state.json"
            payload = fixture_payload()
            payload["review_results"] = {
                "1": {
                    "hosted": [
                        {"status": "dry", "record_evidence": True},
                        {"status": "dry", "record_evidence": True},
                        {"status": "dry", "record_evidence": True, "checkpoint": "explicit-dry"},
                        {"status": "dry", "record_evidence": True, "checkpoint": "explicit-dry"},
                    ]
                }
            }
            fixture_path.write_text(json.dumps(payload), encoding="utf-8")

            acceptance = load(fixture_path, isolated)
            controller = acceptance.controller()
            target = controller.resolve_hosted_target(expected_pr=1)
            adapter = FixtureReviewAdapter("hosted", acceptance.evidence)

            first_result = acceptance.evidence.next_result(target, "hosted")
            self.assertIsNotNone(first_result)
            self.assertEqual(first_result["_fixture_result_position"], 1)
            first_checkpoint = acceptance.evidence.record_result(target, "hosted", first_result)
            second = adapter(target)
            third = adapter(target)
            fourth = adapter(target)

            self.assertIsNotNone(first_checkpoint)
            self.assertNotEqual(first_checkpoint, second["recorded_checkpoint"])
            self.assertTrue(second["recorded_evidence"])
            self.assertEqual(third["recorded_checkpoint"], "explicit-dry")
            self.assertTrue(third["recorded_evidence"])
            self.assertIsNone(fourth["recorded_checkpoint"])
            self.assertFalse(fourth["recorded_evidence"])

            sidecar = json.loads((root / "state.json.fixture-evidence.json").read_text(encoding="utf-8"))
            self.assertEqual(sidecar["result_positions"]["1:hosted"], 4)
            self.assertEqual(
                [item["checkpoint"] for item in sidecar["evidence"]],
                [first_checkpoint, second["recorded_checkpoint"], "explicit-dry"],
            )
            self.assertNotIn("_fixture_result_position", sidecar["evidence"][0])

    def test_allocation_handoff_keeps_findings_and_next_parent_gates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.json"
            isolated = root / "state.json"
            payload = fixture_payload()
            payload["review_results"] = {
                "1": {"hosted": [{"status": "dry", "record_evidence": True, "checkpoint": "allocated-dry"}]}
            }
            fixture.write_text(json.dumps(payload), encoding="utf-8")
            self.assertEqual(self.run_cli(fixture, isolated, "stack", "set", "1", "2").returncode, 0)
            grant = self.run_cli(
                fixture, isolated, "decide", "allocation", "grant", "--pr", "1", "--channel", "hosted",
                "--head", HEAD_1, "--reason", "one review then handoff",
            )
            self.assertEqual(grant.returncode, 0, grant.stderr)
            first = self.run_cli(fixture, isolated, "run", "hosted", "--expect-pr", "1")
            self.assertEqual(first.returncode, 0, first.stderr)

            held = json.loads(json.dumps(payload))
            held["evidence"]["1"]["hosted"] = [
                {"pr": 1, "head": HEAD_1, "checkpoint": "unresolved-thread", "held": True}
            ]
            fixture.write_text(json.dumps(held), encoding="utf-8")
            blocked = self.run_cli(
                fixture, isolated, "decide", "allocation", "handoff", "--pr", "1", "--channel", "hosted",
                "--head", HEAD_1, "--checkpoint", "allocated-dry", "--validation", "checks green",
                "--reason", "attempted before thread resolution",
            )
            self.assertNotEqual(blocked.returncode, 0)
            self.assertIn("obligations remain", blocked.stderr)
            blocked_next = self.run_cli(fixture, isolated, "run", "hosted", "--expect-pr", "2")
            self.assertNotEqual(blocked_next.returncode, 0)
            self.assertIn("expected PR #2, but selected PR #1", blocked_next.stderr)

            fixture.write_text(json.dumps(payload), encoding="utf-8")
            handoff = self.run_cli(
                fixture, isolated, "decide", "allocation", "handoff", "--pr", "1", "--channel", "hosted",
                "--head", HEAD_1, "--checkpoint", "allocated-dry", "--validation", "checks green",
                "--reason", "thread resolved and head validated",
            )
            self.assertEqual(handoff.returncode, 0, handoff.stderr)
            next_pr = self.run_cli(fixture, isolated, "run", "hosted", "--expect-pr", "2")
            self.assertEqual(next_pr.returncode, 0, next_pr.stderr)
            self.assertIn("pr=2", next_pr.stdout)

            moved = json.loads(json.dumps(payload))
            moved["pull_requests"][1]["base_tip"] = "d" * 40
            fixture.write_text(json.dumps(moved), encoding="utf-8")
            blocked_next = self.run_cli(fixture, isolated, "run", "hosted", "--expect-pr", "2")
            self.assertNotEqual(blocked_next.returncode, 0)
            self.assertIn("PARENT_MOVED", blocked_next.stderr)


if __name__ == "__main__":
    unittest.main()
