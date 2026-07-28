#!/usr/bin/env python3
"""Validate the machine-readable authorization route matrix contract."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MATRIX = ROOT / "design/architecture/system-architecture-authz-route-matrix.yaml"
REQUIRED_WS_GAME_CHECKS = {
    "connect_token_single_use_consume",
    "replay_protection_available",
    "replay_admission_fence_match",
    "connect_scope_match",
}
REQUIRED_ISSUE_CONNECT_TOKEN_CHECKS = {
    "replay_protection_available",
    "replay_admission_fence_match",
}
JOIN_ROUTES_REQUIRING_POINTER_ERROR = {
    ("game-session-service", "JOIN"),
    ("account-service", "POST /auth/bootstrap/join"),
    ("account-service", "EnsurePublicProductionPlayerMembership"),
}
REQUIRED_DELEGATED_ENTITLEMENT_CHECKS = {
    "conditional_realm_access_grant",
    "grant_version",
    "issuer_generation",
    "account_generation",
}
REQUIRED_TRUSTED_PROXY_CHECKS = {"trusted_proxy_identity"}
REQUIRED_CONNECT_TOKEN_REVOKE_CHECKS = {"browser_origin", "csrf"}
REQUIRED_FIRST_PARTY_WS_APPLICABILITY = {
    "connection_mode": "first_party_web",
    "operation": "websocket_upgrade",
}
REQUIRED_REVOKE_APPLICABILITY = {
    "connection_mode": "first_party_web",
    "operation": "connect_token_cookie_revoke",
}
GAMEPLAY_CONNECT_ISSUED_TOKEN_STATE = "none_bounded_single_use_replay_exception"
REQUIRED_FIELD_PATTERN = re.compile(r"^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")
ROUTE_STATUS_VALUES = {
    "current_openapi_operator_surface",
    "target_not_currently_routable",
}
TOKEN_ISSUER = "firemud-account-service"
MEMBERSHIP_GENERATION_APPLICABILITY_VALUES = {
    True,
    False,
    "conditional_by_operator_role",
}
CONDITIONAL_OPERATOR_MEMBERSHIP_SHAPE = {
    "tenant_role": True,
    "platformAdmin_global": False,
}
OPERATOR_INGRESS_ROUTES = {
    ("logging-admin-service", "POST /feature-flags/toggle"),
    ("logging-admin-service", "POST /moderation/actions"),
    ("logging-admin-service", "POST /tick-remediation/pause"),
    ("logging-admin-service", "POST /tick-remediation/resume"),
    ("logging-admin-service", "POST /admission-pointers"),
    ("logging-admin-service", "POST /admission-pointers/cutover"),
    ("logging-admin-service", "POST /admission-pointers/version-upgrades"),
}
GAME_SESSION_OPERATOR_ROUTES = {
    ("game-session-service", "POST /sessions"),
    ("game-session-service", "POST /sessions/{sessionId}/stop"),
    ("game-session-service", "POST /sessions/{sessionId}/restart"),
    ("game-session-service", "POST /sessions/{sessionId}/refresh-roles"),
}
CONDITIONAL_OPERATOR_ROUTES = OPERATOR_INGRESS_ROUTES | GAME_SESSION_OPERATOR_ROUTES
REQUIRED_ROLE_ASSURANCE_ROLES = {"platformAdmin", "billingAdmin", "support"}
REQUIRED_TENANT_GENERATION_EXCEPTIONS = {
    "billing_safe_tenant": {
        "target_tenant_generation": False,
        "required_live_checks": {
            "issuer_generation",
            "account_generation",
            "membership_generation",
            "membership",
        },
    },
    "cross_tenant_support_safe": {
        "target_tenant_generation": False,
        "required_live_checks": {
            "issuer_generation",
            "account_generation",
            "current_global_role",
            "role_appropriate_assurance",
        },
    },
    "cross_tenant_billing_safe": {
        "target_tenant_generation": False,
        "required_live_checks": {
            "issuer_generation",
            "account_generation",
            "current_global_role",
            "role_appropriate_assurance",
        },
    },
}
REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS = {
    "pending_deletion_scoped": {
        "target_tenant_generation": False,
        "required_live_checks": {
            "pending_deletion_state",
            "pending_deletion_credential_registry",
        },
    },
}
PRIVILEGED_OPERATOR_ROLE_ASSURANCE = "privileged_control_when_global_role"
CANONICAL_OPERATOR_INGRESS = "logging-admin-service"
DIRECT_OWNER_ROUTE_POLICY = "deny_at_edge_and_migrate_to_logging_admin"
LiveChecksCache = dict[int, set[str]]


def route_key(route: dict[str, Any]) -> str | None:
    service = route.get("service")
    name = route.get("route")
    if not isinstance(service, str) or not service.strip() or not isinstance(name, str) or not name.strip():
        return None
    return f"{service}|{name}"


def string_list(value: Any, field: str, errors: list[str]) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        errors.append(f"{field} must be a list of strings")
        return []
    return value


def collect_live_checks(
    value: Any,
    field: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> list[str]:
    checks: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            child_field = f"{field}.{key}" if field else key
            if key == "required_live_checks":
                parsed_checks = (
                    live_checks_cache.get(id(value))
                    if live_checks_cache is not None
                    else None
                )
                if parsed_checks is None:
                    parsed_checks = set(string_list(child, child_field, errors))
                    if live_checks_cache is not None:
                        live_checks_cache[id(value)] = parsed_checks
                checks.extend(parsed_checks)
            else:
                checks.extend(
                    collect_live_checks(child, child_field, errors, live_checks_cache)
                )
    elif isinstance(value, list):
        for index, child in enumerate(value):
            checks.extend(
                collect_live_checks(child, f"{field}[{index}]", errors, live_checks_cache)
            )
    return checks


def collect_auth_paths(value: Any) -> list[Any]:
    auth_paths: list[Any] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "auth_path":
                auth_paths.append(child)
            auth_paths.extend(collect_auth_paths(child))
    elif isinstance(value, list):
        for child in value:
            auth_paths.extend(collect_auth_paths(child))
    return auth_paths


def validate_auth_path_vocabulary(document: dict[str, Any], errors: list[str]) -> set[str]:
    vocabulary = document.get("auth_path_vocabulary")
    if not isinstance(vocabulary, list) or not vocabulary or any(
        not isinstance(item, str) for item in vocabulary
    ):
        errors.append("auth_path_vocabulary must be a non-empty list of strings")
        allowed_auth_paths: set[str] = set()
    else:
        allowed_auth_paths = set(vocabulary)
        if len(allowed_auth_paths) != len(vocabulary):
            errors.append("auth_path_vocabulary must not contain duplicates")

    unknown_auth_paths = [
        auth_path
        for auth_path in collect_auth_paths(document)
        if not isinstance(auth_path, str) or auth_path not in allowed_auth_paths
    ]
    if unknown_auth_paths:
        errors.append(
            "auth_path contains values outside the closed vocabulary: "
            f"{unknown_auth_paths!r}"
        )
    return allowed_auth_paths


def validate_token_profiles(document: dict[str, Any], errors: list[str]) -> dict[str, dict[str, str]]:
    raw_profiles = document.get("token_profiles")
    if not isinstance(raw_profiles, list):
        errors.append("token_profiles must be a list of mappings")
        return {}

    profiles: dict[str, dict[str, str]] = {}
    for index, raw_profile in enumerate(raw_profiles):
        label = f"token_profiles[{index}]"
        if not isinstance(raw_profile, dict):
            errors.append(f"{label} must be a mapping")
            continue
        values: dict[str, str] = {}
        for field in ("profile", "type", "issuer", "audience"):
            value = raw_profile.get(field)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"{label}.{field} must be a non-empty string")
            else:
                values[field] = value
        profile = values.get("profile")
        if profile:
            if profile in profiles:
                errors.append(f"token_profiles must not contain duplicate profile {profile!r}")
            else:
                profiles[profile] = values
        if values.get("issuer") and values["issuer"] != TOKEN_ISSUER:
            errors.append(f"{label}.issuer must be {TOKEN_ISSUER!r}")
        if "kind" in raw_profile:
            errors.append(f"{label} must use type instead of legacy kind")
    return profiles


def validate_role_assurance(document: dict[str, Any], errors: list[str]) -> set[str]:
    raw_assurance = document.get("role_assurance")
    if not isinstance(raw_assurance, dict):
        errors.append("role_assurance must be a mapping")
        return set()

    vocabulary = raw_assurance.get("vocabulary")
    if not isinstance(vocabulary, dict) or not vocabulary:
        errors.append("role_assurance.vocabulary must be a non-empty mapping")
        predicates: set[str] = set()
    else:
        predicates = set()
        for name, definition in vocabulary.items():
            if not isinstance(name, str) or not name.strip():
                errors.append("role_assurance.vocabulary keys must be non-empty strings")
                continue
            if not isinstance(definition, dict):
                errors.append(f"role_assurance.vocabulary.{name} must be a mapping")
                continue
            predicates.add(name)
    predicate = raw_assurance.get("privileged_control_when_global_role")
    if not isinstance(predicate, dict):
        errors.append(
            "role_assurance.privileged_control_when_global_role must be a mapping"
        )
        return predicates
    requirements = predicate.get("requirements")
    if "requirements" in predicate and not (
        isinstance(vocabulary, dict)
        and PRIVILEGED_OPERATOR_ROLE_ASSURANCE in vocabulary
    ):
        errors.append(
            "role_assurance.vocabulary must declare predicate "
            f"{PRIVILEGED_OPERATOR_ROLE_ASSURANCE} when requirements are present"
        )
    if not isinstance(requirements, dict):
        errors.append(
            "role_assurance.privileged_control_when_global_role.requirements must be a mapping"
        )
        return predicates
    unexpected_roles = sorted(
        set(requirements) - REQUIRED_ROLE_ASSURANCE_ROLES,
        key=str,
    )
    if unexpected_roles:
        errors.append(
            "role_assurance.privileged_control_when_global_role.requirements "
            f"contains unexpected role keys: {unexpected_roles}"
        )
    raw_classifications = document.get("classifications")
    allowed_classifications = {
        item
        for item in (raw_classifications if isinstance(raw_classifications, list) else [])
        if isinstance(item, str)
    }
    for role in sorted(REQUIRED_ROLE_ASSURANCE_ROLES):
        requirement = requirements.get(role)
        label = f"role_assurance.privileged_control_when_global_role.requirements.{role}"
        if not isinstance(requirement, dict):
            errors.append(f"{label} must be a mapping")
            continue
        legacy_keys = {"when", "when_scopes", "allowed_classifications"} & set(requirement)
        if legacy_keys:
            errors.append(
                f"{label} must use one applies_to shape; legacy keys remain: {sorted(legacy_keys)}"
            )
        applies_to = requirement.get("applies_to")
        if not isinstance(applies_to, dict):
            errors.append(f"{label}.applies_to must be a mapping")
            continue
        route_classifications = applies_to.get("route_classifications")
        if not isinstance(route_classifications, list) or not route_classifications:
            errors.append(
                f"{label}.applies_to.route_classifications must be a non-empty list of strings"
            )
            continue
        if any(not isinstance(item, str) for item in route_classifications):
            errors.append(
                f"{label}.applies_to.route_classifications must be a list of strings"
            )
            continue
        unknown_classifications = sorted(
            set(route_classifications) - allowed_classifications
        )
        if unknown_classifications:
            errors.append(
                f"{label}.applies_to.route_classifications contains values outside "
                f"the classification vocabulary: {unknown_classifications}"
            )
    return predicates


def validate_route_status_vocabulary(document: dict[str, Any], errors: list[str]) -> set[str]:
    vocabulary = document.get("route_status_vocabulary")
    if not isinstance(vocabulary, list) or not vocabulary or any(
        not isinstance(item, str) for item in vocabulary
    ):
        errors.append("route_status_vocabulary must be a non-empty list of strings")
        allowed_statuses: set[str] = set()
    else:
        allowed_statuses = set(vocabulary)
        if len(allowed_statuses) != len(vocabulary):
            errors.append("route_status_vocabulary must not contain duplicates")
        if allowed_statuses != ROUTE_STATUS_VALUES:
            errors.append(
                "route_status_vocabulary must contain exactly "
                f"{sorted(ROUTE_STATUS_VALUES)}"
            )
    return allowed_statuses


def validate_route_statuses(
    routes: list[Any], allowed_statuses: set[str], errors: list[str]
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            continue
        status = route.get("route_status")
        if status is not None and status not in allowed_statuses:
            errors.append(
                f"routes[{index}] route_status must be one of {sorted(allowed_statuses)}"
            )
        implementation_status = route.get("implementation_status")
        if isinstance(implementation_status, dict) and "target_only" in implementation_status:
            errors.append(
                f"routes[{index}] must use route_status instead of implementation_status.target_only"
            )


def validate_required_fields(routes: list[Any], errors: list[str]) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict) or "required_fields" not in route:
            continue
        fields = string_list(route.get("required_fields"), f"routes[{index}] required_fields", errors)
        invalid_fields = [field for field in fields if not REQUIRED_FIELD_PATTERN.fullmatch(field)]
        if invalid_fields:
            errors.append(
                f"routes[{index}] required_fields must use snake_case: {invalid_fields}"
            )


def validate_multi_profile_predicates(
    entry: dict[str, Any],
    label: str,
    profiles: list[str],
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
) -> None:
    known_profiles = [token_profiles.get(profile_name) for profile_name in profiles]
    if not all(profile is not None for profile in known_profiles):
        return
    if entry.get("token_audience") is not None:
        errors.append(
            f"{label} multi-profile routes must not declare scalar token_audience; "
            "use accepted_token_profile_audiences"
        )
    shared_type_issuer = {
        (profile.get("type"), profile.get("issuer"))
        for profile in known_profiles
        if profile is not None
    }
    token_predicates = (entry.get("token_type"), entry.get("token_issuer"))
    if token_predicates == (None, None):
        return
    if len(shared_type_issuer) != 1 or token_predicates != next(iter(shared_type_issuer)):
        errors.append(
            f"{label} multi-profile token predicates must match the shared token_type/token_issuer"
        )


def validate_pending_deletion_generation(
    route: dict[str, Any],
    label: str,
    account_generation: Any,
    errors: list[str],
    checks: set[str] | None = None,
) -> None:
    if route.get("classification") != "pending_deletion_scoped":
        return
    if account_generation is not False:
        errors.append(
            f"{label} pending_deletion_scoped routes must set "
            "account_authority_generation_applies=false"
        )
    if route.get("tenant_billing_authority_generation_applies") is not False:
        errors.append(
            f"{label} pending_deletion_scoped routes must set "
            "tenant_billing_authority_generation_applies=false"
        )
    if route.get("membership_authority_generation_applies") is not False:
        errors.append(
            f"{label} pending_deletion_scoped routes must set "
            "membership_authority_generation_applies=false"
        )
    if checks is None:
        checks = route_live_checks(route, label, errors)
    missing = REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS["pending_deletion_scoped"][
        "required_live_checks"
    ] - checks
    if missing:
        errors.append(f"{label} is missing no-target authority checks: {sorted(missing)}")
    forbidden_checks = checks & {"tenant_generation", "target_tenant_generation"}
    if forbidden_checks:
        errors.append(
            f"{label} must not require tenant-generation checks for pending_deletion_scoped: "
            f"{sorted(forbidden_checks)}"
        )


def validate_membership_generation(
    route: dict[str, Any], label: str, errors: list[str]
) -> Any:
    value = route.get("membership_authority_generation_applies")
    valid_scalar = isinstance(value, bool) or value == "conditional_by_operator_role"
    if value is not None and not valid_scalar:
        errors.append(
            f"{label} membership_authority_generation_applies must be one of "
            f"{sorted(MEMBERSHIP_GENERATION_APPLICABILITY_VALUES, key=str)}"
        )
    condition = route.get("membership_authority_generation_condition")
    if value == "conditional_by_operator_role":
        if condition != CONDITIONAL_OPERATOR_MEMBERSHIP_SHAPE:
            errors.append(
                f"{label} conditional membership generation requires "
                "tenant_role=true and platformAdmin_global=false"
            )
    elif condition is not None:
        errors.append(
            f"{label} declares membership_authority_generation_condition without "
            "conditional_by_operator_role"
        )
    return value


def validate_conditional_operator_route(
    route: dict[str, Any],
    label: str,
    value: Any,
    errors: list[str],
    checks: set[str] | None = None,
) -> None:
    route_key_value = (route.get("service"), route.get("route"))
    if route_key_value not in CONDITIONAL_OPERATOR_ROUTES:
        return
    if value != "conditional_by_operator_role":
        errors.append(
            f"{label} operator ingress must use conditional_by_operator_role "
            "membership generation"
        )
    if route.get("global_platform_admin_membership_required") is not False:
        errors.append(
            f"{label} must set global_platform_admin_membership_required=false"
        )
    if checks is None:
        checks = route_live_checks(route, label, errors)
    if "membership_when_tenant_role" not in checks:
        errors.append(
            f"{label} tenant-role branch must require membership_when_tenant_role"
        )
    if "membership_generation" not in checks:
        errors.append(
            f"{label} tenant-role branch must require membership_generation"
        )
    if "tenant_generation" not in checks:
        errors.append(f"{label} operator route must require tenant_generation")
    if "target_tenant_generation" not in checks:
        errors.append(
            f"{label} operator route must require target_tenant_generation"
        )
    if route.get("global_platform_admin_reference_generation_binding") != "target_tenant_generation":
        errors.append(
            f"{label} must bind global platformAdmin operations to target_tenant_generation"
        )
    if route.get("role_assurance") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE:
        errors.append(
            f"{label} operator route must declare role_assurance "
            f"{PRIVILEGED_OPERATOR_ROLE_ASSURANCE}"
        )
    for required_check in ("current_global_role", "role_appropriate_assurance"):
        if required_check not in checks:
            errors.append(
                f"{label} privileged operator route must require live check "
                f"{required_check}"
            )
    if route_key_value in GAME_SESSION_OPERATOR_ROUTES:
        if route.get("canonical_external_ingress") != CANONICAL_OPERATOR_INGRESS:
            errors.append(
                f"{label} must declare canonical_external_ingress {CANONICAL_OPERATOR_INGRESS}"
            )
        if route.get("direct_owner_route_policy") != DIRECT_OWNER_ROUTE_POLICY:
            errors.append(
                f"{label} must declare direct_owner_route_policy {DIRECT_OWNER_ROUTE_POLICY}"
            )


def validate_token_fields(
    entry: dict[str, Any],
    label: str,
    profiles: list[str],
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
    reported_unknown_profiles: set[str] | None = None,
    allow_multi_profile: bool = False,
    allow_implicit_profile: bool = False,
) -> None:
    token_predicates = (
        entry.get("token_type"),
        entry.get("token_issuer"),
        entry.get("token_audience"),
    )
    if not profiles:
        if token_predicates != ("none", "none", "none"):
            errors.append(
                f"{label} must declare token_type/token_issuer/token_audience as none"
            )
        return
    if len(profiles) != 1:
        if allow_multi_profile and len(profiles) > 1:
            validate_multi_profile_predicates(
                entry, label, profiles, token_profiles, errors
            )
            return
        errors.append(f"{label} must declare exactly one token profile per receiver policy")
        return
    profile = token_profiles.get(profiles[0])
    if profile is None:
        if reported_unknown_profiles is None or profiles[0] not in reported_unknown_profiles:
            errors.append(f"{label} uses unknown token profiles: {[profiles[0]]}")
        return
    if allow_implicit_profile and token_predicates == (None, None, None):
        return
    expected = (profile.get("type"), profile.get("issuer"), profile.get("audience"))
    if token_predicates != expected:
        errors.append(
            f"{label} token predicates must exactly match profile {profiles[0]!r}"
        )


def validate_route_profile_declaration(
    route: dict[str, Any],
    index: int,
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
) -> tuple[Any, list[str], list[str]]:
    profiles_value = route.get("accepted_token_profiles")
    if profiles_value is None:
        return (None, [], [])
    label = f"matrix.routes[{index}]"
    profiles = string_list(
        profiles_value, f"{label} accepted_token_profiles", errors
    )
    unknown_profiles = sorted(set(profiles) - set(token_profiles))
    if unknown_profiles:
        errors.append(f"{label} uses unknown token profiles: {unknown_profiles}")
    audience_map = route.get("accepted_token_profile_audiences")
    if len(profiles) > 1 and not isinstance(audience_map, dict):
        errors.append(
            f"{label} multi-profile receiver requires accepted_token_profile_audiences"
        )
    if isinstance(audience_map, dict):
        if set(audience_map) != set(profiles):
            errors.append(
                f"{label} accepted token audience keys must equal accepted profiles"
            )
        for profile_name in profiles:
            expected_audience = token_profiles.get(profile_name, {}).get("audience")
            if audience_map.get(profile_name) != expected_audience:
                errors.append(
                    f"{label} audience for {profile_name!r} must match token_profiles"
                )
    return (profiles_value, profiles, unknown_profiles)


def validate_caller_policies(
    caller_policies: Any,
    label: str,
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
) -> None:
    if not isinstance(caller_policies, list) or not caller_policies:
        errors.append(f"{label} caller_policies must be a non-empty list")
        return
    for policy_index, policy in enumerate(caller_policies):
        policy_label = f"{label} caller_policies[{policy_index}]"
        if not isinstance(policy, dict):
            errors.append(f"{policy_label} must be a mapping")
            continue
        caller = policy.get("caller")
        if not isinstance(caller, str) or not caller.strip():
            errors.append(f"{policy_label}.caller must be a non-empty string")
        mtls_identity = policy.get("mtls_identity")
        if (
            not isinstance(mtls_identity, str)
            or not mtls_identity.startswith("spiffe://")
            or "/sa/" not in mtls_identity
        ):
            errors.append(
                f"{policy_label}.mtls_identity must be a concrete spiffe:// identity"
            )
        if policy.get("method_policy") != "exact_declared_route":
            errors.append(f"{policy_label} must declare method_policy exact_declared_route")
        policy_profiles_value = policy.get("accepted_token_profiles")
        policy_profiles = string_list(
            policy_profiles_value,
            f"{policy_label} accepted_token_profiles",
            errors,
        )
        if isinstance(policy_profiles_value, list) and all(
            isinstance(profile_name, str) for profile_name in policy_profiles_value
        ):
            validate_token_fields(
                policy, policy_label, policy_profiles, token_profiles, errors
            )


def validate_internal_route_callers(
    route: dict[str, Any],
    label: str,
    profiles_value: Any,
    profiles: list[str],
    unknown_profiles: list[str],
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
) -> None:
    allowed_callers = route.get("allowed_callers")
    mtls_callers = route.get("mtls_callers")
    for field, value in (
        ("allowed_callers", allowed_callers),
        ("mtls_callers", mtls_callers),
    ):
        values = value.get("any_of") if isinstance(value, dict) else None
        if not isinstance(values, list) or not values or any(
            not isinstance(item, str) or not item.strip() for item in values
        ):
            errors.append(f"{label} {field}.any_of must be a non-empty list of strings")
    mtls_values = mtls_callers.get("any_of") if isinstance(mtls_callers, dict) else None
    mtls_values_valid = isinstance(mtls_values, list) and bool(mtls_values) and all(
        isinstance(item, str) and item.strip() for item in mtls_values
    )
    if mtls_values_valid and any(
        not item.startswith("spiffe://") or "/sa/" not in item
        for item in mtls_values
    ):
        errors.append(
            f"{label} mtls_callers.any_of must contain concrete spiffe:// identities"
        )
    if route.get("method_policy") != "exact_declared_route":
        errors.append(f"{label} must declare method_policy exact_declared_route")
    if profiles_value is None or (
        isinstance(profiles_value, list)
        and all(isinstance(profile_name, str) for profile_name in profiles_value)
    ):
        validate_token_fields(
            route,
            label,
            profiles,
            token_profiles,
            errors,
            set(unknown_profiles),
            allow_multi_profile=True,
        )


def validate_receiver_predicates(
    routes: list[Any], token_profiles: dict[str, dict[str, str]], errors: list[str]
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            continue
        profiles_value, profiles, unknown_profiles = validate_route_profile_declaration(
            route, index, token_profiles, errors
        )
        if route.get("classification") != "internal_workload":
            if profiles and (
                isinstance(profiles_value, list)
                and all(isinstance(profile_name, str) for profile_name in profiles_value)
            ):
                validate_token_fields(
                    route,
                    f"matrix.routes[{index}]",
                    profiles,
                    token_profiles,
                    errors,
                    set(unknown_profiles),
                    allow_multi_profile=True,
                    allow_implicit_profile=True,
                )
            continue
        label = f"{route.get('service')} {route.get('route')}"
        caller_policies = route.get("caller_policies")
        if caller_policies is not None:
            validate_caller_policies(
                caller_policies, label, token_profiles, errors
            )
            continue
        validate_internal_route_callers(
            route,
            label,
            profiles_value,
            profiles,
            unknown_profiles,
            token_profiles,
            errors,
        )


def validate_tenant_generation_allowlist(
    policy: dict[str, Any], errors: list[str]
) -> None:
    allowlist = policy.get("exception_allowlist")
    if not isinstance(allowlist, dict):
        errors.append("tenant_generation_policy.exception_allowlist must be a mapping")
        return
    if set(allowlist) != set(REQUIRED_TENANT_GENERATION_EXCEPTIONS):
        errors.append(
            "tenant_generation_policy.exception_allowlist must be exactly "
            "the closed route-class allowlist"
        )
    for classification, expected in REQUIRED_TENANT_GENERATION_EXCEPTIONS.items():
        entry = allowlist.get(classification)
        if not isinstance(entry, dict):
            errors.append(
                "tenant_generation_policy.exception_allowlist."
                f"{classification} must be a mapping"
            )
            continue
        if entry.get("target_tenant_generation") is not expected["target_tenant_generation"]:
            errors.append(
                f"tenant_generation_policy exception {classification} "
                "has the wrong target generation setting"
            )
        required_checks = set(
            string_list(
                entry.get("required_authority"),
                "tenant_generation_policy.exception_allowlist."
                f"{classification}.required_authority",
                errors,
            )
        )
        if required_checks != expected["required_live_checks"]:
            errors.append(
                f"tenant_generation_policy exception {classification} "
                "has the wrong required authority checks"
            )
        if classification in (
            "cross_tenant_support_safe",
            "cross_tenant_billing_safe",
        ) and entry.get("role_assurance_policy") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE:
            errors.append(
                f"tenant_generation_policy exception {classification} "
                f"must reference {PRIVILEGED_OPERATOR_ROLE_ASSURANCE}"
            )


def validate_no_target_tenant_classifications(
    policy: dict[str, Any], errors: list[str]
) -> None:
    classifications = policy.get("no_target_tenant_classifications")
    if not isinstance(classifications, dict):
        errors.append(
            "tenant_generation_policy.no_target_tenant_classifications must be a mapping"
        )
        return
    if set(classifications) != set(REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS):
        errors.append(
            "tenant_generation_policy.no_target_tenant_classifications must be exactly "
            "the closed no-target classification set"
        )
    for classification, expected in REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS.items():
        entry = classifications.get(classification)
        label = (
            "tenant_generation_policy.no_target_tenant_classifications."
            f"{classification}"
        )
        if not isinstance(entry, dict):
            errors.append(f"{label} must be a mapping")
            continue
        if entry.get("target_tenant_generation") is not expected["target_tenant_generation"]:
            errors.append(f"{label} must set target_tenant_generation=false")
        required_checks = set(
            string_list(entry.get("required_authority"), f"{label}.required_authority", errors)
        )
        if required_checks != expected["required_live_checks"]:
            errors.append(f"{label} has the wrong required authority checks")
        justification = entry.get("contract_justification")
        if not isinstance(justification, str) or not justification.strip():
            errors.append(f"{label} must declare a bounded contract_justification")
        proof = entry.get("negative_proof")
        if not isinstance(proof, list) or not proof or any(
            not isinstance(item, str) or not item.strip() for item in proof
        ):
            errors.append(f"{label} must declare non-empty negative_proof")


def validate_tenant_generation_exception_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    for route in routes:
        if not isinstance(route, dict):
            continue
        classification = route.get("classification")
        if not isinstance(classification, str):
            continue
        expected = REQUIRED_TENANT_GENERATION_EXCEPTIONS.get(classification)
        if expected is None:
            continue
        label = f"{route.get('service')} {route.get('route')}"
        if route.get("tenant_billing_authority_generation_applies") is not False:
            errors.append(
                f"{label} must explicitly disable tenant_billing_authority_generation_applies "
                f"for {classification}"
            )
        membership_generation = route.get(
            "membership_authority_generation_applies"
        )
        if classification == "billing_safe_tenant":
            if membership_generation is not True:
                errors.append(
                    f"{label} must require membership generation for {classification}"
                )
        elif membership_generation is not False:
            errors.append(
                f"{label} must explicitly disable membership generation "
                f"for {classification}"
            )
        checks = route_live_checks(route, label, errors, live_checks_cache)
        missing = sorted(expected["required_live_checks"] - checks)
        if missing:
            errors.append(f"{label} is missing route-class authority checks: {missing}")
        if classification in {
            "cross_tenant_support_safe",
            "cross_tenant_billing_safe",
        }:
            target_checks = checks & {
                "membership",
                "membership_generation",
                "tenant_generation",
            }
            if target_checks:
                errors.append(
                    f"{label} must not require target membership or tenant "
                    f"generation checks for {classification}: {sorted(target_checks)}"
                )


def validate_tenant_generation_policy(
    document: dict[str, Any],
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    policy = document.get("tenant_generation_policy")
    if not isinstance(policy, dict) or policy.get("applies_by_default") is not True:
        errors.append("tenant_generation_policy must enable applies_by_default")
    else:
        validate_tenant_generation_allowlist(policy, errors)
        validate_no_target_tenant_classifications(policy, errors)
    validate_tenant_generation_exception_routes(routes, errors, live_checks_cache)


def validate_entitlement_contract(
    document: dict[str, Any],
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> None:
    contract = document.get("entitlement_contract")
    if not isinstance(contract, dict):
        errors.append("entitlement_contract must be a mapping")
    else:
        if contract.get("owner") != "account-service":
            errors.append("entitlement_contract.owner must be account-service")
        if contract.get("binding") != "exact_tenant_id":
            errors.append("entitlement_contract.binding must be exact_tenant_id")
        if contract.get("cross_tenant_inheritance") != "forbidden":
            errors.append("entitlement_contract.cross_tenant_inheritance must be forbidden")
        if contract.get("account_wide_fallback") != "forbidden":
            errors.append("entitlement_contract.account_wide_fallback must be forbidden")
    route = resolve_unique_route(
        routes,
        "account-service",
        "GetTenantEntitlementsForRuntime",
        errors,
        cardinality_errors,
    )
    if route is None:
        return
    if route.get("entitlement_scope") != "account_owned_tenant_bound":
        errors.append("GetTenantEntitlementsForRuntime must declare account_owned_tenant_bound entitlement_scope")
    if route.get("cross_tenant_inheritance") != "forbidden":
        errors.append("GetTenantEntitlementsForRuntime must forbid cross-tenant inheritance")


def validate_role_assurance_references(
    routes: list[Any], predicates: set[str], errors: list[str]
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict) or "role_assurance" not in route:
            continue
        assurance = route.get("role_assurance")
        if not isinstance(assurance, str) or assurance not in predicates:
            errors.append(
                f"routes[{index}] role_assurance must reference a declared predicate"
            )


def validate_generation_applicability(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            continue
        label = f"routes[{index}] {route.get('service')} {route.get('route')}"
        if "account_generation_applies" in route:
            errors.append(
                f"{label} must use account_authority_generation_applies instead of "
                "account_generation_applies"
            )
        account_generation = route.get("account_authority_generation_applies")
        if account_generation is not None and not isinstance(account_generation, bool):
            errors.append(f"{label} account_authority_generation_applies must be boolean")
        route_key_value = (route.get("service"), route.get("route"))
        checks = None
        if (
            route.get("classification") == "pending_deletion_scoped"
            or route_key_value in CONDITIONAL_OPERATOR_ROUTES
        ):
            checks = route_live_checks(route, label, errors, live_checks_cache)
        validate_pending_deletion_generation(route, label, account_generation, errors, checks)
        value = validate_membership_generation(route, label, errors)
        validate_conditional_operator_route(route, label, value, errors, checks)


def validate_profile_authority_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    for route_name in ("GetProfile", "UpdateProfile"):
        route = resolve_unique_route(routes, "account-service", route_name, errors)
        if route is None:
            continue
        label = f"account-service {route_name}"
        if route.get("auth_path") != "control_ui_plus_current_tenant_role":
            errors.append(
                f"{label} must declare auth_path control_ui_plus_current_tenant_role"
            )
        if route.get("method_policy") != "exact_declared_route":
            errors.append(f"{label} must declare method_policy exact_declared_route")
        if route.get("tenant_billing_authority_generation_applies") is not True:
            errors.append(
                f"{label} must apply tenant billing authority generation"
            )
        if route.get("membership_authority_generation_applies") is not True:
            errors.append(f"{label} must apply membership authority generation")
        checks = route_live_checks(route, label, errors, live_checks_cache)
        for required_check in ("membership", "membership_generation", "tenant_generation"):
            if required_check not in checks:
                errors.append(f"{label} must require live check {required_check}")


def validate_refresh_roles_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    grpc_route = resolve_unique_route(
        routes, "game-session-service", "RefreshRoles", errors
    )
    if grpc_route is not None:
        label = "game-session-service RefreshRoles"
        if grpc_route.get("auth_path") != "exact_mtls_workload_plus_account_operator_authorization_reference":
            errors.append(
                f"{label} must use Account-redeemed operator authorization auth_path"
            )
        if grpc_route.get("operator_authorization_reference") != "required_and_redeemed_with_account":
            errors.append(
                f"{label} must require Account operator authorization redemption"
            )
        checks = route_live_checks(grpc_route, label, errors, live_checks_cache)
        for required_check in (
            "current_operator_authorization",
            "current_session",
            "current_account_roles",
        ):
            if required_check not in checks:
                errors.append(f"{label} must require live check {required_check}")
        required_fields = set(
            string_list(grpc_route.get("required_fields"), f"{label} required_fields", errors)
        )
        if "mutation_digest" not in required_fields:
            errors.append(f"{label} must require mutation_digest for idempotency")
        canonical_errors = grpc_route.get("canonical_errors", {})
        any_of = canonical_errors.get("any_of") if isinstance(canonical_errors, dict) else None
        outcomes = string_list(
            any_of,
            f"{label} canonical_errors.any_of",
            errors,
        )
        if "IDEMPOTENCY_CONFLICT" not in outcomes:
            errors.append(f"{label} must declare IDEMPOTENCY_CONFLICT")

    http_route = resolve_unique_route(
        routes, "game-session-service", "POST /sessions/{sessionId}/refresh-roles", errors
    )
    if http_route is not None:
        label = "game-session-service POST /sessions/{sessionId}/refresh-roles"
        if http_route.get("operator_authorization_reference") != "account_issued_bounded_reference":
            errors.append(f"{label} must require an Account-issued operator reference")
        required_fields = set(
            string_list(http_route.get("required_fields"), f"{label} required_fields", errors)
        )
        if "mutation_digest" not in required_fields:
            errors.append(f"{label} must require mutation_digest for idempotency")
        canonical_errors = http_route.get("canonical_errors", {})
        any_of = canonical_errors.get("any_of") if isinstance(canonical_errors, dict) else None
        outcomes = string_list(any_of, f"{label} canonical_errors.any_of", errors)
        if "IDEMPOTENCY_CONFLICT" not in outcomes:
            errors.append(f"{label} must declare IDEMPOTENCY_CONFLICT")


def validate_known_drift(value: Any, field: str, errors: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            child_field = f"{field}.{key}" if field else key
            if key == "implementation_status" and isinstance(child, dict) and "known_drift" in child:
                known_drift = child["known_drift"]
                if (
                    not isinstance(known_drift, list)
                    or not known_drift
                    or any(not isinstance(item, str) for item in known_drift)
                ):
                    errors.append(
                        f"{child_field}.known_drift must be a non-empty list of strings"
                    )
            validate_known_drift(child, child_field, errors)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            validate_known_drift(child, f"{field}[{index}]", errors)


def matching_routes(
    routes: list[Any],
    service: str,
    route_name: str,
) -> list[dict[str, Any]]:
    return [
        route
        for route in routes
        if isinstance(route, dict)
        and route.get("service") == service
        and route.get("route") == route_name
    ]


def resolve_unique_route(
    routes: list[Any],
    service: str,
    route_name: str,
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> dict[str, Any] | None:
    matches = matching_routes(routes, service, route_name)
    if len(matches) != 1:
        key = f"{service}|{route_name}"
        if cardinality_errors is None or key not in cardinality_errors:
            errors.append(f"matrix must contain exactly one {service} {route_name} route")
            if cardinality_errors is not None:
                cardinality_errors.add(key)
        return None
    return matches[0]


def applicability_value(
    route: dict[str, Any], key: str, label: str, errors: list[str]
) -> Any:
    applicability = route.get("applicability")
    if not isinstance(applicability, dict):
        return None
    if key in applicability:
        return applicability[key]
    clauses = applicability.get("all_of", [])
    if not isinstance(clauses, list):
        return None
    values = [clause[key] for clause in clauses if isinstance(clause, dict) and key in clause]
    if not values:
        return None
    if any(value != values[0] for value in values[1:]):
        errors.append(f"{label} has conflicting applicability values for {key}: {values!r}")
        return None
    return values[0]


def route_live_checks(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> set[str]:
    if live_checks_cache is not None:
        parsed_checks = live_checks_cache.get(id(route))
        if parsed_checks is not None:
            return parsed_checks
    parsed_checks = set(
        string_list(route.get("required_live_checks"), f"{label} required_live_checks", errors)
    )
    if live_checks_cache is not None:
        live_checks_cache[id(route)] = parsed_checks
    return parsed_checks


def validate_applicability(
    route: dict[str, Any], label: str, expected: dict[str, str], errors: list[str]
) -> None:
    for key, expected_value in expected.items():
        actual_value = applicability_value(route, key, label, errors)
        if actual_value != expected_value:
            errors.append(
                f"{label} must declare applicability {key}={expected_value!r}"
            )


def validate_ws_game_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    ws_routes = matching_routes(routes, "spring-cloud-gateway", "/ws/game/**")
    by_mode: dict[str, list[dict[str, Any]]] = {}
    for route in ws_routes:
        mode = applicability_value(route, "connection_mode", "/ws/game/**", errors)
        if isinstance(mode, str):
            by_mode.setdefault(mode, []).append(route)

    if len(ws_routes) != 2 or any(
        len(by_mode.get(mode, [])) != 1 for mode in ("first_party_web", "trusted_tcp_proxy")
    ):
        errors.append(
            "matrix must contain exactly one first_party_web and one trusted_tcp_proxy "
            "spring-cloud-gateway /ws/game/** route"
        )
    else:
        first_party = by_mode["first_party_web"][0]
        validate_applicability(
            first_party,
            "/ws/game/** first_party_web",
            REQUIRED_FIRST_PARTY_WS_APPLICABILITY,
            errors,
        )
        missing_first_party = sorted(
            REQUIRED_WS_GAME_CHECKS
            - route_live_checks(
                first_party,
                "/ws/game/** first_party_web",
                errors,
                live_checks_cache,
            )
        )
        if missing_first_party:
            errors.append(f"/ws/game/** is missing required live checks: {missing_first_party}")
        handshake_classes = first_party.get("handshake_error_classes", {})
        outcomes = handshake_classes.get("any_of", []) if isinstance(handshake_classes, dict) else []
        if "POLICY_PRESSURE" not in outcomes:
            errors.append("/ws/game/** handshake outcomes must include POLICY_PRESSURE")
        if first_party.get("issued_token_state") != GAMEPLAY_CONNECT_ISSUED_TOKEN_STATE:
            errors.append(
                "/ws/game/** first_party_web must declare the bounded single-use "
                "gameplay-connect issued-token-state exception"
            )
        if first_party.get("issuer_authority_generation_applies") is not False:
            errors.append(
                "/ws/game/** first_party_web must explicitly disable "
                "issuer_authority_generation_applies"
            )
        if first_party.get("account_authority_generation_applies") is not False:
            errors.append(
                "/ws/game/** first_party_web must explicitly disable "
                "account_authority_generation_applies"
            )

        trusted_proxy = by_mode["trusted_tcp_proxy"][0]
        missing_trusted_proxy = sorted(
            REQUIRED_TRUSTED_PROXY_CHECKS
            - route_live_checks(
                trusted_proxy,
                "/ws/game/** trusted_tcp_proxy",
                errors,
                live_checks_cache,
            )
        )
        if missing_trusted_proxy:
            errors.append(
                "/ws/game/** trusted_tcp_proxy is missing required live checks: "
                f"{missing_trusted_proxy}"
            )

    revoke_routes = matching_routes(
        routes,
        "spring-cloud-gateway",
        "POST /ws/game/connect-token/revoke",
    )
    if len(revoke_routes) != 1:
        errors.append(
            "matrix must contain exactly one spring-cloud-gateway "
            "POST /ws/game/connect-token/revoke route"
        )
        return
    missing_revoke = sorted(
        REQUIRED_CONNECT_TOKEN_REVOKE_CHECKS
        - route_live_checks(
            revoke_routes[0],
            "POST /ws/game/connect-token/revoke",
            errors,
            live_checks_cache,
        )
    )
    validate_applicability(
        revoke_routes[0],
        "POST /ws/game/connect-token/revoke",
        REQUIRED_REVOKE_APPLICABILITY,
        errors,
    )
    if missing_revoke:
        errors.append(
            "POST /ws/game/connect-token/revoke is missing required live checks: "
            f"{missing_revoke}"
        )


def validate_issue_connect_token(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    issue_connect_routes = matching_routes(routes, "account-service", "IssueConnectToken")
    if len(issue_connect_routes) != 1:
        errors.append("matrix must contain exactly one account-service IssueConnectToken route")
        return
    missing_checks = sorted(
        REQUIRED_ISSUE_CONNECT_TOKEN_CHECKS
        - route_live_checks(
            issue_connect_routes[0], "IssueConnectToken", errors, live_checks_cache
        )
    )
    if missing_checks:
        errors.append(f"IssueConnectToken is missing required live checks: {missing_checks}")


def validate_join_routes(routes: list[Any], errors: list[str]) -> None:
    for service, name in sorted(JOIN_ROUTES_REQUIRING_POINTER_ERROR):
        matches = matching_routes(routes, service, name)
        if len(matches) != 1:
            errors.append(f"matrix must contain exactly one {service} {name} route")
            continue
        canonical_errors = matches[0].get("canonical_errors", {})
        outcomes = canonical_errors.get("any_of", []) if isinstance(canonical_errors, dict) else []
        if "ADMISSION_POINTER_UNAVAILABLE" not in outcomes:
            errors.append(f"{service} {name} must declare ADMISSION_POINTER_UNAVAILABLE")


def validate_delegated_entitlements(
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    entitlement_route = resolve_unique_route(
        routes,
        "account-service",
        "GetTenantEntitlementsForRuntime",
        errors,
        cardinality_errors,
    )
    if entitlement_route is None:
        return
    caller_policies = entitlement_route.get("caller_policies")
    if not isinstance(caller_policies, list):
        errors.append(
            "GetTenantEntitlementsForRuntime caller_policies must be a list"
        )
        return
    game_session_policies = [
        policy
        for policy in caller_policies
        if isinstance(policy, dict) and policy.get("caller") == "game-session-service"
    ]
    if len(game_session_policies) != 1:
        errors.append(
            "GetTenantEntitlementsForRuntime must contain exactly one game-session-service caller policy"
        )
        return
    missing_checks = sorted(
        REQUIRED_DELEGATED_ENTITLEMENT_CHECKS
        - route_live_checks(
            game_session_policies[0],
            "GetTenantEntitlementsForRuntime game-session",
            errors,
            live_checks_cache,
        )
    )
    if missing_checks:
        errors.append(
            "GetTenantEntitlementsForRuntime game-session policy is missing "
            f"required live checks: {missing_checks}"
        )


def validate_live_check_vocabulary(
    document: dict[str, Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> set[str]:
    vocabulary = document.get("required_live_check_vocabulary")
    if not isinstance(vocabulary, list) or not vocabulary or any(
        not isinstance(item, str) for item in vocabulary
    ):
        errors.append("required_live_check_vocabulary must be a non-empty list of strings")
        allowed_checks: set[str] = set()
    else:
        allowed_checks = set(vocabulary)
        if len(allowed_checks) != len(vocabulary):
            errors.append("required_live_check_vocabulary must not contain duplicates")

    live_checks = collect_live_checks(document, "matrix", errors, live_checks_cache)
    unknown_checks = sorted(
        {
            check
            for check in live_checks
            if check not in allowed_checks
        }
    )
    if unknown_checks:
        errors.append(
            f"required_live_checks contains values outside the closed vocabulary: {unknown_checks}"
        )
    return allowed_checks


def validate_route_variants(
    routes: list[Any], allowed_classifications: set[str], errors: list[str]
) -> set[str]:
    route_variants: dict[str, list[tuple[int, Any]]] = {}
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            errors.append(f"routes[{index}] must be a mapping")
            continue
        key = route_key(route)
        if key is None:
            errors.append(f"routes[{index}] must declare string service and route")
        else:
            route_variants.setdefault(key, []).append((index, route.get("applicability")))
        classification = route.get("classification")
        if not isinstance(classification, str) or classification not in allowed_classifications:
            errors.append(
                f"routes[{index}] uses unknown classification: {classification!r}"
            )

    for key, variants in route_variants.items():
        if len(variants) == 1:
            continue
        if any(applicability is None for _, applicability in variants):
            errors.append(f"duplicate route entries require explicit applicability: {key}")
            continue
        serialized = [json.dumps(applicability, sort_keys=True) for _, applicability in variants]
        if len(serialized) != len(set(serialized)):
            errors.append(f"duplicate route applicability: {key}")

    return set(route_variants)


def validate_matrix_document(path: Path) -> tuple[list[str], set[str]]:
    errors: list[str] = []
    try:
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as error:
        return [f"cannot read matrix {path}: {error}"], set()

    if not isinstance(document, dict):
        return ["matrix root must be a mapping"], set()

    validate_auth_path_vocabulary(document, errors)
    token_profiles = validate_token_profiles(document, errors)
    role_assurance_predicates = validate_role_assurance(document, errors)
    validate_known_drift(document, "matrix", errors)
    live_checks_cache: LiveChecksCache = {}
    validate_live_check_vocabulary(document, errors, live_checks_cache)
    allowed_route_statuses = validate_route_status_vocabulary(document, errors)

    routes = document.get("routes")
    if not isinstance(routes, list):
        errors.append("routes must be a list")
        routes = []

    classifications = string_list(document.get("classifications"), "classifications", errors)
    route_keys = validate_route_variants(routes, set(classifications), errors)
    validate_route_statuses(routes, allowed_route_statuses, errors)
    validate_required_fields(routes, errors)
    validate_generation_applicability(routes, errors, live_checks_cache)
    validate_profile_authority_routes(routes, errors, live_checks_cache)
    validate_refresh_roles_routes(routes, errors, live_checks_cache)
    validate_receiver_predicates(routes, token_profiles, errors)
    validate_role_assurance_references(routes, role_assurance_predicates, errors)
    validate_tenant_generation_policy(document, routes, errors, live_checks_cache)
    cardinality_errors: set[str] = set()
    validate_entitlement_contract(document, routes, errors, cardinality_errors)

    validate_ws_game_routes(routes, errors, live_checks_cache)
    validate_issue_connect_token(routes, errors, live_checks_cache)
    validate_join_routes(routes, errors)
    validate_delegated_entitlements(
        routes, errors, cardinality_errors, live_checks_cache
    )

    return errors, route_keys


def validate(matrix_path: Path = DEFAULT_MATRIX) -> list[str]:
    errors, _ = validate_matrix_document(matrix_path)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, default=DEFAULT_MATRIX)
    args = parser.parse_args()

    errors = validate(args.matrix)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("authorization route matrix validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
