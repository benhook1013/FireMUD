import contextlib
import copy
import importlib.util
import io
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
        "run_attempt": attempt,
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


def classfile_bytes(internal_name):
    def u2(value):
        return value.to_bytes(2, "big")

    def utf8(value):
        encoded = value.encode("utf-8")
        return b"\x01" + u2(len(encoded)) + encoded

    constant_pool = (
        b"\x07\x00\x02"
        + utf8(internal_name)
        + b"\x07\x00\x04"
        + utf8("java/lang/Object")
    )
    return (
        b"\xca\xfe\xba\xbe"
        + u2(0)
        + u2(61)
        + u2(5)
        + constant_pool
        + u2(0x0021)
        + u2(1)
        + u2(3)
        + u2(0)
        + u2(0)
        + u2(0)
        + u2(0)
    )


MAIN_CLASS_BYTES = {
    "net/example/MeasuredService.class": classfile_bytes("net/example/MeasuredService"),
    "net/example/MeasuredService$Nested.class": classfile_bytes(
        "net/example/MeasuredService$Nested"
    ),
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
                "total_count": 2,
                "jobs": [
                    job(module, 1, 2, start=START, finish=MIDDLE, source=native_head),
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
    classes_dir = destination / "classes/java/main"
    for relative_path, contents in MAIN_CLASS_BYTES.items():
        class_file = classes_dir / relative_path
        class_file.parent.mkdir(parents=True, exist_ok=True)
        class_file.write_bytes(contents)
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


def prepare_aggregate(root, module, provenance):
    input_dir = root / "slow-service-ci-partition-inputs"
    input_dir.mkdir()
    plan = make_plan(module)
    artifacts = {
        1: write_artifact(
            input_dir,
            plan,
            1,
            provenance["resolved"]["partitions"]["1"]["attempt"],
        ),
        2: write_artifact(
            input_dir,
            plan,
            2,
            provenance["resolved"]["partitions"]["2"]["attempt"],
        ),
    }
    selection_path = root / "selection.json"
    selection_path.write_text(json.dumps(provenance), encoding="utf-8")
    return (
        input_dir,
        selection_path,
        root / "slow-service-ci-verified-classes",
        artifacts,
    )


def write_compiled_class(directory, class_name, access_flags):
    internal_name = class_name.replace(".", "/")

    def utf8_constant(value):
        encoded = value.encode("utf-8")
        return b"\x01" + len(encoded).to_bytes(2, "big") + encoded

    constant_pool = b"".join(
        (
            utf8_constant(internal_name),
            b"\x07\x00\x01",
            utf8_constant("java/lang/Object"),
            b"\x07\x00\x03",
        )
    )
    class_file = directory.joinpath(*class_name.split(".")).with_suffix(".class")
    class_file.parent.mkdir(parents=True, exist_ok=True)
    class_file.write_bytes(
        b"\xca\xfe\xba\xbe"
        + b"\x00\x00\x00\x41"
        + b"\x00\x05"
        + constant_pool
        + access_flags.to_bytes(2, "big")
        + b"\x00\x02\x00\x04\x00\x00\x00\x00\x00\x00\x00\x00"
    )
    return class_file


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
                access_flags = {
                    class_name: {class_name: 0x0021}
                    for class_name in discovered[task]
                    if PARTITION.is_test_class(class_name)
                }
                plan = PARTITION.build_plan(
                    discovered,
                    SOURCE_SHA,
                    "1",
                    RUN_ID,
                    module,
                    historical,
                    class_access_flags_by_task={task: access_flags},
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

    def test_nested_runnable_group_survives_abstract_outer_and_nonrunnable_types_stay_excluded(self):
        with tempfile.TemporaryDirectory() as temporary:
            classes = Path(temporary) / "classes"
            marker = "net.firedevops.firemud.gamesession.controller.GameSessionIntegrationTest"
            interface_test = "net.example.InterfaceIntegrationTest"
            real_test = "net.example.RealIntegrationTest"
            abstract_test = "net.example.AbstractIntegrationTest"
            abstract_base = "net.example.AbstractBaseIntegrationTest"
            abstract_nested_only = "net.example.AbstractNestedOnlyIntegrationTest"
            helper = "net.example.FixtureHelper"
            write_compiled_class(classes, marker, 0x2601)
            write_compiled_class(classes, interface_test, 0x0621)
            write_compiled_class(classes, real_test, 0x0021)
            write_compiled_class(classes, f"{real_test}$Nested", 0x0021)
            write_compiled_class(classes, abstract_test, 0x0421)
            write_compiled_class(
                classes, f"{abstract_test}$NestedScenario$WhenConfigured", 0x0021
            )
            write_compiled_class(classes, abstract_base, 0x0421)
            write_compiled_class(classes, abstract_nested_only, 0x0421)
            write_compiled_class(
                classes, f"{abstract_nested_only}$NestedTemplate", 0x0421
            )
            write_compiled_class(classes, helper, 0x0021)

            discovered = PARTITION.class_names_from_directories([classes])
            candidates = {
                class_name for class_name in discovered if PARTITION.is_test_class(class_name)
            }
            access_flags = PARTITION.test_class_access_flags_from_directories(
                [classes], candidates
            )
            self.assertEqual(access_flags[marker][marker], 0x2601)
            self.assertTrue(PARTITION.is_runnable_test_class(real_test, access_flags[real_test]))
            self.assertFalse(
                PARTITION.is_runnable_test_class(marker, access_flags[marker])
            )
            self.assertFalse(
                PARTITION.is_runnable_test_class(
                    interface_test, access_flags[interface_test]
                )
            )
            self.assertTrue(
                PARTITION.is_runnable_test_class(
                    abstract_test, access_flags[abstract_test]
                )
            )
            self.assertFalse(
                PARTITION.is_runnable_test_class(
                    abstract_base, access_flags[abstract_base]
                )
            )
            self.assertFalse(
                PARTITION.is_runnable_test_class(
                    abstract_nested_only, access_flags[abstract_nested_only]
                )
            )

            historical = {"tasks": {"integrationTest": {}}}
            plan = PARTITION.build_plan(
                {"integrationTest": discovered},
                SOURCE_SHA,
                "1",
                RUN_ID,
                "game-session-service",
                historical,
                class_access_flags_by_task={"integrationTest": access_flags},
            )
            task_plan = plan["tasks"]["integrationTest"]
            self.assertEqual(set(task_plan["class_universe"]), discovered)
            self.assertEqual(set(task_plan["assignments"]), discovered)
            self.assertEqual(
                task_plan["expected_test_classes"], [abstract_test, real_test]
            )
            first = PARTITION.partition_manifest(plan, 1)
            second = PARTITION.partition_manifest(plan, 2)
            PARTITION.validate_partition_pair(first, second)

            missing_metadata = dict(access_flags)
            missing_metadata.pop(marker)
            with self.assertRaisesRegex(ValueError, "compiled test class metadata is incomplete"):
                PARTITION.build_plan(
                    {"integrationTest": discovered},
                    SOURCE_SHA,
                    "1",
                    RUN_ID,
                    "game-session-service",
                    historical,
                    class_access_flags_by_task={
                        "integrationTest": missing_metadata
                    },
                )

            runnable_partition = task_plan["assignments"][abstract_test]
            runnable_manifest = PARTITION.partition_manifest(plan, runnable_partition)
            artifact_dir = Path(temporary) / f"partition-{runnable_partition}"
            (artifact_dir / "jacoco").mkdir(parents=True)
            (artifact_dir / "jacoco/integrationTest.exec").write_bytes(b"exec")
            junit = artifact_dir / "test-results/integrationTest/TEST-runnable.xml"
            junit.parent.mkdir(parents=True)
            selected_expected = set(runnable_manifest["tasks"]["integrationTest"]["expected_test_classes"]) & set(
                runnable_manifest["tasks"]["integrationTest"]["selected_classes"]
            )

            def write_reported_groups(reported_groups):
                test_cases = []
                for class_name in sorted(reported_groups):
                    report_name = (
                        f"{abstract_test}$NestedScenario$WhenConfigured"
                        if class_name == abstract_test
                        else class_name
                    )
                    test_cases.append(
                        f'<testcase classname="{report_name}" name="runs" />'
                    )
                junit.write_text(
                    "<testsuite>" + "".join(test_cases) + "</testsuite>\n",
                    encoding="utf-8",
                )

            write_reported_groups(selected_expected)
            report = artifact_dir / "reports/jacoco/test/jacocoTestReport.xml"
            report.parent.mkdir(parents=True)
            report.write_text("<report />\n", encoding="utf-8")
            self.assertEqual(
                PARTITION.verify_artifact_reports(artifact_dir, runnable_manifest),
                [artifact_dir / "jacoco/integrationTest.exec"],
            )
            self.assertIn(abstract_test, selected_expected)
            write_reported_groups(selected_expected - {abstract_test})
            with self.assertRaisesRegex(
                ValueError, f"JUnit reports miss integrationTest classes: {abstract_test}"
            ):
                PARTITION.verify_artifact_reports(artifact_dir, runnable_manifest)

    def test_partial_retry_uses_latest_outcomes_and_physical_execution_attempts(self):
        module = "account-service"
        provenance = make_provenance(module)
        carried_first_partition = provenance["attempts"][1]["jobs"][0]
        original_first_partition = provenance["attempts"][0]["jobs"][0]
        self.assertEqual(carried_first_partition["run_attempt"], 2)
        self.assertNotEqual(carried_first_partition["id"], original_first_partition["id"])
        self.assertEqual(carried_first_partition["started_at"], original_first_partition["started_at"])
        self.assertEqual(carried_first_partition["completed_at"], original_first_partition["completed_at"])
        resolved = PARTITION.resolve_effective_artifacts(
            provenance, module, RUN_ID, SOURCE_SHA, "2"
        )
        self.assertEqual(resolved["partitions"]["1"]["attempt"], 1)
        self.assertEqual(resolved["partitions"]["2"]["attempt"], 2)
        self.assertEqual(resolved["partitions"]["1"]["artifact_id"], 701)
        self.assertEqual(resolved["partitions"]["2"]["artifact_id"], 703)
        self.assertEqual(resolved["partitions"]["1"]["job_id"], carried_first_partition["id"])
        self.assertIn("attempt-1-account-service-partition-1", resolved["partitions"]["1"]["artifact_name"])
        self.assertIn("attempt-2-account-service-partition-2", resolved["partitions"]["2"]["artifact_name"])
        reordered = copy.deepcopy(provenance)
        reordered["attempts"].reverse()
        self.assertEqual(
            PARTITION.resolve_effective_artifacts(
                reordered, module, RUN_ID, SOURCE_SHA, "2"
            ),
            resolved,
            "latest effective outcome is ordered by attempt number, not response order",
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            selection = PARTITION.selection_document(
                provenance, module, RUN_ID, SOURCE_SHA, "2"
            )
            input_dir, selection_path, verified_classes_dir, artifacts = prepare_aggregate(
                root, module, selection
            )
            self.assertEqual(
                PARTITION.verify_aggregate(
                    input_dir,
                    selection_path,
                    verified_classes_dir,
                    module,
                    RUN_ID,
                    SOURCE_SHA,
                    "2",
                ),
                2,
            )
            copied = {
                path.relative_to(verified_classes_dir).as_posix(): path.read_bytes()
                for path in verified_classes_dir.rglob("*.class")
            }
            self.assertEqual(copied, MAIN_CLASS_BYTES)
            self.assertEqual(
                {
                    path.relative_to(artifacts[2] / "classes/java/main").as_posix(): path.read_bytes()
                    for path in (artifacts[2] / "classes/java/main").rglob("*.class")
                },
                copied,
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
            with self.subTest(provenance=invalid), self.assertRaises(ValueError):
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

    def test_malformed_provenance_types_raise_type_error(self):
        malformed_cases = (
            ("top-level provenance", None),
            ("run", lambda data: data.update(run=[])),
            ("native head", lambda data: data["run"].update(head_sha=[])),
            ("attempt collection", lambda data: data.update(attempts={})),
            ("attempt entry", lambda data: data["attempts"].__setitem__(0, [])),
            ("jobs collection", lambda data: data["attempts"][0].update(jobs={})),
            ("job entry", lambda data: data["attempts"][0]["jobs"].__setitem__(0, [])),
            ("artifact collection", lambda data: data.update(artifacts=[])),
            ("artifact entries", lambda data: data["artifacts"].update(items={})),
            ("artifact entry", lambda data: data["artifacts"]["items"].__setitem__(0, [])),
            ("artifact name", lambda data: data["artifacts"]["items"][0].update(name=70)),
            ("artifact expiration", lambda data: data["artifacts"]["items"][0].update(expired=0)),
            (
                "artifact API provenance",
                lambda data: data["artifacts"]["items"][0].update(workflow_run=[]),
            ),
            ("pull-request identity", lambda data: data["run"].update(pull_request=[])),
            ("pull-request associations", lambda data: data["run"].update(api_pull_requests={})),
        )
        for description, make_malformed in malformed_cases:
            with self.subTest(description=description), self.assertRaises(TypeError):
                provenance = [] if make_malformed is None else make_provenance()
                if make_malformed is not None:
                    make_malformed(provenance)
                PARTITION.resolve_effective_artifacts(
                    provenance, "account-service", RUN_ID, SOURCE_SHA, "2"
                )

    def test_cli_reports_malformed_json_and_provenance_without_traceback(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            provenance_path = root / "provenance.json"
            for contents in ("[", "[]"):
                with self.subTest(contents=contents):
                    provenance_path.write_text(contents, encoding="utf-8")
                    stderr = io.StringIO()
                    with contextlib.redirect_stderr(stderr):
                        result = PARTITION.main(
                            [
                                "select-artifacts",
                                "--module", "account-service",
                                "--run-id", RUN_ID,
                                "--source-revision", SOURCE_SHA,
                                "--run-attempt", "2",
                                "--provenance-json", str(provenance_path),
                                "--selection-json", str(root / "selection.json"),
                                "--github-output", str(root / "github-output"),
                            ]
                        )
                    self.assertEqual(result, 1)
                    self.assertTrue(
                        stderr.getvalue().startswith("Slow-service CI partition error: ")
                    )
                    self.assertNotIn("Traceback", stderr.getvalue())

    def test_wrong_run_or_source_provenance_is_rejected(self):
        module = "game-session-service"
        for change in (
            lambda data: data["run"].update(head_sha="b" * 40),
            lambda data: data["attempts"][0]["jobs"][0].update(run_id=71),
            lambda data: data["artifacts"]["items"][0]["workflow_run"].update(head_sha="b" * 40),
        ):
            provenance = make_provenance(module)
            change(provenance)
            with self.subTest(provenance=provenance), self.assertRaisesRegex(
                ValueError, "provenance|identity|wrong-run|wrong-source"
            ):
                PARTITION.resolve_effective_artifacts(
                    provenance, module, RUN_ID, SOURCE_SHA, "2"
                )

    def test_missing_or_ambiguous_job_and_artifact_provenance_holds(self):
        module = "account-service"
        missing_attempt = make_provenance(module)
        missing_attempt["attempts"].pop()
        missing_job = make_provenance(module)
        for snapshot in missing_job["attempts"]:
            snapshot["jobs"] = [
                row for row in snapshot["jobs"] if not row["name"].endswith("/ 2)")
            ]
            snapshot["total_count"] = len(snapshot["jobs"])
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
            with self.subTest(provenance=provenance), self.assertRaises(ValueError):
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

    def test_changed_outcome_for_same_physical_window_is_ambiguous(self):
        module = "account-service"
        provenance = make_provenance(module)
        provenance["attempts"][0]["jobs"][0]["conclusion"] = "failure"
        with self.assertRaisesRegex(ValueError, "physical execution outcome changed"):
            PARTITION.resolve_effective_artifacts(
                provenance, module, RUN_ID, SOURCE_SHA, "2"
            )

    def test_changed_physical_window_requires_its_own_partition_artifact(self):
        module = "game-session-service"
        provenance = make_provenance(module)
        carried_job = provenance["attempts"][1]["jobs"][0]
        carried_job["started_at"] = MIDDLE
        carried_job["completed_at"] = END
        with self.assertRaisesRegex(ValueError, "physical attempt 2 has missing"):
            PARTITION.resolve_effective_artifacts(
                provenance, module, RUN_ID, SOURCE_SHA, "2"
            )

    def test_carried_partition_does_not_fall_back_to_newer_attempt_artifact(self):
        module = "account-service"
        provenance = make_provenance(module)
        provenance["artifacts"]["items"] = [
            item
            for item in provenance["artifacts"]["items"]
            if "attempt-1-account-service-partition-1" not in item["name"]
        ]
        provenance["artifacts"]["items"].append(
            artifact(module, 1, 2, 704, created=MIDDLE)
        )
        provenance["artifacts"]["total_count"] = len(provenance["artifacts"]["items"])
        with self.assertRaisesRegex(ValueError, "physical attempt 1 has missing"):
            PARTITION.resolve_effective_artifacts(
                provenance, module, RUN_ID, SOURCE_SHA, "2"
            )

    def test_aggregate_rejects_incomplete_or_different_measured_classfiles(self):
        module = "account-service"
        cases = (
            (
                "missing class",
                lambda classes: (classes / "net/example/MeasuredService.class").unlink(),
                "class inventories differ",
            ),
            (
                "extra class",
                lambda classes: (classes / "net/example/Unexpected.class").write_bytes(
                    classfile_bytes("net/example/Unexpected")
                ),
                "class inventories differ",
            ),
            (
                "different bytes",
                lambda classes: (classes / "net/example/MeasuredService.class").write_bytes(
                    classfile_bytes("net/example/ChangedService")
                ),
                "class bytes differ",
            ),
        )
        for description, mutate_partition_two, message in cases:
            with self.subTest(description=description), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                selection = PARTITION.selection_document(
                    make_provenance(module), module, RUN_ID, SOURCE_SHA, "2"
                )
                input_dir, selection_path, verified_classes_dir, artifacts = prepare_aggregate(
                    root, module, selection
                )
                mutate_partition_two(artifacts[2] / "classes/java/main")
                with self.assertRaisesRegex(ValueError, message):
                    PARTITION.verify_aggregate(
                        input_dir,
                        selection_path,
                        verified_classes_dir,
                        module,
                        RUN_ID,
                        SOURCE_SHA,
                        "2",
                    )
                self.assertFalse(verified_classes_dir.exists())

    def test_aggregate_rejects_symlinked_measured_classfiles(self):
        module = "game-session-service"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            selection = PARTITION.selection_document(
                make_provenance(module), module, RUN_ID, SOURCE_SHA, "2"
            )
            input_dir, selection_path, verified_classes_dir, artifacts = prepare_aggregate(
                root, module, selection
            )
            classes_dir = artifacts[2] / "classes/java/main"
            original = classes_dir / "net/example/MeasuredService.class"
            target = classes_dir / "net/example/MeasuredServiceTarget.class"
            original.rename(target)
            original.symlink_to(target.name)
            with self.assertRaisesRegex(ValueError, "contain a symlink"):
                PARTITION.verify_aggregate(
                    input_dir,
                    selection_path,
                    verified_classes_dir,
                    module,
                    RUN_ID,
                    SOURCE_SHA,
                    "2",
                )
            self.assertFalse(verified_classes_dir.exists())

    def test_aggregate_binds_measured_classes_to_source_and_retained_attempt(self):
        module = "account-service"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            selection = PARTITION.selection_document(
                make_provenance(module), module, RUN_ID, SOURCE_SHA, "2"
            )
            input_dir, selection_path, verified_classes_dir, artifacts = prepare_aggregate(
                root, module, selection
            )
            carried_class = (
                artifacts[1] / "classes/java/main/net/example/MeasuredService.class"
            )
            carried_class.write_bytes(classfile_bytes("net/example/OldSource"))
            with self.assertRaisesRegex(ValueError, "class bytes differ"):
                PARTITION.verify_aggregate(
                    input_dir,
                    selection_path,
                    verified_classes_dir,
                    module,
                    RUN_ID,
                    SOURCE_SHA,
                    "2",
                )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            selection = PARTITION.selection_document(
                make_provenance(module), module, RUN_ID, SOURCE_SHA, "2"
            )
            input_dir, selection_path, verified_classes_dir, artifacts = prepare_aggregate(
                root, module, selection
            )
            manifest = (
                artifacts[1]
                / "slow-service-ci-partition/account-service/partition-1/manifest.json"
            )
            manifest_data = json.loads(manifest.read_text(encoding="utf-8"))
            manifest_data["source_revision"] = "b" * 40
            manifest.write_text(json.dumps(manifest_data), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "different source revision"):
                PARTITION.verify_aggregate(
                    input_dir,
                    selection_path,
                    verified_classes_dir,
                    module,
                    RUN_ID,
                    SOURCE_SHA,
                    "2",
                )
            self.assertFalse(verified_classes_dir.exists())

    def test_aggregate_rejects_missing_junit_for_a_selected_test_class(self):
        module = "account-service"
        provenance = make_provenance(module)
        selection = PARTITION.selection_document(
            provenance, module, RUN_ID, SOURCE_SHA, "2"
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            input_dir, selection_path, verified_classes_dir, artifacts = prepare_aggregate(
                root, module, selection
            )
            second = artifacts[2]
            (second / "test-results/test/TEST-results.xml").unlink()
            with self.assertRaisesRegex(ValueError, "JUnit reports miss"):
                PARTITION.verify_aggregate(
                    input_dir,
                    selection_path,
                    verified_classes_dir,
                    module,
                    RUN_ID,
                    SOURCE_SHA,
                    "2",
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
