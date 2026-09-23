"""Historical checkpoint and private-review-capture evidence readers.

This module is intentionally read-only.  It accepts both the original
duration-less checkpoint comments and the current marker-bearing form, and it
never turns an absent, malformed, or unlinked capture into review evidence.
"""

from __future__ import annotations

import json
import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

CHECKPOINT_HEADING = re.compile(
    r"^(?P<bold>\*\*)?(?P<correction>Correction — )?(?P<type>Hosted|CLI): "
    r"(?P<raw_found>\d+) found / (?P<accepted>\d+) accepted"
    r"(?(bold)\*\*|)(?P<suffix>.*)$"
)
CHECKPOINT_SUFFIX = re.compile(
    r"^(?: · (?P<sha>`?[0-9a-fA-F]{7,40}`?))?"
    r"(?: · (?P<files>\d+) files)?(?: · (?P<duration>\d+)s)?$"
)
CHECKPOINT_CANDIDATE = re.compile(r"^(?:\*\*)?(?:Correction — )?(?:Hosted|CLI):")
RUN_MARKER = re.compile(r"^<!-- firemud-cli-run: (?P<run_id>run\.[A-Za-z0-9]{1,32}) -->$")
HOSTED_MARKER = re.compile(r"^<!-- firemud-hosted-review: (?P<review_id>[1-9][0-9]*) -->$")
DURATION_MARKER = re.compile(r"^<!-- firemud-review-duration-seconds: (?P<seconds>0|[1-9][0-9]*) -->$")
SCOPE_CHANGE = re.compile(r"^\*\*Review scope changed:\*\* (?P<description>.+)$")
SCOPE_MARKER = "<!-- firemud-review-scope-change -->"
RUN_ID = re.compile(r"^run\.[A-Za-z0-9]{1,32}$")
EXACT_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
SUMMARY_MARKERS = {
    "outside_diff": ("Outside diff range comments", "Outside the diff"),
    "duplicate": ("Duplicate comments",),
}
SUMMARY_WRAPPER = re.compile(r"^<(?:details|summary|strong|b|em|span)(?:\s[^>]*)?>\s*", re.IGNORECASE)
SUMMARY_EMOJI = re.compile(r"^(?:[\U0001F000-\U0001FAFF\u2600-\u27BF\uFE0F]|:[A-Za-z0-9_+-]+:)\s*")
SUMMARY_CLOSER = re.compile(
    r"(?:\s*(?:</(?:details|summary|blockquote|strong|b|em|span)>|<blockquote(?:\s[^>]*)?>|\*\*|__))+\s*$",
    re.IGNORECASE,
)


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
    hosted_review_id: int | None
    duration_seconds: int | None = None
    duration_invalid: bool = False
    author_login: str | None = None

    def as_json(self) -> dict[str, Any]:
        value: dict[str, Any] = {
            "comment_id": self.comment_id,
            "created_at": self.created_at,
            "type": self.type,
            "raw_found": self.raw_found,
            "accepted": self.accepted,
            "reviewed_sha": self.reviewed_sha,
            "file_count": self.file_count,
            "correction": self.correction,
        }
        for key, item in (
            ("run_id", self.run_id),
            ("hosted_review_id", self.hosted_review_id),
            ("duration_seconds", self.duration_seconds),
        ):
            if item is not None:
                value[key] = item
        if self.updated_at is not None:
            value["updated_at"] = self.updated_at
        if self.author_login is not None:
            value["author_login"] = self.author_login
        if self.duration_invalid:
            value["duration_invalid"] = True
        return value


@dataclass(frozen=True)
class ScopeChange:
    comment_id: int | None
    created_at: str
    description: str
    updated_at: str | None

    def as_json(self) -> dict[str, Any]:
        value: dict[str, Any] = {
            "comment_id": self.comment_id,
            "created_at": self.created_at,
            "description": self.description,
        }
        if self.updated_at is not None:
            value["updated_at"] = self.updated_at
        return value


class EvidenceError(ValueError):
    """Raised when evidence is malformed or fails identity checks."""


class CaptureUnavailable(EvidenceError):
    """A linked private capture is not present."""


class CaptureInvalid(EvidenceError):
    """A linked private capture is present but cannot be trusted."""


def _summary_marker_context(line: str, marker: str) -> tuple[bool, str] | None:
    """Return whether a line has explicit summary markup and its marker suffix."""

    text = line.strip()
    explicit = False
    while text:
        previous = text
        if text.startswith(">"):
            text = text[1:].lstrip()
        elif (heading := re.match(r"^#{1,6}\s+", text)):
            text = text[heading.end() :]
            explicit = True
        elif text.startswith(("**", "__", "- ", "+ ", "* ")):
            text = text[2:].lstrip()
            explicit = True
        else:
            wrapper = SUMMARY_WRAPPER.match(text)
            if wrapper:
                text = text[wrapper.end() :]
                explicit = True
            else:
                emoji = SUMMARY_EMOJI.match(text)
                if emoji:
                    text = text[emoji.end() :]
                    explicit = True
        if text == previous:
            break
    if not text.casefold().startswith(marker.casefold()):
        return None
    return explicit, text[len(marker) :]


def _summary_action_evidence(body: str) -> tuple[tuple[int, int], bool]:
    counts: dict[str, int] = {"outside_diff": 0, "duplicate": 0}
    has_sections = False
    for kind, markers in SUMMARY_MARKERS.items():
        found: list[int] = []
        for line in body.splitlines():
            for marker in markers:
                context = _summary_marker_context(line, marker)
                if context is None:
                    continue
                explicit, tail = context
                tail = SUMMARY_CLOSER.sub("", tail.strip())
                if not tail:
                    raise EvidenceError(f"CodeRabbit {kind} summary section has no canonical count")
                count = re.fullmatch(r"\((\d+)\)", tail)
                if count:
                    found.append(int(count.group(1)))
                    has_sections = True
                elif explicit or tail.startswith("("):
                    raise EvidenceError(f"CodeRabbit {kind} summary section has no canonical count")
        counts[kind] = max(found, default=0)
    return (counts["outside_diff"], counts["duplicate"]), has_sections


def summary_action_counts(body: str) -> tuple[int, int]:
    """Return canonical summary-only counts, rejecting malformed marked sections."""
    counts, _ = _summary_action_evidence(body)
    return counts


def has_summary_action_sections(body: str) -> bool:
    """Return whether a body contains a canonical summary count, including zero."""
    _, has_sections = _summary_action_evidence(body)
    return has_sections


@dataclass(frozen=True)
class CaptureData:
    metadata: dict[str, str]
    findings: list[dict[str, Any]]
    decisions: dict[int, tuple[str, str]] = field(default_factory=dict)
    unlinked_decisions: list[dict[str, Any]] = field(default_factory=list)
    decision_file_present: bool = False
    source_identity: str | None = None


@dataclass(frozen=True)
class HostedCapture:
    repository: str
    pull_request: int
    review: dict[str, Any]
    comments: list[dict[str, Any]]
    decisions: dict[int, tuple[str, str]]
    unlinked_decisions: list[dict[str, Any]]
    decision_file_present: bool


def _fields(comment: Any, position: int) -> tuple[str, str, str | None, int | None, str | None]:
    if not isinstance(comment, dict):
        raise EvidenceError(f"comment {position} is not an object")
    body, created = comment.get("body"), comment.get("created_at")
    updated, comment_id = comment.get("updated_at"), comment.get("id")
    if not isinstance(body, str) or not isinstance(created, str):
        raise EvidenceError(f"comment {position} is missing body or created_at")
    if updated is not None and not isinstance(updated, str):
        raise EvidenceError(f"comment {position} has invalid updated_at")
    if comment_id is not None and (isinstance(comment_id, bool) or not isinstance(comment_id, int) or comment_id <= 0):
        raise EvidenceError(f"comment {position} has invalid id")
    first = next((line for line in body.splitlines() if line.strip() and not line[0].isspace()), None)
    return body, created, updated, comment_id, first


def _run_id(body: str) -> tuple[str | None, bool]:
    markers = [line.strip() for line in body.splitlines()[1:] if line.strip().startswith("<!-- firemud-cli-run:")]
    if len(markers) > 1:
        return None, True
    if not markers:
        return None, False
    match = RUN_MARKER.fullmatch(markers[0])
    return (match.group("run_id") if match else None), False


def _hosted_id(body: str) -> tuple[int | None, bool]:
    markers = [
        line.strip() for line in body.splitlines()[1:] if line.strip().startswith("<!-- firemud-hosted-review:")
    ]
    if len(markers) > 1:
        return None, True
    if not markers:
        return None, False
    match = HOSTED_MARKER.fullmatch(markers[0])
    return (int(match.group("review_id")) if match else None), False


def _duration_evidence(body: str, visible_duration: str | None) -> tuple[int | None, bool]:
    """Return duration only when visible and hidden evidence agree.

    Historical comments without either representation remain readable. Once
    either representation is present, malformed, duplicate, incomplete, or
    mismatched evidence is explicitly invalid so linkage cannot infer a
    duration from a partially trusted comment.
    """

    found: list[int] = []
    malformed = False
    for line in body.splitlines()[1:]:
        if line.strip().startswith("<!-- firemud-review-duration-seconds:"):
            match = DURATION_MARKER.fullmatch(line.strip())
            if match is None:
                malformed = True
            else:
                found.append(int(match.group("seconds")))
    if malformed or len(found) > 1:
        return None, True
    if visible_duration is None and not found:
        return None, False
    if visible_duration is None or not found or int(visible_duration) != found[0]:
        return None, True
    return found[0], False


def parse_checkpoint_comments(comments: list[dict[str, Any]]) -> tuple[list[Checkpoint], int]:
    checkpoints: list[Checkpoint] = []
    unparsed = 0
    for position, comment in enumerate(comments, 1):
        body, created, updated, comment_id, first = _fields(comment, position)
        if first is None:
            continue
        match = CHECKPOINT_HEADING.fullmatch(first)
        if match is None:
            if CHECKPOINT_CANDIDATE.match(first):
                unparsed += 1
            continue
        suffix = CHECKPOINT_SUFFIX.fullmatch(match.group("suffix").split(r"\n", 1)[0])
        raw, accepted = int(match.group("raw_found")), int(match.group("accepted"))
        if suffix is None or accepted > raw:
            unparsed += 1
            continue
        run_id, duplicate_run_marker = _run_id(body)
        hosted_review_id, duplicate_hosted_marker = _hosted_id(body)
        if duplicate_run_marker or duplicate_hosted_marker:
            unparsed += 1
            continue
        visible = suffix.group("duration")
        duration, duration_invalid = _duration_evidence(body, visible)
        sha = suffix.group("sha")
        checkpoints.append(
            Checkpoint(
                comment_id=comment_id,
                created_at=created,
                type=match.group("type"),
                raw_found=raw,
                accepted=accepted,
                reviewed_sha=sha.strip("`") if sha else None,
                file_count=int(suffix.group("files")) if suffix.group("files") else None,
                correction=match.group("correction") is not None,
                updated_at=updated if updated and updated != created else None,
                run_id=run_id,
                hosted_review_id=hosted_review_id,
                duration_seconds=duration,
                duration_invalid=duration_invalid,
                author_login=(
                    comment.get("author_login")
                    if isinstance(comment.get("author_login"), str)
                    else (
                        (comment.get("author") or {}).get("login") if isinstance(comment.get("author"), dict) else None
                    )
                ),
            )
        )
    return checkpoints, unparsed


def parse_scope_changes(comments: list[dict[str, Any]]) -> list[ScopeChange]:
    changes: list[ScopeChange] = []
    for position, comment in enumerate(comments, 1):
        body, created, updated, comment_id, first = _fields(comment, position)
        match = SCOPE_CHANGE.fullmatch(first or "")
        if match and sum(line.strip() == SCOPE_MARKER for line in body.splitlines()[1:]) == 1:
            changes.append(
                ScopeChange(comment_id, created, match.group("description"), updated if updated != created else None)
            )
    return changes


def duration_marker_audit(comments: list[dict[str, Any]]) -> dict[str, int]:
    result = {"malformed_count": 0, "duplicate_count": 0, "missing_count": 0, "mismatch_count": 0}
    for position, comment in enumerate(comments, 1):
        body, _, _, _, first = _fields(comment, position)
        heading = CHECKPOINT_HEADING.fullmatch(first or "")
        if heading is None:
            continue
        suffix = CHECKPOINT_SUFFIX.fullmatch(heading.group("suffix").split(r"\n", 1)[0])
        if suffix is None:
            continue
        visible = suffix.group("duration")
        found: list[int] = []
        malformed = False
        for line in body.splitlines()[1:]:
            if line.strip().startswith("<!-- firemud-review-duration-seconds:"):
                marker = DURATION_MARKER.fullmatch(line.strip())
                if marker is None:
                    result["malformed_count"] += 1
                    malformed = True
                else:
                    found.append(int(marker.group("seconds")))
        if len(found) > 1:
            result["duplicate_count"] += 1
        if visible is not None and (malformed or not found) or visible is None and (found or malformed):
            result["missing_count"] += 1
        elif visible is not None and len(found) == 1 and int(visible) != found[0]:
            result["mismatch_count"] += 1
    return result


def collect_evidence(comments: list[dict[str, Any]], limit: int = 0) -> dict[str, Any]:
    checkpoints, unparsed = parse_checkpoint_comments(comments)
    checkpoints.sort(key=lambda item: item.created_at)
    returned = checkpoints if limit == 0 else checkpoints[-limit:]
    duration = duration_marker_audit(comments)
    warnings: list[str] = []
    for key, label in (
        ("malformed_count", "malformed"),
        ("duplicate_count", "duplicate"),
        ("missing_count", "incomplete"),
        ("mismatch_count", "mismatched"),
    ):
        if duration[key]:
            warnings.append(f"{duration[key]} {label} review-duration marker(s) were not used")
    timeline = [{"kind": "checkpoint", **item.as_json()} for item in checkpoints]
    timeline.extend({"kind": "scope_change", **item.as_json()} for item in parse_scope_changes(comments))
    timeline.sort(key=lambda item: item["created_at"])
    return {
        "checkpoints": [item.as_json() for item in returned],
        "matched_checkpoints": len(checkpoints),
        "unparsed_candidates": unparsed,
        "duration_audit": duration,
        "warnings": warnings,
        "timeline": timeline,
    }


def git_common_dir() -> Path:
    try:
        completed = subprocess.run(
            ["git", "rev-parse", "--git-common-dir"], check=True, capture_output=True, text=True, timeout=30
        )
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise CaptureUnavailable("could not resolve shared Git common directory") from exc
    value = Path(completed.stdout.strip())
    return value if value.is_absolute() else (Path.cwd() / value).resolve()


def private_review_roots(common: Path | None = None) -> tuple[Path, ...]:
    root = common or git_common_dir()
    # New writes belong below firemud; the legacy root remains read-compatible.
    return (root / "firemud", root / "coderabbit-review-logs")


def _contained_file(directory: Path, name: str, required: bool = True) -> Path | None:
    try:
        resolved_dir = directory.resolve()
        candidate = (resolved_dir / name).resolve(strict=True)
    except FileNotFoundError:
        if required:
            raise CaptureUnavailable(f"linked capture artifact is missing: {name}") from None
        return None
    except OSError as exc:
        raise CaptureUnavailable(f"linked capture artifact cannot be read: {name}") from exc
    try:
        candidate.relative_to(resolved_dir)
    except ValueError as exc:
        raise CaptureInvalid(f"linked capture artifact escapes its run directory: {name}") from exc
    if not candidate.is_file():
        raise CaptureUnavailable(f"linked capture artifact is not a file: {name}")
    return candidate


def _read_metadata(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise CaptureUnavailable("linked capture metadata cannot be read") from exc
    result: dict[str, str] = {}
    for line in lines:
        if not line or "=" not in line:
            raise CaptureInvalid("linked capture metadata is malformed")
        key, value = line.split("=", 1)
        if not key or key in result:
            raise CaptureInvalid("linked capture metadata has duplicate or empty key")
        result[key] = value
    return result


def _parse_capture_stdout(path: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    findings: list[dict[str, Any]] = []
    completes: list[dict[str, Any]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise CaptureUnavailable("linked capture stdout cannot be read") from exc
    for number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as exc:
            raise CaptureInvalid(f"linked capture stdout has invalid JSON at line {number}") from exc
        if not isinstance(event, dict):
            raise CaptureInvalid(f"linked capture stdout has a non-object event at line {number}")
        if event.get("type") == "finding":
            findings.append(event)
        elif event.get("type") == "complete":
            completes.append(event)
    if len(completes) != 1 or completes[0].get("status") != "review_completed":
        raise CaptureInvalid("linked capture has no unique successful completion")
    complete = completes[0]
    if complete.get("findings") != len(findings) or not isinstance(complete.get("reviewedFiles"), list):
        raise CaptureInvalid("linked capture completion does not match findings/files")
    return findings, complete


def _validate_cli_checkpoint_decisions(checkpoint: Checkpoint, capture: CaptureData) -> None:
    if checkpoint.raw_found == 0:
        return
    if capture.unlinked_decisions or len(capture.decisions) != checkpoint.raw_found:
        raise CaptureInvalid("raw-positive CLI checkpoint has no complete linked findings decisions")
    # Historical CLI runs recorded rejected findings in rejections.tsv before
    # decisions.tsv existed.  That format is attributable only when every
    # finding is linked as rejected and the public checkpoint reports zero
    # accepted findings; all other raw-positive cases remain fail-closed.
    if not capture.decision_file_present and (
        checkpoint.accepted != 0 or any(disposition != "rejected" for disposition, _ in capture.decisions.values())
    ):
        raise CaptureInvalid("historical rejection records cannot explain accepted CLI findings")
    accepted = sum(disposition == "accepted" for disposition, _ in capture.decisions.values())
    if accepted != checkpoint.accepted:
        raise CaptureInvalid("CLI checkpoint accepted count does not match linked findings decisions")


def _load_cli_capture(
    checkpoint: Checkpoint,
    repo: str,
    pr_number: int,
    common: Path | None,
    *,
    validate_checkpoint_decisions: bool,
) -> CaptureData:
    if checkpoint.type != "CLI" or not checkpoint.run_id or not RUN_ID.fullmatch(checkpoint.run_id):
        raise CaptureUnavailable("checkpoint has no valid CLI capture marker")
    if checkpoint.duration_invalid:
        raise CaptureInvalid("checkpoint has invalid visible/hidden duration evidence")
    roots = private_review_roots(common)
    # Legacy CLI captures are directly below <git-common>/coderabbit-review-logs.
    # New callers may place captures below the firemud review namespace.
    root_candidates = [
        roots[-1],
        roots[0] / "pr-review" / "runs",
        roots[0] / "runs",
        roots[0] / "coderabbit-review-logs",
    ]
    run_dir = next((root / checkpoint.run_id for root in root_candidates if (root / checkpoint.run_id).is_dir()), None)
    if run_dir is None:
        raise CaptureUnavailable(f"no linked data: run directory {checkpoint.run_id} is missing")
    metadata_path = _contained_file(run_dir, "metadata")
    stdout_path = _contained_file(run_dir, "stdout")
    status_path = _contained_file(run_dir, "exit-status")
    assert metadata_path and stdout_path and status_path
    metadata = _read_metadata(metadata_path)
    if (
        metadata.get("run_id") != checkpoint.run_id
        or metadata.get("repository", "").casefold() != repo.casefold()
        or metadata.get("pull_request") != str(pr_number)
    ):
        raise CaptureInvalid("linked capture metadata does not match checkpoint identity")
    candidate_sha = metadata.get("candidate_sha", "")
    if not EXACT_SHA.fullmatch(candidate_sha) or (
        checkpoint.reviewed_sha and not candidate_sha.lower().startswith(checkpoint.reviewed_sha.lower())
    ):
        raise CaptureInvalid("linked capture candidate SHA does not match checkpoint")
    if status_path.read_text(encoding="utf-8").strip() != "0":
        raise CaptureInvalid("linked capture did not exit successfully")
    findings, complete = _parse_capture_stdout(stdout_path)
    if len(findings) != checkpoint.raw_found:
        raise CaptureInvalid("linked capture finding count does not match checkpoint")
    try:
        candidate_files = int(metadata.get("candidate_files", "-1"))
    except ValueError as exc:
        raise CaptureInvalid("linked capture candidate file count is invalid") from exc
    if len(complete["reviewedFiles"]) != candidate_files or checkpoint.file_count not in (None, candidate_files):
        raise CaptureInvalid("linked capture file count does not match checkpoint")
    if checkpoint.duration_seconds is not None:
        recorded_duration = metadata.get("review_duration_seconds")
        if recorded_duration is None or not recorded_duration.isdigit():
            raise CaptureInvalid("linked capture metadata has no valid review duration")
        duration_path = _contained_file(run_dir, "review-duration-seconds")
        assert duration_path is not None
        try:
            artifact_duration = duration_path.read_text(encoding="utf-8").strip()
        except OSError as exc:
            raise CaptureUnavailable("linked capture review duration cannot be read") from exc
        if int(recorded_duration) != checkpoint.duration_seconds or artifact_duration != recorded_duration:
            raise CaptureInvalid("checkpoint duration does not match linked capture metadata")
    decision_path = _contained_file(run_dir, "decisions.tsv", required=False)
    if decision_path is None:
        rejection_path = _contained_file(run_dir, "rejections.tsv", required=False)
        if rejection_path is None:
            capture = CaptureData(metadata, findings, source_identity=str(run_dir.resolve()))
            if validate_checkpoint_decisions:
                _validate_cli_checkpoint_decisions(checkpoint, capture)
            return capture
        decisions: dict[int, tuple[str, str]] = {}
        unlinked: list[dict[str, Any]] = []
        for number, line in enumerate(rejection_path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            fields = line.split("\t")
            if len(fields) == 3 and fields[0].isdigit():
                finding_id, reference, reason = int(fields[0]), fields[1], fields[2]
                if not 1 <= finding_id <= len(findings) or not reason.strip():
                    unlinked.append(
                        {"finding_id": finding_id, "reference": reference, "reason": reason or "not recorded"}
                    )
                elif finding_id in decisions:
                    raise CaptureInvalid(f"rejection records duplicate finding {finding_id}")
                else:
                    decisions[finding_id] = ("rejected", reason)
            elif len(fields) == 2:
                unlinked.append({"format": "legacy", "reference": fields[0], "reason": fields[1]})
            else:
                raise CaptureInvalid(f"rejection records are malformed at line {number}")
        capture = CaptureData(metadata, findings, decisions, unlinked, False, str(run_dir.resolve()))
        if validate_checkpoint_decisions:
            _validate_cli_checkpoint_decisions(checkpoint, capture)
        return capture
    decisions: dict[int, tuple[str, str]] = {}
    unlinked: list[dict[str, Any]] = []
    for number, line in enumerate(decision_path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        fields = line.split("\t")
        if len(fields) != 3 or not fields[0].isdigit() or fields[1] not in {"accepted", "rejected"}:
            raise CaptureInvalid(f"decision records are malformed at line {number}")
        finding_id, disposition, reason = int(fields[0]), fields[1], fields[2]
        if not 1 <= finding_id <= len(findings) or (disposition == "rejected" and not reason.strip()):
            unlinked.append({"finding_id": finding_id, "disposition": disposition, "reason": reason or "not recorded"})
        elif finding_id in decisions:
            raise CaptureInvalid("decision records duplicate a finding")
        else:
            decisions[finding_id] = (disposition, reason)
    capture = CaptureData(metadata, findings, decisions, unlinked, True, str(run_dir.resolve()))
    if validate_checkpoint_decisions:
        _validate_cli_checkpoint_decisions(checkpoint, capture)
    return capture


def load_cli_capture(checkpoint: Checkpoint, repo: str, pr_number: int, common: Path | None = None) -> CaptureData:
    """Load a public checkpoint's capture and require its decisions to match."""

    return _load_cli_capture(
        checkpoint,
        repo,
        pr_number,
        common,
        validate_checkpoint_decisions=True,
    )


def discover_cli_captures(repo: str, pr_number: int, common: Path | None = None) -> list[CaptureData]:
    """Find complete private CLI captures that may not yet have a public checkpoint."""

    roots = private_review_roots(common)
    candidate_roots = (
        roots[-1],
        roots[0] / "pr-review" / "runs",
        roots[0] / "runs",
        roots[0] / "coderabbit-review-logs",
    )
    captures: list[CaptureData] = []
    visited: set[Path] = set()
    for root in candidate_roots:
        if root.is_symlink() or not root.is_dir():
            continue
        try:
            run_dirs = list(root.iterdir())
        except OSError:
            continue
        for run_dir in run_dirs:
            if not RUN_ID.fullmatch(run_dir.name) or run_dir.is_symlink() or not run_dir.is_dir():
                continue
            resolved = run_dir.resolve()
            if resolved in visited:
                continue
            visited.add(resolved)
            try:
                metadata = _read_metadata(_contained_file(run_dir, "metadata"))
                if (
                    metadata.get("run_id") != run_dir.name
                    or metadata.get("repository", "").casefold() != repo.casefold()
                    or metadata.get("pull_request") != str(pr_number)
                ):
                    continue
                candidate_sha = metadata.get("candidate_sha", "")
                candidate_files = int(metadata.get("candidate_files", "-1"))
                stdout = _contained_file(run_dir, "stdout")
                assert stdout is not None
                findings, _ = _parse_capture_stdout(stdout)
                checkpoint = Checkpoint(
                    comment_id=None,
                    created_at="",
                    type="CLI",
                    raw_found=len(findings),
                    accepted=0,
                    reviewed_sha=candidate_sha[:12] if EXACT_SHA.fullmatch(candidate_sha) else None,
                    file_count=candidate_files,
                    correction=False,
                    updated_at=None,
                    run_id=run_dir.name,
                    hosted_review_id=None,
                )
                captures.append(
                    _load_cli_capture(
                        checkpoint,
                        repo,
                        pr_number,
                        common,
                        validate_checkpoint_decisions=False,
                    )
                )
            except (EvidenceError, OSError, ValueError):
                continue
    return captures


def _is_coderabbit(login: Any) -> bool:
    return isinstance(login, str) and login.casefold() in {"coderabbitai", "coderabbitai[bot]"}


def _validate_hosted_review(review: Any, review_id: int) -> dict[str, Any]:
    if (
        not isinstance(review, dict)
        or review.get("id") != review_id
        or not _is_coderabbit((review.get("user") or {}).get("login"))
    ):
        raise CaptureInvalid("hosted review identity or author is invalid")
    if review.get("state") in {"PENDING", "DISMISSED"} or not isinstance(review.get("submitted_at"), str):
        raise CaptureInvalid("hosted review is not a completed submission")
    commit_id = review.get("commit_id")
    if not isinstance(commit_id, str) or not EXACT_SHA.fullmatch(commit_id):
        raise CaptureInvalid("hosted review has no exact reviewed commit")
    return review


def _validate_hosted_comments(comments: Any, review_id: int) -> list[dict[str, Any]]:
    if not isinstance(comments, list):
        raise CaptureInvalid("hosted review comments are not a list")
    ids: set[int] = set()
    for comment in comments:
        if (
            not isinstance(comment, dict)
            or isinstance(comment.get("id"), bool)
            or not isinstance(comment.get("id"), int)
            or comment["id"] <= 0
        ):
            raise CaptureInvalid("hosted review comment has an invalid ID")
        if comment["id"] in ids:
            raise CaptureInvalid("hosted review comments contain duplicate IDs")
        ids.add(comment["id"])
        parent = comment.get("pull_request_review_id")
        if parent is not None and parent != review_id:
            raise CaptureInvalid("hosted review comment belongs to another review")
    return comments


def _read_decisions(
    path: Path | None, finding_ids: set[int]
) -> tuple[dict[int, tuple[str, str]], list[dict[str, Any]], bool]:
    if path is None:
        return {}, [], False
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise CaptureUnavailable("decision records cannot be read") from exc
    decisions: dict[int, tuple[str, str]] = {}
    unlinked: list[dict[str, Any]] = []
    for number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        fields = line.split("\t")
        if len(fields) != 3 or not fields[0].isdigit() or fields[1] not in {"accepted", "rejected"}:
            raise CaptureInvalid(f"decision records are malformed at line {number}")
        finding_id, disposition, reason = int(fields[0]), fields[1], fields[2]
        if finding_id not in finding_ids or (disposition == "rejected" and not reason.strip()):
            unlinked.append({"finding_id": finding_id, "disposition": disposition, "reason": reason or "not recorded"})
        elif finding_id in decisions:
            raise CaptureInvalid("decision records duplicate a finding")
        else:
            decisions[finding_id] = (disposition, reason)
    return decisions, unlinked, True


def _hosted_snapshot_candidates(repo: str, pr_number: int, review_id: int, common: Path | None = None) -> list[Path]:
    roots = private_review_roots(common)
    safe = repo.replace("/", "_")
    return [
        roots[1] / f"hosted-review.{review_id}" / "snapshot.json",
        roots[0] / f"hosted-review.{review_id}" / "snapshot.json",
        roots[0] / "hosted" / safe / f"pr-{pr_number}" / f"review-{review_id}" / "snapshot.json",
    ]


def load_hosted_capture(repo: str, pr_number: int, review_id: int, common: Path | None = None) -> HostedCapture:
    if isinstance(review_id, bool) or not isinstance(review_id, int) or review_id <= 0:
        raise CaptureInvalid("hosted review ID must be positive")
    snapshot_path = next(
        (
            path
            for path in _hosted_snapshot_candidates(repo, pr_number, review_id, common)
            if path.is_file() and not path.is_symlink()
        ),
        None,
    )
    if snapshot_path is None:
        raise CaptureUnavailable(f"hosted review snapshot {review_id} is missing")
    try:
        snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CaptureInvalid("hosted review snapshot is unreadable or malformed") from exc
    if (
        not isinstance(snapshot, dict)
        or snapshot.get("source") != "hosted"
        or snapshot.get("repository", "").casefold() != repo.casefold()
        or snapshot.get("pull_request") != pr_number
    ):
        raise CaptureInvalid("hosted review snapshot identity does not match")
    review = _validate_hosted_review(snapshot.get("review"), review_id)
    comments = _validate_hosted_comments(snapshot.get("comments"), review_id)
    finding_ids = {
        comment["id"]
        for comment in comments
        if _is_coderabbit((comment.get("user") or {}).get("login")) and comment.get("in_reply_to_id") is None
    }
    decisions, unlinked, present = _read_decisions(
        _contained_file(snapshot_path.parent, "decisions.tsv", required=False), finding_ids
    )
    return HostedCapture(repo, pr_number, review, comments, decisions, unlinked, present)


def hosted_capture_json(capture: HostedCapture) -> dict[str, Any]:
    findings = [
        comment
        for comment in capture.comments
        if _is_coderabbit((comment.get("user") or {}).get("login")) and comment.get("in_reply_to_id") is None
    ]
    return {
        "source": "hosted",
        "repository": capture.repository,
        "pull_request": capture.pull_request,
        "review_id": capture.review["id"],
        "submitted_at": capture.review["submitted_at"],
        "commit_id": capture.review["commit_id"],
        "inline_findings": findings,
        "decisions": {
            str(key): {"disposition": value[0], "reason": value[1]} for key, value in capture.decisions.items()
        },
        "unlinked_decisions": capture.unlinked_decisions,
        "decision_file_present": capture.decision_file_present,
    }


def hosted_checkpoint_evidence(
    checkpoint: Checkpoint, reviews: list[dict[str, Any]], current_head: str
) -> dict[str, Any]:
    """Return attributable completed Hosted proof; missing proof is never completion."""

    if checkpoint.duration_invalid:
        return {"status": "missing", "reason": "Hosted checkpoint has invalid visible/hidden duration evidence"}
    if checkpoint.type != "Hosted" or checkpoint.hosted_review_id is None:
        return {"status": "missing", "reason": "Hosted checkpoint has no valid review marker"}
    for review in reviews:
        author = (review.get("user") or {}).get("login", "") if isinstance(review, dict) else ""
        if review.get("id") != checkpoint.hosted_review_id or author.casefold() not in {
            "coderabbitai",
            "coderabbitai[bot]",
        }:
            continue
        commit = review.get("commit_id")
        if review.get("state") in {"PENDING", "DISMISSED"} or not isinstance(review.get("submitted_at"), str):
            return {"status": "missing", "reason": "Hosted review is not a completed submission"}
        if (
            not isinstance(commit, str)
            or not EXACT_SHA.fullmatch(commit)
            or commit.casefold() != current_head.casefold()
        ):
            return {"status": "missing", "reason": "Hosted review commit does not match current head"}
        return {
            "status": "completed",
            "review_id": checkpoint.hosted_review_id,
            "commit_id": commit,
            "submitted_at": review["submitted_at"],
        }
    return {"status": "missing", "reason": "attributable Hosted review evidence was not found"}


def evidence(
    pr: int | None = None,
    as_json: bool = False,
    *,
    repo: str | None = None,
    comments: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Build structured checkpoint evidence from live or supplied comments.

    Tests and higher-level status composition may supply normalized comments;
    the operator path fetches the complete issue-comment connection through the
    GitHub adapter and converts its camel-case timestamps once at this boundary.
    """

    if pr is None or isinstance(pr, bool) or pr <= 0:
        raise EvidenceError("evidence requires a positive PR number")
    if comments is None:
        try:
            from . import github
        except ImportError:
            import github  # type: ignore[no-redef]

        repo = github.infer_repo(repo)
        payload = github.fetch_pull_request(repo, pr)
        comments = [
            {
                "id": github.immutable_database_id(item),
                "body": item.get("body"),
                "created_at": item.get("createdAt"),
                "updated_at": item.get("updatedAt"),
                "author_login": ((item.get("author") or {}).get("login")),
            }
            for item in payload["data"]["repository"]["pullRequest"]["comments"]["nodes"]
        ]
    result = collect_evidence(comments)
    return result


evidence_report = evidence
