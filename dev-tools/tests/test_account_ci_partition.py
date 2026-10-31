import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = ROOT / "dev-tools/validation/account_ci_test_partition.py"
SPEC = importlib.util.spec_from_file_location("account_ci_test_partition", SCRIPT_PATH)
PARTITION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PARTITION)


def make_plan():
    return {
        "schema_version": 1,
        "source_revision": "a" * 40,
        "run_attempt": "3",
        "partition_count": 2,
        "tasks": {
            "test": {
                "class_universe": [
                    "net.example.AccountKnownOneTest",
                    "net.example.AccountKnownTwoTest",
                ],
                "assignments": {
                    "net.example.AccountKnownOneTest": 1,
                    "net.example.AccountKnownTwoTest": 2,
                },
                "expected_test_classes": [
                    "net.example.AccountKnownOneTest",
                    "net.example.AccountKnownTwoTest",
                ],
            }
        },
    }


def write_artifact(root, partition, plan):
    name = (
        f"account-ci-partition-{plan['source_revision']}-attempt-"
        f"{plan['run_attempt']}-partition-{partition}"
    )
    artifact = root / name
    partition_dir = artifact / "account-ci-partition" / f"partition-{partition}"
    partition_dir.mkdir(parents=True)
    manifest = PARTITION.partition_manifest(plan, partition)
    (partition_dir / "manifest.json").write_text(
        json.dumps(manifest), encoding="utf-8"
    )
    (partition_dir / "test.txt").write_text(
        "".join(f"{item}\n" for item in manifest["tasks"]["test"]["selected_classes"]),
        encoding="utf-8",
    )
    (artifact / "jacoco").mkdir()
    (artifact / "jacoco/test.exec").write_bytes(b"execution data")
    report = artifact / "reports/jacoco/test/jacocoTestReport.xml"
    report.parent.mkdir(parents=True)
    report.write_text("<report />\n", encoding="utf-8")
    class_name = manifest["tasks"]["test"]["selected_classes"][0]
    junit = artifact / "test-results/test/TEST-results.xml"
    junit.parent.mkdir(parents=True)
    junit.write_text(
        "<testsuite><testcase classname=\""
        f"{class_name}"
        "\" name=\"runs\" /></testsuite>\n",
        encoding="utf-8",
    )
    return artifact


class AccountCiPartitionTests(unittest.TestCase):
    def test_newly_discovered_classes_join_a_complete_disjoint_plan(self):
        historical = PARTITION.load_historical_plan()
        known_task = "integrationTest"
        known_class, known_entry = next(
            iter(historical["tasks"][known_task]["classes"].items())
        )
        discovered = {
            known_task: {
                known_class,
                "integration.net.firedevops.firemud.accountservice.NewAccountTest",
                "integration.net.firedevops.firemud.accountservice.NewFixtureHelper",
            }
        }
        plan = PARTITION.build_plan(discovered, "b" * 40, "2", historical)
        first = PARTITION.partition_manifest(plan, 1)
        second = PARTITION.partition_manifest(plan, 2)
        PARTITION.validate_partition_pair(first, second)

        task_one = first["tasks"][known_task]
        task_two = second["tasks"][known_task]
        self.assertEqual(task_one["assignments"][known_class], known_entry["partition"])
        self.assertEqual(
            set(task_one["selected_classes"]) | set(task_two["selected_classes"]),
            discovered[known_task],
        )
        self.assertFalse(
            set(task_one["selected_classes"]) & set(task_two["selected_classes"])
        )

    def test_class_discovery_keeps_nested_classes_with_their_outer_class(self):
        with tempfile.TemporaryDirectory() as temporary:
            classes = Path(temporary) / "classes"
            package = classes / "net/example"
            package.mkdir(parents=True)
            (package / "AccountNewTest.class").write_bytes(b"outer")
            (package / "AccountNewTest$Nested.class").write_bytes(b"nested")
            (package / "AccountFixture.class").write_bytes(b"helper")
            discovered = PARTITION.class_names_from_directories([classes])
        self.assertEqual(
            discovered,
            {"net.example.AccountNewTest", "net.example.AccountFixture"},
        )

    def test_aggregate_requires_both_partitions_and_complete_junit_reports(self):
        plan = make_plan()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_artifact(root, 1, plan)
            second = write_artifact(root, 2, plan)
            self.assertEqual(
                PARTITION.verify_aggregate(root, plan["source_revision"], "3"), 2
            )

            (second / "test-results/test/TEST-results.xml").unlink()
            with self.assertRaisesRegex(ValueError, "JUnit reports miss"):
                PARTITION.verify_aggregate(root, plan["source_revision"], "3")

    def test_aggregate_rejects_a_single_partial_shard(self):
        plan = make_plan()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_artifact(root, 1, plan)
            with self.assertRaisesRegex(ValueError, "Re-run all jobs"):
                PARTITION.verify_aggregate(root, plan["source_revision"], "3")

    def test_aggregate_explains_the_whole_run_retry_when_no_current_shards_exist(self):
        plan = make_plan()
        with tempfile.TemporaryDirectory() as temporary:
            missing = Path(temporary) / "missing-current-attempt"
            with self.assertRaisesRegex(ValueError, "Re-run all jobs"):
                PARTITION.verify_aggregate(missing, plan["source_revision"], "3")

    def test_partition_pair_rejects_duplicate_or_incomplete_assignment(self):
        plan = make_plan()
        first = PARTITION.partition_manifest(plan, 1)
        second = PARTITION.partition_manifest(plan, 2)
        broken = copy.deepcopy(second)
        broken["tasks"]["test"]["selected_classes"] = []
        with self.assertRaisesRegex(ValueError, "test class union is incomplete"):
            PARTITION.validate_partition_pair(first, broken)


if __name__ == "__main__":
    unittest.main()
