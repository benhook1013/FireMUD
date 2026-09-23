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
    try:
        completed = subprocess.run(
            ["gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"],
            check=True,
            capture_output=True,
            text=True,
            timeout=GH_TIMEOUT_SECONDS,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, subprocess.CalledProcessError) as exc:
        raise RuntimeError("could not infer GitHub repository identity") from exc
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
    try:
        completed = subprocess.run(
            args,
            check=True,
            capture_output=True,
            text=True,
            timeout=GH_TIMEOUT_SECONDS,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"gh api graphql timed out after {GH_TIMEOUT_SECONDS} seconds") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() if exc.stderr else "gh api graphql failed") from exc
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
    headRefOid
    commits(last:1) { nodes { commit { oid committedDate statusCheckRollup { state } } } }
    reviewThreads(first:100) { nodes { id isResolved isOutdated path line comments(first:20) {
      nodes { id databaseId author { login } body url createdAt updatedAt }
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
        "reviewThreads": "nodes { id isResolved isOutdated path line comments(first:20) { nodes { id databaseId author { login } body url createdAt updatedAt } } }",
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


def fetch_pull_request(repo: str, pr_number: int) -> dict[str, Any]:
    """Fetch a complete PR payload, paginating every review-bearing connection."""

    owner, name = parse_repo(repo)
    payload = run_gh_query(_BASE_QUERY, {"owner": owner, "repo": name, "number": pr_number})
    pr = _pull_request_from_graphql_payload(payload)
    for connection in REVIEW_CONNECTIONS:
        nodes, page = _review_connection(pr, connection, require_page_info=True)
        nodes = list(nodes)
        query = _connection_query(connection)
        while page["hasNextPage"]:
            cursor = page.get("endCursor")
            if not isinstance(cursor, str) or not cursor:
                raise RuntimeError(f"GitHub {connection} pagination has no cursor")
            next_payload = run_gh_query(
                query,
                {"owner": owner, "repo": name, "number": pr_number, "after": cursor},
            )
            next_pr = _pull_request_from_graphql_payload(next_payload)
            page_nodes, page = _review_connection(next_pr, connection, require_page_info=True)
            nodes.extend(page_nodes)
        pr[connection] = {"nodes": nodes}
    return payload


def load_pull_request(input_path: str | Path | None, repo: str, pr_number: int) -> dict[str, Any]:
    if input_path is None:
        return fetch_pull_request(repo, pr_number)
    payload = json.loads(Path(input_path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise TypeError("input payload must be a JSON object")
    pr = _pull_request_from_graphql_payload(payload)
    for connection in REVIEW_CONNECTIONS:
        _review_connection(pr, connection, require_page_info=False)
    return payload


def fetch_api_endpoint(endpoint: str) -> list[dict[str, Any]]:
    """Read a REST endpoint with GitHub CLI pagination and validate its shape."""

    try:
        completed = subprocess.run(
            ["gh", "api", "--method", "GET", "--paginate", "--slurp", endpoint],
            check=True,
            capture_output=True,
            text=True,
            timeout=GH_API_TIMEOUT_SECONDS,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"gh api timed out after {GH_API_TIMEOUT_SECONDS} seconds") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() if exc.stderr else "gh api failed") from exc
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

    try:
        completed = subprocess.run(
            ["gh", "api", "--method", "GET", "--paginate", "--slurp", endpoint],
            check=True,
            capture_output=True,
            text=True,
            timeout=GH_API_TIMEOUT_SECONDS,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"gh api timed out after {GH_API_TIMEOUT_SECONDS} seconds") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() if exc.stderr else "gh api failed") from exc
    try:
        pages = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("gh api returned invalid JSON") from exc
    if not isinstance(pages, list) or any(not isinstance(page, dict) for page in pages):
        raise RuntimeError("gh api returned an unexpected paginated object response")
    return pages


def _fetch_api_object(endpoint: str) -> dict[str, Any]:
    """Read one non-paginated REST object and validate its shape."""

    try:
        completed = subprocess.run(
            ["gh", "api", "--method", "GET", endpoint],
            check=True,
            capture_output=True,
            text=True,
            timeout=GH_API_TIMEOUT_SECONDS,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"gh api timed out after {GH_API_TIMEOUT_SECONDS} seconds") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() if exc.stderr else "gh api failed") from exc
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
            suite = normalized.get("check_suite")
            if isinstance(suite, dict) and isinstance(suite.get("workflow_name"), str):
                normalized.setdefault("workflowName", suite["workflow_name"])
            check_runs.append(normalized)
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
        "number,title,state,headRefName,headRefOid,baseRefName,baseRefOid,"
        "changedFiles,body,statusCheckRollup,mergeable,mergeStateStatus,reviewDecision,isDraft,url,mergedAt"
    )
    try:
        completed = subprocess.run(
            ["gh", "pr", "view", str(pr_number), "--repo", repo, "--json", fields],
            check=True,
            capture_output=True,
            text=True,
            timeout=GH_TIMEOUT_SECONDS,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"gh pr view timed out after {GH_TIMEOUT_SECONDS} seconds") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() if exc.stderr else "gh pr view failed") from exc
    try:
        value = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("gh pr view returned invalid JSON") from exc
    if not isinstance(value, dict):
        raise TypeError("gh pr view returned a non-object payload")
    return value


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
    try:
        completed = subprocess.run(
            ["gh", "api", f"repos/{repo}/git/ref/heads/{ref_name}", "--jq", ".object.sha"],
            check=True,
            capture_output=True,
            text=True,
            timeout=GH_TIMEOUT_SECONDS,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, subprocess.CalledProcessError) as exc:
        raise RuntimeError(f"could not resolve branch {ref_name!r}") from exc
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
