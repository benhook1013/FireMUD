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
DEFAULT_MATRIX = (
    ROOT / "design/architecture/system-architecture-authz-route-matrix.yaml"
)
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
REQUIRED_JOIN_PRE_MEMBERSHIP_CHECKS = {
    "public_production_visibility",
    "public_production_admission",
    "runtime_entitlements",
    "admission_pointer",
    "idempotency",
}
REQUIRED_DELEGATED_ENTITLEMENT_CHECKS = {
    "conditional_realm_access_grant",
    "grant_version",
    "issuer_generation",
    "account_generation",
}
REQUIRED_TRUSTED_PROXY_CHECKS = {"trusted_proxy_identity"}
REQUIRED_CONNECT_TOKEN_REVOKE_CHECKS = {"browser_origin", "csrf"}
REQUIRED_DOWNSTREAM_ADMISSION_CHECKS = {
    "membership",
    "membership_generation",
    "public_production_admission",
    "realm_visibility",
    "conditional_realm_access_grant",
    "runtime_entitlements",
    "admission_pointer",
}
REQUIRED_FIRST_PARTY_WS_APPLICABILITY = {
    "connection_mode": "first_party_web",
    "operation": "websocket_upgrade",
}
REQUIRED_REVOKE_APPLICABILITY = {
    "connection_mode": "first_party_web",
    "operation": "connect_token_cookie_revoke",
}
REQUIRED_REVOKE_GENERATION_APPLICABILITY = {
    "tenant_billing_authority_generation_applies": False,
    "membership_authority_generation_applies": False,
}
MEMBERSHIP_WRITER_ROUTE = (
    "account-service",
    "EnsurePublicProductionPlayerMembership",
)
REQUIRED_MEMBERSHIP_WRITER_CHECKS = {
    "account_state_bootstrap_eligible",
    "pending_deletion_state",
}
GAMEPLAY_CONNECT_ISSUED_TOKEN_STATE = "none_bounded_single_use_replay_exception"
EXPLICIT_NO_JWT_ROUTES = {
    ("game-session-service", "LOGIN"),
    ("game-session-service", "LOGON"),
    ("game-session-service", "WORLDS_PUBLIC"),
    ("account-service", "AuthLogin"),
    ("account-service", "PlayerBootstrapLogin"),
    ("account-service", "POST /auth/pending-deletion/recovery/challenge"),
    ("account-service", "POST /auth/pending-deletion/recovery"),
}
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
ADMISSION_POINTER_MUTATION_ROUTES = {
    ("logging-admin-service", "POST /admission-pointers"),
    ("logging-admin-service", "POST /admission-pointers/cutover"),
}
GAME_SESSION_OPERATOR_ROUTES = {
    ("game-session-service", "POST /sessions"),
    ("game-session-service", "POST /sessions/{sessionId}/stop"),
    ("game-session-service", "POST /sessions/{sessionId}/restart"),
    ("game-session-service", "POST /sessions/{sessionId}/refresh-roles"),
}
REQUIRED_SESSION_LIFECYCLE_GATE_ROUTES = {
    f"{service}/{route}" for service, route in GAME_SESSION_OPERATOR_ROUTES
}
CONDITIONAL_OPERATOR_ROUTES = OPERATOR_INGRESS_ROUTES | GAME_SESSION_OPERATOR_ROUTES
ACCOUNT_SUBJECT_BOUND_ROUTES = {
    ("account-service", "ExportAccount"),
    ("account-service", "DeleteAccount"),
}
OPERATOR_AUTHORIZATION_BRANCHES = {
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
ACCOUNT_AUTHORIZATION_BRANCHES = {
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
REQUIRED_ROLE_ASSURANCE_ROLES = {"platformAdmin", "billingAdmin", "support"}
REQUIRED_TENANT_GENERATION_EXCEPTIONS = {
    "billing_safe_tenant": {
        "target_tenant_generation": False,
        "required_live_checks": {
            "issuer_generation",
            "account_generation",
            "membership_generation",
            "membership",
            "current_operator_roles",
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
    "public": {
        "target_tenant_generation": False,
        "generation_behavior": "no_tenant_authority",
        "required_live_checks": set(),
        "target_tenant_generation_advance_behavior": "remains_valid",
    },
    "account_scoped": {
        "target_tenant_generation": False,
        "generation_behavior": "issuer_and_account_authority_only",
        "required_live_checks": {"issuer_generation", "account_generation"},
        "target_tenant_generation_advance_behavior": "remains_valid",
    },
    "caller_membership_scoped": {
        "target_tenant_generation": False,
        "generation_behavior": "caller_membership_authority_only",
        "required_live_checks": {"membership", "membership_generation"},
        "target_tenant_generation_advance_behavior": "remains_valid",
    },
    "player_bootstrap_tenant": {
        "target_tenant_generation": False,
        "generation_behavior": "membership_authority_when_route_requires_existing_membership",
        "required_live_checks": {"membership", "membership_generation"},
        "target_tenant_generation_advance_behavior": "remains_valid",
    },
    "pre_tenant_discovery": {
        "target_tenant_generation": False,
        "generation_behavior": "no_tenant_or_membership_authority",
        "required_live_checks": set(),
        "target_tenant_generation_advance_behavior": "remains_valid",
    },
    "public_production_onboarding": {
        "target_tenant_generation": False,
        "generation_behavior": "membership_authority_after_membership_exists",
        "required_live_checks": {"membership", "membership_generation"},
        "target_tenant_generation_advance_behavior": "remains_valid",
    },
    "internal_workload": {
        "target_tenant_generation": False,
        "generation_behavior": "route_declared_caller_and_target_authority",
        "required_live_checks": set(),
        "target_tenant_generation_advance_behavior": "route_declared",
    },
    "pending_deletion_scoped": {
        "target_tenant_generation": False,
        "generation_behavior": "pending_deletion_credential_only",
        "required_live_checks": {
            "pending_deletion_state",
            "pending_deletion_credential_registry",
        },
        "target_tenant_generation_advance_behavior": (
            "denied_by_pending_deletion_credential_contract"
        ),
    },
}
REQUIRED_TENANT_AUTHORITY_CLASSIFICATIONS = {
    "tenant_regular",
    "cross_tenant_data_bearing",
}
NO_TARGET_TENANT_CLASSES_WITHOUT_ROUTE_SPECIFIC_TARGET_AUTHORITY = {
    "public",
    "account_scoped",
    "caller_membership_scoped",
    "player_bootstrap_tenant",
    "pre_tenant_discovery",
    "public_production_onboarding",
}
PRIVILEGED_OPERATOR_ROLE_ASSURANCE = "privileged_control_when_global_role"
PRIVILEGED_CONTROL_VALUES = {"required", "not_required", "establishes_window"}
AUTHORITY_GENERATION_VALUES = {"required", "omitted", "target_tenant_generation"}
PLATFORM_ADMIN_ROLE_ASSURANCE_ROUTE_IDENTITIES = {
    "account-service/IssueHumanOperatorAuthorizationReference",
}
OPERATOR_REFERENCE_ISSUANCE_REQUIRED_FIELDS = {
    ("account-service", "IssueHumanOperatorAuthorizationReference"): {
        "tenant_scope",
        "action_family",
        "action_family_schema_id",
        "action_family_schema_version",
        "control_plane_request_id",
        "mutation_digest",
    },
    ("account-service", "IssueAutomationOperatorAuthorizationReference"): {
        "automation_policy_id",
        "automation_policy_version",
        "tenant_scope",
        "action_family",
        "action_family_schema_id",
        "action_family_schema_version",
        "control_plane_request_id",
        "mutation_digest",
    },
}
AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE"
UNAVAILABLE_AUTHORITY_ERROR_ALIASES = {
    "ENTITLEMENT_UNAVAILABLE",
    "MEMBERSHIP_AUTH_UNAVAILABLE",
}
AUTH_UNAVAILABLE_REQUIRED_ROUTES = {
    ("account-service", "IssueConnectToken"),
    ("account-service", "CommitTenantCapacityAdmission"),
    ("account-service", "BillingArtifactsTenant"),
    ("account-service", "BillingArtifactsCrossTenant"),
}
LOGGING_ADMIN_IDEMPOTENT_OPERATOR_ROUTES = {
    ("logging-admin-service", "POST /admission-pointers"),
    ("logging-admin-service", "POST /admission-pointers/cutover"),
    ("logging-admin-service", "POST /admission-pointers/version-upgrades"),
    ("logging-admin-service", "POST /feature-flags/toggle"),
    ("logging-admin-service", "POST /moderation/actions"),
    ("logging-admin-service", "POST /tick-remediation/pause"),
    ("logging-admin-service", "POST /tick-remediation/resume"),
}
EXPECTED_ROUTE_CLASS_BRANCHES = {
    ("account_scoped", "platformAdmin_global"): {
        "scope": "account",
        "role": "platformAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
    ("tenant_regular", "tenant_role"): {
        "scope": "tenant",
        "role": "route_declared_tenant_role",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "required",
            "membership": "required",
        },
        "privileged_control": "not_required",
    },
    ("tenant_regular", "platformAdmin_global"): {
        "scope": "tenant",
        "role": "platformAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "target_tenant_generation",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
    ("cross_tenant_data_bearing", "platformAdmin_global"): {
        "scope": "cross_tenant",
        "role": "platformAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "target_tenant_generation",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
    ("billing_safe_tenant", "tenantAdmin"): {
        "scope": "tenant",
        "role": "tenantAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "required",
        },
        "privileged_control": "not_required",
    },
    ("cross_tenant_support_safe", "support_global"): {
        "scope": "cross_tenant",
        "role": "support",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "omitted",
        },
        "privileged_control": "not_required",
    },
    ("cross_tenant_support_safe", "platformAdmin_global"): {
        "scope": "cross_tenant",
        "role": "platformAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
    ("cross_tenant_billing_safe", "billingAdmin_global"): {
        "scope": "cross_tenant",
        "role": "billingAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
    ("cross_tenant_billing_safe", "platformAdmin_global"): {
        "scope": "cross_tenant",
        "role": "platformAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
}
CANONICAL_OPERATOR_INGRESS = "logging-admin-service"
DIRECT_OWNER_ROUTE_POLICY = "deny_at_edge_and_migrate_to_logging_admin"
# These caches are created afresh for one parsed matrix document in
# validate_matrix_document. Do not reuse them across validation calls: their object
# identity keys and cached structural errors are meaningful only within that document.
LiveChecksCache = dict[tuple[int, str], tuple[object, set[str]]]
RequiredFieldsCache = dict[tuple[int, str], tuple[object, list[str] | None]]


def canonical_route_components(service: Any, route: Any) -> tuple[str, str] | None:
    if (
        not isinstance(service, str)
        or not service.strip()
        or not isinstance(route, str)
        or not route.strip()
    ):
        return None
    return service.strip(), route.strip()


def route_set_key(route: dict[str, Any]) -> tuple[str, str] | None:
    return canonical_route_components(route.get("service"), route.get("route"))


def route_identity_from_route(route: dict[str, Any]) -> str | None:
    components = canonical_route_components(route.get("service"), route.get("route"))
    if components is None:
        return None
    service, route_name = components
    return f"{service}/{route_name}"


def route_key(route: dict[str, Any]) -> str | None:
    components = canonical_route_components(route.get("service"), route.get("route"))
    if components is None:
        return None
    service, name = components
    return f"{service}|{name}"


def string_list(value: Any, field: str, errors: list[str]) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        errors.append(f"{field} must be a list of strings")
        return []
    return value


def cached_live_checks(
    source: object,
    value: Any,
    field: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cache_field: str | None = None,
) -> set[str]:
    semantic_field = cache_field or field
    if live_checks_cache is not None:
        cache_key = (id(source), semantic_field)
        cached = live_checks_cache.get(cache_key)
        if cached is not None and cached[0] is source:
            return set(cached[1])
    parsed_checks = set(string_list(value, field, errors))
    if live_checks_cache is not None:
        live_checks_cache[(id(source), semantic_field)] = (source, set(parsed_checks))
    return set(parsed_checks)


def cached_required_fields(
    source: object,
    value: Any,
    field: str,
    errors: list[str],
    required_fields_cache: RequiredFieldsCache | None = None,
    cache_field: str | None = None,
) -> list[str]:
    semantic_field = cache_field or field
    if required_fields_cache is not None:
        cache_key = (id(source), semantic_field)
        cached = required_fields_cache.get(cache_key)
        if cached is not None and cached[0] is source:
            return list(cached[1] or [])
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        errors.append(f"{field} must be a list of strings")
        parsed_fields: list[str] | None = None
    else:
        parsed_fields = list(value)
    if required_fields_cache is not None:
        required_fields_cache[(id(source), semantic_field)] = (source, parsed_fields)
    return list(parsed_fields or [])


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
                checks.extend(
                    cached_live_checks(
                        value,
                        child,
                        child_field,
                        errors,
                        live_checks_cache,
                        "required_live_checks",
                    )
                )
            else:
                checks.extend(
                    collect_live_checks(child, child_field, errors, live_checks_cache)
                )
    elif isinstance(value, list):
        for index, child in enumerate(value):
            checks.extend(
                collect_live_checks(
                    child, f"{field}[{index}]", errors, live_checks_cache
                )
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


def validate_auth_path_vocabulary(
    document: dict[str, Any], errors: list[str]
) -> set[str]:
    vocabulary = document.get("auth_path_vocabulary")
    if (
        not isinstance(vocabulary, list)
        or not vocabulary
        or any(not isinstance(item, str) for item in vocabulary)
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


def validate_token_profiles(
    document: dict[str, Any], errors: list[str]
) -> dict[str, dict[str, str]]:
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
                errors.append(
                    f"token_profiles must not contain duplicate profile {profile!r}"
                )
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
    if not raw_assurance:
        errors.append("role_assurance must be a non-empty mapping")

    predicates: set[str] = set()
    for name, definition in raw_assurance.items():
        if not isinstance(name, str) or not name.strip():
            errors.append("role_assurance keys must be non-empty strings")
            continue
        if name == "vocabulary":
            errors.append(
                "role_assurance must use one canonical predicate mapping; vocabulary is not supported"
            )
            continue
        if not isinstance(definition, dict):
            errors.append(f"role_assurance.{name} must be a mapping")
            continue
        predicates.add(name)
    predicate = raw_assurance.get("privileged_control_when_global_role")
    if not isinstance(predicate, dict):
        errors.append(
            "role_assurance.privileged_control_when_global_role must be a mapping"
        )
        return predicates
    requirements = predicate.get("requirements")
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
        for item in (
            raw_classifications if isinstance(raw_classifications, list) else []
        )
        if isinstance(item, str)
    }
    for role in sorted(REQUIRED_ROLE_ASSURANCE_ROLES):
        requirement = requirements.get(role)
        label = (
            f"role_assurance.privileged_control_when_global_role.requirements.{role}"
        )
        if not isinstance(requirement, dict):
            errors.append(f"{label} must be a mapping")
            continue
        legacy_keys = {"when", "when_scopes", "allowed_classifications"} & set(
            requirement
        )
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
        route_identities = applies_to.get("route_identities")
        canonical_route_identities: list[str] | None = None
        if route_identities is not None and (
            not isinstance(route_identities, list)
            or any(
                not isinstance(item, str) or not item.strip()
                for item in route_identities
            )
        ):
            errors.append(f"{label}.applies_to.route_identities must be a list of strings")
            continue
        if route_identities is not None:
            canonical_route_identities = []
            for index, identity in enumerate(route_identities):
                service, separator, route_name = identity.partition("/")
                components = (
                    canonical_route_components(service, route_name)
                    if separator
                    else None
                )
                if components is None:
                    errors.append(
                        f"{label}.applies_to.route_identities[{index}] must be a "
                        "non-empty service/route identity"
                    )
                    continue
                canonical_route_identities.append("/".join(components))
        if role == "platformAdmin":
            expected_route_identities = set(
                PLATFORM_ADMIN_ROLE_ASSURANCE_ROUTE_IDENTITIES
            )
            if (
                canonical_route_identities is None
                or len(canonical_route_identities)
                != len(set(canonical_route_identities))
                or set(canonical_route_identities) != expected_route_identities
            ):
                errors.append(
                    f"{label}.applies_to.route_identities must equal "
                    f"{sorted(expected_route_identities)}"
                )
        elif route_identities is not None:
            errors.append(
                f"{label}.applies_to.route_identities is only allowed for platformAdmin"
            )
    return predicates


def validate_route_status_vocabulary(
    document: dict[str, Any], errors: list[str]
) -> set[str]:
    vocabulary = document.get("route_status_vocabulary")
    if (
        not isinstance(vocabulary, list)
        or not vocabulary
        or any(not isinstance(item, str) for item in vocabulary)
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
        if (
            isinstance(implementation_status, dict)
            and "target_only" in implementation_status
        ):
            errors.append(
                f"routes[{index}] must use route_status instead of implementation_status.target_only"
            )


def validate_required_fields(
    routes: list[Any],
    errors: list[str],
    required_fields_cache: RequiredFieldsCache | None = None,
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict) or "required_fields" not in route:
            continue
        fields = cached_required_fields(
            route,
            route.get("required_fields"),
            f"routes[{index}] required_fields",
            errors,
            required_fields_cache,
            "required_fields",
        )
        invalid_fields = [
            field for field in fields if not REQUIRED_FIELD_PATTERN.fullmatch(field)
        ]
        if invalid_fields:
            errors.append(
                f"routes[{index}] required_fields must use snake_case: {invalid_fields}"
            )


def validate_route_class_branch_table(
    document: dict[str, Any], errors: list[str]
) -> None:
    raw_table = document.get("route_class_branch_table")
    if not isinstance(raw_table, list):
        errors.append("route_class_branch_table must be a list of mappings")
        return

    actual: dict[tuple[str, str], dict[str, Any]] = {}
    for index, entry in enumerate(raw_table):
        label = f"route_class_branch_table[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{label} must be a mapping")
            continue
        classification = entry.get("classification")
        branch = entry.get("branch")
        if not isinstance(classification, str):
            errors.append(f"{label}.classification must be a string")
        if not isinstance(branch, str):
            errors.append(f"{label}.branch must be a string")
        if not isinstance(classification, str) or not isinstance(branch, str):
            continue
        key = (classification, branch)
        if key in actual:
            errors.append(f"{label} duplicates route-class branch {key!r}")
        else:
            actual[key] = entry

    expected_keys = set(EXPECTED_ROUTE_CLASS_BRANCHES)
    if set(actual) != expected_keys:
        errors.append(
            "route_class_branch_table must contain exactly the canonical route-class "
            f"branches: {sorted(expected_keys)!r}"
        )

    for key, expected in EXPECTED_ROUTE_CLASS_BRANCHES.items():
        entry = actual.get(key)
        if entry is None:
            continue
        label = f"route_class_branch_table {key[0]} {key[1]}"
        for field in ("scope", "role"):
            if entry.get(field) != expected[field]:
                errors.append(f"{label} must declare {field}={expected[field]!r}")
        privileged_control = entry.get("privileged_control")
        if privileged_control not in PRIVILEGED_CONTROL_VALUES:
            errors.append(
                f"{label}.privileged_control must be one of "
                f"{sorted(PRIVILEGED_CONTROL_VALUES)}"
            )
        elif privileged_control != expected["privileged_control"]:
            errors.append(
                f"{label} must declare "
                f"privileged_control={expected['privileged_control']!r}"
            )
        generations = entry.get("generations")
        if not isinstance(generations, dict):
            errors.append(f"{label}.generations must be a mapping")
            continue
        if set(generations) != set(expected["generations"]):
            errors.append(
                f"{label}.generations must declare exactly issuer/account/tenant/membership"
            )
            continue
        for generation, expected_value in expected["generations"].items():
            actual_value = generations.get(generation)
            if actual_value not in AUTHORITY_GENERATION_VALUES:
                errors.append(
                    f"{label}.generations.{generation} must be one of "
                    f"{sorted(AUTHORITY_GENERATION_VALUES)}"
                )
            elif actual_value != expected_value:
                errors.append(
                    f"{label}.generations.{generation} must be {expected_value!r}"
                )


def validate_membership_policy(
    document: dict[str, Any],
    errors: list[str],
) -> None:
    policy = document.get("tenant_membership_policy")
    if not isinstance(policy, dict):
        errors.append("tenant_membership_policy must be a mapping")
        return
    if policy.get("tenant_owned_writes_require_existing_membership") is not True:
        errors.append(
            "tenant_membership_policy must require existing membership for tenant-owned writes"
        )
    exception = policy.get("public_production_join_exception")
    if not isinstance(exception, dict):
        errors.append(
            "tenant_membership_policy.public_production_join_exception must be a mapping"
        )
        return
    if exception.get("classification") != "public_production_onboarding":
        errors.append(
            "tenant_membership_policy public-production exception must use "
            "public_production_onboarding"
        )
    if exception.get("membership_creation") != "caller_bound_after_validation":
        errors.append(
            "tenant_membership_policy public-production exception must create "
            "caller-bound membership after validation"
        )
    checks = set(
        string_list(
            exception.get("required_pre_membership_checks"),
            "tenant_membership_policy.public_production_join_exception.required_pre_membership_checks",
            errors,
        )
    )
    if checks != REQUIRED_JOIN_PRE_MEMBERSHIP_CHECKS:
        errors.append(
            "tenant_membership_policy public-production exception has the wrong "
            "pre-membership checks"
        )
    raw_routes = exception.get("routes")
    expected_routes = set(JOIN_ROUTES_REQUIRING_POINTER_ERROR)
    actual_routes: set[tuple[str, str]] = set()
    if not isinstance(raw_routes, list):
        errors.append(
            "tenant_membership_policy.public_production_join_exception.routes "
            "must be a list of two-item lists of strings"
        )
    else:
        for index, item in enumerate(raw_routes):
            if (
                not isinstance(item, list)
                or len(item) != 2
                or any(not isinstance(value, str) for value in item)
            ):
                errors.append(
                    "tenant_membership_policy.public_production_join_exception.routes["
                    f"{index}] must be a two-item list of strings"
                )
                continue
            actual_routes.add((item[0], item[1]))
    if actual_routes != expected_routes:
        errors.append(
            "tenant_membership_policy public-production exception must enumerate "
            f"exactly {sorted(expected_routes)!r}"
        )


def validate_authority_evidence_policy(
    document: dict[str, Any], errors: list[str]
) -> None:
    policy = document.get("authority_evidence_policy")
    if not isinstance(policy, dict):
        errors.append("authority_evidence_policy must be a mapping")
        return
    fresh = policy.get("fail_closed_fresh_evidence")
    if not isinstance(fresh, dict):
        errors.append(
            "authority_evidence_policy.fail_closed_fresh_evidence must be a mapping"
        )
    else:
        applies_to = string_list(
            fresh.get("applies_to"),
            "authority_evidence_policy.fail_closed_fresh_evidence.applies_to",
            errors,
        )
        if set(applies_to) != {
            "admission",
            "play",
            "renewal",
            "reconnect",
            "resume",
            "protected_control_plane_mutation",
        }:
            errors.append(
                "authority_evidence_policy fresh evidence must cover admission, PLAY, "
                "renewal, reconnect, resume, and protected control-plane mutations"
            )
        if fresh.get("unreachable_or_timeout") != "AUTH_UNAVAILABLE":
            errors.append(
                "authority_evidence_policy unreachable or timed-out evidence "
                "must fail closed with AUTH_UNAVAILABLE"
            )
        if fresh.get("reachable_invalid_or_ambiguous") != "AUTH_SESSION_REVOKED":
            errors.append(
                "authority_evidence_policy reachable invalid or ambiguous evidence "
                "must fail closed with AUTH_SESSION_REVOKED"
            )
    bound = policy.get("bound_ordinary_gameplay")
    if not isinstance(bound, dict):
        errors.append(
            "authority_evidence_policy.bound_ordinary_gameplay must be a mapping"
        )
    else:
        if bound.get("applies_to") != "already_bound_instance_runtime":
            errors.append(
                "authority_evidence_policy bound gameplay must be already_bound_instance_runtime"
            )
        if bound.get("pointer_authority_reread") is not False:
            errors.append(
                "authority_evidence_policy bound gameplay must not reread pointer authority"
            )
        required_fences = string_list(
            bound.get("required_fences"),
            "authority_evidence_policy.bound_ordinary_gameplay.required_fences",
            errors,
        )
        if set(required_fences) != {"bound_game_instance", "runtime_fence"}:
            errors.append(
                "authority_evidence_policy bound gameplay must require bound_game_instance and runtime_fence"
            )


def validate_elevation_bootstrap(
    document: dict[str, Any],
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> None:
    contracts = document.get("elevation_contracts")
    privileged = (
        contracts.get("privileged_control") if isinstance(contracts, dict) else None
    )
    bootstrap = (
        privileged.get("bootstrap_exemption") if isinstance(privileged, dict) else None
    )
    expected_route = "account-service/EnterPrivilegedControlWindow"
    if not isinstance(bootstrap, dict):
        errors.append(
            "elevation_contracts.privileged_control.bootstrap_exemption must be a mapping"
        )
    else:
        if bootstrap.get("route") != expected_route:
            errors.append(
                "privileged_control bootstrap exemption must name EnterPrivilegedControlWindow"
            )
        if bootstrap.get("privileged_control") != "establishes_window":
            errors.append(
                "privileged_control bootstrap exemption must establish the window"
            )
        if bootstrap.get("requires_existing_window") is not False:
            errors.append(
                "privileged_control bootstrap exemption must not require an existing window"
            )

    route = resolve_unique_route(
        routes,
        "account-service",
        "EnterPrivilegedControlWindow",
        errors,
        cardinality_errors,
    )
    if route is not None:
        privileged_control = route.get("privileged_control")
        if "privileged_control" not in route or (
            privileged_control in PRIVILEGED_CONTROL_VALUES
            and privileged_control != "establishes_window"
        ):
            errors.append(
                "account-service EnterPrivilegedControlWindow must declare "
                "privileged_control=establishes_window"
            )
    for candidate_route in routes:
        if (
            not isinstance(candidate_route, dict)
            or "privileged_control" not in candidate_route
        ):
            continue
        if candidate_route.get("privileged_control") not in PRIVILEGED_CONTROL_VALUES:
            errors.append(
                f"{candidate_route.get('service')} {candidate_route.get('route')} "
                "privileged_control must be one of "
                f"{sorted(PRIVILEGED_CONTROL_VALUES)}"
            )


def validate_multi_profile_predicates(
    entry: dict[str, Any],
    label: str,
    profiles: list[str],
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
) -> None:
    if entry.get("token_audience") is not None:
        errors.append(
            f"{label} multi-profile routes must not declare scalar token_audience; "
            "use accepted_token_profile_audiences"
        )
    token_type = entry.get("token_type")
    token_issuer = entry.get("token_issuer")
    known_profiles = [token_profiles.get(profile_name) for profile_name in profiles]
    if not all(profile is not None for profile in known_profiles):
        return
    type_issuer_pairs = {
        (profile.get("type"), profile.get("issuer"))
        for profile in known_profiles
        if profile is not None
    }
    if len(type_issuer_pairs) > 1:
        for field, profile_key in (
            ("accepted_token_profile_types", "type"),
            ("accepted_token_profile_issuers", "issuer"),
        ):
            predicate_map = entry.get(field)
            if not isinstance(predicate_map, dict):
                errors.append(
                    f"{label} differing multi-profile predicates require {field}"
                )
                continue
            if set(predicate_map) != set(profiles):
                errors.append(
                    f"{label} {field} keys must equal accepted token profiles"
                )
                continue
            for profile_name in profiles:
                expected = token_profiles[profile_name].get(profile_key)
                if predicate_map.get(profile_name) != expected:
                    errors.append(
                        f"{label} {field} for {profile_name!r} must match token_profiles"
                    )
        if token_type is not None or token_issuer is not None:
            errors.append(
                f"{label} differing multi-profile predicates must use per-profile type/issuer maps"
            )
        return
    if token_type is None or token_issuer is None:
        errors.append(
            f"{label} multi-profile routes must declare token_type/token_issuer"
        )
        return

    token_predicates = (token_type, token_issuer)
    if token_predicates != next(iter(type_issuer_pairs)):
        errors.append(
            f"{label} multi-profile token predicates must match the shared token_type/token_issuer"
        )


def validate_pending_deletion_generation(
    route: dict[str, Any],
    label: str,
    account_generation: Any,
    errors: list[str],
    checks: set[str] | None = None,
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    if route.get("classification") != "pending_deletion_scoped":
        return
    if route.get("accepted_token_profiles") != []:
        errors.append(
            f"{label} pending_deletion_scoped routes must set "
            "accepted_token_profiles=[]"
        )
    if route.get("accepted_credentials") != ["pending-deletion-access"]:
        errors.append(
            f"{label} pending_deletion_scoped routes must accept only "
            "pending-deletion-access"
        )
    if account_generation is not False:
        errors.append(
            f"{label} pending_deletion_scoped routes must set "
            "account_authority_generation_applies=false"
        )
    if route.get("issuer_authority_generation_applies") is not False:
        errors.append(
            f"{label} pending_deletion_scoped routes must set "
            "issuer_authority_generation_applies=false"
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
        checks = route_live_checks(route, label, errors, live_checks_cache)
    missing = (
        REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS["pending_deletion_scoped"][
            "required_live_checks"
        ]
        - checks
    )
    if missing:
        errors.append(
            f"{label} is missing no-target authority checks: {sorted(missing)}"
        )
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


def operator_authorization_branch_checks(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> dict[str, list[set[str]]]:
    raw_branches = route.get("operator_authorization_branches")
    if not isinstance(raw_branches, list):
        errors.append(
            f"{label} operator_authorization_branches must be a list of mappings"
        )
        return {}

    branches: dict[str, list[set[str]]] = {}
    for index, raw_branch in enumerate(raw_branches):
        branch_label = f"{label} operator_authorization_branches[{index}]"
        if not isinstance(raw_branch, dict):
            errors.append(f"{branch_label} must be a mapping")
            continue
        branch = raw_branch.get("branch")
        if not isinstance(branch, str) or not branch.strip():
            errors.append(f"{branch_label}.branch must be a non-empty string")
            continue
        if branch not in OPERATOR_AUTHORIZATION_BRANCHES:
            errors.append(
                f"{branch_label}.branch must be one of "
                f"{sorted(OPERATOR_AUTHORIZATION_BRANCHES)}"
            )
        if branch in branches:
            errors.append(f"{branch_label} duplicates operator branch {branch!r}")
        checks = cached_live_checks(
            raw_branch,
            raw_branch.get("required_live_checks"),
            f"{branch_label}.required_live_checks",
            errors,
            live_checks_cache,
            "required_live_checks",
        )
        expected_checks = OPERATOR_AUTHORIZATION_BRANCHES.get(branch)
        if expected_checks is not None and checks != expected_checks:
            errors.append(
                f"{branch_label}.required_live_checks must equal "
                f"{sorted(expected_checks)}"
            )
        branches.setdefault(branch, []).append(checks)

    expected_branches = set(OPERATOR_AUTHORIZATION_BRANCHES)
    if set(branches) != expected_branches:
        errors.append(
            f"{label} operator_authorization_branches must contain exactly "
            f"{sorted(expected_branches)}"
        )
    return branches


def validate_conditional_operator_route(
    route: dict[str, Any],
    label: str,
    value: Any,
    errors: list[str],
    checks: set[str] | None = None,
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    route_key_value = route_set_key(route)
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
        checks = route_live_checks(route, label, errors, live_checks_cache)
    branch_checks = operator_authorization_branch_checks(
        route, label, errors, live_checks_cache
    )
    tenant_branch_checks = set().union(*branch_checks.get("tenant_role", []))
    platform_admin_branch_checks = set().union(
        *branch_checks.get("platformAdmin_global", [])
    )
    branch_only_checks = set().union(*OPERATOR_AUTHORIZATION_BRANCHES.values())
    duplicated_checks = sorted(checks & branch_only_checks)
    if duplicated_checks:
        errors.append(
            f"{label} required_live_checks must not duplicate branch-qualified "
            f"checks: {duplicated_checks}"
        )
    if "membership_when_tenant_role" not in tenant_branch_checks:
        errors.append(
            f"{label} tenant-role branch must require membership_when_tenant_role"
        )
    if "membership_generation" not in tenant_branch_checks:
        errors.append(f"{label} tenant-role branch must require membership_generation")
    if "tenant_generation" not in tenant_branch_checks:
        errors.append(f"{label} operator route must require tenant_generation")
    if "target_tenant_generation" not in platform_admin_branch_checks:
        errors.append(f"{label} operator route must require target_tenant_generation")
    if (
        route.get("global_platform_admin_reference_generation_binding")
        != "target_tenant_generation"
    ):
        errors.append(
            f"{label} must bind global platformAdmin operations to target_tenant_generation"
        )
    if route.get("role_assurance") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE:
        errors.append(
            f"{label} operator route must declare role_assurance "
            f"{PRIVILEGED_OPERATOR_ROLE_ASSURANCE}"
        )
    for required_check in ("current_global_role", "role_appropriate_assurance"):
        if required_check not in platform_admin_branch_checks:
            errors.append(
                f"{label} privileged operator route must require live check "
                f"{required_check}"
            )
    if "current_operator_authorization" not in checks:
        errors.append(
            f"{label} operator route must require live check "
            "current_operator_authorization"
        )
    if (
        route_key_value in ADMISSION_POINTER_MUTATION_ROUTES
        and "expected_pointer_version" not in checks
    ):
        errors.append(
            f"{label} admission-pointer mutation must require live check "
            "expected_pointer_version"
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


def account_authorization_branch_checks(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> dict[str, list[set[str]]]:
    raw_branches = route.get("account_authorization_branches")
    if not isinstance(raw_branches, list):
        errors.append(
            f"{label} account_authorization_branches must be a list of mappings"
        )
        return {}

    branches: dict[str, list[set[str]]] = {}
    for index, raw_branch in enumerate(raw_branches):
        branch_label = f"{label} account_authorization_branches[{index}]"
        if not isinstance(raw_branch, dict):
            errors.append(f"{branch_label} must be a mapping")
            continue
        branch = raw_branch.get("branch")
        if not isinstance(branch, str) or not branch.strip():
            errors.append(f"{branch_label}.branch must be a non-empty string")
            continue
        if branch not in ACCOUNT_AUTHORIZATION_BRANCHES:
            errors.append(
                f"{branch_label}.branch must be one of "
                f"{sorted(ACCOUNT_AUTHORIZATION_BRANCHES)}"
            )
        if branch in branches:
            errors.append(f"{branch_label} duplicates account branch {branch!r}")

        expected = ACCOUNT_AUTHORIZATION_BRANCHES.get(branch)
        target_subject_binding = raw_branch.get("target_subject_binding")
        if (
            expected is not None
            and target_subject_binding != expected["target_subject_binding"]
        ):
            errors.append(
                f"{branch_label} ({branch}).target_subject_binding must be "
                f"{expected['target_subject_binding']!r}"
            )
        checks = cached_live_checks(
            raw_branch,
            raw_branch.get("required_live_checks"),
            f"{branch_label}.required_live_checks",
            errors,
            live_checks_cache,
            "required_live_checks",
        )
        if expected is not None and checks != expected["required_live_checks"]:
            errors.append(
                f"{branch_label} ({branch}).required_live_checks must equal "
                f"{sorted(expected['required_live_checks'])}"
            )
        branches.setdefault(branch, []).append(checks)

    expected_branches = set(ACCOUNT_AUTHORIZATION_BRANCHES)
    if set(branches) != expected_branches:
        errors.append(
            f"{label} account_authorization_branches must contain exactly "
            f"{sorted(expected_branches)}"
        )
    return branches


def validate_account_authorization_route(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    checks: set[str] | None = None,
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    route_key_value = route_set_key(route)
    if route_key_value not in ACCOUNT_SUBJECT_BOUND_ROUTES:
        return
    if route.get("subject_binding") != "caller_account_id":
        errors.append(
            f"{label} account routes must bind self-service to caller_account_id"
        )
    if route.get("platform_admin_override") != "platformAdmin_only":
        errors.append(
            f"{label} account routes must declare platform_admin_override platformAdmin_only"
        )
    if route.get("role_assurance") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE:
        errors.append(
            f"{label} account routes must declare role_assurance "
            f"{PRIVILEGED_OPERATOR_ROLE_ASSURANCE}"
        )
    if checks is None:
        checks = route_live_checks(route, label, errors, live_checks_cache)
    branch_checks = account_authorization_branch_checks(
        route, label, errors, live_checks_cache
    )
    branch_only_checks = set().union(
        *(
            branch["required_live_checks"]
            for branch in ACCOUNT_AUTHORIZATION_BRANCHES.values()
        )
    )
    duplicated_checks = sorted(checks & branch_only_checks)
    if duplicated_checks:
        errors.append(
            f"{label} required_live_checks must not duplicate branch-qualified "
            f"checks: {duplicated_checks}"
        )
    for branch_name, expected in ACCOUNT_AUTHORIZATION_BRANCHES.items():
        actual = set().union(*branch_checks.get(branch_name, []))
        missing = sorted(expected["required_live_checks"] - actual)
        if missing:
            errors.append(
                f"{label} {branch_name} branch is missing required live checks: "
                f"{missing}"
            )


def validate_token_fields(
    entry: dict[str, Any],
    label: str,
    profiles: list[str],
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
    reported_unknown_profiles: set[str] | None = None,
    allow_multi_profile: bool = False,
    allow_omitted_no_profile_predicates: bool = False,
) -> None:
    predicate_default = "none" if allow_omitted_no_profile_predicates else None
    token_predicates = (
        entry.get("token_type", predicate_default),
        entry.get("token_issuer", predicate_default),
        entry.get("token_audience", predicate_default),
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
                entry,
                label,
                profiles,
                token_profiles,
                errors,
            )
            return
        errors.append(
            f"{label} must declare exactly one token profile per receiver policy"
        )
        return
    profile = token_profiles.get(profiles[0])
    if profile is None:
        if (
            reported_unknown_profiles is None
            or profiles[0] not in reported_unknown_profiles
        ):
            errors.append(f"{label} uses unknown token profiles: {[profiles[0]]}")
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
    profiles = string_list(profiles_value, f"{label} accepted_token_profiles", errors)
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
            errors.append(
                f"{policy_label} must declare method_policy exact_declared_route"
            )
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
        if (
            not isinstance(values, list)
            or not values
            or any(not isinstance(item, str) or not item.strip() for item in values)
        ):
            errors.append(f"{label} {field}.any_of must be a non-empty list of strings")
    mtls_values = mtls_callers.get("any_of") if isinstance(mtls_callers, dict) else None
    mtls_values_valid = (
        isinstance(mtls_values, list)
        and bool(mtls_values)
        and all(isinstance(item, str) and item.strip() for item in mtls_values)
    )
    if mtls_values_valid and any(
        not item.startswith("spiffe://") or "/sa/" not in item for item in mtls_values
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
            if profiles_value is None or (
                isinstance(profiles_value, list)
                and all(
                    isinstance(profile_name, str)
                    for profile_name in profiles_value
                )
            ):
                validate_token_fields(
                    route,
                    f"matrix.routes[{index}]",
                    profiles,
                    token_profiles,
                    errors,
                    set(unknown_profiles),
                    allow_multi_profile=True,
                    allow_omitted_no_profile_predicates=profiles_value is None,
                )
            continue
        label = f"{route.get('service')} {route.get('route')}"
        caller_policies = route.get("caller_policies")
        if caller_policies is not None:
            validate_caller_policies(caller_policies, label, token_profiles, errors)
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


def validate_explicit_no_jwt_routes(routes: list[Any], errors: list[str]) -> None:
    for route in routes:
        if not isinstance(route, dict):
            continue
        key = route_set_key(route)
        if key not in EXPLICIT_NO_JWT_ROUTES:
            continue
        label = f"{route.get('service')} {route.get('route')}"
        if route.get("accepted_token_profiles") != []:
            errors.append(f"{label} must explicitly declare accepted_token_profiles=[]")
        for field in ("token_type", "token_issuer", "token_audience"):
            if route.get(field) != "none":
                errors.append(f"{label} must explicitly declare {field}=none")


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
        if (
            entry.get("target_tenant_generation")
            is not expected["target_tenant_generation"]
        ):
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
        if (
            classification
            in (
                "cross_tenant_support_safe",
                "cross_tenant_billing_safe",
            )
            and entry.get("role_assurance_policy") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE
        ):
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
        if (
            entry.get("target_tenant_generation")
            is not expected["target_tenant_generation"]
        ):
            errors.append(f"{label} must set target_tenant_generation=false")
        if entry.get("generation_behavior") != expected["generation_behavior"]:
            errors.append(f"{label} has the wrong generation_behavior")
        required_checks = set(
            string_list(
                entry.get("required_authority"), f"{label}.required_authority", errors
            )
        )
        if required_checks != expected["required_live_checks"]:
            errors.append(f"{label} has the wrong required authority checks")
        justification = entry.get("contract_justification")
        if not isinstance(justification, str) or not justification.strip():
            errors.append(f"{label} must declare a bounded contract_justification")
        if (
            entry.get("target_tenant_generation_advance_behavior")
            != expected["target_tenant_generation_advance_behavior"]
        ):
            errors.append(
                f"{label} has the wrong target_tenant_generation_advance_behavior"
            )
        if classification == "pending_deletion_scoped":
            proof_contract = entry.get("negative_proof")
            proof = (
                proof_contract.get("required")
                if isinstance(proof_contract, dict)
                else None
            )
            if (
                not isinstance(proof, list)
                or not proof
                or any(not isinstance(item, str) or not item.strip() for item in proof)
            ):
                errors.append(f"{label} must declare non-empty negative_proof.required")


def validate_tenant_generation_negative_proof(
    policy: dict[str, Any], errors: list[str]
) -> None:
    proof = policy.get("negative_proof")
    label = "tenant_generation_policy.negative_proof"
    if not isinstance(proof, dict):
        errors.append(f"{label} must be a mapping")
        return
    classifications = set(
        string_list(
            proof.get("tenant_authority_classifications"),
            f"{label}.tenant_authority_classifications",
            errors,
        )
    )
    if classifications != REQUIRED_TENANT_AUTHORITY_CLASSIFICATIONS:
        errors.append(
            f"{label}.tenant_authority_classifications must be exactly "
            "the closed tenant-authority classification set"
        )
    required = set(string_list(proof.get("required"), f"{label}.required", errors))
    if "non_allowlisted_route_denied_after_target_tenant_generation_advance" not in required:
        errors.append(
            f"{label}.required must retain the tenant-authority generation-advance proof"
        )


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
        membership_generation = route.get("membership_authority_generation_applies")
        if classification == "billing_safe_tenant":
            if membership_generation is not True:
                errors.append(
                    f"{label} must require membership generation for {classification}"
                )
            roles = route.get("roles")
            role_values = roles.get("any_of") if isinstance(roles, dict) else None
            if role_values != ["tenantAdmin"]:
                errors.append(
                    f"{label} billing_safe_tenant roles.any_of must be ['tenantAdmin']"
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
                "target_tenant_generation",
            }
            if target_checks:
                errors.append(
                    f"{label} must not require target membership or tenant "
                    f"generation checks for {classification}: {sorted(target_checks)}"
                )


def validate_no_target_tenant_routes(
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
        if classification not in NO_TARGET_TENANT_CLASSES_WITHOUT_ROUTE_SPECIFIC_TARGET_AUTHORITY:
            continue
        label = f"{route.get('service')} {route.get('route')}"
        checks = (
            route_live_checks(route, label, errors, live_checks_cache)
            if "required_live_checks" in route
            else set()
        )
        forbidden_checks = checks & {"tenant_generation", "target_tenant_generation"}
        if forbidden_checks:
            errors.append(
                f"{label} must not require tenant-generation checks for no-target "
                f"classification {classification}: {sorted(forbidden_checks)}"
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
        validate_tenant_generation_negative_proof(policy, errors)
    validate_tenant_generation_exception_routes(routes, errors, live_checks_cache)
    validate_no_target_tenant_routes(routes, errors, live_checks_cache)


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
            errors.append(
                "entitlement_contract.cross_tenant_inheritance must be forbidden"
            )
        if contract.get("account_wide_fallback") != "forbidden":
            errors.append(
                "entitlement_contract.account_wide_fallback must be forbidden"
            )
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
        errors.append(
            "GetTenantEntitlementsForRuntime must declare account_owned_tenant_bound entitlement_scope"
        )
    if route.get("cross_tenant_inheritance") != "forbidden":
        errors.append(
            "GetTenantEntitlementsForRuntime must forbid cross-tenant inheritance"
        )


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


def validate_role_assurance_route_identities(
    routes: list[Any], errors: list[str]
) -> None:
    for expected_identity in sorted(PLATFORM_ADMIN_ROLE_ASSURANCE_ROUTE_IDENTITIES):
        matches = [
            route
            for route in routes
            if isinstance(route, dict)
            and route_identity_from_route(route) == expected_identity
        ]
        if len(matches) != 1:
            errors.append(
                "role_assurance platformAdmin exact route identity must match exactly "
                f"one route: {expected_identity}"
            )
            continue
        route = matches[0]
        if route.get("classification") != "internal_workload":
            errors.append(
                "role_assurance platformAdmin exact route identity must classify "
                f"{expected_identity} as internal_workload"
            )
        if route.get("role_assurance") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE:
            errors.append(
                "role_assurance platformAdmin exact route identity must declare "
                f"{expected_identity} with {PRIVILEGED_OPERATOR_ROLE_ASSURANCE}"
            )


def validate_operator_reference_issuance(
    routes: list[Any],
    errors: list[str],
    required_fields_cache: RequiredFieldsCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    for (service, route_name), required_fields in (
        OPERATOR_REFERENCE_ISSUANCE_REQUIRED_FIELDS.items()
    ):
        route = resolve_unique_route(
            routes,
            service,
            route_name,
            errors,
            cardinality_errors,
        )
        if route is None:
            continue
        label = f"{service} {route_name}"
        fields = set(
            cached_required_fields(
                route,
                route.get("required_fields"),
                f"{label} required_fields",
                errors,
                required_fields_cache,
                "required_fields",
            )
        )
        missing_fields = sorted(required_fields - fields)
        if missing_fields:
            errors.append(
                f"{label} required_fields must include operator-reference fields: "
                f"{missing_fields}"
            )


def validate_authority_unavailable_outcomes(
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            continue
        canonical_errors = route.get("canonical_errors")
        outcomes = (
            canonical_errors.get("any_of")
            if isinstance(canonical_errors, dict)
            else None
        )
        if not isinstance(outcomes, list) or any(
            not isinstance(outcome, str) for outcome in outcomes
        ):
            continue
        aliases = sorted(
            set(outcomes) & UNAVAILABLE_AUTHORITY_ERROR_ALIASES
        )
        if aliases:
            errors.append(
                f"routes[{index}] canonical_errors must use {AUTH_UNAVAILABLE} "
                f"instead of unavailable-authority aliases: {aliases}"
            )

    for service, route_name in sorted(AUTH_UNAVAILABLE_REQUIRED_ROUTES):
        route = resolve_unique_route(
            routes,
            service,
            route_name,
            errors,
            cardinality_errors,
        )
        if route is None:
            continue
        canonical_errors = route.get("canonical_errors")
        outcomes = (
            canonical_errors.get("any_of")
            if isinstance(canonical_errors, dict)
            else None
        )
        if not isinstance(outcomes, list) or AUTH_UNAVAILABLE not in outcomes:
            errors.append(
                f"{service} {route_name} must declare {AUTH_UNAVAILABLE} "
                "for unavailable authority"
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
            errors.append(
                f"{label} account_authority_generation_applies must be boolean"
            )
        route_key_value = route_set_key(route)
        checks = None
        if (
            route.get("classification") == "pending_deletion_scoped"
            or route_key_value in CONDITIONAL_OPERATOR_ROUTES
            or route_key_value in ACCOUNT_SUBJECT_BOUND_ROUTES
        ):
            checks = route_live_checks(route, label, errors, live_checks_cache)
        validate_pending_deletion_generation(
            route, label, account_generation, errors, checks, live_checks_cache
        )
        value = validate_membership_generation(route, label, errors)
        if value is True:
            if checks is None:
                checks = route_live_checks(route, label, errors, live_checks_cache)
            if "membership_generation" not in checks:
                errors.append(
                    f"{label} membership_authority_generation_applies=true requires "
                    "live check membership_generation"
                )
        validate_conditional_operator_route(
            route, label, value, errors, checks, live_checks_cache
        )
        validate_account_authorization_route(
            route, label, errors, checks, live_checks_cache
        )


def validate_profile_authority_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    for route_name in ("GetProfile", "UpdateProfile"):
        route = resolve_unique_route(
            routes,
            "account-service",
            route_name,
            errors,
            cardinality_errors,
        )
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
            errors.append(f"{label} must apply tenant billing authority generation")
        if route.get("membership_authority_generation_applies") is not True:
            errors.append(f"{label} must apply membership authority generation")
        checks = route_live_checks(route, label, errors, live_checks_cache)
        for required_check in (
            "membership",
            "membership_generation",
            "tenant_generation",
        ):
            if required_check not in checks:
                errors.append(f"{label} must require live check {required_check}")


def validate_idempotency_contract(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    required_fields_cache: RequiredFieldsCache | None = None,
) -> None:
    required_fields = (
        set(
            cached_required_fields(
                route,
                route.get("required_fields"),
                f"{label} required_fields",
                errors,
                required_fields_cache,
                "required_fields",
            )
        )
        if "required_fields" in route
        else set()
    )
    if "mutation_digest" not in required_fields:
        errors.append(f"{label} must require mutation_digest for idempotency")
    canonical_errors = route.get("canonical_errors", {})
    any_of = (
        canonical_errors.get("any_of")
        if isinstance(canonical_errors, dict)
        else None
    )
    outcomes = string_list(any_of, f"{label} canonical_errors.any_of", errors)
    if "IDEMPOTENCY_CONFLICT" not in outcomes:
        errors.append(f"{label} must declare IDEMPOTENCY_CONFLICT")


def validate_logging_admin_idempotency(
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
    required_fields_cache: RequiredFieldsCache | None = None,
) -> None:
    for service, route_name in sorted(LOGGING_ADMIN_IDEMPOTENT_OPERATOR_ROUTES):
        route = resolve_unique_route(
            routes,
            service,
            route_name,
            errors,
            cardinality_errors,
        )
        if route is not None:
            validate_idempotency_contract(
                route,
                f"{service} {route_name}",
                errors,
                required_fields_cache,
            )


def validate_refresh_roles_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
    required_fields_cache: RequiredFieldsCache | None = None,
) -> None:

    grpc_route = resolve_unique_route(
        routes,
        "game-session-service",
        "RefreshRoles",
        errors,
        cardinality_errors,
    )
    if grpc_route is not None:
        label = "game-session-service RefreshRoles"
        if (
            grpc_route.get("auth_path")
            != "exact_mtls_workload_plus_account_operator_authorization_reference"
        ):
            errors.append(
                f"{label} must use Account-redeemed operator authorization auth_path"
            )
        if (
            grpc_route.get("operator_authorization_reference")
            != "required_and_redeemed_with_account"
        ):
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
        validate_idempotency_contract(
            grpc_route,
            label,
            errors,
            required_fields_cache,
        )

    http_route = resolve_unique_route(
        routes,
        "game-session-service",
        "POST /sessions/{sessionId}/refresh-roles",
        errors,
        cardinality_errors,
    )
    if http_route is not None:
        label = "game-session-service POST /sessions/{sessionId}/refresh-roles"
        if (
            http_route.get("operator_authorization_reference")
            != "account_issued_bounded_reference"
        ):
            errors.append(f"{label} must require an Account-issued operator reference")
        validate_idempotency_contract(
            http_route,
            label,
            errors,
            required_fields_cache,
        )


def validate_known_drift(value: Any, field: str, errors: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            child_field = f"{field}.{key}" if field else key
            if (
                key == "implementation_status"
                and isinstance(child, dict)
                and "known_drift" in child
            ):
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


def route_identity(value: Any, field: str, errors: list[str]) -> str | None:
    if not isinstance(value, str) or not value.strip():
        errors.append(f"{field} must be a non-empty service/route identity")
        return None
    service, separator, route = value.partition("/")
    components = (
        canonical_route_components(service, route) if separator else None
    )
    if components is None:
        errors.append(f"{field} must be a non-empty service/route identity")
        return None
    return "/".join(components)


def validate_operator_mutation_support_gate(
    document: dict[str, Any], routes: list[Any], errors: list[str]
) -> None:
    gate = document.get("operator_mutation_support_gate")
    if not isinstance(gate, dict):
        errors.append("operator_mutation_support_gate must be a mapping")
        return

    route_identities = {
        identity
        for route in routes
        if isinstance(route, dict)
        for identity in (route_identity_from_route(route),)
        if identity is not None
    }
    gate_identities: list[str] = []
    applies_to_identities: list[str] = []
    for field in ("applies_to", "live_exceptions"):
        values = string_list(
            gate.get(field), f"operator_mutation_support_gate.{field}", errors
        )
        for index, value in enumerate(values):
            identity = route_identity(
                value,
                f"operator_mutation_support_gate.{field}[{index}]",
                errors,
            )
            if identity is not None:
                gate_identities.append(identity)
                if field == "applies_to":
                    applies_to_identities.append(identity)

    coverage_drift = gate.get("coverage_drift")
    if not isinstance(coverage_drift, list) or not coverage_drift:
        errors.append(
            "operator_mutation_support_gate.coverage_drift must be a non-empty list"
        )
        coverage_drift = []

    drift_identities: list[str] = []
    for index, entry in enumerate(coverage_drift):
        label = f"operator_mutation_support_gate.coverage_drift[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{label} must be a mapping")
            continue
        identity = route_identity(entry.get("identity"), f"{label}.identity", errors)
        if identity is not None:
            drift_identities.append(identity)
        if entry.get("status") != "drift-found":
            errors.append(f"{label}.status must be drift-found")
        note = entry.get("note")
        if not isinstance(note, str) or not note.strip():
            errors.append(f"{label}.note must be a non-empty string")

    if len(set(gate_identities)) != len(gate_identities):
        errors.append(
            "operator_mutation_support_gate applies_to/live_exceptions must not duplicate identities"
        )
    if len(set(drift_identities)) != len(drift_identities):
        errors.append(
            "operator_mutation_support_gate.coverage_drift must not duplicate identities"
        )
    missing_required_routes = sorted(
        REQUIRED_SESSION_LIFECYCLE_GATE_ROUTES - set(applies_to_identities)
    )
    if missing_required_routes:
        errors.append(
            "operator_mutation_support_gate.applies_to is missing required current "
            f"session lifecycle routes: {missing_required_routes}"
        )
    for identity in sorted(
        set(gate_identities) - route_identities - set(drift_identities)
    ):
        errors.append(
            "operator_mutation_support_gate identity is neither a route nor explicit "
            f"coverage drift: {identity}"
        )
    for identity in sorted(set(drift_identities) - set(gate_identities)):
        errors.append(
            "operator_mutation_support_gate.coverage_drift identity is not listed in "
            f"applies_to/live_exceptions: {identity}"
        )


def matching_routes(
    routes: list[Any],
    service: str,
    route_name: str,
) -> list[dict[str, Any]]:
    components = canonical_route_components(service, route_name)
    if components is None:
        return []
    return [
        route
        for route in routes
        if isinstance(route, dict)
        and route_set_key(route) == components
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
        components = canonical_route_components(service, route_name)
        key = (
            f"{components[0]}|{components[1]}"
            if components is not None
            else f"{service}|{route_name}"
        )
        if cardinality_errors is None or key not in cardinality_errors:
            errors.append(
                f"matrix must contain exactly one {key.replace('|', ' ')} route"
            )
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
    values = []
    if key in applicability:
        values.append(applicability[key])
    clauses = applicability.get("all_of", [])
    if isinstance(clauses, list):
        values.extend(
            clause[key]
            for clause in clauses
            if isinstance(clause, dict) and key in clause
        )
    if not values:
        return None
    if any(value != values[0] for value in values[1:]):
        errors.append(
            f"{label} has conflicting applicability values for {key}: {values!r}"
        )
        return None
    return values[0]


def route_live_checks(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> set[str]:
    return cached_live_checks(
        route,
        route.get("required_live_checks"),
        f"{label} required_live_checks",
        errors,
        live_checks_cache,
        "required_live_checks",
    )


def validate_applicability(
    route: dict[str, Any], label: str, expected: dict[str, str], errors: list[str]
) -> None:
    for key, expected_value in expected.items():
        actual_value = applicability_value(route, key, label, errors)
        if actual_value != expected_value:
            errors.append(
                f"{label} must declare applicability {key}={expected_value!r}"
            )


def validate_downstream_admission_contract(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    contract = route.get("downstream_admission_contract")
    if not isinstance(contract, dict):
        errors.append(f"{label} must declare downstream_admission_contract")
        return
    if contract.get("owner") != "game-session-service":
        errors.append(
            f"{label} downstream_admission_contract must be owned by game-session-service"
        )
    if contract.get("tenant_billing_authority_generation_applies") is not False:
        errors.append(
            f"{label} downstream_admission_contract must disable tenant billing authority generation"
        )
    if contract.get("membership_authority_generation_applies") is not True:
        errors.append(
            f"{label} downstream_admission_contract must apply membership authority generation"
        )
    if contract.get("membership_creation") != "explicit_join_only":
        errors.append(
            f"{label} downstream_admission_contract must declare membership_creation explicit_join_only"
        )
    missing = sorted(
        REQUIRED_DOWNSTREAM_ADMISSION_CHECKS
        - cached_live_checks(
            contract,
            contract.get("required_live_checks"),
            f"{label} downstream_admission_contract.required_live_checks",
            errors,
            live_checks_cache,
            "required_live_checks",
        )
    )
    if missing:
        errors.append(
            f"{label} downstream_admission_contract is missing required live checks: {missing}"
        )


def validate_ws_game_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    ws_routes = matching_routes(routes, "spring-cloud-gateway", "/ws/game/**")
    by_mode: dict[str, list[dict[str, Any]]] = {}
    for route in ws_routes:
        mode = applicability_value(route, "connection_mode", "/ws/game/**", errors)
        if isinstance(mode, str):
            by_mode.setdefault(mode, []).append(route)

    if len(ws_routes) != 2 or any(
        len(by_mode.get(mode, [])) != 1
        for mode in ("first_party_web", "trusted_tcp_proxy")
    ):
        errors.append(
            "matrix must contain exactly one first_party_web and one trusted_tcp_proxy "
            "spring-cloud-gateway /ws/game/** route"
        )
    else:
        for mode in ("first_party_web", "trusted_tcp_proxy"):
            route = by_mode[mode][0]
            label = f"/ws/game/** {mode}"
            for field in (
                "tenant_billing_authority_generation_applies",
                "membership_authority_generation_applies",
            ):
                if route.get(field) is not False:
                    errors.append(
                        f"{label} must explicitly set {field}=false for downstream admission"
                    )

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
            errors.append(
                f"/ws/game/** is missing required live checks: {missing_first_party}"
            )
        handshake_classes = first_party.get("handshake_error_classes", {})
        outcomes = (
            handshake_classes.get("any_of", [])
            if isinstance(handshake_classes, dict)
            else []
        )
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
        validate_downstream_admission_contract(
            first_party,
            "/ws/game/** first_party_web",
            errors,
            live_checks_cache,
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
        validate_downstream_admission_contract(
            trusted_proxy,
            "/ws/game/** trusted_tcp_proxy",
            errors,
            live_checks_cache,
        )

    revoke_route = resolve_unique_route(
        routes,
        "spring-cloud-gateway",
        "POST /ws/game/connect-token/revoke",
        errors,
        cardinality_errors,
    )
    if revoke_route is None:
        return
    missing_revoke = sorted(
        REQUIRED_CONNECT_TOKEN_REVOKE_CHECKS
        - route_live_checks(
            revoke_route,
            "POST /ws/game/connect-token/revoke",
            errors,
            live_checks_cache,
        )
    )
    validate_applicability(
        revoke_route,
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
    cardinality_errors: set[str] | None = None,
) -> None:
    issue_connect_route = resolve_unique_route(
        routes,
        "account-service",
        "IssueConnectToken",
        errors,
        cardinality_errors,
    )
    if issue_connect_route is None:
        return
    missing_checks = sorted(
        REQUIRED_ISSUE_CONNECT_TOKEN_CHECKS
        - route_live_checks(
            issue_connect_route, "IssueConnectToken", errors, live_checks_cache
        )
    )
    if missing_checks:
        errors.append(
            f"IssueConnectToken is missing required live checks: {missing_checks}"
        )


def validate_membership_writer_route(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    service, name = MEMBERSHIP_WRITER_ROUTE
    route = resolve_unique_route(routes, service, name, errors, cardinality_errors)
    if route is None:
        return
    checks = route_live_checks(
        route,
        f"{service} {name}",
        errors,
        live_checks_cache,
    )
    missing_checks = sorted(REQUIRED_MEMBERSHIP_WRITER_CHECKS - checks)
    if missing_checks:
        errors.append(
            f"{service} {name} is missing membership-writer checks: {missing_checks}"
        )


def validate_connect_token_revoke_generation_applicability(
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> None:
    service = "spring-cloud-gateway"
    name = "POST /ws/game/connect-token/revoke"
    route = resolve_unique_route(routes, service, name, errors, cardinality_errors)
    if route is None:
        return
    label = f"{service} {name}"
    for field, expected in REQUIRED_REVOKE_GENERATION_APPLICABILITY.items():
        if route.get(field) is not expected:
            errors.append(f"{label} must explicitly set {field}={expected}")


def validate_join_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    for service, name in sorted(JOIN_ROUTES_REQUIRING_POINTER_ERROR):
        route = resolve_unique_route(routes, service, name, errors, cardinality_errors)
        if route is None:
            continue
        canonical_errors = route.get("canonical_errors", {})
        outcomes = (
            canonical_errors.get("any_of", [])
            if isinstance(canonical_errors, dict)
            else []
        )
        if "ADMISSION_POINTER_UNAVAILABLE" not in outcomes:
            errors.append(
                f"{service} {name} must declare ADMISSION_POINTER_UNAVAILABLE"
            )
        checks = route_live_checks(
            route,
            f"{service} {name}",
            errors,
            live_checks_cache,
        )
        missing_checks = sorted(REQUIRED_JOIN_PRE_MEMBERSHIP_CHECKS - checks)
        if missing_checks:
            errors.append(
                f"{service} {name} is missing pre-membership checks: {missing_checks}"
            )
        if route.get("tenant_billing_authority_generation_applies") is not False:
            errors.append(
                f"{service} {name} must disable tenant_billing_authority_generation_applies"
            )
        if route.get("membership_authority_generation_applies") is not False:
            errors.append(
                f"{service} {name} must disable membership_authority_generation_applies"
            )


def validate_delegated_entitlements(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
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
        errors.append("GetTenantEntitlementsForRuntime caller_policies must be a list")
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
    if (
        not isinstance(vocabulary, list)
        or not vocabulary
        or any(not isinstance(item, str) for item in vocabulary)
    ):
        errors.append(
            "required_live_check_vocabulary must be a non-empty list of strings"
        )
        allowed_checks: set[str] = set()
    else:
        allowed_checks = set(vocabulary)
        if len(allowed_checks) != len(vocabulary):
            errors.append("required_live_check_vocabulary must not contain duplicates")

    live_checks = collect_live_checks(document, "matrix", errors, live_checks_cache)
    unknown_checks = sorted(
        {check for check in live_checks if check not in allowed_checks}
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
            route_variants.setdefault(key, []).append(
                (index, route.get("applicability"))
            )
        classification = route.get("classification")
        if not isinstance(classification, str):
            errors.append(f"routes[{index}] classification must be a string")
        elif classification not in allowed_classifications:
            errors.append(
                f"routes[{index}] uses unknown classification: {classification!r}"
            )

    for key, variants in route_variants.items():
        if len(variants) == 1:
            continue
        if any(applicability is None for _, applicability in variants):
            errors.append(
                f"duplicate route entries require explicit applicability: {key}"
            )
            continue
        serialized = []
        for index, applicability in variants:
            try:
                serialized.append(json.dumps(applicability, sort_keys=True))
            except (TypeError, ValueError) as exc:
                errors.append(
                    f"routes[{index}] duplicate route applicability must be "
                    f"JSON-serializable: {exc}"
                )
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
    # Keep both caches local to this validation call and therefore to this document.
    live_checks_cache: LiveChecksCache = {}
    required_fields_cache: RequiredFieldsCache = {}
    validate_live_check_vocabulary(document, errors, live_checks_cache)
    allowed_route_statuses = validate_route_status_vocabulary(document, errors)

    routes = document.get("routes")
    if not isinstance(routes, list):
        errors.append("routes must be a list")
        routes = []

    validate_operator_mutation_support_gate(document, routes, errors)

    classifications = string_list(
        document.get("classifications"), "classifications", errors
    )
    validate_route_class_branch_table(document, errors)
    validate_authority_evidence_policy(document, errors)
    route_keys = validate_route_variants(routes, set(classifications), errors)
    validate_route_statuses(routes, allowed_route_statuses, errors)
    validate_required_fields(routes, errors, required_fields_cache)
    cardinality_errors: set[str] = set()
    validate_authority_unavailable_outcomes(routes, errors, cardinality_errors)
    validate_operator_reference_issuance(
        routes,
        errors,
        required_fields_cache,
        cardinality_errors,
    )
    validate_generation_applicability(routes, errors, live_checks_cache)
    validate_profile_authority_routes(
        routes, errors, live_checks_cache, cardinality_errors
    )
    validate_refresh_roles_routes(
        routes,
        errors,
        live_checks_cache,
        cardinality_errors,
        required_fields_cache,
    )
    validate_logging_admin_idempotency(
        routes,
        errors,
        cardinality_errors,
        required_fields_cache,
    )
    validate_receiver_predicates(routes, token_profiles, errors)
    validate_explicit_no_jwt_routes(routes, errors)
    validate_role_assurance_references(routes, role_assurance_predicates, errors)
    validate_role_assurance_route_identities(routes, errors)
    validate_tenant_generation_policy(document, routes, errors, live_checks_cache)
    validate_membership_policy(document, errors)
    validate_elevation_bootstrap(document, routes, errors, cardinality_errors)
    validate_entitlement_contract(document, routes, errors, cardinality_errors)

    validate_ws_game_routes(routes, errors, live_checks_cache, cardinality_errors)
    validate_connect_token_revoke_generation_applicability(
        routes, errors, cardinality_errors
    )
    validate_issue_connect_token(routes, errors, live_checks_cache, cardinality_errors)
    validate_join_routes(routes, errors, live_checks_cache, cardinality_errors)
    validate_membership_writer_route(
        routes, errors, live_checks_cache, cardinality_errors
    )
    validate_delegated_entitlements(
        routes, errors, live_checks_cache, cardinality_errors
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
