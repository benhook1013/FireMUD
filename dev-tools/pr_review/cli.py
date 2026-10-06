"""Command dispatcher for the one repository PR-review controller."""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import re
import stat
import sys
import uuid
from collections.abc import Mapping
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import acceptance, cli_attempts, github, hosted, hosted_wait, sqlite_hosted_capture, sqlite_records_repair, stack
from . import evidence as evidence_module
from . import status as status_module
from .cli_runner import PullRequestSnapshot
from .controller import LivePullRequest, ReviewController
from .runtime import default_controller
from .sqlite_review_records import FindingObservation, RecordsNotBootstrapped, ReviewRecordsError, SqliteReviewRecords
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


class _CliArgumentParser(argparse.ArgumentParser):
    def parse_args(self, args=None, namespace=None):
        parsed = super().parse_args(args, namespace)
        if (
            getattr(parsed, "command", None) == "decide"
            and getattr(parsed, "decide_command", None) == "allocation"
            and getattr(parsed, "exact_additional_completed", None) is not None
            and (
                getattr(parsed, "min_additional_completed", None) is not None
                or getattr(parsed, "max_additional_completed", None) is not None
            )
        ):
            self.error("--exact-additional-completed cannot be combined with min or max additional-completed bounds")
        return parsed


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


def _source_fix_sha(value: str) -> str:
    if not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", value):
        raise argparse.ArgumentTypeError("must be a full 40- or 64-character commit SHA")
    return value


def _parser() -> argparse.ArgumentParser:
    parser = _CliArgumentParser(
        prog="dev-tools/pr-review",
        description="The existing review engine, also available as firemud-pr-review or firemud-controller reviews.",
        epilog="Use COMMAND --help for options; routes lists work, while records route resolve records a receiving-owner outcome.",
    )
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
    state_migrate.add_argument(
        "--path", metavar="JSON_PATH", help="state file to migrate; defaults to repository state"
    )
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

    routes = commands.add_parser(
        "routes", help="list open incoming or unassigned finding routes",
        description=(
            "List open incoming or unassigned finding routes from structured SQLite records and migrated legacy "
            "controller state. Before review-records schema bootstrap, lists legacy controller routes only."
        ),
        epilog=(
            "Example: firemud-controller reviews routes --target-pr 123 --json\n"
            "Use records routes for source filters or resolved history; records route resolve records an outcome."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
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
    migrate_records = record_commands.add_parser(
        "migrate", help="upgrade review records and import legacy failed CLI attempts"
    )
    records_database(migrate_records)

    history = record_commands.add_parser("history", help="show source and incoming route history for one PR")
    history.add_argument("--pr", required=True, type=_positive_int)
    records_database(history)

    history_batch = record_commands.add_parser(
        "history-batch", help="show structured review history for several PRs in one command"
    )
    history_batch.add_argument("--pr", action="append", required=True, type=_positive_int)
    records_database(history_batch)

    incoming = record_commands.add_parser(
        "routes", help="list structured and migrated controller routes",
        description="List recorded routes; use records route resolve to record a native receiving-owner outcome.",
    )
    incoming_query = incoming.add_mutually_exclusive_group()
    incoming_query.add_argument("--target-pr", type=_positive_int)
    incoming_query.add_argument("--unassigned", action="store_true")
    incoming.add_argument("--source-pr", type=_positive_int)
    incoming.add_argument("--status", choices=("open", "resolved", "all"), default="open")
    records_database(incoming)

    import_run = record_commands.add_parser(
        "import-run", help="batch-register a curated manual or subagent run from bounded JSON"
    )
    import_run.add_argument(
        "--input", required=True, metavar="JSON", help="curated metadata file, not raw capture/stdout"
    )
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

    provider_repair = record_commands.add_parser(
        "repair-provider",
        help="preview or replay exact public provider checkpoints into SQLite",
    )
    provider_repair.add_argument("--pr", required=True, type=_positive_int)
    repair_selection = provider_repair.add_mutually_exclusive_group(required=True)
    repair_selection.add_argument("--checkpoint-id", action="append", type=_positive_int)
    repair_selection.add_argument("--all", action="store_true")
    provider_repair.add_argument("--repo", help="owner/name; defaults to the current GitHub repository")
    provider_repair.add_argument("--actor", required=True)
    provider_repair.add_argument("--scope", required=True, choices=("broad", "narrow"))
    provider_repair.add_argument("--coverage-limit", action="append", default=[])
    provider_repair.add_argument("--apply", action="store_true", help="write after a full dry-run preflight")
    provider_repair.add_argument(
        "--continue-on-error",
        action="store_true",
        help="with --all, report unavailable checkpoints while processing the rest",
    )
    records_database(provider_repair)

    hosted_sync = record_commands.add_parser(
        "sync-hosted", help="complete already-posted Hosted attempts from exact public evidence"
    )
    hosted_sync.add_argument("--pr", type=_positive_int, help="restrict to one pull request")
    hosted_sync.add_argument("--repo", help="owner/name; defaults to the current GitHub repository")
    records_database(hosted_sync)

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
    source_resolve = source_subcommands.add_parser(
        "resolve", help="record an accepted-fix proof for one exact accepted source finding"
    )
    source_resolve.add_argument("--source-pr", required=True, type=_positive_int)
    source_resolve.add_argument("--run-id", required=True)
    source_resolve.add_argument("--finding-key", required=True)
    source_resolve.add_argument("--resolution-id", required=True)
    source_resolve.add_argument("--fix-sha", required=True, type=_source_fix_sha)
    source_resolve.add_argument("--actor", required=True)
    source_resolve.add_argument("--proof-note", required=True)
    source_resolve.add_argument("--resolved-at")
    records_database(source_resolve)

    source_correct_resolution = source_subcommands.add_parser(
        "correct-resolution", help="append an audited correction to one exact source-fix proof SHA"
    )
    source_correct_resolution.add_argument("--source-pr", required=True, type=_positive_int)
    source_correct_resolution.add_argument("--run-id", required=True)
    source_correct_resolution.add_argument("--finding-key", required=True)
    source_correct_resolution.add_argument("--resolution-id", required=True)
    source_correct_resolution.add_argument("--expected-fix-sha", required=True, type=_source_fix_sha)
    source_correct_resolution.add_argument("--fix-sha", required=True, type=_source_fix_sha)
    source_correct_resolution.add_argument("--correction-id", required=True)
    source_correct_resolution.add_argument("--actor", required=True)
    source_correct_resolution.add_argument("--reason", required=True)
    source_correct_resolution.add_argument("--proof-note", required=True)
    source_correct_resolution.add_argument("--corrected-at")
    records_database(source_correct_resolution)

    source_set_severity = source_subcommands.add_parser(
        "set-severity", help="set display severity on one exact recorded source finding"
    )
    source_set_severity.add_argument("--run-id", required=True)
    source_set_severity.add_argument("--finding-key", required=True)
    source_set_severity.add_argument(
        "--severity",
        required=True,
        choices=("Critical", "Major", "Minor", "Trivial"),
    )
    records_database(source_set_severity)

    cli_decisions = record_commands.add_parser(
        "cli-decision", help="record one captured CLI finding decision without a TSV file"
    )
    cli_decisions.add_argument("--run-id", required=True)
    cli_decisions.add_argument("--finding", required=True, type=_positive_int)
    cli_decisions.add_argument("--decision", required=True, choices=("accepted", "routed", "rejected"))
    cli_decisions.add_argument("--actor", required=True)
    cli_decisions.add_argument("--reason", required=True)
    cli_decisions.add_argument("--target-pr", type=_positive_int)
    records_database(cli_decisions)
    cli_correction = record_commands.add_parser(
        "cli-correct", help="append an audited correction to one exact CLI source decision"
    )
    cli_correction.add_argument("--run-id", required=True)
    cli_correction.add_argument("--finding", required=True, type=_positive_int)
    cli_correction.add_argument("--supersedes-id", required=True)
    cli_correction.add_argument("--correction-id", required=True)
    cli_correction.add_argument("--decision", required=True, choices=("accepted", "rejected"))
    cli_correction.add_argument("--actor", required=True)
    cli_correction.add_argument("--reason", required=True)
    records_database(cli_correction)

    subagent = record_commands.add_parser(
        "subagent", help="record an independent subagent pass without CodeRabbit taper credit"
    )
    subagent_commands = subagent.add_subparsers(dest="subagent_command", required=True)
    subagent_start = subagent_commands.add_parser("start", help="register a subagent pass before it runs")
    subagent_start.add_argument("--pr", required=True, type=_positive_int)
    subagent_start.add_argument("--run-id", help="stable caller identity; generated when omitted")
    subagent_start.add_argument("--reviewer", required=True)
    subagent_start.add_argument("--model", required=True, help="actual model identifier from tool metadata")
    subagent_start.add_argument("--reasoning-effort", help="actual reasoning effort, when known")
    subagent_start.add_argument("--scope", required=True, choices=("broad", "narrow"))
    subagent_start.add_argument("--coverage-limit", action="append", default=[])
    subagent_start.add_argument("--head", help="reviewed commit, if the pass is pinned to one")
    records_database(subagent_start)
    subagent_complete = subagent_commands.add_parser(
        "complete", help="record the findings and decisions from one finished subagent pass"
    )
    subagent_complete.add_argument("--run-id", required=True)
    subagent_complete.add_argument("--actor", required=True)
    subagent_complete.add_argument(
        "--finding-json",
        action="append",
        default=[],
        metavar="JSON",
        help=(
            "one bounded finding object with title, exact Critical/Major/Minor/Trivial severity, decision, "
            "reason and optional detail/target_pr/key"
        ),
    )
    records_database(subagent_complete)
    subagent_correct = subagent_commands.add_parser(
        "correct", help="retain an incorrectly recorded rejected finding as a non-finding note"
    )
    subagent_correct.add_argument("--run-id", required=True)
    subagent_correct.add_argument("--finding-key", required=True)
    subagent_correct.add_argument("--actor", required=True)
    subagent_correct.add_argument("--reason", required=True)
    records_database(subagent_correct)
    subagent_fail = subagent_commands.add_parser("fail", help="record a failed pass without review credit")
    subagent_fail.add_argument("--run-id", required=True)
    subagent_fail.add_argument("--reason", required=True)
    records_database(subagent_fail)

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
    route_resolve = route_subcommands.add_parser(
        "resolve", help="record a native receiving-owner fix or rejection",
        description="Record a native SQLite route outcome with proof or a rejection reason; does not complete a review run.",
        epilog=("Find the route ID with routes --target-pr PR or records routes.\n"
                "Example: firemud-controller reviews records route resolve --route-id ROUTE_ID\n"
                "  --resolution-id UNIQUE_ID --target-pr 123 --outcome accepted_fixed\n"
                "  --actor General --proof-or-reason 'Verified fix and focused proof'\n"
                "The example is one command; join its continuation lines."),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    route_resolve.add_argument("--route-id", required=True, help="stable route ID returned by route listing")
    route_resolve.add_argument("--resolution-id", required=True, help="unique identity for this recorded resolution")
    route_resolve.add_argument("--target-pr", required=True, type=_positive_int, help="receiving PR for this route")
    route_resolve.add_argument("--outcome", required=True, choices=("accepted_fixed", "rejected"))
    route_resolve.add_argument("--actor", required=True)
    route_resolve.add_argument("--proof-or-reason", required=True, help="verified fix evidence or bounded rejection reason")
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
        sub.add_argument("--force", action="store_true")
        sub.add_argument("--reason")

    wait = commands.add_parser("wait", help="observe one existing review request without posting another")
    wait_commands = wait.add_subparsers(dest="wait_command", required=True)
    wait_hosted = wait_commands.add_parser("hosted", help="wait for one exact Hosted trigger to become terminal")
    wait_hosted.add_argument("--pr", required=True, type=_positive_int)
    wait_hosted.add_argument("--trigger-id", required=True, type=_positive_int)
    wait_hosted.add_argument("--poll-seconds", type=_positive_int, default=hosted_wait.DEFAULT_POLL_SECONDS)
    wait_hosted.add_argument("--max-wait-minutes", type=_positive_int, default=90)
    wait_hosted.add_argument("--repo", help="owner/name; defaults to the current GitHub repository")

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
        "allocation", help="grant, renew, or cancel one exact-bound channel review allocation",
        description=("Grant a new allocation, or renew to replace an existing allocation or human stop explicitly. "
                     "Recording an allocation does not request a review. Exact rounds always preserve taper "
                     "and cannot be combined with --fresh-taper."),
        epilog=("Example: firemud-controller reviews decide allocation grant --pr 123 --channel cli\n"
                "  --head aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa --exact-additional-completed 2\n"
                "  --reason 'Human requested two further completed results'\n"
                "Join continuation lines and replace the example head with the exact live SHA.\n"
                "Use renew instead of grant when replacing an existing allocation or stop."),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    allocation.add_argument("action", choices=("grant", "renew", "cancel"),
                            help="grant new allowance; renew replaces allowance/stop; cancel removes allowance")
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
        "--exact-additional-completed",
        type=_positive_int,
        metavar="N",
        help="require exactly N additional completed attributable results after the decision",
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
    route = decide_commands.add_parser(
        "route", help="record or disposition one stable routed finding",
        description=("Record controller route decisions. Native SQLite receiving-owner resolution uses "
                     "records route resolve; the actions here are open, accepted-fixed, rejected and retargeted."),
    )
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
    summary_disposition.add_argument("decision", choices=("rejected", "accepted-unfixed", "accepted-fixed", "routed"))
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
    manual_adoption = decide_commands.add_parser(
        "trigger-adopt-manual",
        help="audit a completed, uniquely attributable manual Hosted request without posting another",
    )
    manual_adoption.add_argument("--pr", required=True, type=_positive_int)
    manual_adoption.add_argument("--trigger-id", required=True, type=_positive_int)
    manual_adoption.add_argument("--head", required=True, type=_exact_sha)
    manual_adoption.add_argument("--json", action="store_true", dest="as_json")
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
        return ReviewController(store=ControllerStateStore()), None
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
        if not selected_state.is_dir():
            raise CliError("controller state has not been migrated to SQLite; pass --database explicitly")
        try:
            active_store = ControllerStateStore(selected_state)._active_store()
        except StateError as exc:
            raise CliError("selected controller SQLite database is incompatible") from exc
        selected = (
            active_store.path if isinstance(active_store, SqliteStateStore) else sqlite_state_path(selected_state)
        )
    if selected.is_symlink():
        raise CliError("review-records database path must not be a symlink")
    return selected


def _records_store(args: argparse.Namespace) -> SqliteReviewRecords:
    return SqliteReviewRecords(_records_database_path(args))


def _provider_checkpoints(args: argparse.Namespace, repo: str) -> tuple[list[Any], dict[str, Any]]:
    """Read complete public evidence once and select exact checkpoint identities."""

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
    requested = getattr(args, "checkpoint_id", None)
    if isinstance(requested, int):
        requested = [requested]
    if requested is None:
        selected = [item for item in checkpoints if not item.correction]
    else:
        if len(set(requested)) != len(requested):
            raise CliError("checkpoint IDs must be distinct")
        selected = [item for item in checkpoints if item.comment_id in requested]
        if len(selected) != len(requested):
            raise CliError("every checkpoint ID must identify exactly one parsed checkpoint comment")
    return selected, payload


def _provider_checkpoint(args: argparse.Namespace, repo: str) -> Any:
    selected, _payload = _provider_checkpoints(args, repo)
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
    try:
        with path.open("rb") as input_file:
            content = input_file.read(512_001)
        if len(content) > 512_000:
            raise CliError("batch import input exceeds the 512 KB limit")
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
                digest = hashlib.sha256(f"{run['run_id']}\0{item['source_finding_key']}".encode()).hexdigest()
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
                digest = hashlib.sha256(f"{run['run_id']}\0{decision['source_finding_key']}".encode()).hexdigest()
                decision["decision_id"] = f"batch-{digest[:16]}-{digest[16:32]}"
            normalized_decisions.append(decision)
        decisions = normalized_decisions
    return run, tuple(observations), tuple(decisions)


def _subagent_findings(
    run_id: str, actor: str, encoded: list[str]
) -> tuple[tuple[FindingObservation, ...], tuple[dict[str, Any], ...]]:
    if len(encoded) > 200:
        raise CliError("a subagent pass may record at most 200 findings")
    observations = []
    decisions = []
    for index, raw in enumerate(encoded, 1):
        if len(raw.encode("utf-8")) > 4096:
            raise CliError("one subagent finding exceeds 4 KB")
        try:
            item = json.loads(raw, object_pairs_hook=_reject_duplicate_json_keys, parse_constant=_reject_json_constant)
        except (ValueError, TypeError) as exc:
            raise CliError(f"subagent finding {index} is not valid JSON") from exc
        if not isinstance(item, dict) or not {"title", "severity", "decision", "reason"} <= item.keys():
            raise CliError(f"subagent finding {index} needs title, severity, decision, and reason")
        if item.keys() - {"key", "title", "detail", "severity", "decision", "reason", "target_pr"}:
            raise CliError(f"subagent finding {index} contains unsupported fields")
        severity = item["severity"]
        if not isinstance(severity, str) or severity not in {"Critical", "Major", "Minor", "Trivial"}:
            raise CliError(f"subagent finding {index} has an invalid severity")
        decision = item["decision"]
        if decision not in {"accepted", "routed", "rejected"}:
            raise CliError(f"subagent finding {index} has an invalid decision")
        target = item.get("target_pr")
        if target is not None and (type(target) is not int or target <= 0 or decision != "routed"):
            raise CliError(f"subagent finding {index} has an invalid target PR")
        key = item.get("key", f"subagent:{run_id}:finding:{index}")
        observations.append(
            FindingObservation(
                source_finding_key=key,
                title=item["title"],
                detail=item.get("detail", ""),
                display_severity=severity,
            )
        )
        digest = hashlib.sha256(f"{run_id}\0{key}".encode()).hexdigest()
        decisions.append(
            {
                "source_finding_key": key,
                "decision_id": f"subagent-{digest}",
                "decision": decision,
                "actor": actor,
                "reason": item["reason"],
                **({"target_pr": target} if target is not None else {}),
            }
        )
    return tuple(observations), tuple(decisions)


def _failed_cli_attempts_from_history(history: dict[str, Any]) -> dict[str, Any]:
    """Project bounded non-counting CLI failures from structured history."""

    outcomes = {
        "rate_limited": "rate_limited",
        "timed_out": "timed_out",
        "failed": "provider_failed",
    }
    candidates = [
        attempt
        for attempt in history["attempts"]
        if attempt["channel"] == "cli" and attempt["state"] in outcomes and isinstance(attempt["finished_at"], str)
    ]
    candidates.sort(key=lambda attempt: (attempt["finished_at"], attempt["attempt_id"]), reverse=True)
    attempts = []
    for attempt in candidates[:5]:
        outcome = attempt.get("legacy_outcome") or outcomes[attempt["state"]]
        if outcome not in {"rate_limited", "timed_out", "provider_failed", "setup_failed"}:
            outcome = outcomes[attempt["state"]]
        origin = attempt.get("origin")
        if origin not in {"legacy_private_capture", "sqlite_records"}:
            origin = "sqlite_records"
        attempts.append(
            {
                "run_id": attempt["attempt_id"],
                "finished_at": attempt["finished_at"],
                "outcome": outcome,
                "origin": origin,
            }
        )
    return {"available": True, "attempts": attempts[:5]}


def _dispatch_records(args: argparse.Namespace) -> tuple[Any, int]:
    if args.acceptance_fixture is not None or args.state_path is not None:
        raise CliError("review-records commands do not accept acceptance-fixture options")
    store = _records_store(args)
    if args.records_command == "bootstrap":
        store.bootstrap()
        return {"api_version": 1, "result": {"status": "bootstrapped"}}, 0
    if args.records_command == "migrate":
        store.migrate()
        legacy_attempts = cli_attempts.reconcile_legacy_failed_attempts(store, store.path)
        complete = legacy_attempts["available"] and not legacy_attempts["conflicts"]
        return {
            "api_version": 1,
            "result": {
                "status": "migrated" if complete else "migrated_partial",
                "legacy_cli_attempts": legacy_attempts,
            },
        }, 0 if complete else 2
    if args.records_command == "history":
        history = store.history(args.pr, include_legacy_routes=True)
        history["cli_attempts"] = _failed_cli_attempts_from_history(history)
        return {"api_version": 1, "result": history}, 0
    if args.records_command == "history-batch":
        if len(args.pr) > 200 or len(set(args.pr)) != len(args.pr):
            raise CliError("history-batch requires 1–200 distinct PRs")
        histories = {}
        for pr, history in store.history_batch(args.pr, include_legacy_routes=True).items():
            history["cli_attempts"] = _failed_cli_attempts_from_history(history)
            histories[str(pr)] = history
        return {"api_version": 1, "result": {"prs": histories}}, 0
    if args.records_command == "routes":
        routes = store.list_routes(
            status=args.status,
            target_pr=args.target_pr,
            source_pr=args.source_pr,
            unassigned=args.unassigned,
            include_legacy_routes=True,
        )
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
        selected, payload = _provider_checkpoints(args, repo)
        if len(selected) != 1:
            raise CliError("checkpoint ID must identify exactly one parsed checkpoint comment")
        checkpoint = selected[0]
        if checkpoint.type.casefold() != args.channel:
            raise CliError("checkpoint channel does not match the requested provider channel")
        result = sqlite_records_repair.repair_provider_checkpoints(
            store,
            repo=repo,
            pr_number=args.pr,
            checkpoints=(checkpoint,),
            actor=args.actor,
            scope=args.scope,
            coverage_limits=args.coverage_limit,
            hosted_payload=payload if args.channel == "hosted" else None,
            dry_run=False,
        )
        if result["status"] != "complete":
            raise CliError("provider import is partial; inspect and rerun the exact checkpoint")
        return {"api_version": 1, "result": result}, 0
    if args.records_command == "repair-provider":
        store.history(args.pr)
        repo = github.infer_repo(args.repo)
        checkpoints, payload = _provider_checkpoints(args, repo)
        if args.continue_on_error:
            if not args.all:
                raise CliError("--continue-on-error requires --all")
            items = []
            for checkpoint in checkpoints:
                try:
                    outcome = sqlite_records_repair.repair_provider_checkpoints(
                        store,
                        repo=repo,
                        pr_number=args.pr,
                        checkpoints=(checkpoint,),
                        actor=args.actor,
                        scope=args.scope,
                        coverage_limits=args.coverage_limit,
                        hosted_payload=payload,
                        dry_run=not args.apply,
                    )
                    items.append(
                        {
                            "checkpoint_id": checkpoint.comment_id,
                            "status": outcome["status"],
                            "items": outcome["items"],
                            **({"error": outcome["error"]} if outcome.get("error") else {}),
                        }
                    )
                except sqlite_records_repair.MissingHistoricalEvidenceError as exc:
                    try:
                        gap = sqlite_records_repair.archive_incomplete_checkpoint(
                            store,
                            repo=repo,
                            pr_number=args.pr,
                            checkpoint=checkpoint,
                            missing_reason=str(exc),
                            hosted_payload=payload,
                            dry_run=not args.apply,
                        )
                    except sqlite_records_repair.SqliteRecordsRepairError as gap_error:
                        items.append(
                            {
                                "checkpoint_id": checkpoint.comment_id,
                                "status": "unavailable",
                                "error": str(exc),
                                "archive_error": str(gap_error),
                            }
                        )
                    else:
                        items.append(
                            {
                                "checkpoint_id": checkpoint.comment_id,
                                "status": "incomplete",
                                "missing_evidence": str(exc),
                                "gap": gap,
                            }
                        )
                except sqlite_records_repair.SqliteRecordsRepairError as exc:
                    items.append({"checkpoint_id": checkpoint.comment_id, "status": "unavailable", "error": str(exc)})
            partial = any(item["status"] in {"unavailable", "incomplete", "partial"} for item in items)
            return {
                "api_version": 1,
                "result": {
                    "status": "partial" if partial else "complete" if args.apply else "preview",
                    "dry_run": not args.apply,
                    "source_pr": args.pr,
                    "items": items,
                },
            }, 2 if partial else 0
        result = sqlite_records_repair.repair_provider_checkpoints(
            store,
            repo=repo,
            pr_number=args.pr,
            checkpoints=checkpoints,
            actor=args.actor,
            scope=args.scope,
            coverage_limits=args.coverage_limit,
            hosted_payload=payload,
            dry_run=not args.apply,
        )
        return {"api_version": 1, "result": result}, 0 if result["status"] != "partial" else 2
    if args.records_command == "sync-hosted":
        store.history(args.pr or 1)  # Refuse incompatible or unbootstrapped writers before any live read.
        result = sqlite_hosted_capture.sync_hosted_pending(
            store,
            repo=github.infer_repo(args.repo),
            pr_number=args.pr,
        )
        unresolved = any(result.get(bucket) for bucket in ("pending", "ambiguous", "errors"))
        return {"api_version": 1, "result": result}, 2 if unresolved else 0
    if args.records_command == "source":
        if args.source_command == "finalize":
            result = store.finalize_run(args.run_id, finalized_at=args.finalized_at)
        elif args.source_command == "decide":
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
        elif args.source_command == "correct-resolution":
            result = store.correct_source_resolution(
                args.run_id,
                args.finding_key,
                source_pr=args.source_pr,
                resolution_id=args.resolution_id,
                expected_fix_sha=args.expected_fix_sha,
                fix_sha=args.fix_sha,
                correction_id=args.correction_id,
                actor=args.actor,
                reason=args.reason,
                proof_note=args.proof_note,
                corrected_at=args.corrected_at,
            )
        elif args.source_command == "set-severity":
            result = store.set_source_severity(
                args.run_id,
                args.finding_key,
                severity=args.severity,
            )
        else:
            result = store.record_source_resolution(
                args.run_id,
                args.finding_key,
                source_pr=args.source_pr,
                resolution_id=args.resolution_id,
                fix_sha=args.fix_sha,
                actor=args.actor,
                proof_note=args.proof_note,
                resolved_at=args.resolved_at,
            )
        return {"api_version": 1, "result": result}, 0
    if args.records_command == "cli-decision":
        key = f"cli-run:{args.run_id}:finding:{args.finding}"
        digest = hashlib.sha256(f"{args.run_id}\0{key}".encode()).hexdigest()
        result = store.record_source_decision(
            args.run_id,
            key,
            decision_id=f"cli-{digest}",
            decision=args.decision,
            actor=args.actor,
            reason=args.reason,
            target_pr=args.target_pr,
        )
        return {"api_version": 1, "result": result}, 0
    if args.records_command == "cli-correct":
        result = store.correct_source_decision(
            args.run_id,
            f"cli-run:{args.run_id}:finding:{args.finding}",
            supersedes_id=args.supersedes_id,
            correction_id=args.correction_id,
            decision=args.decision,
            actor=args.actor,
            reason=args.reason,
        )
        return {"api_version": 1, "result": result}, 0
    if args.records_command == "subagent":
        if args.subagent_command == "start":
            run_id = args.run_id or f"subagent.{uuid.uuid4().hex}"
            result = store.start_attempt(
                attempt_id=run_id,
                source_pr=args.pr,
                channel="subagent",
                candidate_sha=args.head,
                metadata={
                    "reviewer": args.reviewer,
                    "model": args.model,
                    **({"reasoning_effort": args.reasoning_effort} if args.reasoning_effort else {}),
                    "scope": args.scope,
                    "coverage_limits": args.coverage_limit,
                },
            )
        else:
            attempt = store.attempt(args.run_id)
            if attempt["channel"] != "subagent":
                raise CliError("run ID does not identify a subagent pass")
            if args.subagent_command == "correct":
                result = store.correct_subagent_record(
                    args.run_id, args.finding_key, actor=args.actor, reason=args.reason
                )
            elif args.subagent_command == "fail":
                if attempt["state"] != "started":
                    raise CliError("only a started subagent pass can fail")
                result = store.finish_attempt(
                    args.run_id,
                    state="failed",
                    diagnostic=args.reason,
                )
            else:
                if attempt["state"] not in {"started", "completed"}:
                    raise CliError("only a started or completed subagent pass can be recorded")
                observations, decisions = _subagent_findings(args.run_id, args.actor, args.finding_json)
                metadata = attempt["metadata"]
                prior = next(
                    (run for run in store.history(attempt["source_pr"])["runs"] if run["run_id"] == args.run_id), None
                )
                finished_at = prior["finished_at"] if prior else attempt["finished_at"]
                if not isinstance(finished_at, str) or not finished_at:
                    finished_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
                recorded = store.import_completed_run(
                    run_id=args.run_id,
                    source_pr=attempt["source_pr"],
                    channel="subagent",
                    findings=observations,
                    source_decisions=decisions,
                    source_head=attempt["candidate_sha"],
                    reviewer=metadata["reviewer"],
                    scope=metadata["scope"],
                    coverage_limits=metadata["coverage_limits"],
                    started_at=attempt["started_at"],
                    finished_at=finished_at,
                )
                if attempt["state"] == "started":
                    store.finish_attempt(args.run_id, state="completed", finished_at=finished_at)
                store.link_attempt_run(args.run_id, args.run_id)
                effective = next(run for run in store.history(attempt["source_pr"])["runs"]
                                 if run["run_id"] == args.run_id)
                if "original_counts" in effective:
                    recorded["counts"] = effective["counts"]
                    recorded["original_counts"] = effective["original_counts"]
                result = {"attempt_id": args.run_id, "run": recorded}
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
    if not state_path().is_dir():
        return [], {"status": "not_bootstrapped", "reason": "controller state has not been migrated to SQLite"}
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
        f"SQLite review-record routes: {route_state.get('status', 'unavailable')} · incoming-open={len(record_routes)}"
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

    if args.command == "wait":
        if args.acceptance_fixture is not None or args.state_path is not None:
            raise CliError("wait commands do not accept acceptance-fixture options")
        return hosted_wait.wait_for_hosted(
            github.infer_repo(args.repo),
            args.pr,
            args.trigger_id,
            poll_seconds=args.poll_seconds,
            max_wait_seconds=args.max_wait_minutes * 60,
            on_change=lambda state: print(
                f"Hosted #{args.pr} trigger {args.trigger_id}: {state}", file=sys.stderr, flush=True
            ),
        )

    budget = github.active_hosted_preflight_budget()
    starting_cli_run = args.command == "run" and args.run_command == "cli" and budget is not None
    if starting_cli_run:
        budget.set_phase("controller_construction", total=1)
    controller, fixture = _controller(args)
    if starting_cli_run:
        budget.set_completed(1)
    if args.command == "stack":
        value = controller.set_stack(args.pr_numbers) if args.stack_command == "set" else controller.show_stack()
        return value, 0

    if args.command == "routes":
        legacy_listing = controller.list_routes(target_pr=args.target_pr, unassigned=args.unassigned)
        if fixture is not None:
            return legacy_listing, 0

        active_store = getattr(controller, "store", None)
        if isinstance(active_store, ControllerStateStore):
            active_store = active_store._active_store()
        if isinstance(active_store, SqliteStateStore):
            try:
                routes = SqliteReviewRecords(active_store.path).list_routes(
                    status="open",
                    target_pr=args.target_pr,
                    unassigned=args.unassigned,
                    include_legacy_routes=True,
                )
            except RecordsNotBootstrapped:
                # Before structured review records are enabled, controller state
                # remains the only route source and the legacy listing is complete.
                return legacy_listing, 0
            return {
                "query": "unassigned" if args.unassigned else "target_pr",
                "target_pr": args.target_pr,
                "routes": routes,
                "count": len(routes),
            }, 0
        return legacy_listing, 0

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
        outgoing_routes = (
            stack_item.get("routes_out", []) if stack_item is not None else stack_report.get("routes_out", [])
        )
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
                and pull_request.get("baseRefOid") == stack_item.get("pr_base_oid")
            )
            if not snapshot_matches:
                review_reasons.append("PR base/head changed between status snapshots")
            if stack_item["reconciliation"] != "COHERENT":
                review_reasons.append(f"review stack is {stack_item['reconciliation']}")
            for channel, state in stack_item["channels"].items():
                if state not in {"COMPLETE", "HUMAN_STOPPED"}:
                    review_reasons.append(f"{channel} review policy is {state}")
                if state == "HUMAN_STOPPED":
                    review_reasons.extend(
                        f"{channel}: {reason}" for reason in stack_item.get("review_obligations", {}).get(channel, ())
                    )
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
            return controller.run_hosted(expected_pr=args.expect_pr, force=args.force, reason=args.reason), 0
        result = controller.run_cli(
            expected_pr=args.expect_pr,
            force=args.force,
            reason=args.reason,
        )
        return result, int(getattr(result, "exit_status", 0))
    if args.command == "decide":
        if fixture is not None and args.decide_command in {
            "trigger-recover-prepost",
            "trigger-retire",
            "trigger-retire-stuck",
            "trigger-adopt-manual",
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
                    raise CliError(
                        "routed summary disposition requires one stable finding reference and observation per item"
                    )
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
                    raise CliError("route open requires --source-pr, --channel, --review, --finding, and --observation")
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
        if args.decide_command == "trigger-adopt-manual":
            state = controller.store.load()
            if args.pr in state.ordered_prs:
                live, reconciliation = controller._reconciliation(state, evidence_prs=set())
                candidate = live.get(args.pr)
                if candidate is None or candidate.head.casefold() != args.head.casefold():
                    raise CliError("manual Hosted adoption requires the current published PR head")
                if reconciliation.status_for(args.pr) != stack.ReconciliationStatus.COHERENT:
                    raise CliError("manual Hosted adoption requires coherent live stack reconciliation")
                if not isinstance(candidate.head_ref, str) or not candidate.head_ref.strip():
                    raise CliError("manual Hosted adoption requires a live PR branch identity")
                anchor = controller._reconciled_anchor(args.pr, candidate, reconciliation)
                if anchor is None:
                    raise CliError("manual Hosted adoption requires a verified current parent and patch")
                anchor_data = anchor.as_dict()
            else:
                snapshot = controller.github.pull_request(args.pr)
                if isinstance(snapshot, PullRequestSnapshot):
                    candidate = LivePullRequest(
                        number=snapshot.number,
                        head=snapshot.head_sha,
                        base_ref=snapshot.base_ref_name,
                        base_tip=snapshot.base_sha,
                        head_ref=snapshot.head_ref_name,
                        merged=snapshot.merged,
                        state=snapshot.state.upper(),
                        mergeable=snapshot.mergeable.upper(),
                        base_exists=snapshot.base_exists,
                        changed_files=snapshot.changed_files,
                        head_repository=snapshot.head_repository,
                    )
                elif isinstance(snapshot, LivePullRequest):
                    candidate = snapshot
                else:
                    raise CliError("off-queue manual Hosted adoption requires a complete live PR snapshot")
                if candidate.number != args.pr or candidate.state.upper() != "OPEN" or candidate.merged:
                    raise CliError("off-queue manual Hosted adoption requires an open live PR")
                if candidate.head.casefold() != args.head.casefold():
                    raise CliError("manual Hosted adoption requires the current published PR head")
                if candidate.base_exists is not True:
                    raise CliError("off-queue manual Hosted adoption requires an existing live base branch")
                if not isinstance(candidate.head_ref, str) or not candidate.head_ref.strip():
                    raise CliError("manual Hosted adoption requires a live PR branch identity")
                problem = controller._head_repository_problem(candidate)
                if problem:
                    raise CliError(f"off-queue manual Hosted adoption {problem}")
                remote_heads = controller.git.remote_heads()
                for ref_name, expected_tip, label in (
                    (candidate.base_ref, candidate.base_tip, "base"),
                    (candidate.head_ref, candidate.head, "head"),
                ):
                    remote_tip = remote_heads.get(ref_name)
                    if not isinstance(remote_tip, str) or remote_tip.casefold() != expected_tip.casefold():
                        raise CliError(
                            f"off-queue manual Hosted adoption requires the live {label} branch tip to match origin"
                        )
                if not controller.git.is_ancestor(candidate.base_tip, candidate.head):
                    raise CliError(
                        "off-queue manual Hosted adoption requires the live base to be an ancestor of the head"
                    )
                link = stack.ParentLink(args.pr, None, candidate.base_ref, candidate.base_tip)
                anchor_data = controller._anchor(args.pr, candidate, link).as_dict()
            return hosted.adopt_manual_completed_trigger(
                controller.repository,
                args.pr,
                args.trigger_id,
                args.head,
                anchor_data,
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
                raise CliError(f"PR #{args.pr} requires exactly one durable Hosted trigger with ID {args.trigger_id}")
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
                exact_additional_completed=args.exact_additional_completed,
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
        if args.command == "run" and args.run_command == "hosted":
            with github.hosted_preflight_budget():
                result, exit_status = _dispatch(args)
        elif args.command == "run" and args.run_command == "cli":
            with github.cli_preflight_budget():
                result, exit_status = _dispatch(args)
        else:
            result, exit_status = _dispatch(args)
        print(_render(result, args.command in {"records", "wait"} or getattr(args, "as_json", False)))
        return exit_status
    except (CliError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    except Exception as error:  # noqa: BLE001 - final operator boundary
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
