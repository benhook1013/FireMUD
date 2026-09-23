"""Command dispatcher for the one repository PR-review controller."""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Mapping
from typing import Any

from . import acceptance, github, hosted
from . import evidence as evidence_module
from . import status as status_module
from .controller import ReviewController
from .runtime import default_controller


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
    reconcile = decide_commands.add_parser(
        "reconcile", help="reopen review against one exact coherent current stack anchor"
    )
    reconcile.add_argument("--pr", required=True, type=_positive_int)
    reconcile.add_argument("--channel", required=True, choices=("hosted", "cli"))
    reconcile.add_argument("--checkpoint", required=True)
    reconcile.add_argument("--prior-head", required=True)
    reconcile.add_argument("--reason", required=True)
    reconcile.add_argument("--json", action="store_true", dest="as_json")
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
    return parser


def _controller(args: argparse.Namespace) -> tuple[ReviewController, acceptance.AcceptanceFixture | None]:
    fixture_path = args.acceptance_fixture
    isolated_state = args.state_path
    if (fixture_path is None) != (isolated_state is None):
        raise CliError("--acceptance-fixture and --state-path must be supplied together")
    if fixture_path is not None and isolated_state is not None:
        fixture = acceptance.load(fixture_path, isolated_state)
        return fixture.controller(), fixture
    if args.command == "stack":
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
        report = status_module.status(args.pr)
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
        if review_reasons:
            report["reasons"].extend(review_reasons)
            report["ready"] = False
            report["verdict"] = "NOT READY"
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
        if fixture is not None and args.decide_command in {"trigger-recover-prepost", "trigger-retire"}:
            raise CliError("Hosted trigger commands are unavailable in acceptance fixture mode")
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
                record = hosted.load_trigger_record(path, controller.repository, args.pr)
                trigger = record.get("trigger")
                trigger_id = trigger.get("id") if isinstance(trigger, dict) else None
                if type(trigger_id) is int and trigger_id == args.trigger_id:
                    matching_paths.append(path)
            if len(matching_paths) != 1:
                raise CliError(
                    f"PR #{args.pr} requires exactly one durable Hosted trigger with ID {args.trigger_id}"
                )
            payload = github.fetch_pull_request(controller.repository, args.pr)
            return hosted.retire_trigger_record(
                matching_paths[0],
                controller.repository,
                args.pr,
                args.trigger_id,
                args.head,
                args.reason,
                payload,
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
        if args.decide_command == "reconcile":
            return controller.decide_reconciliation(
                pr=args.pr,
                channel=args.channel,
                checkpoint=args.checkpoint,
                prior_head=args.prior_head,
                reason=args.reason,
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
