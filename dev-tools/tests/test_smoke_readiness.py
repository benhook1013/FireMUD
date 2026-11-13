"""Offline HTTP readiness checks: no redirect or substring can imply health."""

import importlib.util
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import MagicMock, patch

SCRIPT = Path(__file__).resolve().parents[1] / "smoke/smoke_common.py"
spec = importlib.util.spec_from_file_location("smoke_readiness_subject", SCRIPT)
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class SmokeReadinessTest(unittest.TestCase):
    def check_response(self, body, status=200):
        response = MagicMock(status=status)
        response.read.return_value = body
        response.__enter__.return_value = response
        opener = MagicMock()
        opener.open.return_value = response
        with patch.object(subject.urllib.request, "build_opener", return_value=opener) as build:
            result = subject.http_readiness_up("https://example.invalid/readiness", 3)
        self.assertIsInstance(build.call_args.args[0], subject._NoRedirectHandler)
        opener.open.assert_called_once_with("https://example.invalid/readiness", timeout=3)
        return result

    def test_exact_up_with_normal_json_whitespace(self):
        self.assertTrue(self.check_response(b'{"status": "UP", "components": {}}'))

    def test_nested_up_is_not_root_readiness(self):
        self.assertFalse(self.check_response(b'{"status":"DOWN","components":{"status":"UP"}}'))

    def test_embedded_up_substring_is_not_json_health(self):
        self.assertFalse(self.check_response(b'error text "status":"UP"'))

    def test_non_object_and_missing_or_wrong_status(self):
        for body in (b'[{"status":"UP"}]', b"null", b"{}", b'{"status":"up"}', b'{"status":true}'):
            with self.subTest(body=body):
                self.assertFalse(self.check_response(body))

    def test_non_success_status_is_never_ready(self):
        for status in (199, 301, 302, 307, 400, 404, 500):
            with self.subTest(status=status):
                self.assertFalse(self.check_response(b'{"status":"UP"}', status))

    def test_malformed_json_or_utf8_is_not_ready(self):
        for body in (b"{", b"\xff"):
            with self.subTest(body=body):
                self.assertFalse(self.check_response(body))

    def test_redirect_handler_refuses_followup(self):
        handler = subject._NoRedirectHandler()
        for code in (301, 302, 303, 307, 308):
            with self.subTest(code=code):
                self.assertIsNone(handler.redirect_request(None, None, code, "redirect", {}, "https://other.invalid"))

    def test_transport_failure_is_not_ready(self):
        opener = MagicMock()
        for error in (urllib.error.URLError("offline"), OSError("unavailable")):
            opener.open.side_effect = error
            with self.subTest(error=error), patch.object(subject.urllib.request, "build_opener", return_value=opener):
                self.assertFalse(subject.http_readiness_up("https://example.invalid/readiness", 3))


if __name__ == "__main__":
    unittest.main()
