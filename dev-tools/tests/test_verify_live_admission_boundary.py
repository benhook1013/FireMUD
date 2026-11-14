"""Offline source/spec checks only; no kubectl or live admission execution."""

import contextlib
import copy
import importlib.util
import io
import json
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

PATH = Path(__file__).resolve().parents[1] / "hosted/trust-bootstrap/verify-live-admission-boundary.py"
SPEC = importlib.util.spec_from_file_location("verify_live_admission_boundary", PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class VerifyLiveAdmissionBoundaryTests(unittest.TestCase):
    def test_checked_in_exact_object_set_and_offline_comparison(self):
        expected = MODULE.load_expected_specs()
        self.assertEqual(len(expected), 16)
        self.assertEqual(set(expected), MODULE.expected_keys())
        output = io.StringIO()
        with (
            patch.object(MODULE.sys, "argv", [str(PATH), "--context", "offline-fixture"]),
            patch.object(MODULE, "read_live_spec", side_effect=lambda context, kind, name: expected[(kind, name)]),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(MODULE.main(), 0)
        self.assertIn("admission-specs=pass objects=16", output.getvalue())

    def test_mismatch_never_emits_summary_pass_or_policy_contents(self):
        expected = MODULE.load_expected_specs()

        def altered(context, kind, name):
            result = copy.deepcopy(expected[(kind, name)])
            result["untrusted"] = "do-not-print"
            return result

        output, errors = io.StringIO(), io.StringIO()
        with (
            patch.object(MODULE.sys, "argv", [str(PATH), "--context", "offline-fixture"]),
            patch.object(MODULE, "read_live_spec", side_effect=altered),
            contextlib.redirect_stdout(output),
            contextlib.redirect_stderr(errors),
        ):
            self.assertEqual(MODULE.main(), 1)
        self.assertNotIn("admission-specs=pass", output.getvalue())
        self.assertNotIn("do-not-print", errors.getvalue())
        self.assertIn("spec-mismatch", errors.getvalue())

    def test_only_empty_selector_defaults_are_normalized(self):
        minimal = {"policyName": "fixture", "validationActions": ["Deny"]}
        empty = dict(
            minimal,
            matchResources={"namespaceSelector": {"matchLabels": {}}, "objectSelector": {"matchExpressions": []}},
        )
        self.assertEqual(MODULE.normalize_empty_selectors(MODULE.BINDING_KIND, empty), minimal)
        selected = dict(minimal, matchResources={"namespaceSelector": {"matchLabels": {"x": "y"}}})
        self.assertNotEqual(MODULE.normalize_empty_selectors(MODULE.BINDING_KIND, selected), minimal)
        self.assertIn("matchResources", empty)  # No mutation of checked-in authority.

    def test_read_checks_identity_and_does_not_copy_metadata(self):
        kind, name = MODULE.POLICY_KIND, MODULE.POLICY_NAMES[0]
        value = {
            "apiVersion": MODULE.API_VERSION,
            "kind": kind,
            "metadata": {"name": name, "annotations": {"private": "never-copy"}},
            "spec": {"failurePolicy": "Fail"},
        }
        completed = subprocess.CompletedProcess([], 0, json.dumps(value), "")
        with patch.object(MODULE.subprocess, "run", return_value=completed) as run:
            self.assertEqual(MODULE.read_live_spec("fixture", kind, name), value["spec"])
        args, kwargs = run.call_args
        self.assertEqual(args[0][:3], ["kubectl", "--context", "fixture"])
        self.assertIn("--request-timeout=15s", args[0])
        self.assertEqual(kwargs["timeout"], MODULE.API_TIMEOUT_SECONDS)
        value["metadata"]["name"] = "other"
        with (
            patch.object(
                MODULE.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, json.dumps(value), "")
            ),
            self.assertRaisesRegex(MODULE.ProofError, "invalid-object"),
        ):
            MODULE.read_live_spec("fixture", kind, name)

    def test_api_timeout_and_denial_fail_closed(self):
        for failure in (OSError("private"), subprocess.TimeoutExpired("kubectl", 20)):
            with (
                self.subTest(failure=type(failure).__name__),
                patch.object(MODULE.subprocess, "run", side_effect=failure),
                self.assertRaisesRegex(MODULE.ProofError, "api-unavailable"),
            ):
                MODULE.read_live_spec("fixture", MODULE.POLICY_KIND, MODULE.POLICY_NAMES[0])
        with (
            patch.object(MODULE.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "", "private")),
            self.assertRaisesRegex(MODULE.ProofError, "api-error"),
        ):
            MODULE.read_live_spec("fixture", MODULE.POLICY_KIND, MODULE.POLICY_NAMES[0])

    def test_json_ambiguity_and_size_are_rejected(self):
        for payload in ('{"x":1,"x":2}', '{"x":NaN}', "[] []", "x" * (MODULE.MAX_RESPONSE_BYTES + 1)):
            with (
                self.subTest(payload=payload[:20]),
                patch.object(MODULE.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, payload, "")),
                self.assertRaises(MODULE.ProofError),
            ):
                MODULE.read_live_spec("fixture", MODULE.POLICY_KIND, MODULE.POLICY_NAMES[0])

    def test_source_duplicate_yaml_keys_refused(self):
        with self.assertRaisesRegex(ValueError, "duplicate mapping key"):
            MODULE.yaml.load("spec: {failurePolicy: Fail, failurePolicy: Ignore}", Loader=MODULE.UniqueKeyLoader)


if __name__ == "__main__":
    unittest.main()
