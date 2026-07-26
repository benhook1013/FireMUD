#!/usr/bin/env python3
"""Validate the machine-readable authorization route matrix contract."""

from __future__ import annotations

import argparse
import json
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


def collect_live_checks(value: Any, field: str, errors: list[str]) -> list[str]:
    checks: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            child_field = f"{field}.{key}" if field else key
            if key == "required_live_checks":
                checks.extend(string_list(child, child_field, errors))
            else:
                checks.extend(collect_live_checks(child, child_field, errors))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            checks.extend(collect_live_checks(child, f"{field}[{index}]", errors))
    return checks


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


def route_live_checks(route: dict[str, Any], label: str, errors: list[str]) -> set[str]:
    return set(string_list(route.get("required_live_checks"), f"{label} required_live_checks", errors))


def validate_applicability(
    route: dict[str, Any], label: str, expected: dict[str, str], errors: list[str]
) -> None:
    for key, expected_value in expected.items():
        actual_value = applicability_value(route, key, label, errors)
        if actual_value != expected_value:
            errors.append(
                f"{label} must declare applicability {key}={expected_value!r}"
            )


def validate_ws_game_routes(routes: list[Any], errors: list[str]) -> None:
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
            - route_live_checks(first_party, "/ws/game/** first_party_web", errors)
        )
        if missing_first_party:
            errors.append(f"/ws/game/** is missing required live checks: {missing_first_party}")
        handshake_classes = first_party.get("handshake_error_classes", {})
        outcomes = handshake_classes.get("any_of", []) if isinstance(handshake_classes, dict) else []
        if "POLICY_PRESSURE" not in outcomes:
            errors.append("/ws/game/** handshake outcomes must include POLICY_PRESSURE")

        trusted_proxy = by_mode["trusted_tcp_proxy"][0]
        missing_trusted_proxy = sorted(
            REQUIRED_TRUSTED_PROXY_CHECKS
            - route_live_checks(trusted_proxy, "/ws/game/** trusted_tcp_proxy", errors)
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
        - route_live_checks(revoke_routes[0], "POST /ws/game/connect-token/revoke", errors)
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


def validate_issue_connect_token(routes: list[Any], errors: list[str]) -> None:
    issue_connect_routes = matching_routes(routes, "account-service", "IssueConnectToken")
    if len(issue_connect_routes) != 1:
        errors.append("matrix must contain exactly one account-service IssueConnectToken route")
        return
    missing_checks = sorted(
        REQUIRED_ISSUE_CONNECT_TOKEN_CHECKS
        - route_live_checks(issue_connect_routes[0], "IssueConnectToken", errors)
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


def validate_delegated_entitlements(routes: list[Any], errors: list[str]) -> None:
    entitlement_routes = matching_routes(routes, "account-service", "GetTenantEntitlementsForRuntime")
    if len(entitlement_routes) != 1:
        errors.append(
            "matrix must contain exactly one account-service GetTenantEntitlementsForRuntime route"
        )
        return
    caller_policies = entitlement_routes[0].get("caller_policies")
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
        )
    )
    if missing_checks:
        errors.append(
            "GetTenantEntitlementsForRuntime game-session policy is missing "
            f"required live checks: {missing_checks}"
        )


def validate_live_check_vocabulary(document: dict[str, Any], errors: list[str]) -> set[str]:
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

    live_checks = collect_live_checks(document, "matrix", errors)
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

    validate_live_check_vocabulary(document, errors)

    routes = document.get("routes")
    if not isinstance(routes, list):
        errors.append("routes must be a list")
        routes = []

    classifications = string_list(document.get("classifications"), "classifications", errors)
    route_keys = validate_route_variants(routes, set(classifications), errors)

    validate_ws_game_routes(routes, errors)
    validate_issue_connect_token(routes, errors)
    validate_join_routes(routes, errors)
    validate_delegated_entitlements(routes, errors)

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
