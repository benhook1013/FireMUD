"""Explicit, retry-safe publication of finalized native review checkpoints."""

from __future__ import annotations

import json
import re
from pathlib import Path

from . import evidence, github, hosted, sqlite_hosted_capture
from .sqlite_review_records import ReviewRecordsError
from .state import _locked


def _refuse(message):
    raise ReviewRecordsError(message)


def _hosted_fields(store, repo, source_pr, run, attempt):
    """Re-prove attribution from the immutable archive, never from today's diff."""
    metadata = store.attempt(run["run_id"])["metadata"]
    files = metadata.get("reviewed_file_count")
    if type(files) is not int or files < 0:
        _refuse("Hosted attempt lacks verified request-time reviewed file count; retain historical manual workflow")
    artifacts = store.attempt_artifacts(run["run_id"])
    try:
        capture = json.loads(artifacts["metadata"])
        record = sqlite_hosted_capture._validate_trigger_record(repo, source_pr, capture["trigger_record"])
        reviews = json.loads(artifacts["hosted_review"])
        comments = json.loads(artifacts["hosted_comments"])
        if (metadata.get("repository") != repo or capture.get("repository") != repo
                or capture.get("pull_request") != source_pr or capture.get("state") != "completed"
                or capture.get("terminal") is not True or capture.get("attributable") is not True
                or capture.get("head_sha") != run["source_head"] or record["head_sha"] != run["source_head"]
                or str(capture.get("response_id")) != attempt["provider_review_id"]
                or str(capture.get("trigger_id")) != attempt["trigger_id"]
                or str(record["trigger"]["id"]) != attempt["trigger_id"]):
            _refuse("Hosted archive attribution conflicts with completed attempt")
        payload = {"data": {"repository": {"pullRequest": {
            "number": source_pr, "headRefOid": run["source_head"],
            "reviews": {"nodes": reviews}, "comments": {"nodes": comments["comments"]},
            "reviewThreads": {"nodes": comments["review_threads"]},
        }}}}
        pull = sqlite_hosted_capture._complete_pull_request(payload, source_pr)
        result = hosted.trigger_state(repo, source_pr, payload, record)
        if (result.state != "completed" or result.attributed is not True
                or str(result.response_id) != attempt["provider_review_id"]
                or result.duration_seconds != attempt["duration_seconds"]):
            _refuse("Hosted immutable capture does not prove completed attempt attribution/duration")
        _, review, comment = sqlite_hosted_capture._terminal_event(result, pull, observed_at=attempt["finished_at"])
        findings = sqlite_hosted_capture._findings_for_completed_result(
            result, pull, record, response_review=review, response_comment=comment,
            finished_at=attempt["finished_at"],
        )
        if len(findings) != run["counts"]["found"]:
            _refuse("Hosted captured finding count conflicts with finalized source run")
        response_id = attempt["provider_review_id"]
        if not isinstance(response_id, str) or not re.fullmatch(r"[1-9][0-9]*", response_id):
            _refuse("Hosted provider response identity is invalid")
        return files, f"<!-- firemud-hosted-review: {response_id} -->"
    except ReviewRecordsError:
        raise
    except (KeyError, TypeError, ValueError) as exc:
        raise ReviewRecordsError("Hosted completed capture is incomplete or mismatched") from exc


def checkpoint_body(store, *, repo, source_pr, run_id):
    history = store.history(source_pr, include_display=False)
    runs = [run for run in history["runs"] if run["run_id"] == run_id]
    attempts = [attempt for attempt in store.attempt_history(source_pr) if attempt["run_id"] == run_id]
    if len(runs) != 1 or len(attempts) != 1:
        _refuse("checkpoint requires exactly one native source run and matching attempt")
    run, attempt = runs[0], attempts[0]
    channel = run["channel"]
    if (channel not in {"cli", "hosted"} or run["outcome"] != "completed"
            or run["attributable"] is not True or run["finalized"] is not True
            or attempt["attempt_id"] != run_id or attempt["channel"] != channel
            or attempt["state"] != "completed" or attempt["candidate_sha"] != run["source_head"]):
        _refuse("checkpoint requires completed attributable finalized native evidence")
    head, duration, counts = run["source_head"], attempt["duration_seconds"], run["counts"]
    if (not isinstance(head, str) or not evidence.EXACT_SHA.fullmatch(head)
            or type(duration) is not int or duration < 0
            or any(type(counts.get(key)) is not int or counts[key] < 0 for key in ("found", "accepted", "routed"))
            or counts["accepted"] + counts["routed"] > counts["found"]):
        _refuse("checkpoint source head, counts or duration is invalid")
    if channel == "cli":
        if not evidence.RUN_ID.fullmatch(run_id):
            _refuse("native CLI run has invalid marker identity")
        snapshot = store.cli_capture_snapshot(run_id, source_pr=source_pr)
        evidence._cli_capture_from_sql(snapshot, repo, source_pr)
        metadata = json.loads(snapshot["artifacts"]["metadata"])
        if metadata.get("repository") != repo:
            _refuse("CLI capture lacks exact repository attribution")
        files = metadata["candidate_files"]
        marker = f"<!-- firemud-cli-run: {run_id} -->"
    else:
        files, marker = _hosted_fields(store, repo, source_pr, run, attempt)
    body = (f"{'CLI' if channel == 'cli' else 'Hosted'}: {counts['found']} Found / "
            f"{counts['accepted']} Accepted / {counts['routed']} Routed · {head} · {files} files · "
            f"{evidence.format_duration_seconds(duration)}\n{marker}\n"
            f"<!-- firemud-review-duration-seconds: {duration} -->")
    if channel == "cli":
        checkpoints, _ = evidence.parse_checkpoint_comments([{"body": body, "created_at": attempt["finished_at"]}])
        evidence._cli_capture_from_sql(snapshot, repo, source_pr, checkpoints[0])
    return body, marker


def _same_checkpoint(comment, canonical_body, *, repo, source_pr):
    """Compare canonical semantics after validating a local presentation variant.

    Shared historical parsing remains unchanged. Link destinations must prove
    the exact recorded commit; only presentation whitespace is normalized.
    """
    lines = [line.strip() for line in comment["body"].splitlines() if line.strip()]
    if len(lines) != 3:
        return False
    canonical = {**comment, "body": canonical_body}
    expected, unparsed = evidence.parse_checkpoint_comments([canonical])
    if unparsed or len(expected) != 1:
        return False
    head = expected[0].reviewed_sha

    def commit_link(match):
        label, url = match.group(1).strip("`"), match.group(2)
        if (not head.lower().startswith(label.lower()) or url not in {
            f"https://github.com/{repo}/commit/{head}",
            f"https://github.com/{repo}/pull/{source_pr}/commits/{head}",
        }):
            raise ValueError("checkpoint commit link conflicts with exact captured commit")
        return head

    lines[0] = re.sub(r"\[(`?[0-9a-fA-F]{7,40}`?)\]\(([^)\s]+)\)", commit_link, lines[0])
    lines[0] = re.sub(r"[ \t]+", " ", lines[0])
    lines[0] = re.sub(r" *· *", " · ", lines[0])
    lines[0] = re.sub(r" */ *", " / ", lines[0])
    # Unknown/extra/malformed markers cannot disappear through normalization.
    if set(lines[1:]) != set(canonical_body.splitlines()[1:]):
        return False
    parsed, unparsed = evidence.parse_checkpoint_comments([{**comment, "body": "\n".join(lines)}])
    if unparsed or len(parsed) != 1:
        return False
    observed, required = parsed[0], expected[0]
    if not observed.reviewed_sha or not head.lower().startswith(observed.reviewed_sha.lower()):
        return False
    return all(getattr(observed, field) == getattr(required, field) for field in (
        "type", "raw_found", "accepted", "routed", "file_count", "correction", "run_id",
        "hosted_review_id", "duration_seconds", "duration_invalid",
    ))


def publish_checkpoint(store, *, repo, source_pr, run_id, adapter=github):
    """Serialize publishers, recover matching comments, and never retry uncertain POST."""
    github.parse_repo(repo)
    with _locked(Path(str(store.path) + ".checkpoint-publication.lock")):
        body, marker = checkpoint_body(store, repo=repo, source_pr=source_pr, run_id=run_id)
        identity = adapter._fetch_api_object(f"repos/{repo}")
        actor = adapter.fetch_authenticated_user()
        if (identity.get("full_name") != repo or type(identity.get("id")) is not int
                or identity["id"] <= 0 or type(actor.get("id")) is not int or actor["id"] <= 0
                or not isinstance(actor.get("login"), str) or not actor["login"]):
            _refuse("cannot verify authenticated publisher and repository identity")
        endpoint = f"repos/{repo}/issues/{source_pr}/comments?per_page=100"
        marker_kind, marker_id = marker[5:-4].split(": ", 1)
        identity_end = r"(?![0-9])" if marker_kind == "firemud-hosted-review" else r"(?![A-Za-z0-9])"
        linkage = re.compile(r"<!--\s*" + re.escape(marker_kind) + r"\s*:\s*" + re.escape(marker_id) + identity_end)

        def matching(comments):
            matches = []
            for comment in comments:
                text = comment.get("body", "")
                # Inspect any occurrence of the linked provider identity, including
                # malformed or duplicate markers, rather than silently posting again.
                if not isinstance(text, str) or not linkage.search(text):
                    continue
                user = comment.get("user") or {}
                try:
                    same = _same_checkpoint(comment, body, repo=repo, source_pr=source_pr)
                except (evidence.EvidenceError, KeyError, TypeError, ValueError):
                    same = False
                if (not same or user.get("id") != actor["id"]
                        or user.get("login", "").casefold() != actor["login"].casefold()
                        or type(comment.get("id")) is not int or comment["id"] <= 0
                        or comment.get("issue_url") != f"https://api.github.com/repos/{repo}/issues/{source_pr}"):
                    _refuse("existing checkpoint conflicts with canonical attribution, counts or markers")
                matches.append(comment)
            if len(matches) > 1:
                _refuse("multiple checkpoints already claim the same provider identity")
            return matches[0] if matches else None

        intent = json.dumps({"repository": repo, "repository_id": identity["id"], "source_pr": source_pr,
                             "run_id": run_id, "actor_id": actor["id"], "actor_login": actor["login"],
                             "body": body}, sort_keys=True)
        comment = matching(adapter.fetch_api_endpoint(endpoint))
        # Even a public replay must reject an existing intent with conflicting author.
        fresh = store.reserve_checkpoint_publication(run_id, intent)
        if comment is not None:
            status = "reused"
        elif not fresh:
            _refuse("prior checkpoint POST may have occurred; no verified comment is visible; retry read recovery later")
        else:
            try:
                posted = adapter.post_issue_comment(repo, source_pr, body)
                comment = matching([posted])
            except Exception:  # noqa: BLE001 - uncertain POST must recover, never reissue
                comment = matching(adapter.fetch_api_endpoint(endpoint))
            if comment is None:
                _refuse("checkpoint POST is uncertain; retained intent prevents another POST; retry read recovery later")
            status = "published"
        return {"status": status, "source_pr": source_pr, "run_id": run_id,
                "comment_id": comment["id"], "comment_url": comment.get("html_url"), "body": body}
