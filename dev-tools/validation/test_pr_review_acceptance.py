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
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli, state
from pr_review.acceptance import AcceptanceFixtureError, load

BASE = "a" * 40
HEAD_1 = "b" * 40
HEAD_2 = "c" * 40


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
            self.assertFalse(isolated.exists())

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


if __name__ == "__main__":
    unittest.main()
