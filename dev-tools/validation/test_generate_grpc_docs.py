import os
import subprocess
import tempfile
import unittest
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[2]
GENERATOR = REPOSITORY / "dev-tools/docs/generate-grpc-docs.sh"


class GenerateGrpcDocsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = tempfile.TemporaryDirectory()
        self.root = Path(self.fixture.name)
        (self.root / "protos/example.proto").parent.mkdir(parents=True)
        (self.root / "protos/example.proto").write_text('syntax = "proto3";\n')
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.write_fake_protoc()

    def tearDown(self):
        self.fixture.cleanup()

    def write_fake_protoc(self):
        protoc = self.bin_dir / "protoc"
        protoc.write_text(
            "#!/usr/bin/env python3\n"
            "import os\n"
            "from pathlib import Path\n"
            "import sys\n"
            "if os.environ.get('PROTOC_FAIL') == '1':\n"
            "    sys.exit(9)\n"
            "doc_out = next(arg.split('=', 1)[1] for arg in sys.argv "
            "if arg.startswith('--doc_out='))\n"
            "doc_name = next(arg.split(',', 1)[1] for arg in sys.argv "
            "if arg.startswith('--doc_opt=markdown,'))\n"
            "Path(doc_out, doc_name).write_text("
            "'protoc header\\nTrailing spaces   \\nInternal one\\n\\n\\n'"
            "+ 'Internal two\\t \\nLast content\\n\\n \\n')\n"
        )
        protoc.chmod(0o755)

    def run_generator(self, *, fail=False):
        env = os.environ.copy()
        env["PATH"] = f"{self.bin_dir}{os.pathsep}{env['PATH']}"
        if fail:
            env["PROTOC_FAIL"] = "1"
        else:
            env.pop("PROTOC_FAIL", None)
        return subprocess.run(
            ["bash", str(GENERATOR)],
            cwd=self.root,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_normalizes_trailing_whitespace_and_blank_lines(self):
        result = self.run_generator()

        self.assertEqual(result.returncode, 0, result.stderr)
        output = (self.root / "design/grpc-docs/grpc-api.md").read_text()
        self.assertTrue(
            output.endswith(
                "Trailing spaces\nInternal one\n\n\n"
                "Internal two\nLast content\n"
            )
        )
        self.assertEqual(
            list((self.root / "design/grpc-docs").glob(".grpc-api-*")), []
        )

    def test_protoc_failure_preserves_existing_output_and_cleans_temps(self):
        output_dir = self.root / "design/grpc-docs"
        output_dir.mkdir(parents=True)
        output_file = output_dir / "grpc-api.md"
        output_file.write_text("previous output\n")

        result = self.run_generator(fail=True)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output_file.read_text(), "previous output\n")
        self.assertEqual(list(output_dir.glob(".grpc-api-*")), [])


if __name__ == "__main__":
    unittest.main()
