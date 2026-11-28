const {execFileSync} = require('node:child_process');
const {createHash, randomBytes} = require('node:crypto');
const {resolve} = require('node:path');

module.exports = async function admitPrRuntimeRefresh({
  github,
  context,
  core,
  input,
  admissionAttempt = process.env.GITHUB_RUN_ATTEMPT,
  sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)),
}) {
  const runtimePath = '.github/workflows/runtime-images.yml';
  const admissionPath = '.github/workflows/admit-pr-runtime-refresh.yml';
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
  const transientIdentityRead = error => {
    const status = error?.status ?? error?.response?.status;
    if ([408, 429, 500, 502, 503, 504].includes(status)) return {error};
    throw error;
  };
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
      return transientIdentityRead(error);
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
      return transientIdentityRead(error);
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
      return transientIdentityRead(error);
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
      if (typeof result === 'boolean') return result;
      if (result !== null && observation === 5) throw result.error;
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

  const seenBeforeDispatch = new Set();
  const collectInventory = async (event, status, createdSince, runs, ids) => {
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
        seenBeforeDispatch.add(identity);
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
  const readNewestExact = async () => {
    const exactRuns = [];
    const observedRunIds = new Set();
    try {
      for (const event of inventoryEvents) {
        for (const status of activeStatuses) {
          await collectInventory(event, status, undefined, exactRuns, observedRunIds);
        }
      }

      exactRuns.sort(rankRuns);
      if (exactRuns.length === 0) return null;
      // An older active producer only matters if no newer completed run supersedes it.
      const createdSince = secondsUtc(Date.parse(exactRuns[0].created_at));
      for (const event of inventoryEvents) {
        await collectInventory(event, 'completed', createdSince, exactRuns, observedRunIds);
      }
      exactRuns.sort(rankRuns);
      if (exactRuns.length > 1 && Date.parse(exactRuns[0].created_at) === Date.parse(exactRuns[1].created_at)) {
        throw new Error('Newest exact runtime runs have equal chronology.');
      }
      return exactRuns[0];
    } catch {
      core.warning('Runtime active-run inventory was incomplete or ambiguous; preserving the required refresh fallback.');
      return null;
    }
  };

  const selectedProducerIsActive = async newestExact => {
    try {
      const latest = dataOf(await github.rest.actions.getWorkflowRun({
        ...contextRepo, run_id: newestExact.id,
      }));
      const sameActiveAttempt = observed => isExactActiveRun(observed) &&
        observed.id === newestExact.id && observed.run_attempt === newestExact.run_attempt &&
        observed.event === newestExact.event && observed.head_sha === newestExact.head_sha &&
        Date.parse(observed.created_at) === Date.parse(newestExact.created_at);
      if (!sameActiveAttempt(latest)) return false;
      if (latest.event !== 'repository_dispatch') return true;

      // Native dispatch titles carry SHAs, not base refs. Only the validated
      // exact-base claim and its attempt-bound metadata can identify this receiver.
      const claim = await latestClaim();
      if (claim === null || Date.parse(latest.created_at) < Date.parse(claim.date) ||
          !await receiverProvesClaim(latest, claim)) return false;
      // The attempt can finish or change while its claim/marker is read.
      const afterClaim = dataOf(await github.rest.actions.getWorkflowRun({
        ...contextRepo, run_id: newestExact.id,
      }));
      return sameActiveAttempt(afterClaim);
    } catch {
      core.warning('The exact active runtime producer could not be reread; preserving the required refresh fallback.');
      return false;
    }
  };
  const tuple = Object.fromEntries(requiredFields.map(field => [field, input[field]]));
  const sha256 = value => createHash('sha256').update(value, 'utf8').digest('hex');
  const baseRefHash = sha256(input.base_ref);
  const claimNamespace = `firemud-runtime-refresh-intent/v1/pr-${input.pr_number}/${input.merge_sha}/${baseRefHash}/`;
  const refPrefix = `refs/tags/${claimNamespace}`;
  const tagName = generation => `${claimNamespace}g${generation}`;
  const claimMessage = (generation, witness_sha256, owner_revision) =>
    `${JSON.stringify({...tuple, generation: String(generation), witness_sha256, owner_revision})}\n`;
  const tagger = {name: 'FireMUD Runtime Refresh', email: 'runtime-refresh@firemud.dev'};
  const isSha = value => typeof value === 'string' && value.length === 40 && shaPattern.test(value);
  const isCanonicalId = value => typeof value === 'string' &&
    isPositiveInteger(Number(value)) && String(Number(value)) === value;
  const unsent = {kind: 'unsent', description: 'Pre-dispatch refusal'};
  const dispatchRejection = {kind: 'rejected', description: 'Dispatch rejection'};
  const preSendUnavailable = {kind: 'pre-send-unavailable', description: 'Final identity read failure'};
  const ownerReceiptRefPrefix = (kind, tagSha) => `refs/tags/firemud-runtime-refresh-${kind}/v1/${tagSha}/`;
  const ownerReceiptIdentity = (claim, runId, attempt) =>
    `generation ${claim.generation} run ${runId} attempt ${attempt} tag ${claim.tag_sha}`;
  const isCanonicalDate = value => validChronology(value) && secondsUtc(Date.parse(value)) === value;
  const parseGeneration = value => {
    const generation = Number(value);
    if (!isPositiveInteger(generation) || String(generation) !== value) fail('Invalid runtime claim generation.');
    return generation;
  };
  const parseClaimTag = (tag, generation) => {
    if (tag?.tag !== tagName(generation) || !isSha(tag.sha) || tag.object?.type !== 'commit' ||
        typeof tag.message !== 'string' || tag.tagger?.name !== tagger.name || tag.tagger.email !== tagger.email ||
        !isCanonicalDate(tag.tagger.date)) return null;
    const annotation = JSON.parse(tag.message);
    if (!isRecord(annotation)) return null;
    const {witness_sha256: witnessDigest, owner_revision: ownerRevision} = annotation;
    if (Object.hasOwn(annotation, 'owner_revision') && !isSha(ownerRevision)) return null;
    // Older claims target the PR merge. Their canonical annotation has no owner revision.
    if (tag.object.sha !== (ownerRevision ?? input.merge_sha) ||
        typeof witnessDigest !== 'string' || witnessDigest.length !== 64 || !/^[0-9a-f]{64}$/.test(witnessDigest) ||
        tag.message !== claimMessage(generation, witnessDigest, ownerRevision)) return null;
    return {ownerRevision, witnessDigest};
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
    const annotation = parseClaimTag(tag, generation);
    if (!annotation || tag.sha !== tag_sha) fail('Invalid runtime claim annotation.');
    return {generation, date: tag.tagger.date, tag_sha, ownerRevision: annotation.ownerRevision};
  };

  const ownerReceiptProven = async (claim, {kind}) => {
    try {
      // The ref only locates the attempt. The native step establishes the outcome.
      const prefix = ownerReceiptRefPrefix(kind, claim.tag_sha);
      const refs = await github.rest.git.listMatchingRefs({...contextRepo, ref: prefix.slice(5)});
      if (refs?.status !== 200 || !isRecord(refs.headers) ||
          Object.keys(refs.headers).some(name => name.toLowerCase() === 'link') ||
          !Array.isArray(refs.data) || refs.data.length !== 1) return false;
      const [locator] = refs.data;
      if (typeof locator?.ref !== 'string' || !locator.ref.startsWith(prefix) ||
          locator.object?.type !== 'tag' || locator.object.sha !== claim.tag_sha) return false;
      const owner = locator.ref.slice(prefix.length).split('/');
      if (owner.length !== 2 || !owner.every(isCanonicalId)) return false;
      const [runId, attempt] = owner.map(Number);
      const workflow = dataOf(await github.rest.actions.getWorkflow({
        ...contextRepo, workflow_id: 'admit-pr-runtime-refresh.yml',
      }));
      if (workflow?.path !== admissionPath || !isPositiveInteger(workflow.id)) return false;

      const getRun = async () => dataOf(await github.rest.actions.getWorkflowRun({...contextRepo, run_id: runId}));
      const isAttributableOwner = run => run?.id === runId && run.workflow_id === workflow.id &&
        run.event === 'repository_dispatch' &&
        run.repository?.full_name === fullName && run.head_repository?.full_name === fullName &&
        run.head_branch === defaultBranch && isSha(run.head_sha) &&
        (claim.ownerRevision === undefined || run.head_sha === claim.ownerRevision) &&
        [admissionPath, `${admissionPath}@${defaultBranch}`, `${admissionPath}@refs/heads/${defaultBranch}`].includes(run.path);
      const isSuccessfulOwner = run => isAttributableOwner(run) && run.run_attempt === attempt &&
        run.status === 'completed' && run.conclusion === 'success';
      const getAttempt = () => github.rest.actions.getWorkflowRunAttempt({
        ...contextRepo, run_id: runId, attempt_number: attempt,
      });
      // A definitive rejection belongs to the authenticated POST of the exact
      // attempt, independent of any later attempt of this admission run.
      const rejection = kind === dispatchRejection.kind;
      const latestBefore = rejection ? dataOf(await getAttempt()) : await getRun();
      if (!isAttributableOwner(latestBefore) || !isPositiveInteger(latestBefore.run_attempt)) return false;
      const {run_attempt: latestAttempt, path: latestPath, status: latestStatus, conclusion: latestConclusion} = latestBefore;
      const historical = !rejection && latestAttempt > attempt;
      let run = latestBefore;
      if (historical) {
        // Only this typed failure proves the owner stopped before invoking dispatch.
        if (kind !== preSendUnavailable.kind || claim.ownerRevision === undefined) return false;
        const terminalConclusions = ['success', 'failure', 'neutral', 'cancelled', 'skipped',
          'timed_out', 'action_required', 'stale', 'startup_failure'];
        if (latestStatus === 'completed' ? !terminalConclusions.includes(latestConclusion) :
          !activeStatuses.has(latestStatus) || latestConclusion !== null) return false;
        const response = await getAttempt();
        if (response?.status !== 200) return false;
        run = dataOf(response);
      }
      if (!isSuccessfulOwner(run)) return false;

      const inventory = dataOf(await github.rest.actions.listJobsForWorkflowRunAttempt({
        ...contextRepo, run_id: runId, attempt_number: attempt, per_page: pageSize, page: 1,
      }));
      if (inventory?.total_count !== 1 || !Array.isArray(inventory.jobs) || inventory.jobs.length !== 1) return false;
      const [job] = inventory.jobs;
      if (!isPositiveInteger(job?.id) || job.run_id !== runId || job.head_sha !== run.head_sha ||
          (Object.hasOwn(job, 'run_attempt') && job.run_attempt !== attempt) ||
          job.name !== 'Admit Exact Runtime Tuple' || job.status !== 'completed' || job.conclusion !== 'success' ||
          !Array.isArray(job.steps) || job.steps.some(step => typeof step?.name !== 'string')) return false;
      const markerPrefix = `Runtime refresh ${kind} claim`;
      const markers = job.steps.filter(step => step.name.startsWith(markerPrefix));
      if (markers.length !== 1 || markers[0].status !== 'completed' || markers[0].conclusion !== 'success' ||
          markers[0].name !== `${markerPrefix} ${ownerReceiptIdentity(claim, runId, attempt)}`) return false;
      if (historical && job.steps.some(step =>
        [unsent, dispatchRejection].some(outcome => step.name.startsWith(`Runtime refresh ${outcome.kind} claim`)) &&
        (step.status !== 'completed' || step.conclusion !== 'skipped'))) return false;

      const latest = rejection ? dataOf(await getAttempt()) : await getRun();
      if (historical) {
        // Close the read against the same attributable latest attempt observed at entry.
        return isAttributableOwner(latest) && latest.run_attempt === latestAttempt &&
          latest.path === latestPath && latest.status === latestStatus && latest.conclusion === latestConclusion;
      }
      return isSuccessfulOwner(latest) && latest.head_sha === run.head_sha;
    } catch {
      // An uncertain locator or marker cannot release a claim.
      return false;
    }
  };

  const isTrustedAdmission = () => context.eventName === 'repository_dispatch' &&
    context.payload.action === 'pr-runtime-base-refresh-request' &&
    context.ref === `refs/heads/${defaultBranch}` && isSha(context.sha);
  const isTrustedReceiptOwner = () => isTrustedAdmission() &&
    isPositiveInteger(context.runId) && isCanonicalId(admissionAttempt);

  const recordOwnerReceipt = async (claim, {kind, description}) => {
    const ref = `${ownerReceiptRefPrefix(kind, claim.tag_sha)}${context.runId}/${admissionAttempt}`;
    try {
      const locator = await github.rest.git.createRef({...contextRepo, ref, sha: claim.tag_sha, request: {retries: 0}});
      if (locator?.status !== 201 || locator.data?.ref !== ref ||
          locator.data.object?.type !== 'tag' || locator.data.object.sha !== claim.tag_sha) {
        fail(`${description} locator creation is unproven.`);
      }
    } catch {
      core.warning(`${description} locator creation is unproven; keeping the claim held.`);
      return false;
    }
    // A failed output write must fail the owning step and prevent its success marker.
    core.setOutput(`${kind}_claim`, ownerReceiptIdentity(claim, context.runId, admissionAttempt));
    return true;
  };

  const isDefinitiveDispatchRejection = error => {
    // GitHub uses 422 for both validation failures and spam protection. Require
    // the structured validation shape for this exact authenticated POST.
    const response = error?.response;
    const endpoint = `https://api.github.com/repos/${fullName}/dispatches`;
    if (error?.status !== 422 || response?.status !== 422 ||
        error.request?.method !== 'POST' || error.request.url !== endpoint || response.url !== endpoint ||
        !isRecord(response.headers)) return false;

    const headers = Object.entries(response.headers).map(([key, value]) => [key.toLowerCase(), value]);
    const throttling = /abuse|spam|throttl|rate[\s_-]*limit|too[\s_-]+many[\s_-]+requests|retry[\s_-]*after/i;
    const rateLimitMetadata = ['x-ratelimit-limit', 'x-ratelimit-reset', 'x-ratelimit-used', 'x-ratelimit-resource'];
    const hasThrottlingHeader = headers.some(([key, value]) => key === 'x-ratelimit-remaining'
      ? typeof value !== 'string' || !/^[1-9][0-9]*$/.test(value)
      : !rateLimitMetadata.includes(key) && throttling.test(key));
    if (!headers.some(([key, value]) => key === 'x-github-request-id' &&
        typeof value === 'string' && /^[0-9a-f]+(?::[0-9a-f]+)+$/i.test(value)) ||
        hasThrottlingHeader ||
        (typeof error.message === 'string' && throttling.test(error.message))) return false;

    const body = response.data;
    if (!isRecord(body) || body.message !== 'Validation Failed' ||
        typeof body.documentation_url !== 'string' ||
        !/^https:\/\/docs\.github\.com\/(?:en\/)?rest\/repos\/repos#create-a-repository-dispatch-event$/.test(body.documentation_url) ||
        Object.keys(body).some(key => !['message', 'documentation_url', 'errors', 'status'].includes(key)) ||
        (Object.hasOwn(body, 'status') && body.status !== '422' && body.status !== 422) ||
        !Array.isArray(body.errors) || body.errors.length === 0) return false;

    return body.errors.every(detail => isRecord(detail) &&
      ['invalid', 'missing_field', 'unprocessable'].includes(detail.code) &&
      ['event_type', 'client_payload'].includes(detail.field) &&
      Object.keys(detail).every(key => ['code', 'field', 'message', 'resource', 'index', 'value'].includes(key)) &&
      ['message', 'resource'].every(key => !Object.hasOwn(detail, key) ||
        (typeof detail[key] === 'string' && !throttling.test(detail[key]))) &&
      (!Object.hasOwn(detail, 'index') || Number.isSafeInteger(detail.index)) &&
      (!Object.hasOwn(detail, 'value') || detail.value === null || Number.isSafeInteger(detail.value) ||
        (typeof detail.value === 'string' && !throttling.test(detail.value)) ||
        (Array.isArray(detail.value) && detail.value.every(value => typeof value === 'string' && !throttling.test(value)))));
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
            (Object.hasOwn(job, 'run_attempt') && job.run_attempt !== run.run_attempt) ||
            job.head_sha !== run.head_sha || typeof job.name !== 'string') {
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

  const newestExact = await readNewestExact();
  const activeProducerVerified = newestExact && isExactActiveRun(newestExact) &&
    await selectedProducerIsActive(newestExact);
  if (!await currentIdentity()) {
    core.info('Runtime refresh tuple changed during admission; no dispatch.');
    return 'obsolete';
  }
  if (activeProducerVerified) {
    // Tuple resolution can wait for merge computation. Do not suppress a repair
    // using producer evidence from before that final live comparison lookup.
    const currentProducer = await readNewestExact();
    if (currentProducer && isExactActiveRun(currentProducer) && await selectedProducerIsActive(currentProducer)) {
      core.info('The unique newest exact runtime producer is already active; no dispatch.');
      return 'already-active';
    }
    if (!await currentIdentity()) {
      core.info('Runtime refresh tuple changed during the final producer lookup; no dispatch.');
      return 'obsolete';
    }
  }

  let claim;
  let witness;
  try {
    const prior = await latestClaim();
    if (prior) {
      const provenPreSendOutcome = await ownerReceiptProven(prior, unsent) ||
        await ownerReceiptProven(prior, dispatchRejection) ||
        await ownerReceiptProven(prior, preSendUnavailable);
      if (!provenPreSendOutcome && !await terminalReceiverProven(prior)) {
        core.info('Runtime refresh claim has no proven terminal receiver or trusted owner outcome; no dispatch.');
        return 'claim-held';
      }
      if (provenPreSendOutcome && !await currentIdentity()) {
        core.info('Runtime refresh tuple changed during owner outcome verification; no dispatch.');
        return 'obsolete';
      }
    }
    const generation = (prior?.generation ?? 0) + 1;
    const date = secondsUtc(Date.now());
    if (!isPositiveInteger(generation) || (prior && date < prior.date)) fail('Invalid successor runtime claim.');
    if (!isTrustedAdmission()) fail('Runtime claim owner revision is unproven.');
    // GitHub binds the native repository_dispatch SHA to its default branch.
    // The PR merge stays in the annotation and never becomes newly referenced.
    const ownerRevision = context.sha;
    const candidateWitness = randomBytes(32).toString('hex');
    const witnessDigest = sha256(candidateWitness);
    const tag = await github.rest.git.createTag({
      ...contextRepo, tag: tagName(generation), message: claimMessage(generation, witnessDigest, ownerRevision),
      object: ownerRevision, type: 'commit', tagger: {...tagger, date}, request: {retries: 0},
    });
    const annotation = parseClaimTag(tag?.data, generation);
    if (tag?.status !== 201 || annotation?.ownerRevision !== ownerRevision ||
        annotation.witnessDigest !== witnessDigest || tag.data.tagger.date !== date) {
      fail('Runtime claim tag creation is unproven.');
    }
    const ref = `refs/tags/${tagName(generation)}`;
    const created = await github.rest.git.createRef({...contextRepo, ref, sha: tag.data.sha, request: {retries: 0}});
    if (created?.status !== 201 || created.data?.ref !== ref ||
        created.data.object?.type !== 'tag' || created.data.object.sha !== tag.data.sha) {
      fail('Runtime claim ref creation is unproven.');
    }
    claim = {generation, tag_sha: tag.data.sha};
    witness = candidateWitness;
  } catch {
    // A lost response is not ownership. Never adopt, update, delete, or retry a claim ref.
    core.warning('Runtime refresh claim ownership or predecessor evidence is unproven; no dispatch.');
    return 'claim-held';
  }

  let identityIsCurrent;
  try {
    identityIsCurrent = await currentIdentity();
  } catch {
    core.warning('Runtime refresh tuple could not be rechecked after claiming; no dispatch.');
    if (isTrustedReceiptOwner() && await recordOwnerReceipt(claim, preSendUnavailable)) {
      return 'pre-send-unavailable';
    }
    return 'claim-held';
  }

  if (!identityIsCurrent) {
    core.info('Runtime refresh tuple changed after claiming; no dispatch.');
    if (!isTrustedReceiptOwner() || !await recordOwnerReceipt(claim, unsent)) return 'claim-held';
    return 'obsolete';
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
        runtime_refresh_claim: String(claim.generation),
        runtime_refresh_claim_witness: witness,
      },
      request: {retries: 0, redirect: 'error'},
    });
    if (dispatched?.status !== 204) fail('Unexpected runtime refresh dispatch status.');
  } catch (error) {
    if (isTrustedReceiptOwner() && isDefinitiveDispatchRejection(error) &&
        await recordOwnerReceipt(claim, dispatchRejection)) {
      core.info('GitHub rejected the runtime refresh request at validation. Recorded the claim outcome.');
      return 'dispatch-rejected';
    }
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
            !seenBeforeDispatch.has(`${run.event}:${run.id}:${run.run_attempt}`))) {
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
