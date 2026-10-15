#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SUMMARY_PAYLOAD="$(python3 - "$ROOT_DIR" <<'PY'
from pathlib import Path
import base64
import json
import sys
import yaml

root = Path(sys.argv[1])
ci = yaml.load((root / '.github/workflows/ci.yml').read_text(), Loader=yaml.BaseLoader)
reporter = yaml.load((root / '.github/workflows/validation-summary.yml').read_text(), Loader=yaml.BaseLoader)
if 'validation-summary' in ci['jobs']:
    raise SystemExit('Optional summary must be outside the original Validation workflow')
if reporter['on'] != {'workflow_run': {'workflows': ['CI — Validation'], 'types': ['completed']}}:
    raise SystemExit('Validation Summary must consume only completed owning-workflow callbacks')
if reporter.get('permissions') != {'actions': 'read', 'pull-requests': 'write'}:
    raise SystemExit('Detached summary must retain only Actions read and comment-write permissions')
if reporter.get('concurrency') != {'group': 'validation-summary-${{ github.event.workflow_run.head_sha }}', 'cancel-in-progress': 'false'}:
    raise SystemExit('Summary publishers for a head must be serialized')
job = reporter['jobs']['publish-summary']
if job.get('if') != "${{ github.event.workflow_run.event == 'pull_request' && github.event.workflow_run.conclusion != 'cancelled' }}":
    raise SystemExit('Detached summary must exclude non-PR and cancelled sources')
if job.get('timeout-minutes') != '5':
    raise SystemExit('Summary callback must retain its bounded job timeout')
steps = job['steps']
if len(steps) != 2 or any('run' in step or 'checkout@' in step.get('uses', '') for step in steps):
    raise SystemExit('Trusted reporter must not check out or execute PR code')
script = next(step['with']['script'] for step in steps if step.get('name') == '💬 Publish Validation Summary Comment')
if '${{ needs.' in script or 'downloadArtifact' in script:
    raise SystemExit('Callback must reconstruct native jobs without PR artifacts or unavailable needs outputs')
# The native name mapping must remain complete when CI adds/removes a job.
names = [job['name'] for job in ci['jobs'].values()]
for name in names:
    if '${{' not in name and json.dumps(name, ensure_ascii=False) not in script:
        raise SystemExit(f'Detached summary is missing the CI job name {name}')
print(base64.b64encode(json.dumps({'script': script, 'names': names}).encode()).decode())
PY
)"

SUMMARY_PAYLOAD="$SUMMARY_PAYLOAD" node <<'NODE'
const assert = require('node:assert/strict');
const payload = JSON.parse(Buffer.from(process.env.SUMMARY_PAYLOAD, 'base64').toString('utf8'));
const execute = new Function('github', 'context', 'core', `return (async () => {\n${payload.script}\n})()`);
const head = 'a'.repeat(40), base = 'b'.repeat(40), repository = 'example/firemud';
const clone = value => JSON.parse(JSON.stringify(value));
const source = {
  id: 70, run_number: 7, run_attempt: 1, workflow_id: 12, name: 'CI — Validation', path: '.github/workflows/ci.yml',
  display_title: `CI — Validation pr-42 base-${base} head-${head}`,
  repository: { full_name: repository }, head_repository: { full_name: repository },
  actor: { login: 'contributor' }, event: 'pull_request', status: 'completed', conclusion: 'success', head_sha: head,
  pull_requests: [{ number: 42, base: { ref: 'develop', sha: base }, head: { sha: head } }],
};
const pr = {
  number: 42, state: 'open', head: { sha: head, repo: { full_name: repository } },
  base: { ref: 'develop', sha: base, repo: { full_name: repository } },
};
const row = (name, id) => ({ id, name, run_id: 70, run_attempt: 1, head_sha: head, status: 'completed', conclusion: 'success' });
const jobs = payload.names.filter(name => !name.includes('${{')).map((name, i) => row(name, i + 1));
jobs.push(row('Build and Test (account-service)', 90), row('Build and Test (common-security)', 91));
const botComment = (id, body, created_at) => ({ id, body, user: { login: 'github-actions[bot]' }, created_at });
const comments = [
  { id: 1, body: '### Validation Summary\nspoofed contributor comment', user: { login: 'contributor' } },
  botComment(2, '### Validation Summary\nold bot summary', '2026-01-01T00:00:00Z'),
  botComment(3, '### Validation Summary\nnew bot summary', '2026-01-02T00:00:00Z'),
];
function harness(config = {}) {
  const calls = { jobs: [], comments: [], updates: [], creates: [], deletes: [], pulls: 0, runs: 0 };
  const sourceRun = config.source || clone(source);
  const context = { repo: { owner: 'example', repo: 'firemud' }, payload: { workflow_run: sourceRun } };
  const github = { rest: {
    actions: {
      getWorkflow: async request => {
        assert.equal(request.workflow_id, 'ci.yml');
        assert.ok(request.request.timeout > 0 && request.request.timeout <= 10000);
        return { data: config.workflow || { id: 12, name: 'CI — Validation', path: '.github/workflows/ci.yml' } };
      },
      getWorkflowRun: async request => {
        assert.equal(request.run_id, sourceRun.id);
        const index = calls.runs++;
        return { data: config.runSequence?.[Math.min(index, config.runSequence.length - 1)] || clone(sourceRun) };
      },
      listJobsForWorkflowRunAttempt: async request => {
        calls.jobs.push(request);
        assert.equal(request.run_id, sourceRun.id); assert.equal(request.attempt_number, sourceRun.run_attempt); assert.equal(request.per_page, 100);
        const data = config.jobs || clone(jobs).map(job => ({ ...job, run_id: sourceRun.id, run_attempt: sourceRun.run_attempt }));
        return { data: { total_count: config.total ?? data.length, jobs: data.slice((request.page - 1) * 100, request.page * 100) } };
      },
    },
    pulls: { get: async () => {
      const index = calls.pulls++;
      return { data: config.prSequence?.[Math.min(index, config.prSequence.length - 1)] || config.pr || clone(pr) };
    } },
    issues: {
      listComments: async request => {
        calls.comments.push(request); assert.equal(request.per_page, 100);
        const data = config.comments || [];
        return { data: data.slice((request.page - 1) * 100, request.page * 100), headers: {
          link: request.page * 100 < data.length ? '<next>; rel="next"' : '',
        } };
      },
      createComment: async request => { calls.creates.push(request); },
      updateComment: async request => { calls.updates.push(request); },
      deleteComment: async request => { calls.deletes.push(request); if (config.deleteStatus) throw { status: config.deleteStatus }; },
    },
  } };
  const core = { info() {}, summary: { addRaw() { return this; }, addList() { return this; }, async write() {} } };
  return { calls, run: () => execute(github, context, core) };
}
const writes = calls => calls.creates.length + calls.updates.length + calls.deletes.length;
async function noWrites(config, reject = false) {
  const h = harness(config);
  if (reject) await assert.rejects(h.run); else await h.run();
  assert.equal(writes(h.calls), 0);
  return h.calls;
}
(async () => {
  const successful = harness(); await successful.run();
  assert.equal(successful.calls.creates.length, 1);
  assert.match(successful.calls.creates[0].body, /✅ All validation checks passed/);
  assert.match(successful.calls.creates[0].body, /<!-- firemud-validation-summary -->/);
  assert.match(successful.calls.creates[0].body, /Dev Tools README contract: `success`/);
  // A later native run publishes first; a delayed older callback may neither rewrite it nor delete it as a duplicate.
  const newerSource = { ...clone(source), id: 71, run_number: 8 };
  const newer = harness({ source: newerSource }); await newer.run();
  const newerBody = newer.calls.creates[0].body;
  assert.equal(newerBody.replace(/<!-- firemud-validation-source .*? -->\n/, ''),
    successful.calls.creates[0].body.replace(/<!-- firemud-validation-source .*? -->\n/, ''), 'Visible summary formatting must remain unchanged');
  const reverseOrder = await noWrites({ comments: [
    comments[1], botComment(3, newerBody, '2026-01-02T00:00:00Z'),
  ] });
  assert.equal(reverseOrder.comments.length, 1);
  const advance = harness({ source: newerSource, comments: [botComment(2, successful.calls.creates[0].body, '2026-01-01T00:00:00Z')] });
  await advance.run(); assert.equal(advance.calls.updates.length, 1, 'Newer native run must advance the summary');
  const nextAttempt = harness({ source: { ...clone(source), run_attempt: 2 } }); await nextAttempt.run();
  await noWrites({ comments: [botComment(2, nextAttempt.calls.creates[0].body, '2026-01-01T00:00:00Z'), comments[2]] });
  const advanceAttempt = harness({ source: { ...clone(source), run_attempt: 2 }, comments: [botComment(2, successful.calls.creates[0].body, '2026-01-01T00:00:00Z')] });
  await advanceAttempt.run(); assert.equal(advanceAttempt.calls.updates.length, 1, 'Newer attempt must advance the same run');
  const marker = /<!-- firemud-validation-source (.*?) -->/.exec(newerBody)[1];
  for (const change of [{ workflow_id: 13 }, { pr: 43 }, { repository: 'example/other' },
    { base_ref: 'other-base' }, { base_sha: 'c'.repeat(40) }, { head_sha: 'c'.repeat(40) }]) {
    const otherTuple = { ...JSON.parse(marker), ...change };
    const transition = harness({ comments: [botComment(2, `### Validation Summary\n<!-- firemud-validation-source ${JSON.stringify(otherTuple)} -->`, '2026-01-01T00:00:00Z')] });
    await transition.run(); assert.equal(transition.calls.updates.length, 1, 'Only the exact publication tuple may hold an older callback');
  }
  const invalidIdentity = { ...JSON.parse(marker), run_number: source.run_number };
  await noWrites({ comments: [botComment(2, `### Validation Summary\n<!-- firemud-validation-source ${JSON.stringify(invalidIdentity)} -->`, '2026-01-01T00:00:00Z')] }, true);
  await noWrites({ comments: [botComment(2, '### Validation Summary\n<!-- firemud-validation-source bad-json -->', '2026-01-01T00:00:00Z')] }, true);
  await noWrites({ source: { ...clone(source), run_number: 0 } }, true);
  await noWrites({ runSequence: [clone(source), { ...clone(source), run_number: 8 }] });
  const noise = Array.from({ length: 101 }, (_, i) => botComment(1000 + i, 'Unrelated', '2025-01-01T00:00:00Z'));
  const existing = harness({ comments: [...noise, ...comments] }); await existing.run();
  assert.equal(existing.calls.comments.length, 2);
  assert.deepEqual(existing.calls.updates.map(x => x.comment_id), [2]);
  assert.deepEqual(existing.calls.deletes.map(x => x.comment_id), [3]);
  assert.equal(existing.calls.creates.length, 0);
  const unchanged = harness({ comments: comments.map(x => x.id === 2 ? { ...x, body: successful.calls.creates[0].body } : x) });
  await unchanged.run(); assert.equal(unchanged.calls.updates.length, 0); assert.equal(unchanged.calls.deletes.length, 1);
  const missingDuplicate = harness({ comments, deleteStatus: 404 }); await missingDuplicate.run();
  await assert.rejects(harness({ comments, deleteStatus: 500 }).run);
  for (const mutate of [
    x => { x.head.sha = 'c'.repeat(40); }, x => { x.base.sha = 'c'.repeat(40); },
    x => { x.base.ref = 'other-base'; }, x => { x.state = 'closed'; },
    x => { x.head.repo.full_name = 'fork/repo'; },
  ]) { const stale = clone(pr); mutate(stale); const c = await noWrites({ pr: stale }); assert.equal(c.comments.length, 0); }
  for (const mutate of [
    x => { x.repository.full_name = 'other/repo'; }, x => { x.head_repository.full_name = 'fork/repo'; },
    x => { x.workflow_id = 99; }, x => { x.path = '.github/workflows/other.yml'; },
    x => { x.name = 'Other'; }, x => { x.event = 'push'; }, x => { x.conclusion = 'cancelled'; },
    x => { x.actor.login = 'dependabot[bot]'; }, x => { x.pull_requests[0].base.sha = 'c'.repeat(40); },
  ]) { const invalid = clone(source); mutate(invalid); await noWrites({ source: invalid }); }
  for (const value of [null, undefined, {}, [source.pull_requests[0], source.pull_requests[0]], [{}]]) {
    await noWrites({ source: { ...clone(source), pull_requests: value } }, true);
  }
  // Both inspected native REST runs exposed an actual empty association array and a canonical name/title.
  const emptyAssociation = { ...clone(source), name: source.display_title, pull_requests: [] };
  const nativeShape = harness({ source: emptyAssociation }); await nativeShape.run();
  assert.equal(nativeShape.calls.creates.length, 1);
  assert.equal(JSON.parse(/<!-- firemud-validation-source (.*?) -->/.exec(nativeShape.calls.creates[0].body)[1]).base_ref, 'develop');
  const capturedRef = { ...clone(pr), base: { ...clone(pr.base), ref: 'current-base-ref' } };
  const currentRefOnly = harness({ source: emptyAssociation, pr: capturedRef }); await currentRefOnly.run();
  assert.equal(currentRefOnly.calls.creates.length, 1, 'Empty association does not prove a historical base ref');
  assert.equal(JSON.parse(/<!-- firemud-validation-source (.*?) -->/.exec(currentRefOnly.calls.creates[0].body)[1]).base_ref, 'current-base-ref');
  for (const mutate of [x => { x.base.ref = 'moved-ref'; }, x => { x.base.sha = 'c'.repeat(40); }, x => { x.head.sha = 'c'.repeat(40); }]) {
    const moved = clone(pr); mutate(moved); await noWrites({ source: emptyAssociation, prSequence: [clone(pr), moved] });
  }
  const newerEmpty = harness({ source: { ...clone(emptyAssociation), id: 71, run_number: 8 } }); await newerEmpty.run();
  await noWrites({ source: emptyAssociation, comments: [comments[1], botComment(3, newerEmpty.calls.creates[0].body, '2026-01-02T00:00:00Z')] });
  const mismatch = clone(source); mismatch.pull_requests[0].head.sha = 'c'.repeat(40);
  await noWrites({ source: mismatch });
  const wrongPR = clone(source); wrongPR.pull_requests[0].number = 43; await noWrites({ source: wrongPR });
  const wrongRef = clone(source); wrongRef.pull_requests[0].base.ref = 'wrong-ref'; await noWrites({ source: wrongRef });
  await noWrites({ source: emptyAssociation, runSequence: [clone(emptyAssociation), { ...clone(emptyAssociation), pull_requests: null }] });
  await noWrites({ source: { ...clone(source), display_title: 'not a canonical source title' } }, true);
  await noWrites({ workflow: { id: 12, name: 'Wrong', path: '.github/workflows/ci.yml' } }, true);
  for (const mutate of [x => { x.head.sha = 'c'.repeat(40); }, x => { x.state = 'closed'; }]) {
    const stale = clone(pr); mutate(stale); await noWrites({ prSequence: [clone(pr), stale] });
  }
  await noWrites({ runSequence: [clone(source), { ...clone(source), run_attempt: 2 }] });
  const detectorSkipped = clone(jobs); detectorSkipped.find(x => x.name === 'Detect CI-Relevant Changes').conclusion = 'skipped';
  assert.equal((await noWrites({ jobs: detectorSkipped })).comments.length, 0);
  for (const name of ['Validation Summary', 'PR Metadata Edit (Validation Summary)']) {
    assert.equal((await noWrites({ jobs: [...clone(jobs), row(name, 200)] })).comments.length, 0);
  }
  for (const data of [
    clone(jobs).slice(1), [...clone(jobs), { ...jobs[0], id: 200 }], [...clone(jobs), row('Unknown job', 200)],
    clone(jobs).map((x, i) => i === 0 ? { ...x, run_id: 999 } : x),
    clone(jobs).map((x, i) => i === 0 ? { ...x, run_attempt: 2 } : x),
    clone(jobs).map((x, i) => i === 0 ? { ...x, head_sha: 'c'.repeat(40) } : x),
    clone(jobs).map((x, i) => i === 0 ? { ...x, status: 'queued' } : x),
    clone(jobs).map((x, i) => i === 0 ? { ...x, conclusion: null } : x),
  ]) await noWrites({ jobs: data }, true);
  await noWrites({ total: jobs.length + 1 }, true); await noWrites({ total: 201 }, true);
  const matrixFail = clone(jobs); matrixFail.find(x => x.name === 'Build and Test (common-security)').conclusion = 'failure';
  const failed = harness({ jobs: matrixFail }); await failed.run();
  assert.match(failed.calls.creates[0].body, /One or more validation checks failed/);
  assert.match(failed.calls.creates[0].body, /Service build\/test matrix: `failure`/);
  const skipped = clone(jobs).filter(x => !x.name.startsWith('Build and Test ('));
  skipped.push({ ...row('Build and Test ($' + '{{ matrix.module }})', 201), conclusion: 'skipped' });
  const skippedRun = harness({ jobs: skipped }); await skippedRun.run();
  assert.match(skippedRun.calls.creates[0].body, /Service build\/test matrix: `skipped`/);
  const neutral = clone(jobs); neutral.find(x => x.name.startsWith('Build and Test (')).conclusion = 'neutral';
  await noWrites({ jobs: neutral }, true);
  await noWrites({ comments: Array.from({ length: 1001 }, (_, i) => botComment(i + 1, 'Unrelated', '2025-01-01T00:00:00Z')) }, true);
  console.log('Detached Validation Summary contract passed: trust, source attempts, complete jobs, matrix results, write fences, pagination, ownership, legacy exclusion and monotonic source publication');
})().catch(error => { console.error(error); process.exitCode = 1; });
NODE
