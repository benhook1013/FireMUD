"""Small, fail-closed GitHub data access layer for PR review tooling.

The module deliberately returns the familiar raw GraphQL shape.  Callers can
therefore validate evidence without copying live GitHub state into private
configuration.  All three PR connections are paginated beyond the API's
first page.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import time
from collections.abc import Iterator, Sequence
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from contextvars import ContextVar, Token
from pathlib import Path
from typing import Any

GH_TIMEOUT_SECONDS = 30
GH_API_TIMEOUT_SECONDS = 120
REVIEW_COMMAND_TYPES = {
    "@coderabbitai review": "incremental",
    "@coderabbitai full review": "full",
}
REPO_NAME = re.compile(r"^[^/\s]+/[^/\s]+$")
REVIEW_CONNECTIONS = ("reviewThreads", "comments", "reviews")
HOSTED_PREFLIGHT_BUDGET_SECONDS = 120
ISSUE_COMMENT_BATCH_SIZE = 25


class HostedPreflightDeadlineExceeded(TimeoutError):
    """A bounded provider preflight ran out of its shared preparation budget."""

    def __init__(
        self,
        phase: str,
        elapsed_seconds: float,
        budget_seconds: float,
        completed: int,
        total: int | None,
        preflight_name: str = "Hosted",
        *,
        phase_elapsed_seconds: float | None = None,
        prior_phase_seconds: dict[str, float] | None = None,
        prior_phase_total_seconds: float | None = None,
        prior_phase_count: int | None = None,
        batch_chunk: tuple[int, int, str] | None = None,
    ):
        self.phase = phase
        self.elapsed_seconds = elapsed_seconds
        self.budget_seconds = budget_seconds
        self.completed = completed
        self.total = total
        self.preflight_name = preflight_name
        self.phase_elapsed_seconds = phase_elapsed_seconds
        self.prior_phase_seconds = dict(prior_phase_seconds or {})
        self.prior_phase_total_seconds = prior_phase_total_seconds
        self.prior_phase_count = prior_phase_count
        self.batch_chunk = batch_chunk
        progress = f"{completed}/{total}" if total is not None else str(completed)
        operation = preflight_name if preflight_name == "PR status" else f"{preflight_name} preflight"
        diagnostic_details: list[str] = []
        if phase_elapsed_seconds is not None:
            diagnostic_details.append(f"phase_elapsed={phase_elapsed_seconds:.1f}s")
        if prior_phase_seconds:
            prior_total = (
                sum(prior_phase_seconds.values()) if prior_phase_total_seconds is None else prior_phase_total_seconds
            )
            phase_count = len(prior_phase_seconds) if prior_phase_count is None else prior_phase_count
            # Keep the exception readable and bounded even when selection has
            # visited many queue entries. The aggregate retains total context;
            # these labels are the largest prior phases, not recency claims.
            top_phases = list(prior_phase_seconds.items())[:4]
            rendered = ";".join(f"{name[:48]}:{seconds:.1f}s" for name, seconds in top_phases)
            diagnostic_details.extend(
                (
                    f"prior_phase_total={prior_total:.1f}s",
                    f"prior_phase_count={phase_count}",
                    f"top_prior_phases={rendered}",
                )
            )
        if batch_chunk is not None:
            index, count, stage = batch_chunk
            diagnostic_details.append(f"batch_chunk={index}/{count}:{stage}")
        diagnostic_suffix = f" diagnostics({'; '.join(diagnostic_details)})" if diagnostic_details else ""
        super().__init__(
            f"{operation} deadline exceeded (phase={phase}, elapsed={elapsed_seconds:.1f}s, "
            f"completed={progress}, budget={budget_seconds:.0f}s){diagnostic_suffix}"
        )


class HostedPreflightBudget:
    """One monotonic budget shared across target selection and provider preparation."""

    def __init__(
        self,
        timeout_seconds: float = HOSTED_PREFLIGHT_BUDGET_SECONDS,
        *,
        preflight_name: str = "Hosted",
    ) -> None:
        if isinstance(timeout_seconds, bool) or not isinstance(timeout_seconds, (int, float)) or timeout_seconds <= 0:
            raise ValueError("Hosted preflight budget must be a positive number of seconds")
        self.timeout_seconds = float(timeout_seconds)
        self.preflight_name = preflight_name
        self.started_at = time.monotonic()
        self.deadline = self.started_at + self.timeout_seconds
        self.current_phase = "starting"
        self._phase_started_at = self.started_at
        self.phase_seconds: dict[str, float] = {}
        self.phase_progress: dict[str, dict[str, int | None]] = {}
        self._active_batch_chunk: tuple[int, int, str] | None = None
        self.completed = 0
        self.total: int | None = None
        self.active = True
        self._finished_at: float | None = None

    def _close_phase(self, now: float) -> None:
        elapsed = max(0.0, now - self._phase_started_at)
        self.phase_seconds[self.current_phase] = self.phase_seconds.get(self.current_phase, 0.0) + elapsed
        self.phase_progress[self.current_phase] = {"completed": self.completed, "total": self.total}
        self._phase_started_at = now

    def set_phase(self, phase: str, *, completed: int = 0, total: int | None = None) -> None:
        self.remaining_seconds()
        now = time.monotonic()
        self._close_phase(now)
        self.current_phase = phase
        self._phase_started_at = now
        self.completed = completed
        self.total = total
        self._active_batch_chunk = None
        self.remaining_seconds()

    def set_completed(self, completed: int) -> None:
        self.completed = completed
        self.remaining_seconds()

    def begin_batch_chunk(self, index: int, total: int) -> None:
        """Track only bounded identity-batch progress, never request contents."""

        self._active_batch_chunk = (index, total, "graphql_call")

    def set_batch_chunk_stage(self, index: int, stage: str) -> None:
        if stage not in {"graphql_call", "identity_shape_validation"}:
            raise ValueError("unsupported preflight diagnostic stage")
        current = self._active_batch_chunk
        if current is not None and current[0] == index:
            self._active_batch_chunk = (current[0], current[1], stage)

    def finish_batch_chunk(self, index: int) -> None:
        current = self._active_batch_chunk
        if current is not None and current[0] == index:
            self._active_batch_chunk = None

    def remaining_seconds(self) -> float:
        remaining = self.deadline - time.monotonic()
        if self.active and remaining <= 0:
            raise self.exceeded()
        return max(0.0, remaining)

    def elapsed_seconds(self) -> float:
        return max(0.0, time.monotonic() - self.started_at)

    def request_timeout(self, cap_seconds: float) -> float:
        remaining = self.remaining_seconds()
        if not self.active:
            return cap_seconds
        return min(cap_seconds, remaining)

    def exceeded(self) -> HostedPreflightDeadlineExceeded:
        now = time.monotonic()
        elapsed = max(0.0, now - self.started_at)
        phase_elapsed = max(0.0, now - self._phase_started_at)
        measured_prior_phases = {name: seconds for name, seconds in self.phase_seconds.items() if round(seconds, 1) > 0}
        prior_total = sum(measured_prior_phases.values())
        prior_phase_count = len(measured_prior_phases)
        prior_phases = dict(sorted(measured_prior_phases.items(), key=lambda item: item[1], reverse=True)[:8])
        batch_chunk = self._active_batch_chunk
        return HostedPreflightDeadlineExceeded(
            self.current_phase,
            elapsed,
            self.timeout_seconds,
            self.completed,
            self.total,
            self.preflight_name,
            phase_elapsed_seconds=phase_elapsed,
            prior_phase_seconds=prior_phases,
            prior_phase_total_seconds=prior_total,
            prior_phase_count=prior_phase_count,
            batch_chunk=batch_chunk,
        )

    def complete(self) -> None:
        self.remaining_seconds()
        self.suspend()

    def suspend(self) -> None:
        """Stop enforcing preflight after success or before bounded local cleanup."""

        if not self.active:
            return
        now = time.monotonic()
        self._close_phase(now)
        self._finished_at = now
        self.active = False
        _HOSTED_PREFLIGHT_BUDGET.set(None)

    def summary(self) -> dict[str, Any]:
        now = self._finished_at if self._finished_at is not None else time.monotonic()
        phase_seconds = dict(self.phase_seconds)
        if self.active:
            phase_seconds[self.current_phase] = phase_seconds.get(self.current_phase, 0.0) + max(
                0.0, now - self._phase_started_at
            )
        return {
            "elapsed_seconds": round(max(0.0, now - self.started_at), 1),
            "budget_seconds": self.timeout_seconds,
            "phase_seconds": {name: round(seconds, 1) for name, seconds in phase_seconds.items()},
            "phase_progress": dict(self.phase_progress),
        }


_HOSTED_PREFLIGHT_BUDGET: ContextVar[HostedPreflightBudget | None] = ContextVar(
    "firemud_hosted_preflight_budget", default=None
)


@contextmanager
def activate_hosted_preflight_budget(
    timeout_seconds: float = HOSTED_PREFLIGHT_BUDGET_SECONDS,
    *,
    preflight_name: str = "Hosted",
) -> Iterator[HostedPreflightBudget]:
    budget = HostedPreflightBudget(timeout_seconds, preflight_name=preflight_name)
    token = _HOSTED_PREFLIGHT_BUDGET.set(budget)
    try:
        yield budget
    finally:
        _HOSTED_PREFLIGHT_BUDGET.reset(token)


@contextmanager
def hosted_preflight_budget(
    timeout_seconds: float = HOSTED_PREFLIGHT_BUDGET_SECONDS,
) -> Iterator[HostedPreflightBudget]:
    """Use the current Hosted budget, creating one only at the outer boundary."""

    current = active_hosted_preflight_budget()
    if current is not None:
        yield current
        return
    with activate_hosted_preflight_budget(timeout_seconds) as budget:
        yield budget


@contextmanager
def cli_preflight_budget(
    timeout_seconds: float = HOSTED_PREFLIGHT_BUDGET_SECONDS,
) -> Iterator[HostedPreflightBudget]:
    """Use the shared preflight budget for CLI target and candidate preparation."""

    current = active_hosted_preflight_budget()
    if current is not None:
        yield current
        return
    with activate_hosted_preflight_budget(timeout_seconds, preflight_name="CLI") as budget:
        yield budget


@contextmanager
def bind_hosted_preflight_budget(budget: HostedPreflightBudget) -> Iterator[None]:
    """Bind the caller's budget inside a bounded worker thread."""

    token: Token[HostedPreflightBudget | None] = _HOSTED_PREFLIGHT_BUDGET.set(budget)
    try:
        yield
    finally:
        _HOSTED_PREFLIGHT_BUDGET.reset(token)


@contextmanager
def without_hosted_preflight_budget() -> Iterator[None]:
    """Temporarily exempt bounded local cleanup without ending the shared budget."""

    token: Token[HostedPreflightBudget | None] = _HOSTED_PREFLIGHT_BUDGET.set(None)
    try:
        yield
    finally:
        _HOSTED_PREFLIGHT_BUDGET.reset(token)


def active_hosted_preflight_budget() -> HostedPreflightBudget | None:
    budget = _HOSTED_PREFLIGHT_BUDGET.get()
    return budget if budget is not None and budget.active else None


def _request_timeout(cap_seconds: float) -> tuple[float, HostedPreflightBudget | None]:
    budget = active_hosted_preflight_budget()
    return (budget.request_timeout(cap_seconds), budget) if budget is not None else (cap_seconds, None)


def _raise_if_budget_expired(budget: HostedPreflightBudget | None) -> None:
    if budget is not None:
        budget.remaining_seconds()


def parse_repo(repo: str) -> tuple[str, str]:
    if not REPO_NAME.fullmatch(repo):
        raise ValueError("repo must be in owner/name form")
    return tuple(repo.split("/", 1))  # type: ignore[return-value]


def infer_repo(repo: str | None = None) -> str:
    """Resolve ``owner/name`` from an explicit value, environment, or ``gh``."""

    selected = repo or os.environ.get("GH_REPO") or os.environ.get("GITHUB_REPOSITORY")
    if selected:
        parse_repo(selected)
        return selected
    timeout, budget = _request_timeout(GH_TIMEOUT_SECONDS)
    try:
        completed = subprocess.run(
            ["gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        if budget is not None:
            try:
                _raise_if_budget_expired(budget)
            except HostedPreflightDeadlineExceeded as expired:
                raise expired from exc
        raise RuntimeError("could not infer GitHub repository identity") from exc
    except (FileNotFoundError, subprocess.CalledProcessError) as exc:
        raise RuntimeError("could not infer GitHub repository identity") from exc
    _raise_if_budget_expired(budget)
    selected = completed.stdout.strip()
    parse_repo(selected)
    return selected


def immutable_database_id(item: dict[str, Any]) -> int | None:
    value = item.get("databaseId", item.get("id"))
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value > 0:
        return value
    if isinstance(value, str) and value.isdigit() and int(value) > 0:
        return int(value)
    return None


def is_coderabbit_login(value: Any) -> bool:
    return isinstance(value, str) and value.casefold() in {"coderabbitai", "coderabbitai[bot]"}


def run_gh_query(query: str, variables: dict[str, str | int]) -> dict[str, Any]:
    args = ["gh", "api", "graphql", "-f", f"query={query}"]
    for key, value in variables.items():
        option = "-F" if isinstance(value, int) and not isinstance(value, bool) else "-f"
        args.extend([option, f"{key}={value}"])
    timeout, budget = _request_timeout(GH_TIMEOUT_SECONDS)
    try:
        completed = subprocess.run(
            args,
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.TimeoutExpired as exc:
        if budget is not None:
            try:
                _raise_if_budget_expired(budget)
            except HostedPreflightDeadlineExceeded as expired:
                raise expired from exc
        raise RuntimeError(f"gh api graphql timed out after {GH_TIMEOUT_SECONDS} seconds") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() if exc.stderr else "gh api graphql failed") from exc
    _raise_if_budget_expired(budget)
    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("gh api graphql returned invalid JSON") from exc
    if not isinstance(payload, dict):
        raise TypeError("gh api graphql returned a non-object payload")
    return payload


_BASE_QUERY = """
query($owner:String!, $repo:String!, $number:Int!) {
  repository(owner:$owner, name:$repo) { pullRequest(number:$number) {
    number
    baseRefName
    baseRefOid
    headRefOid
    changedFiles
    commits(last:1) { nodes { commit { oid committedDate statusCheckRollup { state } } } }
    reviewThreads(first:100) { nodes { id isResolved isOutdated path line comments(first:20) {
      nodes { id databaseId author { login } body url createdAt updatedAt pullRequestReview { databaseId } originalCommit { oid } }
      pageInfo { hasNextPage endCursor }
    } } pageInfo { hasNextPage endCursor } }
    comments(first:100) { nodes { id databaseId author { login } body createdAt updatedAt url }
      pageInfo { hasNextPage endCursor } }
    reviews(first:100) { nodes { id databaseId author { login } body state submittedAt url commit { oid } }
      pageInfo { hasNextPage endCursor } }
  } }
}
""".strip()


def _connection_query(connection: str) -> str:
    fields = {
        "reviewThreads": "nodes { id isResolved isOutdated path line comments(first:20) { nodes { id databaseId author { login } body url createdAt updatedAt pullRequestReview { databaseId } originalCommit { oid } } pageInfo { hasNextPage endCursor } } }",
        "comments": "nodes { id databaseId author { login } body createdAt updatedAt url }",
        "reviews": "nodes { id databaseId author { login } body state submittedAt url commit { oid } }",
    }[connection]
    return f"""
query($owner:String!, $repo:String!, $number:Int!, $after:String!) {{
  repository(owner:$owner, name:$repo) {{ pullRequest(number:$number) {{
    {connection}(first:100, after:$after) {{ {fields} pageInfo {{ hasNextPage endCursor }} }}
  }} }}
}}
""".strip()


def _thread_comments_query() -> str:
    return """
query($threadId:ID!, $after:String!) {
  node(id:$threadId) {
    ... on PullRequestReviewThread {
      comments(first:20, after:$after) {
        nodes { id databaseId author { login } body url createdAt updatedAt pullRequestReview { databaseId } originalCommit { oid } }
        pageInfo { hasNextPage endCursor }
      }
    }
  }
}
""".strip()


def _pull_request_from_graphql_payload(payload: dict[str, Any]) -> dict[str, Any]:
    """Validate the review-bearing portion of a GraphQL response."""

    errors = payload.get("errors")
    if errors is not None and (not isinstance(errors, list) or errors):
        raise RuntimeError("GitHub GraphQL response contains errors")
    try:
        pull_request = payload["data"]["repository"]["pullRequest"]
    except (KeyError, TypeError) as exc:
        raise RuntimeError("GitHub response has no pull request") from exc
    if not isinstance(pull_request, dict):
        raise TypeError("GitHub response has no pull request")
    return pull_request


def _review_connection(
    pull_request: dict[str, Any], connection: str, *, require_page_info: bool
) -> tuple[list[Any], dict[str, Any] | None]:
    value = pull_request.get(connection)
    if not isinstance(value, dict):
        raise TypeError(f"GitHub response is missing {connection} connection")
    nodes = value.get("nodes")
    if not isinstance(nodes, list):
        raise TypeError(f"GitHub {connection} connection has malformed nodes")
    page_info = value.get("pageInfo")
    if require_page_info:
        if not isinstance(page_info, dict) or not isinstance(page_info.get("hasNextPage"), bool):
            raise TypeError(f"GitHub {connection} connection has malformed page info")
    elif page_info is not None and not isinstance(page_info, dict):
        raise TypeError(f"GitHub {connection} connection has malformed page info")
    return nodes, page_info


def _reject_incomplete_input_connection(connection: str, page_info: dict[str, Any] | None) -> None:
    if page_info is None:
        return
    has_next_page = page_info.get("hasNextPage")
    if not isinstance(has_next_page, bool):
        raise TypeError(f"GitHub {connection} connection has malformed page info")
    if has_next_page:
        raise TypeError(f"GitHub {connection} connection has incomplete pages")


def _thread_comments_from_graphql_payload(
    payload: dict[str, Any], thread_id: str
) -> tuple[list[Any], dict[str, Any] | None]:
    errors = payload.get("errors")
    if errors is not None and (not isinstance(errors, list) or errors):
        raise RuntimeError("GitHub GraphQL response contains errors")
    try:
        node = payload["data"]["node"]
    except (KeyError, TypeError) as exc:
        raise RuntimeError(f"GitHub response has no review thread {thread_id}") from exc
    if not isinstance(node, dict):
        raise TypeError(f"GitHub response has malformed review thread {thread_id}")
    return _review_connection({"comments": node.get("comments")}, "comments", require_page_info=True)


def _paginate_thread_comments(thread: dict[str, Any]) -> None:
    thread_id = thread.get("id")
    if not isinstance(thread_id, str) or not thread_id:
        raise TypeError("GitHub review thread has no opaque id")
    nodes, page = _review_connection(thread, "comments", require_page_info=True)
    nodes = list(nodes)
    query = _thread_comments_query()
    used_cursors: set[str] = set()
    while page["hasNextPage"]:
        cursor = page.get("endCursor")
        if not isinstance(cursor, str) or not cursor:
            raise RuntimeError(f"GitHub review thread {thread_id} pagination has no cursor")
        if cursor in used_cursors:
            raise RuntimeError(f"GitHub review thread {thread_id} pagination repeated cursor {cursor!r}")
        used_cursors.add(cursor)
        next_payload = run_gh_query(
            query,
            {
                "threadId": thread_id,
                "after": cursor,
            },
        )
        page_nodes, page = _thread_comments_from_graphql_payload(next_payload, thread_id)
        nodes.extend(page_nodes)
    thread["comments"] = {"nodes": nodes}


def _paginate_review_thread_nodes(
    threads: list[Any],
) -> None:
    for thread in threads:
        if not isinstance(thread, dict):
            raise TypeError("GitHub reviewThreads connection has malformed node")
        _paginate_thread_comments(thread)


def fetch_pull_request(repo: str, pr_number: int) -> dict[str, Any]:
    """Fetch a complete PR payload, paginating every review-bearing connection."""

    owner, name = parse_repo(repo)
    payload = run_gh_query(_BASE_QUERY, {"owner": owner, "repo": name, "number": pr_number})
    pr = _pull_request_from_graphql_payload(payload)
    for connection in REVIEW_CONNECTIONS:
        nodes, page = _review_connection(pr, connection, require_page_info=True)
        nodes = list(nodes)
        if connection == "reviewThreads":
            _paginate_review_thread_nodes(nodes)
        query = _connection_query(connection)
        used_cursors: set[str] = set()
        while page["hasNextPage"]:
            cursor = page.get("endCursor")
            if not isinstance(cursor, str) or not cursor:
                raise RuntimeError(f"GitHub {connection} pagination has no cursor")
            if cursor in used_cursors:
                raise RuntimeError(f"GitHub {connection} pagination repeated cursor {cursor!r}")
            used_cursors.add(cursor)
            next_payload = run_gh_query(
                query,
                {"owner": owner, "repo": name, "number": pr_number, "after": cursor},
            )
            next_pr = _pull_request_from_graphql_payload(next_payload)
            page_nodes, page = _review_connection(next_pr, connection, require_page_info=True)
            if connection == "reviewThreads":
                _paginate_review_thread_nodes(page_nodes)
            nodes.extend(page_nodes)
        pr[connection] = {"nodes": nodes}
    return payload


def _issue_comment_batch_query(pr_numbers: Sequence[int], after_by_pr: dict[int, str]) -> str:
    declarations = ["$owner:String!", "$repo:String!"]
    declarations.extend(f"$after_{number}:String!" for number in after_by_pr)
    selections = []
    for number in pr_numbers:
        after = f", after:$after_{number}" if number in after_by_pr else ""
        selections.append(
            f"pr_{number}: pullRequest(number:{number}) {{ number "
            f"comments(first:100{after}) {{ "
            "nodes { id databaseId author { login } body createdAt updatedAt url } "
            "pageInfo { hasNextPage endCursor } } }"
        )
    return f"""
query({", ".join(declarations)}) {{
  repository(owner:$owner, name:$repo) {{
    {" ".join(selections)}
  }}
}}
""".strip()


def fetch_issue_comments_batch(repo: str, pr_numbers: Sequence[int]) -> dict[int, list[dict[str, Any]]]:
    """Fetch complete issue-comment histories for at most 25 PRs per query.

    Each alias is paginated independently until its full comment connection is
    read. Missing identities, malformed connections, duplicate comment IDs,
    and cursor loops fail closed so callers cannot treat partial data as a
    complete repository scan.
    """

    owner, name = parse_repo(repo)
    numbers = tuple(pr_numbers)
    if any(isinstance(number, bool) or not isinstance(number, int) or number <= 0 for number in numbers):
        raise ValueError("issue-comment batch requires positive PR numbers")
    if len(set(numbers)) != len(numbers):
        raise ValueError("issue-comment batch requires unique PR numbers")
    if len(numbers) > ISSUE_COMMENT_BATCH_SIZE:
        raise ValueError(f"issue-comment batch is limited to {ISSUE_COMMENT_BATCH_SIZE} PRs")
    if not numbers:
        return {}

    comments_by_pr: dict[int, list[dict[str, Any]]] = {number: [] for number in numbers}
    seen_ids: dict[int, set[int]] = {number: set() for number in numbers}
    used_cursors: dict[int, set[str]] = {number: set() for number in numbers}
    active_numbers = numbers
    after_by_pr: dict[int, str] = {}
    while active_numbers:
        query = _issue_comment_batch_query(active_numbers, after_by_pr)
        variables: dict[str, str | int] = {"owner": owner, "repo": name}
        variables.update({f"after_{number}": cursor for number, cursor in after_by_pr.items()})
        payload = run_gh_query(query, variables)
        errors = payload.get("errors")
        if errors is not None and (not isinstance(errors, list) or errors):
            raise RuntimeError("GitHub GraphQL response contains errors")
        try:
            repository = payload["data"]["repository"]
        except (KeyError, TypeError) as exc:
            raise RuntimeError("GitHub response has no issue-comment batch") from exc
        if not isinstance(repository, dict):
            raise TypeError("GitHub response has no issue-comment batch")
        expected_aliases = {f"pr_{number}" for number in active_numbers}
        if set(repository) != expected_aliases:
            raise RuntimeError("GitHub issue-comment batch identities are incomplete or unexpected")

        pending_after: dict[int, str] = {}
        for number in active_numbers:
            pull_request = repository.get(f"pr_{number}")
            if (
                not isinstance(pull_request, dict)
                or type(pull_request.get("number")) is not int
                or pull_request["number"] != number
            ):
                raise RuntimeError(f"GitHub issue-comment batch identity is missing or mismatched for PR #{number}")
            connection = pull_request.get("comments")
            if not isinstance(connection, dict):
                raise TypeError(f"GitHub issue-comment connection is missing for PR #{number}")
            nodes = connection.get("nodes")
            page_info = connection.get("pageInfo")
            if not isinstance(nodes, list):
                raise TypeError(f"GitHub issue-comment nodes are malformed for PR #{number}")
            if not isinstance(page_info, dict) or type(page_info.get("hasNextPage")) is not bool:
                raise TypeError(f"GitHub issue-comment page info is malformed for PR #{number}")
            for comment in nodes:
                if not isinstance(comment, dict):
                    raise TypeError(f"GitHub issue-comment node is malformed for PR #{number}")
                comment_id = immutable_database_id(comment)
                if comment_id is None:
                    raise TypeError(f"GitHub issue-comment identity is missing for PR #{number}")
                if comment_id in seen_ids[number]:
                    raise RuntimeError(f"GitHub issue-comment identity is duplicated for PR #{number}")
                if not isinstance(comment.get("body"), str):
                    raise TypeError(f"GitHub issue-comment body is malformed for PR #{number}")
                seen_ids[number].add(comment_id)
                comments_by_pr[number].append(comment)

            if page_info["hasNextPage"]:
                cursor = page_info.get("endCursor")
                if not isinstance(cursor, str) or not cursor:
                    raise RuntimeError(f"GitHub issue-comment pagination has no cursor for PR #{number}")
                if cursor in used_cursors[number]:
                    raise RuntimeError(f"GitHub issue-comment pagination repeated cursor for PR #{number}")
                used_cursors[number].add(cursor)
                pending_after[number] = cursor

        active_numbers = tuple(pending_after)
        after_by_pr = pending_after
    return comments_by_pr


def load_pull_request(input_path: str | Path | None, repo: str, pr_number: int) -> dict[str, Any]:
    if input_path is None:
        return fetch_pull_request(repo, pr_number)
    payload = json.loads(Path(input_path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise TypeError("input payload must be a JSON object")
    pr = _pull_request_from_graphql_payload(payload)
    for connection in REVIEW_CONNECTIONS:
        nodes, page_info = _review_connection(pr, connection, require_page_info=False)
        _reject_incomplete_input_connection(connection, page_info)
        if connection == "reviewThreads":
            for thread in nodes:
                if isinstance(thread, dict) and "comments" in thread:
                    _, thread_page_info = _review_connection(thread, "comments", require_page_info=False)
                    _reject_incomplete_input_connection("review-thread comments", thread_page_info)
    return payload


def fetch_api_endpoint(endpoint: str) -> list[dict[str, Any]]:
    """Read a REST endpoint with GitHub CLI pagination and validate its shape."""

    timeout, budget = _request_timeout(GH_API_TIMEOUT_SECONDS)
    try:
        completed = subprocess.run(
            ["gh", "api", "--method", "GET", "--paginate", "--slurp", endpoint],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.TimeoutExpired as exc:
        if budget is not None:
            try:
                _raise_if_budget_expired(budget)
            except HostedPreflightDeadlineExceeded as expired:
                raise expired from exc
        raise RuntimeError(f"gh api timed out after {GH_API_TIMEOUT_SECONDS} seconds") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() if exc.stderr else "gh api failed") from exc
    _raise_if_budget_expired(budget)
    try:
        pages = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("gh api returned invalid JSON") from exc
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        raise RuntimeError("gh api returned an unexpected paginated response")
    values = [value for page in pages for value in page]
    if any(not isinstance(value, dict) for value in values):
        raise RuntimeError("gh api returned a non-object item")
    return values


def _fetch_api_pages(endpoint: str) -> list[dict[str, Any]]:
    """Read an object-shaped REST endpoint through GitHub CLI pagination."""

    timeout, budget = _request_timeout(GH_API_TIMEOUT_SECONDS)
    try:
        completed = subprocess.run(
            ["gh", "api", "--method", "GET", "--paginate", "--slurp", endpoint],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.TimeoutExpired as exc:
        if budget is not None:
            try:
                _raise_if_budget_expired(budget)
            except HostedPreflightDeadlineExceeded as expired:
                raise expired from exc
        raise RuntimeError(f"gh api timed out after {GH_API_TIMEOUT_SECONDS} seconds") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() if exc.stderr else "gh api failed") from exc
    _raise_if_budget_expired(budget)
    try:
        pages = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("gh api returned invalid JSON") from exc
    if not isinstance(pages, list) or any(not isinstance(page, dict) for page in pages):
        raise RuntimeError("gh api returned an unexpected paginated object response")
    return pages


def _fetch_api_object(endpoint: str) -> dict[str, Any]:
    """Read one non-paginated REST object and validate its shape."""

    timeout, budget = _request_timeout(GH_API_TIMEOUT_SECONDS)
    try:
        completed = subprocess.run(
            ["gh", "api", "--method", "GET", endpoint],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.TimeoutExpired as exc:
        if budget is not None:
            try:
                _raise_if_budget_expired(budget)
            except HostedPreflightDeadlineExceeded as expired:
                raise expired from exc
        raise RuntimeError(f"gh api timed out after {GH_API_TIMEOUT_SECONDS} seconds") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() if exc.stderr else "gh api failed") from exc
    _raise_if_budget_expired(budget)
    try:
        value = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("gh api returned invalid JSON") from exc
    if not isinstance(value, dict):
        raise TypeError("gh api returned a non-object response")
    return value


def fetch_required_status_checks(repo: str, branch: str) -> dict[str, Any]:
    """Fetch the branch-protection authority for required contexts and apps."""

    parse_repo(repo)
    if not branch or any(character in branch for character in "\r\n"):
        raise ValueError("branch ref must be a non-empty single line")
    value = _fetch_api_object(f"repos/{repo}/branches/{branch}/protection/required_status_checks")
    contexts = value.get("contexts", [])
    checks = value.get("checks", [])
    if not isinstance(contexts, list) or any(not isinstance(item, str) or not item for item in contexts):
        raise RuntimeError("GitHub required status-check authority has malformed contexts")
    if not isinstance(checks, list) or any(not isinstance(item, dict) for item in checks):
        raise RuntimeError("GitHub required status-check authority has malformed checks")
    normalized_checks: list[dict[str, Any]] = []
    for index, item in enumerate(checks, 1):
        context = item.get("context")
        app_id = item.get("app_id")
        if not isinstance(context, str) or not context:
            raise RuntimeError(f"GitHub required status-check {index} has no context")
        if app_id is not None and (isinstance(app_id, bool) or not isinstance(app_id, int) or app_id <= 0):
            raise RuntimeError(f"GitHub required status-check {index} has an invalid app_id")
        normalized_checks.append({"context": context, "app_id": app_id})
    return {
        "available": True,
        "strict": value.get("strict"),
        "contexts": contexts,
        "checks": normalized_checks,
    }


def _positive_id(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _actions_workflow_identities(repo: str, head_sha: str) -> dict[int, dict[str, Any]]:
    """Associate exact check suites with stable workflows from one head inventory."""
    pages = _fetch_api_pages(f"repos/{repo}/actions/runs?head_sha={head_sha}&per_page=100")
    runs: list[dict[str, Any]] = []
    totals: set[int] = set()
    for page in pages:
        total = page.get("total_count")
        values = page.get("workflow_runs")
        if (
            isinstance(total, bool)
            or not isinstance(total, int)
            or total < 0
            or not isinstance(values, list)
            or any(not isinstance(item, dict) for item in values)
        ):
            raise RuntimeError("GitHub Actions inventory is malformed")
        totals.add(total)
        runs.extend(values)
    # Filtered Actions inventories are capped at 1,000 results by GitHub.
    if len(totals) != 1 or next(iter(totals)) > 1000 or len(runs) != next(iter(totals)):
        raise RuntimeError("GitHub Actions inventory coverage is incomplete")
    if any(not _positive_id(run.get("id")) for run in runs) or len({run["id"] for run in runs}) != len(runs):
        raise RuntimeError("GitHub Actions inventory has ambiguous run IDs")
    identities: dict[int, dict[str, Any]] = {}
    blocked: set[int] = set()
    for run in runs:
        suite_id = run.get("check_suite_id")
        if not _positive_id(suite_id):
            continue
        repository = run.get("repository")
        if (
            not isinstance(repository, dict)
            or not isinstance(repository.get("full_name"), str)
            or repository["full_name"].casefold() != repo.casefold()
            or not _positive_id(repository.get("id"))
            or not _positive_id(run.get("workflow_id"))
            or not isinstance(run.get("head_sha"), str)
            or run["head_sha"].casefold() != head_sha.casefold()
        ):
            blocked.add(suite_id)
            continue
        identity = {
            "repository_id": repository["id"],
            "workflow_id": run["workflow_id"],
            "head_sha": head_sha.lower(),
            "app_id": 15368,
        }
        previous = identities.get(suite_id)
        if previous is not None and (previous["identity"] != identity or previous["run_id"] != run["id"]):
            blocked.add(suite_id)
        identities[suite_id] = {"identity": identity, "run_id": run["id"]}
    return {suite_id: value for suite_id, value in identities.items() if suite_id not in blocked}


def fetch_check_inventory(repo: str, head_sha: str) -> dict[str, Any]:
    """Fetch every check run and commit status attached to one exact head."""

    parse_repo(repo)
    if not re.fullmatch(r"[0-9a-fA-F]{40}", head_sha):
        raise ValueError("head_sha must be an exact commit SHA")
    check_runs: list[dict[str, Any]] = []
    for page in _fetch_api_pages(f"repos/{repo}/commits/{head_sha}/check-runs?per_page=100"):
        values = page.get("check_runs")
        if not isinstance(values, list) or any(not isinstance(item, dict) for item in values):
            raise RuntimeError("GitHub check-run inventory has malformed check_runs")
        for item in values:
            normalized = dict(item)
            normalized.setdefault("__typename", "CheckRun")
            if "startedAt" not in normalized and "started_at" in normalized:
                normalized["startedAt"] = normalized["started_at"]
            if "detailsUrl" not in normalized and "details_url" in normalized:
                normalized["detailsUrl"] = normalized["details_url"]
            # Explicit null fences unproven native checks from name-only grouping.
            normalized["workflowIdentity"] = None
            check_runs.append(normalized)
    actions_checks = [
        check
        for check in check_runs
        if isinstance(check.get("app"), dict)
        and _positive_id(check["app"].get("id"))
        and check["app"].get("id") == 15368
        and check["app"].get("slug") == "github-actions"
    ]
    if actions_checks:
        identities = _actions_workflow_identities(repo, head_sha)
        for check in actions_checks:
            suite = check.get("check_suite")
            suite_id = suite.get("id") if isinstance(suite, dict) else None
            if not _positive_id(suite_id) or not isinstance(check.get("head_sha"), str):
                continue
            if check["head_sha"].casefold() != head_sha.casefold():
                continue
            metadata = identities.get(suite_id)
            if metadata is not None:
                check["workflowIdentity"] = dict(metadata["identity"])
                check["workflowRunId"] = metadata["run_id"]
    status_contexts: list[dict[str, Any]] = []
    for page in _fetch_api_pages(f"repos/{repo}/commits/{head_sha}/status?per_page=100"):
        values = page.get("statuses")
        if not isinstance(values, list) or any(not isinstance(item, dict) for item in values):
            raise RuntimeError("GitHub status inventory has malformed statuses")
        status_contexts.extend(values)
    return {
        "available": True,
        "head_sha": head_sha.lower(),
        "check_runs": check_runs,
        "status_contexts": status_contexts,
    }


def fetch_pr_metadata(repo: str, pr_number: int) -> dict[str, Any]:
    """Read the complete compact PR/CI snapshot used by status and stack policy."""

    parse_repo(repo)
    fields = (
        "number,title,state,headRefName,headRefOid,headRepository,headRepositoryOwner,baseRefName,baseRefOid,"
        "changedFiles,body,statusCheckRollup,mergeable,mergeStateStatus,reviewDecision,isDraft,url,mergedAt"
    )
    timeout, budget = _request_timeout(GH_TIMEOUT_SECONDS)
    try:
        completed = subprocess.run(
            ["gh", "pr", "view", str(pr_number), "--repo", repo, "--json", fields],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.TimeoutExpired as exc:
        if budget is not None:
            try:
                _raise_if_budget_expired(budget)
            except HostedPreflightDeadlineExceeded as expired:
                raise expired from exc
        raise RuntimeError(f"gh pr view timed out after {GH_TIMEOUT_SECONDS} seconds") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() if exc.stderr else "gh pr view failed") from exc
    _raise_if_budget_expired(budget)
    try:
        value = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("gh pr view returned invalid JSON") from exc
    if not isinstance(value, dict):
        raise TypeError("gh pr view returned a non-object payload")
    return value


def fetch_pr_identity(repo: str, pr_number: int) -> dict[str, Any]:
    """Read only the current identity fields consumed by a live snapshot."""

    owner, name = parse_repo(repo)
    query = """
query($owner:String!, $repo:String!, $number:Int!) {
  repository(owner:$owner, name:$repo) { pullRequest(number:$number) {
    number
    state
    isDraft
    baseRefName
    baseRefOid
    headRefName
    headRefOid
    headRepository { nameWithOwner name owner { login } }
    changedFiles
    mergeable
    mergedAt
  } }
}
""".strip()
    payload = run_gh_query(query, {"owner": owner, "repo": name, "number": pr_number})
    pull_request = _pull_request_from_graphql_payload(payload)
    if type(pull_request.get("number")) is not int or pull_request["number"] != pr_number:
        raise RuntimeError("GitHub response has no matching pull-request identity")
    return pull_request


def fetch_authenticated_user() -> dict[str, Any]:
    """Read the current gh identity under the active Hosted preflight budget."""

    timeout, budget = _request_timeout(GH_TIMEOUT_SECONDS)
    try:
        completed = subprocess.run(
            ["gh", "api", "user"],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.TimeoutExpired as exc:
        if budget is not None:
            try:
                _raise_if_budget_expired(budget)
            except HostedPreflightDeadlineExceeded as expired:
                raise expired from exc
        raise RuntimeError(f"gh api user timed out after {GH_TIMEOUT_SECONDS} seconds") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() if exc.stderr else "gh api user failed") from exc
    _raise_if_budget_expired(budget)
    try:
        value = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("gh api user returned invalid JSON") from exc
    if not isinstance(value, dict):
        raise TypeError("gh api user returned a non-object response")
    return value


def fetch_pr_identity_batch(
    repo: str, pr_numbers: Sequence[int], *, concurrent_chunks: bool = False
) -> dict[int, dict[str, Any] | None]:
    """Fetch bounded live identity/activity summaries for every requested PR.

    Aliased ``pullRequest(number: ...)`` fields keep this query bound to the
    configured stack rather than to a repository listing limit.  The small
    recent comment/review samples are only used to find active Hosted work;
    complete evidence still comes from :func:`fetch_pull_request`.
    """

    owner, name = parse_repo(repo)
    numbers = tuple(pr_numbers)
    if any(isinstance(number, bool) or not isinstance(number, int) or number <= 0 for number in numbers):
        raise ValueError("pull-request identity batch requires positive PR numbers")
    if len(set(numbers)) != len(numbers):
        raise ValueError("pull-request identity batch requires unique PR numbers")
    result: dict[int, dict[str, Any] | None] = {number: None for number in numbers}
    if not numbers:
        return result
    budget = active_hosted_preflight_budget()

    # Keep each request comfortably within GitHub GraphQL's query-cost limit
    # while ensuring every configured number is covered without list truncation.
    chunks = tuple(numbers[offset : offset + 25] for offset in range(0, len(numbers), 25))

    def fetch_chunk(chunk_index: int, chunk: tuple[int, ...]) -> dict[int, dict[str, Any] | None]:
        track_identity_chunk = (
            budget is not None and budget.current_phase == "target_identity_batch" and not concurrent_chunks
        )
        if budget is not None:
            budget.remaining_seconds()
            if track_identity_chunk:
                budget.begin_batch_chunk(chunk_index, len(chunks))
        selections = "\n".join(
            f"pr_{number}: pullRequest(number:{number}) {{ "
            "number state isDraft mergedAt baseRefName baseRefOid headRefName headRefOid mergeable "
            "changedFiles headRepository { nameWithOwner } "
            "comments(last:5) { nodes { databaseId author { login } body createdAt url } } "
            "reviews(last:5) { nodes { databaseId author { login } body state submittedAt url commit { oid } } } "
            "}"
            for number in chunk
        )
        query = f"""
query($owner:String!, $repo:String!) {{
  repository(owner:$owner, name:$repo) {{
    {selections}
  }}
}}
""".strip()
        payload = run_gh_query(query, {"owner": owner, "repo": name})
        if track_identity_chunk:
            budget.set_batch_chunk_stage(chunk_index, "identity_shape_validation")
        errors = payload.get("errors")
        if errors is not None and (not isinstance(errors, list) or errors):
            raise RuntimeError("GitHub GraphQL response contains errors")
        try:
            repository = payload["data"]["repository"]
        except (KeyError, TypeError) as exc:
            raise RuntimeError("GitHub response has no repository identity batch") from exc
        if not isinstance(repository, dict):
            raise TypeError("GitHub response has no repository identity batch")
        chunk_result: dict[int, dict[str, Any] | None] = {number: None for number in chunk}
        for number in chunk:
            item = repository.get(f"pr_{number}")
            if item is None:
                continue
            if not isinstance(item, dict) or item.get("number") != number:
                continue
            required = (
                "state",
                "baseRefName",
                "baseRefOid",
                "headRefName",
                "headRefOid",
                "mergeable",
            )
            if any(not isinstance(item.get(field), str) or not item[field] for field in required):
                continue
            if not isinstance(item.get("isDraft"), bool):
                continue
            for connection in ("comments", "reviews"):
                value = item.get(connection)
                if not isinstance(value, dict) or not isinstance(value.get("nodes"), list):
                    break
            else:
                chunk_result[number] = item
        return chunk_result

    indexed_chunks = tuple(enumerate(chunks, start=1))

    def fetch_bound_chunk(
        indexed_chunk: tuple[int, tuple[int, ...]],
    ) -> tuple[int, dict[int, dict[str, Any] | None]]:
        chunk_index, chunk = indexed_chunk
        if budget is None:
            return chunk_index, fetch_chunk(chunk_index, chunk)
        with bind_hosted_preflight_budget(budget):
            return chunk_index, fetch_chunk(chunk_index, chunk)

    def collect(results: Iterator[tuple[int, dict[int, dict[str, Any] | None]]]) -> None:
        # Consume in configured order even when independent reads finish out of
        # order. Only this caller mutates the assembled result and progress.
        for completed, (chunk_index, chunk_result) in enumerate(results, 1):
            result.update(chunk_result)
            if budget is not None and budget.current_phase == "target_identity_batch":
                budget.set_completed(completed)
            if not concurrent_chunks and budget is not None and budget.current_phase == "target_identity_batch":
                budget.finish_batch_chunk(chunk_index)

    if concurrent_chunks and len(chunks) > 1:
        with ThreadPoolExecutor(max_workers=min(4, len(chunks))) as pool:
            collect(pool.map(fetch_bound_chunk, indexed_chunks))
    else:
        collect(map(fetch_bound_chunk, indexed_chunks))
    return result


def repository_metadata(repo: str) -> dict[str, Any]:
    """Return the repository default branch and immutable branch-tip identity."""

    owner, name = parse_repo(repo)
    query = """
query($owner:String!, $repo:String!) {
  repository(owner:$owner, name:$repo) {
    defaultBranchRef { name target { oid } }
  }
}
""".strip()
    payload = run_gh_query(query, {"owner": owner, "repo": name})
    try:
        branch = payload["data"]["repository"]["defaultBranchRef"]
        ref_name = branch["name"]
        head = branch["target"]["oid"]
    except (KeyError, TypeError) as exc:
        raise RuntimeError("GitHub response has no default branch identity") from exc
    if (
        not isinstance(ref_name, str)
        or not ref_name
        or not isinstance(head, str)
        or not re.fullmatch(r"[0-9a-fA-F]{40}", head)
    ):
        raise RuntimeError("GitHub default branch identity is malformed")
    return {"ref_name": ref_name, "head_sha": head.lower()}


def branch_head(repo: str, ref_name: str) -> str:
    """Resolve one exact same-repository branch head."""

    parse_repo(repo)
    if not ref_name or any(character in ref_name for character in "\r\n"):
        raise ValueError("branch ref must be a non-empty single line")
    timeout, budget = _request_timeout(GH_TIMEOUT_SECONDS)
    try:
        completed = subprocess.run(
            ["gh", "api", f"repos/{repo}/git/ref/heads/{ref_name}", "--jq", ".object.sha"],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        if budget is not None:
            try:
                _raise_if_budget_expired(budget)
            except HostedPreflightDeadlineExceeded as expired:
                raise expired from exc
        raise RuntimeError(f"could not resolve branch {ref_name!r}") from exc
    except (FileNotFoundError, subprocess.CalledProcessError) as exc:
        raise RuntimeError(f"could not resolve branch {ref_name!r}") from exc
    _raise_if_budget_expired(budget)
    head = completed.stdout.strip()
    if not re.fullmatch(r"[0-9a-fA-F]{40}", head):
        raise RuntimeError(f"branch {ref_name!r} did not resolve to an exact commit")
    return head.lower()


def explicit_review_requests(payload: dict[str, Any]) -> list[dict[str, Any]]:
    """Return externally authored review commands with immutable identities."""

    pr = payload["data"]["repository"]["pullRequest"]
    requests: list[dict[str, Any]] = []
    for comment in (pr.get("comments") or {}).get("nodes", []):
        if not isinstance(comment, dict):
            continue
        author = (comment.get("author") or {}).get("login", "")
        command = " ".join((comment.get("body") or "").strip().split()).lower()
        created_at = comment.get("createdAt")
        identity = immutable_database_id(comment)
        if is_coderabbit_login(author) or command not in REVIEW_COMMAND_TYPES or not isinstance(created_at, str):
            continue
        requests.append(
            {
                "id": identity,
                "type": REVIEW_COMMAND_TYPES[command],
                "command": command,
                "created_at": created_at,
                "url": comment.get("url") if isinstance(comment.get("url"), str) else None,
            }
        )
    return requests
