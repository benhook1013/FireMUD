from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT = (
    Path(__file__).resolve().parents[1]
    / "hosted/preview/write-playable-proof.py"
)
SPEC = importlib.util.spec_from_file_location("write_playable_proof", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
proof = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(proof)


BASE_SHA = "1" * 40
HEAD_SHA = "2" * 40
MERGE_SHA = "3" * 40
IMAGE_TAG = "pr-merge-" + MERGE_SHA
IMAGE_DIGEST = "a" * 64


def make_namespace(*, uid: str = "runtime-uid", head: str = HEAD_SHA, image_tag: str = IMAGE_TAG):
    return {
        "apiVersion": "v1",
        "kind": "Namespace",
        "metadata": {
            "name": "pr-42",
            "uid": uid,
            "labels": {
                proof.EXPOSURE_LABEL: "public",
                proof.PREVIEW_LABEL: "true",
                proof.PR_NUMBER_LABEL: "42",
            },
            "annotations": {
                proof.REQUESTED_HEAD_ANNOTATION: head,
                proof.DEPLOYED_HEAD_ANNOTATION: head,
                proof.REQUESTED_BASE_ANNOTATION: BASE_SHA,
                proof.DEPLOYED_BASE_ANNOTATION: BASE_SHA,
                proof.REQUESTED_MERGE_ANNOTATION: MERGE_SHA,
                proof.DEPLOYED_MERGE_ANNOTATION: MERGE_SHA,
                proof.REQUESTED_IMAGE_ANNOTATION: image_tag,
                proof.EXPECTED_IMAGE_ANNOTATION: image_tag,
                proof.TELNET_PORT_ANNOTATION: "32000",
            },
        },
    }


def make_identity(*, namespace_uid: str = "runtime-uid", head: str = HEAD_SHA):
    return {
        "apiVersion": "platform.firemud.dev/v1alpha1",
        "kind": "HostedEnvironmentIdentity",
        "metadata": {"name": "pr-42", "generation": 4},
        "status": {
            "phase": "Ready",
            "observedGeneration": 4,
            "conditions": [
                {"type": "Ready", "status": "True", "observedGeneration": 4}
            ],
            "profile": {
                "runtimeNamespaceUid": namespace_uid,
                "requestedHeadSha": head,
                "deployedHeadSha": head,
                "exposureMode": "public",
                "telnetPort": 32000,
            },
            "ingress": {"revision": "sha256:" + "b" * 64},
            "telnet": {"revision": "sha256:" + "c" * 64},
            "gatewayInternalWs": {"revision": "sha256:" + "d" * 64},
            "tcpProxyBridge": {"revision": "sha256:" + "e" * 64},
            "grpc": {"revision": "sha256:" + "f" * 64},
        },
    }


def make_pods(*, image_tag: str = IMAGE_TAG, include_digest: bool = True):
    items = []
    for service in proof.SERVICE_IMAGES:
        image_id = (
            f"containerd://{proof.APP_IMAGE_PREFIX}{service}@sha256:{IMAGE_DIGEST}"
            if include_digest
            else f"containerd://{proof.APP_IMAGE_PREFIX}{service}"
        )
        items.append(
            {
                "kind": "Pod",
                "metadata": {
                    "name": f"{service}-abcde",
                    "namespace": "pr-42",
                    "labels": {"app": service},
                },
                "spec": {
                    "containers": [
                        {
                            "name": service,
                            "image": f"{proof.APP_IMAGE_PREFIX}{service}:{image_tag}",
                            "env": [{"name": "IGNORED", "value": "never-copy"}],
                        }
                    ]
                },
                "status": {
                    "phase": "Running",
                    "conditions": [{"type": "Ready", "status": "True"}],
                    "containerStatuses": [
                        {"name": service, "ready": True, "imageID": image_id}
                    ],
                },
            }
        )
    return {"apiVersion": "v1", "kind": "PodList", "items": items}


def build(**overrides):
    values = {
        "pr_number": "42",
        "base_sha": BASE_SHA,
        "head_sha": HEAD_SHA,
        "merge_sha": MERGE_SHA,
        "image_tag": IMAGE_TAG,
        "namespace": make_namespace(),
        "identity": make_identity(),
        "pods": make_pods(),
        "telnet_passed": True,
        "wss_passed": True,
    }
    values.update(overrides)
    return proof.build_record(**values)


class WritePlayableProofTests(unittest.TestCase):
    def test_builds_bounded_record_from_ready_public_runtime(self):
        record = build()

        self.assertEqual(record["identity"]["headSha"], HEAD_SHA)
        self.assertEqual(record["namespace"]["uid"], "runtime-uid")
        self.assertEqual(record["namespace"]["exposureMode"], "public")
        self.assertEqual(
            record["proofType"], "hosted-pr-transport-diagnostic"
        )
        self.assertEqual(
            record["outcomes"],
            {
                "publicTelnetDiagnosticPassed": True,
                "firstPartyWssDiagnosticPassed": True,
            },
        )
        self.assertEqual(len(record["runtimeImages"]), len(proof.SERVICE_IMAGES))
        self.assertTrue(all(image["digest"] == f"sha256:{IMAGE_DIGEST}" for image in record["runtimeImages"]))
        self.assertNotIn("never-copy", str(record))
        self.assertNotIn("items", record)

    def test_rejects_stale_head(self):
        with self.assertRaisesRegex(ValueError, "requested head SHA is stale"):
            build(namespace=make_namespace(head="4" * 40))

    def test_rejects_missing_namespace_uid(self):
        with self.assertRaisesRegex(ValueError, "Namespace UID"):
            build(namespace=make_namespace(uid=""))

    def test_rejects_missing_runtime_image_digest(self):
        with self.assertRaisesRegex(ValueError, "no identifiable SHA-256 digest"):
            build(pods=make_pods(include_digest=False))

    def test_rejects_wrong_namespace_image_tag(self):
        with self.assertRaisesRegex(ValueError, "Namespace requested image tag"):
            build(namespace=make_namespace(image_tag="wrong-tag"))

    def test_rejects_missing_or_stale_merge_namespace_evidence(self):
        missing = make_namespace()
        del missing["metadata"]["annotations"][proof.REQUESTED_MERGE_ANNOTATION]
        with self.assertRaisesRegex(ValueError, "requested-preview-merge-sha"):
            build(namespace=missing)
        stale = make_namespace()
        stale["metadata"]["annotations"][proof.DEPLOYED_MERGE_ANNOTATION] = "4" * 40
        with self.assertRaisesRegex(ValueError, "last-preview-merge-sha is stale"):
            build(namespace=stale)

    def test_rejects_wrong_runtime_image_tag(self):
        with self.assertRaisesRegex(ValueError, "does not match the trusted image tag"):
            build(pods=make_pods(image_tag="wrong-tag"))

    def test_rejects_missing_controller_revision(self):
        identity = make_identity()
        del identity["status"]["tcpProxyBridge"]
        with self.assertRaisesRegex(ValueError, "tcpProxyBridge projection"):
            build(identity=identity)

    def test_rejects_namespace_without_exact_preview_ownership_labels(self):
        namespace = make_namespace()
        del namespace["metadata"]["labels"][proof.PR_NUMBER_LABEL]
        with self.assertRaisesRegex(ValueError, "PR label"):
            build(namespace=namespace)

    def test_rejects_absent_transport_proof(self):
        with self.assertRaisesRegex(ValueError, "public Telnet and first-party WSS diagnostic"):
            build(telnet_passed=None)
        with self.assertRaisesRegex(ValueError, "public Telnet and first-party WSS diagnostic"):
            build(wss_passed=None)


if __name__ == "__main__":
    unittest.main()
