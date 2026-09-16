#!/usr/bin/env python3
"""Build a bounded, read-only local worktree and PR topology report."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

MAX_OPEN_PRS = 1000
MAX_CHAIN_PRS = 50
DEFAULT_BRANCHES = {"develop", "main", "master"}
EXACT_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
REPO = re.compile(r"^[^/\s]+/[^/\s]+$")
PR_FIELDS = (
    "number,headRefName,headRefOid,baseRefName,baseRefOid,changedFiles,"
    "headRepository,headRepositoryOwner,mergeable,mergeStateStatus,title,url,isDraft"
)


class TopologyError(ValueError):
    """A topology input could not be correlated without guessing."""


def run_command(args: list[str], *, cwd: Path) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(args, cwd=cwd, check=False, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise TopologyError(f"command failed: {' '.join(args[:3])}: {exc}") from exc


def command_json(args: list[str], *, cwd: Path, label: str) -> Any:
    completed = run_command(args, cwd=cwd)
    if completed.returncode != 0:
        detail = completed.stderr.strip() or f"exit status {completed.returncode}"
        raise TopologyError(f"{label} failed: {detail}")
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise TopologyError(f"{label} returned malformed JSON") from exc


def repository_root() -> Path:
    completed = run_command(["git", "rev-parse", "--show-toplevel"], cwd=Path.cwd())
    if completed.returncode != 0:
        raise TopologyError("run this reporter inside a Git worktree")
    root = Path(completed.stdout.strip())
    if not root.is_dir():
        raise TopologyError("Git worktree root is not a directory")
    return root


def validate_repo(value: str) -> str:
    if not REPO.fullmatch(value):
        raise TopologyError("repository must be in OWNER/REPO form")
    return value


def resolve_repo(root: Path, requested: str | None) -> str:
    if requested is not None:
        return validate_repo(requested)
    payload = command_json(
        ["gh", "repo", "view", "--json", "nameWithOwner"], cwd=root, label="repository lookup"
    )
    if not isinstance(payload, dict) or not isinstance(payload.get("nameWithOwner"), str):
        raise TopologyError("repository lookup has no nameWithOwner")
    return validate_repo(payload["nameWithOwner"])


def exact_sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or not EXACT_SHA.fullmatch(value):
        raise TopologyError(f"{label} must be an exact 40-character SHA")
    return value.lower()


def nonnegative_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise TopologyError(f"{label} must be a non-negative integer")
    return value


def normalize_pr(raw: Any, *, label: str) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise TopologyError(f"{label} is not an object")
    number = raw.get("number")
    if isinstance(number, bool) or not isinstance(number, int) or number <= 0:
        raise TopologyError(f"{label} has an invalid number")
    result: dict[str, Any] = {"number": number}
    for raw_key, key in (
        ("headRefName", "head_branch"),
        ("baseRefName", "base_branch"),
    ):
        value = raw.get(raw_key)
        if not isinstance(value, str) or not value:
            raise TopologyError(f"{label} has no valid {raw_key}")
        result[key] = value
    result["head_sha"] = exact_sha(raw.get("headRefOid"), f"{label} headRefOid")
    result["base_sha"] = exact_sha(raw.get("baseRefOid"), f"{label} baseRefOid")
    result["changed_files"] = nonnegative_int(raw.get("changedFiles"), f"{label} changedFiles")
    head_repository = raw.get("headRepository")
    if not isinstance(head_repository, dict) or not isinstance(head_repository.get("nameWithOwner"), str):
        raise TopologyError(f"{label} has no exact head repository identity")
    result["head_repository"] = validate_repo(head_repository["nameWithOwner"])
    for raw_key, key in (("mergeable", "mergeable"), ("mergeStateStatus", "merge_state_status")):
        value = raw.get(raw_key)
        if not isinstance(value, str) or not value:
            raise TopologyError(f"{label} has no valid {raw_key}")
        result[key] = value
    result["title"] = raw.get("title") if isinstance(raw.get("title"), str) else ""
    result["url"] = raw.get("url") if isinstance(raw.get("url"), str) else ""
    result["is_draft"] = raw.get("isDraft") if isinstance(raw.get("isDraft"), bool) else None
    return result


def fetch_open_prs(root: Path, repo: str, *, include_renovate: bool) -> list[dict[str, Any]]:
    # gh paginates internally up to --limit; a full bounded response is still ambiguous.
    payload = command_json(
        [
            "gh",
            "pr",
            "list",
            "--repo",
            repo,
            "--state",
            "open",
            "--limit",
            str(MAX_OPEN_PRS),
            "--json",
            PR_FIELDS,
        ],
        cwd=root,
        label="open pull-request inventory",
    )
    if not isinstance(payload, list):
        raise TopologyError("open pull-request inventory is not an array")
    if len(payload) >= MAX_OPEN_PRS:
        raise TopologyError(
            f"open pull-request inventory reached the bounded {MAX_OPEN_PRS}-PR limit; refusing to infer a complete stack"
        )
    prs = [normalize_pr(item, label=f"open PR {index}") for index, item in enumerate(payload, 1)]
    numbers: set[int] = set()
    for pr in prs:
        if pr["number"] in numbers:
            raise TopologyError(f"open pull-request inventory contains duplicate PR #{pr['number']}")
        numbers.add(pr["number"])
    if not include_renovate:
        prs = [pr for pr in prs if not pr["head_branch"].startswith("renovate/")]
    return prs


def fetch_selected_pr(root: Path, repo: str, number: int) -> dict[str, Any]:
    payload = command_json(
        ["gh", "pr", "view", str(number), "--repo", repo, "--json", f"state,{PR_FIELDS}"],
        cwd=root,
        label=f"PR #{number} lookup",
    )
    if not isinstance(payload, dict) or payload.get("state") != "OPEN":
        raise TopologyError(f"PR #{number} is not open")
    return normalize_pr(payload, label=f"PR #{number}")


def parse_worktrees(root: Path) -> list[dict[str, Any]]:
    completed = run_command(["git", "worktree", "list", "--porcelain"], cwd=root)
    if completed.returncode != 0:
        raise TopologyError("could not list Git worktrees")
    records: list[dict[str, Any]] = []
    current: dict[str, Any] = {}

    def emit() -> None:
        nonlocal current
        if not current:
            return
        path = current.get("path")
        head = current.get("head_sha")
        bare = bool(current.get("bare", False))
        if not isinstance(path, str) or not path:
            raise TopologyError("Git worktree inventory has no path")
        if head is None and not bare:
            raise TopologyError(f"Git worktree {path} has no exact HEAD SHA")
        if head is not None and (not isinstance(head, str) or not EXACT_SHA.fullmatch(head)):
            raise TopologyError(f"Git worktree {path} has no exact HEAD SHA")
        branch = current.get("branch")
        if branch is not None and not isinstance(branch, str):
            raise TopologyError(f"Git worktree {path} has an invalid branch")
        record = {
            "path": path,
            "branch": branch,
            "head_sha": head.lower() if head is not None else None,
            "prunable": bool(current.get("prunable", False)),
            "locked": bool(current.get("locked", False)),
            "bare": bare,
        }
        if record["prunable"]:
            record["status"] = "prunable"
        elif record["bare"]:
            record["status"] = "bare"
        elif not Path(path).is_dir():
            record["status"] = "missing"
        else:
            status = run_command(["git", "-C", path, "status", "--porcelain"], cwd=root)
            if status.returncode != 0:
                record["status"] = "unavailable"
            elif status.stdout:
                record["status"] = "dirty"
            else:
                record["status"] = "clean"
        records.append(record)
        current = {}

    for line in completed.stdout.splitlines():
        if not line:
            emit()
        elif line.startswith("worktree "):
            emit()
            current["path"] = line.removeprefix("worktree ")
        elif line.startswith("HEAD "):
            current["head_sha"] = line.removeprefix("HEAD ")
        elif line.startswith("branch "):
            branch = line.removeprefix("branch ")
            current["branch"] = branch.removeprefix("refs/heads/")
        elif line == "detached":
            current["branch"] = None
        elif line.startswith("prunable"):
            current["prunable"] = True
        elif line == "bare":
            current["bare"] = True
        elif line == "locked" or line.startswith("locked "):
            current["locked"] = True
        else:
            raise TopologyError(f"unrecognized Git worktree record: {line!r}")
    emit()
    return records


def parse_local_branches(root: Path) -> list[dict[str, Any]]:
    completed = run_command(
        [
            "git",
            "for-each-ref",
            "--format=%(refname:short)\t%(objectname)\t%(upstream:short)\t%(committerdate:iso8601-strict)",
            "refs/heads",
        ],
        cwd=root,
    )
    if completed.returncode != 0:
        raise TopologyError("could not list local branches")
    branches: list[dict[str, Any]] = []
    for index, line in enumerate(completed.stdout.splitlines(), 1):
        fields = line.split("\t")
        if len(fields) != 4 or not fields[0]:
            raise TopologyError(f"local branch record {index} is malformed")
        branches.append(
            {
                "branch": fields[0],
                "head_sha": exact_sha(fields[1], f"local branch {fields[0]} head"),
                "upstream": fields[2] or None,
                "last_commit_at": fields[3] or None,
            }
        )
    return branches


def ancestor_status(root: Path, first: str, second: str) -> bool | None:
    first_result = run_command(["git", "merge-base", "--is-ancestor", first, second], cwd=root)
    if first_result.returncode == 0:
        return True
    if first_result.returncode != 1:
        return None
    return False


def local_head_evidence(root: Path, pr: dict[str, Any], branches: list[dict[str, Any]]) -> dict[str, Any]:
    matches = [item for item in branches if item["branch"] == pr["head_branch"]]
    if len(matches) > 1:
        raise TopologyError(f"local branch inventory ambiguously matches {pr['head_branch']}")
    if not matches:
        return {"status": "absent", "branch": pr["head_branch"]}
    branch = matches[0]
    local_sha = branch["head_sha"]
    result: dict[str, Any] = {
        "status": "published" if local_sha == pr["head_sha"] else "unknown",
        "branch": branch["branch"],
        "head_sha": local_sha,
        "upstream": branch["upstream"],
        "last_commit_at": branch["last_commit_at"],
    }
    if local_sha == pr["head_sha"]:
        return result
    local_before_remote = ancestor_status(root, local_sha, pr["head_sha"])
    remote_before_local = ancestor_status(root, pr["head_sha"], local_sha)
    if local_before_remote is True:
        result["status"] = "stale"
    elif remote_before_local is True:
        result["status"] = "unpublished"
    elif local_before_remote is False and remote_before_local is False:
        result["status"] = "diverged"
    else:
        result["status"] = "unknown"
    return result


def worktrees_for_pr(pr: dict[str, Any], worktrees: list[dict[str, Any]]) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    for worktree in worktrees:
        if worktree["branch"] == pr["head_branch"] or (
            worktree["branch"] is None
            and worktree["head_sha"] is not None
            and worktree["head_sha"] == pr["head_sha"]
        ):
            selected.append(
                {
                    **worktree,
                    "branch_matches": worktree["branch"] == pr["head_branch"],
                    "head_matches": worktree["head_sha"] == pr["head_sha"],
                }
            )
    return selected


def render_pr(
    pr: dict[str, Any],
    relation: str,
    root: Path,
    branches: list[dict[str, Any]],
    worktrees: list[dict[str, Any]],
    relation_evidence: dict[str, Any] | None = None,
) -> dict[str, Any]:
    result = {
        "relation": relation,
        "number": pr["number"],
        "head": {
            "repository": pr["head_repository"],
            "branch": pr["head_branch"],
            "sha": pr["head_sha"],
        },
        "base": {"branch": pr["base_branch"], "sha": pr["base_sha"]},
        "changed_files": pr["changed_files"],
        "merge": {"mergeable": pr["mergeable"], "state": pr["merge_state_status"]},
        "title": pr["title"],
        "url": pr["url"],
        "is_draft": pr["is_draft"],
        "local_head": local_head_evidence(root, pr, branches),
        "worktrees": worktrees_for_pr(pr, worktrees),
    }
    if relation_evidence is not None:
        result["relation_evidence"] = relation_evidence
    return result


def selected_chain(
    root: Path,
    selected: dict[str, Any],
    open_prs: list[dict[str, Any]],
    branches: list[dict[str, Any]],
    worktrees: list[dict[str, Any]],
    repo: str,
) -> tuple[list[dict[str, Any]], list[str]]:
    if selected["head_repository"].casefold() != repo.casefold():
        raise TopologyError(
            f"selected PR #{selected['number']} head repository {selected['head_repository']} does not match {repo}"
        )
    existing = next((pr for pr in open_prs if pr["number"] == selected["number"]), None)
    if existing is not None and any(
        existing[key] != selected[key]
        for key in ("head_repository", "head_branch", "head_sha", "base_branch", "base_sha")
    ):
        raise TopologyError(f"PR #{selected['number']} changed between selected and inventory reads")
    errors: list[str] = []
    discovered: dict[int, tuple[dict[str, Any], str, dict[str, Any] | None, int]] = {
        selected["number"]: (selected, "selected", None, 0)
    }
    same_repository_prs = [
        pr for pr in open_prs if pr["head_repository"].casefold() == repo.casefold()
    ]
    pending = [(selected, 0)]
    while pending:
        current, current_depth = pending.pop(0)
        candidates = [] if current["base_branch"] in DEFAULT_BRANCHES else [
            pr
            for pr in same_repository_prs
            if pr["number"] != current["number"] and pr["head_branch"] == current["base_branch"]
        ]
        if len(candidates) > 1:
            errors.append(f"base branch {current['base_branch']} matches multiple open PR heads")
        elif len(candidates) == 1:
            base = candidates[0]
            if base["head_sha"] != current["base_sha"]:
                errors.append(
                    f"PR #{current['number']} base {current['base_branch']} SHA does not match PR #{base['number']} head"
                )
                discovered.setdefault(
                    base["number"],
                    (
                        base,
                        "base",
                        {
                            "branch_matches": True,
                            "sha_matches": False,
                            "expected_sha": current["base_sha"],
                            "observed_sha": base["head_sha"],
                        },
                        current_depth + 1,
                    ),
                )
            elif base["number"] not in discovered:
                discovered[base["number"]] = (base, "base", None, current_depth + 1)
                pending.append((base, current_depth + 1))

        child_branch_matches = [
            pr
            for pr in same_repository_prs
            if pr["number"] != current["number"] and pr["base_branch"] == current["head_branch"]
        ]
        for child in child_branch_matches:
            if child["base_sha"] != current["head_sha"]:
                errors.append(
                    f"PR #{child['number']} dependent base SHA does not match PR #{current['number']} head"
                )
                discovered.setdefault(
                    child["number"],
                    (
                        child,
                        "dependent",
                        {
                            "branch_matches": True,
                            "sha_matches": False,
                            "expected_sha": current["head_sha"],
                            "observed_sha": child["base_sha"],
                        },
                        current_depth + 1,
                    ),
                )
                continue
            prior = discovered.get(child["number"])
            if prior is None:
                discovered[child["number"]] = (child, "dependent", None, current_depth + 1)
                pending.append((child, current_depth + 1))
            elif prior[0]["head_branch"] != child["head_branch"] or prior[0]["head_sha"] != child["head_sha"]:
                errors.append(f"PR #{child['number']} has conflicting branch/SHA identities")
        if len(discovered) > MAX_CHAIN_PRS:
            errors.append(f"selected stack exceeds the {MAX_CHAIN_PRS}-PR bound")
            break

    head_branch_groups: dict[str, list[int]] = {}
    for pr, _relation, _evidence, _depth in discovered.values():
        head_branch_groups.setdefault(pr["head_branch"], []).append(pr["number"])
    for branch, numbers in head_branch_groups.items():
        if len(numbers) > 1:
            errors.append(f"selected stack has multiple PR identities for head branch {branch}: {sorted(numbers)}")

    chain = [
        render_pr(pr, relation, root, branches, worktrees, relation_evidence)
        for pr, relation, relation_evidence, _depth in sorted(
            discovered.values(),
            key=lambda item: (
                0 if item[1] == "base" else 1 if item[1] == "selected" else 2,
                -item[3] if item[1] == "base" else item[3],
                item[0]["number"],
            ),
        )
    ]
    return chain, sorted(set(errors))


def emit_selected_text(report: dict[str, Any]) -> None:
    print(f"Repository: {report['repository']}")
    print(f"Selected PR: #{report['selected_pr']} (exact branch/SHA stack)")
    print("RELATION\tPR\tHEAD BRANCH\tHEAD SHA\tBASE BRANCH\tBASE SHA\tFILES\tMERGE\tLOCAL HEAD\tWORKTREES")
    for item in report["chain"]:
        local = item["local_head"]["status"]
        print(
            "\t".join(
                (
                    item["relation"],
                    str(item["number"]),
                    item["head"]["branch"],
                    item["head"]["sha"][:12],
                    item["base"]["branch"],
                    item["base"]["sha"][:12],
                    str(item["changed_files"]),
                    f"{item['merge']['state']}/{item['merge']['mergeable']}",
                    local,
                    str(len(item["worktrees"])),
                )
            )
        )
    if report["errors"]:
        for error in report["errors"]:
            print(f"error: {error}", file=sys.stderr)


def emit_inventory_text(report: dict[str, Any]) -> None:
    print(f"Repository: {report['repository']}")
    print()
    print("Worktrees")
    print("PATH\tBRANCH\tHEAD\tSTATUS")
    for worktree in report["worktrees"]:
        if worktree["bare"]:
            branch = "(bare)"
        else:
            branch = worktree["branch"] or "(detached)"
        status = worktree["status"]
        if worktree["locked"] and status not in {"prunable", "bare"}:
            status = f"{status} (locked)"
        head = worktree["head_sha"] or "-"
        print(f"{worktree['path']}\t{branch}\t{head}\t{status}")

    print()
    print("Local branches")
    print("BRANCH\tUPSTREAM\tHEAD\tLAST_COMMIT")
    for branch in sorted(report["local_branches"], key=lambda item: item["branch"]):
        print(
            f"{branch['branch']}\t{branch['upstream'] or '-'}\t"
            f"{branch['head_sha'][:12]}\t{branch['last_commit_at'] or '-'}"
        )

    print()
    print("Open pull requests")
    print("NUMBER\tHEAD\tBASE\tMERGE_STATE\tTITLE\tURL")
    for pr in report["pull_requests"]:
        print(
            f"{pr['number']}\t{pr['head']['branch']}\t{pr['base']['branch']}\t"
            f"{pr['merge']['state']}\t{pr['title']}\t{pr['url']}"
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Report exact local worktree and PR topology.")
    parser.add_argument("--repo", help="GitHub repository in OWNER/REPO form")
    parser.add_argument("--include-renovate", action="store_true")
    parser.add_argument("--pr", type=int, help="bound the report to this selected PR's direct stack")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.pr is not None and args.pr <= 0:
        print("error: --pr must be a positive integer", file=sys.stderr)
        return 2
    try:
        root = repository_root()
        repo = resolve_repo(root, args.repo)
        worktrees = parse_worktrees(root)
        branches = parse_local_branches(root)
        open_prs = fetch_open_prs(root, repo, include_renovate=args.include_renovate)
        if args.pr is None:
            report = {
                "schema_version": 1,
                "repository": repo,
                "mode": "inventory",
                "status": "ok",
                "worktrees": worktrees,
                "local_branches": branches,
                "pull_requests": [
                    {
                        "number": pr["number"],
                        "head": {
                            "repository": pr["head_repository"],
                            "branch": pr["head_branch"],
                            "sha": pr["head_sha"],
                        },
                        "base": {"branch": pr["base_branch"], "sha": pr["base_sha"]},
                        "changed_files": pr["changed_files"],
                        "merge": {"mergeable": pr["mergeable"], "state": pr["merge_state_status"]},
                        "title": pr["title"],
                        "url": pr["url"],
                        "is_draft": pr["is_draft"],
                    }
                    for pr in open_prs
                ],
            }
        else:
            selected = fetch_selected_pr(root, repo, args.pr)
            chain, errors = selected_chain(root, selected, open_prs, branches, worktrees, repo)
            selected_worktrees = [
                worktree
                for item in chain
                for worktree in item["worktrees"]
            ]
            selected_branches = [
                branch
                for branch in branches
                if any(item["head"]["branch"] == branch["branch"] for item in chain)
            ]
            report = {
                "schema_version": 1,
                "repository": repo,
                "mode": "selected-stack",
                "selected_pr": args.pr,
                "chain": chain,
                "worktrees": selected_worktrees,
                "local_branches": selected_branches,
                "errors": errors,
                "status": "ambiguous" if errors else "ok",
            }
        if args.json:
            print(json.dumps(report, indent=2, sort_keys=True))
        elif args.pr is None:
            emit_inventory_text(report)
        else:
            emit_selected_text(report)
        return 1 if report.get("status") == "ambiguous" else 0
    except TopologyError as exc:
        if args.json:
            print(json.dumps({"schema_version": 1, "status": "ambiguous", "errors": [str(exc)]}, indent=2, sort_keys=True))
        else:
            print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
