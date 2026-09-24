#!/usr/bin/env python3
"""Build a bounded, non-secret record for trusted hosted preview proof."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, NoReturn


SHA_RE = re.compile(r"^[0-9a-fA-F]{40}$")
IMAGE_TAG_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$")
UID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
SAFE_REVISION_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:+/-]{0,127}$")
IMAGE_ID_RE = re.compile(r"^(?:[A-Za-z][A-Za-z0-9+.-]*://)?(.+)@sha256:([0-9a-f]{64})$")
SERVICE_IMAGES = (
    "account-service",
    "automation-scripting-service",
    "entity-management-service",
    "game-design-service",
    "game-logic-service",
    "game-session-service",
    "logging-admin-service",
    "social-groups-service",
    "spring-cloud-gateway",
    "tcp-proxy-service",
    "world-management-service",
)
APP_IMAGE_PREFIX = "ghcr.io/benhook1013/"
EXPECTED_IMAGE_ANNOTATION = "firemud.dev/last-preview-image-tag"
REQUESTED_HEAD_ANNOTATION = "firemud.dev/requested-preview-head-sha"
DEPLOYED_HEAD_ANNOTATION = "firemud.dev/last-preview-head-sha"
REQUESTED_BASE_ANNOTATION = "firemud.dev/requested-preview-base-sha"
DEPLOYED_BASE_ANNOTATION = "firemud.dev/last-preview-base-sha"
REQUESTED_MERGE_ANNOTATION = "firemud.dev/requested-preview-merge-sha"
DEPLOYED_MERGE_ANNOTATION = "firemud.dev/last-preview-merge-sha"
REQUESTED_IMAGE_ANNOTATION = "firemud.dev/requested-preview-image-tag"
EXPOSURE_LABEL = "firemud.dev/preview-exposure-mode"
PREVIEW_LABEL = "firemud.dev/preview"
PR_NUMBER_LABEL = "firemud.dev/pr-number"
TELNET_PORT_ANNOTATION = "firemud.dev/last-preview-telnet-port"
MAX_INPUT_BYTES = 2 * 1024 * 1024
MAX_APP_PODS = 128


def fail(message: str) -> NoReturn:
    raise ValueError(message)


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            fail("JSON input contains duplicate object keys")
        value[key] = item
    return value


def _mapping(value: object, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(f"{label} must be a JSON object")
    return value


def _string(value: object, label: str, *, maximum: int = 256) -> str:
    if (
        not isinstance(value, str)
        or not value
        or len(value) > maximum
        or value.strip() != value
        or any(ord(character) < 0x20 for character in value)
    ):
        fail(f"{label} must be a bounded nonempty string")
    return value


def _sha(value: object, label: str) -> str:
    if not isinstance(value, str) or SHA_RE.fullmatch(value) is None:
        fail(f"{label} must be exactly 40 hexadecimal characters")
    return value.lower()


def _positive_integer(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        fail(f"{label} must be a positive integer")
    return value


def _metadata(document: object, label: str) -> dict[str, Any]:
    return _mapping(_mapping(document, label).get("metadata"), f"{label}.metadata")


def _read_json(path: str, label: str, stdin_bytes: bytes | None) -> object:
    try:
        if path == "-":
            if stdin_bytes is None:
                fail("only one Kubernetes JSON input may use stdin")
            payload = stdin_bytes
        else:
            with Path(path).open("rb") as stream:
                payload = stream.read(MAX_INPUT_BYTES + 1)
    except OSError as exc:
        raise ValueError(f"could not read {label} JSON input") from exc
    if len(payload) > MAX_INPUT_BYTES:
        fail(f"{label} JSON input exceeds the size limit")
    try:
        return json.loads(payload.decode("utf-8"), object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label} input must be valid UTF-8 JSON") from exc


def _validate_expected(
    pr_number: object,
    base_sha: object,
    head_sha: object,
    merge_sha: object,
    image_tag: object,
) -> dict[str, object]:
    if (
        not isinstance(pr_number, str)
        or re.fullmatch(r"[1-9][0-9]{0,50}", pr_number) is None
    ):
        fail("trusted PR number must be a canonical positive decimal integer")
    if not isinstance(image_tag, str) or IMAGE_TAG_RE.fullmatch(image_tag) is None:
        fail("trusted image tag is malformed")
    return {
        "prNumber": int(pr_number),
        "baseSha": _sha(base_sha, "trusted base SHA"),
        "headSha": _sha(head_sha, "trusted head SHA"),
        "mergeSha": _sha(merge_sha, "trusted merge SHA"),
        "imageTag": image_tag,
    }


def _validate_namespace(namespace: object, expected: dict[str, object]) -> dict[str, object]:
    namespace_obj = _mapping(namespace, "Namespace")
    if namespace_obj.get("kind") != "Namespace":
        fail("namespace input must be a Kubernetes Namespace")
    metadata = _metadata(namespace_obj, "Namespace")
    namespace_name = f"pr-{expected['prNumber']}"
    if metadata.get("name") != namespace_name:
        fail("Namespace name does not match the trusted PR")
    namespace_uid = _string(metadata.get("uid"), "Namespace UID", maximum=128)
    if UID_RE.fullmatch(namespace_uid) is None:
        fail("Namespace UID is malformed")
    labels = _mapping(metadata.get("labels", {}), "Namespace labels")
    annotations = _mapping(metadata.get("annotations", {}), "Namespace annotations")
    if labels.get(PREVIEW_LABEL) != "true":
        fail("Namespace is not labeled as a preview")
    if labels.get(PR_NUMBER_LABEL) != str(expected["prNumber"]):
        fail("Namespace PR label does not match the trusted PR")
    exposure = labels.get(EXPOSURE_LABEL)
    if exposure != "public":
        fail("playable proof requires the public preview exposure mode")
    requested_head = _sha(
        annotations.get(REQUESTED_HEAD_ANNOTATION), "Namespace requested head SHA"
    )
    deployed_head = _sha(
        annotations.get(DEPLOYED_HEAD_ANNOTATION), "Namespace deployed head SHA"
    )
    if requested_head != expected["headSha"]:
        fail("Namespace requested head SHA is stale")
    if deployed_head != expected["headSha"]:
        fail("Namespace deployed head SHA is stale")
    for annotation, expected_field in (
        (REQUESTED_BASE_ANNOTATION, "baseSha"),
        (DEPLOYED_BASE_ANNOTATION, "baseSha"),
        (REQUESTED_MERGE_ANNOTATION, "mergeSha"),
        (DEPLOYED_MERGE_ANNOTATION, "mergeSha"),
    ):
        if _sha(annotations.get(annotation), f"Namespace {annotation}") != expected[
            expected_field
        ]:
            fail(f"Namespace {annotation} is stale")
    if annotations.get(REQUESTED_IMAGE_ANNOTATION) != expected["imageTag"]:
        fail("Namespace requested image tag does not match the trusted image tag")
    if annotations.get(EXPECTED_IMAGE_ANNOTATION) != expected["imageTag"]:
        fail("Namespace image tag does not match the trusted image tag")
    port = annotations.get(TELNET_PORT_ANNOTATION)
    if not isinstance(port, str) or re.fullmatch(r"32(?:00[0-9]|01[0-5])", port) is None:
        fail("public Namespace Telnet port is malformed or missing")
    return {
        "name": namespace_name,
        "uid": namespace_uid,
        "exposureMode": exposure,
        "telnetPort": int(port),
    }


def _validate_identity(
    identity: object,
    expected: dict[str, object],
    namespace_record: dict[str, object],
) -> dict[str, object]:
    identity_obj = _mapping(identity, "HostedEnvironmentIdentity")
    if (
        identity_obj.get("apiVersion") != "platform.firemud.dev/v1alpha1"
        or identity_obj.get("kind") != "HostedEnvironmentIdentity"
    ):
        fail("identity input must be a HostedEnvironmentIdentity")
    metadata = _metadata(identity_obj, "HostedEnvironmentIdentity")
    if metadata.get("name") != namespace_record["name"]:
        fail("identity name does not match the trusted PR")
    generation = _positive_integer(metadata.get("generation"), "identity generation")
    status = _mapping(identity_obj.get("status"), "identity status")
    if status.get("phase") != "Ready" or status.get("observedGeneration") != generation:
        fail("HostedEnvironmentIdentity is not Ready for its current generation")
    conditions = status.get("conditions")
    if not isinstance(conditions, list):
        fail("identity Ready condition is missing")
    ready_conditions = [
        condition
        for condition in conditions
        if isinstance(condition, dict) and condition.get("type") == "Ready"
    ]
    if len(ready_conditions) != 1:
        fail("identity Ready condition is missing or ambiguous")
    ready = ready_conditions[0]
    if ready.get("status") != "True" or ready.get("observedGeneration") != generation:
        fail("identity Ready condition is stale or false")

    profile = _mapping(status.get("profile"), "identity profile")
    if profile.get("runtimeNamespaceUid") != namespace_record["uid"]:
        fail("identity runtime Namespace UID does not match")
    if _sha(profile.get("requestedHeadSha"), "identity requested head SHA") != expected[
        "headSha"
    ]:
        fail("identity requested head SHA is stale")
    if _sha(profile.get("deployedHeadSha"), "identity deployed head SHA") != expected[
        "headSha"
    ]:
        fail("identity deployed head SHA is stale")
    if profile.get("exposureMode") != namespace_record["exposureMode"]:
        fail("identity exposure mode does not match the Namespace")
    telnet_port = profile.get("telnetPort")
    if (
        isinstance(telnet_port, bool)
        or not isinstance(telnet_port, int)
        or telnet_port != namespace_record["telnetPort"]
    ):
        fail("identity Telnet port does not match the Namespace")

    revisions: dict[str, str] = {}
    for field in (
        "ingress",
        "telnet",
        "gatewayInternalWs",
        "tcpProxyBridge",
        "grpc",
    ):
        projection = _mapping(status.get(field), f"identity {field} projection")
        revision = projection.get("revision")
        revision = _string(revision, f"identity {field} revision", maximum=128)
        if SAFE_REVISION_RE.fullmatch(revision) is None:
            fail(f"identity {field} revision is malformed")
        revisions[field] = revision
    return {"identityName": metadata["name"], "generation": generation, "revisions": revisions}


def _parse_declared_image(value: object, service: str, image_tag: str) -> str:
    expected = f"{APP_IMAGE_PREFIX}{service}:{image_tag}"
    if value != expected:
        fail(f"application image for {service} does not match the trusted image tag")
    return expected


def _validate_image_id(value: object, service: str) -> tuple[str, str]:
    image_id = _string(value, f"{service} runtime image ID", maximum=512)
    match = IMAGE_ID_RE.fullmatch(image_id)
    if match is None:
        fail(f"{service} runtime image ID has no identifiable SHA-256 digest")
    repository, digest = match.groups()
    if repository != f"{APP_IMAGE_PREFIX}{service}":
        fail(f"{service} runtime image ID does not match its approved repository")
    return image_id, f"sha256:{digest}"


def _validate_pods(
    pods: object,
    expected: dict[str, object],
    namespace_record: dict[str, object],
) -> list[dict[str, str]]:
    pod_list = _mapping(pods, "Pod list")
    if pod_list.get("kind") != "PodList" or not isinstance(pod_list.get("items"), list):
        fail("pod input must be a Kubernetes Pod List")
    if len(pod_list["items"]) > MAX_APP_PODS:
        fail("pod list exceeds the bounded item limit")

    observed: list[dict[str, str]] = []
    services_seen: set[str] = set()
    for index, value in enumerate(pod_list["items"]):
        pod = _mapping(value, f"pod item {index}")
        if pod.get("kind") not in (None, "Pod"):
            fail("pod list contains a non-Pod item")
        metadata = _metadata(pod, "Pod")
        pod_namespace = metadata.get("namespace")
        if pod_namespace != namespace_record["name"]:
            fail("pod list contains an item outside the trusted Namespace")
        labels = _mapping(metadata.get("labels", {}), "Pod labels")
        spec = _mapping(pod.get("spec"), "Pod spec")
        containers = spec.get("containers")
        if not isinstance(containers, list):
            fail("pod containers are malformed")
        matching: list[tuple[str, dict[str, Any]]] = []
        for container_value in containers:
            container = _mapping(container_value, "Pod container")
            container_name = container.get("name")
            declared_image = container.get("image")
            if container_name in SERVICE_IMAGES:
                matching.append((container_name, container))
                continue
            if (
                isinstance(declared_image, str)
                and declared_image.startswith(APP_IMAGE_PREFIX)
            ):
                service_name = declared_image[len(APP_IMAGE_PREFIX) :].split(":", 1)[0]
                if service_name not in SERVICE_IMAGES:
                    fail("pod contains an unidentifiable application image")
                matching.append((service_name, container))
        app_label = labels.get("app")
        if app_label in SERVICE_IMAGES and not matching:
            fail(f"application pod {app_label} has no identifiable application image")
        if not matching:
            continue
        if len(matching) != 1:
            fail("application pod has an ambiguous container identity")
        service, container = matching[0]
        if app_label != service or container.get("name") != service:
            fail(f"application pod identity is inconsistent for {service}")
        if metadata.get("deletionTimestamp") is not None:
            fail(f"application pod {service} is terminating")
        _parse_declared_image(container.get("image"), service, str(expected["imageTag"]))

        status = _mapping(pod.get("status"), "Pod status")
        if status.get("phase") != "Running":
            fail(f"application pod {service} is not Running")
        conditions = status.get("conditions")
        if not isinstance(conditions, list):
            fail(f"application pod {service} Ready condition is missing")
        ready_conditions = [
            condition
            for condition in conditions
            if isinstance(condition, dict) and condition.get("type") == "Ready"
        ]
        if len(ready_conditions) != 1 or ready_conditions[0].get("status") != "True":
            fail(f"application pod {service} is not Ready")
        container_statuses = status.get("containerStatuses")
        if not isinstance(container_statuses, list):
            fail(f"application pod {service} container status is missing")
        matching_statuses = [
            item
            for item in container_statuses
            if isinstance(item, dict) and item.get("name") == service
        ]
        if len(matching_statuses) != 1 or matching_statuses[0].get("ready") is not True:
            fail(f"application container {service} is not Ready")
        image_id, digest = _validate_image_id(matching_statuses[0].get("imageID"), service)
        observed.append(
            {
                "service": service,
                "image": f"{APP_IMAGE_PREFIX}{service}:{expected['imageTag']}",
                "imageId": image_id,
                "digest": digest,
            }
        )
        services_seen.add(service)

    missing = sorted(set(SERVICE_IMAGES) - services_seen)
    if missing:
        fail("application pod inventory is incomplete")
    observed.sort(key=lambda item: (item["service"], item["digest"]))
    return observed


def build_record(
    *,
    pr_number: object,
    base_sha: object,
    head_sha: object,
    merge_sha: object,
    image_tag: object,
    namespace: object,
    identity: object,
    pods: object,
    telnet_passed: object,
    wss_passed: object,
) -> dict[str, object]:
    """Validate trusted identity and observed runtime state, then build the allowlisted record."""

    expected = _validate_expected(pr_number, base_sha, head_sha, merge_sha, image_tag)
    if telnet_passed is not True or wss_passed is not True:
        fail(
            "public Telnet and first-party WSS diagnostic outcomes must be explicitly passed"
        )
    namespace_record = _validate_namespace(namespace, expected)
    identity_record = _validate_identity(identity, expected, namespace_record)
    images = _validate_pods(pods, expected, namespace_record)
    return {
        "schemaVersion": 1,
        "proofType": "hosted-pr-transport-diagnostic",
        "identity": expected,
        "namespace": namespace_record,
        "controller": identity_record,
        "runtimeImages": images,
        "outcomes": {
            "publicTelnetDiagnosticPassed": True,
            "firstPartyWssDiagnosticPassed": True,
        },
    }


def _load_inputs(args: argparse.Namespace) -> tuple[object, object, object]:
    paths = (args.namespace_json, args.identity_json, args.pods_json)
    if paths.count("-") > 1:
        fail("at most one Kubernetes JSON input may use stdin")
    stdin_bytes = sys.stdin.buffer.read(MAX_INPUT_BYTES + 1) if "-" in paths else None
    return (
        _read_json(args.namespace_json, "Namespace", stdin_bytes if args.namespace_json == "-" else None),
        _read_json(args.identity_json, "identity", stdin_bytes if args.identity_json == "-" else None),
        _read_json(args.pods_json, "pods", stdin_bytes if args.pods_json == "-" else None),
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(allow_abbrev=False)
    parser.add_argument("--pr-number", required=True)
    parser.add_argument("--base-sha", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--merge-sha", required=True)
    parser.add_argument("--image-tag", required=True)
    parser.add_argument("--namespace-json", required=True)
    parser.add_argument("--identity-json", required=True)
    parser.add_argument("--pods-json", required=True)
    parser.add_argument("--telnet-outcome", choices=("passed", "failed"), required=True)
    parser.add_argument("--wss-outcome", choices=("passed", "failed"), required=True)
    args = parser.parse_args(argv)
    try:
        namespace, identity, pods = _load_inputs(args)
        record = build_record(
            pr_number=args.pr_number,
            base_sha=args.base_sha,
            head_sha=args.head_sha,
            merge_sha=args.merge_sha,
            image_tag=args.image_tag,
            namespace=namespace,
            identity=identity,
            pods=pods,
            telnet_passed=args.telnet_outcome == "passed",
            wss_passed=args.wss_outcome == "passed",
        )
    except ValueError as exc:
        print(f"playable proof rejected: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(record, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
