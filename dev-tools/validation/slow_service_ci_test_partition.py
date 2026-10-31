#!/usr/bin/env python3
"""Select, provenance-check, and aggregate the two slow-service CI partitions."""

from __future__ import annotations

import argparse
import copy
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any

PLAN_PATH = Path(__file__).with_name("slow_service_ci_test_partition_plan.json")
SERVICES = ("account-service", "game-session-service")
PARTITION_COUNT = 2
TEST_CLASS_SUFFIX = re.compile(r"(?:^Test.*|.*(?:Test|Tests|TestCase))$")
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
NON_RUNNABLE_TEST_TYPE_FLAGS = 0x0200 | 0x2000
ABSTRACT_TYPE_FLAG = 0x0400
ARTIFACT_PATTERN = re.compile(
    r"^slow-service-ci-partition-(?P<run_id>[1-9][0-9]*)-"
    r"(?P<source_revision>[0-9a-f]{40})-attempt-(?P<attempt>[1-9][0-9]*)-"
    r"(?P<module>[a-z0-9]+(?:-[a-z0-9]+)*)-partition-(?P<partition>[12])$"
)


def load_historical_plan(module: str, path: Path = PLAN_PATH) -> dict[str, Any]:
    plan = json.loads(path.read_text(encoding="utf-8"))
    if plan.get("schema_version") != 1 or plan.get("partition_count") != PARTITION_COUNT:
        raise ValueError(f"unsupported slow-service partition plan: {path}")
    if module not in SERVICES or module not in plan.get("services", {}):
        raise ValueError(f"unsupported slow-service module: {module}")
    return plan["services"][module]


def is_test_class(class_name: str) -> bool:
    return bool(TEST_CLASS_SUFFIX.fullmatch(class_name.rsplit(".", 1)[-1]))


def class_names_from_directories(directories: list[Path]) -> set[str]:
    class_names: set[str] = set()
    for directory in directories:
        if not directory.exists():
            continue
        if not directory.is_dir():
            raise ValueError(f"test class path is not a directory: {directory}")
        for class_file in directory.rglob("*.class"):
            relative = class_file.relative_to(directory).with_suffix("")
            if relative.name in {"module-info", "package-info"}:
                continue
            if "$" in relative.name:
                relative = relative.with_name(relative.name.split("$", 1)[0])
            class_names.add(".".join(relative.parts))
    return class_names


def read_class_access_flags(class_file: Path) -> int:
    data = class_file.read_bytes()
    offset = 0

    def take(size: int) -> bytes:
        nonlocal offset
        end = offset + size
        if end > len(data):
            raise ValueError(f"truncated compiled class metadata: {class_file}")
        value = data[offset:end]
        offset = end
        return value

    def read_u1() -> int:
        return take(1)[0]

    def read_u2() -> int:
        return int.from_bytes(take(2), "big")

    if take(4) != b"\xca\xfe\xba\xbe":
        raise ValueError(f"invalid compiled class magic: {class_file}")
    take(4)  # minor and major version
    constant_pool_count = read_u2()
    if constant_pool_count < 1:
        raise ValueError(f"invalid compiled class constant pool: {class_file}")

    index = 1
    while index < constant_pool_count:
        tag = read_u1()
        if tag == 1:  # CONSTANT_Utf8
            take(read_u2())
        elif tag in (3, 4):  # CONSTANT_Integer, CONSTANT_Float
            take(4)
        elif tag in (5, 6):  # CONSTANT_Long, CONSTANT_Double
            take(8)
            index += 1
        elif tag in (7, 8, 16, 19, 20):  # two-byte constant-pool references
            take(2)
        elif tag in (9, 10, 11, 12, 17, 18):  # four-byte constant-pool references
            take(4)
        elif tag == 15:  # CONSTANT_MethodHandle
            take(3)
        else:
            raise ValueError(
                f"unsupported compiled class constant-pool tag {tag}: {class_file}"
            )
        index += 1

    return read_u2()


def test_class_access_flags_from_directories(
    directories: list[Path], test_classes: set[str]
) -> dict[str, dict[str, int]]:
    access_flags: dict[str, dict[str, int]] = defaultdict(dict)
    for directory in directories:
        if not directory.exists():
            continue
        if not directory.is_dir():
            raise ValueError(f"test class path is not a directory: {directory}")
        for class_file in directory.rglob("*.class"):
            relative = class_file.relative_to(directory).with_suffix("")
            if relative.name in {"module-info", "package-info"}:
                continue
            group_relative = relative.with_name(relative.name.split("$", 1)[0])
            group_name = ".".join(group_relative.parts)
            if group_name not in test_classes:
                continue
            flags = read_class_access_flags(class_file)
            binary_name = ".".join(relative.parts)
            group_access_flags = access_flags[group_name]
            existing_flags = group_access_flags.get(binary_name)
            if existing_flags is not None and existing_flags != flags:
                raise ValueError(
                    f"compiled test class metadata conflicts for {binary_name}"
                )
            group_access_flags[binary_name] = flags

    missing = test_classes - set(access_flags)
    if missing:
        raise ValueError(
            "compiled test class metadata is missing: " + ", ".join(sorted(missing))
        )
    missing_outer = {
        class_name
        for class_name, members in access_flags.items()
        if class_name not in members
    }
    if missing_outer:
        raise ValueError(
            "compiled test class metadata is missing: "
            + ", ".join(sorted(missing_outer))
        )
    return dict(access_flags)


def is_runnable_test_class(
    class_name: str, group_access_flags: dict[str, int]
) -> bool:
    if not is_test_class(class_name):
        return False
    outer_flags = group_access_flags.get(class_name)
    if outer_flags is None or outer_flags & NON_RUNNABLE_TEST_TYPE_FLAGS:
        return False
    if not outer_flags & ABSTRACT_TYPE_FLAG:
        return True
    return any(
        binary_name.startswith(f"{class_name}$")
        and not flags & (NON_RUNNABLE_TEST_TYPE_FLAGS | ABSTRACT_TYPE_FLAG)
        for binary_name, flags in group_access_flags.items()
    )


def parse_class_directories(values: list[str]) -> dict[str, list[Path]]:
    result: dict[str, list[Path]] = defaultdict(list)
    for value in values:
        task, separator, directory = value.partition("=")
        if not separator or not task or not directory:
            raise ValueError(f"class directory must use TASK=PATH syntax: {value}")
        result[task].append(Path(directory))
    if not result:
        raise ValueError("at least one --classes-dir TASK=PATH is required")
    return dict(result)


def build_plan(
    discovered_by_task: dict[str, set[str]],
    source_revision: str,
    run_attempt: str,
    run_id: str,
    module: str,
    historical_plan: dict[str, Any] | None = None,
    *,
    class_access_flags_by_task: dict[str, dict[str, dict[str, int]]],
) -> dict[str, Any]:
    if module not in SERVICES:
        raise ValueError(f"unsupported slow-service module: {module}")
    historical = historical_plan or load_historical_plan(module)
    task_plans: dict[str, Any] = {}
    global_load = {1: 0.0, 2: 0.0}
    global_test_count = {1: 0, 2: 0}
    pending_unknown_tests: list[tuple[str, str, float]] = []
    pending_other_classes: list[tuple[str, str]] = []

    for task in sorted(discovered_by_task):
        discovered = sorted(discovered_by_task[task])
        task_history = historical.get("tasks", {}).get(task, {})
        historic_classes = task_history.get("classes", {})
        assignments: dict[str, int] = {}
        expected_test_classes: set[str] = set()
        task_access_flags = class_access_flags_by_task.get(task, {})
        test_candidates = {class_name for class_name in discovered if is_test_class(class_name)}
        if set(task_access_flags) != test_candidates:
            raise ValueError(f"compiled test class metadata is incomplete for {task}")
        for class_name in discovered:
            access_flags = task_access_flags.get(class_name, {})
            runnable_test = is_runnable_test_class(class_name, access_flags)
            historic_entry = historic_classes.get(class_name)
            if historic_entry is not None:
                partition = int(historic_entry["partition"])
                weight = float(historic_entry["weight_seconds"])
                assignments[class_name] = partition
                global_load[partition] += weight
                if runnable_test:
                    global_test_count[partition] += 1
                    expected_test_classes.add(class_name)
            elif runnable_test:
                estimate = float(
                    task_history.get("unknown_class_estimated_weight_seconds", 1.0)
                )
                pending_unknown_tests.append((task, class_name, estimate))
                expected_test_classes.add(class_name)
            else:
                pending_other_classes.append((task, class_name))
        task_plans[task] = {
            "class_universe": discovered,
            "assignments": assignments,
            "expected_test_classes": sorted(expected_test_classes),
        }

    for task, class_name, estimate in sorted(pending_unknown_tests):
        partition = min(
            (1, 2), key=lambda value: (global_load[value], global_test_count[value], value)
        )
        task_plans[task]["assignments"][class_name] = partition
        global_load[partition] += estimate
        global_test_count[partition] += 1

    other_class_count = {1: 0, 2: 0}
    for task, class_name in sorted(pending_other_classes):
        partition = min((1, 2), key=lambda value: (other_class_count[value], value))
        task_plans[task]["assignments"][class_name] = partition
        other_class_count[partition] += 1

    for task, task_plan in task_plans.items():
        universe = set(task_plan["class_universe"])
        assignments = task_plan["assignments"]
        if set(assignments) != universe:
            raise ValueError(f"partition assignment is incomplete for {task}")
        if any(partition not in (1, 2) for partition in assignments.values()):
            raise ValueError(f"partition assignment is invalid for {task}")

    return {
        "schema_version": 1,
        "module": module,
        "source_revision": source_revision,
        "run_id": str(run_id),
        "run_attempt": str(run_attempt),
        "partition_count": PARTITION_COUNT,
        "tasks": task_plans,
    }


def partition_manifest(plan: dict[str, Any], partition: int) -> dict[str, Any]:
    if partition not in (1, 2):
        raise ValueError("partition must be 1 or 2")
    tasks = {}
    for task, task_plan in plan["tasks"].items():
        tasks[task] = {
            **task_plan,
            "selected_classes": sorted(
                class_name
                for class_name, assigned in task_plan["assignments"].items()
                if assigned == partition
            ),
        }
    return {**plan, "partition": partition, "tasks": tasks}


def write_partition(plan: dict[str, Any], partition: int, output_dir: Path) -> Path:
    manifest = partition_manifest(plan, partition)
    partition_dir = output_dir / plan["module"] / f"partition-{partition}"
    partition_dir.mkdir(parents=True, exist_ok=True)
    for task, task_plan in manifest["tasks"].items():
        selected = task_plan["selected_classes"]
        (partition_dir / f"{task}.txt").write_text(
            "".join(f"{class_name}\n" for class_name in selected),
            encoding="utf-8",
        )
    manifest_path = partition_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest_path


def validate_partition_pair(
    first: dict[str, Any], second: dict[str, Any]
) -> dict[str, Any]:
    if first.get("module") not in SERVICES or first.get("module") != second.get("module"):
        raise ValueError("aggregate requires matching supported service partitions")
    if first.get("partition") != 1 or second.get("partition") != 2:
        raise ValueError("aggregate requires partition 1 and partition 2 exactly once")
    if first.get("partition_count") != PARTITION_COUNT or second.get("partition_count") != PARTITION_COUNT:
        raise ValueError("aggregate requires the complete two-partition plan")
    for identity in ("run_id", "source_revision"):
        if first.get(identity) != second.get(identity):
            raise ValueError(f"partition artifacts have different {identity}")
    common_first = copy.deepcopy(
        {key: value for key, value in first.items() if key not in {"partition", "run_attempt"}}
    )
    common_second = copy.deepcopy(
        {key: value for key, value in second.items() if key not in {"partition", "run_attempt"}}
    )
    for manifest in (common_first, common_second):
        for task_plan in manifest["tasks"].values():
            task_plan.pop("selected_classes", None)
    if common_first != common_second:
        raise ValueError("partition artifacts do not share one discovered class plan")

    for task, task_plan in first["tasks"].items():
        second_task = second["tasks"].get(task)
        if second_task is None:
            raise ValueError(f"partition 2 is missing test task {task}")
        universe = set(task_plan["class_universe"])
        assignments = task_plan["assignments"]
        if set(assignments) != universe:
            raise ValueError(f"class assignment is incomplete for {task}")
        first_selected = set(task_plan["selected_classes"])
        second_selected = set(second_task["selected_classes"])
        if first_selected & second_selected:
            raise ValueError(f"test classes overlap between partitions for {task}")
        if first_selected | second_selected != universe:
            raise ValueError(f"test class union is incomplete for {task}")
        if first_selected != {
            name for name, assigned in assignments.items() if assigned == 1
        }:
            raise ValueError(f"partition 1 selector disagrees with its assignment for {task}")
        if second_selected != {
            name for name, assigned in assignments.items() if assigned == 2
        }:
            raise ValueError(f"partition 2 selector disagrees with its assignment for {task}")
    return first


def normalized_report_class(class_name: str) -> str:
    return class_name.split("$", 1)[0]


def junit_classes(artifact_dir: Path, task: str) -> set[str]:
    result: set[str] = set()
    for report in artifact_dir.rglob("*.xml"):
        parts = report.parts
        if "test-results" not in parts:
            continue
        index = parts.index("test-results")
        if index + 1 >= len(parts) or parts[index + 1] != task:
            continue
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as error:
            raise ValueError(f"invalid JUnit XML: {report}: {error}") from error
        for test_case in root.iter("testcase"):
            class_name = test_case.attrib.get("classname")
            if class_name:
                result.add(normalized_report_class(class_name))
    return result


def verify_artifact_reports(
    artifact_dir: Path, manifest: dict[str, Any]
) -> list[Path]:
    execution_data = []
    for task, task_plan in manifest["tasks"].items():
        reported = junit_classes(artifact_dir, task)
        universe = set(task_plan["class_universe"])
        assigned = task_plan["assignments"]
        selected = set(task_plan["selected_classes"])
        expected = set(task_plan["expected_test_classes"]) & selected
        missing = expected - reported
        if missing:
            raise ValueError(
                f"partition {manifest['partition']} JUnit reports miss {task} classes: "
                + ", ".join(sorted(missing))
            )
        unexpected = reported - universe
        if unexpected:
            raise ValueError(
                f"partition {manifest['partition']} JUnit reports undiscovered {task} "
                "classes: "
                + ", ".join(sorted(unexpected))
            )
        misassigned = {
            name for name in reported if name in assigned and assigned[name] != manifest["partition"]
        }
        if misassigned:
            raise ValueError(
                f"partition {manifest['partition']} ran classes assigned elsewhere in "
                f"{task}: "
                + ", ".join(sorted(misassigned))
            )
        if expected or reported:
            exec_file = artifact_dir / "jacoco" / f"{task}.exec"
            if not exec_file.is_file() or exec_file.stat().st_size == 0:
                raise ValueError(
                    f"partition {manifest['partition']} is missing JaCoCo execution data "
                    f"for {task}"
                )
            execution_data.append(exec_file)

    report = artifact_dir / "reports" / "jacoco" / "test" / "jacocoTestReport.xml"
    if not report.is_file() or report.stat().st_size == 0:
        raise ValueError(
            f"partition {manifest['partition']} is missing its partial JaCoCo XML report"
        )
    return execution_data


def positive_integer(value: Any, description: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{description} must be a positive integer")
    return value


def parse_timestamp(value: Any, description: str) -> datetime:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{description} is missing")
    try:
        timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{description} is invalid") from error
    if timestamp.tzinfo is None:
        raise ValueError(f"{description} must include a timezone")
    return timestamp


def earliest_physical_job_attempt(
    attempt_map: dict[int, dict[str, Any]],
    effective_attempt: int,
    partition: int,
    effective_job: dict[str, Any],
) -> int:
    job_identity = (
        effective_job.get("run_id"),
        effective_job.get("head_sha"),
        effective_job.get("name"),
        effective_job.get("started_at"),
        effective_job.get("completed_at"),
    )
    physical_attempts = []
    effective_snapshot_matched = False
    for attempt_number, snapshot in sorted(attempt_map.items()):
        snapshot_jobs = [
            job
            for job in snapshot["jobs"]
            if (
                job.get("run_id"),
                job.get("head_sha"),
                job.get("name"),
                job.get("started_at"),
                job.get("completed_at"),
            )
            == job_identity
        ]
        if len(snapshot_jobs) > 1:
            raise ValueError(
                f"partition {partition} has ambiguous physical execution provenance "
                f"in attempt {attempt_number}"
            )
        if not snapshot_jobs:
            continue
        snapshot_job = snapshot_jobs[0]
        if (
            snapshot_job.get("status") != "completed"
            or snapshot_job.get("conclusion") != "success"
        ):
            raise ValueError(
                f"partition {partition} physical execution outcome changed in "
                f"attempt {attempt_number}"
            )
        physical_attempts.append(attempt_number)
        if attempt_number == effective_attempt:
            effective_snapshot_matched = True
    if not effective_snapshot_matched or not physical_attempts:
        raise ValueError(
            f"partition {partition} has no complete physical execution provenance"
        )
    return min(physical_attempts)


def resolve_effective_artifacts(
    provenance: dict[str, Any],
    module: str,
    run_id: str,
    source_revision: str,
    current_attempt: str,
) -> dict[str, Any]:
    if not isinstance(provenance, dict):
        raise TypeError("workflow provenance must be an object")
    if module not in SERVICES:
        raise ValueError(f"unsupported slow-service module: {module}")
    if not SHA_PATTERN.fullmatch(source_revision):
        raise ValueError("source revision must be a full lowercase Git SHA")
    expected_run_id = int(run_id)
    expected_attempt = int(current_attempt)
    if expected_run_id <= 0 or expected_attempt <= 0:
        raise ValueError("workflow run and attempt must be positive integers")

    run = provenance.get("run")
    if not isinstance(run, dict):
        raise TypeError("workflow run provenance must be an object")
    native_head_sha = run.get("head_sha")
    if not isinstance(native_head_sha, str):
        raise TypeError("workflow native head revision must be a string")
    if not SHA_PATTERN.fullmatch(native_head_sha):
        raise ValueError("workflow native head revision is invalid")
    repository = run.get("repository")
    if not isinstance(repository, str):
        raise TypeError("workflow repository identity must be a string")
    if not repository:
        raise ValueError("workflow repository identity is missing")
    if (
        run.get("id") != expected_run_id
        or run.get("tested_source_sha") != source_revision
        or run.get("run_attempt") != expected_attempt
        or run.get("path") != ".github/workflows/ci.yml"
        or run.get("name") != "CI — Validation"
        or run.get("event_name") != run.get("event")
    ):
        raise ValueError("workflow run provenance does not match the current source")
    event_name = run.get("event_name")
    if event_name == "pull_request":
        pull_request = run.get("pull_request")
        api_pull_requests = run.get("api_pull_requests")
        if not isinstance(pull_request, dict):
            raise TypeError("workflow pull-request identity must be an object")
        if not isinstance(api_pull_requests, list):
            raise TypeError("workflow pull-request associations must be a list")
        number = pull_request.get("number")
        base_sha = pull_request.get("base_sha")
        head_sha = pull_request.get("head_sha")
        if isinstance(number, bool) or not isinstance(number, int):
            raise TypeError("workflow pull-request number must be an integer")
        if not isinstance(base_sha, str) or not isinstance(head_sha, str):
            raise TypeError("workflow pull-request base and head revisions must be strings")
        expected_title = f"CI — Validation pr-{number} base-{base_sha} head-{head_sha}"
        if (
            number <= 0
            or not SHA_PATTERN.fullmatch(base_sha)
            or not SHA_PATTERN.fullmatch(head_sha)
            or head_sha != native_head_sha
            or run.get("display_title") != expected_title
            or len(api_pull_requests) > 1
            or (
                len(api_pull_requests) == 1
                and api_pull_requests[0]
                != {"number": number, "base_sha": base_sha, "head_sha": head_sha}
            )
        ):
            raise ValueError("workflow pull-request/run relationship does not match its event identity")
    elif event_name in {"push", "workflow_dispatch"}:
        if native_head_sha != source_revision or run.get("pull_request") is not None:
            raise ValueError("non-pull-request run must use its native head as the tested source")
    else:
        raise ValueError(f"unsupported workflow event identity: {event_name}")

    attempts = provenance.get("attempts")
    if not isinstance(attempts, list):
        raise TypeError("workflow attempt provenance must be a list")
    if len(attempts) != expected_attempt:
        raise ValueError("workflow attempt provenance is incomplete or ambiguous")
    attempt_map: dict[int, dict[str, Any]] = {}
    expected_names = {
        partition: f"Build and Test ({module} / {partition})"
        for partition in (1, 2)
    }
    latest_jobs: dict[int, tuple[int, dict[str, Any]]] = {}

    for snapshot in attempts:
        if not isinstance(snapshot, dict):
            raise TypeError("workflow attempt provenance entries must be objects")
        attempt_number = positive_integer(snapshot.get("attempt"), "workflow attempt")
        if attempt_number in attempt_map or attempt_number > expected_attempt:
            raise ValueError("workflow attempt provenance is duplicate or out of range")
        attempt_map[attempt_number] = snapshot
        jobs = snapshot.get("jobs")
        total_count = snapshot.get("total_count")
        if not isinstance(jobs, list):
            raise TypeError(f"workflow attempt {attempt_number} jobs must be a list")
        if total_count != len(jobs):
            raise ValueError(f"workflow attempt {attempt_number} job pagination is incomplete")
        if isinstance(total_count, bool) or not isinstance(total_count, int):
            raise TypeError(f"workflow attempt {attempt_number} job count must be an integer")
        if total_count < 0:
            raise ValueError(f"workflow attempt {attempt_number} job count is invalid")
        ids: set[int] = set()
        matches: dict[int, list[dict[str, Any]]] = {1: [], 2: []}
        for job in jobs:
            if not isinstance(job, dict):
                raise TypeError(f"workflow attempt {attempt_number} jobs must be objects")
            job_id = positive_integer(job.get("id"), "workflow job id")
            if job_id in ids:
                raise ValueError(f"workflow attempt {attempt_number} repeats a job id")
            ids.add(job_id)
            if job.get("run_id") != expected_run_id or job.get("head_sha") != native_head_sha:
                raise ValueError(f"workflow attempt {attempt_number} contains wrong-run or wrong-source job provenance")
            name = job.get("name")
            if name in expected_names.values():
                partition = next(key for key, value in expected_names.items() if value == name)
                matches[partition].append(job)
        for partition, rows in matches.items():
            if len(rows) > 1:
                raise ValueError(
                    f"workflow attempt {attempt_number} has ambiguous partition {partition} job provenance"
                )
            if rows:
                previous = latest_jobs.get(partition)
                if previous is None or attempt_number > previous[0]:
                    latest_jobs[partition] = (attempt_number, rows[0])

    if set(attempt_map) != set(range(1, expected_attempt + 1)):
        raise ValueError("workflow attempt provenance is missing an attempt snapshot")

    artifact_collection = provenance.get("artifacts")
    if not isinstance(artifact_collection, dict):
        raise TypeError("workflow artifact provenance must be an object")
    artifacts = artifact_collection.get("items")
    artifact_total = artifact_collection.get("total_count")
    if not isinstance(artifacts, list):
        raise TypeError("workflow artifact entries must be a list")
    if artifact_total != len(artifacts):
        raise ValueError("workflow artifact pagination is incomplete or ambiguous")
    if isinstance(artifact_total, bool) or not isinstance(artifact_total, int):
        raise TypeError("workflow artifact count must be an integer")
    if artifact_total < 0:
        raise ValueError("workflow artifact count is invalid")

    matching_artifacts: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise TypeError("workflow artifact entries must be objects")
        artifact_id = positive_integer(artifact.get("id"), "workflow artifact id")
        name = artifact.get("name")
        if not isinstance(name, str):
            raise TypeError("workflow artifact name must be a string")
        if name.startswith("slow-service-ci-partition-"):
            match = ARTIFACT_PATTERN.fullmatch(name)
            if not match:
                raise ValueError(f"slow-service partition artifact name is malformed: {name}")
            if match.group("module") == module:
                if int(match.group("run_id")) != expected_run_id or match.group("source_revision") != source_revision:
                    raise ValueError(f"partition artifact {name} has wrong-run or wrong-source identity")
                if int(match.group("attempt")) > expected_attempt:
                    raise ValueError(f"partition artifact {name} claims a future attempt")
                matching_artifacts[name].append(artifact)
        expired = artifact.get("expired")
        if not isinstance(expired, bool):
            raise TypeError(f"workflow artifact {name} expiration must be a boolean")
        workflow_run = artifact.get("workflow_run")
        if not isinstance(workflow_run, dict):
            raise TypeError(f"workflow artifact {name} API provenance must be an object")
        if (
            workflow_run.get("id") != expected_run_id
            or workflow_run.get("head_sha") != native_head_sha
        ):
            raise ValueError(f"workflow artifact {name} has wrong-run or wrong-source API provenance")

    selected: dict[str, Any] = {}
    selected_ids: set[int] = set()
    for partition in (1, 2):
        latest = latest_jobs.get(partition)
        if latest is None:
            raise ValueError(f"partition {partition} has no authoritative job outcome")
        effective_attempt, job = latest
        if job.get("status") != "completed" or job.get("conclusion") != "success":
            outcome = job.get("conclusion") or job.get("status") or "unknown"
            raise ValueError(
                f"partition {partition} latest effective job outcome in attempt "
                f"{effective_attempt} is {outcome}; older successful artifacts cannot replace it"
            )
        started = parse_timestamp(job.get("started_at"), f"partition {partition} job start")
        completed = parse_timestamp(job.get("completed_at"), f"partition {partition} job completion")
        if completed < started:
            raise ValueError(f"partition {partition} job timestamps are out of order")
        physical_attempt = earliest_physical_job_attempt(
            attempt_map, effective_attempt, partition, job
        )

        artifact_name = (
            f"slow-service-ci-partition-{expected_run_id}-{source_revision}-"
            f"attempt-{physical_attempt}-{module}-partition-{partition}"
        )
        matches = matching_artifacts.get(artifact_name, [])
        if len(matches) != 1:
            raise ValueError(
                f"partition {partition} physical attempt {physical_attempt} has "
                f"{'missing' if not matches else 'ambiguous'} exact-source artifact provenance"
            )
        artifact = matches[0]
        artifact_id = positive_integer(artifact.get("id"), "workflow artifact id")
        if artifact_id in selected_ids:
            raise ValueError("workflow partition artifacts do not have unique IDs")
        selected_ids.add(artifact_id)
        if artifact.get("expired") is not False:
            raise ValueError(f"partition {partition} physical attempt {physical_attempt} artifact is expired")
        if positive_integer(artifact.get("size_in_bytes"), "workflow artifact size") <= 0:
            raise ValueError(f"partition {partition} physical attempt {physical_attempt} artifact is empty")
        created = parse_timestamp(
            artifact.get("created_at"), f"partition {partition} artifact creation time"
        )
        if created < started or created > completed:
            raise ValueError(
                f"partition {partition} artifact creation time does not belong to "
                f"effective job window in attempt {effective_attempt}"
            )
        selected[str(partition)] = {
            "attempt": physical_attempt,
            "artifact_id": artifact_id,
            "artifact_name": artifact_name,
            "job_id": positive_integer(job.get("id"), "workflow job id"),
            "job_conclusion": job["conclusion"],
            "job_started_at": job["started_at"],
            "job_completed_at": job["completed_at"],
            "artifact_created_at": artifact["created_at"],
        }

    return {
        "module": module,
        "run_id": str(expected_run_id),
        "source_revision": source_revision,
        "current_attempt": str(expected_attempt),
        "partitions": selected,
    }


def selection_document(
    provenance: dict[str, Any],
    module: str,
    run_id: str,
    source_revision: str,
    current_attempt: str,
) -> dict[str, Any]:
    return {
        "provenance": provenance,
        "resolved": resolve_effective_artifacts(
            provenance, module, run_id, source_revision, current_attempt
        ),
    }


def verify_aggregate(
    input_dir: Path,
    selection_path: Path,
    verified_classes_dir: Path,
    module: str,
    run_id: str,
    source_revision: str,
    current_attempt: str,
) -> int:
    document = json.loads(selection_path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise TypeError("saved aggregate selection must be an object")
    provenance = document.get("provenance")
    if not isinstance(provenance, dict):
        raise TypeError("saved Actions provenance must be an object")
    resolved = resolve_effective_artifacts(
        provenance, module, run_id, source_revision, current_attempt
    )
    if document.get("resolved") != resolved:
        raise ValueError("saved artifact selection differs from authoritative Actions provenance")

    if not input_dir.is_dir():
        raise ValueError("could not retrieve both effective successful partition artifacts")
    artifacts = sorted(path for path in input_dir.iterdir() if path.is_dir())
    expected_names = {
        resolved["partitions"][str(partition)]["artifact_name"]
        for partition in (1, 2)
    }
    if {path.name for path in artifacts} != expected_names:
        raise ValueError(
            "aggregate requires exactly both latest-effective successful partition artifacts; "
            f"found {[path.name for path in artifacts]}"
        )

    manifests = {}
    artifact_directories = {}
    for partition in (1, 2):
        artifact_name = resolved["partitions"][str(partition)]["artifact_name"]
        artifact_dir = input_dir / artifact_name
        artifact_directories[partition] = artifact_dir
        manifest_path = (
            artifact_dir / "slow-service-ci-partition" / module
            / f"partition-{partition}" / "manifest.json"
        )
        if not manifest_path.is_file():
            raise ValueError(f"partition {partition} selector manifest is missing")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("module") != module:
            raise ValueError(f"partition {partition} is for a different service")
        if manifest.get("run_id") != str(run_id):
            raise ValueError(f"partition {partition} is for a different workflow run")
        if manifest.get("source_revision") != source_revision:
            raise ValueError(f"partition {partition} is for a different source revision")
        if str(manifest.get("run_attempt")) != str(
            resolved["partitions"][str(partition)]["attempt"]
        ):
            raise ValueError(f"partition {partition} is for a different effective job attempt")
        if manifest.get("partition") != partition:
            raise ValueError(f"partition {partition} selector manifest identifies another shard")
        manifests[partition] = manifest

    common = validate_partition_pair(manifests[1], manifests[2])
    measured_classes = verify_identical_main_classes(
        artifact_directories[1], artifact_directories[2]
    )
    execution_data = []
    for partition in (1, 2):
        artifact_name = resolved["partitions"][str(partition)]["artifact_name"]
        artifact_dir = input_dir / artifact_name
        execution_data.extend(verify_artifact_reports(artifact_dir, manifests[partition]))
    if not execution_data:
        raise ValueError("aggregate has no JaCoCo execution data from either partition")
    copy_verified_main_classes(
        artifact_directories[1] / "classes" / "java" / "main",
        measured_classes,
        input_dir,
        verified_classes_dir,
    )
    print(
        f"Verified complete {module} partition coverage input: "
        f"{len(common['tasks'])} test tasks, "
        f"{sum(len(task['class_universe']) for task in common['tasks'].values())} "
        "discovered class groups, identical measured production classes, both "
        "partition reports, and latest effective same-run/source job provenance."
    )
    return len(execution_data)


def main_class_inventory(artifact_dir: Path) -> dict[str, Path]:
    if artifact_dir.is_symlink():
        raise ValueError(f"measured partition artifact is symlinked: {artifact_dir}")
    classes_dir = artifact_dir / "classes" / "java" / "main"
    current = artifact_dir
    for component in ("classes", "java", "main"):
        current = current / component
        if current.is_symlink():
            raise ValueError(f"measured production classes contain a symlink: {current}")
    if not classes_dir.is_dir():
        raise ValueError(f"measured production classes are missing: {classes_dir}")

    return class_file_inventory(classes_dir)


def class_file_inventory(classes_dir: Path) -> dict[str, Path]:
    if classes_dir.is_symlink() or not classes_dir.is_dir():
        raise ValueError(f"measured production classes are missing or symlinked: {classes_dir}")
    inventory = {}
    for path in classes_dir.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"measured production classes contain a symlink: {path}")
        if path.is_dir():
            continue
        if not path.is_file() or path.suffix != ".class":
            raise ValueError(f"measured production output is not a class file: {path}")
        inventory[path.relative_to(classes_dir).as_posix()] = path
    if not inventory:
        raise ValueError(f"measured production classes are empty: {classes_dir}")
    return inventory


def verify_identical_main_classes(first_artifact: Path, second_artifact: Path) -> dict[str, Path]:
    first = main_class_inventory(first_artifact)
    second = main_class_inventory(second_artifact)
    if set(first) != set(second):
        missing = sorted(set(first) - set(second))
        extra = sorted(set(second) - set(first))
        raise ValueError(
            "partition measured production class inventories differ: "
            f"partition 2 missing {missing}, extra {extra}"
        )
    for relative_path in sorted(first):
        if first[relative_path].read_bytes() != second[relative_path].read_bytes():
            raise ValueError(
                f"partition measured production class bytes differ: {relative_path}"
            )
    return first


def copy_verified_main_classes(
    source_directory: Path,
    source_inventory: dict[str, Path],
    input_dir: Path,
    verified_classes_dir: Path,
) -> None:
    if (
        verified_classes_dir.name != "slow-service-ci-verified-classes"
        or verified_classes_dir.parent.resolve() != input_dir.parent.resolve()
    ):
        raise ValueError("verified production classes must use the fixed sibling build path")
    if verified_classes_dir.exists() or verified_classes_dir.is_symlink():
        raise ValueError("verified production class output already exists; refusing to replace it")
    if not verified_classes_dir.parent.is_dir():
        raise ValueError("verified production class output parent is missing")

    shutil.copytree(source_directory, verified_classes_dir)
    copied_inventory = class_file_inventory(verified_classes_dir)
    if set(copied_inventory) != set(source_inventory):
        raise ValueError("verified production class copy changed the class inventory")
    for relative_path, source_file in source_inventory.items():
        copied_file = verified_classes_dir / relative_path
        if copied_file.is_symlink() or not copied_file.is_file():
            raise ValueError(f"verified production class copy is incomplete: {relative_path}")
        if copied_file.read_bytes() != source_file.read_bytes():
            raise ValueError(f"verified production class copy changed bytes: {relative_path}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    plan_parser = subparsers.add_parser("plan", help="write one slow-service shard selector")
    plan_parser.add_argument("--module", choices=SERVICES, required=True)
    plan_parser.add_argument("--partition", type=int, choices=(1, 2), required=True)
    plan_parser.add_argument("--source-revision", required=True)
    plan_parser.add_argument("--run-id", required=True)
    plan_parser.add_argument("--run-attempt", required=True)
    plan_parser.add_argument("--classes-dir", action="append", default=[])
    plan_parser.add_argument("--output-dir", type=Path, required=True)

    select_parser = subparsers.add_parser(
        "select-artifacts", help="resolve the latest successful shard attempts from API provenance"
    )
    select_parser.add_argument("--module", choices=SERVICES, required=True)
    select_parser.add_argument("--run-id", required=True)
    select_parser.add_argument("--source-revision", required=True)
    select_parser.add_argument("--run-attempt", required=True)
    select_parser.add_argument("--provenance-json", type=Path, required=True)
    select_parser.add_argument("--selection-json", type=Path, required=True)
    select_parser.add_argument("--github-output", type=Path, required=True)

    aggregate_parser = subparsers.add_parser(
        "verify-aggregate", help="require complete reports from both effective shard attempts"
    )
    aggregate_parser.add_argument("--module", choices=SERVICES, required=True)
    aggregate_parser.add_argument("--input-dir", type=Path, required=True)
    aggregate_parser.add_argument("--verified-classes-dir", type=Path, required=True)
    aggregate_parser.add_argument("--selection-json", type=Path, required=True)
    aggregate_parser.add_argument("--run-id", required=True)
    aggregate_parser.add_argument("--source-revision", required=True)
    aggregate_parser.add_argument("--run-attempt", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "plan":
            class_dirs = parse_class_directories(args.classes_dir)
            discovered = {
                task: class_names_from_directories(directories)
                for task, directories in class_dirs.items()
            }
            class_access_flags = {}
            for task, class_names in discovered.items():
                test_classes = {
                    class_name for class_name in class_names if is_test_class(class_name)
                }
                class_access_flags[task] = test_class_access_flags_from_directories(
                    class_dirs[task], test_classes
                )
            plan = build_plan(
                discovered,
                args.source_revision,
                args.run_attempt,
                args.run_id,
                args.module,
                class_access_flags_by_task=class_access_flags,
            )
            manifest_path = write_partition(plan, args.partition, args.output_dir)
            print(f"Wrote {args.module} partition {args.partition} selector: {manifest_path}")
            return 0
        if args.command == "select-artifacts":
            provenance = json.loads(args.provenance_json.read_text(encoding="utf-8"))
            document = selection_document(
                provenance,
                args.module,
                args.run_id,
                args.source_revision,
                args.run_attempt,
            )
            args.selection_json.parent.mkdir(parents=True, exist_ok=True)
            args.selection_json.write_text(
                json.dumps(document, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            artifact_ids = ",".join(
                str(document["resolved"]["partitions"][str(partition)]["artifact_id"])
                for partition in (1, 2)
            )
            with args.github_output.open("a", encoding="utf-8") as output:
                output.write(f"artifact_ids={artifact_ids}\n")
            print(f"Selected both successful {args.module} partition artifact IDs: {artifact_ids}")
            return 0
        verify_aggregate(
            args.input_dir,
            args.selection_json,
            args.verified_classes_dir,
            args.module,
            args.run_id,
            args.source_revision,
            args.run_attempt,
        )
        return 0
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as error:
        print(f"Slow-service CI partition error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
