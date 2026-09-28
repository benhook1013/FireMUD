"""Command dispatcher for the one repository PR-review controller."""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import re
import stat
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from . import acceptance, github, hosted, sqlite_provider_imports
from . import evidence as evidence_module
from . import status as status_module
from .controller import ReviewController
from .runtime import default_controller
from .sqlite_review_records import FindingObservation, ReviewRecordsError, SqliteReviewRecords
from .sqlite_store import SqliteStateStore
from .state import (
    ControllerStateStore,
    FindingRoute,
    StateError,
    SummaryFindingDisposition,
    controller_state_status,
    merge_open_route,
    sqlite_state_path,
    state_path,
)


class CliError(RuntimeError):
    pass


def _positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a positive integer") from error
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def _nonnegative_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a non-negative integer") from error
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be a non-negative integer")
    return parsed


def _exact_sha(value: str) -> str:
    if not re.fullmatch(r"[0-9a-fA-F]{40}", value):
        raise argparse.ArgumentTypeError("must be a full 40-character commit SHA")
    return value


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="dev-tools/pr-review")
    parser.add_argument(
        "--acceptance-fixture",
        metavar="JSON",
        help="use a synthetic fixture for an isolated hands-on acceptance run",
    )
    parser.add_argument(
        "--state-path",
        metavar="PATH",
        help="isolated state file (required with --acceptance-fixture)",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    state = commands.add_parser("state", help="inspect or explicitly migrate private controller state")
    state_commands = state.add_subparsers(dest="state_command", required=True)
    state_status = state_commands.add_parser("status", help="show read-only state format compatibility")
    state_status.add_argument("--path", metavar="JSON_PATH", help="state file to inspect; defaults to repository state")
    state_status.add_argument("--json", action="store_true", dest="as_json")
    state_migrate = state_commands.add_parser(
        "migrate-sqlite", help="atomically migrate JSON controller state to SQLite"
    )
    state_migrate.add_argument("--path", metavar="JSON_PATH", help="state file to migrate; defaults to repository state")
    state_migrate.add_argument("--json", action="store_true", dest="as_json")

    stack = commands.add_parser("stack", help="configure the one repository review stack")
    stack_commands = stack.add_subparsers(dest="stack_command", required=True)
    stack_set = stack_commands.add_parser("set")
    stack_set.add_argument("pr_numbers", nargs="+", type=_positive_int)
    stack_commands.add_parser("show")

    status = commands.add_parser("status", help="show live stack or one-PR status")
    status.add_argument("--pr", type=_positive_int)
    status.add_argument("--json", action="store_true", dest="as_json")
    status.add_argument(
        "--full-scan",
        action="store_true",
        help="deeply fetch and reconcile every configured PR",
    )

    routes = commands.add_parser("routes", help="list open incoming or unassigned finding routes")
    route_query = routes.add_mutually_exclusive_group(required=True)
    route_query.add_argument("--target-pr", type=_positive_int)
    route_query.add_argument("--unassigned", action="store_true")
    routes.add_argument("--json", action="store_true", dest="as_json")

    records = commands.add_parser(
        "records", help="inspect or explicitly record structured, non-provider review history"
    )
    record_commands = records.add_subparsers(dest="records_command", required=True)

    def records_database(command: argparse.ArgumentParser) -> None:
        command.add_argument(
            "--database",
            metavar="PATH",
            help="existing controller SQLite database (defaults to the repository controller database)",
        )

    bootstrap = record_commands.add_parser("bootstrap", help="explicitly create the review-records schema")
    records_database(bootstrap)

    history = record_commands.add_parser("history", help="show source and incoming route history for one PR")
    history.add_argument("--pr", required=True, type=_positive_int)
    records_database(history)

    incoming = record_commands.add_parser(
        "routes", help="show open structured and migrated controller routes"
    )
    incoming_query = incoming.add_mutually_exclusive_group(required=True)
    incoming_query.add_argument("--target-pr", type=_positive_int)
    incoming_query.add_argument("--unassigned", action="store_true")
    records_database(incoming)

    import_run = record_commands.add_parser(
        "import-run", help="batch-register a curated manual or subagent run from bounded JSON"
    )
    import_run.add_argument("--input", required=True, metavar="JSON", help="curated metadata file, not raw capture/stdout")
    records_database(import_run)

    provider_import = record_commands.add_parser(
        "import-provider",
        help="import one exact completed Hosted/CLI checkpoint from its existing private capture",
    )
    provider_import.add_argument("--pr", required=True, type=_positive_int)
    provider_import.add_argument("--channel", required=True, choices=("hosted", "cli"))
    provider_import.add_argument("--checkpoint-id", required=True, type=_positive_int)
    provider_import.add_argument("--repo", help="owner/name; defaults to the current GitHub repository")
    provider_import.add_argument("--actor", required=True, help="identity recording the source decisions")
    provider_import.add_argument("--scope", required=True, choices=("broad", "narrow"))
    provider_import.add_argument("--coverage-limit", action="append", default=[])
    records_database(provider_import)

    source_commands = record_commands.add_parser("source", help="adjudicate findings in a source run")
    source_subcommands = source_commands.add_subparsers(dest="source_command", required=True)
    source_decide = source_subcommands.add_parser("decide")
    source_decide.add_argument("--run-id", required=True)
    source_decide.add_argument("--finding-key", required=True)
    source_decide.add_argument("--decision-id", required=True)
    source_decide.add_argument("--decision", required=True, choices=("accepted", "routed", "rejected"))
    source_decide.add_argument("--actor", required=True)
    source_decide.add_argument("--reason", required=True)
    source_decide.add_argument("--target-pr", type=_positive_int)
    source_decide.add_argument("--decided-at")
    records_database(source_decide)
    source_finalize = source_subcommands.add_parser("finalize")
    source_finalize.add_argument("--run-id", required=True)
    source_finalize.add_argument("--finalized-at")
    records_database(source_finalize)

    route_commands = record_commands.add_parser("route", help="record receiving-owner route outcomes")
    route_subcommands = route_commands.add_subparsers(dest="record_route_command", required=True)
    route_decide = route_subcommands.add_parser("decide")
    route_decide.add_argument("--route-id", required=True)
    route_decide.add_argument("--decision-id", required=True)
    route_decide.add_argument("--target-pr", required=True, type=_positive_int)
    route_decide.add_argument("--decision", required=True, choices=("accepted", "rejected", "deferred"))
    route_decide.add_argument("--actor", required=True)
    route_decide.add_argument("--reason", required=True)
    route_decide.add_argument("--decided-at")
    records_database(route_decide)
    route_resolve = route_subcommands.add_parser("resolve")
    route_resolve.add_argument("--route-id", required=True)
    route_resolve.add_argument("--resolution-id", required=True)
    route_resolve.add_argument("--target-pr", required=True, type=_positive_int)
    route_resolve.add_argument("--outcome", required=True, choices=("accepted_fixed", "rejected"))
    route_resolve.add_argument("--actor", required=True)
    route_resolve.add_argument("--proof-or-reason", required=True)
    route_resolve.add_argument("--resolved-at")
    records_database(route_resolve)
    route_retarget = route_subcommands.add_parser("retarget")
    route_retarget.add_argument("--route-id", required=True)
    retarget_assignment = route_retarget.add_mutually_exclusive_group(required=True)
    retarget_assignment.add_argument("--target-pr", type=_positive_int)
    retarget_assignment.add_argument("--unassigned", action="store_true")
    route_retarget.add_argument("--actor", required=True)
    route_retarget.add_argument("--reason", required=True)
    route_retarget.add_argument("--changed-at")
    records_database(route_retarget)

    run = commands.add_parser("run", help="run the automatically selected review target")
    run_commands = run.add_subparsers(dest="run_command", required=True)
    for name in ("hosted", "cli"):
        sub = run_commands.add_parser(name)
        sub.add_argument("--expect-pr", type=_positive_int)
        if name == "cli":
            sub.add_argument("--allow-unreconciled", action="store_true")
            sub.add_argument("--reason")

    evidence = commands.add_parser("evidence", help="show review evidence")
    evidence.add_argument("pr", nargs="?", type=_positive_int)
    evidence.add_argument("--json", action="store_true", dest="as_json")

    decide = commands.add_parser("decide", help="record an exact-bound policy decision")
    decide_commands = decide.add_subparsers(dest="decide_command", required=True)
    judgment = decide_commands.add_parser("judgment")
    judgment.add_argument("decision", choices=("retain", "reopen"))
    judgment.add_argument("--pr", required=True, type=_positive_int)
    judgment.add_argument("--channel", required=True, choices=("hosted", "cli"))
    judgment.add_argument("--head", required=True)
    judgment.add_argument("--checkpoint", required=True)
    judgment.add_argument("--reason", required=True)
    judgment.add_argument("--json", action="store_true", dest="as_json")
    policy = decide_commands.add_parser("policy")
    policy.add_argument("--pr", required=True, type=_positive_int)
    policy.add_argument("--head", required=True)
    policy.add_argument("--checkpoint", required=True)
    policy.add_argument("--hosted-zero-useful", type=_nonnegative_int)
    policy.add_argument("--cli-zero-useful", type=_nonnegative_int)
    policy.add_argument("--reason", required=True)
    policy.add_argument("--json", action="store_true", dest="as_json")
    allocation = decide_commands.add_parser(
        "allocation", help="grant, renew, or cancel one exact-bound channel review allocation"
    )
    allocation.add_argument("action", choices=("grant", "renew", "cancel"))
    allocation.add_argument("--pr", required=True, type=_positive_int)
    allocation.add_argument("--channel", required=True, choices=("hosted", "cli"))
    allocation.add_argument("--head", required=True, type=_exact_sha)
    allocation.add_argument(
        "--checkpoint",
        help="optional completed attributable checkpoint to pin the allocation decision",
    )
    allocation.add_argument(
        "--min-additional-completed",
        type=_nonnegative_int,
        metavar="N",
        help="minimum additional completed attributable results after the decision (max-only defaults to 0)",
    )
    allocation.add_argument(
        "--max-additional-completed",
        type=_positive_int,
        metavar="N",
        help="maximum additional completed attributable results after the decision",
    )
    allocation.add_argument(
        "--fresh-taper",
        action="store_true",
        help="start a new taper streak at the allocation decision; prior results remain historical",
    )
    allocation.add_argument("--reason", required=True)
    allocation.add_argument("--json", action="store_true", dest="as_json")
    stop = decide_commands.add_parser(
        "stop", help="record a human decision to stop new review discovery on one channel"
    )
    stop.add_argument("--pr", required=True, type=_positive_int)
    stop.add_argument("--channel", required=True, choices=("hosted", "cli"))
    stop.add_argument("--reason", required=True)
    stop.add_argument("--head", type=_exact_sha)
    stop.add_argument("--checkpoint")
    stop.add_argument("--retain-ambiguous-fingerprint", action="append", default=[])
    stop.add_argument("--ambiguity-reason")
    stop.add_argument(
        "--acknowledge-over-ceiling",
        action="store_true",
        help="acknowledge only current-head over-ceiling skip evidence for a direct human stop; requires --head",
    )
    stop.add_argument("--json", action="store_true", dest="as_json")
    route = decide_commands.add_parser("route", help="record or disposition one stable routed finding")
    route.add_argument("action", choices=("open", "accepted-fixed", "rejected", "retargeted"))
    route.add_argument("--route-id")
    route.add_argument("--source-pr", type=_positive_int)
    route.add_argument("--channel", choices=("hosted", "cli"))
    route.add_argument("--review")
    route.add_argument("--finding")
    route.add_argument("--observation")
    route.add_argument("--target-pr", type=_positive_int)
    route.add_argument("--reason")
    route.add_argument("--proof")
    route.add_argument("--json", action="store_true", dest="as_json")
    summary_disposition = decide_commands.add_parser(
        "summary-disposition",
        help="adjudicate one exact CodeRabbit summary-only finding bucket",
    )
    summary_disposition.add_argument(
        "decision", choices=("rejected", "accepted-unfixed", "accepted-fixed", "routed")
    )
    summary_disposition.add_argument("--pr", required=True, type=_positive_int)
    summary_disposition.add_argument("--head", required=True, type=_exact_sha)
    summary_disposition.add_argument("--source", required=True, choices=("review", "comment"))
    summary_disposition.add_argument("--summary-id", required=True, type=_positive_int)
    summary_disposition.add_argument("--kind", required=True, choices=("outside_diff", "duplicate"))
    summary_disposition.add_argument("--count", required=True, type=_positive_int)
    summary_disposition.add_argument("--reason", required=True)
    summary_disposition.add_argument("--corrected-head", type=_exact_sha)
    summary_disposition.add_argument("--target-pr", type=_positive_int)
    summary_disposition.add_argument("--route-finding", action="append", default=[])
    summary_disposition.add_argument("--route-observation", action="append", default=[])
    summary_disposition.add_argument("--json", action="store_true", dest="as_json")
    reconcile = decide_commands.add_parser(
        "reconcile", help="reopen review against one exact coherent current stack anchor"
    )
    reconcile.add_argument("--pr", required=True, type=_positive_int)
    reconcile.add_argument("--channel", required=True, choices=("hosted", "cli"))
    reconcile.add_argument("--checkpoint", required=True)
    reconcile.add_argument("--prior-head", required=True)
    reconcile.add_argument("--reason", required=True)
    reconcile.add_argument("--json", action="store_true", dest="as_json")
    transition = decide_commands.add_parser(
        "transition",
        aliases=("legacy-transition",),
        help="make observed legacy evidence non-counting at one exact coherent current anchor",
    )
    transition.add_argument("--pr", required=True, type=_positive_int)
    transition.add_argument("--head", required=True, type=_exact_sha)
    transition.add_argument("--reason", required=True)
    transition.add_argument(
        "--reauthorize",
        action="store_true",
        help="carry the exact prior legacy fingerprints to a new coherent anchor",
    )
    transition.add_argument(
        "--retire-missing-hosted-fingerprint",
        help="retire one exact prior Hosted fingerprint only when it is the sole missing Hosted observation",
    )
    transition.add_argument("--json", action="store_true", dest="as_json")
    retirement = decide_commands.add_parser("trigger-retire")
    retirement.add_argument("--pr", required=True, type=_positive_int)
    retirement.add_argument("--trigger-id", required=True, type=_positive_int)
    retirement.add_argument("--head", required=True)
    retirement.add_argument("--reason", required=True)
    retirement.add_argument("--json", action="store_true", dest="as_json")
    prepost_recovery = decide_commands.add_parser(
        "trigger-recover-prepost", help="archive an operator-confirmed Hosted reservation that was never posted"
    )
    prepost_recovery.add_argument("--pr", required=True, type=_positive_int)
    prepost_recovery.add_argument("--head", required=True)
    prepost_recovery.add_argument("--reason", required=True)
    prepost_recovery.add_argument("--confirmed-not-posted", action="store_true")
    prepost_recovery.add_argument("--json", action="store_true", dest="as_json")
    stuck_recovery = decide_commands.add_parser(
        "trigger-retire-stuck",
        help="retire a stuck trigger after the live PR head advanced",
        description=(
            "Retire one stuck trigger only after its captured head differs from the exact current PR head. "
            "Same-head retry is refused because a late response cannot be distinguished from a replacement review."
        ),
    )
    stuck_recovery.add_argument("--pr", required=True, type=_positive_int)
    stuck_recovery.add_argument("--trigger-id", required=True, type=_positive_int)
    stuck_recovery.add_argument("--head", required=True)
    stuck_recovery.add_argument("--reason", required=True)
    stuck_recovery.add_argument("--confirmed-wait-expired", action="store_true")
    stuck_recovery.add_argument("--json", action="store_true", dest="as_json")
    return parser


def _controller(args: argparse.Namespace) -> tuple[ReviewController, acceptance.AcceptanceFixture | None]:
    fixture_path = args.acceptance_fixture
    isolated_state = args.state_path
    if (fixture_path is None) != (isolated_state is None):
        raise CliError("--acceptance-fixture and --state-path must be supplied together")
    if fixture_path is not None and isolated_state is not None:
        fixture = acceptance.load(fixture_path, isolated_state)
        return fixture.controller(), fixture
    if args.command == "stack" and args.stack_command == "show":
        return ReviewController(), None
    return default_controller(), None


def _render(value: Any, as_json: bool = False) -> str:
    if hasattr(value, "as_dict"):
        value = value.as_dict()
    if as_json:
        return json.dumps(value, sort_keys=True, separators=(",", ":"))
    if isinstance(value, Mapping):
        return "\n".join(f"{key}={value[key]}" for key in sorted(value))
    if isinstance(value, (list, tuple)):
        return "\n".join(str(item) for item in value)
    return str(value)


def _render_status_overview(report: Mapping[str, Any]) -> str:
    window = report.get("detail_window", {})
    lines = [
        (
            f"review stack: {report.get('status', 'UNKNOWN')} · "
            f"detail window={len(window.get('deep_prs', []))}/{window.get('unmerged_limit', 4)} unmerged"
        )
    ]
    for item in report.get("prs", []):
        channels = item.get("channels", {})
        lines.append(
            f"#{item.get('pr')} {item.get('state', 'UNKNOWN')} · head={str(item.get('head') or 'unknown')[:12]} · "
            f"Hosted={channels.get('hosted', 'UNKNOWN')} · CLI={channels.get('cli', 'UNKNOWN')} · "
            f"evidence={item.get('evidence_status', 'unknown')} ({item.get('detail_level', 'unknown')})"
        )
    if window.get("active_target_error"):
        lines.append(f"active target scan: unknown · {window['active_target_error']}")
    if window.get("deep_error"):
        lines.append(f"deep evidence: unavailable · {window['deep_error']}")
    if window.get("reason"):
        lines.append(f"live identity batch: unavailable · {window['reason']}")
    return "\n".join(lines)


def _records_database_path(args: argparse.Namespace) -> Path:
    database = getattr(args, "database", None)
    if database:
        selected = Path(database).expanduser().absolute()
    else:
        selected_state = state_path()
        if selected_state.is_dir():
            try:
                active_store = ControllerStateStore(selected_state)._active_store()
            except StateError as exc:
                raise CliError("selected controller SQLite database is incompatible") from exc
            selected = active_store.path if isinstance(active_store, SqliteStateStore) else sqlite_state_path(selected_state)
        else:
            selected = sqlite_state_path(selected_state)
    if selected.is_symlink():
        raise CliError("review-records database path must not be a symlink")
    return selected


def _records_store(args: argparse.Namespace) -> SqliteReviewRecords:
    return SqliteReviewRecords(_records_database_path(args))


def _provider_checkpoint(args: argparse.Namespace, repo: str) -> Any:
    """Read and select one exact public checkpoint; provider captures remain local evidence."""

    try:
        payload = github.fetch_pull_request(repo, args.pr)
        pull_request = payload["data"]["repository"]["pullRequest"]
        if not isinstance(pull_request, Mapping) or pull_request.get("number") != args.pr:
            raise CliError("GitHub returned a different pull request")
        connection = pull_request.get("comments")
        if not isinstance(connection, Mapping) or not isinstance(connection.get("nodes"), list):
            raise CliError("GitHub returned no complete issue-comment connection")
        comments = []
        for item in connection["nodes"]:
            if not isinstance(item, dict):
                raise CliError("GitHub returned a malformed issue comment")
            author = item.get("author")
            comments.append(
                {
                    "id": github.immutable_database_id(item),
                    "body": item.get("body"),
                    "created_at": item.get("createdAt"),
                    "updated_at": item.get("updatedAt"),
                    "author_login": author.get("login") if isinstance(author, Mapping) else None,
                }
            )
        checkpoints, _unparsed = evidence_module.parse_checkpoint_comments(comments)
    except CliError:
        raise
    except (KeyError, OSError, RuntimeError, TypeError, ValueError) as exc:
        raise CliError("could not read and parse exact checkpoint evidence") from exc
    selected = [item for item in checkpoints if item.comment_id == args.checkpoint_id]
    if len(selected) != 1:
        raise CliError("checkpoint ID must identify exactly one parsed checkpoint comment")
    return selected[0]


def _reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CliError(f"batch import contains duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> None:
    raise CliError(f"batch import contains unsupported JSON constant: {value}")


def _load_records_import(
    path_value: str,
) -> tuple[dict[str, Any], tuple[FindingObservation, ...], tuple[dict[str, Any], ...]]:
    if path_value == "-":
        raise CliError("batch import requires a bounded curated file; stdin/raw captures are not accepted")
    path = Path(path_value).expanduser().absolute()
    if path.is_symlink() or not path.is_file():
        raise CliError("batch import input must be an existing regular file, not a symlink")
    if path.stat().st_size > 512_000:
        raise CliError("batch import input exceeds the 512 KB limit")
    try:
        content = path.read_bytes()
        document = json.loads(
            content,
            object_pairs_hook=_reject_duplicate_json_keys,
            parse_constant=_reject_json_constant,
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CliError("batch import input is not valid bounded JSON") from exc
    if not isinstance(document, dict) or not {"api_version", "run", "findings"} <= set(document):
        raise CliError("batch import requires api_version, run, and findings fields")
    if set(document) - {"api_version", "run", "findings", "decisions"}:
        raise CliError("batch import contains unsupported top-level fields")
    if isinstance(document["api_version"], bool) or document["api_version"] != 1:
        raise CliError("unsupported batch import API version")
    run = document["run"]
    if not isinstance(run, dict):
        raise CliError("batch import run metadata must be an object")
    required_run = {"run_id", "source_pr", "channel", "reviewer", "scope", "coverage_limits", "outcome"}
    optional_run = {"source_head", "started_at", "finished_at"}
    if not required_run <= set(run) or set(run) - required_run - optional_run:
        raise CliError("batch import run metadata has missing or unsupported fields")
    if not isinstance(run["channel"], str) or run["channel"] not in {"manual", "subagent"}:
        raise CliError("batch import source must be manual or subagent; provider imports are not enabled")
    if not isinstance(document["findings"], list) or len(document["findings"]) > 200:
        raise CliError("batch import findings must be a list of at most 200 entries")
    observations: list[FindingObservation] = []
    inline_decisions: list[dict[str, Any]] = []
    decision_fields = {"decision_id", "decision", "actor", "reason", "decided_at"}
    for item in document["findings"]:
        if not isinstance(item, dict):
            raise CliError("each finding must be an object")
        required_finding = {"source_finding_key", "title"}
        optional_finding = {"detail", "disposition", "target_pr", *decision_fields}
        if not required_finding <= set(item) or set(item) - required_finding - optional_finding:
            raise CliError("finding entry has missing or unsupported fields (arbitrary payload is not accepted)")
        observation_target_pr = item.get("target_pr")
        if decision_fields & set(item) and item.get("disposition", "unresolved") != "routed":
            # A complete batch carries the source decision separately. The
            # finding snapshot remains unresolved until that decision is
            # applied atomically, so its target belongs to the decision.
            observation_target_pr = None
        observations.append(
            FindingObservation(
                source_finding_key=item["source_finding_key"],
                title=item["title"],
                detail=item.get("detail", ""),
                disposition=item.get("disposition", "unresolved"),
                target_pr=observation_target_pr,
            )
        )
        present_decision_fields = decision_fields & set(item)
        if present_decision_fields:
            if not {"decision", "actor", "reason"} <= present_decision_fields:
                raise CliError("inline source decisions require decision, actor, and reason")
            decision_id = item.get("decision_id")
            if decision_id is None:
                digest = hashlib.sha256(
                    f"{run['run_id']}\0{item['source_finding_key']}".encode()
                ).hexdigest()
                decision_id = f"batch-{digest[:16]}-{digest[16:32]}"
            decision = {
                "source_finding_key": item["source_finding_key"],
                "decision_id": decision_id,
                "decision": item["decision"],
                "actor": item["actor"],
                "reason": item["reason"],
            }
            if "target_pr" in item:
                decision["target_pr"] = item["target_pr"]
            if "decided_at" in item:
                decision["decided_at"] = item["decided_at"]
            inline_decisions.append(decision)

    top_level_decisions = document.get("decisions", [])
    if not isinstance(top_level_decisions, list) or len(top_level_decisions) > 200:
        raise CliError("batch import decisions must be a list of at most 200 entries")
    decisions = inline_decisions + top_level_decisions
    if decisions:
        required_decision = {"source_finding_key", "decision", "actor", "reason"}
        allowed_decision = required_decision | {"decision_id", "target_pr", "decided_at"}
        normalized_decisions: list[dict[str, Any]] = []
        for item in decisions:
            if not isinstance(item, dict):
                raise CliError("each source decision must be an object")
            if not required_decision <= set(item) or set(item) - allowed_decision:
                raise CliError("source decision has missing or unsupported fields")
            decision = dict(item)
            if "decision_id" not in decision:
                digest = hashlib.sha256(
                    f"{run['run_id']}\0{decision['source_finding_key']}".encode()
                ).hexdigest()
                decision["decision_id"] = f"batch-{digest[:16]}-{digest[16:32]}"
            normalized_decisions.append(decision)
        decisions = normalized_decisions
    return run, tuple(observations), tuple(decisions)


def _dispatch_records(args: argparse.Namespace) -> tuple[Any, int]:
    if args.acceptance_fixture is not None or args.state_path is not None:
        raise CliError("review-records commands do not accept acceptance-fixture options")
    store = _records_store(args)
    if args.records_command == "bootstrap":
        store.bootstrap()
        return {"api_version": 1, "result": {"status": "bootstrapped"}}, 0
    if args.records_command == "history":
        return {"api_version": 1, "result": store.history(args.pr, include_legacy_routes=True)}, 0
    if args.records_command == "routes":
        routes = store.open_routes(
            target_pr=args.target_pr if args.target_pr is not None else None,
            include_legacy_routes=True,
        )
        if args.unassigned:
            routes = [route for route in routes if route["assignment"] == "unassigned"]
        return {"api_version": 1, "result": {"routes": routes}}, 0
    if args.records_command == "import-run":
        run, findings, decisions = _load_records_import(args.input)
        if len(decisions) == len(findings) and (decisions or run["outcome"] == "completed"):
            if run["outcome"] != "completed":
                raise CliError("atomic batch finalization requires a completed run outcome")
            result = store.import_completed_run(
                run_id=run["run_id"],
                source_pr=run["source_pr"],
                channel=run["channel"],
                findings=findings,
                source_decisions=decisions,
                source_head=run.get("source_head"),
                reviewer=run["reviewer"],
                scope=run["scope"],
                coverage_limits=run["coverage_limits"],
                started_at=run.get("started_at"),
                finished_at=run.get("finished_at"),
            )
        elif decisions:
            raise CliError("batch import has partial source decisions; use source decide for incomplete runs")
        else:
            result = store.record_run(
                run_id=run["run_id"],
                source_pr=run["source_pr"],
                channel=run["channel"],
                findings=findings,
                outcome=run["outcome"],
                attributable=False,
                source_head=run.get("source_head"),
                reviewer=run["reviewer"],
                scope=run["scope"],
                coverage_limits=run["coverage_limits"],
                started_at=run.get("started_at"),
                finished_at=run.get("finished_at"),
            )
        return {"api_version": 1, "result": result}, 0
    if args.records_command == "import-provider":
        store.history(args.pr)
        repo = github.infer_repo(args.repo)
        checkpoint = _provider_checkpoint(args, repo)
        if args.channel == "hosted":
            result = sqlite_provider_imports.import_hosted_checkpoint(
                store,
                repo=repo,
                pr_number=args.pr,
                checkpoint=checkpoint,
                actor=args.actor,
                scope=args.scope,
                coverage_limits=args.coverage_limit,
            )
        else:
            result = sqlite_provider_imports.import_cli_checkpoint(
                store,
                repo=repo,
                pr_number=args.pr,
                checkpoint=checkpoint,
                actor=args.actor,
                scope=args.scope,
                coverage_limits=args.coverage_limit,
            )
        return {"api_version": 1, "result": result}, 0
    if args.records_command == "source":
        if args.source_command == "finalize":
            result = store.finalize_run(args.run_id, finalized_at=args.finalized_at)
        else:
            result = store.record_source_decision(
                args.run_id,
                args.finding_key,
                decision_id=args.decision_id,
                decision=args.decision,
                actor=args.actor,
                reason=args.reason,
                target_pr=args.target_pr,
                decided_at=args.decided_at,
            )
        return {"api_version": 1, "result": result}, 0
    if args.records_command == "route":
        if args.record_route_command == "decide":
            result = store.record_decision(
                args.route_id,
                decision_id=args.decision_id,
                decision_pr=args.target_pr,
                decision=args.decision,
                actor=args.actor,
                reason=args.reason,
                decided_at=args.decided_at,
            )
        elif args.record_route_command == "resolve":
            result = store.record_resolution(
                args.route_id,
                resolution_id=args.resolution_id,
                resolution_pr=args.target_pr,
                outcome=args.outcome,
                actor=args.actor,
                proof_or_reason=args.proof_or_reason,
                resolved_at=args.resolved_at,
            )
        else:
            result = store.retarget_route(
                args.route_id,
                target_pr=None if args.unassigned else args.target_pr,
                actor=args.actor,
                reason=args.reason,
                changed_at=args.changed_at,
            )
        return {"api_version": 1, "result": result}, 0
    raise CliError(f"unsupported records command: {args.records_command}")


def _read_record_incoming_routes(pr: int) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    try:
        database = _records_database_path(argparse.Namespace(database=None))
    except CliError:
        return [], {"status": "unavailable", "reason": "selected controller SQLite database is incompatible"}
    try:
        database_stat = database.stat(follow_symlinks=False)
    except FileNotFoundError:
        return [], {"status": "not_bootstrapped", "reason": "controller SQLite database does not exist"}
    except OSError:
        return [], {"status": "unavailable", "reason": "controller SQLite database could not be inspected"}
    if not stat.S_ISREG(database_stat.st_mode):
        return [], {"status": "unavailable", "reason": "controller SQLite database is not a regular file"}
    try:
        routes = [
            route
            for route in SqliteReviewRecords(database).open_routes(
                target_pr=pr,
                include_legacy_routes=True,
            )
            if route["origin"] == "review_records"
        ]
    except ReviewRecordsError as exc:
        message = str(exc)
        if "not bootstrapped" in message:
            return [], {"status": "not_bootstrapped", "reason": "review-records schema has not been bootstrapped"}
        return [], {"status": "unavailable", "reason": "review-records route history could not be read"}
    return routes, {"status": "available", "reason": None}


def _render_selected_pr_status(report: Mapping[str, Any]) -> str:
    lines = [status_module.emit_text(report)]
    route_state = report.get("record_route_store", {})
    record_routes = report.get("incoming_record_routes", [])
    lines.append(
        f"SQLite review-record routes: {route_state.get('status', 'unavailable')} · "
        f"incoming-open={len(record_routes)}"
    )
    for route in record_routes:
        lines.append(
            f"SQLite incoming route: {route['route_id']} · source PR #{route['source_pr']} "
            f"({route['source_channel']}) · finding {route['source_finding_key']}"
        )
    return "\n".join(lines)


def _dispatch(args: argparse.Namespace) -> tuple[Any, int]:
    if args.command == "state":
        if args.acceptance_fixture is not None or args.state_path is not None:
            raise CliError("state management commands do not accept acceptance-fixture options")
        selected = Path(args.path).expanduser().absolute() if args.path is not None else state_path()
        if args.state_command == "status":
            return controller_state_status(selected), 0
        if args.state_command == "migrate-sqlite":
            SqliteStateStore.migrate_legacy_json(selected, sqlite_state_path(selected))
            return controller_state_status(selected), 0
        raise CliError(f"unsupported state command: {args.state_command}")

    if args.command == "records":
        return _dispatch_records(args)

    controller, fixture = _controller(args)
    if args.command == "stack":
        value = controller.set_stack(args.pr_numbers) if args.stack_command == "set" else controller.show_stack()
        return value, 0

    if args.command == "routes":
        return controller.list_routes(target_pr=args.target_pr, unassigned=args.unassigned), 0

    if args.command == "status":
        if args.pr is None:
            if fixture is not None:
                return fixture.status(controller), 0
            if args.full_scan:
                report = controller.status()
                return (report if args.as_json else _render(report)), 0
            report = controller.status_overview()
            return (report if args.as_json else _render_status_overview(report)), 0
        if fixture is not None:
            return fixture.status(controller, args.pr), 0
        state_store = getattr(controller, "store", None)
        summary_dispositions = state_store.load().summary_dispositions if state_store is not None else ()
        report = status_module.status(args.pr, summary_dispositions=summary_dispositions)
        stack_report = controller.status() if args.full_scan else controller.status_for_pr(args.pr)
        report["review_stack"] = stack_report
        stack_item = next((item for item in stack_report.get("prs", []) if item.get("pr") == args.pr), None)
        incoming_routes = (
            stack_item.get("incoming_routes", []) if stack_item is not None else stack_report.get("incoming_routes", [])
        )
        outgoing_routes = stack_item.get("routes_out", []) if stack_item is not None else stack_report.get("routes_out", [])
        report["incoming_routes"] = incoming_routes
        report["routes_out"] = outgoing_routes
        record_routes, record_route_state = _read_record_incoming_routes(args.pr)
        report["incoming_record_routes"] = record_routes
        report["record_route_store"] = record_route_state
        review_reasons: list[str] = []
        if stack_item is None:
            review_reasons.append("PR is not configured in the repository review stack")
        else:
            if incoming_routes:
                review_reasons.append(
                    f"{len(incoming_routes)} open incoming routed finding(s) require target-owner disposition"
                )
            if record_routes:
                review_reasons.append(
                    f"{len(record_routes)} open SQLite-record incoming route(s) require target-owner disposition"
                )
            if record_route_state["status"] == "unavailable":
                review_reasons.append("SQLite review-record route history is unavailable")
            pull_request = report.get("pull_request", {})
            snapshot_matches = (
                pull_request.get("headRefOid") == stack_item.get("head")
                and pull_request.get("baseRefName") == stack_item.get("base")
                and pull_request.get("baseRefOid") == stack_item.get("parent_head")
            )
            if not snapshot_matches:
                review_reasons.append("PR base/head changed between status snapshots")
            if stack_item["reconciliation"] != "COHERENT":
                review_reasons.append(f"review stack is {stack_item['reconciliation']}")
            for channel, state in stack_item["channels"].items():
                if state == "HUMAN_STOPPED":
                    review_reasons.append(
                        f"{channel} review discovery was explicitly stopped; this is not taper or merge-readiness proof"
                    )
                elif state != "COMPLETE":
                    review_reasons.append(f"{channel} review policy is {state}")
            for channel, allocation in stack_item.get("allocations", {}).items():
                if allocation["status"] not in {"HANDED_OFF", "STOPPED"}:
                    review_reasons.append(
                        f"{channel} review allocation is {allocation['status']}: {allocation['reason']}"
                    )
        if review_reasons:
            report["reasons"].extend(review_reasons)
            report["ready"] = False
            report["verdict"] = "NOT READY"
            report["mergeability"]["clean"] = False
            report["mergeability"]["diagnosis"] = "NOT READY"
        return report if args.as_json else _render_selected_pr_status(report), 0
    if args.command == "evidence":
        if args.pr is None:
            return controller.evidence(), 0
        if fixture is not None:
            return {"pr": args.pr, "policy": controller.evidence(args.pr)[str(args.pr)], "checkpoints": []}, 0
        return {
            "pr": args.pr,
            "policy": controller.evidence(args.pr)[str(args.pr)],
            "checkpoints": evidence_module.evidence(args.pr, repo=controller.repository),
        }, 0
    if args.command == "run":
        if args.run_command == "hosted":
            return controller.run_hosted(expected_pr=args.expect_pr), 0
        if args.reason and not args.allow_unreconciled:
            raise CliError("--reason is only valid with --allow-unreconciled")
        if args.allow_unreconciled and not args.reason:
            raise CliError("--allow-unreconciled requires --reason")
        result = controller.run_cli(
            expected_pr=args.expect_pr,
            allow_unreconciled=args.allow_unreconciled,
            reason=args.reason,
        )
        return result, int(getattr(result, "exit_status", 0))
    if args.command == "decide":
        if fixture is not None and args.decide_command in {
            "trigger-recover-prepost",
            "trigger-retire",
            "trigger-retire-stuck",
            "summary-disposition",
        }:
            raise CliError("live-state decisions are unavailable in acceptance fixture mode")
        if args.decide_command == "summary-disposition":
            decision = args.decision.replace("-", "_")
            if decision == "accepted_fixed" and args.corrected_head is None:
                raise CliError("accepted-fixed summary disposition requires --corrected-head")
            if decision != "accepted_fixed" and args.corrected_head is not None:
                raise CliError("--corrected-head is valid only for accepted-fixed summary disposition")
            if decision != "routed" and args.target_pr is not None:
                raise CliError("--target-pr is valid only for routed summary disposition")
            if decision != "routed" and (args.route_finding or args.route_observation):
                raise CliError("route finding references and observations are valid only for routed disposition")
            payload = github.fetch_pull_request(controller.repository, args.pr)
            pull_request = payload.get("data", {}).get("repository", {}).get("pullRequest", {})
            if not isinstance(pull_request, Mapping):
                raise CliError("live PR response is malformed for summary adjudication")
            live_head = pull_request.get("headRefOid")
            if not isinstance(live_head, str) or not status_module.EXACT_SHA.fullmatch(live_head):
                raise CliError("live PR response has no exact head for summary adjudication")
            if decision == "accepted_fixed":
                if args.corrected_head.casefold() != live_head.casefold():
                    raise CliError("--corrected-head must equal the live PR head")
                if args.head.casefold() == live_head.casefold():
                    raise CliError("accepted-fixed disposition must refer to a prior reviewed head")
            elif decision in {"rejected", "accepted_unfixed"} and args.head.casefold() != live_head.casefold():
                raise CliError("rejected and accepted-unfixed dispositions require the live PR head")
            selected = status_module._summary_evidence(payload, args.head)
            if (
                selected.get("status") != "current"
                or selected.get("source") != args.source
                or selected.get("identity") != args.summary_id
            ):
                raise CliError("the exact summary identity is not attributable to the requested head")
            if not any(
                finding.get("kind") == args.kind and finding.get("count") == args.count
                for finding in selected.get("findings", [])
            ):
                raise CliError("the exact summary does not contain the requested finding kind and count")
            routes = []
            if decision == "routed":
                if len(args.route_finding) != args.count or len(args.route_observation) != args.count:
                    raise CliError("routed summary disposition requires one stable finding reference and observation per item")
                if len(set(args.route_finding)) != len(args.route_finding):
                    raise CliError("routed summary finding references must be unique")
                routes = [
                    FindingRoute(
                        source_pr=args.pr,
                        source_channel="hosted",
                        source_review=f"summary:{args.source}:{args.summary_id}",
                        source_finding=f"{args.kind}:ref:{finding_ref}",
                        observations=(observation,),
                        target_pr=args.target_pr,
                    )
                    for finding_ref, observation in zip(args.route_finding, args.route_observation, strict=True)
                ]
            disposition = SummaryFindingDisposition(
                pr=args.pr,
                head=args.head,
                source=args.source,
                summary_id=args.summary_id,
                kind=args.kind,
                count=args.count,
                decision=decision,
                reason=args.reason,
                corrected_head=args.corrected_head,
                route_ids=tuple(item.route_id for item in routes),
            )

            def record(current):
                existing_disposition = next(
                    (item for item in current.summary_dispositions if item.identity == disposition.identity),
                    None,
                )
                if (
                    existing_disposition is not None
                    and existing_disposition.decision == "routed"
                    and (
                        disposition.decision != "routed"
                        or set(existing_disposition.route_ids) != set(disposition.route_ids)
                    )
                ):
                    raise CliError("an existing routed summary bucket must retain its exact route IDs")
                retained = tuple(item for item in current.summary_dispositions if item.identity != disposition.identity)
                stored_routes = current.routes
                for route in routes:
                    existing = next((item for item in stored_routes if item.route_id == route.route_id), None)
                    if existing is None:
                        stored_routes = (*stored_routes, route)
                    elif existing.status != "open":
                        raise CliError(f"stable summary route {route.route_id} is already dispositioned")
                    else:
                        try:
                            updated = merge_open_route(existing, route)
                        except StateError as exc:
                            raise CliError(str(exc)) from exc
                        stored_routes = tuple(
                            updated if item.route_id == updated.route_id else item for item in stored_routes
                        )
                return dataclasses.replace(
                    current,
                    summary_dispositions=(*retained, disposition),
                    routes=stored_routes,
                )

            controller.store.update(record)
            return {"status": "recorded", "disposition": disposition.to_dict()}, 0
        if args.decide_command == "route":
            if args.action == "open":
                required = (args.source_pr, args.channel, args.review, args.finding, args.observation)
                if any(value is None for value in required) or args.route_id or args.reason or args.proof:
                    raise CliError(
                        "route open requires --source-pr, --channel, --review, --finding, and --observation"
                    )
                return controller.record_route(
                    source_pr=args.source_pr,
                    source_channel=args.channel,
                    source_review=args.review,
                    source_finding=args.finding,
                    observation=args.observation,
                    target_pr=args.target_pr,
                ), 0
            if not args.route_id or args.source_pr or args.channel or args.review or args.finding or args.observation:
                raise CliError("route disposition requires --route-id and no source identity arguments")
            return controller.decide_route(
                route_id=args.route_id,
                decision=args.action,
                reason=args.reason,
                proof=args.proof,
                target_pr=args.target_pr,
            ), 0
        if args.decide_command == "trigger-recover-prepost":
            if not args.confirmed_not_posted:
                raise CliError("pre-POST recovery requires --confirmed-not-posted operator assertion")
            paths = hosted.current_trigger_record_paths(controller.repository, args.pr)
            if len(paths) != 1:
                raise CliError(f"PR #{args.pr} requires exactly one current Hosted reservation for recovery")
            return hosted.recover_prepost_reservation(
                paths[0],
                controller.repository,
                args.pr,
                args.head,
                args.reason,
                args.confirmed_not_posted,
                lambda: github.fetch_pull_request(controller.repository, args.pr),
            ), 0
        if args.decide_command == "trigger-retire":
            paths = hosted.trigger_record_paths(controller.repository, args.pr)
            if not paths:
                raise CliError(f"PR #{args.pr} has no durable Hosted trigger")
            matching_paths = []
            for path in paths:
                try:
                    record = hosted.load_trigger_record(path, controller.repository, args.pr)
                except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
                    # An unrelated archived record must not prevent selecting a
                    # valid exact trigger, while malformed matches still fail
                    # closed through the exactly-one-match check below.
                    continue
                trigger = record.get("trigger")
                trigger_id = trigger.get("id") if isinstance(trigger, dict) else None
                if type(trigger_id) is int and trigger_id == args.trigger_id:
                    matching_paths.append(path)
            if len(matching_paths) != 1:
                raise CliError(
                    f"PR #{args.pr} requires exactly one durable Hosted trigger with ID {args.trigger_id}"
                )
            return hosted.retire_trigger_record(
                matching_paths[0],
                controller.repository,
                args.pr,
                args.trigger_id,
                args.head,
                args.reason,
                lambda: github.fetch_pull_request(controller.repository, args.pr),
            ), 0
        if args.decide_command == "trigger-retire-stuck":
            if not args.confirmed_wait_expired:
                raise CliError("stuck-trigger retirement requires --confirmed-wait-expired operator assertion")
            paths = hosted.current_trigger_record_paths(controller.repository, args.pr)
            if len(paths) != 1:
                raise CliError(f"PR #{args.pr} requires exactly one current Hosted trigger for recovery")
            record = hosted.load_trigger_record(paths[0], controller.repository, args.pr)
            trigger = record.get("trigger")
            trigger_id = trigger.get("id") if isinstance(trigger, dict) else None
            if type(trigger_id) is not int or trigger_id != args.trigger_id:
                raise CliError(f"PR #{args.pr} current Hosted trigger does not match ID {args.trigger_id}")
            return hosted.retire_stuck_trigger_after_head_advance(
                paths[0],
                controller.repository,
                args.pr,
                args.trigger_id,
                args.head,
                args.reason,
                args.confirmed_wait_expired,
                lambda: github.fetch_pull_request(controller.repository, args.pr),
            ), 0
        if args.decide_command == "judgment":
            return controller.decide_judgment(
                pr=args.pr,
                channel=args.channel,
                decision=args.decision,
                head=args.head,
                checkpoint=args.checkpoint,
                reason=args.reason,
            ), 0
        if args.decide_command == "allocation":
            return controller.decide_allocation(
                action=args.action,
                pr=args.pr,
                channel=args.channel,
                head=args.head,
                reason=args.reason,
                checkpoint=args.checkpoint,
                min_additional_completed=args.min_additional_completed,
                max_additional_completed=args.max_additional_completed,
                fresh_taper=args.fresh_taper,
            ), 0
        if args.decide_command == "stop":
            return controller.decide_stop(
                pr=args.pr,
                channel=args.channel,
                reason=args.reason,
                head=args.head,
                checkpoint=args.checkpoint,
                retain_ambiguous_fingerprints=args.retain_ambiguous_fingerprint,
                ambiguity_reason=args.ambiguity_reason,
                acknowledge_over_ceiling=args.acknowledge_over_ceiling,
            ), 0
        if args.decide_command == "reconcile":
            return controller.decide_reconciliation(
                pr=args.pr,
                channel=args.channel,
                checkpoint=args.checkpoint,
                prior_head=args.prior_head,
                reason=args.reason,
            ), 0
        if args.decide_command in {"transition", "legacy-transition"}:
            return controller.decide_legacy_transition(
                pr=args.pr,
                head=args.head,
                reason=args.reason,
                reauthorize=args.reauthorize,
                retire_missing_hosted_fingerprint=args.retire_missing_hosted_fingerprint,
            ), 0
        return controller.decide_policy(
            pr=args.pr,
            head=args.head,
            checkpoint=args.checkpoint,
            reason=args.reason,
            hosted_zero_useful=args.hosted_zero_useful,
            cli_zero_useful=args.cli_zero_useful,
        ), 0
    raise CliError(f"unsupported command: {args.command}")


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        result, exit_status = _dispatch(args)
        print(_render(result, args.command == "records" or getattr(args, "as_json", False)))
        return exit_status
    except (CliError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    except Exception as error:  # noqa: BLE001 - final operator boundary
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
