import http.client
import importlib.util
import json
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).with_name("server.py")
SPEC = importlib.util.spec_from_file_location("local_status_server", MODULE_PATH)
server_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server_module)


class LocalRefreshServerTest(unittest.TestCase):
    def test_external_origin_must_be_an_http_origin_without_a_path(self):
        for origin in ("https://192.168.50.100:8877", "http://192.168.50.100:8877/path", "http://user@192.168.50.100:8877"):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                server_module.StatusServer(("127.0.0.1", 0), Path("."), external_origin=origin)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.index = Path(self.directory.name) / "index.html"
        self.index.write_text("old snapshot")
        self.render_calls = 0
        self.publish_calls = 0

        def render():
            self.render_calls += 1
            self.index.write_text("new snapshot")

        def publish():
            self.publish_calls += 1

        self.server = server_module.StatusServer(("127.0.0.1", 0), Path(self.directory.name), render, publish)
        self.server.external_origin = f"http://127.0.0.1:{self.server.server_port}"
        self.server.external_host = f"127.0.0.1:{self.server.server_port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.directory.cleanup()

    def request(self, method="POST", path="/refresh", *, origin=None, host=None, body=None):
        address = f"127.0.0.1:{self.server.server_port}"
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        headers = {"Origin": origin or self.server.external_origin}
        if host is not None:
            headers["Host"] = host
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def phase(self):
        status, headers, body = self.request("GET", "/refresh-status")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        return json.loads(body)["phase"]

    def wait_for(self, condition, timeout=2):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if condition():
                return True
            time.sleep(0.02)
        return condition()

    def trigger_automatic_refresh(self, *, delay=0.02, following_interval=60):
        self.server.auto_refresh_interval = following_interval
        self.server.next_auto_refresh_at = time.monotonic() + delay
        self.server.scheduler_wakeup.set()

    def test_refresh_renders_then_publishes_and_redirects(self):
        self.assertEqual(self.phase(), "idle")
        status, headers, _ = self.request()
        self.assertEqual(status, 303)
        self.assertEqual(headers["Location"], "/")
        self.assertEqual((self.render_calls, self.publish_calls), (1, 1))
        self.assertEqual(self.phase(), "complete")
        status, _, body = self.request("GET", "/")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"new snapshot")
        self.assertGreater(self.server.next_auto_refresh_at - time.monotonic(), 1799)

    def test_automatic_refresh_waits_for_interval_and_runs_both_stages(self):
        self.assertEqual(server_module.AUTO_REFRESH_INTERVAL_SECONDS, 30 * 60)
        self.assertGreater(self.server.next_auto_refresh_at - time.monotonic(), 1799)
        self.assertEqual((self.render_calls, self.publish_calls), (0, 0))
        self.trigger_automatic_refresh()
        self.assertTrue(self.wait_for(lambda: self.phase() == "complete"))
        self.assertEqual((self.render_calls, self.publish_calls), (1, 1))
        self.assertEqual(self.index.read_text(), "new snapshot")
        self.assertGreater(self.server.next_auto_refresh_at - time.monotonic(), 59)

    def test_automatic_refresh_skips_an_active_manual_job(self):
        entered = threading.Event()
        release = threading.Event()

        def slow_publish():
            self.publish_calls += 1
            entered.set()
            release.wait(timeout=2)

        self.server.publish = slow_publish
        result = []
        manual = threading.Thread(target=lambda: result.append(self.request()[0]))
        manual.start()
        self.assertTrue(entered.wait(timeout=1))
        self.trigger_automatic_refresh()
        time.sleep(0.05)
        self.assertEqual((self.render_calls, self.publish_calls), (1, 1))
        release.set()
        manual.join(timeout=2)
        self.assertEqual(result, [303])
        self.assertGreater(self.server.next_auto_refresh_at - time.monotonic(), 59)
        self.assertEqual((self.render_calls, self.publish_calls), (1, 1))

    def test_manual_refresh_is_rejected_during_an_automatic_job(self):
        entered = threading.Event()
        release = threading.Event()

        def slow_publish():
            self.publish_calls += 1
            entered.set()
            release.wait(timeout=2)

        self.server.publish = slow_publish
        self.trigger_automatic_refresh()
        self.assertTrue(entered.wait(timeout=1))
        self.assertEqual(self.phase(), "publishing")
        self.assertEqual(self.request()[0], 409)
        release.set()
        self.assertTrue(self.wait_for(lambda: self.phase() == "complete"))
        self.assertEqual((self.render_calls, self.publish_calls), (1, 1))

    @patch.object(server_module, "REFRESH_COOLDOWN_SECONDS", 0)
    def test_automatic_refresh_retries_after_a_failed_interval(self):
        first_failed = threading.Event()

        def fail_once():
            self.render_calls += 1
            if self.render_calls == 1:
                first_failed.set()
                raise RuntimeError("temporary render failure")
            self.index.write_text("recovered snapshot")
            self.server.auto_refresh_interval = 60

        self.server.render = fail_once
        self.trigger_automatic_refresh(following_interval=0.15)
        self.assertTrue(first_failed.wait(timeout=1))
        self.assertTrue(self.wait_for(lambda: self.phase() == "render_failed"))
        self.assertEqual((self.render_calls, self.publish_calls), (1, 0))
        self.assertEqual(self.index.read_text(), "old snapshot")
        self.assertTrue(self.wait_for(lambda: self.phase() == "complete"))
        self.assertEqual((self.render_calls, self.publish_calls), (2, 1))
        self.assertEqual(self.index.read_text(), "recovered snapshot")

    def test_shutdown_stops_the_timer_without_a_new_refresh(self):
        self.assertTrue(self.wait_for(lambda: self.server.scheduler_thread is not None))
        scheduler = self.server.scheduler_thread
        self.server.shutdown()
        self.thread.join(timeout=2)
        self.assertFalse(scheduler.is_alive())
        self.assertEqual((self.render_calls, self.publish_calls), (0, 0))

    def test_render_failure_preserves_snapshot_and_skips_publish(self):
        def fail_render():
            self.render_calls += 1
            raise RuntimeError("render unavailable")

        self.server.render = fail_render
        status, _, body = self.request()
        self.assertEqual(status, 502)
        self.assertIn(b"Local refresh failed", body)
        self.assertEqual((self.render_calls, self.publish_calls), (1, 0))
        self.assertEqual(self.phase(), "render_failed")
        self.assertEqual(self.index.read_text(), "old snapshot")
        retry_status, _, retry_body = self.request()
        self.assertEqual(retry_status, 503)
        self.assertIn(b"Previous refresh failed", retry_body)
        self.assertNotIn(b"Refresh recently completed", retry_body)
        self.assertEqual((self.render_calls, self.publish_calls), (1, 0))
        self.assertEqual(self.phase(), "render_failed")

    def test_publish_failure_reports_local_success_separately(self):
        def fail_publish():
            self.publish_calls += 1
            raise RuntimeError("publisher unavailable")

        self.server.publish = fail_publish
        status, _, body = self.request()
        self.assertEqual(status, 503)
        self.assertIn(b"The local page was updated, but the public page was not confirmed", body)
        self.assertEqual((self.render_calls, self.publish_calls), (1, 1))
        self.assertEqual(self.phase(), "publish_failed")
        self.assertEqual(self.index.read_text(), "new snapshot")
        retry_status, _, retry_body = self.request()
        self.assertEqual(retry_status, 503)
        self.assertIn(b"Previous refresh failed", retry_body)
        self.assertNotIn(b"Refresh recently completed", retry_body)
        self.assertEqual((self.render_calls, self.publish_calls), (1, 1))
        self.assertEqual(self.phase(), "publish_failed")

    def test_origin_host_and_body_safeguards_precede_both_jobs(self):
        self.assertEqual(self.request(origin="https://example.org")[0], 403)
        self.assertEqual(self.request(host="example.org")[0], 403)
        self.assertEqual(self.request(body="unwanted")[0], 400)
        self.assertEqual((self.render_calls, self.publish_calls), (0, 0))

    def test_refresh_is_rate_limited_and_get_cannot_trigger_it(self):
        self.assertEqual(self.request("GET")[0], 405)
        self.assertEqual(self.request()[0], 303)
        self.assertEqual(self.request()[0], 429)
        self.assertEqual((self.render_calls, self.publish_calls), (1, 1))

    def test_concurrent_refresh_is_rejected_while_publishing(self):
        entered = threading.Event()
        release = threading.Event()

        def slow_publish():
            self.publish_calls += 1
            entered.set()
            release.wait(timeout=4)

        self.server.publish = slow_publish
        result = []
        first = threading.Thread(target=lambda: result.append(self.request()[0]))
        first.start()
        self.assertTrue(entered.wait(timeout=2))
        self.assertEqual(self.phase(), "publishing")
        self.assertEqual(self.request()[0], 409)
        release.set()
        first.join(timeout=5)
        self.assertEqual(result, [303])
        self.assertEqual((self.render_calls, self.publish_calls), (1, 1))

    @patch.object(server_module.subprocess, "run")
    def test_refresh_stages_run_python_scripts_in_the_current_wsl_instance(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, "", "")
        server_module.render_snapshot()
        server_module.publish_snapshot()
        self.assertEqual(
            [call.args[0] for call in run.call_args_list],
            [
                [sys.executable, str(server_module.RENDER_SCRIPT)],
                [sys.executable, str(server_module.PUBLISH_SCRIPT)],
            ],
        )
        self.assertEqual([call.kwargs["cwd"] for call in run.call_args_list], [server_module.ROOT] * 2)
        self.assertFalse(any("wsl.exe" in arg for call in run.call_args_list for arg in call.args[0]))

    def test_refresh_uses_configured_external_origin_behind_port_forward(self):
        self.server.external_origin = "http://192.168.50.100:8877"
        self.server.external_host = "192.168.50.100:8877"
        status, _, _ = self.request(
            origin="http://192.168.50.100:8877",
            host="192.168.50.100:8877",
        )
        self.assertEqual(status, 303)
        self.assertEqual((self.render_calls, self.publish_calls), (1, 1))


if __name__ == "__main__":
    unittest.main()
