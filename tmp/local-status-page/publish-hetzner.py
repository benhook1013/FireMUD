#!/usr/bin/env python3
"""Publish the rendered, brief-link-free status snapshot through preview Traefik."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "output" / "index.html"
PUBLIC_COPY = ROOT / "output" / "public-index.html"
PROGRESS_SOURCE = ROOT / "output" / "progress.html"
HISTORY_SOURCE = ROOT / "output" / "queue-history.html"
REVIEW_SOURCE = ROOT / "output" / "review"
ASSET_FILES = (
    "icon-options.html", "flame-ember.svg", "flame-monogram.svg", "flame-crest.svg", "flame-pixel.svg",
)
SSH_KEY = Path("/home/ben/.ssh/firemud_preview_ed25519")
SSH_TARGET = "root@77.42.29.156"
HOST = "status.preview.firedevops.net"
NAMESPACE = "overseer-status"
IMAGE = "docker.io/library/nginx@sha256:7150b3a39203cb5bee612ff4a9d18774f8c7caf6399d6e8985e97e28eb751c18"
LOCAL_STATUS_URL = "http://192.168.50.100:8877/"
BRIEF_LINK = re.compile(r'<a href="\.\./\.\./task-briefs/[^"]+">([^<]+)</a>')
REFRESH_FORM = re.compile(r'<form class="refresh-form"[^>]*>.*?</form>', re.DOTALL)
REFRESH_TIME = re.compile(r'<span class="refresh-time">.*?</span>', re.DOTALL)
REFRESH_SCRIPT = re.compile(r'<script id="local-refresh-progress">.*?</script>', re.DOTALL)
AGE_BOOTSTRAP_SCRIPT = re.compile(r'<script id="age-pending-bootstrap">(.*?)</script>', re.DOTALL)
AGE_SCRIPT = re.compile(r'<script id="relative-age-updates">(.*?)</script>', re.DOTALL)
SNAPSHOT_SCRIPT = re.compile(r'<script id="snapshot-updates">(.*?)</script>', re.DOTALL)
REVIEW_LINK = re.compile(r'href="review/pr-(\d+)\.html"')


def local_wifi_url() -> str:
    return LOCAL_STATUS_URL


def public_html(source: str, local_url: str, *, history: bool = False) -> str:
    result = BRIEF_LINK.sub(r"\1 (local brief)", source)
    if REFRESH_TIME.search(result) is None:
        raise ValueError("the published page needs its read-only refresh timestamp")
    result = REFRESH_FORM.sub("", result)
    result = REFRESH_SCRIPT.sub("", result)
    age_bootstrap = AGE_BOOTSTRAP_SCRIPT.search(result)
    if age_bootstrap is None:
        raise ValueError("the published page needs its age bootstrap script")
    age_script = AGE_SCRIPT.search(result)
    if age_script is None:
        raise ValueError("the published page needs its read-only relative-time script")
    snapshot_script = SNAPSHOT_SCRIPT.search(result)
    if snapshot_script is None:
        raise ValueError("the published page needs its read-only snapshot script")
    age_bootstrap_hash = base64.b64encode(
        hashlib.sha256(age_bootstrap.group(1).encode()).digest()
    ).decode()
    age_hash = base64.b64encode(hashlib.sha256(age_script.group(1).encode()).digest()).decode()
    snapshot_hash = base64.b64encode(hashlib.sha256(snapshot_script.group(1).encode()).digest()).decode()
    result = result.replace("form-action 'self'", "form-action 'none'")
    result, policy_count = re.subn(
        r"script-src(?: 'sha256-[^']+')+",
        f"script-src 'sha256-{age_bootstrap_hash}' 'sha256-{age_hash}' 'sha256-{snapshot_hash}'",
        result,
        count=1,
    )
    if policy_count != 1:
        raise ValueError("the published page needs a matching script policy")
    result = re.sub(
        r"<footer>.*?</footer>",
        f'<footer>Manual snapshot. At home, <a href="{local_url}">open the local Wi-Fi page</a> to refresh review data.</footer>',
        result,
        count=1,
        flags=re.DOTALL,
    )
    if ("../../task-briefs/" in result or "/home/ben/" in result
            or 'action="/refresh"' in result or 'id="local-refresh-progress"' in result
            or f'href="{local_url}"' not in result):
        raise ValueError("the published page still contains a private local path")
    required = (
        ('<h2>Queue history</h2>', 'href="/"') if history else
        ('<h2>Worker lanes</h2>', '<h2>Configured review queue</h2>', 'href="/queue-history.html"')
    )
    if any(marker not in result for marker in required):
        raise ValueError("the rendered status page is incomplete")
    return result


def progress_public_html(source: str) -> str:
    if ('<h2>Programme tracks</h2>' not in source or '<h2>Implementation by domain</h2>' not in source
            or 'href="/"' not in source or "<form" in source
            or "/home/ben/" in source or "../../task-briefs/" in source):
        raise ValueError("the project map is incomplete or contains a private local path")
    return source


def review_documents(linked_documents: str, directory: Path | None = None) -> dict[str, str]:
    """Read only detail pages linked by the current queue or its history page."""
    review_dir = REVIEW_SOURCE if directory is None else directory
    pr_numbers = sorted({int(match) for match in REVIEW_LINK.findall(linked_documents)})
    if not pr_numbers:
        return {}
    if review_dir.is_symlink() or not review_dir.is_dir():
        raise ValueError("the rendered review detail directory is unavailable")
    review_root = review_dir.resolve(strict=True)
    pages: dict[str, str] = {}
    for pr_number in pr_numbers:
        source = review_dir / f"pr-{pr_number}.html"
        if source.is_symlink() or not source.is_file():
            raise ValueError(f"the rendered review detail page for PR {pr_number} is unavailable")
        if source.resolve(strict=True).parent != review_root:
            raise ValueError(f"the rendered review detail page for PR {pr_number} is outside its directory")
        pages[f"review-pr-{pr_number}.html"] = source.read_text(encoding="utf-8")
    return pages


def resources(
    document: str,
    progress_document: str | None = None,
    review_pages: dict[str, str] | None = None,
    history_document: str | None = None,
) -> tuple[dict, dict]:
    pages = {"index.html": document}
    if progress_document is not None:
        pages["progress.html"] = progress_document
    if history_document is not None:
        pages["queue-history.html"] = history_document
    if review_pages is not None:
        for key, content in review_pages.items():
            if not re.fullmatch(r"review-pr-[1-9]\d*\.html", key):
                raise ValueError("review page has an invalid ConfigMap key")
            pages[key] = content
    pages.update({name: (ROOT / "assets" / name).read_text(encoding="utf-8") for name in ASSET_FILES})
    digest = hashlib.sha256(json.dumps(pages, sort_keys=True).encode()).hexdigest()
    mounted_paths = {
        key: (f"review/{key.removeprefix('review-')}" if key.startswith("review-pr-") else key)
        for key in pages
    }
    labels = {"app": "firemud-status-page"}
    namespace = {"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": NAMESPACE}}
    objects = {
        "apiVersion": "v1",
        "kind": "List",
        "items": [
            {
                "apiVersion": "v1",
                "kind": "ConfigMap",
                "metadata": {"name": "status-page-html", "namespace": NAMESPACE, "labels": labels},
                "data": pages,
            },
            {
                "apiVersion": "apps/v1",
                "kind": "Deployment",
                "metadata": {"name": "status-page", "namespace": NAMESPACE, "labels": labels},
                "spec": {
                    "replicas": 1,
                    "selector": {"matchLabels": labels},
                    "template": {
                        "metadata": {"labels": labels, "annotations": {"status.firemud.dev/content-sha256": digest}},
                        "spec": {
                            "automountServiceAccountToken": False,
                            "containers": [
                                {
                                    "name": "static",
                                    "image": IMAGE,
                                    "imagePullPolicy": "IfNotPresent",
                                    "ports": [{"containerPort": 80}],
                                    "readinessProbe": {"httpGet": {"path": "/", "port": 80}, "initialDelaySeconds": 2},
                                    "resources": {
                                        "requests": {"cpu": "10m", "memory": "32Mi"},
                                        "limits": {"cpu": "100m", "memory": "128Mi"},
                                    },
                                    "volumeMounts": [
                                        {"name": "html", "mountPath": "/usr/share/nginx/html", "readOnly": True}
                                    ],
                                }
                            ],
                            "volumes": [{
                                "name": "html",
                                "configMap": {
                                    "name": "status-page-html",
                                    "items": [{"key": key, "path": path} for key, path in mounted_paths.items()],
                                },
                            }],
                        },
                    },
                },
            },
            {
                "apiVersion": "v1",
                "kind": "Service",
                "metadata": {"name": "status-page", "namespace": NAMESPACE, "labels": labels},
                "spec": {"selector": labels, "ports": [{"port": 80, "targetPort": 80}]},
            },
            {
                "apiVersion": "networking.k8s.io/v1",
                "kind": "Ingress",
                "metadata": {
                    "name": "status-page",
                    "namespace": NAMESPACE,
                    "annotations": {"cert-manager.io/cluster-issuer": "letsencrypt-prod"},
                },
                "spec": {
                    "ingressClassName": "traefik",
                    "rules": [
                        {
                            "host": HOST,
                            "http": {
                                "paths": [
                                    {
                                        "path": "/",
                                        "pathType": "Prefix",
                                        "backend": {"service": {"name": "status-page", "port": {"number": 80}}},
                                    }
                                ]
                            },
                        }
                    ],
                    "tls": [{"hosts": [HOST], "secretName": "status-page-tls"}],
                },
            },
        ],
    }
    return namespace, objects


def write_public_copy(document: str) -> None:
    """Replace the local published snapshot in one filesystem operation."""
    PUBLIC_COPY.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", dir=PUBLIC_COPY.parent,
            prefix=f".{PUBLIC_COPY.name}.", suffix=".tmp", delete=False,
        ) as temporary:
            temporary.write(document)
            temporary_path = Path(temporary.name)
        os.replace(temporary_path, PUBLIC_COPY)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def _apply(document: dict, force_conflicts: bool = False) -> None:
    command = [
        "ssh", "-i", str(SSH_KEY), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
        "-o", "ConnectTimeout=7", SSH_TARGET, "kubectl", "apply", "--server-side",
        "--field-manager=kubectl-client-side-apply",
    ]
    if force_conflicts:
        command.append("--force-conflicts")
    command.extend(["-f", "-"])
    completed = subprocess.run(
        command,
        input=json.dumps(document),
        text=True,
        capture_output=True,
        timeout=60,
        check=False,
    )
    if completed.returncode:
        raise RuntimeError(f"remote apply failed: {completed.stderr.strip()}")
    print(completed.stdout.strip())


def apply(document: dict) -> None:
    _apply(document)


def apply_status_config_map(document: dict) -> None:
    metadata = document.get("metadata", {})
    if (document.get("kind") != "ConfigMap" or metadata.get("name") != "status-page-html"
            or metadata.get("namespace") != NAMESPACE):
        raise ValueError("force-conflicts is limited to the status-page-html ConfigMap")
    _apply(document, force_conflicts=True)


def verify_status_config_map(document: dict) -> None:
    command = [
        "ssh", "-i", str(SSH_KEY), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
        "-o", "ConnectTimeout=7", SSH_TARGET, "kubectl", "-n", NAMESPACE,
        "get", "configmap", "status-page-html", "-o", "json",
    ]
    try:
        completed = subprocess.run(
            command, capture_output=True, text=True, timeout=30, check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise RuntimeError("status-page ConfigMap readback failed") from error
    if completed.returncode:
        raise RuntimeError("status-page ConfigMap readback failed")
    try:
        actual = json.loads(completed.stdout).get("data")
    except (AttributeError, json.JSONDecodeError):
        raise RuntimeError("status-page ConfigMap readback was invalid") from None
    if actual != document.get("data"):
        raise RuntimeError("status-page ConfigMap readback did not match the submitted snapshot")


def separate_status_config_map(objects: dict) -> tuple[dict, dict]:
    items = objects.get("items", [])
    config_maps = [
        item for item in items
        if (item.get("kind") == "ConfigMap" and item.get("metadata", {}).get("name") == "status-page-html"
            and item.get("metadata", {}).get("namespace") == NAMESPACE)
    ]
    if len(config_maps) != 1:
        raise ValueError("the snapshot must contain exactly one status-page-html ConfigMap")
    remaining = [item for item in items if item is not config_maps[0]]
    return config_maps[0], {**objects, "items": remaining}


def publish_snapshot(document: str, namespace: dict, objects: dict, dry_run: bool = False) -> None:
    if dry_run:
        write_public_copy(document)
        return
    config_map, remaining = separate_status_config_map(objects)
    apply(namespace)
    apply_status_config_map(config_map)
    verify_status_config_map(config_map)
    apply(remaining)
    rollout = subprocess.run(
        [
            "ssh", "-i", str(SSH_KEY), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "ConnectTimeout=7", SSH_TARGET, "kubectl", "-n", NAMESPACE,
            "rollout", "status", "deployment/status-page", "--timeout=120s",
        ],
        capture_output=True,
        text=True,
        timeout=140,
        check=False,
    )
    if rollout.returncode:
        raise RuntimeError(f"status-page rollout failed: {rollout.stderr.strip()}")
    print(rollout.stdout.strip())
    write_public_copy(document)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="prepare the public copy without remote changes")
    args = parser.parse_args()
    document = public_html(SOURCE.read_text(encoding="utf-8"), local_wifi_url())
    progress_document = progress_public_html(PROGRESS_SOURCE.read_text(encoding="utf-8"))
    history_document = public_html(HISTORY_SOURCE.read_text(encoding="utf-8"), local_wifi_url(), history=True)
    detail_pages = review_documents(document + history_document)
    namespace, objects = resources(document, progress_document, detail_pages, history_document)
    print(f"Prepared {len(document.encode()):,} bytes for https://{HOST}/")
    publish_snapshot(document, namespace, objects, args.dry_run)


if __name__ == "__main__":
    main()
