import io
import unittest
from contextlib import redirect_stderr
from pathlib import Path
import importlib.util


SCRIPT_PATH = Path(__file__).resolve().parent / "check-proto-time-fields.py"


def _load_checker_module() -> object:
    spec = importlib.util.spec_from_file_location(
        "check_proto_time_fields_test_helper", SCRIPT_PATH
    )
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def _with_test_roots(module: object, tmp_path: Path) -> tuple[Path, Path, Path]:
    proto_root = tmp_path / "protos"
    proto_root.mkdir()
    old_repo_root = module.REPO_ROOT
    old_proto_root = module.PROTO_ROOT
    module.REPO_ROOT = tmp_path
    module.PROTO_ROOT = proto_root
    return proto_root, old_repo_root, old_proto_root


class CheckProtoTimeFieldsTest(unittest.TestCase):
    def test_passes_with_explicit_time_domain_suffixes(self) -> None:
        module = _load_checker_module()
        with tempfile_path() as tmp_root:
            proto_root, old_repo_root, old_proto_root = _with_test_roots(module, tmp_root)
            (proto_root / "session.proto").write_text(
                """
syntax = "proto3";

message Session {
  int64 expires_at_ms = 1;
  int64 remaining_ticks = 2;
}
""",
                encoding="utf-8",
            )
            try:
                findings = []
                for path in sorted(proto_root.rglob("*.proto")):
                    findings.extend(module.validate_file(path))
            finally:
                module.REPO_ROOT = old_repo_root
                module.PROTO_ROOT = old_proto_root
            self.assertEqual(findings, [])

    def test_rejects_ambiguous_time_fields(self) -> None:
        module = _load_checker_module()
        with tempfile_path() as tmp_root:
            proto_root, old_repo_root, old_proto_root = _with_test_roots(module, tmp_root)
            (proto_root / "session.proto").write_text(
                """
syntax = "proto3";

message Session {
  int64 timeout = 1;
}
""",
                encoding="utf-8",
            )
            try:
                with io.StringIO() as stderr, redirect_stderr(stderr):
                    exit_code = module.main()
                    output = stderr.getvalue()
            finally:
                module.REPO_ROOT = old_repo_root
                module.PROTO_ROOT = old_proto_root
            self.assertEqual(exit_code, 1)
            self.assertIn("time-related proto field 'timeout'", output)


def tempfile_path() -> "TemporaryDirectoryPath":
    return TemporaryDirectoryPath()


class TemporaryDirectoryPath:
    def __enter__(self) -> Path:
        import tempfile

        self._tempdir = tempfile.TemporaryDirectory()
        return Path(self._tempdir.name)

    def __exit__(self, exc_type, exc, tb) -> None:
        self._tempdir.cleanup()


if __name__ == "__main__":
    unittest.main()
