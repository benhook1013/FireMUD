const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const {createHash} = require('node:crypto');
const {test} = require('node:test');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const admitRuntimeRefresh = require('../../.github/scripts/admit-pr-runtime-refresh.cjs');
const workflow = JSON.parse(execFileSync('python3', ['-c',
  'import yaml,json,sys; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))',
  path.join(root, '.github/workflows/preview-runtime-refresh.yml')], {encoding: 'utf8'}));
const script = workflow.jobs.refresh.steps[1].with.script;
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const E = 'e'.repeat(40);
const A = 'a'.repeat(40), B = 'b'.repeat(40), C = 'c'.repeat(40), D = 'd'.repeat(40);

function admissionFixture() {
  const input = {pr_number: '7', base_ref: 'develop', base_sha: B, head_sha: C, merge_sha: A};
  const repository = 'example/FireMUD';
  const runtimePath = '.github/workflows/runtime-images.yml';
  const admissionPath = '.github/workflows/admit-pr-runtime-refresh.yml';
  const makeRun = (overrides = {}) => ({
    id: 300, run_attempt: 1, workflow_id: 42, event: 'repository_dispatch', status: 'queued', head_sha: D,
    conclusion: null, path: runtimePath, head_branch: 'develop', repository: {full_name: repository},
    head_repository: {full_name: repository}, created_at: new Date().toISOString(),
    display_title: `Build Runtime Images secure-pr-artifact pr-7 base-${B} head-${C} merge-${A} mode-required`,
    ...overrides,
  });
  const makePullRequestRun = (overrides = {}) => makeRun({
    event: 'pull_request', head_sha: C,
    pull_requests: [{number: 7, head: {sha: C}, base: {sha: B, ref: input.base_ref}}],
    ...overrides,
  });
  const sent = [];
  const warnings = [];
  const messages = [];
  const outputs = new Map();
  const runs = [];
  const admissionRuns = new Map();
  const admissionAttempts = new Map();
  const claimRefs = new Map();
  const claimTags = new Map();
  const receiverJobs = new Map();
  const state = {base: B, mergeParents: [B, C], keepHidden: false, afterInventory: null,
    labels: [{name: 'preview:priority'}], nextId: 301, lookups: [], pullObservations: [],
    baseReads: 0, commitRefs: [], missingExactCommitReads: 0, waits: [], producerReads: []};
  const github = {rest: {
    pulls: {get: async ({pull_number}) => {
      assert.equal(pull_number, 7);
      return {data: {number: 7, state: 'open', head: {sha: C, repo: {full_name: repository}},
        base: {ref: input.base_ref, repo: {full_name: repository}}, mergeable: true,
        mergeable_state: 'clean', merge_commit_sha: A, labels: state.labels, user: {login: 'developer'},
        ...state.pullObservations.shift()}};
    }},
    git: {
      listMatchingRefs: async ({ref}) => ({status: 200, headers: {}, data: [...claimRefs.values()]
        .filter(entry => entry.ref.startsWith(`refs/${ref}`))}),
      getTag: async ({tag_sha}) => ({data: claimTags.get(tag_sha)}),
      createTag: async ({tag, message, object, type, tagger}) => {
        const sha = String(claimTags.size + 1).padStart(40, '0');
        const record = {sha, tag, message, object: {type, sha: object}, tagger};
        claimTags.set(sha, record);
        return {status: 201, data: record};
      },
      createRef: async ({ref, sha}) => {
        assert.ok(claimTags.has(sha));
        if (claimRefs.has(ref)) throw Object.assign(new Error('ref already exists'), {status: 422});
        const record = {ref, object: {type: 'tag', sha}};
        claimRefs.set(ref, record);
        if (/^refs\/tags\/firemud-runtime-refresh-(unsent|rejected|pre-send-unavailable)\//.test(ref)) {
          if (state.loseLocatorResponse) throw new Error('response lost after locator creation');
          return {status: 201, data: record};
        }
        state.afterClaim?.();
        if (state.loseClaimResponse) throw new Error('response lost after ref creation');
        return {status: 201, data: record};
      },
      getRef: async ({ref}) => {
        if (ref.startsWith('tags/')) {
          if (!claimRefs.has(`refs/${ref}`)) throw Object.assign(new Error('ref not found'), {status: 404});
          return {data: claimRefs.get(`refs/${ref}`)};
        }
        assert.equal(ref, `heads/${input.base_ref}`);
        state.baseReads++;
        return {data: {ref: `refs/heads/${input.base_ref}`, object: {sha: state.base}}};
      },
    },
    actions: {
      getWorkflow: async ({workflow_id}) => {
        if (workflow_id === 'admit-pr-runtime-refresh.yml') return {data: {id: 43, path: admissionPath}};
        assert.equal(workflow_id, 'runtime-images.yml');
        return {data: {id: 42, path: runtimePath}};
      },
      getWorkflowRun: async ({run_id}) => {
        if (admissionRuns.has(run_id)) return {data: {...admissionRuns.get(run_id)}};
        state.producerReads.push(run_id);
        const run = runs.find(run => run.id === run_id);
        return {data: state.refreshProducer ? state.refreshProducer(run) : run};
      },
      getWorkflowRunAttempt: async ({owner, repo, run_id, attempt_number}) => {
        assert.equal(owner, 'example');
        assert.equal(repo, 'FireMUD');
        const key = `${run_id}:${attempt_number}`;
        state.ownerAttemptReads ??= [];
        state.ownerAttemptReads.push(key);
        return {status: 200, data: admissionAttempts.get(key)};
      },
      listJobsForWorkflowRunAttempt: async ({run_id, attempt_number}) => {
        state.receiverJobReads ??= [];
        state.receiverJobReads.push(`${run_id}:${attempt_number}`);
        const jobs = receiverJobs.get(`${run_id}:${attempt_number}`) || [];
        return {data: {total_count: jobs.length, jobs}};
      },
      listWorkflowRuns: async ({workflow_id, status, event, per_page, page, created}) => {
        assert.equal(workflow_id, 42);
        assert.equal(per_page, 100);
        assert.ok(Number.isSafeInteger(page) && page > 0 && page <= 3);
        state.lookups.push({status, event, per_page, page, created});
        state.afterInventory?.();
        const matching = runs.filter(run => (!status || run.status === status) && run.event === event &&
          (!created || (() => {
            const [start, end] = created.split('..');
            const timestamp = Date.parse(run.created_at);
            return timestamp >= Date.parse(start) && (end === '*' || timestamp < Date.parse(end) + 1000);
          })()));
        return {data: {workflow_runs: matching.slice((page - 1) * per_page, page * per_page),
          total_count: matching.length}};
      },
    },
    repos: {
      getCommit: async ({ref}) => {
        state.commitRefs.push(ref);
        if (state.alternateCommit?.sha === ref) return {data: state.alternateCommit};
        assert.equal(ref, A);
        if (state.missingExactCommitReads > 0) {
          state.missingExactCommitReads--;
          throw Object.assign(new Error('merge not yet visible'), {status: 404});
        }
        return {data: {sha: A, parents: state.mergeParents.map(sha => ({sha}))}};
      },
      createDispatchEvent: async payload => {
        sent.push(payload);
        if (state.dispatchError) throw state.dispatchError;
        // Registration becomes visible after the fixture's first two-second wait.
        state.newRun = makeRun({id: state.nextId++, created_at: state.dispatchCreatedAt ??
          new Date(Date.now() + 2000).toISOString()});
        if (state.loseDispatchResponse) throw new Error('response lost after dispatch acceptance');
        return {status: state.dispatchStatus ?? 204};
      },
    },
  }};
  const admission = {github, input, admissionAttempt: '1',
    context: {repo: {owner: 'example', repo: 'FireMUD'}, runId: 600, ref: 'refs/heads/develop', sha: D,
      eventName: 'repository_dispatch',
      payload: {action: 'pr-runtime-base-refresh-request', repository: {full_name: repository, default_branch: 'develop'}}},
  core: {warning: value => warnings.push(value), info: value => messages.push(value),
    setOutput: (name, value) => outputs.set(name, value)},
  sleep: async milliseconds => {
    state.waits.push(milliseconds);
    state.afterWait?.();
    if (!state.keepHidden && state.newRun && !runs.includes(state.newRun)) runs.push(state.newRun);
  }};
  return {admission, makeRun, makePullRequestRun, runs, admissionRuns, admissionAttempts, sent, state,
    warnings, messages, outputs, claimRefs, claimTags, receiverJobs};
}

function preparePreviewRefusal(f) {
  f.admission.input.base_ref = 'stacked';
  f.admission.input.preview_only = true;
  f.state.afterClaim = () => { f.state.labels = []; };
}

function failFinalIdentityRead(f) {
  const read = f.admission.github.rest.pulls.get;
  let unavailable = false;
  f.state.afterClaim = () => { unavailable = true; };
  f.admission.github.rest.pulls.get = async request => {
    if (unavailable) throw Object.assign(new Error('temporarily unavailable'), {status: 503});
    return read(request);
  };
  return () => { unavailable = false; };
}

function completeOwnerReceiptAttempt(f, kind = 'unsent') {
  const run = f.makeRun({id: 600, workflow_id: 43, path: '.github/workflows/admit-pr-runtime-refresh.yml',
    status: 'completed', conclusion: 'success'});
  const job = {id: 2, run_id: run.id, head_sha: run.head_sha, name: 'Admit Exact Runtime Tuple',
    status: 'completed', conclusion: 'success',
    steps: [{name: `Runtime refresh ${kind} claim ${f.outputs.get(`${kind}_claim`)}`,
      status: 'completed', conclusion: 'success'}]};
  f.admissionRuns.set(run.id, run);
  f.receiverJobs.set(`${run.id}:${run.run_attempt}`, [job]);
  return {run, job};
}

function dispatchValidationError() {
  const url = 'https://api.github.com/repos/example/FireMUD/dispatches';
  return Object.assign(new Error('Validation Failed'), {
    status: 422,
    request: {method: 'POST', url},
    response: {status: 422, url, headers: {'x-github-request-id': 'A1:B2:C3:D4', 'x-ratelimit-remaining': '42'},
      data: {message: 'Validation Failed',
        documentation_url: 'https://docs.github.com/rest/repos/repos#create-a-repository-dispatch-event',
        errors: [{code: 'invalid', field: 'client_payload', message: 'provider response detail'}]}},
  });
}

async function admitOrdinaryRefresh(f) {
  delete f.admission.input.preview_only;
  delete f.state.afterClaim;
  f.admission.context.runId = 601;
  return admitRuntimeRefresh(f.admission);
}

function proveFixtureDispatchBase(f, run) {
  const {input} = f.admission;
  const base = createHash('sha256').update(input.base_ref).digest('hex');
  const tag = `firemud-runtime-refresh-intent/v1/pr-${input.pr_number}/${input.merge_sha}/${base}/g1`;
  const ref = `refs/tags/${tag}`;
  let sha = f.claimRefs.get(ref)?.object.sha;
  if (!sha) {
    sha = E;
    f.claimTags.set(sha, {
      sha, tag, message: JSON.stringify({...input, generation: '1', witness_sha256: 'a'.repeat(64)}) + '\n',
      object: {type: 'commit', sha: input.merge_sha},
      tagger: {name: 'FireMUD Runtime Refresh', email: 'runtime-refresh@firemud.dev',
        date: new Date(Date.parse(run.created_at) - 1000).toISOString().replace(/\.\d{3}Z$/, 'Z')},
    });
    f.claimRefs.set(ref, {ref, object: {type: 'tag', sha}});
  }
  f.receiverJobs.set(`${run.id}:${run.run_attempt}`, [{
    id: 1, run_id: run.id, run_attempt: run.run_attempt, head_sha: run.head_sha,
    name: 'Derive Runtime Image Metadata', status: 'completed',
    steps: [{
      name: `Runtime refresh claim generation 1 attempt ${run.run_attempt} tag ${sha}`,
      status: 'completed', conclusion: 'success',
    }],
  }]);
}

test('serialized admission waits for the first exact receiver before releasing the permit', async () => {
  const f = admissionFixture();
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  proveFixtureDispatchBase(f, f.runs[0]);
  assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
  const [tag] = f.claimTags.values();
  const {runtime_refresh_claim_witness: witness} = f.sent[0].client_payload;
  assert.match(witness, /^[0-9a-f]{64}$/);
  assert.deepEqual(tag.object, {type: 'commit', sha: D});
  assert.equal(tag.message, JSON.stringify({
    ...f.admission.input,
    generation: '1',
    witness_sha256: createHash('sha256').update(witness).digest('hex'),
    owner_revision: D,
  }) + '\n');
  assert.deepEqual(f.sent, [{owner: 'example', repo: 'FireMUD', event_type: 'pr-runtime-base-refresh',
    client_payload: {...f.admission.input, image_tag: `pr-merge-${A}`, runtime_refresh_claim: '1',
      runtime_refresh_claim_witness: witness},
    request: {retries: 0, redirect: 'error'}}]);
});

test('a non-native admission context cannot create a tag or dispatch', async t => {
  for (const overrides of [
    {sha: undefined}, {sha: 'invalid'}, {ref: 'refs/heads/feature'}, {eventName: 'workflow_dispatch'},
  ]) {
    await t.test(JSON.stringify(overrides), async () => {
      const f = admissionFixture();
      Object.assign(f.admission.context, overrides);
      assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
      assert.equal(f.claimTags.size, 0);
      assert.equal(f.sent.length, 0);
    });
  }
});

test('read-only identity retries require a fresh exact tuple', async () => {
  for (const [group, method] of [['pulls', 'get'], ['git', 'getRef'], ['repos', 'getCommit']]) {
    const f = admissionFixture();
    const api = f.admission.github.rest[group];
    const original = api[method];
    let reads = 0;
    api[method] = async (...args) => {
      if (++reads === 1) throw Object.assign(new Error('unavailable'), {response: {status: 503}});
      return original(...args);
    };
    f.runs.push(f.makePullRequestRun());
    assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
    assert.deepEqual(f.state.waits, [2000]);
    assert.equal(f.sent.length, 0);
  }

  const changed = admissionFixture();
  const api = changed.admission.github.rest.repos;
  api.getCommit = async () => {
    changed.state.base = D;
    throw Object.assign(new Error('unavailable'), {status: 503});
  };
  assert.equal(await admitRuntimeRefresh(changed.admission), 'obsolete');
  assert.deepEqual(changed.state.waits, [2000]);
  assert.equal(changed.sent.length, 0);
});

test('a newer exact failure visible during live identity lookup defeats stale active suppression', async () => {
  const f = admissionFixture();
  const old = f.makePullRequestRun({id: 220, created_at: '2024-01-01T00:00:00Z'});
  const failed = f.makePullRequestRun({
    id: 221, created_at: '2024-01-01T00:00:01Z', status: 'completed', conclusion: 'failure',
  });
  f.runs.push(old);
  const api = f.admission.github.rest.pulls;
  const readPull = api.get;
  let identityReads = 0;
  api.get = async (...args) => {
    if (++identityReads === 2) {
      assert.ok(f.state.producerReads.includes(old.id), 'old active proof must precede live tuple lookup');
      f.runs.push(failed);
    }
    return readPull(...args);
  };
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent.length, 1);
  assert.ok(identityReads >= 4, 'changed inventory must recheck tuple before claim and dispatch');
});

test('identity retries are bounded and do not retry authorization failure', async () => {
  for (const [status, observations] of [[503, 6], [403, 1]]) {
    const f = admissionFixture();
    let reads = 0;
    const error = Object.assign(new Error('cannot observe identity'), {status});
    f.admission.github.rest.pulls.get = async () => {
      reads++;
      throw error;
    };
    await assert.rejects(admitRuntimeRefresh(f.admission), value => value === error);
    assert.equal(reads, observations);
    assert.deepEqual(f.state.waits, Array(observations - 1).fill(2000));
    assert.equal(f.sent.length, 0);
  }
});

test('completed producers still permit an exact repair', async () => {
  for (const conclusion of ['success', 'failure']) {
    const f = admissionFixture();
    f.runs.push(f.makeRun({status: 'completed', conclusion}));
    assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
    assert.equal(f.sent.length, 1);
  }
});

test('a newer completed dispatch displaces an active PR producer only with the exact base-ref claim', async () => {
  for (const bindsRequestedBase of [true, false]) {
    const f = admissionFixture();
    f.runs.push(f.makePullRequestRun({id: 299,
      created_at: new Date(Date.now() - 70 * 60 * 1000).toISOString()}));
    const completed = f.makeRun({id: 300, status: 'completed', conclusion: 'failure',
      created_at: new Date(Date.now() - 60000).toISOString()});
    f.runs.push(completed);
    if (!bindsRequestedBase) f.admission.input.base_ref = 'another-base-ref';
    proveFixtureDispatchBase(f, completed);
    f.admission.input.base_ref = 'develop';
    assert.equal(await admitRuntimeRefresh(f.admission),
      bindsRequestedBase ? 'dispatched-observed' : 'already-active');
    assert.equal(f.sent.length, bindsRequestedBase ? 1 : 0);
  }
});

test('a still-active exact producer is not excluded by age or pagination', async () => {
  const f = admissionFixture();
  f.runs.push(...Array.from({length: 100}, (_, i) => f.makePullRequestRun({
    id: 400 + i, display_title: 'unrelated work',
  })));
  f.runs.push(f.makePullRequestRun({created_at: '2024-01-01T00:00:00Z'}));
  assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
  assert.deepEqual(f.sent, []);
  assert.ok(f.state.lookups.some(call => call.status === 'queued' && call.page === 2 && !call.created));
});

test('serialized admission reapplies preview policy after the inventory read only for preview requests', async () => {
  for (const previewOnly of [false, true]) {
    const f = admissionFixture();
    f.admission.input.base_ref = 'feature/stack';
    if (previewOnly) f.admission.input.preview_only = true;
    f.state.afterInventory = () => { f.state.labels = []; };
    assert.equal(await admitRuntimeRefresh(f.admission), previewOnly ? 'obsolete' : 'dispatched-observed');
    assert.equal(f.sent.length, previewOnly ? 0 : 1);
  }
});

test('preview marker stays within the admission request and the schema stays closed', async () => {
  const f = admissionFixture();
  f.admission.input.preview_only = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(Object.hasOwn(f.sent[0].client_payload, 'preview_only'), false);
  for (const invalid of [{preview_only: 'true'}, {preview_only: false}, {preview_only: true, extra: true}]) {
    const bad = admissionFixture();
    Object.assign(bad.admission.input, invalid);
    await assert.rejects(admitRuntimeRefresh(bad.admission), /Invalid runtime refresh input/);
    assert.deepEqual(bad.sent, []);
  }
});

test('admission refuses a base that moves after the inventory read', async () => {
  const f = admissionFixture();
  f.state.afterInventory = () => { f.state.base = D; };
  assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete');
  assert.deepEqual(f.sent, []);
});

test('admission refuses the exact merge SHA when its ordered parents disagree', async () => {
  const f = admissionFixture();
  f.state.mergeParents = [C, B];
  assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete');
  assert.deepEqual(f.sent, []);
});

test('admission rereads PR and base through pending mergeability, a stale base parent and missing test merge', async () => {
  const f = admissionFixture();
  f.state.pullObservations = [
    {mergeable: null, merge_commit_sha: null},
    {mergeable_state: 'unknown'},
    {merge_commit_sha: E},
  ];
  f.state.alternateCommit = {sha: E, parents: [{sha: D}, {sha: C}]};
  f.state.missingExactCommitReads = 1;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.deepEqual(f.state.commitRefs, [E, A, A, A, A]);
  assert.equal(f.state.baseReads, 7);
  assert.deepEqual(f.state.waits, [2000, 2000, 2000, 2000, 2000]);
  assert.equal(f.sent[0].client_payload.merge_sha, A);
});

test('admission bounds waiting for merge computation and aborts immediately on definitive drift', async () => {
  const pending = {mergeable: null, merge_commit_sha: null};
  for (const [scenario, configure, expectedWaits, expectedRefs] of [
    ['persistent calculation', f => { f.state.pullObservations = Array(6).fill(pending); }, 5, 6],
    ['conflict', f => { f.state.pullObservations = [pending, {mergeable: false, mergeable_state: 'dirty'}]; }, 1, 1],
    ['changed head', f => { f.state.pullObservations = [pending,
      {head: {sha: D, repo: {full_name: 'example/FireMUD'}}}]; }, 1, 1],
    ['moved base', f => {
      f.state.pullObservations = [pending];
      f.state.afterWait = () => { f.state.base = D; };
    }, 1, 2],
    ['different merge for exact parents', f => {
      f.state.pullObservations = [{merge_commit_sha: E}];
      f.state.alternateCommit = {sha: E, parents: [{sha: B}, {sha: C}]};
    }, 0, 1],
  ]) {
    const f = admissionFixture();
    configure(f);
    if (scenario === 'persistent calculation') {
      await assert.rejects(admitRuntimeRefresh(f.admission), /comparison is still unavailable/, scenario);
    } else {
      assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete', scenario);
    }
    assert.deepEqual(f.sent, [], scenario);
    assert.deepEqual(f.state.lookups, [], scenario);
    assert.equal(f.state.waits.length, expectedWaits, scenario);
    assert.equal(f.state.baseReads, expectedRefs, scenario);
  }
});

test('admission suppresses only when the selected active producer still proves the exact attempt', async () => {
  const f = admissionFixture();
  f.runs.push(f.makePullRequestRun());
  f.state.refreshProducer = run => ({...run, status: 'in_progress'});
  assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
  assert.deepEqual(f.state.producerReads, [300, 300]);
  assert.deepEqual(f.sent, []);
});

test('a stale or unavailable selected producer leaves an exact refresh eligible', async () => {
  const invalidProducers = [
    () => { throw Object.assign(new Error('missing run'), {status: 404}); },
    () => null,
    run => ({...run, id: run.id + 1}),
    run => ({...run, run_attempt: run.run_attempt + 1}),
    ...[undefined, null, 0, -1, '1'].map(run_attempt => run => ({...run, run_attempt})),
    run => ({...run, event: 'repository_dispatch'}),
    run => ({...run, display_title: run.display_title.replace(A, E)}),
    run => ({...run, head_sha: E}),
    run => ({...run, created_at: new Date(Date.parse(run.created_at) + 1000).toISOString()}),
    run => ({...run, status: 'completed', conclusion: 'success'}),
    run => ({...run, status: 'completed', conclusion: 'failure'}),
    run => ({...run, status: 'completed', conclusion: 'cancelled'}),
  ];
  for (const afterFinalTuple of [false, true]) {
    for (const refreshProducer of invalidProducers) {
      const f = admissionFixture();
      f.runs.push(f.makePullRequestRun());
      f.state.refreshProducer = run => !afterFinalTuple || f.state.commitRefs.length >= 2 ?
        refreshProducer(run) : run;
      assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
      assert.deepEqual(f.state.producerReads, afterFinalTuple ? [300, 300] : [300]);
      assert.deepEqual(f.state.commitRefs, afterFinalTuple ? [A, A, A, A] : [A, A, A]);
      assert.equal(f.sent.length, 1);
    }
  }
});

test('a producer that completes while the final live tuple is pending cannot suppress repair', async () => {
  const f = admissionFixture();
  f.runs.push(f.makePullRequestRun());
  f.state.pullObservations = [{}, {mergeable: null, merge_commit_sha: null}];
  f.state.afterWait = () => { f.runs[0] = {...f.runs[0], status: 'completed', conclusion: 'failure'}; };
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.deepEqual(f.state.producerReads, [300]);
  assert.ok(f.state.lookups.some(call => call.event === 'pull_request' && call.status === 'completed'));
  assert.deepEqual(f.state.commitRefs, [A, A, A, A]);
  assert.deepEqual(f.state.waits, [2000, 2000]);
  assert.equal(f.sent.length, 1);
});

test('admission rereads the pinned comparison after the producer lookup before fallback', async () => {
  for (const afterFinalTuple of [false, true]) {
    const f = admissionFixture();
    f.runs.push(f.makePullRequestRun());
    f.state.refreshProducer = run => {
      if (afterFinalTuple && f.state.commitRefs.length < 2) return run;
      f.state.base = D;
      return {...run, status: 'completed', conclusion: 'failure'};
    };
    assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete');
    assert.deepEqual(f.state.producerReads, afterFinalTuple ? [300, 300] : [300]);
    assert.deepEqual(f.sent, []);
  }
});

test('only a same-repository exact native producer can suppress the repair', async () => {
  const matched = admissionFixture();
  matched.runs.push(matched.makePullRequestRun());
  assert.equal(await admitRuntimeRefresh(matched.admission), 'already-active');
  assert.deepEqual(matched.state.producerReads, [300, 300]);
  assert.deepEqual(matched.sent, []);

  const foreign = admissionFixture();
  foreign.runs.push(foreign.makePullRequestRun({
    head_repository: {full_name: 'example/Other'},
  }));
  assert.equal(await admitRuntimeRefresh(foreign.admission), 'dispatched-observed');
  assert.deepEqual(foreign.state.producerReads, []);
  assert.equal(foreign.sent.length, 1);
});

test('a native producer for a different base ref at the same SHA leaves refresh eligible', async () => {
  const f = admissionFixture();
  f.runs.push(f.makePullRequestRun({
    pull_requests: [{number: 7, head: {sha: C}, base: {sha: B, ref: 'main'}}],
  }));
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.deepEqual(f.state.producerReads, []);
  assert.equal(f.sent.length, 1);
});

test('indeterminate dispatch visibility does not silently consume a repair or cause an immediate duplicate', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  assert.equal(f.sent.length, 1);
  assert.ok(f.warnings.length > 0);
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
});

test('a lost dispatch acknowledgement keeps the exact tuple held across callbacks', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  f.state.loseDispatchResponse = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
  f.state.loseDispatchResponse = false;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
  assert.equal(f.claimRefs.size, 1);
});

test('a lost atomic claim acknowledgement does not authorize either callback to send', async () => {
  const f = admissionFixture();
  f.state.loseClaimResponse = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.claimRefs.size, 1);
  f.state.loseClaimResponse = false;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.deepEqual(f.sent, []);
});

test('an unexpected dispatch response status cannot be described as accepted', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  f.state.dispatchStatus = 202;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
});

test('an exact native provider rejection receipt allows one currently valid successor', async () => {
  const f = admissionFixture();
  f.state.dispatchError = dispatchValidationError();
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatch-rejected');
  assert.equal(f.sent.length, 1);
  assert.deepEqual(f.state.waits, []);
  const [claim] = f.claimTags.values();
  const ref = `refs/tags/firemud-runtime-refresh-rejected/v1/${claim.sha}/600/1`;
  assert.deepEqual(f.claimRefs.get(ref), {ref, object: {type: 'tag', sha: claim.sha}});
  assert.deepEqual([...f.outputs], [['rejected_claim', `generation 1 run 600 attempt 1 tag ${claim.sha}`]]);
  const witness = f.sent[0].client_payload.runtime_refresh_claim_witness;
  const outputText = [...f.warnings, ...f.messages, ...f.outputs.values()].join('\n');
  assert.ok(!outputText.includes(witness));
  assert.ok(!outputText.includes('provider response detail'));

  delete f.state.dispatchError;
  assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
  completeOwnerReceiptAttempt(f, 'rejected');
  assert.equal(await admitOrdinaryRefresh(f), 'dispatched-observed');
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['1', '2']);
});

test('ambiguous dispatch responses never produce rejection or no-send receipts', async t => {
  const variants = {
    'bare 422': e => { delete e.response; },
    'response status mismatch': e => { e.response.status = 429; },
    'thrown status mismatch': e => { e.status = 503; },
    'different response endpoint': e => { e.response.url += '/unexpected'; },
    'different request method': e => { e.request.method = 'GET'; },
    'missing provider request ID': e => { delete e.response.headers['x-github-request-id']; },
    'retry-after header': e => { e.response.headers['Retry-After'] = '60'; },
    'rate limit exhausted': e => { e.response.headers['x-ratelimit-remaining'] = '0'; },
    'secondary throttle header': e => { e.response.headers['X-GitHub-Throttle'] = 'on'; },
    'secondary limit message': e => { e.message = 'secondary rate limit'; },
    'spam message': e => { e.response.data.message = 'The endpoint has been spammed'; },
    'abuse detail': e => { e.response.data.errors[0].message = 'abuse prevention'; },
    'wrong documentation link': e => { e.response.data.documentation_url = 'https://docs.github.com/rest'; },
    'malformed body': e => { e.response.data = 'Validation Failed'; },
    'no field errors': e => { e.response.data.errors = []; },
    'unknown validation code': e => { e.response.data.errors[0].code = 'custom'; },
    'unrelated validation field': e => { e.response.data.errors[0].field = 'other'; },
  };
  for (const [name, change] of Object.entries(variants)) {
    await t.test(name, async () => {
      const f = admissionFixture();
      f.state.dispatchError = dispatchValidationError();
      change(f.state.dispatchError);
      assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
      assert.equal(f.outputs.size, 0);
      assert.equal(f.claimRefs.size, 1);
      delete f.state.dispatchError;
      assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
      assert.equal(f.sent.length, 1);
    });
  }
});

test('a rejected claim stays held if the locator acknowledgment or the marker write fails', async t => {
  await t.test('locator response lost', async () => {
    const f = admissionFixture();
    f.state.dispatchError = dispatchValidationError();
    f.state.loseLocatorResponse = true;
    assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
    assert.equal(f.claimRefs.size, 2);
    assert.equal(f.outputs.size, 0);
    completeOwnerReceiptAttempt(f, 'rejected');
    assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
    assert.equal(f.sent.length, 1);
  });
  await t.test('marker output write failed', async () => {
    const f = admissionFixture();
    f.state.dispatchError = dispatchValidationError();
    f.admission.core.setOutput = () => { throw new Error('output unavailable'); };
    await assert.rejects(admitRuntimeRefresh(f.admission), /output unavailable/);
    assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
    assert.equal(f.sent.length, 1);
  });
});

test('a comparison that moves during rejection receipt lookup cannot reserve a successor generation', async () => {
  const f = admissionFixture();
  f.state.dispatchError = dispatchValidationError();
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatch-rejected');
  completeOwnerReceiptAttempt(f, 'rejected');
  const readJobs = f.admission.github.rest.actions.listJobsForWorkflowRunAttempt;
  f.admission.github.rest.actions.listJobsForWorkflowRunAttempt = async request => {
    f.state.base = D;
    return readJobs(request);
  };
  assert.equal(await admitOrdinaryRefresh(f), 'obsolete');
  assert.equal(f.claimTags.size, 1);
  assert.equal(f.sent.length, 1);
});

test('admission retries a transient identity read after acknowledging the claim', async () => {
  const f = admissionFixture();
  const exactPull = f.admission.github.rest.pulls.get;
  let pending = false;
  let transientReads = 0;
  f.state.afterClaim = () => { pending = true; };
  f.admission.github.rest.pulls.get = async request => {
    if (pending) {
      pending = false;
      transientReads++;
      throw Object.assign(new Error('temporarily unavailable'), {status: 502});
    }
    return exactPull(request);
  };
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(transientReads, 1);
  assert.equal(f.claimRefs.size, 1);
  assert.equal(f.sent.length, 1);
  const dispatch = f.sent[0].client_payload;
  assert.equal(dispatch.runtime_refresh_claim, '1');
  assert.equal(dispatch.merge_sha, A);
});

test('a comparison moving during the claim is never dispatched', async () => {
  const f = admissionFixture();
  f.state.afterClaim = () => { f.state.base = D; };
  assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete');
  assert.equal(f.claimRefs.size, 2);
  assert.deepEqual(f.sent, []);
});

test('an ordinary claim can resume after the exact final merge computation becomes available', async () => {
  const f = admissionFixture();
  f.state.afterClaim = () => {
    f.state.pullObservations = Array(6).fill({mergeable: null, merge_commit_sha: null});
  };
  assert.equal(await admitRuntimeRefresh(f.admission), 'pre-send-unavailable');
  assert.deepEqual(f.sent, []);
  assert.match(f.outputs.get('pre-send-unavailable_claim'), /^generation 1 run 600 attempt 1 tag [0-9a-f]{40}$/);
  assert.equal(f.outputs.has('unsent_claim'), false);
  completeOwnerReceiptAttempt(f, 'pre-send-unavailable');

  assert.equal(await admitOrdinaryRefresh(f), 'dispatched-observed');
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['2']);
});

test('an exact successful native preview refusal allows ordinary CI to claim the next generation', async () => {
  const f = admissionFixture();
  preparePreviewRefusal(f);
  assert.equal(await admitRuntimeRefresh(f.admission), 'obsolete');
  assert.deepEqual(f.sent, []);
  const [claim] = f.claimTags.values();
  const locatorRef = `refs/tags/firemud-runtime-refresh-unsent/v1/${claim.sha}/600/1`;
  assert.deepEqual(f.claimRefs.get(locatorRef), {ref: locatorRef, object: {type: 'tag', sha: claim.sha}});
  assert.equal(f.outputs.get('unsent_claim'), `generation 1 run 600 attempt 1 tag ${claim.sha}`);
  completeOwnerReceiptAttempt(f);

  assert.equal(await admitOrdinaryRefresh(f), 'dispatched-observed');
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['2']);
});

test('only the successful current admission attempt can prove an unsent or rejected claim', async t => {
  const mismatches = {
    'duplicate locator': (f, {kind}) => {
      const locator = [...f.claimRefs.values()].find(ref => ref.ref.includes(`/firemud-runtime-refresh-${kind}/`));
      const ref = locator.ref.replace('/600/1', '/600/2');
      f.claimRefs.set(ref, {...locator, ref});
    },
    'missing native run': f => f.admissionRuns.clear(),
    'wrong workflow': (f, {run}) => { run.workflow_id = 42; },
    'wrong event': (f, {run}) => { run.event = 'workflow_dispatch'; },
    'wrong branch': (f, {run}) => { run.head_branch = 'stacked'; },
    'wrong qualified path': (f, {run}) => { run.path += '@refs/heads/stacked'; },
    'wrong repository': (f, {run}) => { run.head_repository.full_name = 'fork/FireMUD'; },
    'wrong owner revision': (f, {run, job}) => { run.head_sha = E; job.head_sha = E; },
    'superseded attempt': (f, {run}) => { run.run_attempt++; },
    'failed run': (f, {run}) => { run.conclusion = 'failure'; },
    'wrong job head': (f, {job}) => { job.head_sha = E; },
    'skipped marker': (f, {job}) => { job.steps[0].conclusion = 'skipped'; },
    'duplicate marker': (f, {job}) => { job.steps.push(job.steps[0]); },
    'wrong tag': (f, {job}) => { job.steps[0].name = job.steps[0].name.replace(/[0-9a-f]{40}$/, E); },
    'wrong marker kind': (f, {job, kind}) => {
      job.steps[0].name = job.steps[0].name.replace(kind, kind === 'unsent' ? 'rejected' : 'unsent');
    },
    'attempt moves during marker read': (f, {run}) => {
      const read = f.admission.github.rest.actions.listJobsForWorkflowRunAttempt;
      f.admission.github.rest.actions.listJobsForWorkflowRunAttempt = async request => {
        run.run_attempt++;
        return read(request);
      };
    },
  };
  for (const kind of ['unsent', 'rejected', 'pre-send-unavailable']) {
    for (const [name, change] of Object.entries(mismatches)) {
      await t.test(`${kind}: ${name}`, async () => {
        const f = admissionFixture();
        let restoreIdentity;
        if (kind === 'unsent') preparePreviewRefusal(f);
        else if (kind === 'rejected') f.state.dispatchError = dispatchValidationError();
        else restoreIdentity = failFinalIdentityRead(f);
        assert.equal(await admitRuntimeRefresh(f.admission),
          {unsent: 'obsolete', rejected: 'dispatch-rejected', 'pre-send-unavailable': kind}[kind]);
        restoreIdentity?.();
        change(f, {...completeOwnerReceiptAttempt(f, kind), kind});
        delete f.state.dispatchError;
        assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
        assert.equal(f.sent.length, kind === 'rejected' ? 1 : 0);
      });
    }
  }
});

test('final identity failure before dispatch records a distinct native claim receipt', async () => {
  const f = admissionFixture();
  const restoreIdentity = failFinalIdentityRead(f);
  assert.equal(await admitRuntimeRefresh(f.admission), 'pre-send-unavailable');
  const [claim] = f.claimTags.values();
  const ref = `refs/tags/firemud-runtime-refresh-pre-send-unavailable/v1/${claim.sha}/600/1`;
  assert.deepEqual(f.claimRefs.get(ref), {ref, object: {type: 'tag', sha: claim.sha}});
  assert.equal(f.outputs.get('pre-send-unavailable_claim'), `generation 1 run 600 attempt 1 tag ${claim.sha}`);
  assert.deepEqual(f.sent, []);
  completeOwnerReceiptAttempt(f, 'pre-send-unavailable');
  restoreIdentity();

  f.state.base = E;
  assert.equal(await admitOrdinaryRefresh(f), 'obsolete');
  assert.deepEqual(f.sent, []);
  f.state.base = B;
  assert.equal(await admitOrdinaryRefresh(f), 'dispatched-observed');
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['2']);
});

async function historicalOwnerReceiptFixture(kind = 'pre-send-unavailable') {
  const f = admissionFixture();
  let restoreIdentity;
  if (kind === 'unsent') preparePreviewRefusal(f);
  else if (kind === 'rejected') f.state.dispatchError = dispatchValidationError();
  else restoreIdentity = failFinalIdentityRead(f);
  assert.equal(await admitRuntimeRefresh(f.admission),
    {unsent: 'obsolete', rejected: 'dispatch-rejected', 'pre-send-unavailable': kind}[kind]);
  restoreIdentity?.();
  delete f.state.dispatchError;

  const {run, job} = completeOwnerReceiptAttempt(f, kind);
  const latest = {...run, run_attempt: 2, status: 'completed', conclusion: 'failure'};
  f.admissionRuns.set(run.id, latest);
  f.admissionAttempts.set('600:1', run);
  return {f, run, job, latest};
}

test('exact native pre-send-unavailable proof survives a stable newer owner attempt', async t => {
  for (const state of [{status: 'completed', conclusion: 'failure'}, {status: 'in_progress', conclusion: null}]) {
    await t.test(state.status, async () => {
      const {f, latest} = await historicalOwnerReceiptFixture();
      Object.assign(latest, state);
      assert.equal(await admitOrdinaryRefresh(f), 'dispatched-observed');
      assert.deepEqual(f.state.ownerAttemptReads, ['600:1']);
      assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['2']);
    });
  }
});

test('historical owner evidence holds the claim when attribution or the no-send proof is uncertain', async t => {
  const failures = {
    'missing attempt': ({f}) => f.admissionAttempts.clear(),
    'unavailable attempt': ({f}) => {
      f.admission.github.rest.actions.getWorkflowRunAttempt = async () => {
        throw Object.assign(new Error('temporarily unavailable'), {status: 503});
      };
    },
    'wrong attempt': ({run}) => { run.run_attempt = 2; },
    'wrong owner revision': ({run, job}) => { run.head_sha = E; job.head_sha = E; },
    'unsuccessful exact attempt': ({run}) => { run.conclusion = 'cancelled'; },
    'missing exact-attempt jobs': ({f}) => f.receiverJobs.clear(),
    'wrong job attempt': ({job}) => { job.run_attempt = 2; },
    'absent marker': ({job}) => { job.steps = []; },
    'wrong marker attempt': ({job}) => { job.steps[0].name = job.steps[0].name.replace('attempt 1', 'attempt 2'); },
    'wrong marker generation': ({job}) => { job.steps[0].name = job.steps[0].name.replace('generation 1', 'generation 2'); },
    'ambiguous outcome': ({job}) => {
      job.steps.push({...job.steps[0], name: job.steps[0].name.replace('pre-send-unavailable', 'rejected')});
    },
    'malformed latest state': ({latest}) => { latest.conclusion = 'unknown'; },
    'wrong latest owner revision': ({latest}) => { latest.head_sha = E; },
    'latest attempt advances during proof': ({f, latest}) => {
      const read = f.admission.github.rest.actions.listJobsForWorkflowRunAttempt;
      f.admission.github.rest.actions.listJobsForWorkflowRunAttempt = async request => {
        latest.run_attempt++;
        return read(request);
      };
    },
    'legacy claim': ({f}) => {
      const [tag] = f.claimTags.values();
      const annotation = JSON.parse(tag.message);
      delete annotation.owner_revision;
      tag.message = JSON.stringify(annotation) + '\n';
      tag.object.sha = A;
    },
  };
  for (const [name, change] of Object.entries(failures)) {
    await t.test(name, async () => {
      const fixture = await historicalOwnerReceiptFixture();
      const {f} = fixture;
      change(fixture);
      assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
      assert.deepEqual(f.sent, []);
      assert.equal(f.claimTags.size, 1);
    });
  }
});

test('historical unsent and rejected receipts stay held despite exact successful native evidence', async t => {
  for (const kind of ['unsent', 'rejected']) {
    await t.test(kind, async () => {
      const {f} = await historicalOwnerReceiptFixture(kind);
      assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
      assert.equal(f.state.ownerAttemptReads, undefined);
      assert.equal(f.claimTags.size, 1);
      assert.equal(f.sent.length, kind === 'rejected' ? 1 : 0);
    });
  }
});

test('an unacknowledged final identity failure receipt leaves the claim held', async () => {
  const f = admissionFixture();
  f.state.loseLocatorResponse = true;
  const restoreIdentity = failFinalIdentityRead(f);
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.outputs.size, 0);
  completeOwnerReceiptAttempt(f, 'pre-send-unavailable');
  restoreIdentity();
  assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
  assert.deepEqual(f.sent, []);
});

test('a lost locator acknowledgement does not produce a preview refusal marker', async () => {
  const f = admissionFixture();
  preparePreviewRefusal(f);
  f.state.loseLocatorResponse = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.claimRefs.size, 2);
  assert.equal(f.outputs.size, 0);
  completeOwnerReceiptAttempt(f);
  assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
  assert.deepEqual(f.sent, []);
});

test('losing preview claim ownership acknowledgement never creates a refusal locator', async () => {
  const f = admissionFixture();
  preparePreviewRefusal(f);
  f.state.loseClaimResponse = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.claimRefs.size, 1);
  assert.equal(f.outputs.size, 0);
  assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
  assert.deepEqual(f.sent, []);
});

test('a failed preview refusal output write fails the owning step', async () => {
  const f = admissionFixture();
  preparePreviewRefusal(f);
  f.admission.core.setOutput = () => { throw new Error('output unavailable'); };
  await assert.rejects(admitRuntimeRefresh(f.admission), /output unavailable/);
  assert.deepEqual(f.sent, []);
  assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
});

test('a preview that invokes dispatch with ambiguous acceptance cannot record a refusal', async () => {
  const f = admissionFixture();
  preparePreviewRefusal(f);
  delete f.state.afterClaim;
  f.state.loseDispatchResponse = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.claimRefs.size, 1);
  assert.equal(f.outputs.size, 0);
  assert.equal(await admitOrdinaryRefresh(f), 'claim-held');
  assert.equal(f.sent.length, 1);
});

test('advancing a generation requires one exact terminal receiver with a successful claim marker', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  const run = {...f.state.newRun, created_at: new Date().toISOString(), status: 'completed', conclusion: 'failure'};
  f.runs.push(run);
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
  const [tag] = f.claimTags.values();
  const jobs = [{id: 1, run_id: run.id, run_attempt: run.run_attempt, head_sha: run.head_sha,
    name: 'Derive Runtime Image Metadata', status: 'completed', conclusion: 'success',
    steps: [{name: `Runtime refresh claim generation 1 attempt 1 tag ${tag.sha}`,
      status: 'completed', conclusion: 'success'}]}];
  const legacy = f.makeRun({id: 299, created_at: run.created_at, status: 'completed', conclusion: 'success'});
  f.runs.push(legacy);
  f.receiverJobs.set(`${legacy.id}:${legacy.run_attempt}`, [{...jobs[0], id: 2, run_id: legacy.id,
    steps: [{name: 'Runtime refresh claim generation  attempt  tag ', status: 'completed', conclusion: 'skipped'}]}]);
  f.receiverJobs.set(`${run.id}:${run.run_attempt}`, [{...jobs[0], run_attempt: run.run_attempt + 1}]);
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 1);
  delete jobs[0].run_attempt;
  f.receiverJobs.set(`${run.id}:${run.run_attempt}`, jobs);
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['1', '2']);
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 2);
});

test('terminal receiver search subdivides a crowded creation interval', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  const now = Math.floor(Date.now() / 1000) * 1000;
  const run = f.makeRun({id: 100, created_at: new Date(now - 120000).toISOString(),
    status: 'completed', conclusion: 'failure'});
  f.runs.push(run);
  proveFixtureDispatchBase(f, run);
  f.runs.push(...Array.from({length: 301}, (_, index) => f.makeRun({
    id: index + 1000, status: 'completed', conclusion: 'success',
    created_at: new Date(now - 100000 + index * 250).toISOString(),
    display_title: 'Unrelated protected-branch build',
  })));
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['2']);
  assert.ok(f.state.lookups.filter(call => call.status === 'completed').length > 1);
});

test('attempt-scoped receiver accepts a sibling job without a redundant attempt field', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  const [tag] = f.claimTags.values();
  const run = {...f.state.newRun, created_at: new Date().toISOString(), status: 'completed', conclusion: 'failure', run_attempt: 2};
  f.runs.push(run);
  f.receiverJobs.set(`${run.id}:2`, [
    {id: 1, run_id: run.id, run_attempt: 2, head_sha: run.head_sha,
      name: 'Derive Runtime Image Metadata', status: 'completed', conclusion: 'success',
      steps: [{name: `Runtime refresh claim generation 1 attempt 2 tag ${tag.sha}`,
        status: 'completed', conclusion: 'success'}]},
    {id: 2, run_id: run.id, head_sha: run.head_sha, name: 'Build Runtime Images',
      status: 'completed', conclusion: 'failure'},
  ]);
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['1', '2']);
});

test('a successful marker carried from another attempt cannot advance the claim', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  const [tag] = f.claimTags.values();
  const run = {...f.state.newRun, created_at: new Date().toISOString(), status: 'completed', conclusion: 'failure', run_attempt: 2};
  f.runs.push(run);
  f.receiverJobs.set(`${run.id}:2`, [{id: 1, run_id: run.id, run_attempt: 2, head_sha: run.head_sha,
    name: 'Derive Runtime Image Metadata', status: 'completed', conclusion: 'success',
    steps: [{name: `Runtime refresh claim generation 1 attempt 1 tag ${tag.sha}`,
      status: 'completed', conclusion: 'success'}]}]);
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.ok(f.state.receiverJobReads.includes(`${run.id}:2`), 'attempt-2 receiver jobs must be read before the wrong marker holds the claim');
  assert.equal(f.sent.length, 1);
});

test('an equal commit on a different base ref gets an independent exact claim', async () => {
  const f = admissionFixture();
  f.state.keepHidden = true;
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  f.admission.input.base_ref = 'feature/stack';
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
  assert.equal(f.claimRefs.size, 2);
  assert.deepEqual(f.sent.map(({client_payload}) => client_payload.runtime_refresh_claim), ['1', '1']);
});

test('a claim with inconsistent annotation or tag target stays held despite a native terminal receiver', async t => {
  const variants = {
    'different target': record => { record.object.sha = A; },
    'malformed owner revision': record => {
      record.message = JSON.stringify({...JSON.parse(record.message), owner_revision: null}) + '\n';
      record.object.sha = A;
    },
    'noncanonical annotation bytes': record => { record.message += '\n'; },
  };
  for (const [name, change] of Object.entries(variants)) {
    await t.test(name, async () => {
      const f = admissionFixture();
      assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
      const [run] = f.runs;
      run.status = 'completed';
      run.conclusion = 'success';
      proveFixtureDispatchBase(f, run);
      const [record] = f.claimTags.values();
      change(record);
      assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
      assert.equal(f.sent.length, 1);
    });
  }
});

test('an ambiguous legacy merge-target claim retains its generation until a terminal receiver proves it', async () => {
  const f = admissionFixture();
  const legacyRun = f.makeRun({status: 'completed', conclusion: 'success'});
  proveFixtureDispatchBase(f, legacyRun);
  const prior = structuredClone(f.claimTags.get(E));
  assert.equal(await admitRuntimeRefresh(f.admission), 'claim-held');
  assert.equal(f.sent.length, 0);

  f.runs.push(legacyRun);
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent[0].client_payload.runtime_refresh_claim, '2');
  assert.deepEqual(f.claimTags.get(E), prior);
  const successor = [...f.claimTags.values()].find(tag => tag.tag.endsWith('/g2'));
  assert.deepEqual(successor.object, {type: 'commit', sha: D});
  assert.equal(JSON.parse(successor.message).owner_revision, D);
});

test('unseen same-second activity is ambiguous dispatch attribution and never triggers a retry', async t => {
  t.mock.method(Date, 'now', () => Date.parse('2026-11-22T12:00:00.750Z'));
  for (const milliseconds of ['000', '749', '750', '999']) {
    const f = admissionFixture();
    f.state.keepHidden = true;
    const unseen = f.makeRun({created_at: `2026-11-22T12:00:00.${milliseconds}Z`});
    f.state.afterWait = () => { if (!f.runs.includes(unseen)) f.runs.push(unseen); };
    assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-unconfirmed');
    assert.equal(f.sent.length, 1);
    assert.equal(f.state.waits.length, 7);
    assert.ok(f.warnings.some(warning => warning.includes('No exact active runtime producer became visible')));
  }
});

test('unseen exact activity in the next second can confirm bounded dispatch observation', async t => {
  t.mock.method(Date, 'now', () => Date.parse('2026-11-22T12:00:00.750Z'));
  const f = admissionFixture();
  f.state.dispatchCreatedAt = '2026-11-22T12:00:01Z';
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent.length, 1);
  assert.deepEqual(f.state.waits, [2000]);
});

test('active dispatch suppression proves the exact base-ref claim and current attempt', async () => {
  const f = admissionFixture();
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  const run = f.runs[0];
  const [ref] = f.claimRefs.values();
  const [delivery] = f.sent;
  const generation = delivery.client_payload.runtime_refresh_claim;
  f.receiverJobs.set(`${run.id}:${run.run_attempt}`, [{
    id: 1, name: 'Derive Runtime Image Metadata', status: 'completed',
    run_id: run.id, run_attempt: run.run_attempt, head_sha: run.head_sha,
    steps: [{
      name: `Runtime refresh claim generation ${generation} attempt ${run.run_attempt} tag ${ref.object.sha}`,
      status: 'completed', conclusion: 'success',
    }],
  }]);
  assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
  assert.equal(f.sent.length, 1);

  f.admission.input.base_ref = 'stack/retargeted';
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent.length, 2);
  assert.equal(f.sent[1].client_payload.base_ref, 'stack/retargeted');
  assert.equal(f.claimRefs.size, 2);
});

test('an unmarked native producer without the exact-base claim cannot suppress claim admission', async () => {
  const f = admissionFixture();
  f.runs.push(f.makeRun());
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent.length, 1);
});

test('both wake inputs retain intent in one non-replacing exact tuple queue on the default branch', () => {
  const admissionWorkflow = JSON.parse(execFileSync('python3', ['-c',
    'import yaml,json,sys; print(json.dumps(yaml.load(open(sys.argv[1]), Loader=yaml.BaseLoader)))',
    path.join(root, '.github/workflows/admit-pr-runtime-refresh.yml')], {encoding: 'utf8'}));
  assert.deepEqual(admissionWorkflow.on, {repository_dispatch: {types: ['pr-runtime-base-refresh-request']}});
  const job = admissionWorkflow.jobs.dispatch;
  assert.equal(job.if, "${{ github.event.action == 'pr-runtime-base-refresh-request' && github.ref == format('refs/heads/{0}', github.event.repository.default_branch) }}");
  assert.deepEqual(job.concurrency, {
    group: 'admit-pr-runtime-refresh-${{ github.event.client_payload.pr_number }}-${{ github.event.client_payload.base_sha }}-${{ github.event.client_payload.head_sha }}-${{ github.event.client_payload.merge_sha }}',
    'cancel-in-progress': 'false',
    queue: 'max',
  });
  assert.deepEqual(job.permissions, {actions: 'read', contents: 'write', 'pull-requests': 'read'});
  assert.deepEqual(job.steps.find(step => step.uses.startsWith('actions/checkout@')).with,
    {ref: '${{ github.workflow_sha }}', 'persist-credentials': 'false'});
  assert.match(job.steps.find(step => step.id === 'admit').with.script, /input: context.payload.client_payload/);
  for (const [kind, output] of [
    ['unsent', 'steps.admit.outputs.unsent_claim'],
    ['rejected', 'steps.admit.outputs.rejected_claim'],
    ['pre-send-unavailable', "steps.admit.outputs['pre-send-unavailable_claim']"],
  ]) {
    const marker = job.steps.find(step => step.name.startsWith(`Runtime refresh ${kind} claim `));
    assert.equal(marker.name, `Runtime refresh ${kind} claim \${{ ${output} }}`);
    assert.equal(marker.if, `\${{ success() && ${output} != '' }}`);
    assert.equal(marker['continue-on-error'], undefined);
  }
});

const tupleStep = (kind, base, merge) => ({
  name: `Preview ${kind} tuple pr-7 base-${base} head-${C} merge-${merge}`,
  status: 'completed', conclusion: 'success',
});
function fixture() {
  return {
    run: {id: 99, run_attempt: 2, display_title: `PR preview 7 base-${D} head-${C} merge-${E}`, path: '.github/workflows/preview.yml', event: 'pull_request', status: 'completed', conclusion: 'success',
      repository: {full_name: 'example/FireMUD'}, head_repository: {full_name: 'example/FireMUD'},
      head_sha: C, pull_requests: [{number: 7, head: {sha: C}, base: {sha: D}}]},
    pr: {number: 7, state: 'open', head: {sha: C, repo: {full_name: 'example/FireMUD'}},
      base: {ref: 'develop', repo: {full_name: 'example/FireMUD'}},
      merge_commit_sha: A, mergeable: true, mergeable_state: 'clean', labels: [], user: {login: 'developer'}},
    jobs: [{name: 'Preview Plan', run_id: 99, run_attempt: 2, head_sha: C, workflow_name: 'PR Preview Environment', status: 'completed', conclusion: 'success',
      steps: [tupleStep('event', D, E), tupleStep('resolved', D, E)]}],
    recordedMerge: {sha: E, parents: [{sha: D}, {sha: C}]},
    base: {object: {sha: B}}, merge: {sha: A, parents: [{sha: B}, {sha: C}]},
  };
}
async function execute(f, sent = []) {
  const github = {request: async (route, args) => {
    assert.equal(route, 'GET /repos/{owner}/{repo}/actions/runs/{run_id}/attempts/{attempt_number}/jobs');
    assert.equal(args.run_id, 99); assert.equal(args.attempt_number, 2);
    assert.equal(args.per_page, 100); assert.ok(args.page <= 3);
    return {data: f.inventory ?? {total_count: f.jobs.length, jobs: f.jobs}};
  }, rest: {
    actions: {
      getWorkflowRun: async ({run_id}) => {
        assert.equal(run_id, 99);
        f.runReads = (f.runReads ?? 0) + 1;
        return {data: f.runReads === 1 ? f.run : f.latestRun ?? f.run};
      },
      listWorkflowRuns: async () => assert.fail('only admission may inspect runtime producers'),
    },
    pulls: {get: async ({pull_number}) => {
      assert.equal(pull_number, f.pr.number);
      f.pullReads = (f.pullReads ?? 0) + 1;
      return {data: f.pullHistory?.[f.pullReads - 1] ?? (f.pullReads === 1 ? f.pr : f.latestPr ?? f.pr)};
    }},
    git: {getRef: async ({ref}) => {
      assert.equal(ref, 'heads/' + f.pr.base.ref);
      f.baseReads = (f.baseReads ?? 0) + 1;
      return {data: f.baseReads === 1 ? f.base : f.latestBase ?? f.base};
    }},
    repos: {
      getCommit: async ({ref}) => {
        (f.mergeReads ??= []).push(ref);
        return {data: f.mergeReads.length === 1 ? f.recordedMerge :
          f.pendingMerges?.[ref] ?? (ref === f.resolvedMerge?.sha ? f.resolvedMerge : f.merge)};
      },
      createDispatchEvent: async p => sent.push(p),
    },
  }};
  await new AsyncFunction('github', 'context', 'core', 'require', script)(github,
    {repo: {owner: 'example', repo: 'FireMUD'}, payload: {workflow_run: {id: 99, run_attempt: 2}, repository: {default_branch: 'develop'}}}, {info(message) {(f.messages ??= []).push(message);}}, name => {
      if (name === 'node:timers/promises') {
        return {setTimeout: async delay => { assert.equal(delay, 2000); f.pauses = (f.pauses ?? 0) + 1; }};
      }
      assert.equal(name, 'node:child_process');
      return {execFileSync(command, args, options) {
        if (command === 'python3') {
          assert.deepEqual(args, ['./dev-tools/hosted/preview/preview-eligibility.py',
            '--revalidate-deploy', '--expected-repository', 'example/FireMUD',
            '--expected-head-sha', C]);
          assert.deepEqual(JSON.parse(options.input),
            f.pullHistory?.[f.pullReads - 1] ?? (f.pullReads === 1 ? f.pr : f.latestPr ?? f.pr));
          return execFileSync(command, args, {...options, cwd: root});
        }
        assert.equal(command, 'bash');
        assert.deepEqual(args, ['./dev-tools/hosted/preview/resolve-preview-image-tag.sh',
          f.pr.merge_commit_sha, '7', f.base.object.sha]);
        return f.imageTag ?? `pr-merge-${f.pr.merge_commit_sha}`;
      }};
    });
  return sent;
}
test('exact stale-base current tuple produces only the typed refresh', async () => {
  const f = fixture();
  assert.deepEqual(await execute(f), [{owner: 'example', repo: 'FireMUD',
    event_type: 'pr-runtime-base-refresh-request', client_payload: {pr_number: '7', base_ref: 'develop',
      base_sha: B, head_sha: C, merge_sha: A, preview_only: true}}]);
  assert.deepEqual(f.mergeReads, [E, A]);
});
test('a merge-reported source can request exact images for an eligible stacked PR', async () => {
  for (const nativeBinding of ['association', 'qualified-path', 'bare-path']) {
    const f = fixture();
    f.run.head_sha = A;
    if (nativeBinding === 'qualified-path') f.run.path += '@refs/pull/7/merge';
    if (nativeBinding !== 'association') f.run.pull_requests = [];
    f.jobs[0].head_sha = A;
    f.jobs[0].steps = [tupleStep('event', B, A), tupleStep('resolved', B, A)];
    f.recordedMerge = f.merge;
    f.pr.base.ref = 'feature/stack';
    f.pr.labels = [{name: 'preview:priority'}];
    const bound = nativeBinding !== 'bare-path';
    assert.equal((await execute(f)).length, bound ? 1 : 0, nativeBinding);
    assert.deepEqual(f.mergeReads, bound ? [A] : undefined, nativeBinding);
  }
});

test('a merge-reported source must prove the recorded merge parent order', async () => {
  const f = fixture();
  f.run.head_sha = E;
  f.jobs[0].head_sha = E;
  f.recordedMerge.parents.reverse();
  assert.deepEqual(await execute(f), []);
});

test('a completed render failure permits repair when the plan was eligible', async () => {
  const f = fixture(); f.run.conclusion = 'failure';
  assert.equal((await execute(f)).length, 1);
});
test('the pinned Jobs endpoint identifies a plan without a row-level attempt number', async () => {
  const f = fixture(); delete f.jobs[0].run_attempt;
  assert.equal((await execute(f)).length, 1);
});
for (const [name, mutate] of Object.entries({
  wrongPath: f => f.run.path = '.github/workflows/attacker.yml',
  wrongQualifiedPath: f => f.run.path += '@refs/pull/8/merge',
  wrongEvent: f => f.run.event = 'repository_dispatch',
  unfinishedSource: f => f.run.status = 'in_progress',
  cancelledSource: f => f.run.conclusion = 'cancelled',
  failedPlan: f => { f.run.conclusion = 'failure'; f.jobs[0].conclusion = 'failure'; },
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
  legacyTitle: f => f.jobs[0].steps = [],
  missingTitle: f => f.jobs = [],
  spoofTitleHead: f => f.jobs[0].steps[0].name = `Preview event tuple pr-7 base-${D} head-${B} merge-${E}`,
  badRecordedParent: f => f.recordedMerge.parents[0].sha = B,
  missingRecordedParent: f => f.recordedMerge.parents.pop(),
  wrongRun: f => f.run.id = 100,
  wrongAttempt: f => f.run.run_attempt = 3,
  wrongJobAttempt: f => f.jobs[0].run_attempt = 3,
  nullJobAttempt: f => f.jobs[0].run_attempt = null,
  duplicatePlan: f => f.jobs.push(f.jobs[0]),
  duplicateTuple: f => f.jobs[0].steps.push(f.jobs[0].steps[0]),
  failedTuple: f => f.jobs[0].steps[0].conclusion = "failure",
  skippedResolvedTuple: f => f.jobs[0].steps[1].conclusion = 'skipped',
  missingResolvedTuple: f => f.jobs[0].steps.pop(),
  badResolvedParent: f => f.jobs[0].steps[1] = tupleStep('resolved', B, E),
  ambiguousResolvedTuple: f => f.jobs[0].steps.push(f.jobs[0].steps[1]),
  malformedTuple: f => f.jobs[0].steps[0].name = "Preview event tuple invalid",
  invalidBase: f => f.base.object.sha = 'invalid',
  invalidMerge: f => f.pr.merge_commit_sha = 'invalid',
  wrongMerge: f => f.merge.sha = D,
  missingParent: f => f.merge.parents.pop(),
  wrongBaseParent: f => f.merge.parents[0].sha = D,
  wrongHeadParent: f => f.merge.parents[1].sha = D,
})) test(`fails closed: ${name}`, async () => {
  const f = fixture(); mutate(f); assert.deepEqual(await execute(f), []);
});
test('matching event, plan and current integration merges need no refresh', async () => {
  const f = fixture();
  f.jobs[0].steps = [tupleStep('event', B, E), tupleStep('resolved', B, E)];
  f.recordedMerge.parents[0].sha = B;
  f.pr.merge_commit_sha = E;
  assert.deepEqual(await execute(f), []);
  assert.deepEqual(f.mergeReads, [E]);
});

test('current integration base returning to the event base still repairs a stale plan', async () => {
  const f = fixture();
  f.jobs[0].steps = [tupleStep('event', B, E), tupleStep('resolved', D, A)];
  f.recordedMerge.parents[0].sha = B;
  f.pr.merge_commit_sha = E;
  f.resolvedMerge = {sha: A, parents: [{sha: D}, {sha: C}]};
  f.imageTag = `pr-merge-${E}`;
  const sent = await execute(f);
  assert.deepEqual(f.mergeReads, [E, A]);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].client_payload.merge_sha, E);
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
  f.pr.labels = [{name: 'preview:priority'}]; f.run.pull_requests[0].base.sha = D;
  f.base.object.sha = D; f.merge.parents[0].sha = D;
  const sent = await execute(f);
  assert.equal(sent.length, 1); assert.equal(sent[0].client_payload.base_ref, 'feature/stack');
});

test('canonical non-runtime base image selection does not dispatch', async () => {
  const f = fixture(); f.imageTag = B; assert.deepEqual(await execute(f), []);
});
test('unexpected canonical image selection fails closed', async () => {
  const f = fixture(); f.imageTag = 'untrusted';
  const sent = [];
  await assert.rejects(execute(f, sent), /Unexpected image/);
  assert.deepEqual(sent, []);
});

test('qualified canonical workflow path remains eligible', async () => {
  const f = fixture(); f.run.path += '@refs/pull/7/merge';
  assert.equal((await execute(f)).length, 1);
});
test('a malformed qualified workflow path is refused before any commit lookup', async () => {
  const f = fixture();
  f.run.path = '.github/workflows/preview.yml@';
  assert.deepEqual(await execute(f), []);
  assert.deepEqual(f.mergeReads ?? [], []);
});
test('observer callbacks cannot replace a different source run or attempt', () => {
  const group = workflow.jobs.refresh.concurrency.group;
  assert.match(group, /workflow_run.id/);
  assert.match(group, /workflow_run.run_attempt/);
  assert.equal(workflow.jobs.refresh.concurrency['cancel-in-progress'], false);
});

test('older base classifiers cannot omit newly introduced validation modules', async () => {
  const ci = JSON.parse(execFileSync('python3', ['-c',
    'import yaml,json,sys; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))',
    path.join(root, '.github/workflows/ci.yml')], {encoding: 'utf8'}));
  const compute = ci.jobs.changes.steps.find(step => step.id === 'compute').with.script;
  const complete = JSON.parse(compute.match(/const completeModules = (\[[\s\S]*?\]);/)[1].replace(/,\s*\]/, "]"));
  for (const modules of [undefined, complete.filter(name => name !== 'recovery-controller-service'), complete]) {
    const outputs = {};
    const changedFile = 'services/account-service/src/main/java/Example.java';
    let classified = false;
    const github = {rest: {repos: {compareCommitsWithBasehead: async ({basehead}) => {
      assert.equal(basehead, B + '...' + C);
      return {data: {base_commit: {sha: B}, merge_base_commit: {sha: B}, status: 'ahead',
        files: [{filename: changedFile, status: 'modified'}]}};
    }}}};
    const context = {eventName: 'pull_request', payload: {pull_request: {
      base: {sha: B}, head: {sha: C}, changed_files: 1,
    }}};
    await new AsyncFunction('require', 'github', 'context', 'core', compute)(
      name => name === 'node:fs' ? {existsSync: () => true} : {
        ALL_MODULES: modules,
        classifyChangeScope: paths => {
          classified = true;
          assert.deepEqual(paths, [changedFile]);
          return {runAll: false, affectedModules: ['account-service'], bootableModules: ['account-service'],
            affectedServices: ['account-service'], lightweightOnly: true};
        },
      }, github, context, {setOutput: (name, value) => outputs[name] = value, warning() {}});
    assert.equal(classified, true);
    if (modules === complete) {
      assert.equal(outputs.run_all, 'false');
      assert.deepEqual(JSON.parse(outputs.affected_modules), ['account-service']);
    } else {
      assert.equal(outputs.run_all, 'true');
      assert.equal(outputs.lightweight_only, 'false');
      assert.ok(JSON.parse(outputs.affected_modules).includes('recovery-controller-service'));
      assert.ok(JSON.parse(outputs.bootable_modules).includes('recovery-controller-service'));
    }
  }
});

function producer(f, event = 'repository_dispatch') {
  return f.makeRun({id: 100, event, head_sha: event === 'pull_request' ? C : D,
    status: 'in_progress', pull_requests: [{number: 7, head: {sha: C}, base: {sha: B, ref: f.admission.input.base_ref}}]});
}
for (const event of ['repository_dispatch', 'pull_request']) {
  for (const status of ['queued', 'waiting', 'requested', 'pending', 'in_progress']) {
    test(`admission suppresses dispatch for an active exact ${event} ${status} producer`, async () => {
      const f = admissionFixture();
      const run = {...producer(f, event), status};
      f.runs.push(run);
      if (event === 'repository_dispatch') proveFixtureDispatchBase(f, run);
      assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
      assert.deepEqual(f.sent, []);
    });
  }
}
for (const [name, mutate] of Object.entries({
  otherPR: r => r.display_title = r.display_title.replace('pr-7 ', 'pr-8 '),
  otherBase: r => r.display_title = r.display_title.replace(B, D),
  otherHead: r => r.display_title = r.display_title.replace(C, D),
  otherMerge: r => r.display_title = r.display_title.replace(A, D),
  wrongWorkflow: r => r.workflow_id = 9,
  wrongPath: r => r.path = '.github/workflows/attacker.yml',
  wrongRepo: r => r.repository.full_name = 'fork/FireMUD',
  fork: r => r.head_repository.full_name = 'fork/FireMUD',
  wrongEvent: r => r.event = 'workflow_dispatch',
  wrongDispatchBranch: r => r.head_branch = 'attacker',
  completed: r => {r.status = 'completed'; r.conclusion = 'success';},
  inconsistentConclusion: r => r.conclusion = 'failure',
})) test(`unrelated/untrusted producer cannot suppress recovery: ${name}`, async () => {
  const f = admissionFixture();
  const event = name === 'wrongRepo' || name === 'fork' ? 'pull_request' : 'repository_dispatch';
  const r = producer(f, event);
  f.runs.push(r);
  if (event === 'repository_dispatch') {
    proveFixtureDispatchBase(f, r);
    assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
  }
  mutate(r);
  const expected = event === 'pull_request' || name === 'completed' ? 'dispatched-observed' : 'claim-held';
  assert.equal(await admitRuntimeRefresh(f.admission), expected);
  assert.equal(f.sent.length, expected === 'dispatched-observed' ? 1 : 0);
});
for (const [name, mutate] of Object.entries({
  head: r => r.head_sha = D,
  associationMissing: r => r.pull_requests = [],
  associationPR: r => r.pull_requests[0].number = 8,
  associationBase: r => r.pull_requests[0].base.sha = D,
  associationHead: r => r.pull_requests[0].head.sha = D,
})) test(`PR title alone cannot suppress: ${name}`, async () => {
  const f = admissionFixture(); const r = producer(f, 'pull_request'); mutate(r); f.runs.push(r);
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent.length, 1);
});
test('admission retains the refresh fallback when active inventory exceeds its bound', async () => {
  const f = admissionFixture();
  f.runs.push(...Array.from({length: 301}, (_, i) => f.makeRun({
    id: 400 + i, display_title: 'unrelated work', created_at: '2024-01-01T00:00:00Z',
  })));
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
  assert.equal(f.sent.length, 1);
  assert.ok(f.warnings.some(warning => warning.includes('inventory was incomplete')));
});
test('qualified trusted default workflow path suppresses exact dispatch', async () => {
  const f = admissionFixture(); const r = producer(f);
  r.path += '@refs/heads/develop'; f.runs.push(r);
  proveFixtureDispatchBase(f, r);
  assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
});
test('canonical eligibility refuses absent author metadata', async () => {
  const f = fixture(); delete f.pr.user; assert.deepEqual(await execute(f), []);
});

test('qualified exact PR merge workflow path suppresses native producer', async () => {
  const f = admissionFixture(); const r = producer(f, 'pull_request');
  r.path += '@refs/pull/7/merge'; f.runs.push(r);
  assert.equal(await admitRuntimeRefresh(f.admission), 'already-active');
});
test('qualified other PR workflow path cannot suppress native producer', async () => {
  const f = admissionFixture(); const r = producer(f, 'pull_request');
  r.path += '@refs/pull/8/merge'; f.runs.push(r);
  assert.equal(await admitRuntimeRefresh(f.admission), 'dispatched-observed');
});

test('immutable title repairs base despite current mutable association', async () => {
  const f = fixture();
  f.run.pull_requests[0].base.sha = B;
  f.run.pull_requests.unshift({number: 99, head: {sha: C}, base: {sha: B}});
  assert.equal((await execute(f)).length, 1);
});

test('partial rerun without an exact-attempt plan gives safe full-rerun guidance', async () => {
  const f = fixture(); f.jobs = [];
  assert.deepEqual(await execute(f), []);
  assert.ok(f.messages.some(message => message.includes('Re-run all jobs')));
});

test('priority removed before the final observer fence refuses dispatch', async () => {
  const f = fixture();
  f.pr.base.ref = 'feature/stack';
  f.pr.labels = [{name: 'preview:priority'}];
  f.latestPr = {...f.pr, labels: []};
  assert.deepEqual(await execute(f), []);
  assert.equal(f.pullReads, 2);
});

test('observer waits for a pending test merge and rereads a formerly stale merge tuple', async () => {
  const f = fixture();
  const stale = 'f'.repeat(40);
  f.pullHistory = [{...f.pr, mergeable: null, mergeable_state: 'unknown', merge_commit_sha: null},
    {...f.pr, merge_commit_sha: stale}];
  f.pendingMerges = {[stale]: {sha: stale, parents: [{sha: B}, {sha: D}]}};
  assert.equal((await execute(f)).length, 1);
  assert.deepEqual(f.mergeReads, [E, stale, A]);
  assert.equal(f.baseReads, 4);
  assert.equal(f.pauses, 2);
});

test('observer stops waiting on changed head, ineligibility, or an actual merge conflict', async () => {
  for (const mutate of [
    pr => { pr.head.sha = D; },
    pr => { pr.labels = []; },
    pr => { pr.mergeable = false; pr.mergeable_state = 'dirty'; },
  ]) {
    const f = fixture();
    f.pr.base.ref = 'feature/stack';
    f.pr.labels = [{name: 'preview:priority'}];
    const stopped = structuredClone(f.pr);
    mutate(stopped);
    f.pullHistory = [{...f.pr, mergeable: null, mergeable_state: 'unknown', merge_commit_sha: null}, stopped];
    assert.deepEqual(await execute(f), []);
    assert.equal(f.pullReads, 2);
    assert.deepEqual(f.mergeReads, [E]);
    assert.equal(f.pauses, 1);
  }
});

test('event base drift can repair the independently revalidated current merge', async () => {
  const f = fixture();
  f.jobs[0].steps = [tupleStep('event', D, A), tupleStep('resolved', B, A)];
  f.recordedMerge = structuredClone(f.merge);
  assert.equal((await execute(f)).length, 1);
  assert.deepEqual(f.mergeReads, [A, A]);

  const moved = fixture();
  moved.jobs[0].steps = [tupleStep('event', D, A), tupleStep('resolved', B, A)];
  moved.recordedMerge = structuredClone(moved.merge);
  moved.merge.parents[0].sha = D;
  assert.deepEqual(await execute(moved), []);
  assert.equal(moved.mergeReads.length, 7);
  assert.ok(moved.mergeReads.every(sha => sha === A));
});

test('a resolved historical merge can precede a second base advance', async () => {
  const intermediateBase = 'f'.repeat(40);
  const f = fixture();
  f.recordedMerge.parents[0].sha = intermediateBase;
  f.jobs[0].steps[1] = tupleStep('resolved', intermediateBase, E);
  const dispatches = await execute(f);
  assert.equal(dispatches.length, 1);
  assert.equal(dispatches[0].client_payload.base_sha, B);
  assert.equal(dispatches[0].client_payload.merge_sha, A);
  assert.deepEqual(f.mergeReads, [E, A]);
});

test('a third independently verified merge can be the resolved plan', async () => {
  const plannedMerge = 'f'.repeat(40);
  const plannedBase = '0'.repeat(40);
  for (const exact of [true, false]) {
    const f = fixture();
    f.recordedMerge.parents[0].sha = '1'.repeat(40);
    f.jobs[0].steps[1] = tupleStep('resolved', plannedBase, plannedMerge);
    f.resolvedMerge = {sha: plannedMerge, parents: [{sha: exact ? plannedBase : D}, {sha: C}]};
    const dispatches = await execute(f);
    assert.equal(dispatches.length, exact ? 1 : 0);
    if (exact) assert.equal(dispatches[0].client_payload.merge_sha, A);
    assert.deepEqual(f.mergeReads, [E, A, plannedMerge]);
  }
});

test('a branch ref advance during lookup invalidates the live merge', async () => {
  const f = fixture();
  f.latestBase = {object: {sha: 'f'.repeat(40)}};
  assert.deepEqual(await execute(f), []);
  assert.equal(f.baseReads, 2);
});

test('a newer source attempt during lookup invalidates the plan observation', async () => {
  const f = fixture(); f.latestRun = {...f.run, run_attempt: 3};
  assert.deepEqual(await execute(f), []);
  assert.equal(f.runReads, 2);
});

test('truncated or oversized attempt inventory is refused', async () => {
  for (const inventory of [{total_count: 301, jobs: []}, {total_count: 2, jobs: []}]) {
    const f = fixture(); f.inventory = inventory;
    const sent = [];
    await assert.rejects(execute(f, sent), /inventory/);
    assert.deepEqual(sent, []);
  }
});
