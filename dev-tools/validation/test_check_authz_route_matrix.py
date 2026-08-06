#!/usr/bin/env python3
"""Regression tests for authorization route matrix validation."""

from __future__ import annotations

import copy
import datetime
import importlib.util
import tempfile
import unittest
from collections import defaultdict
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "dev-tools/validation/check-authz-route-matrix.py"
MATRIX = ROOT / "design/architecture/system-architecture-authz-route-matrix.yaml"


def load_validator():
    spec = importlib.util.spec_from_file_location(
        "authz_route_matrix_validator", SCRIPT
    )
    if spec is None or spec.loader is None:
        raise AssertionError("could not load authz route matrix validator")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RouteCardinalityError(AssertionError):
    def __init__(self, service: str, route_name: str, match_count: int):
        super().__init__(
            f"expected exactly one {service} {route_name} route, found {match_count}"
        )


def grouped_routes(document, service):
    routes = defaultdict(list)
    for route in document["routes"]:
        if route.get("service") == service:
            routes[route["route"]].append(route)
    return routes


def route_for(document, service, route_name):
    matches = [
        route
        for route in document["routes"]
        if route.get("service") == service and route.get("route") == route_name
    ]
    if len(matches) != 1:
        raise RouteCardinalityError(service, route_name, len(matches))
    return matches[0]


def route_index(document, route):
    for index, candidate in enumerate(document["routes"]):
        if candidate is route:
            return index
    raise AssertionError("route is not present in document")


def validate_document(validator, document):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "matrix.yaml"
        path.write_text(
            validator.yaml.safe_dump(document, sort_keys=False),
            encoding="utf-8",
        )
        return validator.validate(path)


def websocket_route(document, connection_mode):
    matches = [
        route
        for route in document["routes"]
        if route.get("service") == "spring-cloud-gateway"
        and route.get("route") == "/ws/game/**"
        and {"connection_mode": connection_mode}
        in route.get("applicability", {}).get("all_of", [])
    ]
    if len(matches) != 1:
        raise AssertionError(
            f"expected exactly one /ws/game/** {connection_mode} route, found {len(matches)}"
        )
    return matches[0]


def configure_multi_profile_route(document):
    route = route_for(document, "game-session-service", "ToggleFeatureFlag")
    base_profile = next(
        profile
        for profile in document["token_profiles"]
        if profile["profile"] == "control-ui"
    )
    second_profile = {
        **base_profile,
        "profile": "control-ui-secondary",
        "audience": "control-ui-secondary",
    }
    document["token_profiles"].append(second_profile)
    profile_names = [base_profile["profile"], second_profile["profile"]]
    route["accepted_token_profiles"] = profile_names
    route["accepted_token_profile_audiences"] = {
        profile_name: next(
            profile["audience"]
            for profile in document["token_profiles"]
            if profile["profile"] == profile_name
        )
        for profile_name in profile_names
    }
    route["token_type"] = base_profile["type"]
    route["token_issuer"] = base_profile["issuer"]
    route.pop("token_audience", None)
    return route


class AuthzRouteMatrixValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()

    def test_current_matrix_passes(self):
        self.assertEqual([], self.validator.validate(MATRIX))

    def test_owner_route_metadata_is_explicit(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = grouped_routes(document, "game-session-service")
        self.assertTrue(routes["StopSession"])
        for route in routes["StopSession"]:
            self.assertTrue(
                {
                    "current_operator_authorization",
                    "runtime_ownership",
                }.issubset(route["required_live_checks"])
            )
        for route_name in (
            "ToggleFeatureFlag",
            "PauseTicksForScope",
            "ResumeTicksForScope",
            "SetAdmissionPointer",
            "ExecutePreparedVersionCutover",
            "PrepareVersionUpgrade",
        ):
            self.assertTrue(routes[route_name])
            for route in routes[route_name]:
                self.assertEqual("grpc", route["transport"])

    def test_operator_ingress_uses_conditional_membership_generation(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route_names = {
            route
            for service, route in self.validator.OPERATOR_INGRESS_ROUTES
            if service == "logging-admin-service"
        }
        self.assertTrue(route_names)
        routes = grouped_routes(document, "logging-admin-service")
        for route_name in route_names:
            self.assertTrue(routes[route_name])
            for route in routes[route_name]:
                self.assertEqual(
                    "conditional_by_operator_role",
                    route["membership_authority_generation_applies"],
                )
                self.assertEqual(
                    {"tenant_role": True, "platformAdmin_global": False},
                    route["membership_authority_generation_condition"],
                )
                self.assertFalse(route["global_platform_admin_membership_required"])
                tenant_branch = next(
                    branch
                    for branch in route["operator_authorization_branches"]
                    if branch["branch"] == "tenant_role"
                )
                self.assertTrue(
                    {
                        "membership_when_tenant_role",
                        "membership_generation",
                        "tenant_generation",
                    }.issubset(tenant_branch["required_live_checks"])
                )
                platform_admin_branch = next(
                    branch
                    for branch in route["operator_authorization_branches"]
                    if branch["branch"] == "platformAdmin_global"
                )
                self.assertIn(
                    "current_operator_roles",
                    platform_admin_branch["required_live_checks"],
                )

    def test_operator_ingress_conditional_shape_is_validated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "logging-admin-service", "POST /feature-flags/toggle"
        )
        route["membership_authority_generation_condition"]["platformAdmin_global"] = (
            True
        )
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "conditional membership generation requires" in error
                for error in errors
            )
        )

    def test_operator_mutation_gate_identities_are_routes_or_explicit_drift(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        errors = []
        self.validator.validate_operator_mutation_support_gate(
            document, document["routes"], errors
        )
        self.assertEqual([], errors)

    def test_operator_mutation_gate_declares_route_status_precedence(self):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        self.assertEqual(
            ["gate_wins_for_applies_to"],
            baseline["route_status_override_vocabulary"],
        )
        self.assertEqual(
            "gate_wins_for_applies_to",
            baseline["operator_mutation_support_gate"]["route_status_override"],
        )

        mutations = (
            (
                "missing vocabulary",
                lambda document: document.pop("route_status_override_vocabulary"),
                "route_status_override_vocabulary must be a non-empty list of strings",
            ),
            (
                "unknown vocabulary value",
                lambda document: document.__setitem__(
                    "route_status_override_vocabulary", ["route_wins"]
                ),
                (
                    "route_status_override_vocabulary must contain exactly "
                    "['gate_wins_for_applies_to']"
                ),
            ),
            (
                "missing gate override",
                lambda document: document["operator_mutation_support_gate"].pop(
                    "route_status_override"
                ),
                (
                    "operator_mutation_support_gate.route_status_override must be one of "
                    "['gate_wins_for_applies_to']"
                ),
            ),
        )
        for name, mutate, expected_error in mutations:
            with self.subTest(name=name):
                document = copy.deepcopy(baseline)
                mutate(document)
                errors = []
                self.validator.validate_operator_mutation_support_gate(
                    document, document["routes"], errors
                )
                self.assertIn(expected_error, errors)

    def test_operator_mutation_gate_status_precedence_has_positive_and_negative_proof(
        self,
    ):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        gate = baseline["operator_mutation_support_gate"]
        errors = []
        self.validator.validate_operator_mutation_support_gate(
            baseline, baseline["routes"], errors
        )
        self.assertEqual([], errors)
        self.assertEqual(
            "target_not_currently_routable",
            gate["status"],
        )

        document = copy.deepcopy(baseline)
        document["operator_mutation_support_gate"]["status"] = (
            "current_openapi_operator_surface"
        )
        errors = []
        self.validator.validate_operator_mutation_support_gate(
            document, document["routes"], errors
        )
        self.assertIn(
            "operator_mutation_support_gate.status must be "
            "target_not_currently_routable",
            errors,
        )

        document = copy.deepcopy(baseline)
        route = route_for(
            document, "logging-admin-service", "POST /feature-flags/toggle"
        )
        route["route_status"] = "current_openapi_operator_surface"
        errors = []
        self.validator.validate_operator_mutation_support_gate(
            document, document["routes"], errors
        )
        self.assertIn(
            "operator_mutation_support_gate.applies_to route "
            "logging-admin-service/POST /feature-flags/toggle must declare "
            "route_status target_not_currently_routable when "
            "route_status_override is gate_wins_for_applies_to",
            errors,
        )

    def test_http_operator_session_mutations_are_target_gated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        expected = set(self.validator.REQUIRED_SESSION_LIFECYCLE_GATE_ROUTES)
        self.assertTrue(
            expected.issubset(
                set(document["operator_mutation_support_gate"]["applies_to"])
            )
        )
        self.assertEqual(
            "target_not_currently_routable",
            document["operator_mutation_support_gate"]["status"],
        )
        for identity in expected:
            service, route_name = identity.split("/", 1)
            route = route_for(document, service, route_name)
            self.assertEqual("target_not_currently_routable", route["route_status"])

        refresh_roles = "game-session-service/POST /sessions/{sessionId}/refresh-roles"
        self.assertNotIn(
            refresh_roles,
            document["operator_mutation_support_gate"]["applies_to"],
        )
        service, route_name = refresh_roles.split("/", 1)
        self.assertEqual(
            "current_openapi_operator_surface",
            route_for(document, service, route_name)["route_status"],
        )

        document["operator_mutation_support_gate"]["applies_to"].remove(
            "game-session-service/POST /sessions"
        )
        errors = []
        self.validator.validate_operator_mutation_support_gate(
            document, document["routes"], errors
        )
        self.assertTrue(
            any(
                "missing required current session lifecycle routes" in error
                for error in errors
            )
        )

    def test_operator_mutation_gate_rejects_unclassified_identity(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document["operator_mutation_support_gate"]["coverage_drift"] = [
            entry
            for entry in document["operator_mutation_support_gate"]["coverage_drift"]
            if entry["identity"] != "logging-admin-service/EvaluateModerationPolicy"
        ]
        errors = []
        self.validator.validate_operator_mutation_support_gate(
            document, document["routes"], errors
        )
        self.assertIn(
            "operator_mutation_support_gate identity is neither a route nor explicit "
            "coverage drift: logging-admin-service/EvaluateModerationPolicy",
            errors,
        )

    def test_operator_routes_declare_branch_qualified_live_checks(self):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        expected = {
            "tenant_role": {
                "membership_when_tenant_role",
                "membership_generation",
                "tenant_generation",
            },
            "platformAdmin_global": {
                "current_operator_roles",
                "current_global_role",
                "role_appropriate_assurance",
                "target_tenant_generation",
            },
        }
        for service, route_name in sorted(self.validator.CONDITIONAL_OPERATOR_ROUTES):
            with self.subTest(service=service, route=route_name):
                document = copy.deepcopy(baseline)
                route = route_for(document, service, route_name)
                branches = {
                    branch["branch"]: set(branch["required_live_checks"])
                    for branch in route["operator_authorization_branches"]
                }
                self.assertEqual(expected, branches)

        with self.subTest(route="IssueHumanOperatorAuthorizationReference"):
            document = copy.deepcopy(baseline)
            route = route_for(
                document,
                "account-service",
                "IssueHumanOperatorAuthorizationReference",
            )
            branches = {
                branch["branch"]: set(branch["required_live_checks"])
                for branch in route["operator_authorization_branches"]
            }
            self.assertEqual(expected, branches)

            platform_admin_branch = next(
                branch
                for branch in route["operator_authorization_branches"]
                if branch["branch"] == "platformAdmin_global"
            )
            platform_admin_branch["required_live_checks"].remove(
                "target_tenant_generation"
            )
            errors = []
            self.validator.validate_generation_applicability(
                document["routes"], errors
            )
            self.assertTrue(
                any(
                    "IssueHumanOperatorAuthorizationReference"
                    in error
                    and "required_live_checks must equal" in error
                    for error in errors
                )
            )

        for branch_name, missing_check, expected_diagnostic in (
            (
                "tenant_role",
                "tenant_generation",
                "operator route must require tenant_generation",
            ),
            (
                "platformAdmin_global",
                "target_tenant_generation",
                "operator route must require target_tenant_generation",
            ),
        ):
            with self.subTest(branch=branch_name, missing_check=missing_check):
                document = copy.deepcopy(baseline)
                route = route_for(
                    document, "logging-admin-service", "POST /feature-flags/toggle"
                )
                branch = next(
                    branch
                    for branch in route["operator_authorization_branches"]
                    if branch["branch"] == branch_name
                )
                branch["required_live_checks"].remove(missing_check)
                errors = []
                self.validator.validate_generation_applicability(
                    document["routes"], errors
                )
                route_index_value = route_index(document, route)
                self.assertIn(
                    f"routes[{route_index_value}] logging-admin-service "
                    f"POST /feature-flags/toggle {expected_diagnostic}",
                    errors,
                )

    def test_admission_pointer_mutation_requires_expected_version_check(self):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        for route_name in (
            "POST /admission-pointers",
            "POST /admission-pointers/cutover",
        ):
            with self.subTest(route=route_name):
                document = copy.deepcopy(baseline)
                route = route_for(document, "logging-admin-service", route_name)
                self.assertIn("expected_pointer_version", route["required_live_checks"])

                route["required_live_checks"].remove("expected_pointer_version")
                errors = validate_document(self.validator, document)
                self.assertTrue(
                    any(
                        "admission-pointer mutation must require live check "
                        "expected_pointer_version" in error
                        for error in errors
                    )
                )

    def test_game_session_operator_routes_match_ingress_authority_shape(self):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document = copy.deepcopy(baseline)
        routes = grouped_routes(document, "game-session-service")
        self.assertTrue(self.validator.GAME_SESSION_OPERATOR_ROUTES)
        for route_key in self.validator.GAME_SESSION_OPERATOR_ROUTES:
            service, route_name = route_key
            self.assertEqual("game-session-service", service)
            self.assertTrue(routes[route_name])
            for route in routes[route_name]:
                self.assertEqual(
                    "conditional_by_operator_role",
                    route["membership_authority_generation_applies"],
                )
                self.assertEqual(
                    {"tenant_role": True, "platformAdmin_global": False},
                    route["membership_authority_generation_condition"],
                )
                self.assertTrue(route["tenant_billing_authority_generation_applies"])
                self.assertFalse(route["global_platform_admin_membership_required"])
                self.assertEqual(
                    "target_tenant_generation",
                    route["global_platform_admin_reference_generation_binding"],
                )
                branch_checks = {
                    branch["branch"]: set(branch["required_live_checks"])
                    for branch in route["operator_authorization_branches"]
                }
                self.assertEqual(
                    self.validator.OPERATOR_AUTHORIZATION_BRANCHES,
                    branch_checks,
                )
                self.assertEqual(
                    "logging-admin-service", route["canonical_external_ingress"]
                )
                self.assertEqual(
                    "deny_at_edge_and_migrate_to_logging_admin",
                    route["direct_owner_route_policy"],
                )

        for missing_check in ("tenant_generation", "target_tenant_generation"):
            with self.subTest(missing_check=missing_check):
                document = copy.deepcopy(baseline)
                drifted_route = route_for(
                    document, "game-session-service", "POST /sessions"
                )
                drifted_route_index = route_index(document, drifted_route)
                branch = next(
                    branch
                    for branch in drifted_route["operator_authorization_branches"]
                    if branch["branch"]
                    == (
                        "tenant_role"
                        if missing_check == "tenant_generation"
                        else "platformAdmin_global"
                    )
                )
                checks = branch["required_live_checks"]
                checks.remove(missing_check)
                errors = []
                self.validator.validate_generation_applicability(
                    document["routes"], errors
                )
                self.assertIn(
                    f"routes[{drifted_route_index}] game-session-service POST /sessions "
                    f"operator route must require {missing_check}",
                    errors,
                )

    def test_profile_routes_require_generation_checks(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = grouped_routes(document, "account-service")
        self.assertNotIn("GetProfile", routes)
        self.assertNotIn("UpdateProfile", routes)
        for _, route_name in self.validator.PROFILE_ROUTES:
            self.assertTrue(routes[route_name])
            for route in routes[route_name]:
                self.assertTrue(route["tenant_billing_authority_generation_applies"])
                self.assertTrue(route["membership_authority_generation_applies"])
                self.assertEqual(
                    "control_ui_plus_current_tenant_role", route["auth_path"]
                )
                self.assertEqual("exact_declared_route", route["method_policy"])
                self.assertTrue(
                    {
                        "membership",
                        "membership_generation",
                        "tenant_generation",
                    }.issubset(route["required_live_checks"])
                )

        routes[self.validator.PROFILE_ROUTES[0][1]][0]["required_live_checks"].remove(
            "tenant_generation"
        )
        errors = []
        self.validator.validate_profile_authority_routes(document["routes"], errors)
        self.assertIn(
            "account-service GET /tenants/{tenantId}/profiles/{accountId} must require live check tenant_generation",
            errors,
        )

    def test_profile_route_auth_and_method_policy_are_validated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, *self.validator.PROFILE_ROUTES[0])
        route["auth_path"] = "wrong_auth_path"
        route["method_policy"] = "all_methods"
        errors = []
        self.validator.validate_profile_authority_routes(document["routes"], errors)
        self.assertIn(
            "account-service GET /tenants/{tenantId}/profiles/{accountId} must declare auth_path control_ui_plus_current_tenant_role",
            errors,
        )
        self.assertIn(
            "account-service GET /tenants/{tenantId}/profiles/{accountId} must declare method_policy exact_declared_route",
            errors,
        )

    def test_refresh_roles_uses_owner_authority_and_independent_idempotency(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = grouped_routes(document, "game-session-service")
        grpc = routes["RefreshRoles"][0]
        self.assertEqual("exact_mtls_workload", grpc["auth_path"])
        self.assertNotIn("operator_authorization_reference", grpc)
        self.assertNotIn("delegated_subject", grpc)
        self.assertEqual(
            {"current_session", "current_account_roles"},
            set(grpc["required_live_checks"]),
        )
        self.assertIn("mutation_digest", grpc["required_fields"])
        self.assertIn("IDEMPOTENCY_CONFLICT", grpc["canonical_errors"]["any_of"])
        http = routes["POST /sessions/{sessionId}/refresh-roles"][0]
        self.assertEqual("exact_mtls_workload", http["auth_path"])
        self.assertNotIn("operator_authorization_reference", http)
        self.assertNotIn("delegated_subject", http)
        self.assertEqual(
            {"current_session", "current_account_roles"},
            set(http["required_live_checks"]),
        )
        self.assertEqual(
            "owner_atomic_idempotent_role_refresh_with_durable_result",
            http["mutation_contract"],
        )
        self.assertIn("mutation_digest", http["required_fields"])
        self.assertIn("IDEMPOTENCY_CONFLICT", http["canonical_errors"]["any_of"])

        grpc["auth_path"] = (
            "exact_mtls_workload_plus_account_operator_authorization_reference"
        )
        grpc["operator_authorization_reference"] = "required_and_redeemed_with_account"
        grpc["delegated_subject"] = "operator_authorization_reference"
        grpc["required_live_checks"].append("current_operator_authorization")
        grpc["required_live_checks"].remove("current_account_roles")
        errors = []
        self.validator.validate_refresh_roles_routes(document["routes"], errors)
        self.assertIn(
            "game-session-service RefreshRoles must use exact_mtls_workload auth_path",
            errors,
        )
        self.assertIn(
            "game-session-service RefreshRoles must not receive an operator "
            "authorization reference",
            errors,
        )
        self.assertIn(
            "game-session-service RefreshRoles must not declare a delegated subject",
            errors,
        )

        http["auth_path"] = (
            "control_ui_plus_current_role_and_role_appropriate_assurance"
        )
        http["operator_authorization_reference"] = "account_issued_bounded_reference"
        http["delegated_subject"] = "authenticated_operator"
        http["required_live_checks"].append("current_operator_authorization")
        http["required_live_checks"].remove("current_account_roles")
        http["mutation_contract"] = (
            "durable_intent_then_account_redeemed_owner_idempotent_mutation"
        )
        errors = []
        self.validator.validate_refresh_roles_routes(document["routes"], errors)
        self.assertIn(
            "game-session-service POST /sessions/{sessionId}/refresh-roles must use "
            "exact_mtls_workload auth_path",
            errors,
        )
        self.assertIn(
            "game-session-service POST /sessions/{sessionId}/refresh-roles must not "
            "receive an operator authorization reference",
            errors,
        )
        self.assertIn(
            "game-session-service POST /sessions/{sessionId}/refresh-roles must not "
            "declare a delegated subject",
            errors,
        )
        self.assertIn(
            "game-session-service POST /sessions/{sessionId}/refresh-roles must "
            "require live check current_account_roles",
            errors,
        )
        self.assertIn(
            "game-session-service POST /sessions/{sessionId}/refresh-roles must not "
            "depend on current operator authorization",
            errors,
        )
        self.assertIn(
            "game-session-service POST /sessions/{sessionId}/refresh-roles must use "
            "the owner role-refresh mutation contract",
            errors,
        )
        self.assertIn(
            "game-session-service RefreshRoles must require live check "
            "current_account_roles",
            errors,
        )
        self.assertIn(
            "game-session-service RefreshRoles must not depend on current operator "
            "authorization",
            errors,
        )

    def test_refresh_roles_rejects_malformed_required_fields(self):
        for route_name in (
            "RefreshRoles",
            "POST /sessions/{sessionId}/refresh-roles",
        ):
            with self.subTest(route_name=route_name):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = route_for(document, "game-session-service", route_name)
                route_position = route_index(document, route)
                route["required_fields"] = [{"invalid": "field"}]
                errors = validate_document(self.validator, document)
                self.assertEqual(
                    1,
                    errors.count(
                        f"routes[{route_position}] required_fields must be a list of strings"
                    ),
                )
                self.assertTrue(
                    any("must require mutation_digest" in error for error in errors)
                )

    def test_refresh_roles_missing_required_fields_has_one_canonical_error(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "RefreshRoles")
        route.pop("required_fields")

        errors = validate_document(self.validator, document)

        self.assertFalse(
            any("required_fields must be a list of strings" in error for error in errors)
        )
        self.assertEqual(
            1,
            sum("must require mutation_digest" in error for error in errors),
        )

    def test_refresh_roles_rejects_malformed_canonical_error_any_of(self):
        for route_name in ("RefreshRoles", "POST /sessions/{sessionId}/refresh-roles"):
            for malformed in ("not-a-list", ["IDEMPOTENCY_CONFLICT", 7]):
                with self.subTest(route_name=route_name, malformed=malformed):
                    document = self.validator.yaml.safe_load(
                        MATRIX.read_text(encoding="utf-8")
                    )
                    route = route_for(document, "game-session-service", route_name)
                    route["canonical_errors"]["any_of"] = malformed
                    errors = []
                    self.validator.validate_refresh_roles_routes(
                        document["routes"], errors
                    )
                    label = f"game-session-service {route_name}"
                    self.assertIn(
                        f"{label} canonical_errors.any_of must be a list of strings",
                        errors,
                    )
                    self.assertIn(
                        f"{label} must declare IDEMPOTENCY_CONFLICT",
                        errors,
                    )

    def test_logging_admin_operator_mutations_require_idempotency_conflict(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        for service, route_name in sorted(
            self.validator.LOGGING_ADMIN_IDEMPOTENT_OPERATOR_ROUTES
        ):
            route = route_for(document, service, route_name)
            self.assertIn("mutation_digest", route["required_fields"])
            self.assertIn(
                "IDEMPOTENCY_CONFLICT",
                route["canonical_errors"]["any_of"],
            )

        route = route_for(
            document,
            "logging-admin-service",
            "POST /feature-flags/toggle",
        )
        route["canonical_errors"]["any_of"] = []
        errors = []
        self.validator.validate_logging_admin_idempotency(
            document["routes"],
            errors,
        )
        self.assertIn(
            "logging-admin-service POST /feature-flags/toggle must declare "
            "IDEMPOTENCY_CONFLICT",
            errors,
        )

    def test_privileged_operator_routes_require_live_global_role_and_assurance(self):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document = copy.deepcopy(baseline)
        route = route_for(
            document, "logging-admin-service", "POST /feature-flags/toggle"
        )
        route_position = route_index(document, route)
        platform_branch = next(
            branch
            for branch in route["operator_authorization_branches"]
            if branch["branch"] == "platformAdmin_global"
        )
        platform_branch["required_live_checks"].remove("current_global_role")
        errors = []
        self.validator.validate_generation_applicability(document["routes"], errors)
        self.assertIn(
            f"routes[{route_position}] logging-admin-service POST /feature-flags/toggle "
            "privileged operator route must require live check current_global_role",
            errors,
        )

        document = copy.deepcopy(baseline)
        route = route_for(
            document, "logging-admin-service", "POST /feature-flags/toggle"
        )
        route.pop("role_assurance")
        errors = []
        self.validator.validate_generation_applicability(document["routes"], errors)
        self.assertIn(
            f"routes[{route_position}] logging-admin-service POST /feature-flags/toggle "
            "operator route must declare role_assurance privileged_control_when_global_role",
            errors,
        )

        document = copy.deepcopy(baseline)
        owner_route = route_for(document, "game-session-service", "POST /sessions")
        owner_route_index = route_index(document, owner_route)
        owner_route.pop("canonical_external_ingress")
        errors = []
        self.validator.validate_generation_applicability(document["routes"], errors)
        self.assertIn(
            f"routes[{owner_route_index}] game-session-service POST /sessions "
            "must declare canonical_external_ingress logging-admin-service",
            errors,
        )

    def test_route_live_checks_cache_rejects_stale_identity_entries(self):
        route = {"required_live_checks": ["first_check"]}
        cache = {}
        errors = []

        cache[(id(route), "required_live_checks")] = (
            object(),
            {"stale_check"},
        )
        checks = self.validator.route_live_checks(route, "route", errors, cache)
        self.assertEqual({"first_check"}, checks)
        self.assertIs(cache[(id(route), "required_live_checks")][0], route)
        self.assertEqual([], errors)

    def test_live_checks_cache_is_scoped_to_source_identity_and_field_name(self):
        source = {}
        cache = {}
        errors = []

        first = self.validator.cached_live_checks(
            source,
            ["first_check"],
            "first field",
            errors,
            cache,
        )
        second = self.validator.cached_live_checks(
            source,
            ["second_check"],
            "second field",
            errors,
            cache,
        )

        self.assertEqual({"first_check"}, first)
        self.assertEqual({"second_check"}, second)
        self.assertEqual([], errors)

    def test_required_fields_cache_rejects_stale_identity_and_scopes_field_name(self):
        source = {}
        cache = {}
        errors = []

        cache[(id(source), "stale field")] = (object(), ["stale_field"])
        fields = self.validator.cached_required_fields(
            source,
            ["first_field"],
            "stale field",
            errors,
            cache,
        )
        other_fields = self.validator.cached_required_fields(
            source,
            ["second_field"],
            "other field",
            errors,
            cache,
        )

        self.assertEqual(["first_field"], fields)
        self.assertEqual(["second_field"], other_fields)
        self.assertIs(cache[(id(source), "stale field")][0], source)
        self.assertEqual([], errors)

    def test_multi_profile_unknown_name_is_not_resolved(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "ToggleFeatureFlag")
        route["accepted_token_profiles"] = ["control-ui", "unknown-profile"]
        route["accepted_token_profile_audiences"] = {
            "control-ui": "control-ui",
            "unknown-profile": "unknown",
        }
        route["token_type"] = "control_plane_user"
        route["token_issuer"] = "firemud-account-service"
        errors = validate_document(self.validator, document)
        self.assertTrue(any("uses unknown token profiles" in error for error in errors))
        self.assertFalse(
            any(
                "exactly one token profile per receiver policy" in error
                for error in errors
            )
        )

    def test_non_internal_single_profile_predicates_are_validated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "logging-admin-service", "POST /feature-flags/toggle"
        )
        route["token_type"] = "wrong_token_type"
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "token predicates must exactly match profile 'control-ui'" in error
                for error in errors
            )
        )

    def test_non_internal_single_profile_predicates_cannot_be_implicit(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "logging-admin-service", "POST /feature-flags/toggle"
        )
        for field in ("token_type", "token_issuer", "token_audience"):
            route.pop(field)
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "token predicates must exactly match profile 'control-ui'" in error
                for error in errors
            )
        )

    def test_non_internal_omitted_profiles_use_no_profile_predicates(self):
        for field in ("token_type", "token_issuer", "token_audience"):
            with self.subTest(field=field):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = route_for(document, "game-session-service", "PLAY")
                route.pop("accepted_token_profiles")
                route[field] = "unexpected"
                errors = validate_document(self.validator, document)
                self.assertTrue(
                    any(
                        "token_type/token_issuer/token_audience as none" in error
                        for error in errors
                    )
                )

    def test_non_internal_multi_profile_predicates_cannot_be_implicit(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = configure_multi_profile_route(document)
        route.pop("token_type")
        route.pop("token_issuer")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "multi-profile routes must declare token_type/token_issuer" in error
                for error in errors
            )
        )

    def test_malformed_mtls_shape_does_not_emit_duplicate_spiffe_error(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "StartSession")
        route["mtls_callers"]["any_of"] = "not-a-list"
        errors = validate_document(self.validator, document)
        structural_error = (
            "game-session-service StartSession mtls_callers.any_of "
            "must be a non-empty list of strings"
        )
        concrete_error = (
            "game-session-service StartSession mtls_callers.any_of "
            "must contain concrete spiffe:// identities"
        )
        self.assertEqual(1, errors.count(structural_error))
        self.assertNotIn(concrete_error, errors)

    def test_route_without_caller_policies_reports_method_policy_once(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "ToggleFeatureFlag")
        route["method_policy"] = "all_methods"
        errors = validate_document(self.validator, document)
        self.assertEqual(
            1,
            errors.count(
                "game-session-service ToggleFeatureFlag must declare method_policy exact_declared_route"
            ),
        )

    def test_profile_routes_bind_every_role_to_the_caller(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        for service, route_name in self.validator.PROFILE_ROUTES:
            route = route_for(document, service, route_name)
            self.assertEqual(
                "caller_account_id", route["subject_binding"]
            )
            self.assertNotIn("self_only_roles", route)
            self.assertEqual(
                "exact_caller_account_id_for_every_role", route["target_subject_binding"]
            )
            self.assertEqual("forbidden", route["platform_admin_override"])

    def test_profile_routes_reject_role_subject_bypasses(self):
        mutations = (
            (
                "self_only_roles",
                lambda route: route.__setitem__("self_only_roles", ["tenantAdmin"]),
                "must not declare self_only_roles",
            ),
            (
                "target_subject_binding",
                lambda route: route.__setitem__(
                    "target_subject_binding", "explicit_target_account_id"
                ),
                "must declare target_subject_binding exact_caller_account_id_for_every_role",
            ),
        )
        for service, route_name in self.validator.PROFILE_ROUTES:
            for mutation_name, mutate, expected_error in mutations:
                with self.subTest(route=route_name, mutation=mutation_name):
                    document = self.validator.yaml.safe_load(
                        MATRIX.read_text(encoding="utf-8")
                    )
                    matching = self.validator.matching_routes(
                        document["routes"], service, route_name
                    )
                    self.assertTrue(matching)
                    for route in matching:
                        mutate(route)

                    errors = validate_document(self.validator, document)
                    self.assertIn(
                        f"{self.validator.route_label(route)} {expected_error}",
                        errors,
                    )

    def test_account_subject_routes_use_explicit_self_service_and_override_branches(
        self,
    ):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        expected_branches = {
            "self_service": {
                "target_subject_binding": "exact_caller_account_id",
                "required_live_checks": {"current_account_generation"},
            },
            "platformAdmin_override": {
                "target_subject_binding": "explicit_target_account_id",
                "required_live_checks": {
                    "issuer_generation",
                    "account_generation",
                    "current_global_role",
                    "role_appropriate_assurance",
                },
            },
        }
        for route_name in ("ExportAccount", "DeleteAccount"):
            with self.subTest(route=route_name):
                route = route_for(document, "account-service", route_name)
                self.assertEqual("account_scoped", route["classification"])
                self.assertEqual("caller_account_id", route["subject_binding"])
                self.assertEqual("platformAdmin_only", route["platform_admin_override"])
                branches = {
                    branch["branch"]: {
                        "target_subject_binding": branch["target_subject_binding"],
                        "required_live_checks": set(branch["required_live_checks"]),
                    }
                    for branch in route["account_authorization_branches"]
                }
                self.assertEqual(expected_branches, branches)
                self.assertNotIn("current_global_role", route["required_live_checks"])
                self.assertNotIn(
                    "role_appropriate_assurance", route["required_live_checks"]
                )
                self.assertNotIn(
                    "current_account_generation", route["required_live_checks"]
                )

        export = route_for(document, "account-service", "ExportAccount")
        self.assertEqual(
            "asynchronous_versioned_cross_owner_export_manifest",
            export["response_profile"],
        )
        delete = route_for(document, "account-service", "DeleteAccount")
        self.assertEqual(
            "pending_delete_advances_account_generation_then_runs_retryable_cross_owner_erasure",
            delete["mutation_contract"],
        )
        self.assertEqual("control-ui", export["token_audience"])
        self.assertEqual("firemud-account-service", export["token_issuer"])

    def test_account_subject_global_override_retains_generation_fences(self):
        for route_name in ("ExportAccount", "DeleteAccount"):
            with self.subTest(route=route_name):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = route_for(document, "account-service", route_name)
                branch = next(
                    branch
                    for branch in route["account_authorization_branches"]
                    if branch["branch"] == "platformAdmin_override"
                )
                branch["required_live_checks"].remove("issuer_generation")
                errors = validate_document(self.validator, document)
                self.assertTrue(
                    any(
                        "platformAdmin_override" in error
                        and "required_live_checks must equal" in error
                        for error in errors
                    )
                )

    def test_account_subject_routes_reject_missing_or_mixed_branch_checks(self):
        for route_name in ("ExportAccount", "DeleteAccount"):
            with self.subTest(route=route_name):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = route_for(document, "account-service", route_name)
                route["account_authorization_branches"] = [
                    route["account_authorization_branches"][0]
                ]
                errors = validate_document(self.validator, document)
                self.assertTrue(
                    any(
                        "account_authorization_branches must contain exactly" in error
                        for error in errors
                    )
                )

                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = route_for(document, "account-service", route_name)
                self_service = next(
                    branch
                    for branch in route["account_authorization_branches"]
                    if branch["branch"] == "self_service"
                )
                self_service["required_live_checks"] = ["current_global_role"]
                errors = validate_document(self.validator, document)
                self.assertTrue(
                    any(
                        "self_service" in error
                        and "required_live_checks must equal" in error
                        for error in errors
                    )
                )

                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = route_for(document, "account-service", route_name)
                platform_branch = next(
                    branch
                    for branch in route["account_authorization_branches"]
                    if branch["branch"] == "platformAdmin_override"
                )
                platform_branch["required_live_checks"].remove(
                    "role_appropriate_assurance"
                )
                errors = validate_document(self.validator, document)
                self.assertTrue(
                    any(
                        "platformAdmin_override" in error
                        and "required_live_checks must equal" in error
                        for error in errors
                    )
                )

    def test_account_subject_routes_reject_branch_only_checks_in_common_checks(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "ExportAccount")
        route["required_live_checks"].append("role_appropriate_assurance")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "required_live_checks must not duplicate branch-qualified checks"
                in error
                for error in errors
            )
        )

    def test_export_tenant_data_is_billing_safe_and_membership_bound(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "ExportTenantData")
        self.assertEqual("billing_safe_tenant", route["classification"])
        self.assertFalse(route["tenant_billing_authority_generation_applies"])
        self.assertNotIn("tenant_generation", route["required_live_checks"])
        self.assertTrue(route["membership_authority_generation_applies"])
        self.assertIn("membership_generation", route["required_live_checks"])
        self.assertIn("membership", route["required_live_checks"])
        self.assertIn("current_operator_roles", route["required_live_checks"])

    def test_all_billing_safe_tenant_routes_require_route_class_checks(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = [
            route
            for route in document["routes"]
            if route.get("classification") == "billing_safe_tenant"
        ]
        self.assertTrue(routes, "matrix must define billing_safe_tenant routes")
        expected_checks = {
            "issuer_generation",
            "account_generation",
            "current_operator_roles",
            "membership",
            "membership_generation",
            "membership_version",
        }
        for route in routes:
            with self.subTest(route=route["route"]):
                self.assertTrue(
                    expected_checks.issubset(set(route["required_live_checks"]))
                )
                self.assertEqual(["tenantAdmin"], route["roles"]["any_of"])

    def test_billing_safe_tenant_route_role_check_cannot_be_omitted(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("classification") == "billing_safe_tenant"
        )
        route["required_live_checks"].remove("current_operator_roles")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "is missing route-class authority checks: ['current_operator_roles']"
                in error
                for error in errors
            )
        )

    def test_billing_safe_tenant_route_role_must_remain_tenant_admin(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("classification") == "billing_safe_tenant"
        )
        route["roles"]["any_of"] = ["support"]
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "billing_safe_tenant roles.any_of must be ['tenantAdmin']" in error
                for error in errors
            )
        )

    def test_play_rechecks_membership_generation(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "PLAY")
        self.assertTrue(route["tenant_authority_generation_applies"])
        self.assertTrue(route["membership_authority_generation_applies"])
        self.assertTrue(route["membership_version_applies"])
        self.assertNotIn("membership_authority_generation_condition", route)
        self.assertTrue(
            self.validator.REQUIRED_PLAY_GENERATION_CHECKS.issubset(
                set(route["required_live_checks"])
            )
        )

        route["required_live_checks"].remove("target_tenant_generation")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "game-session-service PLAY is missing exact selected-tenant generation checks"
                in error
                for error in errors
            )
        )

        route["classification"] = "player_bootstrap_tenant"
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "game-session-service PLAY must use classification gameplay_admission"
                in error
                for error in errors
            )
        )

    def test_play_admission_composes_common_checks_with_selected_branch_metadata(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "PLAY")
        self.assertIn("TENANT_BILLING_BLOCKED", route["canonical_errors"]["any_of"])
        selection = route["admission_mode_selection"]
        common = set(selection["required_live_checks"])
        self.assertEqual(
            common,
            self.validator.REQUIRED_GAMEPLAY_ADMISSION_COMMON_CHECKS,
        )
        self.assertEqual(
            common
            | set(selection["branches"]["returning_membership"]["required_live_checks"]),
            {
                "runtime_entitlements",
                "admission_pointer",
                "membership",
                "membership_generation",
                "realm_visibility",
            },
        )
        self.assertNotIn(
            "public_production_admission",
            selection["branches"]["returning_membership"]["required_live_checks"],
        )
        self.assertEqual(
            common
            | set(
                selection["branches"]["grant_backed_private_or_playtest"][
                    "required_live_checks"
                ]
            ),
            {
                "runtime_entitlements",
                "admission_pointer",
                "membership",
                "membership_generation",
                "conditional_realm_access_grant",
            },
        )
        self.assertTrue(common.issubset(set(route["required_live_checks"])))
        self.assertIn("target_tenant_generation", route["required_live_checks"])

    def test_play_rejects_legacy_conditional_membership_generation(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "PLAY")
        route["membership_authority_generation_applies"] = (
            "conditional_by_admission_mode"
        )
        errors = []
        self.validator.validate_generation_applicability(document["routes"], errors)
        self.assertTrue(
            any(
                "membership_authority_generation_applies must be one of" in error
                for error in errors
            )
        )

    def test_true_membership_generation_requires_live_check(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, *self.validator.PROFILE_ROUTES[0])
        route["required_live_checks"].remove("membership_generation")
        errors = []
        self.validator.validate_generation_applicability(document["routes"], errors)
        self.assertTrue(
            any(
                "account-service GET /tenants/{tenantId}/profiles/{accountId} membership_authority_generation_applies=true "
                "requires live check membership_generation" in error
                for error in errors
            )
        )

    def test_resume_ingress_and_owner_require_recovery_gate(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        for service, route_name in (
            ("logging-admin-service", "POST /tick-remediation/resume"),
            ("game-session-service", "ResumeTicksForScope"),
        ):
            with self.subTest(service=service, route=route_name):
                route = route_for(document, service, route_name)
                self.assertIn("recovery_resume_gate", route["required_live_checks"])

    def test_route_class_branch_table_matches_canonical_branches(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        branches = {
            (entry["classification"], entry["branch"]): entry
            for entry in document["route_class_branch_table"]
        }
        self.assertEqual(
            set(self.validator.EXPECTED_ROUTE_CLASS_BRANCHES), set(branches)
        )
        self.assertEqual(
            "omitted",
            branches[("billing_safe_tenant", "tenantAdmin")]["generations"]["tenant"],
        )
        self.assertEqual(
            "not_required",
            branches[("cross_tenant_support_safe", "support_global")][
                "privileged_control"
            ],
        )
        self.assertEqual(
            "required",
            branches[("cross_tenant_support_safe", "platformAdmin_global")][
                "privileged_control"
            ],
        )

    def test_route_class_branch_table_rejects_intentional_omission_drift(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        entry = next(
            entry
            for entry in document["route_class_branch_table"]
            if entry["classification"] == "billing_safe_tenant"
        )
        entry["generations"]["membership"] = "omitted"
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "billing_safe_tenant tenantAdmin.generations.membership must be 'required'"
                in error
                for error in errors
            )
        )

    def test_route_class_branch_table_reports_non_string_keys_without_raising(self):
        for field, value in (
            ("classification", ["tenant_regular"]),
            ("branch", {"name": "tenant_role"}),
        ):
            with self.subTest(field=field):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                entry = document["route_class_branch_table"][0]
                entry[field] = value
                errors = validate_document(self.validator, document)
                self.assertIn(
                    f"route_class_branch_table[0].{field} must be a string",
                    errors,
                )
                self.assertTrue(
                    any(
                        "must contain exactly the canonical route-class branches"
                        in error
                        for error in errors
                    )
                )

    def test_route_class_branch_table_duplicate_key_keeps_first_entry(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        duplicate = copy.deepcopy(document["route_class_branch_table"][0])
        duplicate["privileged_control"] = "unsupported"
        duplicate_index = len(document["route_class_branch_table"])
        document["route_class_branch_table"].append(duplicate)
        errors = validate_document(self.validator, document)
        self.assertIn(
            f"route_class_branch_table[{duplicate_index}] duplicates route-class branch "
            "('account_scoped', 'platformAdmin_global')",
            errors,
        )
        self.assertFalse(
            any(
                "account_scoped platformAdmin_global must declare privileged_control="
                in error
                for error in errors
            )
        )

    def test_route_class_branch_table_reports_invalid_privileged_control_once(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        entry = document["route_class_branch_table"][0]
        entry["privileged_control"] = "unsupported"
        errors = validate_document(self.validator, document)
        self.assertEqual(
            1,
            errors.count(
                "route_class_branch_table account_scoped "
                "platformAdmin_global.privileged_control must be one of "
                "['establishes_window', 'not_required', 'required']"
            ),
        )
        self.assertFalse(
            any(
                "account_scoped platformAdmin_global must declare privileged_control="
                in error
                for error in errors
            )
        )

    def test_join_pre_membership_contract_is_explicit(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        expected = {
            "public_production_visibility",
            "public_production_admission",
            "runtime_entitlements",
            "admission_pointer",
            "idempotency",
        }
        exception = document["tenant_membership_policy"][
            "public_production_join_exception"
        ]
        self.assertEqual(expected, set(exception["required_pre_membership_checks"]))
        for service, route_name in self.validator.JOIN_ROUTES_REQUIRING_POINTER_ERROR:
            route = route_for(document, service, route_name)
            self.assertTrue(expected.issubset(set(route["required_live_checks"])))
            self.assertEqual(
                "caller_bound_after_validation", route["membership_creation"]
            )

    def test_membership_writer_requires_current_account_state_and_pending_deletion_gate(self):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        self.assertNotIn(
            "EnsurePublicProductionPlayerMembership",
            {route["route"] for route in baseline["routes"]},
        )
        route = route_for(
            baseline, *self.validator.MEMBERSHIP_WRITER_ROUTE
        )
        self.assertTrue(
            self.validator.REQUIRED_MEMBERSHIP_WRITER_CHECKS.issubset(
                set(route["required_live_checks"])
            )
        )
        for missing_check in self.validator.REQUIRED_MEMBERSHIP_WRITER_CHECKS:
            with self.subTest(missing_check=missing_check):
                document = copy.deepcopy(baseline)
                route = route_for(
                    document,
                    *self.validator.MEMBERSHIP_WRITER_ROUTE,
                )
                route["required_live_checks"].remove(missing_check)
                errors = []
                self.validator.validate_membership_writer_route(
                    document["routes"], errors
                )
                self.assertTrue(
                    any("missing membership-writer checks" in error for error in errors)
                )

    def test_join_pre_membership_checks_are_validated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "JOIN")
        route["required_live_checks"].remove("idempotency")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "game-session-service JOIN is missing pre-membership checks" in error
                for error in errors
            )
        )

    def test_join_exception_routes_reject_unhashable_and_non_string_entries(self):
        for malformed_entry in (
            ["game-session-service", {"route": "JOIN"}],
            ["game-session-service", ["JOIN"]],
            ["game-session-service", 7],
        ):
            with self.subTest(malformed_entry=malformed_entry):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                exception = document["tenant_membership_policy"][
                    "public_production_join_exception"
                ]
                exception["routes"][0] = malformed_entry
                errors = validate_document(self.validator, document)
                self.assertIn(
                    "tenant_membership_policy.public_production_join_exception."
                    "routes[0] must be a two-item list of strings",
                    errors,
                )

    def test_join_exception_routes_keep_exact_valid_route_set_comparison(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        exception = document["tenant_membership_policy"][
            "public_production_join_exception"
        ]
        exception["routes"].append(["account-service", "UnexpectedRoute"])
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "public-production exception must enumerate exactly" in error
                for error in errors
            )
        )

    def test_fresh_authority_evidence_excludes_bound_ordinary_gameplay_rereads(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        fresh = document["authority_evidence_policy"]["fail_closed_fresh_evidence"]
        self.assertEqual(
            {
                "admission",
                "play",
                "renewal",
                "reconnect",
                "resume",
                "protected_control_plane_mutation",
            },
            set(fresh["applies_to"]),
        )
        self.assertEqual("AUTH_UNAVAILABLE", fresh["unreachable_or_timeout"])
        self.assertEqual(
            "AUTH_SESSION_REVOKED",
            fresh["reachable_invalid_or_ambiguous"],
        )
        self.assertTrue(fresh["route_specific_canonical_errors_precedence"])
        bound = document["authority_evidence_policy"]["bound_ordinary_gameplay"]
        self.assertFalse(bound["pointer_authority_reread"])
        self.assertEqual(
            {"bound_game_instance", "runtime_fence"}, set(bound["required_fences"])
        )

    def test_fresh_authority_evidence_requires_distinct_failure_outcomes(self):
        cases = (
            (
                "unreachable_or_timeout",
                "AUTH_SESSION_REVOKED",
                "must fail closed with AUTH_UNAVAILABLE",
            ),
            (
                "reachable_invalid_or_ambiguous",
                "AUTH_UNAVAILABLE",
                "must fail closed with AUTH_SESSION_REVOKED",
            ),
            (
                "route_specific_canonical_errors_precedence",
                False,
                "must give route-specific canonical_errors precedence",
            ),
        )
        for field, invalid_value, expected_error in cases:
            with self.subTest(field=field):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                document["authority_evidence_policy"]["fail_closed_fresh_evidence"][
                    field
                ] = invalid_value
                errors = []
                self.validator.validate_authority_evidence_policy(document, errors)
                self.assertTrue(
                    any(expected_error in error for error in errors),
                    errors,
                )

    def test_authority_evidence_vocabularies_reject_malformed_values(self):
        cases = (
            (
                "fail_closed_fresh_evidence",
                "applies_to",
                "authority_evidence_policy.fail_closed_fresh_evidence.applies_to",
            ),
            (
                "bound_ordinary_gameplay",
                "required_fences",
                "authority_evidence_policy.bound_ordinary_gameplay.required_fences",
            ),
        )
        for section, field, error_field in cases:
            for malformed in (None, 7, {"value": "invalid"}, ["valid", 7]):
                with self.subTest(section=section, field=field, malformed=malformed):
                    document = self.validator.yaml.safe_load(
                        MATRIX.read_text(encoding="utf-8")
                    )
                    document["authority_evidence_policy"][section][field] = malformed
                    errors = []
                    self.validator.validate_authority_evidence_policy(document, errors)
                    self.assertIn(f"{error_field} must be a list of strings", errors)

    def test_ws_game_defers_membership_to_downstream_admission(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = [
            route
            for route in document["routes"]
            if route.get("service") == "spring-cloud-gateway"
            and route.get("route") == "/ws/game/**"
        ]
        self.assertEqual(2, len(routes))
        for route in routes:
            self.assertFalse(route["tenant_billing_authority_generation_applies"])
            self.assertFalse(route["membership_authority_generation_applies"])
            downstream = route["downstream_admission_contract"]
            self.assertFalse(downstream["tenant_billing_authority_generation_applies"])
            self.assertEqual(
                "required_fail_closed", downstream["admission_mode_selection"]
            )
            self.assertEqual(
                {
                    "public_production_onboarding",
                    "returning_membership",
                    "grant_backed_private_or_playtest",
                },
                set(downstream["required_mode_branches"]),
            )

    def test_ws_game_downstream_membership_contract_is_required(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("service") == "spring-cloud-gateway"
            and route.get("route") == "/ws/game/**"
            and {"connection_mode": "trusted_tcp_proxy"}
            in route.get("applicability", {}).get("all_of", [])
        )
        route["downstream_admission_contract"]["admission_mode_selection"] = (
            "public_production_onboarding"
        )
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "/ws/game/** trusted_tcp_proxy downstream_admission_contract must require fail-closed admission mode selection"
                in error
                for error in errors
            )
        )

    def test_cross_tenant_safe_routes_do_not_require_target_membership(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        matched_routes = [
            route
            for route in document["routes"]
            if route.get("classification")
            in {"cross_tenant_support_safe", "cross_tenant_billing_safe"}
        ]
        self.assertTrue(matched_routes)
        for route in matched_routes:
            self.assertFalse(route["tenant_billing_authority_generation_applies"])
            self.assertFalse(route["membership_authority_generation_applies"])
            self.assertNotIn("membership", route["required_live_checks"])
            self.assertNotIn("membership_generation", route["required_live_checks"])

    def test_unknown_live_check_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "WORLDS_PUBLIC")
        route["required_live_checks"] = ["unknown_check"]
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any("outside the closed vocabulary" in error for error in errors)
        )

    def test_unknown_auth_path_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "Authenticate")
        route["auth_path"] = "mtls_workload"
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "auth_path contains values outside the closed vocabulary" in error
                for error in errors
            )
        )

    def test_nested_unknown_auth_path_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "account-service", "GetTenantEntitlementsForRuntime"
        )
        route["caller_policies"][0]["auth_path"] = (
            "mtls_workload_plus_current_token_generation"
        )
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "auth_path contains values outside the closed vocabulary" in error
                for error in errors
            )
        )

    def test_caller_policy_unknown_token_profile_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "account-service", "GetTenantEntitlementsForRuntime"
        )
        policy = next(
            policy
            for policy in route["caller_policies"]
            if policy.get("caller") == "game-session-service"
        )
        policy["accepted_token_profiles"] = ["unknown-profile"]
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "uses unknown token profiles" in error and "unknown-profile" in error
                for error in errors
            )
        )

    def test_outer_route_unknown_token_profile_is_reported_once(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document,
            "account-service",
            "IssueHumanOperatorAuthorizationReference",
        )
        route["accepted_token_profiles"] = ["unknown-profile"]
        errors = []
        token_profiles = self.validator.validate_token_profiles(document, errors)
        self.validator.validate_receiver_predicates([route], token_profiles, errors)
        self.assertEqual(
            ["matrix.routes[0] uses unknown token profiles: ['unknown-profile']"],
            errors,
        )

    def test_malformed_route_profiles_are_reported_once(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "ToggleFeatureFlag")
        route_position = route_index(document, route)
        route["accepted_token_profiles"] = "control-ui"
        errors = validate_document(self.validator, document)
        self.assertEqual(
            1,
            errors.count(
                f"matrix.routes[{route_position}] accepted_token_profiles must be a list of strings"
            ),
        )

    def test_empty_token_profiles_require_none_predicates(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "PLAY")
        errors = validate_document(self.validator, document)
        self.assertEqual([], errors)

        route["token_audience"] = "gameplay"
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                error.startswith("matrix.routes[")
                and "token_type/token_issuer/token_audience as none" in error
                for error in errors
            )
        )

    def test_named_no_jwt_routes_use_explicit_none_metadata(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        for service, route_name in self.validator.EXPLICIT_NO_JWT_ROUTES:
            route = route_for(document, service, route_name)
            with self.subTest(service=service, route=route_name):
                self.assertEqual([], route["accepted_token_profiles"])
                self.assertEqual("none", route["token_type"])
                self.assertEqual("none", route["token_issuer"])
                self.assertEqual("none", route["token_audience"])

    def test_named_no_jwt_routes_reject_omitted_metadata(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "LOGIN")
        route["service"] = " game-session-service "
        route["route"] = " LOGIN "
        route.pop("accepted_token_profiles")
        route.pop("token_type")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "must explicitly declare accepted_token_profiles=[]" in error
                for error in errors
            )
        )

    def test_route_set_key_trims_components_for_policy_lookups(self):
        for route_set in (
            self.validator.CONDITIONAL_OPERATOR_ROUTES,
            self.validator.ADMISSION_POINTER_MUTATION_ROUTES,
            self.validator.GAME_SESSION_OPERATOR_ROUTES,
            self.validator.ACCOUNT_SUBJECT_BOUND_ROUTES,
        ):
            service, route_name = next(iter(route_set))
            with self.subTest(service=service, route=route_name):
                self.assertIn(
                    self.validator.route_set_key(
                        {"service": f" {service} ", "route": f" {route_name} "}
                    ),
                    route_set,
                )

    def test_route_resolution_trims_padded_route_metadata(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "LOGIN")
        route["service"] = " game-session-service "
        route["route"] = " LOGIN "

        errors = []
        self.assertEqual(
            [route],
            self.validator.matching_routes(
                document["routes"], "game-session-service", "LOGIN"
            ),
        )
        self.assertIs(
            route,
            self.validator.resolve_unique_route(
                document["routes"],
                "game-session-service",
                "LOGIN",
                errors,
            ),
        )
        self.assertEqual([], errors)

    def test_malformed_token_profiles_skip_predicate_validation_after_shape_error(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "PLAY")
        route["accepted_token_profiles"] = ["gameplay-connect", 7]
        route["token_type"] = "unexpected"
        route["token_issuer"] = "unexpected"
        route["token_audience"] = "unexpected"
        errors = validate_document(self.validator, document)
        self.assertEqual(
            1,
            sum(
                error.endswith("accepted_token_profiles must be a list of strings")
                for error in errors
            ),
        )
        self.assertFalse(
            any(
                error.startswith("matrix.routes[")
                and "token_type/token_issuer/token_audience as none" in error
                for error in errors
            )
        )

    def test_malformed_route_identities_do_not_report_equality_after_shape_error(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        requirement = document["role_assurance"][
            "privileged_control_when_global_role"
        ]["requirements"]["platformAdmin"]
        requirement["applies_to"]["route_identities"] = [7]
        errors = []
        self.validator.validate_role_assurance(document, errors)
        self.assertEqual(
            [
                (
                    "role_assurance.privileged_control_when_global_role.requirements."
                    "platformAdmin.applies_to.route_identities must be a list of strings"
                )
            ],
            errors,
        )

    def test_multi_profile_route_with_audience_map_is_accepted(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        configure_multi_profile_route(document)
        errors = validate_document(self.validator, document)
        self.assertEqual([], errors)

    def test_multi_profile_route_reports_audience_map_error(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = configure_multi_profile_route(document)
        route["accepted_token_profile_audiences"] = {"control-ui": "control-ui"}
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "accepted token audience keys must equal accepted profiles" in error
                for error in errors
            )
        )
        self.assertFalse(
            any(
                "exactly one token profile per receiver policy" in error
                for error in errors
            )
        )

    def test_duplicate_profile_preserves_first_definition(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document["token_profiles"].append(
            {
                "profile": "control-ui",
                "type": "different_type",
                "issuer": "firemud-account-service",
                "audience": "different-audience",
            }
        )
        errors = []
        profiles = self.validator.validate_token_profiles(document, errors)
        self.assertTrue(
            any("duplicate profile 'control-ui'" in error for error in errors)
        )
        self.assertEqual("control-ui", profiles["control-ui"]["audience"])

    def test_route_status_vocabulary_is_declared_and_closed(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        errors = []
        statuses = self.validator.validate_route_status_vocabulary(document, errors)
        self.assertEqual(
            {"current_openapi_operator_surface", "target_not_currently_routable"},
            statuses,
        )
        self.assertEqual([], errors)

        document["route_status_vocabulary"].append("current_openapi_operator_surface")
        errors = []
        self.validator.validate_route_status_vocabulary(document, errors)
        self.assertEqual(
            ["route_status_vocabulary must not contain duplicates"], errors
        )

    def test_route_status_uses_declared_vocabulary(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("route_status") == "target_not_currently_routable"
        )
        route["route_status"] = "declared_but_not_current"
        document["route_status_vocabulary"].append("declared_but_not_current")
        errors = []
        statuses = self.validator.validate_route_status_vocabulary(document, errors)
        self.validator.validate_route_statuses(document["routes"], statuses, errors)
        self.assertTrue(
            any(
                "route_status_vocabulary must contain exactly" in error
                for error in errors
            )
        )

        document["route_status_vocabulary"].remove("declared_but_not_current")
        errors = []
        statuses = self.validator.validate_route_status_vocabulary(document, errors)
        self.validator.validate_route_statuses(document["routes"], statuses, errors)
        self.assertTrue(any("route_status must be one of" in error for error in errors))

    def test_route_status_may_be_omitted_without_implying_routability(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "POST /sessions")
        route.pop("route_status")
        errors = []
        statuses = self.validator.validate_route_status_vocabulary(document, errors)
        self.validator.validate_route_statuses(document["routes"], statuses, errors)
        self.assertEqual([], errors)

    def test_required_fields_use_snake_case(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        errors = []
        self.validator.validate_required_fields(document["routes"], errors)
        self.assertEqual([], errors)

        route = route_for(document, "account-service", "EnterPrivilegedControlWindow")
        route["required_fields"] = ["RequestedGlobalRole"]
        errors = []
        self.validator.validate_required_fields(document["routes"], errors)
        self.assertTrue(
            any("required_fields must use snake_case" in error for error in errors)
        )

    def test_operator_reference_issuance_requires_schema_pair_fields(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        expected_fields = {
            "action_family_schema_id",
            "action_family_schema_version",
        }
        for route_name in (
            "IssueHumanOperatorAuthorizationReference",
            "IssueAutomationOperatorAuthorizationReference",
        ):
            with self.subTest(route_name=route_name):
                route = route_for(document, "account-service", route_name)
                self.assertTrue(expected_fields.issubset(set(route["required_fields"])))

        route = route_for(
            document, "account-service", "IssueHumanOperatorAuthorizationReference"
        )
        route["required_fields"].remove("action_family_schema_id")
        errors = validate_document(self.validator, document)
        self.assertIn(
            "account-service IssueHumanOperatorAuthorizationReference required_fields "
            "must include operator-reference fields: ['action_family_schema_id']",
            errors,
        )

    def test_operator_reference_issuance_missing_required_fields_has_one_diagnostic(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "account-service", "IssueHumanOperatorAuthorizationReference"
        )
        route.pop("required_fields")

        errors = validate_document(self.validator, document)

        self.assertEqual(
            1,
            errors.count(
                "account-service IssueHumanOperatorAuthorizationReference "
                "required_fields must include operator-reference fields: "
                "['action_family', 'action_family_schema_id', "
                "'action_family_schema_version', 'control_plane_request_id', "
                "'mutation_digest', 'tenant_scope']"
            ),
        )
        self.assertFalse(
            any(
                "account-service IssueHumanOperatorAuthorizationReference "
                "required_fields must be a list of strings" in error
                for error in errors
            )
        )

    def test_unavailable_authority_uses_one_canonical_error(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        entitlement_route = route_for(document, "game-session-service", "REALMS")
        self.assertIn(
            "ENTITLEMENT_UNAVAILABLE",
            entitlement_route["canonical_errors"]["any_of"],
        )
        self.assertNotIn(
            "ENTITLEMENT_UNAVAILABLE",
            self.validator.UNAVAILABLE_AUTHORITY_ERROR_ALIASES,
        )
        errors = []
        self.validator.validate_authority_unavailable_outcomes(
            document["routes"], errors
        )
        self.assertEqual([], errors)
        for route_name in (
            "IssueConnectToken",
            "CommitTenantCapacityAdmission",
            "BillingArtifactsTenant",
            "BillingArtifactsCrossTenant",
        ):
            with self.subTest(route_name=route_name):
                route = route_for(document, "account-service", route_name)
                self.assertIn("AUTH_UNAVAILABLE", route["canonical_errors"]["any_of"])

        route = route_for(document, "account-service", "BillingArtifactsTenant")
        route["canonical_errors"]["any_of"] = ["MEMBERSHIP_AUTH_UNAVAILABLE"]
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "must use AUTH_UNAVAILABLE instead of unavailable-authority aliases"
                in error
                for error in errors
            )
        )

    def test_privileged_control_entry_route_establishes_the_window(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "EnterPrivilegedControlWindow")
        bootstrap = document["elevation_contracts"]["privileged_control"][
            "bootstrap_exemption"
        ]
        self.assertEqual("establishes_window", route["privileged_control"])
        self.assertEqual(
            "account-service/EnterPrivilegedControlWindow", bootstrap["route"]
        )
        self.assertFalse(bootstrap["requires_existing_window"])

    def test_privileged_control_entry_route_rejects_existing_window_requirement(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document["elevation_contracts"]["privileged_control"]["bootstrap_exemption"][
            "requires_existing_window"
        ] = True
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "privileged_control bootstrap exemption must not require an existing window"
                in error
                for error in errors
            )
        )

    def test_invalid_privileged_control_does_not_cascade(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "EnterPrivilegedControlWindow")
        route["privileged_control"] = "unsupported"
        errors = []
        self.validator.validate_elevation_bootstrap(
            document, document["routes"], errors
        )
        self.assertEqual(
            [
                (
                    "account-service EnterPrivilegedControlWindow privileged_control "
                    "must be one of ['establishes_window', 'not_required', 'required']"
                )
            ],
            errors,
        )

    def test_pending_deletion_uses_canonical_account_generation_field(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        pending_routes = [
            route
            for route in document["routes"]
            if route.get("classification") == "pending_deletion_scoped"
        ]
        self.assertTrue(pending_routes, "matrix must define pending-deletion routes")
        for route in pending_routes:
            self.assertEqual([], route["accepted_token_profiles"])
            self.assertEqual(["pending-deletion-access"], route["accepted_credentials"])
            self.assertEqual("none", route["token_type"])
            self.assertEqual("none", route["token_issuer"])
            self.assertEqual("none", route["token_audience"])
            self.assertFalse(route["account_authority_generation_applies"])
            self.assertFalse(route["tenant_billing_authority_generation_applies"])
            self.assertFalse(route["membership_authority_generation_applies"])

        route = pending_routes[0]
        route["account_generation_applies"] = route.pop(
            "account_authority_generation_applies"
        )
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "must use account_authority_generation_applies" in error
                for error in errors
            )
        )

    def test_pending_deletion_routes_disable_issuer_authority_generation(self):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        pending_routes = [
            route
            for route in baseline["routes"]
            if route.get("classification") == "pending_deletion_scoped"
        ]
        self.assertTrue(pending_routes)
        self.assertTrue(
            all(
                route["issuer_authority_generation_applies"] is False
                for route in pending_routes
            )
        )

        document = copy.deepcopy(baseline)
        route = next(
            route
            for route in document["routes"]
            if route.get("classification") == "pending_deletion_scoped"
        )
        route["issuer_authority_generation_applies"] = True
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "pending_deletion_scoped routes must set "
                "issuer_authority_generation_applies=false" in error
                for error in errors
            )
        )

    def test_pending_deletion_generation_exception_has_bounded_negative_proof(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        self.assertNotIn(
            "pending_deletion_scoped",
            document["tenant_generation_policy"]["exception_allowlist"],
        )
        exception = document["tenant_generation_policy"][
            "no_target_tenant_classifications"
        ]["pending_deletion_scoped"]
        self.assertFalse(exception["target_tenant_generation"])
        self.assertEqual(
            "pending_deletion_credential_only", exception["generation_behavior"]
        )
        self.assertIn("pending_deletion_state", exception["required_authority"])
        self.assertTrue(exception["contract_justification"])
        self.assertEqual(
            "denied_by_pending_deletion_credential_contract",
            exception["target_tenant_generation_advance_behavior"],
        )
        self.assertIn(
            "pending_deletion_route_denied_after_target_tenant_generation_advance",
            exception["negative_proof"]["required"],
        )

        exception.pop("contract_justification")
        errors = validate_document(self.validator, document)
        self.assertIn(
            "tenant_generation_policy.no_target_tenant_classifications."
            "pending_deletion_scoped must declare a bounded contract_justification",
            errors,
        )

    def test_security_lock_export_contract_has_bounded_negative_proof(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        rule = document["classification_rules"]["security_lock_export_scoped"]
        self.assertEqual(
            ["accountId", "recoveryCaseId"], rule["subject_binding"]
        )
        self.assertEqual(
            ["accountId", "recoveryCaseId", "exportId"],
            rule["export_operation_binding"]["required"],
        )
        self.assertEqual(
            {
                "security_lock_export_recovery_case_mismatch_denied",
                "security_lock_export_export_job_mismatch_denied",
                "security_lock_export_action_family_mismatch_denied",
            },
            set(
                document["tenant_generation_policy"]["no_target_tenant_classifications"][
                    "security_lock_export_scoped"
                ]["negative_proof"]["required"]
            ),
        )
        self.assertEqual([], validate_document(self.validator, document))

        for proof_name in self.validator.SECURITY_LOCK_EXPORT_NEGATIVE_PROOF:
            with self.subTest(proof=proof_name):
                mutated = copy.deepcopy(document)
                proof = mutated["tenant_generation_policy"][
                    "no_target_tenant_classifications"
                ]["security_lock_export_scoped"]["negative_proof"]["required"]
                proof.remove(proof_name)
                errors = validate_document(self.validator, mutated)
                self.assertTrue(
                    any(
                        "security_lock_export_scoped must declare exactly the bounded "
                        "security-lock export negative proof requirements" in error
                        for error in errors
                    )
                )

    def test_pending_deletion_export_initiation_requires_availability(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("classification") == "pending_deletion_scoped"
            and route.get("route") == "POST /accounts/{accountId}/exports"
        )
        self.assertIn("export_availability", route["required_live_checks"])
        route["required_live_checks"].remove("export_availability")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "pending-deletion export initiation must require live check "
                "export_availability" in error
                for error in errors
            )
        )

    def test_reporting_billing_route_has_no_mutation_provider_contract(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "BillingArtifactsTenant")
        self.assertEqual("billing_reporting", route["response_profile"])
        self.assertNotIn("mutation_contract", route)
        self.assertNotIn("provider_instrument_contract", route)

    def test_no_target_tenant_classifications_are_closed_and_explicit(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        policy = document["tenant_generation_policy"]
        classifications = policy["no_target_tenant_classifications"]
        self.assertEqual(
            set(self.validator.REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS),
            set(classifications),
        )
        for classification, expected in self.validator.REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS.items():
            with self.subTest(classification=classification):
                entry = classifications[classification]
                self.assertFalse(entry["target_tenant_generation"])
                self.assertEqual(
                    expected["generation_behavior"], entry["generation_behavior"]
                )
                self.assertTrue(entry["contract_justification"])
                self.assertEqual(
                    expected["target_tenant_generation_advance_behavior"],
                    entry["target_tenant_generation_advance_behavior"],
                )

    def test_class_required_authority_is_metadata_not_universal_route_checks(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        classifications = document["tenant_generation_policy"][
            "no_target_tenant_classifications"
        ]
        for classification, expected in self.validator.REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS.items():
            self.assertEqual(
                expected["required_authority"],
                set(classifications[classification]["required_authority"]),
            )
        join = route_for(document, "game-session-service", "JOIN")
        self.assertNotIn("membership", join["required_live_checks"])
        self.assertNotIn("membership_generation", join["required_live_checks"])
        account_route = route_for(document, "account-service", "ExportAccount")
        self.assertNotEqual(
            set(account_route["required_live_checks"]),
            set(classifications["account_scoped"]["required_authority"]),
        )
        pending_route = route_for(document, "account-service", "GET /accounts/{accountId}/deletion")
        self.assertEqual(
            set(pending_route["required_live_checks"]),
            set(classifications["pending_deletion_scoped"]["required_authority"]),
        )
        errors = validate_document(self.validator, document)
        self.assertEqual([], errors)

        document["tenant_generation_policy"]["no_target_tenant_classifications"][
            "public"
        ]["required_authority"].append("membership")
        errors = validate_document(self.validator, document)
        self.assertIn(
            "tenant_generation_policy.no_target_tenant_classifications.public "
            "has the wrong required authority metadata",
            errors,
        )

    def test_target_generation_denial_proof_is_scoped_to_tenant_authority(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        proof = document["tenant_generation_policy"]["negative_proof"]
        self.assertEqual(
            self.validator.REQUIRED_TENANT_AUTHORITY_CLASSIFICATIONS,
            set(proof["tenant_authority_classifications"]),
        )
        self.assertIn(
            "non_allowlisted_route_denied_after_target_tenant_generation_advance",
            proof["required"],
        )

        proof["tenant_authority_classifications"].append("account_scoped")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "tenant_authority_classifications must be exactly" in error
                for error in errors
            )
        )

    def test_no_target_routes_do_not_require_target_generation_fence(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route_keys = (
            ("account-service", "AuthLogout"),
            ("account-service", "DELETE /tenants/{tenantId}/memberships/me"),
        )
        for service, route_name in route_keys:
            with self.subTest(route=route_name):
                route = route_for(document, service, route_name)
                self.assertNotIn(
                    "target_tenant_generation", route.get("required_live_checks", [])
                )
                self.assertEqual(
                    "remains_valid",
                    document["tenant_generation_policy"][
                        "no_target_tenant_classifications"
                    ][route["classification"]][
                        "target_tenant_generation_advance_behavior"
                    ],
                )

        issue_connect_token = route_for(
            document, "account-service", "IssueConnectToken"
        )
        self.assertIn(
            "target_tenant_generation", issue_connect_token["required_live_checks"]
        )
        self.assertIn(
            ("account-service", "IssueConnectToken"),
            self.validator.ROUTES_WITH_EXPLICIT_TARGET_TENANT_AUTHORITY,
        )
        player_bootstrap_policy = document["tenant_generation_policy"][
            "no_target_tenant_classifications"
        ]["player_bootstrap_tenant"]
        self.assertEqual(
            "route_declared",
            player_bootstrap_policy["target_tenant_generation_advance_behavior"],
        )

        report_route = route_for(
            document, "logging-admin-service", "POST /reports"
        )
        report_route["required_live_checks"].append("target_tenant_generation")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "logging-admin-service POST /reports must not require "
                "tenant-generation checks for no-target classification "
                "player_bootstrap_tenant" in error
                for error in errors
            )
        )

    def test_selected_tenant_generation_exception_matches_route_classification(self):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(baseline, "account-service", "IssueConnectToken")

        route["classification"] = "gameplay_admission"
        errors = validate_document(self.validator, baseline)
        self.assertTrue(
            any(
                "explicit target-tenant authority must use classification "
                "player_bootstrap_tenant" in error
                for error in errors
            )
        )

        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "IssueConnectToken")
        route["tenant_authority_generation_applies"] = False
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "explicit target-tenant authority must set "
                "tenant_authority_generation_applies=true" in error
                for error in errors
            )
        )

    def test_cross_tenant_safe_route_rejects_target_generation_checks(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("classification") == "cross_tenant_support_safe"
        )
        route["required_live_checks"].append("target_tenant_generation")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "must not require target membership or tenant generation checks"
                in error
                and "target_tenant_generation" in error
                for error in errors
            )
        )

    def test_multi_profile_route_requires_shared_type_and_issuer(self):
        for field in ("token_type", "token_issuer"):
            with self.subTest(field=field):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = configure_multi_profile_route(document)
                route[field] = "mismatch"
                errors = validate_document(self.validator, document)
                self.assertTrue(
                    any(
                        "multi-profile token predicates must match the shared token_type/token_issuer"
                        in error
                        for error in errors
                    )
                )

    def test_multi_profile_route_requires_token_type_and_issuer(self):
        for field in ("token_type", "token_issuer"):
            with self.subTest(field=field):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = configure_multi_profile_route(document)
                route.pop(field)
                errors = validate_document(self.validator, document)
                self.assertTrue(
                    any(
                        "multi-profile routes must declare token_type/token_issuer"
                        in error
                        for error in errors
                    )
                )

    def test_multi_profile_route_rejects_scalar_audience(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = configure_multi_profile_route(document)
        route["token_audience"] = "control-ui"
        route.pop("token_type")
        route.pop("token_issuer")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any("must not declare scalar token_audience" in error for error in errors)
        )

    def test_differing_multi_profile_predicates_require_exact_maps(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "AuthLogout")
        route.pop("accepted_token_profile_types")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "differing multi-profile predicates require accepted_token_profile_types"
                in error
                for error in errors
            )
        )

    def test_caller_policy_shape_errors_are_not_duplicated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "account-service", "GetTenantEntitlementsForRuntime"
        )
        policy = next(
            policy
            for policy in route["caller_policies"]
            if policy.get("caller") == "game-session-service"
        )
        policy.pop("accepted_token_profiles")
        errors = validate_document(self.validator, document)
        self.assertEqual(
            1,
            errors.count(
                "account-service GetTenantEntitlementsForRuntime caller_policies[0] accepted_token_profiles must be a list of strings"
            ),
        )
        self.assertFalse(
            any(
                "GetTenantEntitlementsForRuntime caller_policies[0] must declare token_type/token_issuer/token_audience as none"
                in error
                for error in errors
            )
        )

    def test_entitlement_route_cardinality_error_is_shared_once(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = [
            route
            for route in document["routes"]
            if not (
                route.get("service") == "account-service"
                and route.get("route") == "GetTenantEntitlementsForRuntime"
            )
        ]
        errors = []
        cardinality_errors = set()
        self.validator.validate_entitlement_contract(
            document, routes, errors, cardinality_errors
        )
        self.validator.validate_delegated_entitlements(
            routes, errors, cardinality_errors=cardinality_errors
        )
        self.assertEqual(
            [
                "matrix must contain exactly one account-service GetTenantEntitlementsForRuntime route"
            ],
            errors,
        )

    def test_profile_route_zero_match_cardinality_error_is_shared_once(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = [
            route
            for route in document["routes"]
            if (route.get("service"), route.get("route"))
            not in self.validator.PROFILE_ROUTES
        ]
        errors = []
        cardinality_errors = set()
        self.validator.validate_profile_authority_routes(
            routes, errors, cardinality_errors=cardinality_errors
        )
        self.validator.validate_profile_authority_routes(
            routes, errors, cardinality_errors=cardinality_errors
        )
        self.assertEqual(
            [
                "matrix must contain exactly one account-service GET /tenants/{tenantId}/profiles/{accountId} route",
                "matrix must contain exactly one account-service PUT /tenants/{tenantId}/profiles/{accountId} route",
            ],
            errors,
        )

    def test_missing_join_route_error_is_shared_once(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = [
            route
            for route in document["routes"]
            if not (
                route.get("service") == "game-session-service"
                and route.get("route") == "JOIN"
            )
        ]
        errors = []
        cardinality_errors = set()
        self.validator.validate_join_routes(
            routes, errors, cardinality_errors=cardinality_errors
        )
        self.validator.validate_join_routes(
            routes, errors, cardinality_errors=cardinality_errors
        )
        self.assertEqual(
            ["matrix must contain exactly one game-session-service JOIN route"],
            [
                error
                for error in errors
                if "exactly one game-session-service JOIN" in error
            ],
        )

    def test_privileged_control_cardinality_error_uses_shared_set(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = [
            route
            for route in document["routes"]
            if not (
                route.get("service") == "account-service"
                and route.get("route") == "EnterPrivilegedControlWindow"
            )
        ]
        errors = []
        cardinality_errors = set()
        self.validator.validate_elevation_bootstrap(
            document, routes, errors, cardinality_errors
        )
        self.validator.validate_elevation_bootstrap(
            document, routes, errors, cardinality_errors
        )
        self.assertEqual(
            [
                (
                    "matrix must contain exactly one account-service "
                    "EnterPrivilegedControlWindow route"
                )
            ],
            errors,
        )

    def test_caller_policy_method_policy_error_is_not_duplicated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "account-service", "GetTenantEntitlementsForRuntime"
        )
        policy = next(
            policy
            for policy in route["caller_policies"]
            if policy.get("caller") == "game-session-service"
        )
        policy["method_policy"] = "all_methods"
        errors = validate_document(self.validator, document)
        self.assertEqual(
            1,
            errors.count(
                "account-service GetTenantEntitlementsForRuntime caller_policies[0] must declare method_policy exact_declared_route"
            ),
        )

    def test_known_drift_must_be_a_non_empty_list_of_strings(self):
        for malformed in ("scalar_drift", [], ["valid_drift", 7]):
            with self.subTest(malformed=malformed):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = route_for(document, "account-service", "IssueConnectToken")
                route["implementation_status"]["known_drift"] = malformed
                errors = validate_document(self.validator, document)
                self.assertTrue(
                    any(
                        error.endswith(
                            "implementation_status.known_drift must be a non-empty list of strings"
                        )
                        for error in errors
                    )
                )

    def test_route_live_checks_must_be_a_list(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "WORLDS_PUBLIC")
        route["required_live_checks"] = "public_production_visibility"
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                error.startswith("matrix.routes[")
                and error.endswith("required_live_checks must be a list of strings")
                for error in errors
            )
        )

    def test_route_live_check_entries_must_be_strings(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "WORLDS_PUBLIC")
        route["required_live_checks"] = ["public_production_visibility", 7]
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                error.startswith("matrix.routes[")
                and error.endswith("required_live_checks must be a list of strings")
                for error in errors
            )
        )

    def test_malformed_route_live_checks_are_reported_once_across_validators(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "POST /sessions")
        route["required_live_checks"] = "not-a-list"
        errors = validate_document(self.validator, document)
        structural_errors = [
            error
            for error in errors
            if error.endswith("required_live_checks must be a list of strings")
        ]
        self.assertEqual(1, len(structural_errors))
        self.assertTrue(
            any(
                "operator route must require live check current_operator_authorization"
                in error
                for error in errors
            )
        )

    def test_ws_game_live_checks_are_required(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = websocket_route(document, "first_party_web")
        route["required_live_checks"].remove("replay_admission_fence_match")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "/ws/game/** is missing required live checks" in error
                for error in errors
            )
        )

    def test_ws_game_policy_pressure_outcome_is_required(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = websocket_route(document, "first_party_web")
        route["handshake_error_classes"]["any_of"].remove("POLICY_PRESSURE")
        errors = validate_document(self.validator, document)
        self.assertTrue(any("POLICY_PRESSURE" in error for error in errors))

    def test_trusted_tcp_proxy_route_is_required(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = websocket_route(document, "trusted_tcp_proxy")
        route["applicability"]["all_of"][0]["connection_mode"] = (
            "missing_trusted_proxy"
        )
        errors = validate_document(self.validator, document)
        self.assertTrue(any("trusted_tcp_proxy" in error for error in errors))

    def test_conflicting_ws_game_connection_modes_are_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = websocket_route(document, "trusted_tcp_proxy")
        route["applicability"]["all_of"].append(
            {"connection_mode": "first_party_web"}
        )
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "/ws/game/** has conflicting applicability values for connection_mode"
                in error
                for error in errors
            )
        )

    def test_applicability_value_requires_direct_and_all_of_values_to_agree(self):
        route = {
            "applicability": {
                "connection_mode": "trusted_tcp_proxy",
                "all_of": [{"connection_mode": "trusted_tcp_proxy"}],
            }
        }
        errors = []
        self.assertEqual(
            "trusted_tcp_proxy",
            self.validator.applicability_value(
                route, "connection_mode", "/ws/game/**", errors
            ),
        )
        self.assertEqual([], errors)

    def test_applicability_value_preserves_missing_value_behavior(self):
        route = {"applicability": {"all_of": [{"other_key": "value"}]}}
        errors = []
        self.assertIsNone(
            self.validator.applicability_value(
                route, "connection_mode", "/ws/game/**", errors
            )
        )
        self.assertEqual([], errors)

    def test_applicability_value_rejects_direct_and_all_of_conflicts(self):
        route = {
            "applicability": {
                "connection_mode": "trusted_tcp_proxy",
                "all_of": [{"connection_mode": "first_party_web"}],
            }
        }
        errors = []
        self.assertIsNone(
            self.validator.applicability_value(
                route, "connection_mode", "/ws/game/**", errors
            )
        )
        self.assertEqual(
            [
                (
                    "/ws/game/** has conflicting applicability values for "
                    "connection_mode: ['trusted_tcp_proxy', 'first_party_web']"
                )
            ],
            errors,
        )

    def test_first_party_ws_and_revoke_operations_are_mutually_exclusive(self):
        for connection_mode, operation, expected_label in (
            (
                "first_party_web",
                "connect_token_cookie_revoke",
                "/ws/game/** first_party_web",
            ),
            (
                "first_party_web",
                "websocket_upgrade",
                "POST /ws/game/connect-token/revoke",
            ),
        ):
            with self.subTest(expected_label=expected_label):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                if expected_label.startswith("/ws/game"):
                    route = websocket_route(document, connection_mode)
                else:
                    route = route_for(
                        document,
                        "spring-cloud-gateway",
                        "POST /ws/game/connect-token/revoke",
                    )
                operation_predicate = next(
                    predicate
                    for predicate in route["applicability"]["all_of"]
                    if predicate.get("operation") is not None
                )
                operation_predicate["operation"] = operation
                errors = validate_document(self.validator, document)
                self.assertTrue(
                    any(
                        f"{expected_label} must declare applicability operation"
                        in error
                        for error in errors
                    )
                )

    def test_trusted_tcp_proxy_live_checks_are_required(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = websocket_route(document, "trusted_tcp_proxy")
        route["required_live_checks"] = []
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "/ws/game/** trusted_tcp_proxy is missing required live checks" in error
                for error in errors
            )
        )

    def test_connect_token_revoke_checks_are_required(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document,
            "spring-cloud-gateway",
            "POST /ws/game/connect-token/revoke",
        )
        route["required_live_checks"] = ["browser_origin"]
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "POST /ws/game/connect-token/revoke is missing" in error
                for error in errors
            )
        )

    def test_connect_token_revoke_declares_tenant_and_membership_generation_scope(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "spring-cloud-gateway", "POST /ws/game/connect-token/revoke"
        )
        self.assertEqual(
            self.validator.GAMEPLAY_CONNECT_ISSUED_TOKEN_STATE,
            route["issued_token_state"],
        )
        expected = {
            "issuer_authority_generation_applies": False,
            "account_authority_generation_applies": False,
            "tenant_billing_authority_generation_applies": False,
            "membership_authority_generation_applies": False,
        }
        self.assertEqual(
            expected,
            self.validator.REQUIRED_REVOKE_GENERATION_APPLICABILITY,
        )
        for field in expected:
            self.assertFalse(route[field])

        route["membership_authority_generation_applies"] = True
        errors = []
        self.validator.validate_connect_token_revoke_generation_applicability(
            document["routes"], errors
        )
        label = self.validator.route_label(route)
        self.assertIn(
            f"{label} must explicitly set membership_authority_generation_applies=false",
            errors,
        )

    def test_gameplay_connect_bounded_registry_and_generation_exception_is_required(
        self,
    ):
        mutations = (
            (
                "issued_token_state",
                "none",
                "issued-token-state exception",
            ),
            (
                "issuer_authority_generation_applies",
                True,
                "issuer_authority_generation_applies",
            ),
            (
                "account_authority_generation_applies",
                True,
                "account_authority_generation_applies",
            ),
        )
        for field, value, expected in mutations:
            with self.subTest(expected=expected):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = next(
                    route
                    for route in document["routes"]
                    if route.get("service") == "spring-cloud-gateway"
                    and route.get("route") == "/ws/game/**"
                    and {"connection_mode": "first_party_web"}
                    in route.get("applicability", {}).get("all_of", [])
                )
                route[field] = value
                errors = []
                self.validator.validate_ws_game_routes(document["routes"], errors)
                self.assertTrue(any(expected in error for error in errors))

    def test_malformed_ws_game_route_counts_do_not_suppress_revoke_checks(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        proxy_route = websocket_route(document, "trusted_tcp_proxy")
        proxy_route["applicability"]["all_of"][0]["connection_mode"] = (
            "malformed_route"
        )
        revoke_route = route_for(
            document,
            "spring-cloud-gateway",
            "POST /ws/game/connect-token/revoke",
        )
        revoke_route["required_live_checks"] = ["browser_origin"]
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "exactly one first_party_web and one trusted_tcp_proxy" in error
                for error in errors
            )
        )
        self.assertTrue(
            any(
                "POST /ws/game/connect-token/revoke is missing" in error
                for error in errors
            )
        )

    def test_issue_connect_token_replay_fence_checks_are_required(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "IssueConnectToken")
        route["required_live_checks"].remove("replay_admission_fence_match")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "IssueConnectToken is missing required live checks" in error
                for error in errors
            )
        )

    def test_issue_connect_token_requires_selected_tenant_generation(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "IssueConnectToken")
        self.assertTrue(route["tenant_authority_generation_applies"])
        self.assertTrue(route["membership_authority_generation_applies"])
        self.assertTrue(route["membership_version_applies"])
        self.assertTrue(
            self.validator.REQUIRED_ISSUE_CONNECT_TOKEN_CHECKS.issubset(
                set(route["required_live_checks"])
            )
        )

        route["required_live_checks"].remove("target_tenant_generation")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "IssueConnectToken is missing required live checks" in error
                for error in errors
            )
        )

    def test_malformed_classification_vocabulary_is_reported(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document["classifications"][0] = {"invalid": "value"}
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "classifications must be a list of strings" in error for error in errors
            )
        )

    def test_null_classification_vocabulary_is_reported_without_crashing(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document["classifications"] = None
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "classifications must be a list of strings" in error for error in errors
            )
        )

    def test_route_classification_lists_and_mappings_are_reported(self):
        for malformed in (["public"], {"name": "public"}):
            with self.subTest(malformed=malformed):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = route_for(document, "game-session-service", "WORLDS_PUBLIC")
                route["classification"] = malformed
                errors = validate_document(self.validator, document)
                self.assertEqual(
                    1,
                    sum(
                        error.endswith("classification must be a string")
                        for error in errors
                    ),
                )
                self.assertFalse(
                    any("uses unknown classification" in error for error in errors)
                )

    def test_unknown_route_classification_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "WORLDS_PUBLIC")
        route["classification"] = "not_a_real_classification"
        errors = validate_document(self.validator, document)
        self.assertTrue(any("uses unknown classification" in error for error in errors))

    def test_duplicate_route_requires_applicability(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        duplicate_route = {
            "service": "duplicate-service",
            "route": "GET /duplicate",
            "classification": "public",
        }
        document["routes"].extend(
            [copy.deepcopy(duplicate_route), copy.deepcopy(duplicate_route)]
        )
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "duplicate route entries require explicit applicability" in error
                for error in errors
            )
        )

    def test_duplicate_route_rejects_non_json_applicability(self):
        non_json_value = datetime.date(2026, 7, 31)
        route = {
            "service": "duplicate-service",
            "route": "GET /duplicate",
            "classification": "public",
            "applicability": {"all_of": [{"effective_date": non_json_value}]},
        }
        errors = []
        self.validator.validate_route_variants(
            [route, copy.deepcopy(route)], {"public"}, errors
        )
        self.assertTrue(
            any(
                "duplicate route applicability must be JSON-serializable" in error
                and "date" in error
                for error in errors
            )
        )

    def test_duplicate_route_reports_successful_duplicates_when_another_variant_fails(self):
        valid_route = {
            "service": "duplicate-service",
            "route": "GET /duplicate",
            "classification": "public",
            "applicability": {"all_of": [{"effective_date": "2026-07-31"}]},
        }
        invalid_route = {
            **valid_route,
            "applicability": {
                "all_of": [{"effective_date": datetime.date(2026, 7, 31)}]
            },
        }
        errors = []
        self.validator.validate_route_variants(
            [valid_route, copy.deepcopy(valid_route), invalid_route],
            {"public"},
            errors,
        )
        self.assertIn(
            "duplicate route applicability: duplicate-service|GET /duplicate",
            errors,
        )
        self.assertTrue(
            any("duplicate route applicability must be JSON-serializable" in error for error in errors)
        )

    def test_route_identity_components_are_trimmed_before_comparison(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        requirement = document["role_assurance"][
            "privileged_control_when_global_role"
        ]["requirements"]["platformAdmin"]
        requirement["applies_to"]["route_identities"] = [
            f" {identity.replace('/', ' / ', 1)} "
            for identity in requirement["applies_to"]["route_identities"]
        ]
        errors = []
        self.validator.validate_role_assurance(document, errors)
        self.assertFalse(
            any("platformAdmin.applies_to.route_identities must equal" in error for error in errors)
        )
        self.assertEqual(
            "service|GET /route",
            self.validator.route_key(
                {"service": " service ", "route": " GET /route "}
            ),
        )
        self.assertEqual(
            "service/GET /route",
            self.validator.route_identity(" service / GET /route ", "identity", errors),
        )
        self.assertEqual([], errors)

    def test_role_assurance_route_identity_trims_route_components(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        expected_identity = next(
            iter(self.validator.PLATFORM_ADMIN_ROLE_ASSURANCE_ROUTE_IDENTITIES)
        )
        service, route_name = expected_identity.split("/", 1)
        route = route_for(document, service, route_name)
        route["service"] = f" {service} "
        route["route"] = f" {route_name} "

        errors = []
        self.validator.validate_role_assurance_route_identities(
            document["routes"], errors
        )

        self.assertEqual([], errors)

    def test_platform_admin_route_identities_compare_as_an_order_independent_set(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        requirement = document["role_assurance"][
            "privileged_control_when_global_role"
        ]["requirements"]["platformAdmin"]
        existing_identity = next(
            iter(self.validator.PLATFORM_ADMIN_ROLE_ASSURANCE_ROUTE_IDENTITIES)
        )
        expected_identities = {
            existing_identity,
            "test-service/GET /second-route",
        }
        second_identity = next(iter(expected_identities - {existing_identity}))
        second_route = copy.deepcopy(
            route_for(document, *existing_identity.split("/", 1))
        )
        second_route["service"], second_route["route"] = second_identity.split(
            "/", 1
        )
        document["routes"].append(second_route)
        requirement["applies_to"]["route_identities"] = sorted(
            expected_identities, reverse=True
        )

        errors = []
        with patch.object(
            self.validator,
            "PLATFORM_ADMIN_ROLE_ASSURANCE_ROUTE_IDENTITIES",
            expected_identities,
        ):
            self.validator.validate_role_assurance(document, errors)
            self.validator.validate_role_assurance_route_identities(
                document["routes"], errors
            )

        self.assertFalse(
            any("platformAdmin.applies_to.route_identities must equal" in error for error in errors)
        )
        self.assertEqual([], errors)

    def test_join_routes_require_admission_pointer_error(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "JOIN")
        route.pop("canonical_errors")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "must declare ADMISSION_POINTER_UNAVAILABLE" in error
                for error in errors
            )
        )

    def test_delegated_entitlement_checks_require_account_cutoffs(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "account-service", "GetTenantEntitlementsForRuntime"
        )
        policy = next(
            policy
            for policy in route["caller_policies"]
            if policy.get("caller") == "game-session-service"
        )
        policy["required_live_checks"] = [
            "current_token_generation",
            "tenant_generation",
            "membership_generation",
            "membership",
            "runtime_entitlements",
            "conditional_realm_access_grant",
            "grant_version",
        ]
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "GetTenantEntitlementsForRuntime game-session policy is missing"
                in error
                for error in errors
            )
        )

    def test_caller_policies_null_and_mapping_values_are_reported(self):
        for malformed in (None, {"caller": "game-session-service"}):
            with self.subTest(malformed=malformed):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = route_for(
                    document, "account-service", "GetTenantEntitlementsForRuntime"
                )
                route["caller_policies"] = malformed
                errors = validate_document(self.validator, document)
                self.assertIn(
                    "GetTenantEntitlementsForRuntime caller_policies must be a list",
                    errors,
                )

    def test_game_session_caller_policy_count_remains_exactly_one(self):
        for policy_count in (0, 2):
            with self.subTest(policy_count=policy_count):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = route_for(
                    document, "account-service", "GetTenantEntitlementsForRuntime"
                )
                game_policy = next(
                    policy
                    for policy in route["caller_policies"]
                    if policy.get("caller") == "game-session-service"
                )
                policies = [] if policy_count == 0 else [game_policy, dict(game_policy)]
                route["caller_policies"] = policies
                errors = validate_document(self.validator, document)
                self.assertIn(
                    "GetTenantEntitlementsForRuntime must contain exactly one game-session-service caller policy",
                    errors,
                )

    def test_join_route_errors_are_emitted_in_sorted_order(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        join_routes = self.validator.JOIN_ROUTES_REQUIRING_POINTER_ERROR
        self.assertTrue(join_routes)
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

    def test_role_assurance_requires_one_applies_to_shape(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        requirement = document["role_assurance"]["privileged_control_when_global_role"][
            "requirements"
        ]["support"]
        requirement["allowed_classifications"] = requirement["applies_to"][
            "route_classifications"
        ]
        del requirement["applies_to"]
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any("support.applies_to must be a mapping" in error for error in errors)
        )
        self.assertTrue(any("one applies_to shape" in error for error in errors))

    def test_platform_admin_assurance_has_exact_issuance_route_without_scope_widening(
        self,
    ):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        requirement = document["role_assurance"][
            "privileged_control_when_global_role"
        ]["requirements"]["platformAdmin"]
        self.assertEqual(
            ["account-service/IssueHumanOperatorAuthorizationReference"],
            requirement["applies_to"]["route_identities"],
        )
        self.assertNotIn(
            "internal_workload",
            requirement["applies_to"]["route_classifications"],
        )
        self.assertNotIn(
            "internal_workload",
            document["role_assurance"]["privileged_control_when_global_role"][
                "requirements"
            ]["support"]["applies_to"]["route_classifications"],
        )
        self.assertNotIn(
            "internal_workload",
            document["role_assurance"]["privileged_control_when_global_role"][
                "requirements"
            ]["billingAdmin"]["applies_to"]["route_classifications"],
        )

        requirement["applies_to"].pop("route_identities")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "platformAdmin.applies_to.route_identities must equal" in error
                for error in errors
            )
        )

    def test_support_and_billing_admin_cannot_use_route_identities(self):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        for role in ("support", "billingAdmin"):
            with self.subTest(role=role):
                document = copy.deepcopy(baseline)
                document_requirements = document["role_assurance"][
                    "privileged_control_when_global_role"
                ]["requirements"]
                document_requirements[role]["applies_to"]["route_identities"] = [
                    "account-service/IssueHumanOperatorAuthorizationReference"
                ]
                errors = []
                self.validator.validate_role_assurance(document, errors)
                self.assertIn(
                    "role_assurance.privileged_control_when_global_role.requirements."
                    f"{role}.applies_to.route_identities is only allowed for platformAdmin",
                    errors,
                )

    def test_route_identity_shape_is_validated_before_platform_admin_equality(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        requirement = document["role_assurance"][
            "privileged_control_when_global_role"
        ]["requirements"]["platformAdmin"]
        requirement["applies_to"]["route_identities"] = "not-a-list"
        errors = []
        self.validator.validate_role_assurance(document, errors)
        self.assertIn(
            "role_assurance.privileged_control_when_global_role.requirements."
            "platformAdmin.applies_to.route_identities must be a list of strings",
            errors,
        )
        self.assertNotIn(
            "platformAdmin.applies_to.route_identities must equal",
            "\n".join(errors),
        )

    def test_role_assurance_scope_must_be_non_empty_and_declared(self):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        for classifications, expected_fragment in (
            ([], "must be a non-empty list of strings"),
            (["not_a_classification"], "outside the classification vocabulary"),
        ):
            with self.subTest(classifications=classifications):
                document = copy.deepcopy(baseline)
                requirement = document["role_assurance"][
                    "privileged_control_when_global_role"
                ]["requirements"]["support"]
                requirement["applies_to"]["route_classifications"] = classifications
                errors = validate_document(self.validator, document)
                self.assertTrue(any(expected_fragment in error for error in errors))

    def test_role_assurance_rejects_unexpected_role_keys(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        requirements = document["role_assurance"][
            "privileged_control_when_global_role"
        ]["requirements"]
        requirements["suport"] = requirements.pop("support")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any("contains unexpected role keys" in error for error in errors)
        )

    def test_role_assurance_requires_canonical_predicate(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document["role_assurance"].pop("privileged_control_when_global_role")
        errors = validate_document(self.validator, document)
        self.assertIn(
            "role_assurance.privileged_control_when_global_role must be a mapping",
            errors,
        )
        self.assertTrue(
            any(
                "role_assurance must reference a declared predicate" in error
                for error in errors
            )
        )

    def test_role_assurance_rejects_legacy_vocabulary_shape(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document["role_assurance"]["vocabulary"] = {
            "privileged_control_when_global_role": {}
        }
        errors = []
        self.validator.validate_role_assurance(document, errors)
        self.assertIn(
            "role_assurance must use one canonical predicate mapping; vocabulary is not supported",
            errors,
        )

    def test_legacy_target_only_route_declaration_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "POST /auth/bootstrap/join")
        route.pop("route_status")
        route.setdefault("implementation_status", {})["target_only"] = True
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "route_status instead of implementation_status.target_only" in error
                for error in errors
            )
        )

    def test_unknown_route_status_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "BillingArtifactsTenant")
        route["route_status"] = "target_only"
        errors = validate_document(self.validator, document)
        self.assertTrue(any("route_status must be one of" in error for error in errors))

    def test_private_receiver_requires_exact_token_and_method_predicates(self):
        baseline = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        mutations = (
            (
                "token_audience",
                "internal",
                (
                    "caller_policies[0] token predicates must exactly match profile "
                    "'game-session-account-delegation'"
                ),
            ),
            (
                "token_issuer",
                "untrusted-service",
                (
                    "caller_policies[0] token predicates must exactly match profile "
                    "'game-session-account-delegation'"
                ),
            ),
            (
                "mtls_identity",
                "game-session-service",
                "caller_policies[0].mtls_identity must be a concrete spiffe:// identity",
            ),
            (
                "method_policy",
                "all_methods",
                "caller_policies[0] must declare method_policy exact_declared_route",
            ),
        )
        for field, value, expected_suffix in mutations:
            with self.subTest(field=field):
                document = copy.deepcopy(baseline)
                route = route_for(
                    document, "account-service", "RefreshGameplayServiceToken"
                )
                route["caller_policies"][0][field] = value
                errors = validate_document(self.validator, document)
                self.assertIn(
                    f"account-service RefreshGameplayServiceToken {expected_suffix}",
                    errors,
                )

    def test_tenant_generation_exception_requires_class_checks(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "GetTenantEntitlementsTenant")
        route["required_live_checks"].remove("membership_generation")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "GetTenantEntitlementsTenant is missing route-class authority checks"
                in error
                for error in errors
            )
        )

    def test_tenant_generation_exception_entries_must_be_mappings(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document["tenant_generation_policy"]["exception_allowlist"][
            "billing_safe_tenant"
        ] = "malformed"
        errors = validate_document(self.validator, document)
        self.assertIn(
            "tenant_generation_policy.exception_allowlist.billing_safe_tenant must be a mapping",
            errors,
        )

    def test_invalid_tenant_generation_policy_does_not_skip_route_checks(self):
        for malformed_policy in (None, {"applies_by_default": False}):
            with self.subTest(policy=malformed_policy):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                document["tenant_generation_policy"] = malformed_policy
                route = next(
                    route
                    for route in document["routes"]
                    if route.get("classification") == "cross_tenant_support_safe"
                )
                route["required_live_checks"] = []
                errors = validate_document(self.validator, document)
                self.assertIn(
                    "tenant_generation_policy must enable applies_by_default",
                    errors,
                )
                self.assertTrue(
                    any(
                        "missing route-class authority checks" in error
                        for error in errors
                    )
                )

    def test_cross_tenant_generation_exceptions_reference_role_assurance_policy(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        exception = document["tenant_generation_policy"]["exception_allowlist"][
            "cross_tenant_support_safe"
        ]
        exception.pop("role_assurance_policy")
        errors = validate_document(self.validator, document)
        self.assertIn(
            "tenant_generation_policy exception cross_tenant_support_safe "
            "must reference privileged_control_when_global_role",
            errors,
        )

    def test_cross_tenant_target_membership_checks_are_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "account-service", "GetSubscriptionCrossTenantSupportSafe"
        )
        route["required_live_checks"].append("membership")
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any("must not require target membership" in error for error in errors)
        )

    def test_entitlement_contract_is_exactly_tenant_bound(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document["entitlement_contract"]["cross_tenant_inheritance"] = "allowed"
        errors = validate_document(self.validator, document)
        self.assertIn(
            "entitlement_contract.cross_tenant_inheritance must be forbidden", errors
        )

    def test_entitlement_contract_requires_exactly_one_matching_route(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "account-service", "GetTenantEntitlementsForRuntime"
        )
        expected = "matrix must contain exactly one account-service GetTenantEntitlementsForRuntime route"
        for routes in ([], [route, dict(route)]):
            with self.subTest(route_count=len(routes)):
                errors = []
                self.validator.validate_entitlement_contract(document, routes, errors)
                self.assertEqual([expected], errors)


if __name__ == "__main__":
    unittest.main()
