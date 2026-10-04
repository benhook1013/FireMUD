# Private status-site adapter

The adjacent patch is a source-only handover artifact for the existing private status site. It adds batched SQL-backed lane and project-map projections to the local renderer, in-memory private job, workstream-note, and inbox routes to the local server, and explicit private-route rejection to public staging. It also emphasizes only the current PR endpoint in Routed Findings, styles the existing “View routed finding” fragment link as dark normal-weight inherited text, and keeps review-header age and runtime labels at the same readable size. The patch contains no site data, private briefs, credentials, or configuration. The original site remains unchanged; integration proof uses the isolated copy at `tmp/job-site-integration`.

The patch changes only `render.py`, `server.py`, `publish-hetzner.py`, and `render_progress.py`. The unchanged CSS and existing test files were fingerprinted with the captured baseline:

| Source file | Captured SHA-256 |
| --- | --- |
| render.py | 8e66c84f5919cdbf9e2c77cafb8e6d1c1cf2ae333b20e172c433f66f8c42ab02 |
| server.py | f882ad287c87d23c79a3f6fdda08aa0e10b89853a228a54b943b8575bff0c193 |
| publish-hetzner.py | 0fd388caf3a5919ff5e4242009e6fc26e9d95a4e39d0df76b09f79d7647416de |
| render_progress.py | 4f9fe30c9d8d6ba0f433445625758e494af60d28069dc49d2fd056e3d39a2adb |
| shared.css | cff6958f26f5b65dc4e14f63d43fd08fd8481dcdb830a9025190c85e51614596 |
| test_render.py | 831b29a35518fcfe60d0331444ad83f92f90f92b126f810fbcc5e810949bd549 |
| test_server.py | 21d1423f86281497b07a74e7e14d6e6564819f90df6bdc3015f227a4aa8643d9 |
| test_publish.py | ac798bb369ec9aab76a6028a2185f0fd28c8ca681059f278ab668d52745a29c1 |

Before applying, copy the current private website directory to a new isolated destination. Pass both the unchanged source and that isolated destination. The helper checks source fingerprints, exact pre-patch fingerprints for all four targets, and the patch’s four-file scope before applying. It refuses an overlapping or already-applied copy. The `--verify` option checks all four post-patch fingerprints without writing.

```sh
python3 dev-tools/fire_controller/integration/apply_private_adapter.py \
  --source-site /path/to/current/local-status-page \
  --site-copy /path/to/isolated/job-site-copy

python3 dev-tools/fire_controller/integration/apply_private_adapter.py \
  --source-site /path/to/current/local-status-page \
  --site-copy /path/to/isolated/job-site-copy \
  --verify
```

Captured post-patch SHA-256 values for the isolated copy:

| Source file | Post-patch SHA-256 |
| --- | --- |
| render.py | 7bc70250ebf32a676138eeda82d1fa523033565d73d1ff5e589267e37dee206e |
| server.py | 2dc5418179804dfacb0247dc5ac48fc9f7e435aaca3a82ca3fbf06583418f010 |
| publish-hetzner.py | 6d3a4dad2de8cdccd760aaaa17843926cbbeb97db4b392d17227c6162dee4298 |
| render_progress.py | d1f2b3bdc2862ba46166e8d30348920f0836e52eec1bdde28ed2378a0b0c47a2 |

The renderer accepts `--jobs-database` and `--controller-tools` together. It loads the controller lane snapshot in one batched call and joins SQL map state to the repository-owned `progress.json` editorial headings and explanations. Public text uses the safe renderer’s HTTP(S)-only link policy, so local job, workstream, and inbox paths do not enter generated output. The server uses the same explicit database and tool paths, serves private detail pages from memory, and adds local links only to HTTP responses. The publisher scans staged pages, assets, and review pages and fails if a private `/jobs/`, `/workstreams/`, or `/inbox/` route appears.

The focused handoff proof is `python3 -m unittest discover -s dev-tools/validation -p test_fire_controller_web.py`. With the isolated copy prepared, it exercises the full website renderer, a loopback server with fake stores, and the existing public staging helpers without invoking the website refresh, publisher, scheduler, or provider operations. A normal checkout without that private copy runs the reusable component tests and explicitly skips the private adapter integration class; that skip does not establish adapter proof.

The renderer selects logical review fronts and per-channel “Next” badges only from the controller’s additive `review_fronts` projection. Hosted and CLI may name the same PR or different PRs; a known front remains visible when request identity makes `review_targets` unknown. Missing or unknown fronts select nothing, and merged or closed PRs are excluded. “Next” identifies queue position independently of running activity or request availability; progress, rules, and pending fixes retain their controller-provided labels.
