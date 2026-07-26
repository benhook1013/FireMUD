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


def replace_or_fail(text: str, old: str, new: str) -> str:
    occurrences = text.count(old)
    if occurrences != 1:
        raise AssertionError(f"expected exactly one occurrence of {old!r}, found {occurrences}")
    return text.replace(old, new, 1)


class AuthzRouteMatrixValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()

    def test_current_matrix_passes(self):
        self.assertEqual([], self.validator.validate(MATRIX))

    def test_unknown_live_check_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                """  - service: game-session-service
    route: WORLDS_PUBLIC
    transport_command: WORLDS
    scope: public
    classification: public
    applicability:
      all_of:
        - authentication_state: unauthenticated
    response_profile: public_production_catalog_only
    required_live_checks: [public_production_visibility, runtime_entitlements]""",
                """  - service: game-session-service
    route: WORLDS_PUBLIC
    transport_command: WORLDS
    scope: public
    classification: public
    applicability:
      all_of:
        - authentication_state: unauthenticated
    response_profile: public_production_catalog_only
    required_live_checks: [unknown_check]""",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("outside the closed vocabulary" in error for error in errors))

    def test_ws_game_live_checks_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                "required_live_checks: [connect_token_single_use_consume, replay_protection_available, replay_admission_fence_match, connect_scope_match]",
                "required_live_checks: [connect_token_single_use_consume, replay_protection_available, connect_scope_match]",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("/ws/game/** is missing required live checks" in error for error in errors))

    def test_ws_game_policy_pressure_outcome_is_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"), "        - POLICY_PRESSURE\n", ""
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("POLICY_PRESSURE" in error for error in errors))

    def test_trusted_tcp_proxy_route_is_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                "        - connection_mode: trusted_tcp_proxy",
                "        - connection_mode: missing_trusted_proxy",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("trusted_tcp_proxy" in error for error in errors))

    def test_conflicting_ws_game_connection_modes_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                """      all_of:
        - connection_mode: trusted_tcp_proxy""",
                """      all_of:
        - connection_mode: trusted_tcp_proxy
        - connection_mode: first_party_web""",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(
            any(
                "/ws/game/** has conflicting applicability values for connection_mode" in error
                for error in errors
            )
        )

    def test_trusted_tcp_proxy_live_checks_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                "    required_live_checks: [trusted_proxy_identity]",
                "    required_live_checks: []",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(
            any("/ws/game/** trusted_tcp_proxy is missing required live checks" in error for error in errors)
        )

    def test_connect_token_revoke_checks_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                "    required_live_checks: [browser_origin, csrf]",
                "    required_live_checks: [browser_origin]",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(
            any("POST /ws/game/connect-token/revoke is missing" in error for error in errors)
        )

    def test_malformed_ws_game_route_counts_do_not_suppress_revoke_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                "        - connection_mode: trusted_tcp_proxy",
                "        - connection_mode: malformed_route",
            )
            text = replace_or_fail(
                text,
                "    required_live_checks: [browser_origin, csrf]",
                "    required_live_checks: [browser_origin]",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("exactly one first_party_web and one trusted_tcp_proxy" in error for error in errors))
        self.assertTrue(any("POST /ws/game/connect-token/revoke is missing" in error for error in errors))

    def test_issue_connect_token_replay_fence_checks_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                "admission_pointer, connect_scope_match, replay_protection_available, replay_admission_fence_match]",
                "admission_pointer, connect_scope_match, replay_protection_available]",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("IssueConnectToken is missing required live checks" in error for error in errors))

    def test_malformed_classification_vocabulary_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                "classifications:\n  - public",
                "classifications:\n  - {invalid: value}",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("classifications must be a list of strings" in error for error in errors))

    def test_unknown_route_classification_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                """    route: WORLDS_PUBLIC
    transport_command: WORLDS
    scope: public
    classification: public""",
                """    route: WORLDS_PUBLIC
    transport_command: WORLDS
    scope: public
    classification: not_a_real_classification""",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("uses unknown classification" in error for error in errors))

    def test_duplicate_route_requires_applicability(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = MATRIX.read_text(encoding="utf-8")
            route = "\n  - service: duplicate-service\n    route: GET /duplicate\n    classification: public\n"
            text = replace_or_fail(text, "\nroutes:\n", f"\nroutes:{route}{route}")
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("duplicate route entries require explicit applicability" in error for error in errors))

    def test_join_routes_require_admission_pointer_error(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                """  - service: game-session-service
    route: JOIN
    scope: tenant
    classification: public_production_onboarding
    auth_path: game_session_authenticated_context
    accepted_token_profiles: []
    delegated_subject: authenticated_session_account
    tenant_billing_authority_generation_applies: false
    membership_authority_generation_applies: false
    required_live_checks: [public_production_admission, runtime_entitlements, admission_pointer]
    canonical_errors:
      any_of: [PUBLIC_PRODUCTION_ADMISSION_DENIED, ADMISSION_POINTER_UNAVAILABLE]
    mutation_contract: explicit_public_membership_atomic""",
                """  - service: game-session-service
    route: JOIN
    scope: tenant
    classification: public_production_onboarding
    auth_path: game_session_authenticated_context
    accepted_token_profiles: []
    delegated_subject: authenticated_session_account
    tenant_billing_authority_generation_applies: false
    membership_authority_generation_applies: false
    required_live_checks: [public_production_admission, runtime_entitlements, admission_pointer]
    mutation_contract: explicit_public_membership_atomic""",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("must declare ADMISSION_POINTER_UNAVAILABLE" in error for error in errors))

    def test_delegated_entitlement_checks_require_account_cutoffs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                "required_live_checks: [issuer_generation, account_generation, current_token_generation, tenant_generation, membership_generation, membership, runtime_entitlements, conditional_realm_access_grant, grant_version]",
                "required_live_checks: [current_token_generation, tenant_generation, membership_generation, membership, runtime_entitlements, conditional_realm_access_grant, grant_version]",
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
