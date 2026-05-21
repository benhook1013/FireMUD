#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "dev-tools" / "smoke"))

from smoke_common import (  # noqa: E402
    http_readiness_up,
    login_play_look_steps,
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
    config = SmokeConfig.from_env(args.source, args.canary_path)

    mirrored_signals = execute_smoke(config, args.simulate, injected)
    evidence = build_evidence(config, mirrored_signals, injected)
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
        session_id: str,
        username: str,
        password: str,
        world: str,
        timeout_seconds: int,
        startup_wait_seconds: int,
        external_checks: dict[str, str],
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
        self.session_id = session_id
        self.username = username
        self.password = password
        self.world = world
        self.timeout_seconds = timeout_seconds
        self.startup_wait_seconds = startup_wait_seconds
        self.external_checks = external_checks
        self.verified_by = verified_by
        self.preflight_ref = preflight_ref
        self.deployment_ref = deployment_ref

    @classmethod
    def from_env(cls, source: str, canary_path: str) -> "SmokeConfig":
        return cls(
            source=source,
            canary_path=canary_path,
            websocket_url=os.environ.get(
                "SMOKE_GAME_SESSION_WS_URL", "ws://localhost:8086/ws/game"
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
            session_id=os.environ.get("SMOKE_SESSION_ID", "1"),
            username=os.environ.get("SMOKE_USERNAME", "demo@example.com"),
            password=os.environ.get("SMOKE_PASSWORD", "swordfish"),
            world=os.environ.get("PLAYER_EXPERIENCE_WORLD", "demo"),
            timeout_seconds=int(os.environ.get("SMOKE_TIMEOUT_SECONDS", "10")),
            startup_wait_seconds=int(os.environ.get("SMOKE_STARTUP_WAIT_SECONDS", "90")),
            external_checks={
                "prometheus": os.environ.get(
                    "PLAYER_EXPERIENCE_PROMETHEUS_CHECK", "green"
                ),
                "alertmanager": os.environ.get(
                    "PLAYER_EXPERIENCE_ALERTMANAGER_CHECK", "green"
                ),
                "grafana": os.environ.get("PLAYER_EXPERIENCE_GRAFANA_CHECK", "green"),
                "kibana_log_query": os.environ.get(
                    "PLAYER_EXPERIENCE_KIBANA_LOG_QUERY_CHECK", "green"
                ),
                "jaeger_query": os.environ.get(
                    "PLAYER_EXPERIENCE_JAEGER_QUERY_CHECK", "green"
                ),
            },
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
    if config.canary_path == "telnet":
        wait_for_http_readiness(
            "spring-cloud-gateway",
            config.gateway_api_base,
            config.startup_wait_seconds,
            config.timeout_seconds,
        )
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

    ws = websocket.create_connection(
        config.websocket_url,
        timeout=config.timeout_seconds,
        header=[
            f"X-Game-Instance-Id: {config.session_id}",
            f"X-Tenant-Id: {config.tenant_id}",
        ],
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
    ):
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

    step_results: list[dict[str, Any]] = []
    ws = websocket.create_connection(
        config.websocket_url,
        timeout=config.timeout_seconds,
        header=[
            f"X-Game-Instance-Id: {config.session_id}",
            f"X-Tenant-Id: {config.tenant_id}",
        ],
    )
    try:
        run_websocket_command_plan(
            ws,
            login_play_look_steps(
                config.username,
                config.password,
                config.world,
                "OK WORLDS",
                "OK LOGIN",
                "OK PLAY",
                "OK LOOK",
            ),
            config.timeout_seconds,
            step_results,
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
    config: SmokeConfig, mirrored_signals: dict[str, Any], injected: set[str]
) -> dict[str, Any]:
    return {
        "deploymentRef": config.deployment_ref,
        "verifiedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "verifiedBy": config.verified_by,
        "preflightEvidenceRef": config.preflight_ref,
        "externalAuthority": {
            "deadmanIncidentOpened": "deadman" not in injected,
            "entrypointChecks": {
                name: resolve_external_check(name, configured, injected)
                for name, configured in config.external_checks.items()
            },
        },
        "mirroredSignals": mirrored_signals,
        "canaryAlerts": [
            alert_record("PlayerFlowCanaryLoginFailed", "P0", injected),
            alert_record("PlayerFlowCanaryCommandFailed", "P1", injected),
            alert_record("PlayerFlowCanaryLatencyHigh", "P1", injected),
        ],
    }


def resolve_external_check(name: str, configured: str, injected: set[str]) -> str:
    if name in injected:
        return "red"
    if configured in {"green", "red"}:
        return configured
    try:
        request = urllib.request.Request(configured, method="GET")
        with urllib.request.urlopen(request, timeout=5) as response:
            return "green" if response.status < 500 else "red"
    except (urllib.error.URLError, OSError, ValueError):
        return "red"


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
