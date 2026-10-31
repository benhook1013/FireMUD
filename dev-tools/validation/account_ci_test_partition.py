#!/usr/bin/env python3
"""Select and aggregate the two Account CI test partitions."""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path
from typing import Any


PLAN_PATH = Path(__file__).with_name("account_ci_test_partition_plan.json")
PARTITION_COUNT = 2
TEST_CLASS_SUFFIX = re.compile(r"(?:^Test.*|.*(?:Test|Tests|TestCase))$")


def load_historical_plan(path: Path = PLAN_PATH) -> dict[str, Any]:
    plan = json.loads(path.read_text(encoding="utf-8"))
    if plan.get("schema_version") != 1 or plan.get("partition_count") != 2:
        raise ValueError(f"unsupported Account partition plan: {path}")
    return plan


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
    historical_plan: dict[str, Any] | None = None,
) -> dict[str, Any]:
    historical = historical_plan or load_historical_plan()
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
        "source_revision": source_revision,
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
    partition_dir = output_dir / f"partition-{partition}"
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
    if first.get("partition") != 1 or second.get("partition") != 2:
        raise ValueError("aggregate requires partition 1 and partition 2 exactly once")
    if first.get("partition_count") != 2 or second.get("partition_count") != 2:
        raise ValueError("aggregate requires the complete two-partition plan")
    if first.get("source_revision") != second.get("source_revision"):
        raise ValueError("partition artifacts have different source revisions")
    if first.get("run_attempt") != second.get("run_attempt"):
        raise ValueError("partition artifacts have different workflow attempts")
    common_first = copy.deepcopy(
        {key: value for key, value in first.items() if key != "partition"}
    )
    common_second = copy.deepcopy(
        {key: value for key, value in second.items() if key != "partition"}
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


def verify_aggregate(
    input_dir: Path, source_revision: str, run_attempt: str
) -> int:
    expected_dirs = {
        f"account-ci-partition-{source_revision}-attempt-{run_attempt}-partition-1": 1,
        f"account-ci-partition-{source_revision}-attempt-{run_attempt}-partition-2": 2,
    }
    artifacts = sorted(path for path in input_dir.iterdir() if path.is_dir())
    if {path.name for path in artifacts} != set(expected_dirs):
        raise ValueError(
            "aggregate requires exactly the two current Account partition artifacts; "
            f"found {[path.name for path in artifacts]}"
        )

    manifests = {}
    for artifact_dir in artifacts:
        partition = expected_dirs[artifact_dir.name]
        manifest_path = (
            artifact_dir
            / "account-ci-partition"
            / f"partition-{partition}"
            / "manifest.json"
        )
        if not manifest_path.is_file():
            raise ValueError(f"partition {partition} selector manifest is missing")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("source_revision") != source_revision:
            raise ValueError(f"partition {partition} is for a different source revision")
        if str(manifest.get("run_attempt")) != str(run_attempt):
            raise ValueError(f"partition {partition} is for a different workflow attempt")
        manifests[partition] = manifest

    common = validate_partition_pair(manifests[1], manifests[2])
    execution_data = []
    for artifact_dir in artifacts:
        partition = expected_dirs[artifact_dir.name]
        execution_data.extend(verify_artifact_reports(artifact_dir, manifests[partition]))
    if not execution_data:
        raise ValueError("aggregate has no JaCoCo execution data from either partition")
    print(
        "Verified complete Account partition coverage input: "
        f"{len(common['tasks'])} test tasks, "
        f"{sum(len(task['class_universe']) for task in common['tasks'].values())} "
        "discovered class groups, and both partition reports."
    )
    return len(execution_data)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    plan_parser = subparsers.add_parser("plan", help="write one Account shard selector")
    plan_parser.add_argument("--partition", type=int, choices=(1, 2), required=True)
    plan_parser.add_argument("--source-revision", required=True)
    plan_parser.add_argument("--run-attempt", required=True)
    plan_parser.add_argument("--classes-dir", action="append", default=[])
    plan_parser.add_argument("--output-dir", type=Path, required=True)

    aggregate_parser = subparsers.add_parser(
        "verify-aggregate", help="require both complete Account shard artifacts"
    )
    aggregate_parser.add_argument("--input-dir", type=Path, required=True)
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
            plan = build_plan(discovered, args.source_revision, args.run_attempt)
            manifest_path = write_partition(plan, args.partition, args.output_dir)
            print(f"Wrote Account partition {args.partition} selector: {manifest_path}")
            return 0
        verify_aggregate(args.input_dir, args.source_revision, args.run_attempt)
        return 0
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"Account CI partition error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
