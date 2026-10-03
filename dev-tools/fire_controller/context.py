"""One explicit project configuration for a project-specific runtime wrapper."""
from __future__ import annotations

import json
import os
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ProjectContext:
    root: Path
    database: Path
    repository: str

    @classmethod
    def load(cls, filename):
        source = Path(filename).expanduser().resolve()
        value = json.loads(source.read_text(encoding="utf-8"))
        if not isinstance(value, dict) or set(value) - {"root", "database", "repository"}:
            raise ValueError("context must contain only root, database and repository")
        for name in ("root", "database", "repository"):
            if not isinstance(value.get(name), str) or not value[name].strip():
                raise ValueError(f"context {name} must be nonempty text")
        root, database = Path(value["root"]).expanduser(), Path(value["database"]).expanduser()
        if not root.is_absolute() or not root.is_dir() or not database.is_absolute():
            raise ValueError("context root must be an existing absolute directory and database an absolute path")
        if database.is_symlink():
            raise ValueError("context database must not be a symlink")
        repository = value["repository"]
        if repository.count("/") != 1 or any(not part or any(c.isspace() for c in part)
                                              for part in repository.split("/")):
            raise ValueError("context repository must be owner/name")
        return cls(root.resolve(), database.resolve(), repository)

    def select_database(self, supplied=None):
        if supplied is not None and Path(supplied).expanduser().resolve() != self.database:
            raise ValueError("--database does not match the selected project context")
        return self.database

    def validate_review_arguments(self, arguments):
        # Use the delegated parser's destinations, including accepted argparse
        # abbreviations, so forwarding cannot evade project selection guards.
        from pr_review.cli import _parser

        try:
            parsed = _parser().parse_args(arguments)
        except SystemExit as error:
            if error.code == 0:
                raise
            raise ValueError("invalid delegated review arguments") from error
        supplied_database = getattr(parsed, "database", None)
        if supplied_database is not None:
            self.select_database(supplied_database)
        supplied_repository = getattr(parsed, "repo", None)
        if supplied_repository is not None and supplied_repository != self.repository:
            raise ValueError("--repo does not match the selected project context")
        for field in ("path", "state_path"):
            value = getattr(parsed, field, None)
            if value is not None and Path(value).expanduser().resolve() != self.database.with_suffix(".json"):
                raise ValueError("review state path does not match the selected project's controller state path")

    @contextmanager
    def review_environment(self):
        # Review state/locks/captures are selected by the existing engine from
        # this repository's Git common directory, never the executable's tree.
        import subprocess
        result = subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=self.root,
                                capture_output=True, text=True, check=False)
        if result.returncode:
            raise ValueError("review context root must be a Git checkout")
        common = Path(result.stdout.strip())
        if not common.is_absolute():
            common = self.root / common
        expected = (common / "firemud" / "pr-review-stack.sqlite3").resolve()
        if expected != self.database:
            raise ValueError("review context database must match the selected repository's controller SQLite path")
        previous = Path.cwd()
        previous_repo = os.environ.get("GH_REPO")
        try:
            os.chdir(self.root)
            os.environ["GH_REPO"] = self.repository
            yield
        finally:
            os.chdir(previous)
            if previous_repo is None:
                os.environ.pop("GH_REPO", None)
            else:
                os.environ["GH_REPO"] = previous_repo
