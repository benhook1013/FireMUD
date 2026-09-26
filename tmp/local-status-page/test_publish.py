import importlib.util
import base64
import hashlib
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).with_name("publish-hetzner.py")
SPEC = importlib.util.spec_from_file_location("local_status_publish", MODULE_PATH)
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class PublishedPageTest(unittest.TestCase):
    def test_local_refresh_url_uses_static_windows_lan_address(self):
        with mock.patch.object(publisher.subprocess, "run", side_effect=AssertionError("unexpected subprocess")) as run:
            self.assertEqual(publisher.local_wifi_url(), "http://192.168.50.100:8877/")
            run.assert_not_called()

    def test_public_copy_links_to_local_page_without_exposing_refresh_endpoint(self):
        age_script = "read-only relative age behavior"
        snapshot_script = "read-only snapshot polling behavior"
        source = (
            '<span>Private local snapshot</span>'
            '<meta name="status-snapshot" content="2026-09-24T12:00:00+00:00">'
            '<meta http-equiv="Content-Security-Policy" content="script-src \'sha256-abc\' \'sha256-def\'; connect-src \'self\'; form-action \'self\'">'
            '<form class="refresh-form" action="/refresh" method="post"><button>Refresh</button>'
            '<span class="refresh-time">Refreshed <time datetime="2026-09-24T12:00:00Z">just now</time></span></form>'
            '<script id="local-refresh-progress">fetch("/refresh-status")</script>'
            '<span class="round-pill" aria-label="2/2, Completed 24 Sep 2026 23:46 NZST" '
            'title="Completed 24 Sep 2026 23:46 NZST"><span>2/2</span>'
            '<time class="round-age" datetime="2026-09-24T11:46:00+00:00">14m</time></span>'
            f'<script id="relative-age-updates">{age_script}</script>'
            f'<script id="snapshot-updates">{snapshot_script}</script>'
            '<h2>Worker lanes</h2><h2>Configured review queue</h2>'
            '<footer>Local refresh instructions</footer>'
        )
        result = publisher.public_html(source, "http://192.168.50.100:8877/")
        age_hash = base64.b64encode(hashlib.sha256(age_script.encode()).digest()).decode()
        snapshot_hash = base64.b64encode(hashlib.sha256(snapshot_script.encode()).digest()).decode()
        self.assertIn('href="http://192.168.50.100:8877/"', result)
        self.assertNotIn('action="/refresh"', result)
        self.assertNotIn('<form class="refresh-form"', result)
        self.assertIn('<div class="refresh-form"><span class="refresh-time">Refreshed '
                      '<time datetime="2026-09-24T12:00:00Z">just now</time></span></div>', result)
        self.assertIn("form-action 'none'", result)
        self.assertIn("connect-src 'self'", result)
        self.assertIn(f"script-src 'sha256-{age_hash}' 'sha256-{snapshot_hash}'", result)
        self.assertNotIn("sha256-abc", result)
        self.assertNotIn('id="local-refresh-progress"', result)
        self.assertNotIn('/refresh-status', result)
        self.assertIn(f'<script id="relative-age-updates">{age_script}</script>', result)
        self.assertIn(f'<script id="snapshot-updates">{snapshot_script}</script>', result)
        self.assertIn('<time class="round-age" datetime="2026-09-24T11:46:00+00:00">14m</time>', result)
        self.assertIn('aria-label="2/2, Completed 24 Sep 2026 23:46 NZST"', result)
        self.assertNotIn("Local refresh instructions", result)
        self.assertIn("Published delivery snapshot", result)

    def test_public_copy_rejects_missing_relative_age_script(self):
        with self.assertRaisesRegex(ValueError, "read-only relative-time script"):
            publisher.public_html('<h2>Worker lanes</h2><h2>Configured review queue</h2>', "http://192.168.50.100:8877/")

    def test_public_copy_rejects_missing_snapshot_script(self):
        with self.assertRaisesRegex(ValueError, "read-only snapshot script"):
            publisher.public_html('<script id="relative-age-updates">age</script>', "http://192.168.50.100:8877/")

    def test_public_copy_rejects_refresh_form_without_timestamp(self):
        source = '<form class="refresh-form" action="/refresh" method="post"><button>Refresh</button></form>'
        with self.assertRaisesRegex(ValueError, "read-only refresh timestamp"):
            publisher.public_html(source, "http://192.168.50.100:8877/")


if __name__ == "__main__":
    unittest.main()
