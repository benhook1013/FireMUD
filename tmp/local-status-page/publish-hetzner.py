#!/usr/bin/env python3
"""Publish the rendered, brief-link-free status snapshot through preview Traefik."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "output" / "index.html"
PUBLIC_COPY = ROOT / "output" / "public-index.html"
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
AGE_SCRIPT = re.compile(r'<script id="relative-age-updates">(.*?)</script>', re.DOTALL)
SNAPSHOT_SCRIPT = re.compile(r'<script id="snapshot-updates">(.*?)</script>', re.DOTALL)


def local_wifi_url() -> str:
    return LOCAL_STATUS_URL


def public_html(source: str, local_url: str) -> str:
    result = BRIEF_LINK.sub(r"\1 (local brief)", source)
    if REFRESH_TIME.search(result) is None:
        raise ValueError("the published page needs its read-only refresh timestamp")
    result = REFRESH_FORM.sub("", result)
    result = REFRESH_SCRIPT.sub("", result)
    age_script = AGE_SCRIPT.search(result)
    if age_script is None:
        raise ValueError("the published page needs its read-only relative-time script")
    snapshot_script = SNAPSHOT_SCRIPT.search(result)
    if snapshot_script is None:
        raise ValueError("the published page needs its read-only snapshot script")
    age_hash = base64.b64encode(hashlib.sha256(age_script.group(1).encode()).digest()).decode()
    snapshot_hash = base64.b64encode(hashlib.sha256(snapshot_script.group(1).encode()).digest()).decode()
    result = result.replace("form-action 'self'", "form-action 'none'")
    result, policy_count = re.subn(
        r"script-src(?: 'sha256-[^']+')+", f"script-src 'sha256-{age_hash}' 'sha256-{snapshot_hash}'", result, count=1
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
    if "<h2>Worker lanes</h2>" not in result or "<h2>Configured review queue</h2>" not in result:
        raise ValueError("the rendered status page is incomplete")
    return result


def resources(document: str) -> tuple[dict, dict]:
    pages = {"index.html": document}
    pages.update({name: (ROOT / "assets" / name).read_text(encoding="utf-8") for name in ASSET_FILES})
    digest = hashlib.sha256(json.dumps(pages, sort_keys=True).encode()).hexdigest()
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
                            "volumes": [{"name": "html", "configMap": {"name": "status-page-html"}}],
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


def apply(document: dict) -> None:
    completed = subprocess.run(
        [
            "ssh", "-i", str(SSH_KEY), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "ConnectTimeout=7", SSH_TARGET, "kubectl", "apply", "-f", "-",
        ],
        input=json.dumps(document),
        text=True,
        capture_output=True,
        timeout=60,
        check=False,
    )
    if completed.returncode:
        raise RuntimeError(f"remote apply failed: {completed.stderr.strip()}")
    print(completed.stdout.strip())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="prepare the public copy without remote changes")
    args = parser.parse_args()
    document = public_html(SOURCE.read_text(encoding="utf-8"), local_wifi_url())
    PUBLIC_COPY.write_text(document, encoding="utf-8")
    namespace, objects = resources(document)
    print(f"Prepared {len(document.encode()):,} bytes for https://{HOST}/")
    if args.dry_run:
        return
    apply(namespace)
    apply(objects)
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


if __name__ == "__main__":
    main()
