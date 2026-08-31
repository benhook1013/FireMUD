import importlib.util
import types
import unittest
from pathlib import Path

SCRIPT_PATH = (
    Path(__file__).resolve().parents[1]
    / "observability/check-metrics-cardinality.py"
)


def _load_checker_module() -> types.ModuleType:
    spec = importlib.util.spec_from_file_location(
        "check_metrics_cardinality_test_helper", SCRIPT_PATH
    )
    if spec is None or spec.loader is None:
        raise AssertionError(f"could not load {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CheckMetricsCardinalityTest(unittest.TestCase):
    def test_grafana_legend_labels_are_matched_as_atomic_identifiers(self) -> None:
        module = _load_checker_module()

        for line in (
            '"legendFormat": "{{scope_class}}"',
            '"legendFormat": "{{className}}"',
            '"legendFormat": "{{script_patch_version_label}}"',
        ):
            with self.subTest(line=line):
                self.assertIsNone(module.GRAFANA_LEGEND_PATTERN.search(line))

    def test_grafana_legend_matches_exact_and_standalone_forbidden_labels(self) -> None:
        module = _load_checker_module()

        for line, expected_label in (
            ('"legendFormat": "{{class}}"', "class"),
            ('"legendFormat": "class"', "class"),
            ('"legendFormat": "service {{characterId}}"', "characterId"),
        ):
            with self.subTest(line=line):
                match = module.GRAFANA_LEGEND_PATTERN.search(line)
                self.assertIsNotNone(match)
                self.assertEqual(match.group("label"), expected_label)


if __name__ == "__main__":
    unittest.main()
