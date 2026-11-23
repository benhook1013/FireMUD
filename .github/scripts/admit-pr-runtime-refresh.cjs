const {execFileSync} = require('node:child_process');
const {createHash, randomBytes} = require('node:crypto');
const {resolve} = require('node:path');

module.exports = async function admitPrRuntimeRefresh({
  github,
  context,
  core,
  input,
  sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)),
}) {
  const runtimePath = '.github/workflows/runtime-images.yml';
  const requiredFields = ['pr_number', 'base_ref', 'base_sha', 'head_sha', 'merge_sha'];
  const shaPattern = /^[0-9a-f]{40}$/;
  const activeStatuses = new Set(['queued', 'waiting', 'requested', 'pending', 'in_progress']);
  const inventoryEvents = ['pull_request', 'repository_dispatch'];
  const inventoryPages = 3;
  const pageSize = 100;
  const inventoryLimit = inventoryPages * pageSize;

  const fail = message => { throw new Error(message); };
  const isRecord = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const isPositiveInteger = value => Number.isSafeInteger(value) && value > 0;
  const isSafeBranchRef = value => {
    if (typeof value !== 'string' || value.length === 0 || value === '@' ||
        value.startsWith('/') || value.endsWith('/') || value.endsWith('.') ||
        value.includes('//') || value.includes('..') || value.includes('@{') ||
        /[\u0000-\u0020\u007f~^:?*\\\[]/.test(value)) return false;
    return value.split('/').every(part => part.length > 0 && part[0] !== '.' && !part.endsWith('.lock'));
  };
  const isNotFound = error => error?.status === 404 || error?.response?.status === 404;
  const dataOf = response => response && isRecord(response.data) ? response.data : null;

  if (!isRecord(input) ||
      Reflect.ownKeys(input).some(field => !requiredFields.includes(field) && field !== 'preview_only') ||
      requiredFields.some(field => !Object.hasOwn(input, field)) ||
      (Object.hasOwn(input, 'preview_only') && input.preview_only !== true)) {
    fail('Invalid runtime refresh input.');
  }
  if (requiredFields.some(field => typeof input[field] !== 'string')) fail('Invalid runtime refresh input.');
  if (!/^[1-9][0-9]*$/.test(input.pr_number) || !Number.isSafeInteger(Number(input.pr_number)) ||
      Number(input.pr_number) <= 0 || !isSafeBranchRef(input.base_ref) ||
      !shaPattern.test(input.base_sha) || !shaPattern.test(input.head_sha) || !shaPattern.test(input.merge_sha)) {
    fail('Invalid runtime refresh input.');
  }

  const repository = context?.payload?.repository;
  const contextRepo = context?.repo;
  if (!isRecord(repository) || !isRecord(contextRepo) ||
      typeof contextRepo.owner !== 'string' || contextRepo.owner.length === 0 ||
      typeof contextRepo.repo !== 'string' || contextRepo.repo.length === 0 ||
      repository.full_name !== `${contextRepo.owner}/${contextRepo.repo}` ||
      !isSafeBranchRef(repository.default_branch)) {
    fail('Repository identity or default branch is unavailable.');
  }
  const fullName = repository.full_name;
  const defaultBranch = repository.default_branch;
  const pullNumber = Number(input.pr_number);

  const observeIdentity = async () => {
    let pullResponse;
    try {
      pullResponse = await github.rest.pulls.get({...contextRepo, pull_number: pullNumber});
    } catch (error) {
      if (isNotFound(error)) return false;
      throw error;
    }
    const pull = dataOf(pullResponse);
    if (!pull || pull.number !== pullNumber || pull.state !== 'open' ||
        pull.head?.repo?.full_name !== fullName || pull.base?.repo?.full_name !== fullName ||
        pull.head?.sha !== input.head_sha || pull.base?.ref !== input.base_ref ||
        (pull.mergeable !== true && pull.mergeable !== null) ||
        typeof pull.mergeable_state !== 'string' ||
        ['dirty', 'conflicting'].includes(pull.mergeable_state)) return false;

    let refResponse;
    try {
      refResponse = await github.rest.git.getRef({...contextRepo, ref: `heads/${input.base_ref}`});
    } catch (error) {
      if (isNotFound(error)) return false;
      throw error;
    }
    const ref = dataOf(refResponse);
    if (ref?.ref !== `refs/heads/${input.base_ref}` || ref.object?.sha !== input.base_sha) return false;

    const reportedMerge = pull.merge_commit_sha;
    if (pull.mergeable === null || pull.mergeable_state === 'unknown' || reportedMerge === null) return null;
    if (typeof reportedMerge !== 'string' || !shaPattern.test(reportedMerge)) return false;

    let commitResponse;
    try {
      commitResponse = await github.rest.repos.getCommit({...contextRepo, ref: reportedMerge});
    } catch (error) {
      if (isNotFound(error)) return null;
      throw error;
    }
    const commit = dataOf(commitResponse);
    if (commit?.sha !== reportedMerge || !Array.isArray(commit.parents) ||
        commit.parents.length !== 2 || commit.parents[1]?.sha !== input.head_sha) return false;
    const mergeBase = commit.parents[0]?.sha;
    if (reportedMerge !== input.merge_sha) {
      // GitHub can still report an older test merge while the pinned base is live.
      return typeof mergeBase === 'string' && shaPattern.test(mergeBase) &&
        mergeBase !== input.base_sha ? null : false;
    }
    if (mergeBase !== input.base_sha) return false;
    if (Object.hasOwn(input, 'preview_only')) {
      try {
        execFileSync('python3', [
          resolve(__dirname, '../../dev-tools/hosted/preview/preview-eligibility.py'), '--revalidate-deploy',
          '--expected-repository', fullName, '--expected-head-sha', input.head_sha,
        ], {input: JSON.stringify(pull), encoding: 'utf8'});
      } catch (error) {
        if (error.status !== 1) throw error;
        core.info('Canonical preview eligibility refused the current PR; no dispatch.');
        return false;
      }
    }
    return true;
  };

  const currentIdentity = async () => {
    for (let observation = 0; observation < 6; observation++) {
      const result = await observeIdentity();
      if (result !== null) return result;
      if (observation < 5) await sleep(2000);
    }
    return false;
  };

  if (!await currentIdentity()) {
    core.info('Runtime refresh tuple is obsolete; no dispatch.');
    return 'obsolete';
  }

  const {data: runtimeWorkflow} = await github.rest.actions.getWorkflow({
    ...contextRepo,
    workflow_id: 'runtime-images.yml',
  });
  if (runtimeWorkflow?.path !== runtimePath || !isPositiveInteger(runtimeWorkflow.id)) {
    fail('Runtime workflow identity is unproven.');
  }

  const expectedTitle = 'Build Runtime Images secure-pr-artifact ' +
    `pr-${input.pr_number} base-${input.base_sha} head-${input.head_sha} merge-${input.merge_sha} mode-required`;
  const exactNativeTuple = run => {
    if (!isRecord(run) || run.workflow_id !== runtimeWorkflow.id ||
        run.display_title !== expectedTitle || run.repository?.full_name !== fullName ||
        run.head_repository?.full_name !== fullName) return false;

    const barePath = run.path === runtimePath;
    if (run.event === 'repository_dispatch') {
      return (barePath || run.path === `${runtimePath}@${defaultBranch}` ||
        run.path === `${runtimePath}@refs/heads/${defaultBranch}`) &&
        run.head_branch === defaultBranch;
    }
    if (run.event === 'pull_request') {
      const associations = run.pull_requests;
      return (barePath || run.path === `${runtimePath}@refs/pull/${pullNumber}/merge`) &&
        run.head_sha === input.head_sha && Array.isArray(associations) && associations.length === 1 &&
        associations[0]?.number === pullNumber && associations[0]?.head?.sha === input.head_sha &&
        associations[0]?.base?.sha === input.base_sha;
    }
    return false;
  };
  const validChronology = value => typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) &&
    Number.isFinite(Date.parse(value));
  const isExactActiveRun = run => exactNativeTuple(run) &&
    isPositiveInteger(run.id) && isPositiveInteger(run.run_attempt) &&
    activeStatuses.has(run.status) && run.conclusion == null && validChronology(run.created_at);
  const secondsUtc = timestamp => new Date(timestamp).toISOString().replace(/\.\d{3}Z$/, 'Z');

  let inventoryComplete = true;
  let newestExact = null;
  const observedRunIds = new Set();
  const exactRuns = [];
  const collectInventory = async (event, status, createdSince, runs = exactRuns, ids = observedRunIds) => {
    let total;
    let seen = 0;
    for (let page = 1; page <= inventoryPages; page++) {
      const {data: inventory} = await github.rest.actions.listWorkflowRuns({
        ...contextRepo, workflow_id: runtimeWorkflow.id, event, status, per_page: pageSize, page,
        ...(createdSince ? {created: `${createdSince}..*`} : {}),
      });
      if (!Number.isSafeInteger(inventory?.total_count) || inventory.total_count < 0 ||
          inventory.total_count > inventoryLimit || !Array.isArray(inventory.workflow_runs) ||
          inventory.workflow_runs.length > pageSize || (total !== undefined && inventory.total_count !== total)) {
        throw new Error('Incomplete runtime run inventory.');
      }
      total = inventory.total_count;
      seen += inventory.workflow_runs.length;
      for (const run of inventory.workflow_runs) {
        if (run?.event !== event || run.status !== status) {
          throw new Error('Runtime inventory contains an unexpected event or status.');
        }
        if (!exactNativeTuple(run)) continue;
        if (!isPositiveInteger(run.id) || !isPositiveInteger(run.run_attempt) ||
            !validChronology(run.created_at) ||
            (createdSince && Date.parse(run.created_at) < Date.parse(createdSince))) {
          throw new Error('Exact runtime run has invalid native chronology.');
        }
        const identity = `${run.event}:${run.id}:${run.run_attempt}`;
        if (ids.has(identity)) throw new Error('Duplicate exact runtime run identity.');
        ids.add(identity);
        if (activeStatuses.has(run.status)) {
          if (run.conclusion != null) throw new Error('Active runtime run has a conclusion.');
        } else if (run.status !== 'completed' || typeof run.conclusion !== 'string' || run.conclusion.length === 0) {
          throw new Error('Exact runtime run status is unclear.');
        }
        runs.push(run);
      }
      if (seen === total) return;
      if (seen > total || inventory.workflow_runs.length < pageSize) break;
    }
    throw new Error('Incomplete runtime run inventory.');
  };
  const rankRuns = (left, right) => Date.parse(right.created_at) - Date.parse(left.created_at);
  try {
    for (const event of inventoryEvents) {
      for (const status of activeStatuses) await collectInventory(event, status);
    }

    exactRuns.sort(rankRuns);
    if (exactRuns.length > 0) {
      // An older active producer still matters. Only completed runs at least
      // as new as the newest active candidate can change that decision.
      const createdSince = secondsUtc(Date.parse(exactRuns[0].created_at));
      for (const event of inventoryEvents) await collectInventory(event, 'completed', createdSince);
      exactRuns.sort(rankRuns);
    }
    if (exactRuns.length > 0) {
      if (exactRuns.length > 1 && Date.parse(exactRuns[0].created_at) === Date.parse(exactRuns[1].created_at)) {
        throw new Error('Newest exact runtime runs have equal chronology.');
      }
      newestExact = exactRuns[0];
    }
  } catch {
    inventoryComplete = false;
    core.warning('Runtime active-run inventory was incomplete or ambiguous; preserving the required refresh fallback.');
  }

  const selectedProducerIsActive = async () => {
    try {
      const latest = dataOf(await github.rest.actions.getWorkflowRun({
        ...contextRepo, run_id: newestExact.id,
      }));
      return isExactActiveRun(latest) && latest.id === newestExact.id &&
        latest.run_attempt === newestExact.run_attempt && latest.event === newestExact.event &&
        latest.head_sha === newestExact.head_sha &&
        Date.parse(latest.created_at) === Date.parse(newestExact.created_at);
    } catch {
      core.warning('The exact active runtime producer could not be reread; preserving the required refresh fallback.');
      return false;
    }
  };
  const activeProducerVerified = inventoryComplete && newestExact && isExactActiveRun(newestExact) &&
    await selectedProducerIsActive();
  if (!await currentIdentity()) {
    core.info('Runtime refresh tuple changed during admission; no dispatch.');
    return 'obsolete';
  }
  if (activeProducerVerified) {
    // Tuple resolution can wait for merge computation. Do not suppress a repair
    // using producer evidence from before that final live comparison lookup.
    if (await selectedProducerIsActive()) {
      core.info('The unique newest exact runtime producer is already active; no dispatch.');
      return 'already-active';
    }
    if (!await currentIdentity()) {
      core.info('Runtime refresh tuple changed during the final producer lookup; no dispatch.');
      return 'obsolete';
    }
  }

  const tuple = Object.fromEntries(requiredFields.map(field => [field, input[field]]));
  const sha256 = value => createHash('sha256').update(value, 'utf8').digest('hex');
  const baseRefHash = sha256(input.base_ref);
  const claimNamespace = `firemud-runtime-refresh-intent/v1/pr-${input.pr_number}/${input.merge_sha}/${baseRefHash}/`;
  const refPrefix = `refs/tags/${claimNamespace}`;
  const tagName = generation => `${claimNamespace}g${generation}`;
  const claimMessage = (generation, witness_sha256) =>
    `${JSON.stringify({...tuple, generation: String(generation), witness_sha256})}\n`;
  const tagger = {name: 'FireMUD Runtime Refresh', email: 'runtime-refresh@firemud.dev'};
  const isSha = value => typeof value === 'string' && value.length === 40 && shaPattern.test(value);
  const isCanonicalDate = value => validChronology(value) && secondsUtc(Date.parse(value)) === value;
  const parseGeneration = value => {
    const generation = Number(value);
    if (!isPositiveInteger(generation) || String(generation) !== value) fail('Invalid runtime claim generation.');
    return generation;
  };
  const exactTag = (tag, generation, expectedWitnessDigest) => {
    if (tag?.tag !== tagName(generation) || !isSha(tag.sha) || tag.object?.type !== 'commit' ||
        tag.object.sha !== input.merge_sha || typeof tag.message !== 'string' ||
        tag.tagger?.name !== tagger.name || tag.tagger.email !== tagger.email ||
        !isCanonicalDate(tag.tagger.date)) return false;
    const annotation = JSON.parse(tag.message);
    const digest = annotation?.witness_sha256;
    return typeof digest === 'string' && digest.length === 64 && /^[0-9a-f]{64}$/.test(digest) &&
      (expectedWitnessDigest === undefined || digest === expectedWitnessDigest) &&
      tag.message === claimMessage(generation, digest);
  };

  const latestClaim = async () => {
    // Matching refs returns an array, not a count-backed paginated inventory.
    // Do not use a response that signals pagination or exceeds the inventory budget.
    const response = await github.rest.git.listMatchingRefs({...contextRepo, ref: `tags/${claimNamespace}`});
    const refs = response?.data;
    if (response?.status !== 200 || !isRecord(response.headers) ||
        Object.keys(response.headers).some(name => name.toLowerCase() === 'link') ||
        !Array.isArray(refs) || refs.length > inventoryLimit) fail('Incomplete runtime claim ref inventory.');

    const claims = new Map();
    const tagShas = new Set();
    for (const ref of refs) {
      if (typeof ref?.ref !== 'string' || !ref.ref.startsWith(`${refPrefix}g`) ||
          ref.object?.type !== 'tag' || !isSha(ref.object.sha)) fail('Invalid runtime claim ref.');
      const generation = parseGeneration(ref.ref.slice(refPrefix.length + 1));
      if (claims.has(generation) || tagShas.has(ref.object.sha)) fail('Duplicate runtime claim ref.');
      claims.set(generation, ref.object.sha);
      tagShas.add(ref.object.sha);
    }
    for (let generation = 1; generation <= claims.size; generation++) {
      if (!claims.has(generation)) fail('Gapped runtime claim ref inventory.');
    }

    if (claims.size === 0) return null;
    const generation = claims.size;
    const tag_sha = claims.get(generation);
    const tag = dataOf(await github.rest.git.getTag({...contextRepo, tag_sha}));
    if (!exactTag(tag, generation) || tag.sha !== tag_sha) fail('Invalid runtime claim annotation.');
    return {generation, date: tag.tagger.date, tag_sha};
  };

  const receiverProvesClaim = async (run, claim) => {
    if (!isSha(run.head_sha)) fail('Invalid terminal runtime receiver head.');
    let total;
    const jobIds = new Set();
    let metadataJob;
    for (let page = 1; page <= inventoryPages; page++) {
      const jobs = dataOf(await github.rest.actions.listJobsForWorkflowRunAttempt({
        ...contextRepo, run_id: run.id, attempt_number: run.run_attempt, per_page: pageSize, page,
      }));
      if (!Number.isSafeInteger(jobs?.total_count) || jobs.total_count < 0 || jobs.total_count > inventoryLimit ||
          (total !== undefined && jobs.total_count !== total) || !Array.isArray(jobs.jobs) ||
          jobs.jobs.length !== Math.min(pageSize, jobs.total_count - jobIds.size)) {
        fail('Incomplete runtime receiver job inventory.');
      }
      total = jobs.total_count;
      for (const job of jobs.jobs) {
        if (!isPositiveInteger(job?.id) || jobIds.has(job.id) || job.run_id !== run.id ||
            job.run_attempt !== run.run_attempt || job.head_sha !== run.head_sha || typeof job.name !== 'string') {
          fail('Invalid runtime receiver job identity.');
        }
        jobIds.add(job.id);
        if (job.name !== 'Derive Runtime Image Metadata') continue;
        if (metadataJob) fail('Duplicate runtime receiver metadata job.');
        metadataJob = job;
      }
      if (jobIds.size === total) break;
    }
    if (jobIds.size !== total) fail('Incomplete runtime receiver job inventory.');
    if (!metadataJob) return false;
    const steps = metadataJob.steps;
    if (!Array.isArray(steps) || steps.some(step => typeof step?.name !== 'string')) {
      fail('Invalid runtime receiver step inventory.');
    }
    const markerPrefix = 'Runtime refresh claim generation';
    const markers = steps.filter(step => step.name.startsWith(markerPrefix));
    if (markers.length === 0) return false;
    if (markers.length !== 1) fail('Ambiguous runtime receiver claim marker.');
    const [marker] = markers;
    const expected = `${markerPrefix} ${claim.generation} attempt ${run.run_attempt} tag ${claim.tag_sha}`;
    if (marker.name === expected) {
      if (metadataJob.status !== 'completed' || marker.status !== 'completed' || marker.conclusion !== 'success') {
        fail('Unsuccessful runtime receiver claim marker.');
      }
      return true;
    }

    const suffix = marker.name.slice(markerPrefix.length);
    if (marker.status === 'completed' && marker.conclusion === 'skipped' &&
        /^(?:[1-9][0-9]*|attempt\s+tag)?$/.test(suffix.trim())) return false;

    const identity = /^ ([1-9][0-9]*) attempt ([1-9][0-9]*) tag ([0-9a-f]{40})$/.exec(suffix);
    if (identity?.[0] !== suffix) fail('Invalid runtime receiver claim marker.');
    const generation = parseGeneration(identity[1]);
    const attempt = Number(identity[2]);
    if (!isPositiveInteger(attempt) || attempt > run.run_attempt) fail('Invalid runtime receiver marker attempt.');
    // The title omits the base ref. Even an exact native run can carry another
    // claim's tag, or retain a marker from a prior partial rerun attempt.
    if (identity[3] !== claim.tag_sha) return false;
    if (generation !== claim.generation) fail('Inconsistent runtime receiver marker generation.');
    return false;
  };

  const terminalReceiverProven = async claim => {
    const runs = [];
    await collectInventory('repository_dispatch', 'completed', claim.date, runs, new Set());
    if (new Set(runs.map(run => run.id)).size !== runs.length) fail('Duplicate terminal runtime receiver run.');
    let receiver;
    for (const run of runs) {
      if (!await receiverProvesClaim(run, claim)) continue;
      if (receiver) fail('Multiple terminal runtime receivers for the same claim.');
      receiver = run;
    }
    if (!receiver) return false;

    const latest = dataOf(await github.rest.actions.getWorkflowRun({...contextRepo, run_id: receiver.id}));
    return exactNativeTuple(latest) && latest.event === 'repository_dispatch' && latest.id === receiver.id &&
      latest.run_attempt === receiver.run_attempt && latest.head_sha === receiver.head_sha &&
      latest.created_at === receiver.created_at && latest.status === 'completed' &&
      latest.conclusion === receiver.conclusion;
  };

  let generation;
  let witness;
  try {
    const prior = await latestClaim();
    if (prior && !await terminalReceiverProven(prior)) {
      core.info('Runtime refresh claim has no proven terminal receiver; no dispatch.');
      return 'claim-held';
    }
    generation = (prior?.generation ?? 0) + 1;
    const date = secondsUtc(Date.now());
    if (!isPositiveInteger(generation) || (prior && date < prior.date)) fail('Invalid successor runtime claim.');
    const candidateWitness = randomBytes(32).toString('hex');
    const witnessDigest = sha256(candidateWitness);
    const tag = await github.rest.git.createTag({
      ...contextRepo, tag: tagName(generation), message: claimMessage(generation, witnessDigest),
      object: input.merge_sha, type: 'commit', tagger: {...tagger, date}, request: {retries: 0},
    });
    if (tag?.status !== 201 || !exactTag(tag.data, generation, witnessDigest) || tag.data.tagger.date !== date) {
      fail('Runtime claim tag creation is unproven.');
    }
    const ref = `refs/tags/${tagName(generation)}`;
    const created = await github.rest.git.createRef({...contextRepo, ref, sha: tag.data.sha, request: {retries: 0}});
    if (created?.status !== 201 || created.data?.ref !== ref ||
        created.data.object?.type !== 'tag' || created.data.object.sha !== tag.data.sha) {
      fail('Runtime claim ref creation is unproven.');
    }
    witness = candidateWitness;
  } catch {
    // A lost response is not ownership. Never adopt, update, delete, or retry a claim ref.
    core.warning('Runtime refresh claim ownership or predecessor evidence is unproven; no dispatch.');
    return 'claim-held';
  }

  try {
    if (!await currentIdentity()) {
      core.info('Runtime refresh tuple changed after claiming; no dispatch.');
      return 'obsolete';
    }
  } catch {
    core.warning('Runtime refresh tuple could not be rechecked after claiming; no dispatch.');
    return 'claim-held';
  }

  const dispatchCreatedSince = secondsUtc(Date.now());
  const nextDispatchSecond = Date.parse(dispatchCreatedSince) + 1000;
  try {
    const dispatched = await github.rest.repos.createDispatchEvent({
      ...contextRepo,
      event_type: 'pr-runtime-base-refresh',
      client_payload: {
        ...tuple,
        image_tag: `pr-merge-${input.merge_sha}`,
        runtime_refresh_claim: String(generation),
        runtime_refresh_claim_witness: witness,
      },
      request: {retries: 0},
    });
    if (dispatched?.status !== 204) fail('Unexpected runtime refresh dispatch status.');
  } catch {
    core.warning('Runtime refresh dispatch acceptance is unproven; leaving the claim held without retry.');
    return 'claim-held';
  } finally {
    witness = undefined;
  }
  core.info('Runtime refresh dispatch returned HTTP 204; terminal claim receipt is not established.');

  for (let observation = 0; observation < 8; observation += 1) {
    try {
      const {data: inventory} = await github.rest.actions.listWorkflowRuns({
        ...contextRepo,
        workflow_id: runtimeWorkflow.id,
        event: 'repository_dispatch',
        created: `${dispatchCreatedSince}..*`,
        per_page: 100,
        page: 1,
      });
      if (Array.isArray(inventory?.workflow_runs) &&
          inventory.workflow_runs.some(run => isExactActiveRun(run) &&
            // Unseen activity in the dispatch second may predate this request.
            // Second-resolution chronology cannot confirm its attribution.
            run.event === 'repository_dispatch' && Date.parse(run.created_at) >= nextDispatchSecond &&
            !observedRunIds.has(`${run.event}:${run.id}:${run.run_attempt}`))) {
        core.info('An exact active runtime producer became visible after dispatch; this is not claim receipt.');
        return 'dispatched-observed';
      }
    } catch {
      core.warning('Runtime refresh registration is not yet observable.');
    }
    if (observation < 7) await sleep(2000);
  }

  core.warning('No exact active runtime producer became visible after dispatch within the bounded poll.');
  return 'dispatched-unconfirmed';
};
