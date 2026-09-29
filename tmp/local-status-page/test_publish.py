import importlib.util
import base64
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).with_name("publish-hetzner.py")
SPEC = importlib.util.spec_from_file_location("local_status_publish", MODULE_PATH)
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class PublishedPageTest(unittest.TestCase):
    def test_only_queue_and_history_linked_review_pages_are_published_at_nested_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            review_dir = Path(temporary)
            (review_dir / "pr-41.html").write_text("public detail 41", encoding="utf-8")
            (review_dir / "pr-42.html").write_text("public detail 42", encoding="utf-8")
            (review_dir / "pr-43.html").write_text("stale detail 43", encoding="utf-8")
            index = '<a href="review/pr-41.html">PR 41</a>'
            history = '<a href="review/pr-42.html">PR 42</a>'

            review_pages = publisher.review_documents(index + history, review_dir)
            _, objects = publisher.resources(index, review_pages=review_pages)
            config_map = objects["items"][0]
            mounts = objects["items"][1]["spec"]["template"]["spec"]["volumes"][0]["configMap"]["items"]

        self.assertEqual(review_pages, {"review-pr-41.html": "public detail 41",
                                        "review-pr-42.html": "public detail 42"})
        self.assertEqual(sum(item["kind"] == "ConfigMap" for item in objects["items"]), 1)
        self.assertEqual(config_map["data"]["review-pr-41.html"], "public detail 41")
        self.assertNotIn("review-pr-43.html", config_map["data"])
        self.assertIn({"key": "review-pr-41.html", "path": "review/pr-41.html"}, mounts)
        self.assertIn({"key": "review-pr-42.html", "path": "review/pr-42.html"}, mounts)
        self.assertNotIn({"key": "review-pr-43.html", "path": "review/pr-43.html"}, mounts)
        self.assertTrue(all("/" not in key for key in config_map["data"]))

    def test_apply_uses_server_side_field_ownership_for_large_snapshots(self):
        document = {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {"name": "status-page-html", "namespace": publisher.NAMESPACE},
            "data": {"index.html": "x" * 270_000},
        }
        completed = publisher.subprocess.CompletedProcess([], 0, stdout="applied", stderr="")
        with mock.patch.object(publisher.subprocess, "run", return_value=completed) as run:
            publisher.apply(document)

        command = run.call_args.args[0]
        submitted = json.loads(run.call_args.kwargs["input"])
        self.assertIn("--server-side", command)
        self.assertIn("--field-manager=kubectl-client-side-apply", command)
        self.assertNotIn("--force-conflicts", command)
        self.assertGreater(len(submitted["data"]["index.html"]), 262_144)
        self.assertNotIn("kubectl.kubernetes.io/last-applied-configuration",
                         submitted["metadata"].get("annotations", {}))

    def test_force_conflicts_is_limited_to_the_status_config_map(self):
        config_map = {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {"name": "status-page-html", "namespace": publisher.NAMESPACE},
            "data": {"index.html": "new snapshot"},
        }
        completed = publisher.subprocess.CompletedProcess([], 0, stdout="applied", stderr="")
        with mock.patch.object(publisher.subprocess, "run", return_value=completed) as run:
            publisher.apply_status_config_map(config_map)
            command = run.call_args.args[0]

        self.assertIn("--server-side", command)
        self.assertIn("--force-conflicts", command)
        self.assertIn("--field-manager=kubectl-client-side-apply", command)
        self.assertEqual(command[command.index("apply") + 1:command.index("-f")], [
            "--server-side", "--field-manager=kubectl-client-side-apply", "--force-conflicts",
        ])
        with mock.patch.object(publisher.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "limited to the status-page-html ConfigMap"):
                publisher.apply_status_config_map({
                    "apiVersion": "apps/v1", "kind": "Deployment",
                    "metadata": {"name": "status-page", "namespace": publisher.NAMESPACE},
                })
            run.assert_not_called()

    def test_publish_applies_single_config_map_before_unforced_remaining_objects(self):
        config_map = {"kind": "ConfigMap", "metadata": {"name": "status-page-html",
                                                           "namespace": publisher.NAMESPACE},
                      "data": {"index.html": "snapshot"}}
        deployment = {"kind": "Deployment", "metadata": {"name": "status-page"}}
        objects = {"kind": "List", "items": [config_map, deployment]}
        order = []
        rollout = publisher.subprocess.CompletedProcess([], 0, stdout="ready", stderr="")
        with tempfile.TemporaryDirectory() as temporary:
            with mock.patch.object(publisher, "PUBLIC_COPY", Path(temporary) / "public-index.html"), \
                    mock.patch.object(publisher, "apply", side_effect=lambda value: order.append(("apply", value))), \
                    mock.patch.object(publisher, "apply_status_config_map",
                                      side_effect=lambda value: order.append(("forced", value))), \
                    mock.patch.object(publisher, "verify_status_config_map",
                                      side_effect=lambda value: order.append(("verified", value))), \
                    mock.patch.object(publisher.subprocess, "run", return_value=rollout):
                publisher.publish_snapshot("snapshot", {"kind": "Namespace"}, objects)

        self.assertEqual([kind for kind, _ in order], ["apply", "forced", "verified", "apply"])
        self.assertEqual(order[0][1], {"kind": "Namespace"})
        self.assertEqual(order[1][1], config_map)
        self.assertEqual(order[2][1], config_map)
        self.assertEqual(order[3][1], {"kind": "List", "items": [deployment]})

    def test_stale_configmap_readback_stops_publish_after_false_successful_apply(self):
        config_map = {"kind": "ConfigMap", "metadata": {"name": "status-page-html",
                                                           "namespace": publisher.NAMESPACE},
                      "data": {"index.html": "new snapshot"}}
        deployment = {"kind": "Deployment", "metadata": {"name": "status-page"}}
        objects = {"kind": "List", "items": [config_map, deployment]}
        stale_readback = publisher.subprocess.CompletedProcess(
            [], 0, stdout=json.dumps({"data": {"index.html": "old snapshot"}}), stderr="",
        )
        with tempfile.TemporaryDirectory() as temporary:
            public_copy = Path(temporary) / "public-index.html"
            public_copy.write_text("previous public snapshot", encoding="utf-8")
            with mock.patch.object(publisher, "PUBLIC_COPY", public_copy), \
                    mock.patch.object(publisher, "apply") as apply, \
                    mock.patch.object(publisher, "apply_status_config_map",
                                      return_value=None) as false_successful_apply, \
                    mock.patch.object(publisher.subprocess, "run", return_value=stale_readback) as run:
                with self.assertRaisesRegex(RuntimeError, "readback did not match"):
                    publisher.publish_snapshot("new public snapshot", {"kind": "Namespace"}, objects)

            self.assertEqual(public_copy.read_text(encoding="utf-8"), "previous public snapshot")
            apply.assert_called_once_with({"kind": "Namespace"})
            false_successful_apply.assert_called_once_with(config_map)
            run.assert_called_once()
            self.assertIn("get", run.call_args.args[0])

    def test_matching_configmap_readback_allows_remaining_apply_rollout_and_public_copy(self):
        config_map = {"kind": "ConfigMap", "metadata": {"name": "status-page-html",
                                                           "namespace": publisher.NAMESPACE},
                      "data": {"index.html": "new snapshot"}}
        deployment = {"kind": "Deployment", "metadata": {"name": "status-page"}}
        objects = {"kind": "List", "items": [config_map, deployment]}
        readback = publisher.subprocess.CompletedProcess(
            [], 0, stdout=json.dumps({"data": config_map["data"]}), stderr="",
        )
        rollout = publisher.subprocess.CompletedProcess([], 0, stdout="ready", stderr="")
        order = []
        with tempfile.TemporaryDirectory() as temporary:
            public_copy = Path(temporary) / "public-index.html"
            with mock.patch.object(publisher, "PUBLIC_COPY", public_copy), \
                    mock.patch.object(publisher, "apply", side_effect=lambda value: order.append(("apply", value))), \
                    mock.patch.object(publisher, "apply_status_config_map",
                                      side_effect=lambda value: order.append(("forced", value))), \
                    mock.patch.object(publisher.subprocess, "run", side_effect=[readback, rollout]) as run:
                publisher.publish_snapshot("new public snapshot", {"kind": "Namespace"}, objects)

            self.assertEqual(public_copy.read_text(encoding="utf-8"), "new public snapshot")
            self.assertEqual([kind for kind, _ in order], ["apply", "forced", "apply"])
            self.assertEqual(order[2][1], {"kind": "List", "items": [deployment]})
            self.assertEqual(run.call_count, 2)
            self.assertIn("get", run.call_args_list[0].args[0])
            self.assertIn("rollout", run.call_args_list[1].args[0])

    def test_failed_configmap_apply_preserves_previous_public_copy(self):
        with tempfile.TemporaryDirectory() as temporary:
            public_copy = Path(temporary) / "public-index.html"
            public_copy.write_text("previous public snapshot", encoding="utf-8")
            with mock.patch.object(publisher, "PUBLIC_COPY", public_copy), \
                    mock.patch.object(publisher, "apply", return_value=None) as apply, \
                    mock.patch.object(publisher, "apply_status_config_map",
                                      side_effect=RuntimeError("apply rejected")) as apply_config_map:
                with self.assertRaisesRegex(RuntimeError, "apply rejected"):
                    publisher.publish_snapshot("new snapshot", {"kind": "Namespace"}, {
                        "kind": "List", "items": [
                            {"kind": "ConfigMap", "metadata": {"name": "status-page-html",
                                                                  "namespace": publisher.NAMESPACE}},
                            {"kind": "Deployment", "metadata": {"name": "status-page"}},
                        ],
                    })

            self.assertEqual(public_copy.read_text(encoding="utf-8"), "previous public snapshot")
            apply.assert_called_once_with({"kind": "Namespace"})
            apply_config_map.assert_called_once()

    def test_missing_index_linked_review_page_rejects_incomplete_snapshot(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "PR 43 is unavailable"):
                publisher.review_documents('<a href="review/pr-43.html">PR 43</a>', Path(temporary))

    def test_project_map_is_published_with_navigation(self):
        progress = ('<html><a href="/">PR delivery</a>'
                    '<h2>Programme tracks</h2><h2>Implementation by domain</h2></html>')
        _, objects = publisher.resources("<html>status</html>", publisher.progress_public_html(progress))
        self.assertEqual(objects["items"][0]["data"]["progress.html"], progress)
        with self.assertRaises(ValueError):
            publisher.progress_public_html(progress + '/home/ben/private')

    def test_public_resources_include_icon_gallery_and_favicon(self):
        _, objects = publisher.resources("<html>status</html>")
        pages = objects["items"][0]["data"]
        self.assertIn("icon-options.html", pages)
        self.assertIn("flame-ember.svg", pages)
        self.assertIn('src="/flame-ember.svg"', pages["icon-options.html"])
        self.assertIn('<svg xmlns="http://www.w3.org/2000/svg"', pages["flame-ember.svg"])

    def test_local_refresh_url_uses_static_windows_lan_address(self):
        with mock.patch.object(publisher.subprocess, "run", side_effect=AssertionError("unexpected subprocess")) as run:
            self.assertEqual(publisher.local_wifi_url(), "http://192.168.50.100:8877/")
            run.assert_not_called()

    def test_public_copy_links_to_local_page_without_exposing_refresh_endpoint(self):
        age_bootstrap_script = "document.documentElement.classList.add('age-pending');"
        age_script = "read-only relative age behavior"
        snapshot_script = "read-only snapshot polling behavior"
        source = (
            '<meta name="status-snapshot" content="2026-09-24T12:00:00+00:00">'
            '<meta http-equiv="Content-Security-Policy" content="script-src \'sha256-abc\' \'sha256-def\'; connect-src \'self\'; form-action \'self\'">'
            '<form class="refresh-form" action="/refresh" method="post"><button>Refresh</button></form>'
            '<span class="refresh-time">Refreshed <time datetime="2026-09-24T12:00:00Z">just now</time></span>'
            '<script id="local-refresh-progress">fetch("/refresh-status")</script>'
            '<span class="round-pill" aria-label="2/2, Completed 24 Sep 2026 23:46 NZST" '
            'title="Completed 24 Sep 2026 23:46 NZST"><span>2/2</span>'
            '<time class="round-age" datetime="2026-09-24T11:46:00+00:00">14m</time></span>'
            f'<script id="age-pending-bootstrap">{age_bootstrap_script}</script>'
            f'<script id="relative-age-updates">{age_script}</script>'
            f'<script id="snapshot-updates">{snapshot_script}</script>'
            '<h2>Worker lanes</h2><h2>Configured review queue</h2><a href="/queue-history.html">Queue history</a>'
            '<a class="local-public-link" href="https://status.preview.firedevops.net/">Public Site</a>'
            '<footer>Local refresh instructions</footer>'
        )
        result = publisher.public_html(source, "http://192.168.50.100:8877/")
        history_source = source.replace('<h2>Worker lanes</h2><h2>Configured review queue</h2>'
                                        '<a href="/queue-history.html">Queue history</a>',
                                        '<h2>Queue history</h2><a href="/">Delivery</a>')
        history = publisher.public_html(history_source, "http://192.168.50.100:8877/", history=True)
        self.assertIn('<h2>Queue history</h2>', history)
        self.assertNotIn('action="/refresh"', history)
        _, objects = publisher.resources(result, history_document=history)
        self.assertEqual(objects["items"][0]["data"]["queue-history.html"], history)
        age_bootstrap_hash = base64.b64encode(hashlib.sha256(age_bootstrap_script.encode()).digest()).decode()
        age_hash = base64.b64encode(hashlib.sha256(age_script.encode()).digest()).decode()
        snapshot_hash = base64.b64encode(hashlib.sha256(snapshot_script.encode()).digest()).decode()
        self.assertIn('href="http://192.168.50.100:8877/"', result)
        self.assertNotIn('class="local-public-link"', result)
        self.assertNotIn('action="/refresh"', result)
        self.assertNotIn('<form class="refresh-form"', result)
        self.assertIn('<span class="refresh-time">Refreshed '
                      '<time datetime="2026-09-24T12:00:00Z">just now</time></span>', result)
        self.assertIn("form-action 'none'", result)
        self.assertIn("connect-src 'self'", result)
        self.assertIn(
            f"script-src 'sha256-{age_bootstrap_hash}' 'sha256-{age_hash}' 'sha256-{snapshot_hash}'", result
        )
        self.assertNotIn("sha256-abc", result)
        self.assertNotIn('id="local-refresh-progress"', result)
        self.assertNotIn('/refresh-status', result)
        self.assertIn(f'<script id="relative-age-updates">{age_script}</script>', result)
        self.assertIn(f'<script id="age-pending-bootstrap">{age_bootstrap_script}</script>', result)
        self.assertIn(f'<script id="snapshot-updates">{snapshot_script}</script>', result)
        self.assertIn('<time class="round-age" datetime="2026-09-24T11:46:00+00:00">14m</time>', result)
        self.assertIn('aria-label="2/2, Completed 24 Sep 2026 23:46 NZST"', result)
        self.assertNotIn("Local refresh instructions", result)
        self.assertNotIn("Private local snapshot", result)

    def test_public_copy_rejects_missing_relative_age_script(self):
        with self.assertRaisesRegex(ValueError, "read-only relative-time script"):
            publisher.public_html('<span class="refresh-time">Refreshed now</span>'
                                  '<script id="age-pending-bootstrap">bootstrap</script>'
                                  '<h2>Worker lanes</h2><h2>Configured review queue</h2>', "http://192.168.50.100:8877/")

    def test_public_copy_rejects_missing_age_bootstrap_script(self):
        with self.assertRaisesRegex(ValueError, "age bootstrap script"):
            publisher.public_html('<span class="refresh-time">Refreshed now</span>'
                                  '<script id="relative-age-updates">age</script>'
                                  '<script id="snapshot-updates">snapshot</script>'
                                  '<h2>Worker lanes</h2><h2>Configured review queue</h2>',
                                  "http://192.168.50.100:8877/")

    def test_public_copy_rejects_missing_snapshot_script(self):
        with self.assertRaisesRegex(ValueError, "read-only snapshot script"):
            publisher.public_html('<span class="refresh-time">Refreshed now</span>'
                                  '<script id="age-pending-bootstrap">bootstrap</script>'
                                  '<script id="relative-age-updates">age</script>', "http://192.168.50.100:8877/")

    def test_public_copy_rejects_refresh_form_without_timestamp(self):
        source = '<form class="refresh-form" action="/refresh" method="post"><button>Refresh</button></form>'
        with self.assertRaisesRegex(ValueError, "read-only refresh timestamp"):
            publisher.public_html(source, "http://192.168.50.100:8877/")


if __name__ == "__main__":
    unittest.main()
