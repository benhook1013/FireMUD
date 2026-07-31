#!/usr/bin/env python3
"""Unit tests for the dev-demo workflow and summary validator."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "dev-tools/validation/check_dev_demo_summary.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("dev_demo_summary_validator", SCRIPT)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load dev-demo summary validator")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class DevDemoSummaryValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()

    def test_shell_group_tokens_ignore_comments_quotes_and_expansions(self):
        fixtures = (
            ("{ echo safe # comment with } and )", ["{"]),
            ("{ echo safe#not-a-comment }", ["{", "}"]),
            ('{ echo "# not a comment }" }', ["{", "}"]),
            (r"{ echo \#not-a-comment }", ["{", "}"]),
            ("{ echo ${value#pattern} }", ["{", "}"]),
            ('{ echo $(printf "# not a comment }") }', ["{", "}"]),
        )
        for fixture, expected in fixtures:
            with self.subTest(fixture=fixture):
                self.assertEqual(self.validator.shell_group_tokens(fixture), expected)

    def test_cleanup_parsers_handle_nested_groups_and_reject_unsupported_if_forms(self):
        validator = self.validator
        nested_if = [
            'if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then',
            'if [[ -n "${BOOTSTRAP_SECRET_DIR}" ]]; then',
            "true",
            "fi",
            "return 0",
            "fi",
        ]
        self.assertEqual(validator.closing_fi_index(nested_if, 0), 5)
        nested_group = [
            "cleanup_bootstrap_temp_dir() {",
            "{",
            'if [[ -n "${BOOTSTRAP_SECRET_DIR}" ]]; then',
            "true",
            "fi",
            "}",
            "}",
        ]
        self.assertEqual(
            validator._cleanup_function_end_index(nested_group, 0), len(nested_group)
        )
        for fixture in (["if true", "then", "fi"], ["if true; then echo inline; fi"]):
            with (
                self.subTest(fixture=fixture),
                self.assertRaisesRegex(AssertionError, "unsupported shell if form"),
            ):
                validator.closing_fi_index(fixture, 0)
        self.assertIsNone(
            validator.closing_fi_index(
                ['if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then', "true"], 0
            )
        )
        self.assertIsNone(
            validator._cleanup_function_end_index(
                ["cleanup_bootstrap_temp_dir() {", "true"], 0
            )
        )

    def test_summary_helper_paths_allow_only_workspace_root_variables(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = root / "dev-tools/tests/smoke-transport-contract.sh"
            fixtures = (
                "dev-tools/tests/smoke-transport-contract.sh",
                "./dev-tools/tests/smoke-transport-contract.sh",
                "bash dev-tools/tests/smoke-transport-contract.sh",
                "bash ./dev-tools/tests/smoke-transport-contract.sh",
                f"bash {root}/dev-tools/tests/smoke-transport-contract.sh",
                "$FIREMUD_REPO_ROOT/dev-tools/tests/smoke-transport-contract.sh",
                "$GITHUB_WORKSPACE/dev-tools/tests/smoke-transport-contract.sh",
                "${ROOT_DIR}/dev-tools/tests/smoke-transport-contract.sh",
            )
            for fixture in fixtures:
                with self.subTest(fixture=fixture):
                    matches = list(validator._helper_matches(fixture))
                    self.assertEqual(len(matches), 1)
                    self.assertEqual(
                        validator.normalize_summary_helper_path(
                            matches[0].group("invocation"), root
                        ),
                        expected.resolve(),
                    )
            unsupported = "$HOME/dev-tools/tests/smoke-transport-contract.sh"
            match = next(validator._helper_matches(unsupported))
            with self.assertRaisesRegex(ValueError, "unsupported variable-prefixed"):
                validator.normalize_summary_helper_path(match.group("invocation"), root)

            outside = root.parent / "dev-tools/tests/smoke-transport-contract.sh"
            with self.assertRaisesRegex(
                ValueError, "summary helper path escapes repository root"
            ):
                validator.discover_summary_writers(
                    [
                        validator.WorkflowRunSource(
                            "job",
                            "step",
                            f'bash {outside} >> "$GITHUB_STEP_SUMMARY"',
                        )
                    ],
                    root,
                )

    def test_summary_write_regions_preserve_group_and_heredoc_boundaries(self):
        validator = self.validator
        fixtures = (
            ('echo "safe summary" > "$GITHUB_STEP_SUMMARY"', False),
            ('echo "safe summary" | tee "$GITHUB_STEP_SUMMARY"', False),
            ('echo "safe summary" | tee -a "$GITHUB_STEP_SUMMARY"', False),
            (
                'echo "safe summary" | tee --append "$GITHUB_STEP_SUMMARY"',
                False,
            ),
            (
                (
                    'printf "%s" "$DEMO_SMOKE_PASSWORD" >/tmp/password\n'
                    'echo "safe summary" >> "$GITHUB_STEP_SUMMARY"'
                ),
                False,
            ),
            (
                '{\n  echo "unsafe: $DEMO_SMOKE_PASSWORD"\n} >> "$GITHUB_STEP_SUMMARY"',
                True,
            ),
            (
                (
                    "cat <<'SUMMARY_EOF' >> \"$GITHUB_STEP_SUMMARY\"\n"
                    "unsafe: $DEMO_SMOKE_PASSWORD\n"
                    "SUMMARY_EOF"
                ),
                True,
            ),
            (
                (
                    "cat <<-'SUMMARY_EOF' >> \"$GITHUB_STEP_SUMMARY\"\n"
                    "\tunsafe: $DEMO_SMOKE_PASSWORD\n"
                    "SUMMARY_EOF"
                ),
                True,
            ),
            (
                (
                    "cat >> \"$GITHUB_STEP_SUMMARY\" <<'SUMMARY_EOF'\n"
                    "unsafe: $DEMO_SMOKE_PASSWORD\n"
                    "SUMMARY_EOF"
                ),
                True,
            ),
            (
                (
                    "cat <<'SUMMARY_EOF' | tee \"$GITHUB_STEP_SUMMARY\"\n"
                    "unsafe: $DEMO_SMOKE_PASSWORD\n"
                    "SUMMARY_EOF"
                ),
                True,
            ),
            (
                '{ echo "unsafe: $DEMO_SMOKE_PASSWORD"; } >> "$GITHUB_STEP_SUMMARY"',
                True,
            ),
            (
                '( echo "unsafe: $DEMO_SMOKE_PASSWORD" ) >> "$GITHUB_STEP_SUMMARY"',
                True,
            ),
            (
                (
                    "cat <<'UNRELATED'\n"
                    "unsafe: $DEMO_SMOKE_PASSWORD\n"
                    "UNRELATED\n"
                    'echo "safe summary" >> "$GITHUB_STEP_SUMMARY"'
                ),
                False,
            ),
            (
                (
                    "unrelated() {\n"
                    '  echo "unsafe: $DEMO_SMOKE_PASSWORD"\n'
                    "}\n"
                    'echo "safe summary" >> "$GITHUB_STEP_SUMMARY"'
                ),
                False,
            ),
        )
        for fixture, unsafe in fixtures:
            with self.subTest(fixture=fixture):
                regions = validator.summary_write_regions(fixture)
                self.assertEqual(
                    any(
                        validator.has_forbidden_summary_reference(region)
                        for region in regions
                    ),
                    unsafe,
                )

    def test_forbidden_summary_reference_detects_secret_pipelines_without_crossing_commands(
        self,
    ):
        validator = self.validator
        secret_pipelines = (
            "kubectl get secret demo -o json | base64 -d",
            "kubectl get SECRETS demo -o json | jq -r .data.password | base64 --decode",
            "kubectl get sec demo -o json | tr -d '\\n' | base64 -d",
            "echo API_SECRET_TOKEN | base64 -d",
            "echo DB_CREDS | base64 -d",
            "echo DB_CREDS | base64 -di",
        )
        for fixture in secret_pipelines:
            with self.subTest(fixture=fixture):
                self.assertTrue(validator.has_forbidden_summary_reference(fixture))
        direct_references = (
            'echo "${BOOTSTRAP_SECRET_DIR}/password"',
            'echo "${{ secrets.DEMO_SMOKE_PASSWORD }}"',
            'echo "${{ steps.create-account.outputs.password }}"',
        )
        for fixture in direct_references:
            with self.subTest(fixture=fixture):
                self.assertTrue(validator.has_forbidden_summary_reference(fixture))
        safe = (
            "echo secret summary",
            "kubectl get secret demo -o json; cat encoded.txt | base64 -d",
            "kubectl get secret demo -o json\ncat encoded.txt | base64 -d",
            "kubectl get secret demo -o json | jq -r .metadata.name",
            "kubectl get secret demo -o json; echo base64 -d",
            "kubectl get secret demo -o json\nbase64 -d",
        )
        for fixture in safe:
            with self.subTest(fixture=fixture):
                self.assertFalse(validator.has_forbidden_summary_reference(fixture))

    def test_summary_reachability_propagates_through_redirected_helpers(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper_one = root / "dev-tools/summary-one.sh"
            helper_two = root / "dev-tools/summary-two.sh"
            helper_one.parent.mkdir(parents=True)
            helper_one.write_text(
                "#!/usr/bin/env bash\nbash dev-tools/summary-two.sh\n",
                encoding="utf-8",
            )
            helper_two.write_text(
                '#!/usr/bin/env bash\necho "unsafe: $DEMO_SMOKE_PASSWORD"\n',
                encoding="utf-8",
            )
            sources = [
                validator.WorkflowRunSource(
                    "job",
                    "step",
                    'bash dev-tools/summary-one.sh >> "$GITHUB_STEP_SUMMARY"',
                )
            ]
            writers = validator.discover_summary_writers(sources, root)
            self.assertEqual(len(writers), 3)
            reachable_helpers = [
                writer
                for writer in writers
                if writer.resolved_helper_path
                in {helper_one.resolve(), helper_two.resolve()}
            ]
            self.assertEqual(len(reachable_helpers), 2)
            self.assertTrue(all(writer.summary_reachable for writer in reachable_helpers))
            self.assertTrue(
                any(
                    validator.has_forbidden_summary_reference(writer.source)
                    for writer in reachable_helpers
                    if writer.summary_reachable
                )
            )

    def test_helper_cycles_terminate_without_repeating_resolved_paths(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper_one = root / "dev-tools/summary-one.sh"
            helper_two = root / "dev-tools/summary-two.sh"
            helper_one.parent.mkdir(parents=True)
            helper_one.write_text(
                "#!/usr/bin/env bash\nbash dev-tools/summary-two.sh\n",
                encoding="utf-8",
            )
            helper_two.write_text(
                "#!/usr/bin/env bash\nbash dev-tools/summary-one.sh\n",
                encoding="utf-8",
            )
            sources = [
                validator.WorkflowRunSource(
                    "job",
                    "step",
                    'bash dev-tools/summary-one.sh >> "$GITHUB_STEP_SUMMARY"',
                )
            ]
            writers = validator.discover_summary_writers(sources, root)
            self.assertEqual(len(writers), 3)
            self.assertEqual(
                len({writer.resolved_helper_path for writer in writers}), 3
            )
            self.assertTrue(
                all(
                    writer.summary_reachable
                    for writer in writers
                    if writer.resolved_helper_path is not None
                )
            )

    def test_helper_reprocesses_when_reachability_is_upgraded(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = root / "dev-tools/summary.sh"
            helper.parent.mkdir(parents=True)
            helper.write_text(
                '#!/usr/bin/env bash\necho "unsafe: $DEMO_SMOKE_PASSWORD"\n',
                encoding="utf-8",
            )
            sources = [
                validator.WorkflowRunSource(
                    "job",
                    "summary-step",
                    'bash dev-tools/summary.sh >> "$GITHUB_STEP_SUMMARY"',
                ),
                validator.WorkflowRunSource(
                    "job", "non-summary-step", "bash dev-tools/summary.sh"
                ),
            ]
            writers = validator.discover_summary_writers(sources, root)
            reachable_helpers = [
                writer
                for writer in writers
                if writer.resolved_helper_path == helper.resolve()
            ]
            self.assertEqual(len(reachable_helpers), 1)
            self.assertTrue(reachable_helpers[0].summary_reachable)

    def test_missing_helper_fails_closed_with_clear_assertion(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = [
                validator.WorkflowRunSource(
                    "job",
                    "step",
                    'bash dev-tools/missing-summary.sh >> "$GITHUB_STEP_SUMMARY"',
                )
            ]
            with self.assertRaisesRegex(
                AssertionError, "summary helper file is missing"
            ):
                validator.discover_summary_writers(source, root)

    def test_discovery_fails_closed_for_unsupported_variable_helper(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = [
                validator.WorkflowRunSource(
                    "job",
                    "step",
                    'bash "$UNTRUSTED_ROOT/dev-tools/summary.sh" >> "$GITHUB_STEP_SUMMARY"',
                )
            ]
            with self.assertRaisesRegex(ValueError, "unsupported variable-prefixed"):
                validator.discover_summary_writers(source, root)


if __name__ == "__main__":
    unittest.main()
