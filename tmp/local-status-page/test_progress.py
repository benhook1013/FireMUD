import json
import base64
import hashlib
import sys
import tempfile
import unittest
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_progress


class ProjectMapTest(unittest.TestCase):
    def test_primary_capability_rows_and_coarse_proxy(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.md"
            path.write_text("""## Capability Status
| Capability | Implementation | Verification |
| --- | --- | --- |
| AA-1.1 First | `implemented` | `proven` |
| AA-1.2 Second | `partial` - bounded | `drift-found` - open |
| AA-1.3 Third | `not-implemented` | `unverified` |
## Other section
| AA-9.9 Ignore | `implemented` | `proven` |
""", encoding="utf-8")
            implementation, verification = render_progress.tracker_counts(path)
        self.assertEqual(implementation, Counter({"implemented": 1, "partial": 1, "not-implemented": 1}))
        self.assertEqual(verification["proven"], 1)
        self.assertEqual(render_progress.rough_percent(implementation), 50)

    def test_current_map_has_ten_domains_and_return_points(self):
        data = json.loads((Path(__file__).with_name("progress.json")).read_text(encoding="utf-8"))
        result = render_progress.render(data, datetime(2026, 9, 27, tzinfo=timezone.utc))
        self.assertEqual(result.count('class="domain"'), 10)
        self.assertIn('<strong>1/79</strong><span>fully implemented</span>', result)
        self.assertIn('class="domain-summary"', result)
        self.assertIn('href="/"', result)
        self.assertIn('Return points', result)
        self.assertIn('FireMUD Project Map', result)
        self.assertIn(render_progress.SHARED_CSS, result)
        self.assertIn('class="mast-inner"><div class="mast-content"><img class="mast-icon"', result)
        self.assertIn('class="mast-middle mast-meta"', result)
        self.assertIn('Programme notes checked <time class="relative-age"', result)
        self.assertIn('Page rendered <time class="relative-age"', result)
        age_hash = base64.b64encode(hashlib.sha256(render_progress.AGE_SCRIPT.encode()).digest()).decode()
        self.assertIn(f"script-src 'sha256-{age_hash}'", result)
        self.assertIn('PR Delivery Page</a>', result)
        self.assertNotIn('Long-range progress', result)
        self.assertNotIn('/home/ben/', result)

    def test_manual_text_is_escaped_and_external_links_are_restricted(self):
        self.assertEqual(render_progress.safe('<script>'), '&lt;script&gt;')
        with self.assertRaises(ValueError):
            render_progress.safe_link('Bad', 'javascript:alert(1)')


if __name__ == "__main__":
    unittest.main()
