#!/usr/bin/env python3
"""Regression tests for authorization route matrix validation."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "dev-tools/validation/check-authz-route-matrix.py"
MATRIX = ROOT / "design/architecture/system-architecture-authz-route-matrix.yaml"


def load_validator():
    spec = importlib.util.spec_from_file_location("authz_route_matrix_validator", SCRIPT)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load authz route matrix validator")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AuthzRouteMatrixValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()

    def test_current_matrix_passes(self):
        self.assertEqual([], self.validator.validate(MATRIX))

    def test_unknown_live_check_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = MATRIX.read_text(encoding="utf-8").replace(
                "required_live_checks: [runtime_entitlements]",
                "required_live_checks: [unknown_check]",
                1,
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("outside the closed vocabulary" in error for error in errors))

    def test_ws_game_live_checks_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = MATRIX.read_text(encoding="utf-8").replace(
                "required_live_checks: [connect_token_single_use_consume, replay_protection_available, replay_admission_fence_match, connect_scope_match]",
                "required_live_checks: [connect_token_single_use_consume, replay_protection_available, connect_scope_match]",
                1,
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("/ws/game/** is missing required live checks" in error for error in errors))

    def test_ws_game_policy_pressure_outcome_is_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = MATRIX.read_text(encoding="utf-8").replace("        - POLICY_PRESSURE\n", "", 1)
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("POLICY_PRESSURE" in error for error in errors))

    def test_issue_connect_token_replay_fence_checks_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = MATRIX.read_text(encoding="utf-8").replace(
                "admission_pointer, replay_protection_available, replay_admission_fence_match]",
                "admission_pointer, replay_protection_available]",
                1,
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("IssueConnectToken is missing required live checks" in error for error in errors))

    def test_malformed_classification_vocabulary_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = MATRIX.read_text(encoding="utf-8").replace(
                "classifications:\n  - public",
                "classifications:\n  - {invalid: value}",
                1,
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("classifications must be a list of strings" in error for error in errors))

    def test_duplicate_route_requires_applicability(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = MATRIX.read_text(encoding="utf-8")
            route = "\n  - service: duplicate-service\n    route: GET /duplicate\n    classification: public\n"
            text = text.replace("\nroutes:\n", f"\nroutes:{route}{route}", 1)
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("duplicate route entries require explicit applicability" in error for error in errors))

    def test_join_routes_require_admission_pointer_error(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = MATRIX.read_text(encoding="utf-8").replace(
                "    canonical_errors:\n      any_of: [ADMISSION_POINTER_UNAVAILABLE]\n",
                "",
                1,
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("must declare ADMISSION_POINTER_UNAVAILABLE" in error for error in errors))

    def test_delegated_entitlement_checks_require_account_cutoffs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = MATRIX.read_text(encoding="utf-8").replace(
                "required_live_checks: [issuer_generation, account_generation, current_token_generation, tenant_generation, membership_generation, membership, runtime_entitlements]",
                "required_live_checks: [current_token_generation, tenant_generation, membership_generation, membership, runtime_entitlements]",
                1,
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(
            any(
                "GetTenantEntitlementsForRuntime game-session policy is missing" in error
                for error in errors
            )
        )


if __name__ == "__main__":
    unittest.main()
