#!/usr/bin/env python3
"""Select, provenance-check, and aggregate the two slow-service CI partitions."""

from __future__ import annotations

import argparse
import copy
import json
import re
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
        for class_name in discovered:
            historic_entry = historic_classes.get(class_name)
            if historic_entry is not None:
                partition = int(historic_entry["partition"])
                weight = float(historic_entry["weight_seconds"])
                assignments[class_name] = partition
                global_load[partition] += weight
                if is_test_class(class_name):
                    global_test_count[partition] += 1
                expected_test_classes.add(class_name)
            elif is_test_class(class_name):
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


def resolve_effective_artifacts(
    provenance: dict[str, Any],
    module: str,
    run_id: str,
    source_revision: str,
    current_attempt: str,
) -> dict[str, Any]:
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
        raise ValueError("workflow run provenance is missing")
    if (
        run.get("id") != expected_run_id
        or run.get("head_sha") != source_revision
        or run.get("run_attempt") != expected_attempt
        or run.get("path") != ".github/workflows/ci.yml"
        or run.get("name") != "CI — Validation"
    ):
        raise ValueError("workflow run provenance does not match the current source")

    attempts = provenance.get("attempts")
    if not isinstance(attempts, list) or len(attempts) != expected_attempt:
        raise ValueError("workflow attempt provenance is incomplete or ambiguous")
    attempt_map: dict[int, dict[str, Any]] = {}
    expected_names = {
        partition: f"Build and Test ({module} / {partition})"
        for partition in (1, 2)
    }
    latest_jobs: dict[int, tuple[int, dict[str, Any]]] = {}

    for snapshot in attempts:
        if not isinstance(snapshot, dict):
            raise ValueError("workflow attempt provenance is malformed")
        attempt_number = positive_integer(snapshot.get("attempt"), "workflow attempt")
        if attempt_number in attempt_map or attempt_number > expected_attempt:
            raise ValueError("workflow attempt provenance is duplicate or out of range")
        attempt_map[attempt_number] = snapshot
        jobs = snapshot.get("jobs")
        total_count = snapshot.get("total_count")
        if not isinstance(jobs, list) or total_count != len(jobs):
            raise ValueError(f"workflow attempt {attempt_number} job pagination is incomplete")
        if isinstance(total_count, bool) or not isinstance(total_count, int) or total_count < 0:
            raise ValueError(f"workflow attempt {attempt_number} job count is invalid")
        ids: set[int] = set()
        matches: dict[int, list[dict[str, Any]]] = {1: [], 2: []}
        for job in jobs:
            if not isinstance(job, dict):
                raise ValueError(f"workflow attempt {attempt_number} contains a malformed job")
            job_id = positive_integer(job.get("id"), "workflow job id")
            if job_id in ids:
                raise ValueError(f"workflow attempt {attempt_number} repeats a job id")
            ids.add(job_id)
            if job.get("run_id") != expected_run_id or job.get("head_sha") != source_revision:
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
        raise ValueError("workflow artifact provenance is missing")
    artifacts = artifact_collection.get("items")
    artifact_total = artifact_collection.get("total_count")
    if not isinstance(artifacts, list) or artifact_total != len(artifacts):
        raise ValueError("workflow artifact pagination is incomplete or ambiguous")
    if isinstance(artifact_total, bool) or not isinstance(artifact_total, int) or artifact_total < 0:
        raise ValueError("workflow artifact count is invalid")

    matching_artifacts: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise ValueError("workflow artifact provenance is malformed")
        artifact_id = positive_integer(artifact.get("id"), "workflow artifact id")
        name = artifact.get("name")
        if not isinstance(name, str):
            raise ValueError("workflow artifact name is missing")
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
        if artifact.get("expired") not in (True, False):
            raise ValueError(f"workflow artifact {name} has an unknown expiration state")
        workflow_run = artifact.get("workflow_run")
        if (
            not isinstance(workflow_run, dict)
            or workflow_run.get("id") != expected_run_id
            or workflow_run.get("head_sha") != source_revision
        ):
            raise ValueError(f"workflow artifact {name} has wrong-run or wrong-source API provenance")

    selected: dict[str, Any] = {}
    selected_ids: set[int] = set()
    for partition in (1, 2):
        latest = latest_jobs.get(partition)
        if latest is None:
            raise ValueError(f"partition {partition} has no authoritative job outcome")
        attempt_number, job = latest
        if job.get("status") != "completed" or job.get("conclusion") != "success":
            outcome = job.get("conclusion") or job.get("status") or "unknown"
            raise ValueError(
                f"partition {partition} latest effective job outcome in attempt "
                f"{attempt_number} is {outcome}; older successful artifacts cannot replace it"
            )
        started = parse_timestamp(job.get("started_at"), f"partition {partition} job start")
        completed = parse_timestamp(job.get("completed_at"), f"partition {partition} job completion")
        if completed < started:
            raise ValueError(f"partition {partition} job timestamps are out of order")

        artifact_name = (
            f"slow-service-ci-partition-{expected_run_id}-{source_revision}-"
            f"attempt-{attempt_number}-{module}-partition-{partition}"
        )
        matches = matching_artifacts.get(artifact_name, [])
        if len(matches) != 1:
            raise ValueError(
                f"partition {partition} attempt {attempt_number} has "
                f"{'missing' if not matches else 'ambiguous'} exact-source artifact provenance"
            )
        artifact = matches[0]
        artifact_id = positive_integer(artifact.get("id"), "workflow artifact id")
        if artifact_id in selected_ids:
            raise ValueError("workflow partition artifacts do not have unique IDs")
        selected_ids.add(artifact_id)
        if artifact.get("expired") is not False:
            raise ValueError(f"partition {partition} attempt {attempt_number} artifact is expired")
        if positive_integer(artifact.get("size_in_bytes"), "workflow artifact size") <= 0:
            raise ValueError(f"partition {partition} attempt {attempt_number} artifact is empty")
        created = parse_timestamp(
            artifact.get("created_at"), f"partition {partition} artifact creation time"
        )
        if created < started or created > completed:
            raise ValueError(
                f"partition {partition} artifact creation time does not belong to "
                f"effective job attempt {attempt_number}"
            )
        selected[str(partition)] = {
            "attempt": attempt_number,
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
    module: str,
    run_id: str,
    source_revision: str,
    current_attempt: str,
) -> int:
    document = json.loads(selection_path.read_text(encoding="utf-8"))
    provenance = document.get("provenance")
    if not isinstance(provenance, dict):
        raise ValueError("saved Actions provenance is missing")
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
    for partition in (1, 2):
        artifact_name = resolved["partitions"][str(partition)]["artifact_name"]
        artifact_dir = input_dir / artifact_name
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
    execution_data = []
    for partition in (1, 2):
        artifact_name = resolved["partitions"][str(partition)]["artifact_name"]
        artifact_dir = input_dir / artifact_name
        execution_data.extend(verify_artifact_reports(artifact_dir, manifests[partition]))
    if not execution_data:
        raise ValueError("aggregate has no JaCoCo execution data from either partition")
    print(
        f"Verified complete {module} partition coverage input: "
        f"{len(common['tasks'])} test tasks, "
        f"{sum(len(task['class_universe']) for task in common['tasks'].values())} "
        "discovered class groups, both partition reports, and latest effective "
        "same-run/source job provenance."
    )
    return len(execution_data)


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
            plan = build_plan(
                discovered,
                args.source_revision,
                args.run_attempt,
                args.run_id,
                args.module,
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
            args.module,
            args.run_id,
            args.source_revision,
            args.run_attempt,
        )
        return 0
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"Slow-service CI partition error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
