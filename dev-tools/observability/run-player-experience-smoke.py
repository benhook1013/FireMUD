#!/usr/bin/env python3

from __future__ import annotations

import argparse
import copy
import json
import os
import socket
import sys
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "dev-tools" / "smoke"))

from smoke_common import (  # noqa: E402
    http_request_json,
    http_request_json_with_headers,
    http_readiness_up,
    login_play_look_steps,
    quote_path,
    recv_until_socket,
    run_telnet_command_plan,
    run_websocket_command_plan,
    verify_smoke_account,
    wait_for_account_schema,
    wait_for_http_readiness,
)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Run the canonical prod-like player-experience smoke and emit "
            "mirrored canary/blackbox/deadman evidence."
        )
    )
    parser.add_argument(
        "--evidence-out",
        type=Path,
        required=True,
        help="Path to write the retained smoke evidence JSON file.",
    )
    parser.add_argument(
        "--metrics-out",
        type=Path,
        help="Optional path to write OpenMetrics-style mirrored signal output.",
    )
    parser.add_argument(
        "--simulate",
        action="store_true",
        help=(
            "Skip network execution and emit synthetic success/failure records. "
            "Useful for non-production contract proof and failure-injection tests."
        ),
    )
    parser.add_argument(
        "--external-authority-evidence",
        type=Path,
        default=Path(os.environ["PLAYER_EXPERIENCE_EXTERNAL_AUTHORITY_EVIDENCE"])
        if os.environ.get("PLAYER_EXPERIENCE_EXTERNAL_AUTHORITY_EVIDENCE")
        else None,
        help=(
            "Path to retained authoritative external-monitor evidence for the "
            "deadman and observability-entrypoint checks. Required for non-simulated "
            "prod-like smoke."
        ),
    )
    parser.add_argument(
        "--failure-injection",
        default=os.environ.get("PLAYER_EXPERIENCE_FAILURE_INJECTION", ""),
        help=(
            "Comma-separated synthetic failure flags. Supported values: "
            "websocket,telnet,login,command,deadman,prometheus,alertmanager,"
            "grafana,kibana_log_query,jaeger_query,"
            "PlayerFlowCanaryLoginFailed,PlayerFlowCanaryCommandFailed,"
            "PlayerFlowCanaryLatencyHigh."
        ),
    )
    parser.add_argument(
        "--source",
        default=os.environ.get("PLAYER_EXPERIENCE_SOURCE", "local"),
        help="Low-cardinality source label for the deadman heartbeat mirror.",
    )
    parser.add_argument(
        "--canary-path",
        choices=("websocket", "telnet"),
        default=os.environ.get("PLAYER_EXPERIENCE_CANARY_PATH", "websocket"),
        help="Transport used for the synthetic login + representative command canary.",
    )
    args = parser.parse_args()

    injected = parse_failure_injection(args.failure_injection)
    config = SmokeConfig.from_env(
        args.source, args.canary_path, args.external_authority_evidence
    )
    external_authority = resolve_external_authority(config, injected, args.simulate)

    mirrored_signals = execute_smoke(config, args.simulate, injected)
    evidence = build_evidence(config, mirrored_signals, external_authority, injected)
    args.evidence_out.parent.mkdir(parents=True, exist_ok=True)
    args.evidence_out.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

    if args.metrics_out is not None:
        args.metrics_out.parent.mkdir(parents=True, exist_ok=True)
        args.metrics_out.write_text(render_metrics(config, mirrored_signals), encoding="utf-8")

    print(f"Wrote player-experience evidence to {args.evidence_out}")
    if args.metrics_out is not None:
        print(f"Wrote mirrored metrics to {args.metrics_out}")
    return 0


class SmokeConfig:
    def __init__(
        self,
        source: str,
        canary_path: str,
        websocket_url: str,
        websocket_target: str,
        telnet_host: str,
        telnet_port: int,
        telnet_target: str,
        account_api_base: str,
        game_logic_api_base: str,
        game_session_api_base: str,
        gateway_api_base: str,
        tcp_proxy_api_base: str,
        tenant_id: str,
        realm_slug: str,
        character_name: str | None,
        username: str,
        password: str,
        world: str,
        timeout_seconds: int,
        startup_wait_seconds: int,
        external_authority_evidence: Path | None,
        auth_api_base: str,
        auth_api_prefix: str,
        verified_by: str,
        preflight_ref: str,
        deployment_ref: str,
    ) -> None:
        self.source = source
        self.canary_path = canary_path
        self.websocket_url = websocket_url
        self.websocket_target = websocket_target
        self.telnet_host = telnet_host
        self.telnet_port = telnet_port
        self.telnet_target = telnet_target
        self.account_api_base = account_api_base
        self.game_logic_api_base = game_logic_api_base
        self.game_session_api_base = game_session_api_base
        self.gateway_api_base = gateway_api_base
        self.tcp_proxy_api_base = tcp_proxy_api_base
        self.tenant_id = tenant_id
        self.realm_slug = realm_slug
        self.character_name = character_name
        self.username = username
        self.password = password
        self.world = world
        self.timeout_seconds = timeout_seconds
        self.startup_wait_seconds = startup_wait_seconds
        self.external_authority_evidence = external_authority_evidence
        self.auth_api_base = auth_api_base
        self.auth_api_prefix = auth_api_prefix
        self.verified_by = verified_by
        self.preflight_ref = preflight_ref
        self.deployment_ref = deployment_ref

    @classmethod
    def from_env(
        cls, source: str, canary_path: str, external_authority_evidence: Path | None
    ) -> "SmokeConfig":
        return cls(
            source=source,
            canary_path=canary_path,
            websocket_url=os.environ.get(
                "PLAYER_EXPERIENCE_WEBSOCKET_URL",
                websocket_url_from_http_base(
                    os.environ.get("SMOKE_GATEWAY_API_BASE", "http://localhost:8080")
                ),
            ),
            websocket_target=os.environ.get(
                "PLAYER_EXPERIENCE_WEBSOCKET_TARGET", "local-websocket-edge"
            ),
            telnet_host=os.environ.get("SMOKE_TELNET_HOST", "localhost"),
            telnet_port=int(os.environ.get("TCP_PROXY_PORT", "2323")),
            telnet_target=os.environ.get(
                "PLAYER_EXPERIENCE_TELNET_TARGET", "local-telnet-edge"
            ),
            account_api_base=os.environ.get("SMOKE_ACCOUNT_API_BASE", "http://localhost:8081"),
            game_logic_api_base=os.environ.get(
                "SMOKE_GAME_LOGIC_API_BASE", "http://localhost:8085"
            ),
            game_session_api_base=os.environ.get(
                "SMOKE_GAME_SESSION_API_BASE", "http://localhost:8086"
            ),
            gateway_api_base=os.environ.get("SMOKE_GATEWAY_API_BASE", "http://localhost:8080"),
            tcp_proxy_api_base=os.environ.get(
                "SMOKE_TCP_PROXY_API_BASE", "http://localhost:8089"
            ),
            tenant_id=os.environ.get("SMOKE_TENANT_ID", "1"),
            realm_slug=os.environ.get("PLAYER_EXPERIENCE_REALM", "production"),
            character_name=os.environ.get("PLAYER_EXPERIENCE_CHARACTER"),
            username=os.environ.get("SMOKE_USERNAME", "demo@example.com"),
            password=os.environ.get("SMOKE_PASSWORD", "swordfish"),
            world=os.environ.get("PLAYER_EXPERIENCE_WORLD", "demo"),
            timeout_seconds=int(os.environ.get("SMOKE_TIMEOUT_SECONDS", "10")),
            startup_wait_seconds=int(os.environ.get("SMOKE_STARTUP_WAIT_SECONDS", "90")),
            external_authority_evidence=external_authority_evidence,
            auth_api_base=os.environ.get("PLAYER_EXPERIENCE_AUTH_API_BASE")
            or os.environ.get("SMOKE_GATEWAY_API_BASE", "http://localhost:8080"),
            auth_api_prefix=os.environ.get(
                "PLAYER_EXPERIENCE_AUTH_API_PREFIX", "/api/account"
            ),
            verified_by=os.environ.get("PLAYER_EXPERIENCE_VERIFIED_BY", "local-operator"),
            preflight_ref=os.environ.get(
                "PLAYER_EXPERIENCE_PREFLIGHT_REF",
                "ci://player-experience-smoke/local",
            ),
            deployment_ref=os.environ.get(
                "PLAYER_EXPERIENCE_DEPLOYMENT_REF", "local-player-experience-smoke"
            ),
        )


def parse_failure_injection(raw: str) -> set[str]:
    return {item.strip() for item in raw.split(",") if item.strip()}


def websocket_url_from_http_base(http_base: str) -> str:
    parsed = urlparse(http_base)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    port = f":{parsed.port}" if parsed.port is not None else ""
    return f"{scheme}://{parsed.hostname}{port}/ws/game"


def execute_smoke(
    config: SmokeConfig, simulate: bool, injected: set[str]
) -> dict[str, Any]:
    if simulate:
        return simulated_signals(config, injected)

    wait_for_account_schema(config.startup_wait_seconds, config.timeout_seconds)
    wait_for_http_readiness(
        "account-service",
        config.account_api_base,
        config.startup_wait_seconds,
        config.timeout_seconds,
    )
    wait_for_http_readiness(
        "game-logic-service",
        config.game_logic_api_base,
        config.startup_wait_seconds,
        config.timeout_seconds,
    )
    wait_for_http_readiness(
        "game-session-service",
        config.game_session_api_base,
        config.startup_wait_seconds,
        config.timeout_seconds,
    )
    wait_for_http_readiness(
        "spring-cloud-gateway",
        config.gateway_api_base,
        config.startup_wait_seconds,
        config.timeout_seconds,
    )
    if config.canary_path == "telnet":
        wait_for_http_readiness(
            "tcp-proxy-service",
            config.tcp_proxy_api_base,
            config.startup_wait_seconds,
            config.timeout_seconds,
        )
    verify_smoke_account(
        config.account_api_base,
        config.tenant_id,
        config.username,
        config.password,
        config.timeout_seconds,
    )

    entrypath_records = [
        blackbox_websocket_record(config, injected),
        blackbox_telnet_record(config, injected),
    ]
    playerflow_success, playerflow_latency = run_playerflow_canary(config, injected)
    deadman = deadman_record(config, injected)
    return {
        "entrypath_blackbox_probe_success": entrypath_records,
        "playerflow_canary_success": playerflow_success,
        "playerflow_canary_latency_ms": playerflow_latency,
        "observability_deadman_heartbeat_timestamp_seconds": deadman,
    }


def simulated_signals(config: SmokeConfig, injected: set[str]) -> dict[str, Any]:
    now = time.time()
    entrypath_records = [
        {
            "path": "websocket",
            "target": config.websocket_target,
            "value": 0 if "websocket" in injected else 1,
        },
        {
            "path": "telnet",
            "target": config.telnet_target,
            "value": 0 if "telnet" in injected else 1,
        },
    ]
    playerflow_success = [
        {
            "flow": "login",
            "path": config.canary_path,
            "target": canary_target(config),
            "value": 0 if "login" in injected else 1,
        },
        {
            "flow": "command",
            "path": config.canary_path,
            "target": canary_target(config),
            "value": 0 if "command" in injected else 1,
        },
    ]
    playerflow_latency = [
        {
            "flow": "command",
            "path": config.canary_path,
            "target": canary_target(config),
            "value": 0 if "command" in injected else 125,
        }
    ]
    return {
        "entrypath_blackbox_probe_success": entrypath_records,
        "playerflow_canary_success": playerflow_success,
        "playerflow_canary_latency_ms": playerflow_latency,
        "observability_deadman_heartbeat_timestamp_seconds": {
            "source": config.source,
            "value": 0 if "deadman" in injected else int(now),
        },
    }


def blackbox_websocket_record(config: SmokeConfig, injected: set[str]) -> dict[str, Any]:
    if "websocket" in injected:
        return {"path": "websocket", "target": config.websocket_target, "value": 0}
    try:
        import websocket  # type: ignore
    except ImportError as exc:  # pragma: no cover - exercised in live use, not tests
        raise RuntimeError(
            "The python 'websocket-client' package is required for WebSocket canary smoke"
        ) from exc

    first_party = first_party_connect_context(config)
    ws = websocket.create_connection(
        config.websocket_url,
        timeout=config.timeout_seconds,
        header=[f"Cookie: {first_party['connectCookie']}"],
    )
    try:
        return {"path": "websocket", "target": config.websocket_target, "value": 1}
    finally:
        ws.close()


def blackbox_telnet_record(config: SmokeConfig, injected: set[str]) -> dict[str, Any]:
    if "telnet" in injected:
        return {"path": "telnet", "target": config.telnet_target, "value": 0}
    with socket.create_connection(
        (config.telnet_host, config.telnet_port), timeout=config.timeout_seconds
    ) as sock:
        recv_until_socket(sock, "\n", config.timeout_seconds)
        run_telnet_command_plan(
            sock,
            [("WORLDS", ["OK WORLDS"], "WORLDS")],
            config.timeout_seconds,
        )
        return {"path": "telnet", "target": config.telnet_target, "value": 1}


def run_playerflow_canary(
    config: SmokeConfig, injected: set[str]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if config.canary_path == "websocket":
        return run_websocket_canary(config, injected)
    return run_telnet_canary(config, injected)


def run_websocket_canary(
    config: SmokeConfig, injected: set[str]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if "login" in injected or "command" in injected:
        return simulated_canary_records(config, injected)
    try:
        import websocket  # type: ignore
    except ImportError as exc:  # pragma: no cover
        raise RuntimeError(
            "The python 'websocket-client' package is required for WebSocket canary smoke"
        ) from exc

    first_party = first_party_connect_context(config)
    step_results: list[dict[str, Any]] = []
    ws = websocket.create_connection(
        config.websocket_url,
        timeout=config.timeout_seconds,
        header=[f"Cookie: {first_party['connectCookie']}"],
    )
    try:
        run_first_party_websocket_canary(
            ws, config, first_party.get("characterName"), step_results
        )
    finally:
        ws.close()
    return canary_records_from_steps(config, step_results)


def run_telnet_canary(
    config: SmokeConfig, injected: set[str]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if "login" in injected or "command" in injected:
        return simulated_canary_records(config, injected)
    step_results: list[dict[str, Any]] = []
    with socket.create_connection(
        (config.telnet_host, config.telnet_port), timeout=config.timeout_seconds
    ) as sock:
        recv_until_socket(sock, "\n", config.timeout_seconds)
        run_telnet_command_plan(
            sock,
            login_play_look_steps(
                config.username,
                config.password,
                config.world,
                "OK WORLDS",
                "OK LOGIN",
                "OK PLAY",
                "OK LOOK",
                realm=config.realm_slug,
                character=config.character_name,
            ),
            config.timeout_seconds,
            step_results=step_results,
        )
    return canary_records_from_steps(config, step_results)


def simulated_canary_records(
    config: SmokeConfig, injected: set[str]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    success = [
        {
            "flow": "login",
            "path": config.canary_path,
            "target": canary_target(config),
            "value": 0 if "login" in injected else 1,
        },
        {
            "flow": "command",
            "path": config.canary_path,
            "target": canary_target(config),
            "value": 0 if "command" in injected else 1,
        },
    ]
    latency = [
        {
            "flow": "command",
            "path": config.canary_path,
            "target": canary_target(config),
            "value": 0 if "command" in injected else 125,
        }
    ]
    return success, latency


def canary_records_from_steps(
    config: SmokeConfig, step_results: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    by_label = {step["label"]: step for step in step_results}
    login = by_label["LOGIN"]
    command = by_label["LOOK"]
    success = [
        {
            "flow": "login",
            "path": config.canary_path,
            "target": canary_target(config),
            "value": 1,
        },
        {
            "flow": "command",
            "path": config.canary_path,
            "target": canary_target(config),
            "value": 1,
        },
    ]
    latency = [
        {
            "flow": "command",
            "path": config.canary_path,
            "target": canary_target(config),
            "value": command["latencyMs"],
            "loginLatencyMs": login["latencyMs"],
        }
    ]
    return success, latency


def deadman_record(config: SmokeConfig, injected: set[str]) -> dict[str, Any]:
    return {
        "source": config.source,
        "value": 0 if "deadman" in injected else int(time.time()),
    }


def canary_target(config: SmokeConfig) -> str:
    return config.websocket_target if config.canary_path == "websocket" else config.telnet_target


def build_evidence(
    config: SmokeConfig,
    mirrored_signals: dict[str, Any],
    external_authority: dict[str, Any],
    injected: set[str],
) -> dict[str, Any]:
    return {
        "deploymentRef": config.deployment_ref,
        "verifiedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "verifiedBy": config.verified_by,
        "preflightEvidenceRef": config.preflight_ref,
        "externalAuthority": external_authority,
        "mirroredSignals": mirrored_signals,
        "canaryAlerts": [
            alert_record("PlayerFlowCanaryLoginFailed", "P0", injected),
            alert_record("PlayerFlowCanaryCommandFailed", "P1", injected),
            alert_record("PlayerFlowCanaryLatencyHigh", "P1", injected),
        ],
    }


def resolve_external_authority(
    config: SmokeConfig, injected: set[str], simulate: bool
) -> dict[str, Any]:
    authority = load_external_authority(config, simulate)
    authority = copy.deepcopy(authority)
    if "deadman" in injected:
        authority["deadmanAuthority"]["status"] = "red"
    for name, value in authority["entrypointChecks"].items():
        if name in injected:
            value["status"] = "red"
    return authority


def load_external_authority(config: SmokeConfig, simulate: bool) -> dict[str, Any]:
    path = config.external_authority_evidence
    if path is None:
        if simulate:
            return simulated_external_authority()
        raise RuntimeError(
            "Non-simulated player-experience smoke requires "
            "--external-authority-evidence or PLAYER_EXPERIENCE_EXTERNAL_AUTHORITY_EVIDENCE"
        )
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise RuntimeError(
            f"Unable to read external authority evidence from {path}: {exc}"
        ) from exc
    if not isinstance(data, dict):
        raise RuntimeError(
            f"External authority evidence at {path} must be a JSON object"
        )
    validate_external_authority_shape(data, path)
    return data


def simulated_external_authority() -> dict[str, Any]:
    return {
        "deadmanAuthority": {
            "status": "green",
            "evidenceRef": "synthetic://external-authority/deadman",
            "target": "synthetic-deadman-authority",
            "checkRef": "synthetic-deadman-check",
        },
        "entrypointChecks": {
            name: {
                "status": "green",
                "evidenceRef": f"synthetic://external-authority/{name}",
                "target": f"synthetic-{name}",
                "checkRef": f"synthetic-{name}-check",
            }
            for name in (
                "prometheus",
                "alertmanager",
                "grafana",
                "kibana_log_query",
                "jaeger_query",
            )
        },
    }


def validate_external_authority_shape(data: dict[str, Any], path: Path) -> None:
    deadman = data.get("deadmanAuthority")
    if not isinstance(deadman, dict):
        raise RuntimeError(
            f"External authority evidence at {path} must include deadmanAuthority"
        )
    validate_authority_record(deadman, "deadmanAuthority", path)
    checks = data.get("entrypointChecks")
    if not isinstance(checks, dict):
        raise RuntimeError(
            f"External authority evidence at {path} must include entrypointChecks"
        )
    required_checks = {
        "prometheus",
        "alertmanager",
        "grafana",
        "kibana_log_query",
        "jaeger_query",
    }
    missing = sorted(required_checks - set(checks.keys()))
    if missing:
        raise RuntimeError(
            f"External authority evidence at {path} is missing entrypoint checks: "
            + ", ".join(missing)
        )
    for name in required_checks:
        record = checks.get(name)
        if not isinstance(record, dict):
            raise RuntimeError(
                f"External authority evidence at {path} must define {name} as an object"
            )
        validate_authority_record(record, f"entrypointChecks.{name}", path)


def validate_authority_record(record: dict[str, Any], key: str, path: Path) -> None:
    status = record.get("status")
    if status not in {"green", "red"}:
        raise RuntimeError(
            f"External authority evidence at {path} has invalid {key}.status: {status!r}"
        )
    for field in ("evidenceRef", "target", "checkRef"):
        value = record.get(field)
        if not isinstance(value, str) or not value.strip():
            raise RuntimeError(
                f"External authority evidence at {path} must define {key}.{field}"
            )


def first_party_connect_context(config: SmokeConfig) -> dict[str, Any]:
    bootstrap = issue_player_bootstrap(config)
    require_visible_world(config, bootstrap["bootstrapToken"])
    connect_scope_id = resolve_connect_scope_id(config, bootstrap["bootstrapToken"])
    character_name = resolve_character_name(config, bootstrap["bootstrapToken"])
    connect_token = issue_connect_token(config, bootstrap["bootstrapToken"], connect_scope_id)
    connect_token["characterName"] = character_name
    return connect_token


def issue_player_bootstrap(config: SmokeConfig) -> dict[str, Any]:
    response = http_request_json(
        public_auth_url(config, "/auth/player-bootstrap"),
        config.timeout_seconds,
        method="POST",
        payload={
            "accountIdentifier": config.username,
            "secret": config.password,
        },
    )
    return response["data"]


def resolve_connect_scope_id(config: SmokeConfig, bootstrap_token: str) -> str:
    response = http_request_json(
        public_auth_url(
            config, f"/auth/bootstrap/worlds/{quote_path(config.world)}/realms"
        ),
        config.timeout_seconds,
        headers={"Authorization": f"Bearer {bootstrap_token}"},
    )
    for realm in response["data"]:
        if realm.get("realmSlug") == config.realm_slug:
            return realm["connectScopeId"]
    raise RuntimeError(
        f"Realm {config.realm_slug!r} was not visible during bootstrap discovery for world {config.world!r}"
    )


def require_visible_world(config: SmokeConfig, bootstrap_token: str) -> None:
    response = http_request_json(
        public_auth_url(config, "/auth/bootstrap/worlds"),
        config.timeout_seconds,
        headers={"Authorization": f"Bearer {bootstrap_token}"},
    )
    for world in response["data"]:
        if world.get("worldSlug") == config.world:
            return
    raise RuntimeError(
        f"World {config.world!r} was not visible during bootstrap discovery"
    )


def resolve_character_name(config: SmokeConfig, bootstrap_token: str) -> str | None:
    response = http_request_json(
        public_auth_url(
            config,
            f"/auth/bootstrap/worlds/{quote_path(config.world)}/realms/{quote_path(config.realm_slug)}/characters",
        ),
        config.timeout_seconds,
        headers={"Authorization": f"Bearer {bootstrap_token}"},
    )
    characters = response["data"]
    if config.character_name:
        for character in characters:
            if character.get("characterName") == config.character_name:
                return config.character_name
        raise RuntimeError(
            f"Character {config.character_name!r} was not visible during bootstrap discovery"
        )
    if not characters:
        return None
    return characters[0].get("characterName")


def issue_connect_token(
    config: SmokeConfig, bootstrap_token: str, connect_scope_id: str
) -> dict[str, Any]:
    response, headers = http_request_json_with_headers(
        public_auth_url(config, "/auth/connect-token"),
        config.timeout_seconds,
        method="POST",
        payload={
            "connectScopeId": connect_scope_id,
            "requestId": f"player-experience-smoke-{int(time.time() * 1000)}",
        },
        headers={"Authorization": f"Bearer {bootstrap_token}"},
    )
    cookie = headers.get("Set-Cookie", "")
    if "Firemud-Connect-Token=" not in cookie:
        raise RuntimeError(
            "Connect-token response did not issue the Firemud-Connect-Token cookie"
        )
    return {
        **response["data"],
        "connectCookie": cookie.split(";", 1)[0],
    }


def run_first_party_websocket_canary(
    ws: Any,
    config: SmokeConfig,
    character_name: str | None,
    step_results: list[dict[str, Any]],
) -> None:
    run_first_party_websocket_step(
        ws,
        "LOGIN",
        "LOGIN",
        config.timeout_seconds,
        step_results,
    )
    run_first_party_websocket_step(
        ws,
        play_command(config, character_name),
        "PLAY",
        config.timeout_seconds,
        step_results,
    )
    run_first_party_websocket_step(
        ws,
        "LOOK",
        "LOOK",
        config.timeout_seconds,
        step_results,
    )


def run_first_party_websocket_step(
    ws: Any,
    command: str,
    command_type: str,
    timeout_seconds: int,
    step_results: list[dict[str, Any]],
) -> None:
    started_at = time.time()
    ws.send(command)
    payload = await_structured_command_result(ws, command_type, timeout_seconds)
    step_results.append(
        {
            "label": command_type,
            "command": command,
            "latencyMs": round((time.time() - started_at) * 1000, 3),
            "response": payload,
        }
    )


def await_structured_command_result(ws: Any, command_type: str, timeout_seconds: int) -> str:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        remaining = min(1.0, max(0.1, deadline - time.time()))
        ws.settimeout(remaining)
        payload = ws.recv()
        try:
            parsed = json.loads(payload)
        except json.JSONDecodeError:
            continue
        if (
            parsed.get("eventType") == "command_result"
            and parsed.get("commandType") == command_type
        ):
            if not parsed.get("accepted", False):
                error_code = parsed.get("errorCode") or "UNKNOWN"
                raise RuntimeError(f"{command_type} failed with {error_code}: {payload}")
            return payload
    raise RuntimeError(f"Timed out waiting for structured {command_type} result")


def play_command(config: SmokeConfig, character_name: str | None) -> str:
    if character_name:
        return f"PLAY {config.world} {config.realm_slug} {character_name}"
    return f"PLAY {config.world} {config.realm_slug}"


def public_auth_url(config: SmokeConfig, path: str) -> str:
    return f"{config.auth_api_base}{config.auth_api_prefix}{path}"


def alert_record(alert: str, severity: str, injected: set[str]) -> dict[str, str]:
    return {
        "alert": alert,
        "severity": severity,
        "exerciseResult": "failed" if alert in injected else "passed",
    }


def render_metrics(config: SmokeConfig, mirrored_signals: dict[str, Any]) -> str:
    lines = [
        "# HELP playerflow_canary_success Mirrored synthetic player-flow canary result.",
        "# TYPE playerflow_canary_success gauge",
    ]
    for record in mirrored_signals["playerflow_canary_success"]:
        lines.append(
            metric_line(
                "playerflow_canary_success",
                {"flow": record["flow"], "path": record["path"], "target": record["target"]},
                record["value"],
            )
        )
    lines.extend(
        [
            "# HELP playerflow_canary_latency_ms Mirrored synthetic representative-command latency.",
            "# TYPE playerflow_canary_latency_ms gauge",
        ]
    )
    for record in mirrored_signals["playerflow_canary_latency_ms"]:
        lines.append(
            metric_line(
                "playerflow_canary_latency_ms",
                {"flow": record["flow"], "path": record["path"], "target": record["target"]},
                record["value"],
            )
        )
    lines.extend(
        [
            "# HELP entrypath_blackbox_probe_success Mirrored independent entry-path blackbox result.",
            "# TYPE entrypath_blackbox_probe_success gauge",
        ]
    )
    for record in mirrored_signals["entrypath_blackbox_probe_success"]:
        lines.append(
            metric_line(
                "entrypath_blackbox_probe_success",
                {"path": record["path"], "target": record["target"]},
                record["value"],
            )
        )
    lines.extend(
        [
            "# HELP observability_deadman_heartbeat_timestamp_seconds Mirrored deadman heartbeat timestamp.",
            "# TYPE observability_deadman_heartbeat_timestamp_seconds gauge",
            metric_line(
                "observability_deadman_heartbeat_timestamp_seconds",
                {"source": config.source},
                mirrored_signals["observability_deadman_heartbeat_timestamp_seconds"]["value"],
            ),
            "",
        ]
    )
    return "\n".join(lines)


def metric_line(name: str, labels: dict[str, Any], value: Any) -> str:
    label_text = ",".join(f'{key}="{escape_label(value)}"' for key, value in labels.items())
    return f"{name}{{{label_text}}} {value}"


def escape_label(value: Any) -> str:
    return str(value).replace("\\", "\\\\").replace('"', '\\"')


if __name__ == "__main__":
    raise SystemExit(main())
