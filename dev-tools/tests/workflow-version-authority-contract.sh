#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export FIREMUD_REPO_ROOT="$ROOT_DIR"

python3 - <<'PY'
from __future__ import annotations

import json
import os
import re
from pathlib import Path

import yaml

root = Path(os.environ["FIREMUD_REPO_ROOT"])
workflows = root / ".github" / "workflows"


def fail(message: str) -> None:
    raise SystemExit(message)


def read_authority(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*=[A-Za-z0-9._-]+", line):
            fail(f"{path}: invalid authority line: {line}")
        key, value = line.split("=", 1)
        if key in values:
            fail(f"{path}: duplicate authority key: {key}")
        values[key] = value
    return values


node_version = (root / ".node-version").read_text(encoding="utf-8").strip()
if not re.fullmatch(r"24\.\d+\.\d+", node_version):
    fail(".node-version must pin one exact Node 24 release")
gradle_build = (root / "build.gradle.kts").read_text(encoding="utf-8")
if 'layout.projectDirectory.file(".node-version")' not in gradle_build:
    fail("Gradle Node setup must consume .node-version")
if re.search(r"node\s*\{.*?version\.set\(\s*\"", gradle_build, re.DOTALL):
    fail("Gradle Node setup must not duplicate the canonical version")

python_version = (root / ".python-version").read_text(encoding="utf-8").strip()
if python_version != "3.14":
    fail(".python-version must express the exact workflow Python 3.14 policy")

requirements = (root / "config/docs/requirements.txt").read_text(encoding="utf-8").splitlines()
packages = {}
for line in requirements:
    match = re.fullmatch(r"(mkdocs(?:-material)?)==([^=\s]+)", line)
    if not match:
        fail("documentation requirements must contain only exact MkDocs pins")
    packages[match.group(1)] = match.group(2)
if set(packages) != {"mkdocs", "mkdocs-material"}:
    fail("documentation requirements must pin MkDocs and MkDocs Material exactly once")

authority_path = root / "config/workflow-tool-versions.env"
authority = read_authority(authority_path)
expected_version_keys = {"KUBECTL_VERSION", "HELM_VERSION", "GH_VERSION", "ORT_VERSION"}
for key in expected_version_keys:
    if not re.fullmatch(r"\d+\.\d+\.\d+", authority.get(key, "")):
        fail(f"{authority_path}: {key} must be an exact three-part version")
for key in {"HELM_LINUX_AMD64_SHA256", "GH_LINUX_AMD64_SHA256"}:
    if not re.fullmatch(r"[0-9a-f]{64}", authority.get(key, "")):
        fail(f"{authority_path}: {key} must be a lowercase SHA-256")
if set(authority) != expected_version_keys | {
    "HELM_LINUX_AMD64_SHA256",
    "GH_LINUX_AMD64_SHA256",
}:
    fail(f"{authority_path}: unexpected or missing authority keys")

workflow_documents: dict[Path, dict] = {}
for path in sorted(workflows.glob("*.yml")):
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    if isinstance(document, dict):
        workflow_documents[path] = document

node_consumers = 0
python_consumers = 0
ort_consumers = 0
local_operational_consumers = {"setup-kubectl": 0, "setup-helm": 0, "setup-gh": 0}
for path, document in workflow_documents.items():
    jobs = document.get("jobs", {})
    if not isinstance(jobs, dict):
        continue
    for job_name, job in jobs.items():
        if not isinstance(job, dict):
            continue
        steps = job.get("steps", [])
        if not isinstance(steps, list):
            continue
        checkout_seen = False
        tool_loader_seen = False
        for step in steps:
            if not isinstance(step, dict):
                continue
            uses = step.get("uses", "")
            if isinstance(uses, str) and uses.startswith("actions/checkout@"):
                checkout_seen = True
            if uses == "./.github/actions/load-workflow-tool-versions":
                if not checkout_seen:
                    fail(f"{path}:{job_name}: tool authority loader runs before checkout")
                tool_loader_seen = True
            if isinstance(uses, str) and uses.startswith("actions/setup-node@"):
                node_consumers += 1
                if not checkout_seen:
                    fail(f"{path}:{job_name}: setup-node runs before checkout")
                inputs = step.get("with", {})
                if inputs.get("node-version-file") != ".node-version" or "node-version" in inputs:
                    fail(f"{path}:{job_name}: setup-node must consume .node-version")
            if isinstance(uses, str) and uses.startswith("actions/setup-python@"):
                python_consumers += 1
                if not checkout_seen:
                    fail(f"{path}:{job_name}: setup-python runs before checkout")
                inputs = step.get("with", {})
                if inputs.get("python-version-file") != ".python-version" or "python-version" in inputs:
                    fail(f"{path}:{job_name}: setup-python must consume .python-version")
            for action in local_operational_consumers:
                if uses == f"./.github/actions/{action}":
                    if not checkout_seen:
                        fail(f"{path}:{job_name}: {action} runs before checkout")
                    local_operational_consumers[action] += 1
            if isinstance(uses, str) and uses.startswith("oss-review-toolkit/ort-ci-github-action@"):
                ort_consumers += 1
                if not tool_loader_seen:
                    fail(f"{path}:{job_name}: ORT runs before loading its version authority")
                if step.get("with", {}).get("image") != "${{ steps.workflow-tool-versions.outputs.ort-image }}":
                    fail(f"{path}:{job_name}: ORT does not consume the canonical image output")

if node_consumers == 0 or python_consumers == 0 or ort_consumers != 4:
    fail("expected Node, Python, and all four ORT workflow consumers")
if any(count == 0 for count in local_operational_consumers.values()):
    fail("expected workflow consumers for each operational setup action")

all_workflow_text = "\n".join(path.read_text(encoding="utf-8") for path in workflow_documents)
for forbidden in (
    "node-version:",
    "python-version:",
    "azure/setup-kubectl@",
    "ORT_DOCKER_IMAGE",
    "mkdocs==",
    "mkdocs-material==",
):
    if forbidden in all_workflow_text:
        fail(f"workflow still contains duplicated authority: {forbidden}")

for workflow_name in ("ci.yml", "docs.yml"):
    text = (workflows / workflow_name).read_text(encoding="utf-8")
    if "python3 -m pip install --disable-pip-version-check -r config/docs/requirements.txt" not in text:
        fail(f"{workflow_name} must install the canonical documentation requirements")

kubectl_action = (root / ".github/actions/setup-kubectl/action.yml").read_text(encoding="utf-8")
if "version: v${{ steps.versions.outputs.kubectl-version }}" not in kubectl_action:
    fail("setup-kubectl must consume the canonical version output")
helm_action = (root / ".github/actions/setup-helm/action.yml").read_text(encoding="utf-8")
for required in (
    "HELM_VERSION: v${{ steps.versions.outputs.helm-version }}",
    "HELM_SHA256: ${{ steps.versions.outputs.helm-linux-amd64-sha256 }}",
    "sha256sum --check --status",
):
    if required not in helm_action:
        fail(f"setup-helm does not preserve authority/checksum consumption: {required}")
gh_action = (root / ".github/actions/setup-gh/action.yml").read_text(encoding="utf-8")
for required in (
    "GH_VERSION: ${{ steps.versions.outputs.gh-version }}",
    "GH_SHA256: ${{ steps.versions.outputs.gh-linux-amd64-sha256 }}",
    "sha256sum --check --status",
):
    if required not in gh_action:
        fail(f"setup-gh does not preserve authority/checksum consumption: {required}")

renovate = json.loads((root / "renovate.json").read_text(encoding="utf-8"))
required_managers = {"nodenv", "pyenv", "pip_requirements", "custom.regex"}
if not required_managers.issubset(set(renovate.get("enabledManagers", []))):
    fail("Renovate must enable every canonical version-authority manager")
custom_managers = renovate.get("customManagers", [])
if len(custom_managers) != 1:
    fail("Renovate must define one workflow-tool regex manager")
manager = custom_managers[0]
if manager.get("managerFilePatterns") != ["/^config/workflow-tool-versions\\.env$/"]:
    fail("Renovate workflow-tool manager must target the canonical authority")
pattern = re.compile(manager.get("matchStrings", [""])[0].replace("(?<", "(?P<"))
matches = list(pattern.finditer(authority_path.read_text(encoding="utf-8")))
if {match.group("currentValue") for match in matches} != {
    authority[key] for key in expected_version_keys
}:
    fail("Renovate workflow-tool manager must recognize every canonical version")

gradle_diagnostic = (root / "gradle/proto-convention.gradle").read_text(encoding="utf-8")
if "Gradle 9.5.1" in gradle_diagnostic or "${gradle.gradleVersion}" not in gradle_diagnostic:
    fail("protobuf diagnostic must derive the active Gradle version")

print("Workflow version authority contract passed")
PY
