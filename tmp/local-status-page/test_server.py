import http.client
import importlib.util
import tempfile
import threading
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("server.py")
SPEC = importlib.util.spec_from_file_location("local_status_server", MODULE_PATH)
server_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server_module)


class LocalRefreshServerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.index = Path(self.directory.name) / "index.html"
        self.index.write_text("old snapshot")
        self.calls = 0

        def refresh():
            self.calls += 1
            self.index.write_text("new snapshot")

        self.server = server_module.StatusServer(("127.0.0.1", 0), Path(self.directory.name), refresh)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.directory.cleanup()

    def request(self, method="POST", path="/refresh", *, origin=None, body=None):
        address = f"127.0.0.1:{self.server.server_port}"
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        headers = {"Origin": origin or f"http://{address}"}
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def test_refresh_updates_snapshot_and_redirects(self):
        status, headers, _ = self.request()
        self.assertEqual(status, 303)
        self.assertEqual(headers["Location"], "/")
        self.assertEqual(self.calls, 1)
        status, _, body = self.request("GET", "/")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"new snapshot")

    def test_foreign_origin_cannot_trigger_refresh(self):
        status, _, _ = self.request(origin="https://example.org")
        self.assertEqual(status, 403)
        self.assertEqual(self.calls, 0)

    def test_refresh_is_rate_limited_and_get_cannot_trigger_it(self):
        self.assertEqual(self.request("GET")[0], 405)
        self.assertEqual(self.request()[0], 303)
        self.assertEqual(self.request()[0], 429)
        self.assertEqual(self.calls, 1)

    def test_concurrent_refresh_is_rejected(self):
        entered = threading.Event()
        release = threading.Event()

        def slow_refresh():
            entered.set()
            release.wait(timeout=4)

        self.server.refresh = slow_refresh
        result = []
        first = threading.Thread(target=lambda: result.append(self.request()[0]))
        first.start()
        self.assertTrue(entered.wait(timeout=2))
        self.assertEqual(self.request()[0], 409)
        release.set()
        first.join(timeout=5)
        self.assertEqual(result, [303])


if __name__ == "__main__":
    unittest.main()
