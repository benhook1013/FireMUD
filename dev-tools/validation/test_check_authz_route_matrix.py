#!/usr/bin/env python3
"""Regression tests for authorization route matrix validation."""

from __future__ import annotations

from collections import defaultdict
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


def grouped_routes(document, service):
    routes = defaultdict(list)
    for route in document["routes"]:
        if route.get("service") == service:
            routes[route["route"]].append(route)
    return routes


def route_for(document, service, route_name):
    return next(
        route
        for route in document["routes"]
        if route.get("service") == service and route.get("route") == route_name
    )


def configure_multi_profile_route(document):
    route = route_for(document, "game-session-service", "ToggleFeatureFlag")
    base_profile = next(
        profile for profile in document["token_profiles"] if profile["profile"] == "control-ui"
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
                self.assertIn("membership_when_tenant_role", route["required_live_checks"])
                self.assertIn("membership_generation", route["required_live_checks"])
                self.assertIn("tenant_generation", route["required_live_checks"])

    def test_operator_ingress_conditional_shape_is_validated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("route") == "POST /feature-flags/toggle"
        )
        route["membership_authority_generation_condition"]["platformAdmin_global"] = True
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(
                self.validator.yaml.safe_dump(document, sort_keys=False),
                encoding="utf-8",
            )
            errors = self.validator.validate(path)
        self.assertTrue(
            any(
                "conditional membership generation requires" in error
                for error in errors
            )
        )

    def test_game_session_operator_routes_match_ingress_authority_shape(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = grouped_routes(document, "game-session-service")
        for route_key in self.validator.GAME_SESSION_OPERATOR_ROUTES:
            _, route_name = route_key
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
                self.assertTrue(
                    {
                        "membership_when_tenant_role",
                        "membership_generation",
                        "tenant_generation",
                        "target_tenant_generation",
                        "current_global_role",
                        "role_appropriate_assurance",
                    }.issubset(route["required_live_checks"])
                )
                self.assertEqual("logging-admin-service", route["canonical_external_ingress"])
                self.assertEqual(
                    "deny_at_edge_and_migrate_to_logging_admin",
                    route["direct_owner_route_policy"],
                )

        drifted_route = routes["POST /sessions"][0]
        for missing_check in ("tenant_generation", "target_tenant_generation"):
            with self.subTest(missing_check=missing_check):
                drifted_route["required_live_checks"].remove(missing_check)
                errors = []
                self.validator.validate_generation_applicability(document["routes"], errors)
                self.assertTrue(
                    any(f"operator route must require {missing_check}" in error for error in errors)
                )
                drifted_route["required_live_checks"].append(missing_check)

    def test_profile_routes_require_generation_checks(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        routes = grouped_routes(document, "account-service")
        for route_name in ("GetProfile", "UpdateProfile"):
            self.assertTrue(routes[route_name])
            for route in routes[route_name]:
                self.assertTrue(route["tenant_billing_authority_generation_applies"])
                self.assertTrue(route["membership_authority_generation_applies"])
                self.assertEqual("control_ui_plus_current_tenant_role", route["auth_path"])
                self.assertEqual("exact_declared_route", route["method_policy"])
                self.assertTrue(
                    {"membership", "membership_generation", "tenant_generation"}.issubset(
                        route["required_live_checks"]
                    )
                )

        routes["GetProfile"][0]["required_live_checks"].remove("tenant_generation")
        errors = []
        self.validator.validate_profile_authority_routes(document["routes"], errors)
        self.assertIn("account-service GetProfile must require live check tenant_generation", errors)

    def test_profile_route_auth_and_method_policy_are_validated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(route for route in document["routes"] if route.get("route") == "GetProfile")
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
        self.assertEqual("required_and_redeemed_with_account", grpc["operator_authorization_reference"])
        self.assertIn("mutation_digest", grpc["required_fields"])
        self.assertIn("IDEMPOTENCY_CONFLICT", grpc["canonical_errors"]["any_of"])
        http = routes["POST /sessions/{sessionId}/refresh-roles"][0]
        self.assertEqual("account_issued_bounded_reference", http["operator_authorization_reference"])
        self.assertIn("mutation_digest", http["required_fields"])

        grpc.pop("operator_authorization_reference")
        errors = []
        self.validator.validate_refresh_roles_routes(document["routes"], errors)
        self.assertTrue(any("operator authorization redemption" in error for error in errors))

    def test_refresh_roles_rejects_malformed_required_fields(self):
        for route_name in (
            "RefreshRoles",
            "POST /sessions/{sessionId}/refresh-roles",
        ):
            with self.subTest(route_name=route_name):
                document = self.validator.yaml.safe_load(
                    MATRIX.read_text(encoding="utf-8")
                )
                route = next(
                    route
                    for route in document["routes"]
                    if route.get("route") == route_name
                )
                route["required_fields"] = [{"invalid": "field"}]
                errors = []
                self.validator.validate_refresh_roles_routes(document["routes"], errors)
                self.assertTrue(
                    any("required_fields must be a list of strings" in error for error in errors)
                )
                self.assertTrue(any("must require mutation_digest" in error for error in errors))

    def test_refresh_roles_rejects_malformed_canonical_error_any_of(self):
        for malformed in ("not-a-list", ["IDEMPOTENCY_CONFLICT", 7]):
            with self.subTest(malformed=malformed):
                document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
                route = route_for(document, "game-session-service", "RefreshRoles")
                route["canonical_errors"]["any_of"] = malformed
                errors = []
                self.validator.validate_refresh_roles_routes(document["routes"], errors)
                self.assertIn(
                    "game-session-service RefreshRoles canonical_errors.any_of "
                    "must be a list of strings",
                    errors,
                )
                self.assertIn(
                    "game-session-service RefreshRoles must declare IDEMPOTENCY_CONFLICT",
                    errors,
                )

    def test_privileged_operator_routes_require_live_global_role_and_assurance(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("route") == "POST /feature-flags/toggle"
        )
        route["required_live_checks"].remove("current_global_role")
        errors = []
        self.validator.validate_generation_applicability(document["routes"], errors)
        self.assertTrue(any("current_global_role" in error for error in errors))

        owner_route = next(
            route
            for route in document["routes"]
            if route.get("route") == "POST /sessions"
        )
        owner_route.pop("canonical_external_ingress")
        errors = []
        self.validator.validate_generation_applicability(document["routes"], errors)
        self.assertTrue(any("canonical_external_ingress" in error for error in errors))

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
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(
                self.validator.yaml.safe_dump(document, sort_keys=False),
                encoding="utf-8",
            )
            errors = self.validator.validate(path)
        self.assertTrue(any("uses unknown token profiles" in error for error in errors))
        self.assertFalse(
            any("exactly one token profile per receiver policy" in error for error in errors)
        )

    def test_non_internal_single_profile_predicates_are_validated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("service") == "logging-admin-service"
            and route.get("route") == "POST /feature-flags/toggle"
        )
        route["token_type"] = "wrong_token_type"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(
                self.validator.yaml.safe_dump(document, sort_keys=False),
                encoding="utf-8",
            )
            errors = self.validator.validate(path)
        self.assertTrue(
            any(
                "token predicates must exactly match profile 'control-ui'"
                in error
                for error in errors
            )
        )

    def test_malformed_mtls_shape_does_not_emit_duplicate_spiffe_error(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("service") == "game-session-service"
            and route.get("route") == "StartSession"
        )
        route["mtls_callers"]["any_of"] = "not-a-list"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(
                self.validator.yaml.safe_dump(document, sort_keys=False),
                encoding="utf-8",
            )
            errors = self.validator.validate(path)
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
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(
                self.validator.yaml.safe_dump(document, sort_keys=False),
                encoding="utf-8",
            )
            errors = self.validator.validate(path)
        self.assertEqual(
            1,
            errors.count(
                "game-session-service ToggleFeatureFlag must declare method_policy exact_declared_route"
            ),
        )

    def test_profile_routes_distinguish_self_only_subjects(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        for route_name in ("GetProfile", "UpdateProfile"):
            route = next(route for route in document["routes"] if route.get("route") == route_name)
            self.assertEqual("caller_account_id_for_self_only_roles", route["subject_binding"])
            self.assertEqual(["player", "moderator", "designer"], route["self_only_roles"])
            self.assertEqual("same_tenant_profile_for_tenantAdmin", route["target_subject_binding"])
            self.assertEqual("forbidden", route["platform_admin_override"])

    def test_export_tenant_data_is_tenant_generation_bound(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(route for route in document["routes"] if route.get("route") == "ExportTenantData")
        self.assertEqual("tenant_regular", route["classification"])
        self.assertTrue(route["tenant_billing_authority_generation_applies"])
        self.assertIn("tenant_generation", route["required_live_checks"])

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

    def test_caller_policy_unknown_token_profile_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("route") == "GetTenantEntitlementsForRuntime"
        )
        policy = next(
            policy
            for policy in route["caller_policies"]
            if policy.get("caller") == "game-session-service"
        )
        policy["accepted_token_profiles"] = ["unknown-profile"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(
                self.validator.yaml.safe_dump(document, sort_keys=False),
                encoding="utf-8",
            )
            errors = self.validator.validate(path)
        self.assertTrue(
            any(
                "uses unknown token profiles" in error and "unknown-profile" in error
                for error in errors
            )
        )

    def test_outer_route_unknown_token_profile_is_reported_once(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("route") == "IssueHumanOperatorAuthorizationReference"
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
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertEqual(
            1,
            errors.count(f"matrix.routes[{route_index}] accepted_token_profiles must be a list of strings"),
        )

    def test_multi_profile_route_with_audience_map_is_accepted(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = configure_multi_profile_route(document)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertEqual([], errors)

    def test_multi_profile_route_reports_audience_map_error(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = configure_multi_profile_route(document)
        route["accepted_token_profile_audiences"] = {"control-ui": "control-ui"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(
            any("accepted token audience keys must equal accepted profiles" in error for error in errors)
        )
        self.assertFalse(any("exactly one token profile per receiver policy" in error for error in errors))

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
        self.assertTrue(any("duplicate profile 'control-ui'" in error for error in errors))
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
        self.assertEqual(["route_status_vocabulary must not contain duplicates"], errors)

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
            any("route_status_vocabulary must contain exactly" in error for error in errors)
        )

        document["route_status_vocabulary"].remove("declared_but_not_current")
        errors = []
        statuses = self.validator.validate_route_status_vocabulary(document, errors)
        self.validator.validate_route_statuses(document["routes"], statuses, errors)
        self.assertTrue(any("route_status must be one of" in error for error in errors))

    def test_required_fields_use_snake_case(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        errors = []
        self.validator.validate_required_fields(document["routes"], errors)
        self.assertEqual([], errors)

        route = next(route for route in document["routes"] if route.get("route") == "EnterPrivilegedControlWindow")
        route["required_fields"] = ["RequestedGlobalRole"]
        errors = []
        self.validator.validate_required_fields(document["routes"], errors)
        self.assertTrue(any("required_fields must use snake_case" in error for error in errors))

    def test_pending_deletion_uses_canonical_account_generation_field(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("classification") == "pending_deletion_scoped"
        )
        self.assertFalse(route["account_authority_generation_applies"])
        self.assertFalse(route["tenant_billing_authority_generation_applies"])
        self.assertFalse(route["membership_authority_generation_applies"])
        route["account_generation_applies"] = route.pop("account_authority_generation_applies")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("must use account_authority_generation_applies" in error for error in errors))

    def test_pending_deletion_generation_exception_has_bounded_negative_proof(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        exception = document["tenant_generation_policy"]["exception_allowlist"]["pending_deletion_scoped"]
        self.assertFalse(exception["target_tenant_generation"])
        self.assertIn("pending_deletion_state", exception["required_authority"])
        self.assertTrue(exception["contract_justification"])
        self.assertIn(
            "pending_deletion_route_denied_after_target_tenant_generation_advance",
            exception["negative_proof"],
        )

        exception.pop("contract_justification")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("bounded contract_justification" in error for error in errors))

    def test_multi_profile_route_requires_shared_type_and_issuer(self):
        for field in ("token_type", "token_issuer"):
            with self.subTest(field=field):
                document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
                route = configure_multi_profile_route(document)
                route[field] = "mismatch"
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "matrix.yaml"
                    path.write_text(
                        self.validator.yaml.safe_dump(document, sort_keys=False),
                        encoding="utf-8",
                    )
                    errors = self.validator.validate(path)
                self.assertTrue(
                    any(
                        "multi-profile token predicates must match the shared token_type/token_issuer"
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
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("must not declare scalar token_audience" in error for error in errors))

    def test_caller_policy_shape_errors_are_not_duplicated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route for route in document["routes"] if route.get("route") == "GetTenantEntitlementsForRuntime"
        )
        policy = next(policy for policy in route["caller_policies"] if policy.get("caller") == "game-session-service")
        policy.pop("accepted_token_profiles")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
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
        self.validator.validate_delegated_entitlements(routes, errors, cardinality_errors)
        self.assertEqual(
            ["matrix must contain exactly one account-service GetTenantEntitlementsForRuntime route"],
            errors,
        )

    def test_caller_policy_method_policy_error_is_not_duplicated(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route for route in document["routes"] if route.get("route") == "GetTenantEntitlementsForRuntime"
        )
        policy = next(policy for policy in route["caller_policies"] if policy.get("caller") == "game-session-service")
        policy["method_policy"] = "all_methods"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertEqual(
            1,
            errors.count(
                "account-service GetTenantEntitlementsForRuntime caller_policies[0] must declare method_policy exact_declared_route"
            ),
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

    def test_gameplay_connect_bounded_registry_and_generation_exception_is_required(self):
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
                document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
                route = route_for(document, "spring-cloud-gateway", "/ws/game/**")
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
                "account-service EnsurePublicProductionPlayerMembership must declare ADMISSION_POINTER_UNAVAILABLE",
                "account-service POST /auth/bootstrap/join must declare ADMISSION_POINTER_UNAVAILABLE",
                "game-session-service JOIN must declare ADMISSION_POINTER_UNAVAILABLE",
            ],
            errors,
        )

    def test_role_assurance_requires_one_applies_to_shape(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        requirement = document["role_assurance"]["privileged_control_when_global_role"]["requirements"]["support"]
        requirement["allowed_classifications"] = requirement["applies_to"]["route_classifications"]
        del requirement["applies_to"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("support.applies_to must be a mapping" in error for error in errors))
        self.assertTrue(any("one applies_to shape" in error for error in errors))

    def test_role_assurance_scope_must_be_non_empty_and_declared(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        requirement = document["role_assurance"]["privileged_control_when_global_role"]["requirements"]["support"]
        requirement["applies_to"]["route_classifications"] = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("must be a non-empty list of strings" in error for error in errors))

        requirement["applies_to"]["route_classifications"] = ["not_a_classification"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("outside the classification vocabulary" in error for error in errors))

    def test_role_assurance_rejects_unexpected_role_keys(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        requirements = document["role_assurance"]["privileged_control_when_global_role"]["requirements"]
        requirements["suport"] = requirements.pop("support")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(
                self.validator.yaml.safe_dump(document, sort_keys=False),
                encoding="utf-8",
            )
            errors = self.validator.validate(path)
        self.assertTrue(any("contains unexpected role keys" in error for error in errors))

    def test_legacy_target_only_route_declaration_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route for route in document["routes"] if route.get("route") == "POST /auth/bootstrap/join"
        )
        route.pop("route_status")
        route.setdefault("implementation_status", {})["target_only"] = True
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("route_status instead of implementation_status.target_only" in error for error in errors))

    def test_unknown_route_status_is_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(route for route in document["routes"] if route.get("route") == "BillingArtifactsTenant")
        route["route_status"] = "target_only"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("route_status must be one of" in error for error in errors))

    def test_private_receiver_requires_exact_token_and_method_predicates(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(route for route in document["routes"] if route.get("route") == "RefreshGameplayServiceToken")
        policy = route["caller_policies"][0]
        policy["token_audience"] = "internal"
        policy["token_issuer"] = "untrusted-service"
        policy["mtls_identity"] = "game-session-service"
        policy["method_policy"] = "all_methods"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("token predicates must exactly match profile" in error for error in errors))
        self.assertTrue(any("must be a concrete spiffe:// identity" in error for error in errors))
        self.assertTrue(any("must declare method_policy exact_declared_route" in error for error in errors))

    def test_tenant_generation_exception_requires_class_checks(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(route for route in document["routes"] if route.get("route") == "GetTenantEntitlementsTenant")
        route["required_live_checks"].remove("membership_generation")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("GetTenantEntitlementsTenant is missing route-class authority checks" in error for error in errors))

    def test_tenant_generation_exception_entries_must_be_mappings(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document["tenant_generation_policy"]["exception_allowlist"]["billing_safe_tenant"] = "malformed"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertIn(
            "tenant_generation_policy.exception_allowlist.billing_safe_tenant must be a mapping",
            errors,
        )

    def test_cross_tenant_generation_exceptions_reference_role_assurance_policy(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        exception = document["tenant_generation_policy"]["exception_allowlist"][
            "cross_tenant_support_safe"
        ]
        exception.pop("role_assurance_policy")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(
                self.validator.yaml.safe_dump(document, sort_keys=False),
                encoding="utf-8",
            )
            errors = self.validator.validate(path)
        self.assertIn(
            "tenant_generation_policy exception cross_tenant_support_safe "
            "must reference privileged_control_when_global_role",
            errors,
        )

    def test_cross_tenant_target_membership_checks_are_rejected(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("route") == "GetSubscriptionCrossTenantSupportSafe"
        )
        route["required_live_checks"].append("membership")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertTrue(any("must not require target membership" in error for error in errors))

    def test_entitlement_contract_is_exactly_tenant_bound(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        document["entitlement_contract"]["cross_tenant_inheritance"] = "allowed"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.yaml"
            path.write_text(self.validator.yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            errors = self.validator.validate(path)
        self.assertIn("entitlement_contract.cross_tenant_inheritance must be forbidden", errors)

    def test_entitlement_contract_requires_exactly_one_matching_route(self):
        document = self.validator.yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
        route = next(
            route
            for route in document["routes"]
            if route.get("route") == "GetTenantEntitlementsForRuntime"
        )
        expected = "matrix must contain exactly one account-service GetTenantEntitlementsForRuntime route"
        for routes in ([], [route, dict(route)]):
            with self.subTest(route_count=len(routes)):
                errors = []
                self.validator.validate_entitlement_contract(document, routes, errors)
                self.assertEqual([expected], errors)


if __name__ == "__main__":
    unittest.main()
