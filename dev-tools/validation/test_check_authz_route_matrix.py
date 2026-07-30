#!/usr/bin/env python3
"""Regression tests for authorization route matrix validation."""

from __future__ import annotations

import copy
import importlib.util
import tempfile
import unittest
from collections import defaultdict
from pathlib import Path

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


def replace_or_fail(text: str, old: str, new: str) -> str:
    occurrences = text.count(old)
    if occurrences != 1:
        raise AssertionError(
            f"expected exactly one occurrence of {old!r}, found {occurrences}"
        )
    return text.replace(old, new, 1)


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


def validate_document(validator, document):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "matrix.yaml"
        path.write_text(
            validator.yaml.safe_dump(document, sort_keys=False),
            encoding="utf-8",
        )
        return validator.validate(path)


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
                        "current_operator_roles",
                    }.issubset(tenant_branch["required_live_checks"])
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

    def test_http_session_mutations_are_gated_without_losing_current_route_status(self):
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
            self.assertEqual("current_openapi_operator_surface", route["route_status"])

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
                "current_operator_roles",
            },
            "platformAdmin_global": {
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

        for branch_name, missing_check in (
            ("tenant_role", "tenant_generation"),
            ("platformAdmin_global", "target_tenant_generation"),
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
                self.assertTrue(
                    any(
                        missing_check in error
                        and (
                            "tenant-role branch must require" in error
                            or "operator route must require" in error
                        )
                        for error in errors
                    )
                )

    def test_admission_pointer_mutation_requires_expected_version_check(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "logging-admin-service", "POST /admission-pointers")
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
                drifted_route_index = document["routes"].index(drifted_route)
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
                checks.append(missing_check)

    def test_profile_routes_require_generation_checks(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = grouped_routes(document, "account-service")
        for route_name in ("GetProfile", "UpdateProfile"):
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

        routes["GetProfile"][0]["required_live_checks"].remove("tenant_generation")
        errors = []
        self.validator.validate_profile_authority_routes(document["routes"], errors)
        self.assertIn(
            "account-service GetProfile must require live check tenant_generation",
            errors,
        )

    def test_profile_route_auth_and_method_policy_are_validated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "GetProfile")
        route["auth_path"] = "wrong_auth_path"
        route["method_policy"] = "all_methods"
        errors = []
        self.validator.validate_profile_authority_routes(document["routes"], errors)
        self.assertIn(
            "account-service GetProfile must declare auth_path control_ui_plus_current_tenant_role",
            errors,
        )
        self.assertIn(
            "account-service GetProfile must declare method_policy exact_declared_route",
            errors,
        )

    def test_refresh_roles_uses_redeemed_operator_authority_and_idempotency(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = grouped_routes(document, "game-session-service")
        grpc = routes["RefreshRoles"][0]
        self.assertEqual(
            "exact_mtls_workload_plus_account_operator_authorization_reference",
            grpc["auth_path"],
        )
        self.assertEqual(
            "required_and_redeemed_with_account",
            grpc["operator_authorization_reference"],
        )
        self.assertIn("mutation_digest", grpc["required_fields"])
        self.assertIn("IDEMPOTENCY_CONFLICT", grpc["canonical_errors"]["any_of"])
        http = routes["POST /sessions/{sessionId}/refresh-roles"][0]
        self.assertEqual(
            "account_issued_bounded_reference", http["operator_authorization_reference"]
        )
        self.assertIn("mutation_digest", http["required_fields"])
        self.assertIn("IDEMPOTENCY_CONFLICT", http["canonical_errors"]["any_of"])

        grpc.pop("operator_authorization_reference")
        errors = []
        self.validator.validate_refresh_roles_routes(document["routes"], errors)
        self.assertTrue(
            any("operator authorization redemption" in error for error in errors)
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
                route_index = document["routes"].index(route)
                route["required_fields"] = [{"invalid": "field"}]
                errors = validate_document(self.validator, document)
                self.assertEqual(
                    1,
                    errors.count(
                        f"routes[{route_index}] required_fields must be a list of strings"
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

    def test_privileged_operator_routes_require_live_global_role_and_assurance(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(
            document, "logging-admin-service", "POST /feature-flags/toggle"
        )
        route_index = document["routes"].index(route)
        platform_branch = next(
            branch
            for branch in route["operator_authorization_branches"]
            if branch["branch"] == "platformAdmin_global"
        )
        platform_branch["required_live_checks"].remove("current_global_role")
        errors = []
        self.validator.validate_generation_applicability(document["routes"], errors)
        self.assertIn(
            f"routes[{route_index}] logging-admin-service POST /feature-flags/toggle "
            "privileged operator route must require live check current_global_role",
            errors,
        )

        platform_branch["required_live_checks"].append("current_global_role")
        route.pop("role_assurance")
        errors = []
        self.validator.validate_generation_applicability(document["routes"], errors)
        self.assertIn(
            f"routes[{route_index}] logging-admin-service POST /feature-flags/toggle "
            "operator route must declare role_assurance privileged_control_when_global_role",
            errors,
        )

        owner_route = route_for(document, "game-session-service", "POST /sessions")
        owner_route_index = document["routes"].index(owner_route)
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

    def test_profile_routes_distinguish_self_only_subjects(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        for route_name in ("GetProfile", "UpdateProfile"):
            route = route_for(document, "account-service", route_name)
            self.assertEqual(
                "caller_account_id_for_self_only_roles", route["subject_binding"]
            )
            self.assertEqual(
                ["player", "moderator", "designer"], route["self_only_roles"]
            )
            self.assertEqual(
                "same_tenant_profile_for_tenantAdmin", route["target_subject_binding"]
            )
            self.assertEqual("forbidden", route["platform_admin_override"])

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

    def test_export_tenant_data_is_tenant_generation_bound(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "ExportTenantData")
        self.assertEqual("tenant_regular", route["classification"])
        self.assertTrue(route["tenant_billing_authority_generation_applies"])
        self.assertIn("tenant_generation", route["required_live_checks"])

    def test_play_rechecks_membership_generation(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "game-session-service", "PLAY")
        self.assertTrue(route["membership_authority_generation_applies"])
        self.assertIn("membership_generation", route["required_live_checks"])

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
        route = route_for(
            baseline, "account-service", "EnsurePublicProductionPlayerMembership"
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
                    "account-service",
                    "EnsurePublicProductionPlayerMembership",
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
            self.assertTrue(downstream["membership_authority_generation_applies"])
            self.assertIn("membership", downstream["required_live_checks"])
            self.assertIn("membership_generation", downstream["required_live_checks"])

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
        route["downstream_admission_contract"][
            "membership_authority_generation_applies"
        ] = False
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "/ws/game/** trusted_tcp_proxy downstream_admission_contract must apply membership authority generation"
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
        route_index = document["routes"].index(route)
        route["accepted_token_profiles"] = "control-ui"
        errors = validate_document(self.validator, document)
        self.assertEqual(
            1,
            errors.count(
                f"matrix.routes[{route_index}] accepted_token_profiles must be a list of strings"
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
        self.assertEqual(4, len(pending_routes))
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
            ("account-service", "IssueConnectToken"),
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
        issue_connect_token["required_live_checks"].append(
            "target_tenant_generation"
        )
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any("must not require tenant-generation checks" in error for error in errors)
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
        self.validator.validate_membership_policy(document, errors)
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
                route = route_for(document, "game-session-service", "PLAY")
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
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                "required_live_checks: [connect_token_single_use_consume, replay_protection_available, replay_admission_fence_match, connect_scope_match]",
                "required_live_checks: [connect_token_single_use_consume, replay_protection_available, connect_scope_match]",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(
            any(
                "/ws/game/** is missing required live checks" in error
                for error in errors
            )
        )

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
                "/ws/game/** has conflicting applicability values for connection_mode"
                in error
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
                        f"{expected_label} must declare applicability operation"
                        in error
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
            any(
                "/ws/game/** trusted_tcp_proxy is missing required live checks" in error
                for error in errors
            )
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
        for field in self.validator.REQUIRED_REVOKE_GENERATION_APPLICABILITY:
            self.assertFalse(route[field])

        route["membership_authority_generation_applies"] = True
        errors = []
        self.validator.validate_connect_token_revoke_generation_applicability(
            document["routes"], errors
        )
        self.assertIn(
            "spring-cloud-gateway POST /ws/game/connect-token/revoke must explicitly set "
            "membership_authority_generation_applies=False",
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
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                "admission_pointer, connect_scope_match, replay_protection_available, replay_admission_fence_match]",
                "admission_pointer, connect_scope_match, replay_protection_available]",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(
            any(
                "IssueConnectToken is missing required live checks" in error
                for error in errors
            )
        )

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
        self.assertTrue(
            any(
                "classifications must be a list of strings" in error for error in errors
            )
        )

    def test_null_classification_vocabulary_is_reported_without_crashing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            text = replace_or_fail(
                MATRIX.read_text(encoding="utf-8"),
                "classifications:\n  - public",
                "classifications: null\nignored_classifications:\n  - public",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(
            any(
                "classifications must be a list of strings" in error for error in errors
            )
        )

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
                self.assertTrue(
                    any("uses unknown classification" in error for error in errors)
                )

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
        self.assertTrue(
            any(
                "duplicate route entries require explicit applicability" in error
                for error in errors
            )
        )

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
    token_type: none
    token_issuer: none
    token_audience: none
    delegated_subject: authenticated_session_account
    tenant_billing_authority_generation_applies: false
    membership_authority_generation_applies: false
    required_live_checks: [public_production_visibility, public_production_admission, runtime_entitlements, admission_pointer, idempotency]
    canonical_errors:
      any_of: [PUBLIC_PRODUCTION_ADMISSION_DENIED, ADMISSION_POINTER_UNAVAILABLE, TENANT_BILLING_BLOCKED]
    mutation_contract: explicit_public_membership_atomic
    membership_creation: caller_bound_after_validation""",
                """  - service: game-session-service
    route: JOIN
    scope: tenant
    classification: public_production_onboarding
    auth_path: game_session_authenticated_context
    accepted_token_profiles: []
    token_type: none
    token_issuer: none
    token_audience: none
    delegated_subject: authenticated_session_account
    tenant_billing_authority_generation_applies: false
    membership_authority_generation_applies: false
    required_live_checks: [public_production_visibility, public_production_admission, runtime_entitlements, admission_pointer, idempotency]
    mutation_contract: explicit_public_membership_atomic""",
            )
            path.write_text(text, encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(
            any(
                "must declare ADMISSION_POINTER_UNAVAILABLE" in error
                for error in errors
            )
        )

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
                "account-service EnsurePublicProductionPlayerMembership must declare ADMISSION_POINTER_UNAVAILABLE",
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
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = route_for(document, "account-service", "RefreshGameplayServiceToken")
        policy = route["caller_policies"][0]
        policy["token_audience"] = "internal"
        policy["token_issuer"] = "untrusted-service"
        policy["mtls_identity"] = "game-session-service"
        policy["method_policy"] = "all_methods"
        errors = validate_document(self.validator, document)
        self.assertTrue(
            any(
                "token predicates must exactly match profile" in error
                for error in errors
            )
        )
        self.assertTrue(
            any("must be a concrete spiffe:// identity" in error for error in errors)
        )
        self.assertTrue(
            any(
                "must declare method_policy exact_declared_route" in error
                for error in errors
            )
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
