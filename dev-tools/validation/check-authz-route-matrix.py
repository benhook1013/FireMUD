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
    "issuer_generation",
    "account_generation",
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


def collect_live_checks(value: Any) -> list[Any]:
    checks: list[Any] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "required_live_checks":
                checks.extend(child if isinstance(child, list) else [child])
            else:
                checks.extend(collect_live_checks(child))
    elif isinstance(value, list):
        for child in value:
            checks.extend(collect_live_checks(child))
    return checks


def validate_matrix_document(path: Path) -> tuple[list[str], set[str]]:
    errors: list[str] = []
    try:
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as error:
        return [f"cannot read matrix {path}: {error}"], set()

    if not isinstance(document, dict):
        return ["matrix root must be a mapping"], set()

    vocabulary = document.get("required_live_check_vocabulary")
    if not isinstance(vocabulary, list) or not vocabulary or any(not isinstance(item, str) for item in vocabulary):
        errors.append("required_live_check_vocabulary must be a non-empty list of strings")
        allowed_checks: set[str] = set()
    else:
        allowed_checks = set(vocabulary)
        if len(allowed_checks) != len(vocabulary):
            errors.append("required_live_check_vocabulary must not contain duplicates")

    live_checks = collect_live_checks(document)
    if any(not isinstance(check, str) for check in live_checks):
        errors.append("every required_live_checks value must be a string")
    unknown_checks = sorted({check for check in live_checks if isinstance(check, str) and check not in allowed_checks})
    if unknown_checks:
        errors.append(f"required_live_checks contains values outside the closed vocabulary: {unknown_checks}")

    routes = document.get("routes")
    if not isinstance(routes, list):
        errors.append("routes must be a list")
        routes = []

    route_variants: dict[str, list[tuple[int, Any]]] = {}
    classifications = string_list(document.get("classifications"), "classifications", errors)
    allowed_classifications = set(classifications)
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            errors.append(f"routes[{index}] must be a mapping")
            continue
        key = route_key(route)
        if key is None:
            errors.append(f"routes[{index}] must declare string service and route")
        else:
            route_variants.setdefault(key, []).append((index, route.get("applicability")))
        if route.get("classification") not in allowed_classifications:
            errors.append(f"routes[{index}] uses unknown classification: {route.get('classification')!r}")

    for key, variants in route_variants.items():
        if len(variants) == 1:
            continue
        if any(applicability is None for _, applicability in variants):
            errors.append(f"duplicate route entries require explicit applicability: {key}")
            continue
        serialized = [json.dumps(applicability, sort_keys=True) for _, applicability in variants]
        if len(serialized) != len(set(serialized)):
            errors.append(f"duplicate route applicability: {key}")

    ws_routes = [
        route
        for route in routes
        if isinstance(route, dict)
        and route.get("service") == "spring-cloud-gateway"
        and route.get("route") == "/ws/game/**"
    ]
    if len(ws_routes) != 1:
        errors.append("matrix must contain exactly one spring-cloud-gateway /ws/game/** route")
    else:
        ws_checks = set(
            string_list(
                ws_routes[0].get("required_live_checks"),
                "/ws/game/** required_live_checks",
                errors,
            )
        )
        missing_ws_checks = sorted(REQUIRED_WS_GAME_CHECKS - ws_checks)
        if missing_ws_checks:
            errors.append(f"/ws/game/** is missing required live checks: {missing_ws_checks}")
        handshake_classes = ws_routes[0].get("handshake_error_classes", {})
        outcomes = handshake_classes.get("any_of", []) if isinstance(handshake_classes, dict) else []
        if "POLICY_PRESSURE" not in outcomes:
            errors.append("/ws/game/** handshake outcomes must include POLICY_PRESSURE")

    issue_connect_routes = [
        route
        for route in routes
        if isinstance(route, dict)
        and route.get("service") == "account-service"
        and route.get("route") == "IssueConnectToken"
    ]
    if len(issue_connect_routes) != 1:
        errors.append("matrix must contain exactly one account-service IssueConnectToken route")
    else:
        issue_checks = set(
            string_list(
                issue_connect_routes[0].get("required_live_checks"),
                "IssueConnectToken required_live_checks",
                errors,
            )
        )
        missing_issue_checks = sorted(REQUIRED_ISSUE_CONNECT_TOKEN_CHECKS - issue_checks)
        if missing_issue_checks:
            errors.append(f"IssueConnectToken is missing required live checks: {missing_issue_checks}")

    for service, name in JOIN_ROUTES_REQUIRING_POINTER_ERROR:
        matching_routes = [
            route
            for route in routes
            if isinstance(route, dict)
            and route.get("service") == service
            and route.get("route") == name
        ]
        if len(matching_routes) != 1:
            errors.append(f"matrix must contain exactly one {service} {name} route")
            continue
        canonical_errors = matching_routes[0].get("canonical_errors", {})
        outcomes = canonical_errors.get("any_of", []) if isinstance(canonical_errors, dict) else []
        if "ADMISSION_POINTER_UNAVAILABLE" not in outcomes:
            errors.append(f"{service} {name} must declare ADMISSION_POINTER_UNAVAILABLE")

    entitlement_routes = [
        route
        for route in routes
        if isinstance(route, dict)
        and route.get("service") == "account-service"
        and route.get("route") == "GetTenantEntitlementsForRuntime"
    ]
    if len(entitlement_routes) != 1:
        errors.append(
            "matrix must contain exactly one account-service GetTenantEntitlementsForRuntime route"
        )
    else:
        caller_policies = entitlement_routes[0].get("caller_policies", [])
        game_session_policies = [
            policy
            for policy in caller_policies
            if isinstance(policy, dict) and policy.get("caller") == "game-session-service"
        ]
        if len(game_session_policies) != 1:
            errors.append(
                "GetTenantEntitlementsForRuntime must contain exactly one game-session-service caller policy"
            )
        else:
            delegated_checks = set(
                string_list(
                    game_session_policies[0].get("required_live_checks"),
                    "GetTenantEntitlementsForRuntime game-session required_live_checks",
                    errors,
                )
            )
            missing_delegated_checks = sorted(
                REQUIRED_DELEGATED_ENTITLEMENT_CHECKS - delegated_checks
            )
            if missing_delegated_checks:
                errors.append(
                    "GetTenantEntitlementsForRuntime game-session policy is missing "
                    f"required live checks: {missing_delegated_checks}"
                )

    return errors, set(route_variants)


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
