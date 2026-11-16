const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const {test} = require('node:test');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const workflow = JSON.parse(execFileSync('python3', ['-c',
  'import yaml,json,sys; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))',
  path.join(root, '.github/workflows/preview-runtime-refresh.yml')], {encoding: 'utf8'}));
const script = workflow.jobs.refresh.steps[1].with.script;
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const A = 'a'.repeat(40), B = 'b'.repeat(40), C = 'c'.repeat(40), D = 'd'.repeat(40);
function fixture() {
  return {
    run: {path: '.github/workflows/preview.yml', event: 'pull_request', conclusion: 'success',
      repository: {full_name: 'example/FireMUD'}, head_repository: {full_name: 'example/FireMUD'},
      head_sha: C, pull_requests: [{number: 7, head: {sha: C}, base: {sha: D}}]},
    pr: {number: 7, state: 'open', head: {sha: C, repo: {full_name: 'example/FireMUD'}},
      base: {ref: 'develop', repo: {full_name: 'example/FireMUD'}},
      merge_commit_sha: A, labels: [], user: {login: 'developer'}},
    base: {object: {sha: B}}, merge: {sha: A, parents: [{sha: B}, {sha: C}]},
  };
}
async function execute(f) {
  const sent = [];
  const github = {rest: {
    actions: {getWorkflowRun: async () => ({data: f.run})},
    pulls: {get: async () => ({data: f.pr})},
    git: {getRef: async () => ({data: f.base})},
    repos: {getCommit: async () => ({data: f.merge}), createDispatchEvent: async p => sent.push(p)},
  }};
  await new AsyncFunction('github', 'context', 'core', 'require', script)(github,
    {repo: {owner: 'example', repo: 'FireMUD'}, payload: {workflow_run: {id: 99}}}, {info() {}}, name => {
      assert.equal(name, 'node:child_process');
      return {execFileSync(command, args) {
        assert.equal(command, 'bash');
        assert.deepEqual(args, ['./dev-tools/hosted/preview/resolve-preview-image-tag.sh', A, '7', B]);
        return f.imageTag ?? `pr-merge-${A}`;
      }};
    });
  return sent;
}
test('exact stale-base current tuple produces only the typed refresh', async () => {
  assert.deepEqual(await execute(fixture()), [{owner: 'example', repo: 'FireMUD',
    event_type: 'pr-runtime-base-refresh', client_payload: {pr_number: '7', base_ref: 'develop',
      base_sha: B, head_sha: C, merge_sha: A, image_tag: `pr-merge-${A}`}}]);
});
for (const [name, mutate] of Object.entries({
  wrongPath: f => f.run.path = '.github/workflows/attacker.yml',
  wrongEvent: f => f.run.event = 'repository_dispatch',
  failedSource: f => f.run.conclusion = 'failure',
  wrongRepository: f => f.run.repository.full_name = 'fork/FireMUD',
  forkSource: f => f.run.head_repository.full_name = 'fork/FireMUD',
  absentAssociation: f => f.run.pull_requests = [],
  ambiguousAssociation: f => f.run.pull_requests.push(f.run.pull_requests[0]),
  staleHead: f => f.run.head_sha = D,
  wrongAssociatedHead: f => f.run.pull_requests[0].head.sha = D,
  closed: f => f.pr.state = 'closed',
  forkCurrent: f => f.pr.head.repo.full_name = 'fork/FireMUD',
  wrongBaseRepository: f => f.pr.base.repo.full_name = 'fork/FireMUD',
  unsupportedBase: f => f.pr.base.ref = 'feature/stack',
  missingLabels: f => delete f.pr.labels,
  malformedLabels: f => f.pr.labels = [{name: 3}],
  appBot: f => f.pr.user.login = 'app/renovate',
  bot: f => f.pr.user.login = 'renovate[bot]',
  currentBase: f => f.run.pull_requests[0].base.sha = B,
  invalidBase: f => f.base.object.sha = 'invalid',
  invalidMerge: f => f.pr.merge_commit_sha = 'invalid',
  wrongMerge: f => f.merge.sha = D,
  missingParent: f => f.merge.parents.pop(),
  wrongBaseParent: f => f.merge.parents[0].sha = D,
  wrongHeadParent: f => f.merge.parents[1].sha = D,
})) test(`fails closed: ${name}`, async () => {
  const f = fixture(); mutate(f); assert.deepEqual(await execute(f), []);
});
test('observer checks out only trusted policy and has no artifact or candidate execution', () => {
  assert.deepEqual(workflow.permissions, {});
  assert.deepEqual(workflow.jobs.refresh.permissions,
    {actions: 'read', contents: 'write', 'pull-requests': 'read'});
  assert.equal(workflow.jobs.refresh.steps.length, 2);
  assert.deepEqual(workflow.jobs.refresh.steps[0].with,
    {ref: '${{ github.event.repository.default_branch }}', 'persist-credentials': false});
  assert.match(workflow.jobs.refresh.steps[1].uses, /^actions\/github-script@[0-9a-f]{40}$/);
  assert.doesNotMatch(script, /downloadArtifact|downloadWorkflowRunArtifact|spawn\(/);
});

test('priority stacked base refreshes even when source base is current', async () => {
  const f = fixture(); f.pr.base.ref = 'feature/stack';
  f.pr.labels = [{name: 'preview:priority'}]; f.run.pull_requests[0].base.sha = B;
  const sent = await execute(f);
  assert.equal(sent.length, 1); assert.equal(sent[0].client_payload.base_ref, 'feature/stack');
});

test('canonical non-runtime base image selection does not dispatch', async () => {
  const f = fixture(); f.imageTag = B; assert.deepEqual(await execute(f), []);
});
test('unexpected canonical image selection fails closed', async () => {
  const f = fixture(); f.imageTag = 'untrusted'; await assert.rejects(execute(f), /Unexpected image/);
});
