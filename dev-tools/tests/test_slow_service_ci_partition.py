import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = ROOT / "dev-tools/validation/slow_service_ci_test_partition.py"
SPEC = importlib.util.spec_from_file_location("slow_service_ci_test_partition", SCRIPT_PATH)
PARTITION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PARTITION)

SOURCE_SHA = "a" * 40
PR_HEAD_SHA = "b" * 40
PR_BASE_SHA = "c" * 40
RUN_ID = "70"
START = "2026-10-30T01:00:00Z"
MIDDLE = "2026-10-30T02:00:00Z"
END = "2026-10-30T03:00:00Z"


def make_plan(module="account-service", run_attempt="2"):
    return {
        "schema_version": 1,
        "module": module,
        "source_revision": SOURCE_SHA,
        "run_id": RUN_ID,
        "run_attempt": str(run_attempt),
        "partition_count": 2,
        "tasks": {
            "test": {
                "class_universe": [
                    "net.example.KnownOneTest",
                    "net.example.KnownTwoTest",
                ],
                "assignments": {
                    "net.example.KnownOneTest": 1,
                    "net.example.KnownTwoTest": 2,
                },
                "expected_test_classes": [
                    "net.example.KnownOneTest",
                    "net.example.KnownTwoTest",
                ],
            }
        },
    }


def job(module, partition, attempt, conclusion="success", start=START, finish=MIDDLE, run_id=70, source=SOURCE_SHA):
    return {
        "id": attempt * 100 + partition,
        "run_id": run_id,
        "head_sha": source,
        "name": f"Build and Test ({module} / {partition})",
        "status": "completed",
        "conclusion": conclusion,
        "started_at": start,
        "completed_at": finish,
    }


def artifact(
    module,
    partition,
    attempt,
    artifact_id,
    created=MIDDLE,
    run_id=70,
    source=SOURCE_SHA,
    native_head=None,
):
    name = (
        f"slow-service-ci-partition-{run_id}-{source}-attempt-{attempt}-"
        f"{module}-partition-{partition}"
    )
    return {
        "id": artifact_id,
        "name": name,
        "expired": False,
        "size_in_bytes": 1024,
        "created_at": created,
        "workflow_run": {"id": run_id, "head_sha": native_head or source},
    }


def make_provenance(
    module="account-service",
    *,
    native_head=SOURCE_SHA,
    tested_source=SOURCE_SHA,
    event_name="pull_request",
    api_pull_requests=None,
):
    if event_name == "pull_request":
        pull_request = {
            "number": 42,
            "base_sha": PR_BASE_SHA,
            "head_sha": native_head,
        }
        display_title = (
            f"CI — Validation pr-42 base-{PR_BASE_SHA} head-{native_head}"
        )
        associations = [] if api_pull_requests is None else api_pull_requests
    else:
        pull_request = None
        display_title = "CI — Validation develop"
        associations = []
    return {
        "run": {
            "id": 70,
            "head_sha": native_head,
            "tested_source_sha": tested_source,
            "run_attempt": 2,
            "path": ".github/workflows/ci.yml",
            "name": "CI — Validation",
            "event": event_name,
            "event_name": event_name,
            "pull_request": pull_request,
            "api_pull_requests": associations,
            "display_title": display_title,
            "repository": "example/firemud",
        },
        "attempts": [
            {
                "attempt": 1,
                "total_count": 2,
                "jobs": [
                    job(module, 1, 1, source=native_head),
                    job(module, 2, 1, source=native_head),
                ],
            },
            {
                "attempt": 2,
                "total_count": 1,
                "jobs": [
                    job(module, 2, 2, start=MIDDLE, finish=END, source=native_head)
                ],
            },
        ],
        "artifacts": {
            "total_count": 3,
            "items": [
                artifact(module, 1, 1, 701, source=tested_source, native_head=native_head),
                artifact(module, 2, 1, 702, source=tested_source, native_head=native_head),
                artifact(
                    module, 2, 2, 703, created=END,
                    source=tested_source, native_head=native_head,
                ),
            ],
        },
    }


def write_artifact(root, plan, partition, attempt):
    name = (
        f"slow-service-ci-partition-{RUN_ID}-{SOURCE_SHA}-attempt-{attempt}-"
        f"{plan['module']}-partition-{partition}"
    )
    destination = root / name
    partition_dir = (
        destination
        / "slow-service-ci-partition"
        / plan["module"]
        / f"partition-{partition}"
    )
    partition_dir.mkdir(parents=True)
    manifest = PARTITION.partition_manifest({**plan, "run_attempt": str(attempt)}, partition)
    (partition_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    (partition_dir / "test.txt").write_text(
        "".join(f"{item}\n" for item in manifest["tasks"]["test"]["selected_classes"]),
        encoding="utf-8",
    )
    (destination / "jacoco").mkdir()
    (destination / "jacoco/test.exec").write_bytes(b"execution data")
    report = destination / "reports/jacoco/test/jacocoTestReport.xml"
    report.parent.mkdir(parents=True)
    report.write_text("<report />\n", encoding="utf-8")
    class_name = manifest["tasks"]["test"]["selected_classes"][0]
    junit = destination / "test-results/test/TEST-results.xml"
    junit.parent.mkdir(parents=True)
    junit.write_text(
        f'<testsuite><testcase classname="{class_name}" name="runs" /></testsuite>\n',
        encoding="utf-8",
    )
    return destination


class SlowServiceCiPartitionTests(unittest.TestCase):
    def test_historical_assignments_and_new_classes_form_complete_disjoint_plans_for_both_services(self):
        for module, task in (
            ("account-service", "integrationTest"),
            ("game-session-service", "crossServiceTest"),
        ):
            with self.subTest(module=module):
                historical = PARTITION.load_historical_plan(module)
                known_class, known_entry = next(
                    iter(historical["tasks"][task]["classes"].items())
                )
                discovered = {
                    task: {
                        known_class,
                        "net.example.NewDiscoveredTest",
                        "net.example.NewFixtureHelper",
                    }
                }
                plan = PARTITION.build_plan(
                    discovered, SOURCE_SHA, "1", RUN_ID, module, historical
                )
                first = PARTITION.partition_manifest(plan, 1)
                second = PARTITION.partition_manifest(plan, 2)
                PARTITION.validate_partition_pair(first, second)
                self.assertEqual(
                    first["tasks"][task]["assignments"][known_class],
                    known_entry["partition"],
                )
                self.assertEqual(
                    set(first["tasks"][task]["selected_classes"])
                    | set(second["tasks"][task]["selected_classes"]),
                    discovered[task],
                )
                self.assertFalse(
                    set(first["tasks"][task]["selected_classes"])
                    & set(second["tasks"][task]["selected_classes"])
                )

    def test_nested_compiled_classes_stay_with_their_outer_class(self):
        with tempfile.TemporaryDirectory() as temporary:
            classes = Path(temporary) / "classes"
            package = classes / "net/example"
            package.mkdir(parents=True)
            (package / "NewServiceTest.class").write_bytes(b"outer")
            (package / "NewServiceTest$Nested.class").write_bytes(b"nested")
            (package / "FixtureHelper.class").write_bytes(b"helper")
            discovered = PARTITION.class_names_from_directories([classes])
        self.assertEqual(
            discovered,
            {"net.example.NewServiceTest", "net.example.FixtureHelper"},
        )

    def test_partial_retry_selects_older_success_and_newer_success_by_exact_effective_provenance(self):
        module = "account-service"
        provenance = make_provenance(module)
        resolved = PARTITION.resolve_effective_artifacts(
            provenance, module, RUN_ID, SOURCE_SHA, "2"
        )
        self.assertEqual(resolved["partitions"]["1"]["attempt"], 1)
        self.assertEqual(resolved["partitions"]["2"]["attempt"], 2)
        self.assertEqual(resolved["partitions"]["1"]["artifact_id"], 701)
        self.assertEqual(resolved["partitions"]["2"]["artifact_id"], 703)
        reordered = copy.deepcopy(provenance)
        reordered["attempts"].reverse()
        self.assertEqual(
            PARTITION.resolve_effective_artifacts(
                reordered, module, RUN_ID, SOURCE_SHA, "2"
            ),
            resolved,
            "latest effective outcome is ordered by attempt number, not response order",
        )

        plan = make_plan(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_artifact(root, plan, 1, 1)
            write_artifact(root, plan, 2, 2)
            selection = PARTITION.selection_document(
                provenance, module, RUN_ID, SOURCE_SHA, "2"
            )
            selection_path = root / "selection.json"
            selection_path.write_text(json.dumps(selection), encoding="utf-8")
            self.assertEqual(
                PARTITION.verify_aggregate(
                    root, selection_path, module, RUN_ID, SOURCE_SHA, "2"
                ),
                2,
            )

    def test_pull_request_merge_source_is_distinct_from_native_api_head(self):
        module = "game-session-service"
        matching_api_association = {
            "number": 42,
            "base_sha": PR_BASE_SHA,
            "head_sha": PR_HEAD_SHA,
        }
        provenance = make_provenance(
            module,
            native_head=PR_HEAD_SHA,
            tested_source=SOURCE_SHA,
            api_pull_requests=[matching_api_association],
        )
        resolved = PARTITION.resolve_effective_artifacts(
            provenance, module, RUN_ID, SOURCE_SHA, "2"
        )
        self.assertEqual(resolved["partitions"]["1"]["attempt"], 1)
        self.assertEqual(
            resolved["partitions"]["1"]["artifact_name"],
            f"slow-service-ci-partition-{RUN_ID}-{SOURCE_SHA}-attempt-1-{module}-partition-1",
        )

        # Empty API associations remain valid when the trusted PR event and
        # canonical run title bind the exact PR/base/head tuple.
        empty_associations = make_provenance(
            module, native_head=PR_HEAD_SHA, tested_source=SOURCE_SHA
        )
        self.assertEqual(
            PARTITION.resolve_effective_artifacts(
                empty_associations, module, RUN_ID, SOURCE_SHA, "2"
            ),
            resolved,
        )

        wrong_identity_cases = []
        wrong_association = copy.deepcopy(provenance)
        wrong_association["run"]["api_pull_requests"][0]["number"] = 43
        wrong_identity_cases.append(wrong_association)
        wrong_title = copy.deepcopy(provenance)
        wrong_title["run"]["display_title"] = wrong_title["run"]["display_title"].replace(
            "pr-42", "pr-43"
        )
        wrong_identity_cases.append(wrong_title)
        wrong_job_head = copy.deepcopy(provenance)
        wrong_job_head["attempts"][0]["jobs"][0]["head_sha"] = SOURCE_SHA
        wrong_identity_cases.append(wrong_job_head)
        wrong_artifact_head = copy.deepcopy(provenance)
        wrong_artifact_head["artifacts"]["items"][0]["workflow_run"]["head_sha"] = SOURCE_SHA
        wrong_identity_cases.append(wrong_artifact_head)
        wrong_tested_source = copy.deepcopy(provenance)
        wrong_tested_source["run"]["tested_source_sha"] = PR_HEAD_SHA
        wrong_identity_cases.append(wrong_tested_source)
        for invalid in wrong_identity_cases:
            with self.subTest(provenance=invalid):
                with self.assertRaises(ValueError):
                    PARTITION.resolve_effective_artifacts(
                        invalid, module, RUN_ID, SOURCE_SHA, "2"
                    )

    def test_push_and_dispatch_keep_one_native_tested_revision(self):
        for event_name in ("push", "workflow_dispatch"):
            with self.subTest(event_name=event_name):
                provenance = make_provenance(event_name=event_name)
                self.assertEqual(
                    PARTITION.resolve_effective_artifacts(
                        provenance, "account-service", RUN_ID, SOURCE_SHA, "2"
                    )["source_revision"],
                    SOURCE_SHA,
                )
                wrong_head = make_provenance(
                    native_head=PR_HEAD_SHA,
                    tested_source=SOURCE_SHA,
                    event_name=event_name,
                )
                with self.assertRaisesRegex(ValueError, "non-pull-request"):
                    PARTITION.resolve_effective_artifacts(
                        wrong_head, "account-service", RUN_ID, SOURCE_SHA, "2"
                    )

    def test_wrong_run_or_source_provenance_is_rejected(self):
        module = "game-session-service"
        for change in (
            lambda data: data["run"].update(head_sha="b" * 40),
            lambda data: data["attempts"][0]["jobs"][0].update(run_id=71),
            lambda data: data["artifacts"]["items"][0]["workflow_run"].update(head_sha="b" * 40),
        ):
            provenance = make_provenance(module)
            change(provenance)
            with self.subTest(provenance=provenance):
                with self.assertRaisesRegex(ValueError, "provenance|identity|wrong-run|wrong-source"):
                    PARTITION.resolve_effective_artifacts(
                        provenance, module, RUN_ID, SOURCE_SHA, "2"
                    )

    def test_missing_or_ambiguous_job_and_artifact_provenance_holds(self):
        module = "account-service"
        missing_attempt = make_provenance(module)
        missing_attempt["attempts"].pop()
        missing_job = make_provenance(module)
        missing_job["attempts"][0]["jobs"] = [
            row for row in missing_job["attempts"][0]["jobs"] if row["name"].endswith("/ 2)")
        ]
        duplicate_job = make_provenance(module)
        duplicate_job["attempts"][0]["jobs"].append(
            copy.deepcopy(duplicate_job["attempts"][0]["jobs"][0])
        )
        duplicate_artifact = make_provenance(module)
        duplicate_artifact["artifacts"]["items"].append(
            copy.deepcopy(duplicate_artifact["artifacts"]["items"][0])
        )
        incomplete_artifact_page = make_provenance(module)
        incomplete_artifact_page["artifacts"]["total_count"] += 1
        incomplete_page = make_provenance(module)
        incomplete_page["attempts"][0]["total_count"] += 1

        for provenance in (
            missing_attempt,
            missing_job,
            duplicate_job,
            duplicate_artifact,
            incomplete_artifact_page,
            incomplete_page,
        ):
            with self.subTest(provenance=provenance):
                with self.assertRaises(ValueError):
                    PARTITION.resolve_effective_artifacts(
                        provenance, module, RUN_ID, SOURCE_SHA, "2"
                    )

    def test_failed_newer_partition_never_falls_back_to_older_success(self):
        module = "game-session-service"
        provenance = make_provenance(module)
        provenance["attempts"][1]["jobs"][0]["name"] = (
            f"Build and Test ({module} / 1)"
        )
        provenance["attempts"][1]["jobs"][0]["id"] = 201
        provenance["attempts"][1]["jobs"][0]["conclusion"] = "failure"
        provenance["artifacts"]["items"].append(artifact(module, 1, 2, 704, created=END))
        provenance["artifacts"]["total_count"] += 1
        with self.assertRaisesRegex(ValueError, "older successful artifacts cannot replace it"):
            PARTITION.resolve_effective_artifacts(
                provenance, module, RUN_ID, SOURCE_SHA, "2"
            )

    def test_aggregate_rejects_missing_junit_for_a_selected_test_class(self):
        module = "account-service"
        plan = make_plan(module)
        provenance = make_provenance(module)
        selection = PARTITION.selection_document(
            provenance, module, RUN_ID, SOURCE_SHA, "2"
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_artifact(root, plan, 1, 1)
            second = write_artifact(root, plan, 2, 2)
            selection_path = root / "selection.json"
            selection_path.write_text(json.dumps(selection), encoding="utf-8")
            (second / "test-results/test/TEST-results.xml").unlink()
            with self.assertRaisesRegex(ValueError, "JUnit reports miss"):
                PARTITION.verify_aggregate(
                    root, selection_path, module, RUN_ID, SOURCE_SHA, "2"
                )

    def test_partition_pair_rejects_incomplete_class_union(self):
        plan = make_plan()
        first = PARTITION.partition_manifest(plan, 1)
        second = PARTITION.partition_manifest(plan, 2)
        broken = copy.deepcopy(second)
        broken["tasks"]["test"]["selected_classes"] = []
        with self.assertRaisesRegex(ValueError, "test class union is incomplete"):
            PARTITION.validate_partition_pair(first, broken)


if __name__ == "__main__":
    unittest.main()
