"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const { publishPreviewComment } = require("../hosted/preview/publish-preview-comment.js");

const context = {
  repo: { owner: "example", repo: "firemud" },
};

function previewComment(id, body, updatedAt) {
  return {
    id,
    body,
    user: { login: "github-actions[bot]" },
    created_at: updatedAt,
    updated_at: updatedAt,
  };
}

function makeGithub({ pullRequests, comments, deletedCommentStatuses = {} }) {
  const calls = {
    get: [],
    paginate: [],
    deleted: [],
    updates: [],
    creates: [],
  };
  let pullRequestIndex = 0;
  const github = {
    rest: {
      pulls: {
        get: async (params) => {
          calls.get.push(params);
          const index = Math.min(pullRequestIndex++, pullRequests.length - 1);
          return { data: pullRequests[index] };
        },
      },
      issues: {
        listComments: () => {},
        deleteComment: async (params) => {
          calls.deleted.push(params.comment_id);
          const status = deletedCommentStatuses[params.comment_id];
          if (status !== undefined) {
            const error = new Error(`delete failed with status ${status}`);
            error.status = status;
            throw error;
          }
        },
        updateComment: async (params) => {
          calls.updates.push(params);
        },
        createComment: async (params) => {
          calls.creates.push(params);
        },
      },
    },
    paginate: async (method, params) => {
      calls.paginate.push({ method, params });
      return comments;
    },
  };
  return { github, calls };
}

async function withEnvironment(values, callback) {
  const previous = {};
  for (const [name, value] of Object.entries(values)) {
    previous[name] = process.env[name];
    if (value === undefined) {
      delete process.env[name];
    } else {
      process.env[name] = value;
    }
  }
  try {
    return await callback();
  } finally {
    for (const [name, value] of Object.entries(previous)) {
      if (value === undefined) {
        delete process.env[name];
      } else {
        process.env[name] = value;
      }
    }
  }
}

async function publish(options = {}) {
  const {
    pullRequests = [{ state: "open", head: { sha: "head-123" } }],
    comments = [],
    deletedCommentStatuses,
    summaryText = "generated preview summary",
    ...publisherOptions
  } = options;
  const { github, calls } = makeGithub({
    pullRequests,
    comments,
    deletedCommentStatuses,
  });
  const infos = [];
  const core = { info: (message) => infos.push(message) };
  const summaryCalls = [];
  const summaryExecutor = (...args) => {
    summaryCalls.push(args);
    return summaryText;
  };

  await withEnvironment(
    {
      PREVIEW_PR_NUMBER: "123",
      PREVIEW_HEAD_SHA: "head-123",
      PREVIEW_IMAGE_TAG: "image-123",
      PREVIEW_HOSTNAME: "pr-123.preview.firedevops.net",
      DEMO_SMOKE_USERNAME: "demo-user",
      DEMO_SMOKE_EMAIL: "demo@example.test",
      DEMO_SMOKE_PASSWORD: "demo-password",
    },
    async () => {
      await publishPreviewComment({
        github,
        context,
        core,
        mode: "success",
        summaryExecutor,
        ...publisherOptions,
      });
    },
  );

  return { calls, infos, summaryCalls };
}

test("selects the canonical preview comment by updated time, then id", async () => {
  const oldGenerated = previewComment(
    "100",
    "<!-- firemud-preview-summary -->\nold generated",
    "2026-09-01T00:00:00Z",
  );
  const duplicateOldGenerated = { ...oldGenerated };
  const newerLegacy = previewComment(
    "101",
    "### Preview Summary\nnewer legacy",
    "2026-09-02T00:00:00Z",
  );
  const newestLowerId = previewComment(
    "102",
    "<!-- firemud-preview-summary -->\nnewest lower id",
    "2026-09-03T00:00:00Z",
  );
  const newestHigherId = previewComment(
    "103",
    "### Preview Summary\nnewest higher id",
    "2026-09-03T00:00:00Z",
  );
  const userComment = {
    ...previewComment("999", "<!-- firemud-preview-summary -->\nuser", "2026-09-04T00:00:00Z"),
    user: { login: "contributor" },
  };

  const result = await publish({
    comments: [oldGenerated, duplicateOldGenerated, newerLegacy, newestLowerId, newestHigherId, userComment],
    includeDemoCredentials: true,
  });

  assert.deepEqual(result.calls.deleted, ["100", "101", "102"]);
  assert.equal(result.calls.updates.length, 1);
  assert.equal(result.calls.updates[0].comment_id, "103");
  assert.match(result.calls.updates[0].body, /Demo login username: demo-user/);
  assert.match(result.calls.updates[0].body, /Demo login email: demo@example\.test/);
  assert.match(result.calls.updates[0].body, /Demo login password: demo-password/);
});

test("ignores null or missing bot comment bodies while publishing the canonical summary", async () => {
  const nullBody = {
    ...previewComment("90", "ignored", "2026-09-04T00:00:00Z"),
    body: null,
  };
  const missingBody = {
    ...previewComment("91", "ignored", "2026-09-05T00:00:00Z"),
  };
  delete missingBody.body;
  const canonical = previewComment(
    "92",
    "<!-- firemud-preview-summary -->\ncanonical",
    "2026-09-03T00:00:00Z",
  );

  const result = await publish({ comments: [nullBody, missingBody, canonical] });

  assert.deepEqual(result.calls.deleted, []);
  assert.equal(result.calls.updates.length, 1);
  assert.equal(result.calls.updates[0].comment_id, "92");
  assert.equal(result.calls.creates.length, 0);
});

test("rejects an initially stale expected-open or expected-closed target", async () => {
  const expectedOpen = await publish({
    pullRequests: [{ state: "closed", head: { sha: "head-123" } }],
    statePolicy: "expected-open",
  });
  assert.equal(expectedOpen.calls.get.length, 1);
  assert.equal(expectedOpen.summaryCalls.length, 0);
  assert.equal(expectedOpen.calls.paginate.length, 0);
  assert.equal(expectedOpen.calls.creates.length, 0);

  const expectedClosed = await publish({
    pullRequests: [{ state: "open", head: { sha: "head-123" } }],
    statePolicy: "expected-closed",
  });
  assert.equal(expectedClosed.calls.get.length, 1);
  assert.equal(expectedClosed.summaryCalls.length, 0);
  assert.equal(expectedClosed.calls.paginate.length, 0);
  assert.equal(expectedClosed.calls.creates.length, 0);
});

test("accepts the matching expected state and manual-any policy", async () => {
  for (const [statePolicy, state] of [
    ["expected-open", "open"],
    ["expected-closed", "closed"],
    ["manual-any", "open"],
    ["manual-any", "closed"],
  ]) {
    const result = await publish({
      pullRequests: [{ state, head: { sha: "head-123" } }],
      mode: "cleanup",
      statePolicy,
    });
    assert.equal(result.calls.creates.length, 1, `${statePolicy}/${state}`);
    assert.equal(result.calls.get.length, 2, `${statePolicy}/${state}`);
  }
});

test("final stale check prevents a changed head from deleting or updating comments", async () => {
  const result = await publish({
    pullRequests: [
      { state: "open", head: { sha: "head-123" } },
      { state: "open", head: { sha: "head-new" } },
    ],
    comments: [
      previewComment("201", "<!-- firemud-preview-summary -->\nold", "2026-09-01T00:00:00Z"),
    ],
    statePolicy: "expected-open",
  });

  assert.equal(result.calls.get.length, 2);
  assert.equal(result.summaryCalls.length, 1);
  assert.equal(result.calls.paginate.length, 1);
  assert.deepEqual(result.calls.deleted, []);
  assert.deepEqual(result.calls.updates, []);
  assert.deepEqual(result.calls.creates, []);
});

test("final expected-closed check ignores a closed-event cleanup after same-head reopen", async () => {
  const result = await publish({
    pullRequests: [
      { state: "closed", head: { sha: "head-123" } },
      { state: "open", head: { sha: "head-123" } },
    ],
    comments: [
      previewComment("202", "<!-- firemud-preview-summary -->\ncurrent preview", "2026-09-02T00:00:00Z"),
    ],
    mode: "failure",
    statePolicy: "expected-closed",
    failureStage: "cleanup",
  });

  assert.equal(result.calls.get.length, 2);
  assert.equal(result.summaryCalls.length, 1);
  assert.deepEqual(result.calls.deleted, []);
  assert.deepEqual(result.calls.updates, []);
  assert.deepEqual(result.calls.creates, []);
});

test("preserves a reclaimed marker after both freshness checks pass", async () => {
  const result = await publish({
    comments: [
      previewComment(
        "301",
        "<!-- firemud-preview-summary -->\n<!-- firemud-preview-reclaimed -->\nreclaimed",
        "2026-09-01T00:00:00Z",
      ),
    ],
    markerPolicy: "preserve-reclaimed",
    statePolicy: "expected-open",
  });

  assert.equal(result.calls.get.length, 2);
  assert.equal(result.calls.paginate.length, 1);
  assert.deepEqual(result.calls.deleted, []);
  assert.deepEqual(result.calls.updates, []);
  assert.deepEqual(result.calls.creates, []);
  assert.match(result.infos.join("\n"), /Preserving the reclaimed preview status/);
});

test("deletes duplicate summaries, tolerates a concurrent 404, and updates the canonical one", async () => {
  const duplicate = previewComment(
    "401",
    "<!-- firemud-preview-summary -->\nold duplicate",
    "2026-09-01T00:00:00Z",
  );
  const result = await publish({
    comments: [
      duplicate,
      { ...duplicate },
      previewComment("402", "### Preview Summary\nold and already gone", "2026-09-02T00:00:00Z"),
      previewComment("403", "<!-- firemud-preview-summary -->\ncanonical", "2026-09-03T00:00:00Z"),
    ],
    deletedCommentStatuses: { "402": 404 },
  });

  assert.deepEqual(result.calls.deleted, ["401", "402"]);
  assert.equal(result.calls.updates.length, 1);
  assert.equal(result.calls.updates[0].comment_id, "403");
});

test("passes a trimmed failure stage only to failure summaries", async () => {
  const result = await publish({
    mode: "failure",
    failureStage: "  rollout  ",
    statePolicy: "expected-open",
  });

  assert.deepEqual(result.summaryCalls[0][0], "bash");
  assert.deepEqual(result.summaryCalls[0][1], [
    "./dev-tools/hosted/preview/write-preview-summary.sh",
    "failure",
    "123",
    "head-123",
    "image-123",
    "pr-123.preview.firedevops.net",
    "unavailable",
    "rollout",
  ]);

  const unavailable = await publish({
    mode: "unavailable",
    failureStage: "  capacity  ",
    statePolicy: "expected-open",
  });
  assert.equal(unavailable.summaryCalls[0][1].at(-1), "capacity");

  const success = await publish({
    mode: "success",
    failureStage: "should-not-be-appended",
    statePolicy: "expected-open",
  });
  assert.equal(success.summaryCalls[0][1].length, 7);
});
