#!/usr/bin/env python3
"""Extract the canonical review checkpoint comments from a pull request."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

CHECKPOINT_HEADING = re.compile(
    r"^\*\*(?P<correction>Correction — )?(?P<type>Hosted|CLI): "
    r"(?P<raw_found>\d+) found / (?P<accepted>\d+) accepted\*\*"
    r"(?P<suffix>.*)$"
)
CHECKPOINT_SUFFIX = re.compile(
    r"^(?: · (?P<sha>`?[0-9a-fA-F]{7,40}`?))?"
    r"(?: · (?P<files>\d+) files)?$"
)
CHECKPOINT_CANDIDATE = re.compile(r"^\*\*(?:Correction — )?(?:Hosted|CLI):")
SCOPE_CHANGE = re.compile(r"^\*\*Review scope changed:\*\* (?P<description>.+)$")
SCOPE_CHANGE_CANDIDATE = re.compile(r"^\*\*Review scope changed:\*\*")
SCOPE_MARKER = "<!-- firemud-review-scope-change -->"
REPO_NAME = re.compile(r"^[^/\s]+/[^/\s]+$")
RUN_MARKER = re.compile(r"^<!-- firemud-cli-run: (?P<run_id>run\.[A-Za-z0-9]{1,32}) -->$")
RUN_ID = re.compile(r"^run\.[A-Za-z0-9]{1,32}$")


@dataclass(frozen=True)
class Checkpoint:
    comment_id: int | None
    created_at: str
    type: str
    raw_found: int
    accepted: int
    reviewed_sha: str | None
    file_count: int | None
    correction: bool
    updated_at: str | None
    run_id: str | None

    def as_json(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "comment_id": self.comment_id,
            "created_at": self.created_at,
            "type": self.type,
            "raw_found": self.raw_found,
            "accepted": self.accepted,
            "reviewed_sha": self.reviewed_sha,
            "file_count": self.file_count,
            "correction": self.correction,
        }
        if self.run_id is not None:
            result["run_id"] = self.run_id
        if self.updated_at is not None:
            result["updated_at"] = self.updated_at
        return result


@dataclass(frozen=True)
class ScopeChange:
    comment_id: int | None
    created_at: str
    description: str
    updated_at: str | None

    def as_json(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "comment_id": self.comment_id,
            "created_at": self.created_at,
            "description": self.description,
        }
        if self.updated_at is not None:
            result["updated_at"] = self.updated_at
        return result


class CheckpointError(ValueError):
    """Raised when API data or command arguments cannot be interpreted safely."""


def parse_limit(value: str) -> int:
    try:
        limit = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("limit must be a non-negative integer") from exc
    if limit < 0:
        raise argparse.ArgumentTypeError("limit must be a non-negative integer")
    return limit


def parse_repo(repo: str) -> tuple[str, str]:
    if not REPO_NAME.fullmatch(repo):
        raise CheckpointError("repo must be in OWNER/REPO form")
    owner, name = repo.split("/", 1)
    return owner, name


def parse_comment_id(value: str) -> int:
    try:
        comment_id = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("comment ID must be a positive integer") from exc
    if comment_id <= 0:
        raise argparse.ArgumentTypeError("comment ID must be a positive integer")
    return comment_id


def parse_positive_limit(value: str) -> int:
    try:
        limit = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("rejections must be a positive integer") from exc
    if limit <= 0:
        raise argparse.ArgumentTypeError("rejections must be a positive integer")
    return limit


def _comment_run_id(body: str) -> str | None:
    marker_lines = []
    for line in body.splitlines()[1:]:
        stripped = line.strip()
        if not stripped.startswith("<!-- firemud-cli-run:"):
            continue
        match = RUN_MARKER.fullmatch(stripped)
        if match is None:
            return None
        marker_lines.append(match.group("run_id"))
    return marker_lines[0] if len(marker_lines) == 1 else None


def parse_checkpoint_comments(
    comments: list[dict[str, Any]],
) -> tuple[list[Checkpoint], int]:
    checkpoints: list[Checkpoint] = []
    unparsed_candidates = 0
    for position, comment in enumerate(comments, 1):
        if not isinstance(comment, dict):
            raise CheckpointError(f"comment {position} is not an object")
        body = comment.get("body")
        created_at = comment.get("created_at")
        updated_at = comment.get("updated_at")
        if not isinstance(body, str) or not isinstance(created_at, str):
            raise CheckpointError(f"comment {position} is missing body or created_at")
        if updated_at is not None and not isinstance(updated_at, str):
            raise CheckpointError(f"comment {position} has an invalid updated_at")
        comment_id = comment.get("id")
        if comment_id is not None and (
            isinstance(comment_id, bool) or not isinstance(comment_id, int) or comment_id <= 0
        ):
            raise CheckpointError(f"comment {position} has an invalid id")
        first_line = next(
            (line for line in body.splitlines() if line.strip() and not line[0].isspace()),
            None,
        )
        if first_line is None:
            continue
        match = CHECKPOINT_HEADING.fullmatch(first_line)
        if match is None:
            if CHECKPOINT_CANDIDATE.match(first_line) or (
                SCOPE_CHANGE_CANDIDATE.match(first_line) and body.splitlines()[1:].count(SCOPE_MARKER) != 1
            ):
                unparsed_candidates += 1
            continue
        # One observed correction was posted with literal "\\n" separators in its body.
        suffix_text = match.group("suffix").split(r"\n", 1)[0]
        suffix = CHECKPOINT_SUFFIX.fullmatch(suffix_text)
        if suffix is None:
            unparsed_candidates += 1
            continue
        sha = suffix.group("sha")
        if sha is not None:
            sha = sha.strip("`")
        file_count = suffix.group("files")
        checkpoints.append(
            Checkpoint(
                comment_id=comment_id,
                created_at=created_at,
                type=match.group("type"),
                raw_found=int(match.group("raw_found")),
                accepted=int(match.group("accepted")),
                reviewed_sha=sha,
                file_count=int(file_count) if file_count is not None else None,
                correction=match.group("correction") is not None,
                updated_at=(updated_at if updated_at is not None and updated_at != created_at else None),
                run_id=_comment_run_id(body),
            )
        )
    return checkpoints, unparsed_candidates


def parse_scope_changes(comments: list[dict[str, Any]]) -> list[ScopeChange]:
    changes: list[ScopeChange] = []
    for position, comment in enumerate(comments, 1):
        if not isinstance(comment, dict):
            raise CheckpointError(f"comment {position} is not an object")
        body = comment.get("body")
        created_at = comment.get("created_at")
        updated_at = comment.get("updated_at")
        if not isinstance(body, str) or not isinstance(created_at, str):
            raise CheckpointError(f"comment {position} is missing body or created_at")
        if updated_at is not None and not isinstance(updated_at, str):
            raise CheckpointError(f"comment {position} has an invalid updated_at")
        comment_id = comment.get("id")
        if comment_id is not None and (
            isinstance(comment_id, bool) or not isinstance(comment_id, int) or comment_id <= 0
        ):
            raise CheckpointError(f"comment {position} has an invalid id")
        first_line = next(
            (line for line in body.splitlines() if line.strip() and not line[0].isspace()),
            None,
        )
        if first_line is None:
            continue
        match = SCOPE_CHANGE.fullmatch(first_line)
        if match is None or body.splitlines()[1:].count(SCOPE_MARKER) != 1:
            continue
        changes.append(
            ScopeChange(
                comment_id=comment_id,
                created_at=created_at,
                description=match.group("description"),
                updated_at=(updated_at if updated_at is not None and updated_at != created_at else None),
            )
        )
    return changes


def _load_comments(payload: Any) -> list[dict[str, Any]]:
    if not isinstance(payload, list) or any(not isinstance(page, list) for page in payload):
        raise CheckpointError("gh api returned an unexpected paginated response")
    comments = [comment for page in payload for comment in page]
    if any(not isinstance(comment, dict) for comment in comments):
        raise CheckpointError("gh api returned a non-object comment")
    return comments


def fetch_comments(repo: str, pr_number: int) -> list[dict[str, Any]]:
    owner, name = parse_repo(repo)
    endpoint = f"repos/{owner}/{name}/issues/{pr_number}/comments?per_page=100"
    try:
        completed = subprocess.run(
            ["gh", "api", "--method", "GET", "--paginate", "--slurp", endpoint],
            check=True,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr.strip() if exc.stderr else "gh api failed"
        raise RuntimeError(detail) from exc
    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("gh api returned invalid JSON") from exc
    return _load_comments(payload)


def collect_report(
    comments: list[dict[str, Any]],
    limit: int,
) -> dict[str, Any]:
    checkpoints, unparsed_candidates = parse_checkpoint_comments(comments)
    scope_changes = parse_scope_changes(comments)
    checkpoints.sort(key=lambda checkpoint: checkpoint.created_at)
    scope_changes.sort(key=lambda change: change.created_at)
    returned = checkpoints if limit == 0 else checkpoints[-limit:]
    unlinked_cli_count = sum(checkpoint.type == "CLI" and checkpoint.run_id is None for checkpoint in checkpoints)
    warnings = (
        [f"{unlinked_cli_count} CLI checkpoint comment(s) lack a firemud-cli-run marker"] if unlinked_cli_count else []
    )
    timeline: list[dict[str, Any]] = [{"kind": "checkpoint", **checkpoint.as_json()} for checkpoint in returned]
    timeline.extend({"kind": "scope_change", **change.as_json()} for change in scope_changes)
    timeline.sort(key=lambda item: item["created_at"])
    return {
        "total_comments": len(comments),
        "matched_checkpoints": len(checkpoints),
        "returned_checkpoints": len(returned),
        "omitted_checkpoints": len(checkpoints) - len(returned),
        "unparsed_candidates": unparsed_candidates,
        "matched_scope_changes": len(scope_changes),
        "warnings": warnings,
        "checkpoints": [checkpoint.as_json() for checkpoint in returned],
        "timeline": timeline,
    }


class CaptureUnavailable(CheckpointError):
    """Raised when a linked run or one of its required artifacts is absent."""


class CaptureInvalid(CheckpointError):
    """Raised when linked capture evidence is malformed or fails identity checks."""


@dataclass(frozen=True)
class CaptureData:
    metadata: dict[str, str]
    findings: list[dict[str, Any]]
    reasons: dict[int, str]
    unlinked_rejections: list[dict[str, Any]]
    rejection_file_present: bool


def _read_metadata(path: Path) -> dict[str, str]:
    metadata: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise CaptureUnavailable(f"linked capture metadata cannot be read: {path.name}") from exc
    for line in lines:
        if not line or "=" not in line:
            raise CaptureInvalid("linked capture metadata is malformed")
        key, value = line.split("=", 1)
        if not key or key in metadata:
            raise CaptureInvalid("linked capture metadata has a duplicate or empty key")
        metadata[key] = value
    return metadata


def _contained_file(run_dir: Path, name: str, required: bool = True) -> Path | None:
    candidate = run_dir / name
    try:
        resolved = candidate.resolve(strict=True)
    except FileNotFoundError:
        if required:
            raise CaptureUnavailable(f"linked capture artifact is missing: {name}") from None
        return None
    except OSError as exc:
        raise CaptureUnavailable(f"linked capture artifact cannot be read: {name}") from exc
    try:
        resolved.relative_to(run_dir)
    except ValueError as exc:
        raise CaptureInvalid(f"linked capture artifact escapes its run directory: {name}") from exc
    if not resolved.is_file():
        raise CaptureUnavailable(f"linked capture artifact is not a file: {name}")
    return resolved


def _git_log_root() -> Path:
    try:
        completed = subprocess.run(
            ["git", "rev-parse", "--git-common-dir"],
            check=True,
            capture_output=True,
            text=True,
        )
    except (FileNotFoundError, subprocess.CalledProcessError) as exc:
        raise CaptureUnavailable("could not resolve the shared Git common directory") from exc
    common_dir = Path(completed.stdout.strip())
    if not common_dir.is_absolute():
        common_dir = Path.cwd() / common_dir
    return (common_dir / "coderabbit-review-logs").resolve()


def _parse_capture_stdout(path: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    findings: list[dict[str, Any]] = []
    completes: list[dict[str, Any]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise CaptureUnavailable("linked capture stdout cannot be read") from exc
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as exc:
            raise CaptureInvalid(f"linked capture stdout has invalid JSON at line {line_number}") from exc
        if not isinstance(event, dict):
            raise CaptureInvalid(f"linked capture stdout has a non-object event at line {line_number}")
        if event.get("type") == "finding":
            findings.append(event)
        elif event.get("type") == "complete":
            completes.append(event)
    if len(completes) != 1:
        raise CaptureInvalid("linked capture stdout does not have exactly one completion event")
    complete = completes[0]
    if complete.get("status") != "review_completed":
        raise CaptureInvalid("linked capture did not complete successfully")
    if complete.get("findings") != len(findings):
        raise CaptureInvalid("linked capture finding count does not match its completion event")
    reviewed_files = complete.get("reviewedFiles")
    if not isinstance(reviewed_files, list) or any(not isinstance(path, str) for path in reviewed_files):
        raise CaptureInvalid("linked capture completion has no valid reviewed file list")
    return findings, complete


def _valid_reference(reference: str) -> bool:
    try:
        path, line_text = reference.rsplit(":", 1)
        line = int(line_text)
    except (ValueError, TypeError):
        return False
    return bool(path) and line > 0


def _read_rejections(path: Path, findings: list[dict[str, Any]]) -> tuple[dict[int, str], list[dict[str, Any]]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise CaptureUnavailable("linked rejection records cannot be read") from exc
    reasons: dict[int, str] = {}
    unlinked: list[dict[str, Any]] = []
    for line_number, line in enumerate(lines, 1):
        fields = line.split("\t")
        ordinal: int | None = None
        if len(fields) == 3 and fields[0].isdigit():
            ordinal = int(fields[0])
            reference, reason = fields[1:]
            if ordinal < 1 or ordinal > len(findings) or not _valid_reference(reference):
                unlinked.append({"format": "ordinal", "ordinal": ordinal, "reference": reference, "reason": reason})
                continue
        elif len(fields) == 2:
            reference, reason = fields
            unlinked.append({"format": "legacy", "reference": reference, "reason": reason})
            continue
        else:
            raise CaptureInvalid(f"linked rejection records are malformed at line {line_number}")
        if not reason.strip():
            unlinked.append(
                {
                    "format": "ordinal",
                    "ordinal": ordinal,
                    "reference": reference,
                    "reason": "not recorded",
                }
            )
            continue
        if ordinal in reasons:
            raise CaptureInvalid(f"linked rejection records duplicate finding {ordinal}")
        reasons[ordinal] = reason
    return reasons, unlinked


def load_capture(checkpoint: Checkpoint, repo: str, pr_number: int) -> CaptureData:
    if checkpoint.run_id is None:
        raise CaptureUnavailable("no linked data: checkpoint has no firemud-cli-run marker")
    if checkpoint.type != "CLI":
        raise CaptureUnavailable("no linked data: only CLI checkpoints have local captures")
    if checkpoint.reviewed_sha is None:
        raise CaptureUnavailable("no linked data: checkpoint has no reviewed SHA")
    if not RUN_ID.fullmatch(checkpoint.run_id):
        raise CaptureInvalid("linked run ID is invalid")
    log_root = _git_log_root()
    run_dir = log_root / checkpoint.run_id
    try:
        run_dir = run_dir.resolve(strict=True)
        run_dir.relative_to(log_root)
    except FileNotFoundError as exc:
        raise CaptureUnavailable(f"no linked data: run directory {checkpoint.run_id} is missing") from exc
    except ValueError as exc:
        raise CaptureInvalid("linked run directory escapes the review log root") from exc
    if not run_dir.is_dir():
        raise CaptureUnavailable(f"no linked data: run directory {checkpoint.run_id} is unavailable")

    metadata_path = _contained_file(run_dir, "metadata")
    stdout_path = _contained_file(run_dir, "stdout")
    status_path = _contained_file(run_dir, "exit-status")
    assert metadata_path is not None and stdout_path is not None and status_path is not None
    metadata = _read_metadata(metadata_path)
    required = ("repository", "pull_request", "candidate_sha", "candidate_files")
    if any(key not in metadata for key in required):
        raise CaptureInvalid("linked capture metadata is missing identity fields")
    if metadata["repository"].casefold() != repo.casefold() or metadata["pull_request"] != str(pr_number):
        raise CaptureInvalid("linked capture metadata does not match the requested repository and PR")
    candidate_sha = metadata["candidate_sha"]
    if not re.fullmatch(r"[0-9a-fA-F]{40}", candidate_sha) or not candidate_sha.lower().startswith(
        checkpoint.reviewed_sha.lower()
    ):
        raise CaptureInvalid("linked capture candidate SHA does not match the checkpoint")
    try:
        candidate_files = int(metadata["candidate_files"])
    except ValueError as exc:
        raise CaptureInvalid("linked capture candidate file count is invalid") from exc
    if candidate_files < 0:
        raise CaptureInvalid("linked capture candidate file count is invalid")
    try:
        status = status_path.read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise CaptureUnavailable("linked capture exit status cannot be read") from exc
    if status != "0":
        raise CaptureInvalid("linked capture did not exit successfully")
    findings, complete = _parse_capture_stdout(stdout_path)
    if len(findings) != checkpoint.raw_found:
        raise CaptureInvalid("linked capture finding count does not match the checkpoint")
    if len(complete["reviewedFiles"]) != candidate_files:
        raise CaptureInvalid("linked capture file count does not match metadata")
    if checkpoint.file_count is not None and checkpoint.file_count != candidate_files:
        raise CaptureInvalid("checkpoint file count does not match linked capture metadata")
    rejection_path = _contained_file(run_dir, "rejections.tsv", required=False)
    if rejection_path is None:
        return CaptureData(metadata, findings, {}, [], False)
    reasons, unlinked = _read_rejections(rejection_path, findings)
    return CaptureData(metadata, findings, reasons, unlinked, True)


def collect_detail(comments: list[dict[str, Any]], comment_id: int, repo: str, pr_number: int) -> dict[str, Any]:
    checkpoints, unparsed_candidates = parse_checkpoint_comments(comments)
    checkpoint = next((item for item in checkpoints if item.comment_id == comment_id), None)
    if checkpoint is None:
        return {
            "comment_id": comment_id,
            "linkage_status": "unavailable",
            "no_linked_data": True,
            "message": "no linked data: comment is not a parsed checkpoint",
            "unparsed_candidates": unparsed_candidates,
        }
    result: dict[str, Any] = {"comment_id": comment_id, "checkpoint": checkpoint.as_json()}
    try:
        capture = load_capture(checkpoint, repo, pr_number)
    except CaptureUnavailable as exc:
        result.update({"linkage_status": "unavailable", "no_linked_data": True, "message": str(exc)})
        return result
    except CaptureInvalid as exc:
        result.update({"linkage_status": "invalid", "no_linked_data": True, "message": str(exc)})
        return result
    result.update(
        {
            "linkage_status": "linked",
            "no_linked_data": False,
            "message": (
                "linked capture loaded"
                if capture.rejection_file_present
                else "linked capture loaded; rejection reasons are not recorded"
            ),
            "run_id": checkpoint.run_id,
            "capture": {
                "candidate_sha": capture.metadata["candidate_sha"],
                "candidate_files": int(capture.metadata["candidate_files"]),
            },
            "findings": [
                {
                    "ordinal": ordinal,
                    "finding": finding,
                    "rejection_reason": capture.reasons.get(ordinal),
                    "reason_status": ("recorded" if ordinal in capture.reasons else "not recorded"),
                }
                for ordinal, finding in enumerate(capture.findings, 1)
            ],
            "unlinked_rejections": capture.unlinked_rejections,
        }
    )
    return result


def collect_rejections(comments: list[dict[str, Any]], count: int, repo: str, pr_number: int) -> dict[str, Any]:
    checkpoints, _ = parse_checkpoint_comments(comments)
    cli_checkpoints = sorted(
        (checkpoint for checkpoint in checkpoints if checkpoint.type == "CLI"),
        key=lambda checkpoint: checkpoint.created_at,
    )
    selected = cli_checkpoints[-count:]
    rounds: list[dict[str, Any]] = []
    for checkpoint in selected:
        round_result: dict[str, Any] = {
            "comment_id": checkpoint.comment_id,
            "created_at": checkpoint.created_at,
            "reviewed_sha": checkpoint.reviewed_sha,
            "raw_found": checkpoint.raw_found,
            "accepted": checkpoint.accepted,
            "file_count": checkpoint.file_count,
            "run_id": checkpoint.run_id,
            "rejections": [],
        }
        try:
            capture = load_capture(checkpoint, repo, pr_number)
        except CaptureUnavailable as exc:
            round_result.update({"status": "unavailable", "message": str(exc)})
            rounds.append(round_result)
            continue
        except CaptureInvalid as exc:
            round_result.update({"status": "invalid", "message": str(exc)})
            rounds.append(round_result)
            continue
        round_result["status"] = "linked"
        round_result["message"] = (
            "linked capture loaded"
            if capture.rejection_file_present
            else "linked capture loaded; rejection reasons are not recorded"
        )
        round_result["rejections"] = [
            {
                "ordinal": ordinal,
                "finding": capture.findings[ordinal - 1],
                "reason": reason,
            }
            for ordinal, reason in sorted(capture.reasons.items())
        ]
        round_result["references"] = list(capture.unlinked_rejections)
        rounds.append(round_result)
    return {
        "matched_cli_rounds": len(cli_checkpoints),
        "returned_rounds": len(rounds),
        "omitted_rounds": len(cli_checkpoints) - len(rounds),
        "requested_rounds": count,
        "rounds": rounds,
    }


def format_marker(value: str | int | None) -> str:
    return "-" if value is None else str(value)


def emit_text(report: dict[str, Any]) -> None:
    print(
        "matched={matched_checkpoints} returned={returned_checkpoints} "
        "omitted={omitted_checkpoints} unparsed={unparsed_candidates}".format(**report)
    )
    for warning in report.get("warnings", []):
        print(f"warning={warning}")
    print("comment_id posted_at_utc type found/accepted sha files run_id")
    for item in report["timeline"]:
        if item["kind"] == "scope_change":
            print(f"{format_marker(item['comment_id'])} {item['created_at']} scope_change {item['description']}")
            continue
        checkpoint = item
        checkpoint_type = checkpoint["type"]
        if checkpoint["correction"]:
            checkpoint_type = f"Correction {checkpoint_type}"
        run_id = checkpoint.get("run_id")
        if run_id is None:
            run_id = "unlinked" if checkpoint["type"] == "CLI" else "-"
        print(
            f"{format_marker(checkpoint['comment_id'])} {checkpoint['created_at']} {checkpoint_type} "
            f"{checkpoint['raw_found']}/{checkpoint['accepted']} "
            f"{format_marker(checkpoint['reviewed_sha'])} "
            f"{format_marker(checkpoint['file_count'])} {run_id}"
        )


def emit_detail_text(detail: dict[str, Any]) -> None:
    print(f"comment_id={detail['comment_id']} linkage={detail['linkage_status']}")
    print(detail["message"])
    for item in detail.get("findings", []):
        finding = item["finding"]
        print(f"finding[{item['ordinal']}] severity={finding.get('severity', '-')} file={finding.get('fileName', '-')}")
        instructions = finding.get("codegenInstructions")
        if isinstance(instructions, str):
            print(f"  details={instructions}")
        reason = item.get("rejection_reason")
        print(f"  rejection_reason={reason if reason is not None else 'not recorded'}")
    for rejection in detail.get("unlinked_rejections", []):
        print(f"unlinked_rejection reference={rejection['reference']} reason={rejection['reason']}")


def emit_rejections_text(report: dict[str, Any]) -> None:
    print(
        "matched_cli={matched_cli_rounds} returned={returned_rounds} "
        "omitted={omitted_rounds} requested={requested_rounds}".format(**report)
    )
    for round_result in report["rounds"]:
        print(
            f"round comment_id={format_marker(round_result['comment_id'])} "
            f"posted_at_utc={round_result['created_at']} "
            f"sha={format_marker(round_result['reviewed_sha'])} "
            f"run_id={format_marker(round_result.get('run_id'))} "
            f"status={round_result['status']}"
        )
        print(f"  {round_result['message']}")
        for rejection in round_result["rejections"]:
            finding = rejection["finding"]
            print(
                f"  rejected finding[{rejection['ordinal']}] "
                f"file={finding.get('fileName', '-')} reason={rejection['reason']}"
            )
        for reference in round_result.get("references", []):
            print(f"  rejected reference={reference['reference']} reason={reference['reason']}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Extract posted Hosted and CLI review checkpoint comments.")
    parser.add_argument("--repo", required=True, help="GitHub repository in OWNER/REPO form")
    parser.add_argument("--pr", required=True, type=int, help="Pull request number")
    parser.add_argument(
        "--limit",
        type=parse_limit,
        default=20,
        help="Number of newest checkpoints to return; 0 returns all (default: 20)",
    )
    detail_or_rejections = parser.add_mutually_exclusive_group()
    detail_or_rejections.add_argument(
        "--details",
        type=parse_comment_id,
        metavar="COMMENT_ID",
        help="Show findings and local evidence for one checkpoint comment",
    )
    detail_or_rejections.add_argument(
        "--rejections",
        type=parse_positive_limit,
        metavar="N",
        help="Show recorded rejections from the latest N CLI checkpoints",
    )
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.pr <= 0:
        print("error: --pr must be a positive integer", file=sys.stderr)
        return 2
    try:
        comments = fetch_comments(args.repo, args.pr)
        if args.details is not None:
            detail = collect_detail(comments, args.details, args.repo, args.pr)
        elif args.rejections is not None:
            report = collect_rejections(comments, args.rejections, args.repo, args.pr)
        else:
            report = collect_report(comments, args.limit)
    except (CheckpointError, RuntimeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    if args.details is not None:
        if args.json:
            print(json.dumps(detail, indent=2, sort_keys=True))
        else:
            emit_detail_text(detail)
    elif args.rejections is not None:
        if args.json:
            print(json.dumps(report, indent=2, sort_keys=True))
        else:
            emit_rejections_text(report)
    elif args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        emit_text(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
