import json
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
        self.assertIn('1/79 fully implemented', result)
        self.assertIn('href="/"', result)
        self.assertIn('Return points', result)
        self.assertNotIn('/home/ben/', result)

    def test_manual_text_is_escaped_and_external_links_are_restricted(self):
        self.assertEqual(render_progress.safe('<script>'), '&lt;script&gt;')
        with self.assertRaises(ValueError):
            render_progress.safe_link('Bad', 'javascript:alert(1)')


if __name__ == "__main__":
    unittest.main()
