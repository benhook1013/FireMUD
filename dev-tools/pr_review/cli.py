"""Command dispatcher for the one repository PR-review controller."""

from __future__ import annotations

import argparse
import dataclasses
import json
import re
import sys
from collections.abc import Mapping
from typing import Any

from . import acceptance, github, hosted
from . import evidence as evidence_module
from . import status as status_module
from .controller import ReviewController
from .runtime import default_controller
from .state import SummaryFindingDisposition


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

    stack = commands.add_parser("stack", help="configure the one repository review stack")
    stack_commands = stack.add_subparsers(dest="stack_command", required=True)
    stack_set = stack_commands.add_parser("set")
    stack_set.add_argument("pr_numbers", nargs="+", type=_positive_int)
    stack_commands.add_parser("show")

    status = commands.add_parser("status", help="show live stack or one-PR status")
    status.add_argument("--pr", type=_positive_int)
    status.add_argument("--json", action="store_true", dest="as_json")

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
        "allocation", help="grant, renew, cancel, or hand off one exact-bound channel review allocation"
    )
    allocation.add_argument("action", choices=("grant", "renew", "cancel", "handoff"))
    allocation.add_argument("--pr", required=True, type=_positive_int)
    allocation.add_argument("--channel", required=True, choices=("hosted", "cli"))
    allocation.add_argument("--head", required=True, type=_exact_sha)
    allocation.add_argument("--reason", required=True)
    allocation.add_argument("--checkpoint")
    allocation.add_argument("--validation")
    allocation.add_argument("--json", action="store_true", dest="as_json")
    summary_disposition = decide_commands.add_parser(
        "summary-disposition",
        help="adjudicate one exact CodeRabbit summary-only finding bucket",
    )
    summary_disposition.add_argument(
        "decision", choices=("rejected", "accepted-unfixed", "accepted-fixed")
    )
    summary_disposition.add_argument("--pr", required=True, type=_positive_int)
    summary_disposition.add_argument("--head", required=True, type=_exact_sha)
    summary_disposition.add_argument("--source", required=True, choices=("review", "comment"))
    summary_disposition.add_argument("--summary-id", required=True, type=_positive_int)
    summary_disposition.add_argument("--kind", required=True, choices=("outside_diff", "duplicate"))
    summary_disposition.add_argument("--count", required=True, type=_positive_int)
    summary_disposition.add_argument("--reason", required=True)
    summary_disposition.add_argument("--corrected-head", type=_exact_sha)
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


def _dispatch(args: argparse.Namespace) -> tuple[Any, int]:
    controller, fixture = _controller(args)
    if args.command == "stack":
        value = controller.set_stack(args.pr_numbers) if args.stack_command == "set" else controller.show_stack()
        return value, 0

    if args.command == "status":
        if args.pr is None:
            return fixture.status(controller) if fixture is not None else controller.status(), 0
        if fixture is not None:
            return fixture.status(controller, args.pr), 0
        state_store = getattr(controller, "store", None)
        summary_dispositions = state_store.load().summary_dispositions if state_store is not None else ()
        report = status_module.status(args.pr, summary_dispositions=summary_dispositions)
        stack_report = controller.status()
        report["review_stack"] = stack_report
        stack_item = next((item for item in stack_report.get("prs", []) if item.get("pr") == args.pr), None)
        review_reasons: list[str] = []
        if stack_item is None:
            review_reasons.append("PR is not configured in the repository review stack")
        else:
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
                if state != "COMPLETE":
                    review_reasons.append(f"{channel} review policy is {state}")
            for channel, allocation in stack_item.get("allocations", {}).items():
                if allocation["status"] != "HANDED_OFF":
                    review_reasons.append(
                        f"{channel} review allocation is {allocation['status']}: {allocation['reason']}"
                    )
        if review_reasons:
            report["reasons"].extend(review_reasons)
            report["ready"] = False
            report["verdict"] = "NOT READY"
            report["mergeability"]["clean"] = False
            report["mergeability"]["diagnosis"] = "NOT READY"
        return report if args.as_json else status_module.emit_text(report), 0
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
            elif args.head.casefold() != live_head.casefold():
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
            )

            def record(current):
                retained = tuple(item for item in current.summary_dispositions if item.identity != disposition.identity)
                return dataclasses.replace(current, summary_dispositions=(*retained, disposition))

            controller.store.update(record)
            return {"status": "recorded", "disposition": disposition.to_dict()}, 0
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
                validation=args.validation,
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
        print(_render(result, getattr(args, "as_json", False)))
        return exit_status
    except (CliError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    except Exception as error:  # noqa: BLE001 - final operator boundary
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
