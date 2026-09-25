import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("publish-hetzner.py")
SPEC = importlib.util.spec_from_file_location("local_status_publish", MODULE_PATH)
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class PublishedPageTest(unittest.TestCase):
    def test_public_copy_links_to_local_page_without_exposing_refresh_endpoint(self):
        source = (
            '<span>Private local snapshot</span>'
            '<meta http-equiv="Content-Security-Policy" content="script-src \'sha256-abc\'; form-action \'self\'">'
            '<form class="refresh-form" action="/refresh" method="post"><button>Refresh</button></form>'
            '<script id="local-refresh-progress">local refresh behavior</script>'
            '<h2>Worker lanes</h2><h2>Configured review queue</h2>'
            '<footer>Local refresh instructions</footer>'
        )
        result = publisher.public_html(source, "http://192.168.50.100:8877/")
        self.assertIn('href="http://192.168.50.100:8877/"', result)
        self.assertNotIn('action="/refresh"', result)
        self.assertIn("form-action 'none'", result)
        self.assertIn("script-src 'none'", result)
        self.assertNotIn('id="local-refresh-progress"', result)
        self.assertNotIn("Local refresh instructions", result)
        self.assertIn("Published delivery snapshot", result)


if __name__ == "__main__":
    unittest.main()
