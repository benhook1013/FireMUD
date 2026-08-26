#!/usr/bin/env python3

from __future__ import annotations

import argparse
import copy
import datetime as dt
import importlib.util
import json
import math
import os
import socket
import sys
import time
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any
from urllib.parse import urlencode, urlparse

if str(Path(__file__).resolve().parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
from numeric_validation import (
    format_bounded_positive_seconds_error,
    is_bounded_positive_seconds,
    is_finite_number,
    parse_bounded_positive_seconds,
)
from observability_contract import (
    OMITTED_QUERYABILITY_CAPABILITY,
)

_EVIDENCE_VALIDATOR_PATH = (
    Path(__file__).resolve().with_name("validate-player-experience-smoke-evidence.py")
)
_EVIDENCE_VALIDATOR_SPEC = importlib.util.spec_from_file_location(
    "player_experience_smoke_evidence_validator", _EVIDENCE_VALIDATOR_PATH
)
if _EVIDENCE_VALIDATOR_SPEC is None or _EVIDENCE_VALIDATOR_SPEC.loader is None:
    raise ImportError(f"Unable to load evidence validator at {_EVIDENCE_VALIDATOR_PATH}")
_EVIDENCE_VALIDATOR = importlib.util.module_from_spec(_EVIDENCE_VALIDATOR_SPEC)
_EVIDENCE_VALIDATOR_SPEC.loader.exec_module(_EVIDENCE_VALIDATOR)

METRIC_TARGET_BY_PATH = _EVIDENCE_VALIDATOR.METRIC_TARGET_BY_PATH
PROMETHEUS_MIRRORS_CAPABILITY = "prometheusMirrors"
PLAYER_FLOW_CANARY_CAPABILITY = "playerFlowCanary"
DEFAULT_SMOKE_USERNAME = "demo@example.com"
DEFAULT_SMOKE_PASSWORD = "swordfish"
PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC = (
    "playerflow_canary_last_run_timestamp_seconds"
)
PLAYERFLOW_CANARY_FRESHNESS_BUDGET_METRIC = (
    "playerflow_canary_freshness_budget_seconds"
)
DEFAULT_SIMULATED_DETECTION_BUDGET_SECONDS = 195
STALE_NUMERIC_TOLERANCE_SECONDS = _EVIDENCE_VALIDATOR.STALE_NUMERIC_TOLERANCE_SECONDS
CANARY_ALERT_MAX_HOLD_SECONDS = _EVIDENCE_VALIDATOR.CANARY_ALERT_MAX_HOLD_SECONDS
CANARY_ALERT_EVALUATION_MARGIN_SECONDS = (
    _EVIDENCE_VALIDATOR.CANARY_ALERT_EVALUATION_MARGIN_SECONDS
)
MIN_CANARY_DETECTION_BUDGET_SECONDS = (
    CANARY_ALERT_MAX_HOLD_SECONDS + CANARY_ALERT_EVALUATION_MARGIN_SECONDS
)
CAPABILITY_VALUES = {
    PROMETHEUS_MIRRORS_CAPABILITY: {"published", "omitted"},
    PLAYER_FLOW_CANARY_CAPABILITY: {"advertised", "omitted"},
}
CANARY_IDENTITY_REQUIRED_FIELDS = frozenset(
    {
        "authority",
        "classification",
        "analyticsSloExclusion",
        "credentials",
        "transportCharacters",
        "evidenceRef",
    }
)
# The repository currently has no shipped Account-owned verifier that can bind
# synthetic classification and per-transport character identities to a smoke
# execution. Local JSON therefore cannot authorize an advertised canary.
AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE = False
FAILURE_INJECTION_SIGNAL_VALUES = frozenset(
    {"websocket", "telnet", "login", "command", "deadman"}
)
FAILURE_INJECTION_ALERT_VALUES = frozenset(
    {
        "PlayerFlowCanaryLoginFailed",
        "PlayerFlowCanaryCommandFailed",
        "PlayerFlowCanaryLatencyHigh",
        "PlayerFlowCanaryEvidenceStale",
    }
)
FAILURE_INJECTION_VALUES = (
    FAILURE_INJECTION_SIGNAL_VALUES | FAILURE_INJECTION_ALERT_VALUES
)

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "dev-tools" / "smoke"))

from smoke_common import (
    ProbeOperationalFailure,
    http_request_json,
    http_request_json_with_headers,
    login_play_look_steps,
    quote_path,
    recv_until_socket,
    run_telnet_command_plan,
    verify_smoke_account,
    wait_for_account_schema,
    wait_for_http_readiness,
)


class PlayerFlowProbeFailure(ProbeOperationalFailure):
    """Expected transport/upstream failure captured as zero-valued evidence."""


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
            "deadman and public-path checks. Required for non-simulated prod-like "
            "smoke. The evidence profile may explicitly be independent-omitted."
        ),
    )
    parser.add_argument(
        "--failure-injection",
        default=os.environ.get("PLAYER_EXPERIENCE_FAILURE_INJECTION", ""),
        help=(
            "Comma-separated synthetic failure flags. Signal values are "
            "websocket,telnet,login,command,deadman; alert-family values "
            "PlayerFlowCanaryLoginFailed,PlayerFlowCanaryCommandFailed,"
            "PlayerFlowCanaryLatencyHigh,PlayerFlowCanaryEvidenceStale only "
            "retain a failed incident-diagnostic exerciseResult. They do not "
            "execute the alert path or create passing gate evidence. Signal "
            "injection changes only locally produced mirrors and canary records; "
            "retained external-authority observations remain unchanged."
        ),
    )
    parser.add_argument(
        "--allow-failure-evidence",
        action="store_true",
        help=(
            "Allow fresh retained external-authority evidence whose current "
            "deadman or exposed public-path status is red, so the runner can "
            "preserve a non-authorizing incident artifact. This does not mark "
            "unexercised alert paths passed. Omit this option for readiness, "
            "recovery, and promotion evidence."
        ),
    )
    parser.add_argument(
        "--source",
        default=os.environ.get("PLAYER_EXPERIENCE_SOURCE", "local"),
        help="Low-cardinality source label for the deadman heartbeat mirror.",
    )
    parser.add_argument(
        "--deployment-event-id",
        default=os.environ.get("PLAYER_EXPERIENCE_DEPLOYMENT_EVENT_ID"),
        help=(
            "Canonical UUID for this deployment apply event. Promotion/staging "
            "evidence should set it; standalone/local evidence may omit it."
        ),
    )
    parser.add_argument(
        "--canary-path",
        choices=("websocket", "telnet"),
        default=os.environ.get("PLAYER_EXPERIENCE_CANARY_PATH", "websocket"),
        help=(
            "Preferred first exposed transport for the login + representative "
            "command canary; all exposed paths are exercised."
        ),
    )
    parser.add_argument(
        "--prometheus-mirrors",
        choices=tuple(sorted(CAPABILITY_VALUES[PROMETHEUS_MIRRORS_CAPABILITY])),
        default=os.environ.get("PLAYER_EXPERIENCE_PROMETHEUS_MIRRORS", "published"),
        help="Whether this evidence advertises optional Prometheus mirror signals.",
    )
    parser.add_argument(
        "--player-flow-canary",
        choices=tuple(sorted(CAPABILITY_VALUES[PLAYER_FLOW_CANARY_CAPABILITY])),
        default=os.environ.get("PLAYER_EXPERIENCE_PLAYER_FLOW_CANARY", "advertised"),
        help="Whether this evidence advertises the independent player-flow canary.",
    )
    parser.add_argument(
        "--synthetic-identity-evidence",
        type=Path,
        default=Path(os.environ["PLAYER_EXPERIENCE_SYNTHETIC_IDENTITY_EVIDENCE"])
        if os.environ.get("PLAYER_EXPERIENCE_SYNTHETIC_IDENTITY_EVIDENCE")
        else None,
        help=(
            "Path to authoritative synthetic identity/isolation evidence. An "
            "advertised canary is downgraded to omitted when this attestation "
            "is absent or invalid."
        ),
    )
    parser.add_argument(
        "--queryability-profile",
        default=os.environ.get("PLAYER_EXPERIENCE_QUERYABILITY_PROFILE"),
        help=(
            "The selected deployment profile for the queryability omission "
            "record. Required together with --queryability-freshness-budget-seconds."
        ),
    )
    parser.add_argument(
        "--queryability-freshness-budget-seconds",
        default=os.environ.get(
            "PLAYER_EXPERIENCE_QUERYABILITY_FRESHNESS_BUDGET_SECONDS"
        ),
        help=(
            "Positive finite profile-resolved freshness budget for the "
            "queryability omission record."
        ),
    )
    args = parser.parse_args()

    try:
        injected = parse_failure_injection(args.failure_injection)
    except ValueError as exc:
        parser.error(str(exc))
    config = SmokeConfig.from_env(
        args.source,
        args.canary_path,
        args.external_authority_evidence,
        args.prometheus_mirrors,
        args.player_flow_canary,
        deployment_event_id=args.deployment_event_id,
        synthetic_identity_evidence=args.synthetic_identity_evidence,
        queryability_profile=args.queryability_profile,
        queryability_freshness_budget_seconds=args.queryability_freshness_budget_seconds,
    )
    try:
        validate_queryability_omission_config(config)
    except ValueError as exc:
        parser.error(str(exc))
    execution_mode = "simulated" if args.simulate else "live"
    requested_canary_advertised = config.player_flow_canary == "advertised"
    authority_provenance = (
        "synthetic"
        if args.simulate and args.external_authority_evidence is None
        else "retained-external"
    )
    authority_verified_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    evaluation_epoch = dt.datetime.fromisoformat(
        authority_verified_at.replace("Z", "+00:00")
    ).timestamp()
    initial_external_authority = resolve_external_authority(
        config,
        args.simulate,
        evaluation_epoch,
        allow_failure_evidence=args.allow_failure_evidence,
        # Validate the base external-authority posture before deciding whether
        # the local canary may remain advertised. A missing or invalid
        # synthetic identity must be able to downgrade an omitted profile
        # without making canary-only budget fields mandatory.
        canary_advertised=False,
        allow_unadvertised_canary_budget=requested_canary_advertised,
    )
    establish_player_flow_canary_capability(config, initial_external_authority)
    if (
        config.player_flow_canary == "advertised"
        and config.external_authority_evidence is not None
    ):
        validate_external_authority_shape(
            initial_external_authority,
            config.external_authority_evidence or Path("<synthetic external authority>"),
            evaluation_epoch=evaluation_epoch,
            canary_advertised=True,
            allow_failure_evidence=args.allow_failure_evidence,
        )

    mirrored_signals = execute_smoke(
        config, args.simulate, injected, initial_external_authority
    )
    verified_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    final_evaluation_epoch = dt.datetime.fromisoformat(
        verified_at.replace("Z", "+00:00")
    ).timestamp()
    # Re-read retained authority after the smoke run and require the stable
    # profile/path/budget fields to match the snapshot used for mirrors.
    reread_external_authority = resolve_external_authority(
        config,
        args.simulate,
        final_evaluation_epoch,
        allow_failure_evidence=args.allow_failure_evidence,
        canary_advertised=config.player_flow_canary == "advertised",
        allow_unadvertised_canary_budget=requested_canary_advertised,
    )
    compare_external_authority_snapshots(
        initial_external_authority, reread_external_authority
    )
    external_authority = reread_external_authority
    if (
        config.player_flow_canary != "advertised"
        and external_authority.get("profile") == "independent-omitted"
    ):
        # The detection budget is only meaningful for an advertised local
        # canary. Remove it when an unverified canary is downgraded so the
        # retained authority remains a valid independent-omitted record.
        external_authority.pop("detectionBudgetSeconds", None)
    synchronize_deadman_heartbeat_mirror(
        mirrored_signals, external_authority, injected
    )
    validate_external_authority_freshness(
        external_authority,
        config.external_authority_evidence or Path("<synthetic external authority>"),
        final_evaluation_epoch,
    )
    evidence = build_evidence(
        config,
        mirrored_signals,
        external_authority,
        injected,
        execution_mode,
        authority_provenance,
        verified_at,
    )
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
        telnet_host: str,
        telnet_port: int,
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
        deployment_event_id: str | None = None,
        prometheus_mirrors: str = "published",
        player_flow_canary: str = "advertised",
        synthetic_identity_evidence: Path | None = None,
        queryability_profile: str | None = None,
        queryability_freshness_budget_seconds: str | float | None = None,
    ) -> None:
        self.source = source
        self.canary_path = canary_path
        self.websocket_url = websocket_url
        self.telnet_host = telnet_host
        self.telnet_port = telnet_port
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
        self.deployment_event_id = validate_deployment_event_id(deployment_event_id)
        self.prometheus_mirrors = validate_capability(
            prometheus_mirrors, PROMETHEUS_MIRRORS_CAPABILITY
        )
        self.player_flow_canary = validate_capability(
            player_flow_canary, PLAYER_FLOW_CANARY_CAPABILITY
        )
        self.synthetic_identity_evidence = synthetic_identity_evidence
        self.queryability_profile = queryability_profile
        self.queryability_freshness_budget_seconds = parse_optional_positive_finite_number(
            queryability_freshness_budget_seconds,
            "queryabilityFreshnessBudgetSeconds",
        )
        self.player_flow_canary_identity: dict[str, Any] | None = None
        self._validated_player_flow_canary_paths: frozenset[str] = frozenset()

    @classmethod
    def from_env(
        cls,
        source: str,
        canary_path: str,
        external_authority_evidence: Path | None,
        prometheus_mirrors: str | None = None,
        player_flow_canary: str | None = None,
        deployment_event_id: str | None = None,
        synthetic_identity_evidence: Path | None = None,
        queryability_profile: str | None = None,
        queryability_freshness_budget_seconds: str | float | None = None,
    ) -> SmokeConfig:
        prometheus_mirrors = prometheus_mirrors or os.environ.get(
            "PLAYER_EXPERIENCE_PROMETHEUS_MIRRORS", "published"
        )
        player_flow_canary = player_flow_canary or os.environ.get(
            "PLAYER_EXPERIENCE_PLAYER_FLOW_CANARY", "advertised"
        )
        if deployment_event_id is None:
            deployment_event_id = os.environ.get("PLAYER_EXPERIENCE_DEPLOYMENT_EVENT_ID")
        queryability_profile = queryability_profile or os.environ.get(
            "PLAYER_EXPERIENCE_QUERYABILITY_PROFILE"
        )
        queryability_freshness_budget_seconds = (
            queryability_freshness_budget_seconds
            if queryability_freshness_budget_seconds is not None
            else os.environ.get("PLAYER_EXPERIENCE_QUERYABILITY_FRESHNESS_BUDGET_SECONDS")
        )
        return cls(
            source=source,
            canary_path=canary_path,
            websocket_url=os.environ.get(
                "PLAYER_EXPERIENCE_WEBSOCKET_URL",
                websocket_url_from_http_base(
                    os.environ.get("SMOKE_GATEWAY_API_BASE", "http://localhost:8080")
                ),
            ),
            telnet_host=os.environ.get("SMOKE_TELNET_HOST", "localhost"),
            telnet_port=int(os.environ.get("TCP_PROXY_PORT", "2323")),
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
            username=os.environ.get("SMOKE_USERNAME", DEFAULT_SMOKE_USERNAME),
            password=os.environ.get("SMOKE_PASSWORD", DEFAULT_SMOKE_PASSWORD),
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
            deployment_event_id=deployment_event_id,
            prometheus_mirrors=prometheus_mirrors,
            player_flow_canary=player_flow_canary,
            synthetic_identity_evidence=synthetic_identity_evidence,
            queryability_profile=queryability_profile,
            queryability_freshness_budget_seconds=queryability_freshness_budget_seconds,
        )


def parse_failure_injection(raw: str) -> set[str]:
    values = {item.strip() for item in raw.split(",") if item.strip()}
    unsupported = sorted(values - FAILURE_INJECTION_VALUES)
    if unsupported:
        raise ValueError(
            "unsupported failure-injection token(s): " + ", ".join(unsupported)
        )
    return values


def validate_capability(value: str, key: str) -> str:
    allowed_values = CAPABILITY_VALUES[key]
    if not isinstance(value, str) or value not in allowed_values:
        raise ValueError(
            f"{key} must be one of {', '.join(sorted(allowed_values))}"
        )
    return value


def parse_optional_positive_finite_number(
    value: str | float | None, key: str
) -> float | None:
    if value is None:
        return None
    return parse_bounded_positive_seconds(value, key)


def validate_queryability_omission_config(config: SmokeConfig) -> None:
    if not isinstance(config.queryability_profile, str) or not config.queryability_profile.strip():
        raise ValueError(
            "queryability profile is required; set --queryability-profile or "
            "PLAYER_EXPERIENCE_QUERYABILITY_PROFILE"
        )
    if config.queryability_freshness_budget_seconds is None:
        raise ValueError(
            "queryability freshness budget is required; set "
            "--queryability-freshness-budget-seconds or "
            "PLAYER_EXPERIENCE_QUERYABILITY_FRESHNESS_BUDGET_SECONDS"
        )


def load_synthetic_identity_evidence(path: Path | None) -> dict[str, Any] | None:
    if path is None:
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def validate_synthetic_identity_evidence(
    evidence: dict[str, Any] | None,
    exposed_paths: set[str],
    config: SmokeConfig,
) -> bool:
    if not isinstance(evidence, dict):
        return False
    if set(evidence) != CANARY_IDENTITY_REQUIRED_FIELDS:
        return False
    if evidence.get("authority") != "account-service":
        return False
    if evidence.get("classification") != "synthetic":
        return False
    if evidence.get("analyticsSloExclusion") is not True:
        return False
    if not isinstance(evidence.get("evidenceRef"), str) or not evidence["evidenceRef"].strip():
        return False
    credentials = evidence.get("credentials")
    if (
        not isinstance(credentials, dict)
        or set(credentials) != {"nonDefault", "productionSafe"}
        or credentials.get("nonDefault") is not True
        or credentials.get("productionSafe") is not True
    ):
        return False
    if config.username == DEFAULT_SMOKE_USERNAME or config.password == DEFAULT_SMOKE_PASSWORD:
        return False
    transport_characters = evidence.get("transportCharacters")
    if not isinstance(transport_characters, dict) or not exposed_paths.issubset(
        set(transport_characters)
    ):
        return False
    for path in exposed_paths:
        identity = transport_characters[path]
        if (
            not isinstance(identity, dict)
            or set(identity) != {"restricted", "isolated"}
            or identity.get("restricted") is not True
            or identity.get("isolated") is not True
        ):
            return False
    return True


def establish_player_flow_canary_capability(
    config: SmokeConfig, external_authority: dict[str, Any]
) -> None:
    if config.player_flow_canary != "advertised":
        return
    if not AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE:
        config.player_flow_canary = "omitted"
        config.player_flow_canary_identity = None
        config._validated_player_flow_canary_paths = frozenset()
        print(
            "WARNING: authoritative Account synthetic identity verification is not "
            "implemented; player-flow canary downgraded to omitted",
            file=sys.stderr,
        )
        return
    exposed_paths = declared_exposed_paths(external_authority)
    identity = load_synthetic_identity_evidence(config.synthetic_identity_evidence)
    if not validate_synthetic_identity_evidence(identity, exposed_paths, config):
        config.player_flow_canary = "omitted"
        config.player_flow_canary_identity = None
        config._validated_player_flow_canary_paths = frozenset()
        print(
            "WARNING: player-flow canary identity/isolation evidence is absent or "
            "invalid; playerFlowCanary downgraded to omitted",
            file=sys.stderr,
        )
        return
    config.player_flow_canary_identity = identity
    config._validated_player_flow_canary_paths = frozenset(exposed_paths)


def validate_deployment_event_id(value: str | None) -> str | None:
    if value is None:
        return None
    value = value.strip()
    if not value:
        return None
    try:
        parsed = uuid.UUID(value)
    except ValueError as exc:
        raise ValueError("deploymentEventId must be a UUID") from exc
    if str(parsed) != value:
        raise ValueError("deploymentEventId must use canonical UUID form")
    return value


def websocket_url_from_http_base(http_base: str) -> str:
    parsed = urlparse(http_base)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    port = f":{parsed.port}" if parsed.port is not None else ""
    return f"{scheme}://{parsed.hostname}{port}/ws/game"


def execute_smoke(
    config: SmokeConfig,
    simulate: bool,
    injected: set[str],
    external_authority: dict[str, Any],
) -> dict[str, Any]:
    enforce_authoritative_canary_gate(config)
    if simulate:
        return simulated_signals(config, injected, external_authority)

    exposed_paths = declared_exposed_paths(external_authority)
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
    if (
        "telnet" in exposed_paths
        and (
            config.player_flow_canary == "advertised"
            or config.prometheus_mirrors == "published"
        )
    ):
        wait_for_http_readiness(
            "tcp-proxy-service",
            config.tcp_proxy_api_base,
            config.startup_wait_seconds,
            config.timeout_seconds,
        )
    verify_smoke_account(
        config.account_api_base,
        config.username,
        config.password,
        config.timeout_seconds,
    )

    signals: dict[str, Any] = {}
    signals.update(
        entrypath_signals(
            config, injected, exposed_paths, live_entrypath_record
        )
    )
    if config.player_flow_canary == "advertised":
        playerflow_success, playerflow_latency, playerflow_last_run = run_playerflow_canaries(
            config, injected, exposed_paths, external_authority["profile"]
        )
        signals["playerflow_canary_success"] = playerflow_success
        signals["playerflow_canary_latency_ms"] = playerflow_latency
        signals[PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC] = playerflow_last_run
        freshness_budget = canary_freshness_budget_record(external_authority)
        signals[PLAYERFLOW_CANARY_FRESHNESS_BUDGET_METRIC] = freshness_budget
    if (
        config.prometheus_mirrors == "published"
        and external_authority["profile"] == "independent-required"
    ):
        signals["observability_deadman_heartbeat_timestamp_seconds"] = deadman_record(
            config, injected, external_authority
        )
    return signals


def simulated_signals(
    config: SmokeConfig,
    injected: set[str],
    external_authority: dict[str, Any],
) -> dict[str, Any]:
    enforce_authoritative_canary_gate(config)
    now = time.time()
    exposed_paths = declared_exposed_paths(external_authority)
    signals: dict[str, Any] = {}
    signals.update(
        entrypath_signals(
            config, injected, exposed_paths, simulated_entrypath_record
        )
    )
    if config.player_flow_canary == "advertised":
        (
            signals["playerflow_canary_success"],
            signals["playerflow_canary_latency_ms"],
            signals[PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC],
        ) = simulated_playerflow_canaries(
            config, injected, exposed_paths, external_authority["profile"]
        )
        freshness_budget = canary_freshness_budget_record(external_authority)
        signals[PLAYERFLOW_CANARY_FRESHNESS_BUDGET_METRIC] = freshness_budget
    if (
        config.prometheus_mirrors == "published"
        and external_authority["profile"] == "independent-required"
    ):
        signals["observability_deadman_heartbeat_timestamp_seconds"] = {
            "source": config.source,
            "value": (
                stale_deadman_heartbeat_timestamp(external_authority, now)
                if "deadman" in injected
                else external_authority_heartbeat_timestamp(external_authority)
            ),
        }
    return signals


def enforce_authoritative_canary_gate(
    config: SmokeConfig, mirrored_signals: dict[str, Any] | None = None
) -> None:
    """Prevent lower-level producer helpers from emitting an unverified canary."""
    if config.player_flow_canary == "omitted":
        _scrub_canary_mirrored_signals(mirrored_signals)
        return
    if config.player_flow_canary != "advertised" or _canary_authorization_is_valid(config):
        return
    config.player_flow_canary = "omitted"
    config.player_flow_canary_identity = None
    config._validated_player_flow_canary_paths = frozenset()
    _scrub_canary_mirrored_signals(mirrored_signals)


def _scrub_canary_mirrored_signals(
    mirrored_signals: dict[str, Any] | None,
) -> None:
    if mirrored_signals is None:
        return
    for key in (
        "playerflow_canary_success",
        "playerflow_canary_latency_ms",
        PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC,
        PLAYERFLOW_CANARY_FRESHNESS_BUDGET_METRIC,
    ):
        mirrored_signals.pop(key, None)


def _canary_authorization_is_valid(config: SmokeConfig) -> bool:
    if not AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE:
        return False
    validated_paths = getattr(config, "_validated_player_flow_canary_paths", None)
    if (
        not isinstance(validated_paths, frozenset)
        or not validated_paths
        or not all(isinstance(path, str) for path in validated_paths)
        or not validated_paths.issubset(METRIC_TARGET_BY_PATH)
        or not isinstance(config.canary_path, str)
        or config.canary_path not in validated_paths
    ):
        return False
    return validate_synthetic_identity_evidence(
        config.player_flow_canary_identity,
        set(validated_paths),
        config,
    )


def canary_producer_is_authorized(config: SmokeConfig) -> bool:
    """Return whether a direct canary producer may emit records."""
    authorized = (
        config.player_flow_canary == "advertised"
        and _canary_authorization_is_valid(config)
    )
    if not authorized:
        enforce_authoritative_canary_gate(config)
    return authorized


def declared_exposed_paths(external_authority: dict[str, Any]) -> set[str]:
    return set(external_authority["exposedPublicPlayerPaths"])


def ordered_canary_paths(config: SmokeConfig, exposed_paths: set[str]) -> list[str]:
    paths: list[str] = []
    if config.canary_path in exposed_paths:
        paths.append(config.canary_path)
    paths.extend(
        path
        for path in ("websocket", "telnet")
        if path in exposed_paths and path not in paths
    )
    return paths


def entrypath_signals(
    config: SmokeConfig,
    injected: set[str],
    exposed_paths: set[str],
    record_producer: Callable[[SmokeConfig, set[str], str], dict[str, Any]],
) -> dict[str, Any]:
    if config.prometheus_mirrors != "published":
        return {}
    records: list[dict[str, Any]] = []
    for path in ("websocket", "telnet"):
        if path not in exposed_paths:
            continue
        try:
            records.append(record_producer(config, injected, path))
        except PlayerFlowProbeFailure:
            # A failed live probe is itself actionable evidence. Keep the
            # artifact and let the freshness-gated alert classify the path.
            records.append(
                {
                    "path": path,
                    "target": metric_target_for_path(path),
                    "value": 0,
                }
            )
    return {"entrypath_blackbox_probe_success": records}


def live_entrypath_record(
    config: SmokeConfig, injected: set[str], path: str
) -> dict[str, Any]:
    if path == "websocket":
        return blackbox_websocket_record(config, injected)
    return blackbox_telnet_record(config, injected)


def simulated_entrypath_record(
    config: SmokeConfig, injected: set[str], path: str
) -> dict[str, Any]:
    return {
        "path": path,
        "target": metric_target_for_path(path),
        "value": 0 if path in injected else 1,
    }


def blackbox_websocket_record(config: SmokeConfig, injected: set[str]) -> dict[str, Any]:
    if "websocket" in injected:
        return {"path": "websocket", "target": metric_target_for_path("websocket"), "value": 0}
    import websocket  # type: ignore

    try:
        first_party = first_party_connect_context(config)
        ws = websocket.create_connection(
            config.websocket_url,
            timeout=config.timeout_seconds,
            header=[f"Cookie: {first_party['connectCookie']}"],
        )
        try:
            return {"path": "websocket", "target": metric_target_for_path("websocket"), "value": 1}
        finally:
            ws.close()
    except (OSError, ProbeOperationalFailure, websocket.WebSocketException) as exc:
        raise PlayerFlowProbeFailure(f"WebSocket blackbox probe failed: {exc}") from exc


def blackbox_telnet_record(config: SmokeConfig, injected: set[str]) -> dict[str, Any]:
    if "telnet" in injected:
        return {"path": "telnet", "target": metric_target_for_path("telnet"), "value": 0}
    try:
        with socket.create_connection(
            (config.telnet_host, config.telnet_port), timeout=config.timeout_seconds
        ) as sock:
            recv_until_socket(sock, "\n", config.timeout_seconds)
            run_telnet_command_plan(
                sock,
                [("WORLDS", ["OK WORLDS"], "WORLDS")],
                config.timeout_seconds,
            )
            return {"path": "telnet", "target": metric_target_for_path("telnet"), "value": 1}
    except (OSError, ProbeOperationalFailure) as exc:
        raise PlayerFlowProbeFailure(f"Telnet blackbox probe failed: {exc}") from exc


def run_playerflow_canary(
    config: SmokeConfig, injected: set[str]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    # This dispatcher is also a public/direct helper, so callers can bypass
    # establish_player_flow_canary_capability(). Keep that bypass fail-closed
    # before selecting a transport-specific producer.
    if not canary_producer_is_authorized(config):
        return [], []
    if config.canary_path == "websocket":
        return run_websocket_canary(config, injected)
    return run_telnet_canary(config, injected)


def run_playerflow_canaries(
    config: SmokeConfig,
    injected: set[str],
    exposed_paths: set[str],
    profile: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    if not canary_producer_is_authorized(config):
        return [], [], []
    success: list[dict[str, Any]] = []
    latency: list[dict[str, Any]] = []
    last_run: list[dict[str, Any]] = []
    for path in ordered_canary_paths(config, exposed_paths):
        path_config = copy.copy(config)
        path_config.canary_path = path
        try:
            path_success, path_latency = run_playerflow_canary(path_config, injected)
        except PlayerFlowProbeFailure:
            # Preserve a current failed attempt instead of aborting before the
            # runner can publish the zero-valued, freshness-gated canary state.
            path_success, path_latency = failed_canary_records(path_config)
        path_success = canary_records_with_profile(path_success, profile)
        path_latency = canary_records_with_profile(path_latency, profile)
        success.extend(path_success)
        latency.extend(path_latency)
        last_run.extend(canary_last_run_records(path_success, int(time.time()), profile))
    return success, latency, last_run


def failed_canary_records(
    config: SmokeConfig,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if not canary_producer_is_authorized(config):
        return [], []
    target = canary_target(config)
    return (
        [
            {"flow": flow, "path": config.canary_path, "target": target, "value": 0}
            for flow in ("login", "command")
        ],
        [
            {
                "flow": "command",
                "path": config.canary_path,
                "target": target,
                "value": 0,
            }
        ],
    )


def simulated_playerflow_canaries(
    config: SmokeConfig,
    injected: set[str],
    exposed_paths: set[str],
    profile: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    if not canary_producer_is_authorized(config):
        return [], [], []
    success: list[dict[str, Any]] = []
    latency: list[dict[str, Any]] = []
    last_run: list[dict[str, Any]] = []
    for path in ordered_canary_paths(config, exposed_paths):
        path_config = copy.copy(config)
        path_config.canary_path = path
        path_success, path_latency = simulated_canary_records(path_config, injected)
        path_success = canary_records_with_profile(path_success, profile)
        path_latency = canary_records_with_profile(path_latency, profile)
        success.extend(path_success)
        latency.extend(path_latency)
        last_run.extend(canary_last_run_records(path_success, int(time.time()), profile))
    return success, latency, last_run


def canary_last_run_records(
    success_records: list[dict[str, Any]], observed_at: int, profile: str
) -> list[dict[str, Any]]:
    return [
        {
            "flow": record["flow"],
            "path": record["path"],
            "target": record["target"],
            "profile": profile,
            "value": observed_at,
        }
        for record in success_records
    ]


def canary_records_with_profile(
    records: list[dict[str, Any]], profile: str
) -> list[dict[str, Any]]:
    return [{**record, "profile": profile} for record in records]


def run_websocket_canary(
    config: SmokeConfig, injected: set[str]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if not canary_producer_is_authorized(config):
        return [], []
    if "login" in injected or "command" in injected:
        return simulated_canary_records(config, injected)
    import websocket  # type: ignore

    try:
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
    except (OSError, ProbeOperationalFailure, websocket.WebSocketException) as exc:
        raise PlayerFlowProbeFailure(f"WebSocket player-flow canary failed: {exc}") from exc


def run_telnet_canary(
    config: SmokeConfig, injected: set[str]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if not canary_producer_is_authorized(config):
        return [], []
    if "login" in injected or "command" in injected:
        return simulated_canary_records(config, injected)
    try:
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
    except (OSError, ProbeOperationalFailure) as exc:
        raise PlayerFlowProbeFailure(f"Telnet player-flow canary failed: {exc}") from exc


def simulated_canary_records(
    config: SmokeConfig, injected: set[str]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    # This lower-level producer is directly importable and is reachable from
    # transport helpers when failure injection is active.
    if not canary_producer_is_authorized(config):
        return [], []
    login_succeeded = "login" not in injected
    command_succeeded = login_succeeded and "command" not in injected
    success = [
        {
            "flow": "login",
            "path": config.canary_path,
            "target": canary_target(config),
            "value": 1 if login_succeeded else 0,
        },
        {
            "flow": "command",
            "path": config.canary_path,
            "target": canary_target(config),
            # This metric is the complete representative-command journey. A
            # failed LOGIN means LOOK was not reached, so the journey cannot
            # be reported successful even when command failure was not also
            # requested explicitly.
            "value": 1 if command_succeeded else 0,
        },
    ]
    latency = [
        {
            "flow": "command",
            "path": config.canary_path,
            "target": canary_target(config),
            # Zero records that no successful command-completion latency was
            # observed for the failed/unreached journey.
            "value": 125 if command_succeeded else 0,
        }
    ]
    return success, latency


def canary_records_from_steps(
    config: SmokeConfig, step_results: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if not canary_producer_is_authorized(config):
        return [], []
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


def external_authority_heartbeat_timestamp(
    external_authority: dict[str, Any],
) -> float:
    observed_at = external_authority.get("lastSuccessfulHeartbeatObservedAt")
    if not isinstance(observed_at, str) or not observed_at.endswith("Z"):
        raise RuntimeError(
            "A published deadman heartbeat mirror requires "
            "lastSuccessfulHeartbeatObservedAt as an RFC3339 UTC timestamp"
        )
    try:
        timestamp = dt.datetime.fromisoformat(
            observed_at.replace("Z", "+00:00")
        ).timestamp()
    except ValueError as exc:
        raise RuntimeError(
            "A published deadman heartbeat mirror requires a valid "
            "lastSuccessfulHeartbeatObservedAt timestamp"
        ) from exc
    if not is_finite_number(timestamp) or timestamp <= 0:
        raise RuntimeError(
            "A published deadman heartbeat mirror requires a positive finite "
            "lastSuccessfulHeartbeatObservedAt timestamp"
        )
    return timestamp


def stale_deadman_heartbeat_timestamp(
    external_authority: dict[str, Any], now: float
) -> float:
    stale_threshold = external_authority.get("staleThresholdSeconds")
    if not is_bounded_positive_seconds(stale_threshold):
        raise RuntimeError(format_bounded_positive_seconds_error("staleThresholdSeconds"))
    timestamp = now - stale_threshold - 1
    if not is_finite_number(timestamp) or timestamp <= 0:
        raise RuntimeError(
            "A deadman failure injection could not produce a positive finite heartbeat timestamp"
        )
    return timestamp


def deadman_record(
    config: SmokeConfig,
    injected: set[str],
    external_authority: dict[str, Any],
) -> dict[str, Any]:
    return {
        "source": config.source,
        "value": (
            stale_deadman_heartbeat_timestamp(external_authority, time.time())
            if "deadman" in injected
            else external_authority_heartbeat_timestamp(external_authority)
        ),
    }


def synchronize_deadman_heartbeat_mirror(
    mirrored_signals: dict[str, Any],
    external_authority: dict[str, Any],
    injected: set[str],
) -> None:
    if "deadman" in injected:
        return
    record = mirrored_signals.get(
        "observability_deadman_heartbeat_timestamp_seconds"
    )
    if not isinstance(record, dict):
        return
    # The retained authority may observe a new successful heartbeat while the
    # public-path smoke is running. Bind the final artifact to the final
    # retained snapshot instead of preserving the earlier observation.
    record["value"] = external_authority_heartbeat_timestamp(external_authority)


def canary_freshness_budget_record(
    external_authority: dict[str, Any]
) -> dict[str, Any]:
    budget = external_authority.get("detectionBudgetSeconds")
    if budget is None:
        raise RuntimeError(
            "An advertised player-flow canary requires detectionBudgetSeconds "
            "to publish playerflow_canary_freshness_budget_seconds"
        )
    return {"profile": external_authority["profile"], "value": budget}


def canary_target(config: SmokeConfig) -> str:
    return metric_target_for_path(config.canary_path)


def metric_target_for_path(path: str) -> str:
    try:
        return METRIC_TARGET_BY_PATH[path]
    except (KeyError, TypeError) as exc:
        raise ValueError(f"Unsupported public player path {path!r}") from exc


def queryability_omission_record(config: SmokeConfig, verified_at: str) -> dict[str, Any]:
    validate_queryability_omission_config(config)
    try:
        observed_at = dt.datetime.fromisoformat(verified_at.replace("Z", "+00:00"))
    except (TypeError, ValueError, OverflowError) as exc:
        raise ValueError(f"queryability evidenceObservedAt is invalid: {exc}") from exc
    if observed_at.tzinfo is None:
        raise ValueError("queryability evidenceObservedAt must include a timezone")
    observed_at = observed_at.astimezone(dt.timezone.utc)
    observed_at_serialized = observed_at.isoformat().replace("+00:00", "Z")
    budget = config.queryability_freshness_budget_seconds
    if not is_bounded_positive_seconds(budget):
        raise ValueError(format_bounded_positive_seconds_error("queryability freshness budget"))
    max_budget = (
        dt.datetime.max.replace(tzinfo=observed_at.tzinfo) - observed_at
    ).total_seconds()
    if budget > max_budget:
        raise ValueError(
            "queryability freshness budget exceeds the representable datetime range"
        )
    try:
        expires_at = observed_at + dt.timedelta(seconds=budget)
    except (OverflowError, ValueError) as exc:
        raise ValueError(
            "queryability freshness budget exceeds the representable datetime range"
        ) from exc
    return {
        "selectedProfile": config.queryability_profile.strip(),
        "capability": OMITTED_QUERYABILITY_CAPABILITY,
        "result": "not_applicable",
        "omissionReason": (
            "the player-experience smoke runner does not execute a log queryability check"
        ),
        "evidenceObservedAt": observed_at_serialized,
        "evidenceFreshnessBudgetSeconds": budget,
        "evidenceExpiresAt": expires_at.isoformat().replace("+00:00", "Z"),
        "evidenceRef": f"{config.preflight_ref}/{OMITTED_QUERYABILITY_CAPABILITY}",
    }


def build_evidence(
    config: SmokeConfig,
    mirrored_signals: dict[str, Any],
    external_authority: dict[str, Any],
    injected: set[str],
    execution_mode: str = "live",
    authority_provenance: str = "retained-external",
    verified_at: str | None = None,
) -> dict[str, Any]:
    enforce_authoritative_canary_gate(config, mirrored_signals)
    verified_at = verified_at or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    evidence = {
        "deploymentRef": config.deployment_ref,
        "verifiedAt": verified_at,
        "verifiedBy": config.verified_by,
        "preflightEvidenceRef": config.preflight_ref,
        "executionMode": execution_mode,
        "externalAuthorityProvenance": authority_provenance,
        "logPipelineQueryability": queryability_omission_record(config, verified_at),
        "capabilities": {
            PROMETHEUS_MIRRORS_CAPABILITY: config.prometheus_mirrors,
            PLAYER_FLOW_CANARY_CAPABILITY: config.player_flow_canary,
        },
        "externalAuthority": external_authority,
        "mirroredSignals": mirrored_signals,
    }
    if config.player_flow_canary == "advertised":
        evidence["playerFlowCanaryIdentity"] = config.player_flow_canary_identity
    if config.deployment_event_id is not None:
        evidence["deploymentEventId"] = config.deployment_event_id
    if config.player_flow_canary == "advertised":
        evidence["canaryAlerts"] = [
            alert_record("PlayerFlowCanaryLoginFailed", "P1", injected),
            alert_record("PlayerFlowCanaryCommandFailed", "P1", injected),
            alert_record("PlayerFlowCanaryLatencyHigh", "P1", injected),
            alert_record("PlayerFlowCanaryEvidenceStale", "P1", injected),
        ]
    return evidence


def resolve_external_authority(
    config: SmokeConfig,
    simulate: bool,
    evaluation_epoch: float | None = None,
    *,
    allow_failure_evidence: bool = False,
    canary_advertised: bool | None = None,
    allow_unadvertised_canary_budget: bool = False,
) -> dict[str, Any]:
    # External authority is provider-owned observation evidence, not a local
    # failure-injection target. Copy it so later evidence assembly cannot
    # mutate either the loaded retained record or synthetic source fixture.
    return copy.deepcopy(
        load_external_authority(
            config,
            simulate,
            evaluation_epoch,
            allow_failure_evidence=allow_failure_evidence,
            canary_advertised=canary_advertised,
            allow_unadvertised_canary_budget=allow_unadvertised_canary_budget,
        )
    )


def compare_external_authority_snapshots(
    initial: dict[str, Any], reread: dict[str, Any]
) -> None:
    for field in (
        "profile",
        "exposedPublicPlayerPaths",
        "detectionBudgetSeconds",
        "staleThresholdSeconds",
    ):
        initial_value = initial.get(field)
        reread_value = reread.get(field)
        if initial_value != reread_value:
            raise RuntimeError(
                "External authority changed between smoke execution and evidence "
                f"build: {field} changed from {initial_value!r} to {reread_value!r}"
            )


def load_external_authority(
    config: SmokeConfig,
    simulate: bool,
    evaluation_epoch: float | None = None,
    *,
    allow_failure_evidence: bool = False,
    canary_advertised: bool | None = None,
    allow_unadvertised_canary_budget: bool = False,
) -> dict[str, Any]:
    path = config.external_authority_evidence
    if path is None:
        if simulate:
            observed_at = (
                time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(evaluation_epoch))
                if evaluation_epoch is not None
                else None
            )
            return simulated_external_authority(observed_at)
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
        raise TypeError(
            f"External authority evidence at {path} must be a JSON object"
        )
    validate_external_authority_shape(
        data,
        path,
        evaluation_epoch=evaluation_epoch,
        canary_advertised=(
            config.player_flow_canary == "advertised"
            if canary_advertised is None
            else canary_advertised
        ),
        allow_unadvertised_canary_budget=allow_unadvertised_canary_budget,
        allow_failure_evidence=allow_failure_evidence,
    )
    return data


def simulated_external_authority(evidence_observed_at: str | None = None) -> dict[str, Any]:
    observed_at = evidence_observed_at or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    return {
        "profile": "independent-required",
        "exposedPublicPlayerPaths": ["websocket", "telnet"],
        "detectionBudgetSeconds": DEFAULT_SIMULATED_DETECTION_BUDGET_SECONDS,
        "staleThresholdSeconds": 180,
        "evidenceObservedAt": observed_at,
        "lastSuccessfulHeartbeatObservedAt": observed_at,
        "observedStalenessSeconds": 0,
        "deadmanAuthority": {
            "status": "green",
            "evidenceRef": "synthetic://external-authority/deadman",
            "pageEvidenceRef": "synthetic://external-authority/deadman-page",
            "target": "synthetic-deadman-authority",
            "checkRef": "synthetic-deadman-check",
        },
        "publicPathChecks": {
            path: {
                "status": "green",
                "evidenceRef": f"synthetic://external-authority/{path}",
                "pageEvidenceRef": f"synthetic://external-authority/{path}-page",
                "target": f"synthetic-{path}",
                "lastSuccessfulProbeObservedAt": observed_at,
                "observedProbeAgeSeconds": 0,
            }
            for path in ("websocket", "telnet")
        },
    }


def validate_external_authority_shape(
    data: dict[str, Any],
    path: Path,
    evaluation_epoch: float | None = None,
    canary_advertised: bool = False,
    allow_failure_evidence: bool = False,
    allow_unadvertised_canary_budget: bool = False,
) -> None:
    profile = data.get("profile")
    if not isinstance(profile, str) or profile not in {
        "independent-required",
        "independent-omitted",
    }:
        raise RuntimeError(
            f"External authority evidence at {path} must declare profile "
            "independent-required or independent-omitted"
        )
    exposed_paths = data.get("exposedPublicPlayerPaths")
    if not isinstance(exposed_paths, list) or not exposed_paths:
        raise TypeError(
            f"External authority evidence at {path} must include exposedPublicPlayerPaths"
        )
    supported_paths = {"websocket", "telnet"}
    if any(not isinstance(item, str) for item in exposed_paths):
        raise TypeError(
            f"External authority evidence at {path} exposedPublicPlayerPaths must contain only strings"
        )
    if len(exposed_paths) != len(set(exposed_paths)):
        raise RuntimeError(
            f"External authority evidence at {path} exposedPublicPlayerPaths must not contain duplicates"
        )
    unsupported_paths = sorted(set(exposed_paths) - supported_paths)
    if unsupported_paths:
        raise RuntimeError(
            f"External authority evidence at {path} has unsupported public paths: "
            + ", ".join(unsupported_paths)
        )

    if profile == "independent-omitted":
        reason = data.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            raise RuntimeError(
                f"External authority evidence at {path} must define a non-empty reason "
                "for independent-omitted"
            )
        allowed_fields = {"profile", "reason", "exposedPublicPlayerPaths"}
        if canary_advertised or (
            allow_unadvertised_canary_budget and "detectionBudgetSeconds" in data
        ):
            allowed_fields.add("detectionBudgetSeconds")
            detection_budget = data.get("detectionBudgetSeconds")
            if (
                not is_bounded_positive_seconds(detection_budget)
            ):
                raise RuntimeError(
                    format_bounded_positive_seconds_error(
                        f"External authority evidence at {path} detectionBudgetSeconds"
                    )
                )
            if canary_advertised and detection_budget < MIN_CANARY_DETECTION_BUDGET_SECONDS:
                raise RuntimeError(
                    f"External authority evidence at {path} detectionBudgetSeconds must be at least {MIN_CANARY_DETECTION_BUDGET_SECONDS} seconds for an advertised player-flow canary"
                )
        unexpected = sorted(set(data) - allowed_fields)
        if unexpected:
            raise RuntimeError(
                f"External authority evidence at {path} independent-omitted must not include external authority fields: "
                + ", ".join(unexpected)
            )
        return

    detection_budget = data.get("detectionBudgetSeconds")
    if (
        not is_bounded_positive_seconds(detection_budget)
    ):
        raise RuntimeError(
            format_bounded_positive_seconds_error(
                f"External authority evidence at {path} detectionBudgetSeconds"
            )
        )

    if canary_advertised and detection_budget < MIN_CANARY_DETECTION_BUDGET_SECONDS:
        raise RuntimeError(
            f"External authority evidence at {path} detectionBudgetSeconds must be at least {MIN_CANARY_DETECTION_BUDGET_SECONDS} seconds for an advertised player-flow canary"
        )
    stale_threshold = data.get("staleThresholdSeconds")
    if (
        not is_bounded_positive_seconds(stale_threshold)
    ):
        raise RuntimeError(
            format_bounded_positive_seconds_error(
                f"External authority evidence at {path} staleThresholdSeconds"
            )
        )
    observed_staleness = data.get("observedStalenessSeconds")
    if (
        isinstance(observed_staleness, bool)
        or not isinstance(observed_staleness, (int, float))
        or not is_finite_number(observed_staleness)
        or observed_staleness < 0
    ):
        raise RuntimeError(
            f"External authority evidence at {path} must define a nonnegative finite observedStalenessSeconds"
        )
    if data.get("lastSuccessfulHeartbeatObservedAt") is None:
        raise RuntimeError(
            f"External authority evidence at {path} lastSuccessfulHeartbeatObservedAt is required for independent-required"
        )
    validate_external_authority_freshness(data, path, evaluation_epoch)

    deadman = data.get("deadmanAuthority")
    if not isinstance(deadman, dict):
        raise TypeError(
            f"External authority evidence at {path} must include deadmanAuthority"
        )
    validate_authority_record(
        deadman,
        "deadmanAuthority",
        path,
        allow_failure_evidence=allow_failure_evidence,
    )
    checks = data.get("publicPathChecks")
    if not isinstance(checks, dict):
        raise TypeError(
            f"External authority evidence at {path} must include publicPathChecks"
        )
    required_paths = {"websocket", "telnet"}
    missing = sorted(required_paths - set(checks.keys()))
    extra = sorted(set(checks.keys()) - required_paths)
    if missing:
        raise RuntimeError(
            f"External authority evidence at {path} is missing public path checks: "
            + ", ".join(missing)
        )
    if extra:
        raise RuntimeError(
            f"External authority evidence at {path} has unsupported public path checks: "
            + ", ".join(extra)
        )
    for name in required_paths:
        record = checks.get(name)
        if not isinstance(record, dict):
            raise TypeError(
                f"External authority evidence at {path} must define {name} as an object"
            )
        if name in exposed_paths:
            require_source_timestamp(
                record.get("lastSuccessfulProbeObservedAt"),
                f"publicPathChecks.{name}.lastSuccessfulProbeObservedAt",
                path,
            )
            validate_public_path_record(
                record,
                f"publicPathChecks.{name}",
                path,
                allow_failure_evidence=allow_failure_evidence,
            )
        else:
            validate_not_applicable_path_record(record, f"publicPathChecks.{name}", path)


def validate_source_timestamp(
    value: Any,
    key: str,
    evidence_observed_epoch: float,
    path: Path,
) -> None:
    if value is None:
        return
    if not isinstance(value, str) or not value.endswith("Z"):
        raise RuntimeError(
            f"External authority evidence at {path} {key} must be an RFC3339 UTC timestamp ending in Z"
        )
    try:
        source_epoch = dt.datetime.fromisoformat(
            value.replace("Z", "+00:00")
        ).timestamp()
    except ValueError as exc:
        raise RuntimeError(
            f"External authority evidence at {path} {key} is invalid: {exc}"
        ) from exc
    if source_epoch > evidence_observed_epoch:
        raise RuntimeError(
            f"External authority evidence at {path} {key} cannot be later than evidenceObservedAt"
        )


def require_source_timestamp(value: Any, key: str, path: Path) -> None:
    if value is None:
        raise RuntimeError(
            f"External authority evidence at {path} {key} is required for independent-required"
        )


def validate_external_authority_freshness(
    data: dict[str, Any], path: Path, evaluation_epoch: float | None = None
) -> None:
    if data.get("profile") != "independent-required":
        return
    detection_budget = data.get("detectionBudgetSeconds")
    if (
        not is_bounded_positive_seconds(detection_budget)
    ):
        raise RuntimeError(
            format_bounded_positive_seconds_error(
                f"External authority evidence at {path} detectionBudgetSeconds"
            )
        )
    evidence_observed_at = data.get("evidenceObservedAt")
    if not isinstance(evidence_observed_at, str) or not evidence_observed_at.endswith("Z"):
        raise RuntimeError(
            f"External authority evidence at {path} must define evidenceObservedAt as an RFC3339 UTC timestamp ending in Z"
        )
    try:
        observed_epoch = dt.datetime.fromisoformat(
            evidence_observed_at.replace("Z", "+00:00")
        ).timestamp()
    except ValueError as exc:
        raise RuntimeError(
            f"External authority evidence at {path} has an invalid evidenceObservedAt: {exc}"
        ) from exc
    validate_source_timestamp(
        data.get("lastSuccessfulHeartbeatObservedAt"),
        "lastSuccessfulHeartbeatObservedAt",
        observed_epoch,
        path,
    )
    checks = data.get("publicPathChecks")
    if isinstance(checks, dict):
        for name, record in checks.items():
            if isinstance(record, dict):
                validate_source_timestamp(
                    record.get("lastSuccessfulProbeObservedAt"),
                    f"publicPathChecks.{name}.lastSuccessfulProbeObservedAt",
                    observed_epoch,
                    path,
                )
    evaluation_epoch = time.time() if evaluation_epoch is None else evaluation_epoch
    evidence_age = evaluation_epoch - observed_epoch
    if evidence_age < 0:
        raise RuntimeError(
            f"External authority evidence at {path} evidenceObservedAt cannot be in the future"
        )
    if evidence_age > detection_budget:
        raise RuntimeError(
            f"External authority evidence at {path} evidenceObservedAt is older than detectionBudgetSeconds"
        )
    heartbeat_epoch = dt.datetime.fromisoformat(
        data["lastSuccessfulHeartbeatObservedAt"].replace("Z", "+00:00")
    ).timestamp()
    observed_staleness = data["observedStalenessSeconds"]
    expected_staleness = observed_epoch - heartbeat_epoch
    if not math.isclose(
        observed_staleness,
        expected_staleness,
        rel_tol=0.0,
        abs_tol=STALE_NUMERIC_TOLERANCE_SECONDS,
    ):
        raise RuntimeError(
            f"External authority evidence at {path} observedStalenessSeconds must equal evidenceObservedAt minus lastSuccessfulHeartbeatObservedAt within numeric tolerance"
        )
    deadman = data.get("deadmanAuthority")
    if (
        isinstance(deadman, dict)
        and deadman.get("status") == "green"
        and observed_staleness
        > data["staleThresholdSeconds"] + STALE_NUMERIC_TOLERANCE_SECONDS
    ):
        raise RuntimeError(
            f"External authority evidence at {path} green deadman observedStalenessSeconds must be no greater than staleThresholdSeconds"
        )
    if isinstance(checks, dict):
        raw_exposed_paths = data.get("exposedPublicPlayerPaths", [])
        if not isinstance(raw_exposed_paths, list):
            raw_exposed_paths = []
        exposed_paths = {
            item for item in raw_exposed_paths if isinstance(item, str)
        }
        for name, record in checks.items():
            if isinstance(record, dict) and name in exposed_paths:
                validate_public_path_freshness(
                    record,
                    f"publicPathChecks.{name}",
                    observed_epoch,
                    detection_budget,
                    path,
                )


def validate_public_path_freshness(
    record: dict[str, Any],
    key: str,
    evidence_observed_epoch: float,
    detection_budget: float,
    path: Path,
) -> None:
    observed_probe_age = record.get("observedProbeAgeSeconds")
    if (
        isinstance(observed_probe_age, bool)
        or not isinstance(observed_probe_age, (int, float))
        or not is_finite_number(observed_probe_age)
        or observed_probe_age < 0
    ):
        raise RuntimeError(
            f"External authority evidence at {path} must define a nonnegative finite {key}.observedProbeAgeSeconds"
        )
    source_timestamp = record.get("lastSuccessfulProbeObservedAt")
    if source_timestamp is None:
        return
    probe_epoch = dt.datetime.fromisoformat(
        source_timestamp.replace("Z", "+00:00")
    ).timestamp()
    expected_probe_age = evidence_observed_epoch - probe_epoch
    if not math.isclose(
        observed_probe_age,
        expected_probe_age,
        rel_tol=0.0,
        abs_tol=STALE_NUMERIC_TOLERANCE_SECONDS,
    ):
        raise RuntimeError(
            f"External authority evidence at {path} {key}.observedProbeAgeSeconds must equal evidenceObservedAt minus lastSuccessfulProbeObservedAt within numeric tolerance"
        )
    if (
        record.get("status") == "green"
        and observed_probe_age > detection_budget + STALE_NUMERIC_TOLERANCE_SECONDS
    ):
        raise RuntimeError(
            f"External authority evidence at {path} green {key}.observedProbeAgeSeconds must be no greater than detectionBudgetSeconds"
        )


def validate_authority_record(
    record: dict[str, Any],
    key: str,
    path: Path,
    *,
    allow_failure_evidence: bool = False,
) -> None:
    validate_external_monitor_record(
        record,
        key,
        path,
        required_fields=("evidenceRef", "target", "checkRef"),
        allow_failure_evidence=allow_failure_evidence,
    )


def validate_public_path_record(
    record: dict[str, Any],
    key: str,
    path: Path,
    *,
    allow_failure_evidence: bool = False,
) -> None:
    validate_external_monitor_record(
        record,
        key,
        path,
        required_fields=("evidenceRef", "target"),
        allow_failure_evidence=allow_failure_evidence,
    )


def validate_external_monitor_record(
    record: dict[str, Any],
    key: str,
    path: Path,
    *,
    required_fields: tuple[str, ...],
    allow_failure_evidence: bool = False,
) -> None:
    status = record.get("status")
    if status != "green" and not (
        allow_failure_evidence and status == "red"
    ):
        raise RuntimeError(
            f"External authority evidence at {path} requires {key}.status=green"
        )
    required_fields = list(required_fields)
    if status == "green" or not allow_failure_evidence:
        required_fields.append("pageEvidenceRef")
    for field in required_fields:
        value = record.get(field)
        if not isinstance(value, str) or not value.strip():
            raise RuntimeError(
                f"External authority evidence at {path} must define {key}.{field}"
            )
        if value.startswith(("synthetic://", "synthetic-")):
            raise RuntimeError(
                f"External authority evidence at {path} must not use synthetic {key}.{field}"
            )
    validate_optional_page_evidence_ref(
        record, key, path, allow_failure_evidence=allow_failure_evidence
    )


def validate_optional_page_evidence_ref(
    record: dict[str, Any],
    key: str,
    path: Path,
    *,
    allow_failure_evidence: bool,
) -> None:
    if not allow_failure_evidence or record.get("status") != "red":
        return
    if "pageEvidenceRef" not in record:
        return
    value = record["pageEvidenceRef"]
    if not isinstance(value, str) or not value.strip():
        raise RuntimeError(
            f"External authority evidence at {path} must define {key}.pageEvidenceRef when present"
        )
    if value.startswith(("synthetic://", "synthetic-")):
        raise RuntimeError(
            f"External authority evidence at {path} must not use synthetic {key}.pageEvidenceRef"
        )


def validate_not_applicable_path_record(
    record: dict[str, Any], key: str, path: Path
) -> None:
    if record != {"status": "not_applicable"}:
        raise RuntimeError(
            f"External authority evidence at {path} requires {key}={{'status': 'not_applicable'}}"
        )


def first_party_connect_context(config: SmokeConfig) -> dict[str, Any]:
    bootstrap = issue_player_bootstrap(config)
    require_visible_world(config, bootstrap["bootstrapToken"])
    connect_scope_id = resolve_connect_scope_id(config, bootstrap["bootstrapToken"])
    character_name = resolve_character_name(
        config, bootstrap["bootstrapToken"], connect_scope_id
    )
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
    raise ProbeOperationalFailure(
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
    raise ProbeOperationalFailure(
        f"World {config.world!r} was not visible during bootstrap discovery"
    )


def resolve_character_name(
    config: SmokeConfig, bootstrap_token: str, connect_scope_id: str
) -> str | None:
    response = http_request_json(
        public_auth_url(
            config,
            (
                f"/auth/bootstrap/worlds/{quote_path(config.world)}"
                f"/realms/{quote_path(config.realm_slug)}/characters?"
                f"{urlencode({'connectScopeId': connect_scope_id})}"
            ),
        ),
        config.timeout_seconds,
        headers={"Authorization": f"Bearer {bootstrap_token}"},
    )
    characters = response["data"]
    if config.character_name:
        for character in characters:
            if character.get("characterName") == config.character_name:
                return config.character_name
        raise ProbeOperationalFailure(
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
        raise ProbeOperationalFailure(
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
                raise ProbeOperationalFailure(
                    f"{command_type} failed with {error_code}: {payload}"
                )
            return payload
    raise ProbeOperationalFailure(f"Timed out waiting for structured {command_type} result")


def play_command(config: SmokeConfig, character_name: str | None) -> str:
    if character_name:
        return f"PLAY {config.world} {config.realm_slug} {character_name}"
    return f"PLAY {config.world} {config.realm_slug}"


def public_auth_url(config: SmokeConfig, path: str) -> str:
    return f"{config.auth_api_base}{config.auth_api_prefix}{path}"


def alert_record(alert: str, severity: str, injected: set[str]) -> dict[str, str]:
    # This runner injects signal values but does not execute or observe the
    # alert evaluator and notification path. Alert-family tokens can preserve
    # an explicitly failed exercise input for incident diagnostics; absence of
    # such a token is unproved, never an inferred pass.
    return {
        "alert": alert,
        "severity": severity,
        "exerciseResult": "failed" if alert in injected else "not_exercised",
    }


def render_metrics(config: SmokeConfig, mirrored_signals: dict[str, Any]) -> str:
    enforce_authoritative_canary_gate(config, mirrored_signals)
    lines: list[str] = []
    for key, help_text, metric_name in (
        (
            "playerflow_canary_success",
            "Mirrored synthetic player-flow canary result.",
            "playerflow_canary_success",
        ),
        (
            "playerflow_canary_latency_ms",
            "Mirrored synthetic representative-command latency.",
            "playerflow_canary_latency_ms",
        ),
        (
            PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC,
            "Timestamp of the most recent synthetic player-flow canary run.",
            PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC,
        ),
    ):
        records = mirrored_signals.get(key)
        if records is None:
            continue
        lines.extend([f"# HELP {metric_name} {help_text}", f"# TYPE {metric_name} gauge"])
        for record in records:
            lines.append(
                metric_line(
                    metric_name,
                    {
                        "flow": record["flow"],
                        "path": record["path"],
                        "target": record["target"],
                        "profile": record["profile"],
                    },
                    record["value"],
                )
            )
    freshness_budget = mirrored_signals.get(PLAYERFLOW_CANARY_FRESHNESS_BUDGET_METRIC)
    if freshness_budget is not None:
        lines.extend(
            [
                "# HELP playerflow_canary_freshness_budget_seconds Profile-derived maximum player-flow canary freshness budget.",
                "# TYPE playerflow_canary_freshness_budget_seconds gauge",
                metric_line(
                    PLAYERFLOW_CANARY_FRESHNESS_BUDGET_METRIC,
                    {"profile": freshness_budget["profile"]},
                    freshness_budget["value"],
                ),
            ]
        )
    entrypath_records = mirrored_signals.get("entrypath_blackbox_probe_success")
    if entrypath_records is not None:
        lines.extend(
            [
                "# HELP entrypath_blackbox_probe_success Mirrored independent entry-path blackbox result.",
                "# TYPE entrypath_blackbox_probe_success gauge",
            ]
        )
        for record in entrypath_records:
            lines.append(
                metric_line(
                    "entrypath_blackbox_probe_success",
                    {"path": record["path"], "target": record["target"]},
                    record["value"],
                )
            )
    deadman = mirrored_signals.get(
        "observability_deadman_heartbeat_timestamp_seconds"
    )
    if deadman is not None:
        lines.extend(
            [
                "# HELP observability_deadman_heartbeat_timestamp_seconds Mirrored deadman heartbeat timestamp.",
                "# TYPE observability_deadman_heartbeat_timestamp_seconds gauge",
                metric_line(
                    "observability_deadman_heartbeat_timestamp_seconds",
                    {"source": config.source},
                    deadman["value"],
                ),
            ]
        )
    lines.append("")
    return "\n".join(lines)


def metric_line(name: str, labels: dict[str, Any], value: Any) -> str:
    label_text = ",".join(f'{key}="{escape_label(value)}"' for key, value in labels.items())
    return f"{name}{{{label_text}}} {value}"


def escape_label(value: Any) -> str:
    return str(value).replace("\\", "\\\\").replace('"', '\\"')


if __name__ == "__main__":
    raise SystemExit(main())
