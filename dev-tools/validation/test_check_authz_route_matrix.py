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

    def test_unknown_auth_path_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(route for route in document["routes"] if route.get("route") == "Authenticate")
        route["auth_path"] = "mtls_workload"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(
                self.validator.yaml.safe_dump(document, sort_keys=False),
                encoding="utf-8",
            )
            errors = self.validator.validate(path)
        self.assertTrue(
            any("auth_path contains values outside the closed vocabulary" in error for error in errors)
        )

    def test_nested_unknown_auth_path_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("route") == "GetTenantEntitlementsForRuntime"
        )
        route["caller_policies"][0]["auth_path"] = "mtls_workload_plus_current_token_generation"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(
                self.validator.yaml.safe_dump(document, sort_keys=False),
                encoding="utf-8",
            )
            errors = self.validator.validate(path)
        self.assertTrue(
            any("auth_path contains values outside the closed vocabulary" in error for error in errors)
        )

    def test_known_drift_must_be_a_non_empty_list_of_strings(self):
        for malformed in ("scalar_drift", [], ["valid_drift", 7]):
            with self.subTest(malformed=malformed), tempfile.TemporaryDirectory() as directory:
                document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
                route = next(route for route in document["routes"] if route.get("route") == "PLAY")
                route["implementation_status"]["known_drift"] = malformed
                path = Path(directory) / "matrix.yaml"
                path.write_text(
                    self.validator.yaml.safe_dump(document, sort_keys=False),
                    encoding="utf-8",
                )
                errors = self.validator.validate(path)
            self.assertTrue(
                any(
                    error.endswith(
                        "implementation_status.known_drift must be a non-empty list of strings"
                    )
                    for error in errors
                )
            )

    def test_route_live_checks_must_be_a_list(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                """    response_profile: public_production_catalog_only
    required_live_checks: [public_production_visibility, runtime_entitlements]""",
                """    response_profile: public_production_catalog_only
    required_live_checks: public_production_visibility""",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(
            any(
                error.startswith("matrix.routes[")
                and error.endswith("required_live_checks must be a list of strings")
                for error in errors
            )
        )

    def test_route_live_check_entries_must_be_strings(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                """    response_profile: public_production_catalog_only
    required_live_checks: [public_production_visibility, runtime_entitlements]""",
                """    response_profile: public_production_catalog_only
    required_live_checks: [public_production_visibility, 7]""",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(
            any(
                error.startswith("matrix.routes[")
                and error.endswith("required_live_checks must be a list of strings")
                for error in errors
            )
        )

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

    def test_first_party_ws_and_revoke_operations_are_mutually_exclusive(self):
        for old, new, expected_label in (
            (
                "        - operation: websocket_upgrade",
                "        - operation: connect_token_cookie_revoke",
                "/ws/game/** first_party_web",
            ),
            (
                "        - operation: connect_token_cookie_revoke",
                "        - operation: websocket_upgrade",
                "POST /ws/game/connect-token/revoke",
            ),
        ):
            with self.subTest(expected_label=expected_label):
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "matrix.yaml"
                    text = replace_or_fail(MATRIX.read_text(encoding="utf-8"), old, new)
                    path.write_text(text, encoding="utf-8")
                    errors = self.validator.validate(path)
                self.assertTrue(
                    any(
                        f"{expected_label} must declare applicability operation" in error
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

    def test_route_classification_lists_and_mappings_are_reported(self):
        old = """    classification: public
    applicability:
      all_of:
        - authentication_state: unauthenticated"""
        for malformed in ("[public]", "{name: public}"):
            with self.subTest(malformed=malformed):
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "matrix.yaml"
                    text = replace_or_fail(
                        MATRIX.read_text(encoding="utf-8"),
                        old,
                        f"""    classification: {malformed}
    applicability:
      all_of:
        - authentication_state: unauthenticated""",
                    )
                    path.write_text(text, encoding="utf-8")
                    errors = self.validator.validate(path)
                self.assertTrue(any("uses unknown classification" in error for error in errors))

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
      any_of: [PUBLIC_PRODUCTION_ADMISSION_DENIED, ADMISSION_POINTER_UNAVAILABLE, TENANT_BILLING_BLOCKED]
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

    def test_caller_policies_null_and_mapping_values_are_reported(self):
        for malformed in (None, {"caller": "game-session-service"}):
            with self.subTest(malformed=malformed):
                with tempfile.TemporaryDirectory() as directory:
                    document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
                    route = next(
                        route
                        for route in document["routes"]
                        if route.get("route") == "GetTenantEntitlementsForRuntime"
                    )
                    route["caller_policies"] = malformed
                    path = Path(directory) / "matrix.yaml"
                    path.write_text(
                        self.validator.yaml.safe_dump(document, sort_keys=False),
                        encoding="utf-8",
                    )
                    errors = self.validator.validate(path)
                self.assertIn(
                    "GetTenantEntitlementsForRuntime caller_policies must be a list",
                    errors,
                )

    def test_game_session_caller_policy_count_remains_exactly_one(self):
        for policy_count in (0, 2):
            with self.subTest(policy_count=policy_count):
                document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
                route = next(
                    route
                    for route in document["routes"]
                    if route.get("route") == "GetTenantEntitlementsForRuntime"
                )
                game_policy = next(
                    policy
                    for policy in route["caller_policies"]
                    if policy.get("caller") == "game-session-service"
                )
                policies = [] if policy_count == 0 else [game_policy, dict(game_policy)]
                route["caller_policies"] = policies
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "matrix.yaml"
                    path.write_text(
                        self.validator.yaml.safe_dump(document, sort_keys=False),
                        encoding="utf-8",
                    )
                    errors = self.validator.validate(path)
                self.assertIn(
                    "GetTenantEntitlementsForRuntime must contain exactly one game-session-service caller policy",
                    errors,
                )

    def test_join_route_errors_are_emitted_in_sorted_order(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        join_routes = self.validator.JOIN_ROUTES_REQUIRING_POINTER_ERROR
        for route in document["routes"]:
            if (route.get("service"), route.get("route")) in join_routes:
                route.pop("canonical_errors", None)

        errors = []
        self.validator.validate_join_routes(document["routes"], errors)

        self.assertEqual(
            [
                "account-service JoinPublicProductionMembership must declare ADMISSION_POINTER_UNAVAILABLE",
                "account-service POST /auth/bootstrap/join must declare ADMISSION_POINTER_UNAVAILABLE",
                "game-session-service JOIN must declare ADMISSION_POINTER_UNAVAILABLE",
            ],
            errors,
        )


if __name__ == "__main__":
    unittest.main()
