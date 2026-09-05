"use strict";

const { execFileSync } = require("child_process");

const PREVIEW_SUMMARY_MARKER = "<!-- firemud-preview-summary -->";
const LEGACY_SUMMARY_PREFIX = "### Preview Summary";
const RECLAIMED_MARKER = "<!-- firemud-preview-reclaimed -->";
const PREVIEW_STATE_POLICIES = new Set([
  "expected-open",
  "expected-closed",
  "manual-any",
]);

function commentTimestamp(comment) {
  const parsed = Date.parse(comment.updated_at || comment.created_at || "");
  return Number.isNaN(parsed) ? 0 : parsed;
}

function latestPreviewComment(comments) {
  const isBotAuthored = (comment) => comment.user?.login === "github-actions[bot]";
  const isWorkflowComment = (comment) =>
    isBotAuthored(comment) && (comment.body ?? "").includes(PREVIEW_SUMMARY_MARKER);
  const isLegacyWorkflowComment = (comment) =>
    isBotAuthored(comment) && (comment.body ?? "").startsWith(LEGACY_SUMMARY_PREFIX);
  const previewComments = [];
  const seenCommentIds = new Set();
  for (const comment of comments) {
    if (!isWorkflowComment(comment) && !isLegacyWorkflowComment(comment)) {
      continue;
    }
    if (seenCommentIds.has(comment.id)) {
      continue;
    }
    seenCommentIds.add(comment.id);
    previewComments.push(comment);
  }

  const existing = previewComments.reduce((latest, comment) => {
    if (!latest) return comment;
    const latestTimestamp = commentTimestamp(latest);
    const candidateTimestamp = commentTimestamp(comment);
    if (candidateTimestamp !== latestTimestamp) {
      return candidateTimestamp > latestTimestamp ? comment : latest;
    }
    return Number(comment.id) > Number(latest.id) ? comment : latest;
  }, null);

  return { existing, previewComments };
}

async function deleteCommentIfPresent(github, context, comment) {
  try {
    await github.rest.issues.deleteComment({
      ...context.repo,
      comment_id: comment.id,
    });
  } catch (error) {
    if (error.status !== 404) throw error;
  }
}

async function publishPreviewComment({
  github,
  context,
  core,
  mode,
  markerPolicy = "replace",
  statePolicy = "manual-any",
  includeDemoCredentials = false,
  telnetPort = process.env.PREVIEW_TELNET_PORT || "unavailable",
  failureStage = "unknown",
  staleDescription = "preview result",
  summaryExecutor = execFileSync,
}) {
  if (!PREVIEW_STATE_POLICIES.has(statePolicy)) {
    throw new Error(`Unknown preview pull request state policy: ${statePolicy}`);
  }

  const prNumber = Number(process.env.PREVIEW_PR_NUMBER);
  if (!Number.isInteger(prNumber) || prNumber <= 0) {
    core.info("No PR number available for preview comment publishing.");
    return;
  }

  const headSha = process.env.PREVIEW_HEAD_SHA || "";
  const getCurrentPullRequest = () =>
    github.rest.pulls.get({
      ...context.repo,
      pull_number: prNumber,
    });
  const isStaleTarget = (pullRequest) =>
    pullRequest.head?.sha !== headSha ||
    (statePolicy === "expected-open" && pullRequest.state !== "open") ||
    (statePolicy === "expected-closed" && pullRequest.state !== "closed");

  const { data: currentPullRequest } = await getCurrentPullRequest();
  if (isStaleTarget(currentPullRequest)) {
    core.info(
      `Skipping stale ${staleDescription} for PR #${prNumber}: ` +
        `expected ${headSha}, current ${currentPullRequest.state}/${currentPullRequest.head?.sha}`
    );
    return;
  }

  const args = [
    "./dev-tools/hosted/preview/write-preview-summary.sh",
    mode,
    String(prNumber),
    headSha,
    process.env.PREVIEW_IMAGE_TAG || "",
    process.env.PREVIEW_HOSTNAME || "",
    telnetPort,
  ];
  if (mode === "failure" || mode === "unavailable") {
    args.push(
      typeof failureStage === "string" && failureStage.trim().length > 0
        ? failureStage.trim()
        : "unknown",
    );
  }

  const summaryText = summaryExecutor("bash", args, { encoding: "utf8" });
  const bodyLines = [PREVIEW_SUMMARY_MARKER, LEGACY_SUMMARY_PREFIX, "", summaryText.trimEnd()];
  if (includeDemoCredentials) {
    bodyLines.push(`- Demo login username: ${process.env.DEMO_SMOKE_USERNAME}`);
    bodyLines.push(`- Demo login email: ${process.env.DEMO_SMOKE_EMAIL}`);
    bodyLines.push(`- Demo login password: ${process.env.DEMO_SMOKE_PASSWORD}`);
  }
  const body = `${bodyLines.join("\n")}\n`;

  const comments = await github.paginate(github.rest.issues.listComments, {
    ...context.repo,
    issue_number: prNumber,
    per_page: 100,
  });
  const { existing, previewComments } = latestPreviewComment(comments);

  const { data: latestPullRequest } = await getCurrentPullRequest();
  if (isStaleTarget(latestPullRequest)) {
    core.info(
      `Skipping superseded ${staleDescription} for PR #${prNumber}: ` +
        `expected ${headSha}, current ${latestPullRequest.state}/${latestPullRequest.head?.sha}`
    );
    return;
  }

  if (markerPolicy === "preserve-reclaimed" && existing?.body.includes(RECLAIMED_MARKER)) {
    core.info(`Preserving the reclaimed preview status for PR #${prNumber} until capacity is allocated.`);
    return;
  }

  for (const comment of previewComments) {
    if (comment.id === existing?.id) continue;
    await deleteCommentIfPresent(github, context, comment);
  }

  if (existing) {
    await github.rest.issues.updateComment({
      ...context.repo,
      comment_id: existing.id,
      body,
    });
  } else {
    await github.rest.issues.createComment({
      ...context.repo,
      issue_number: prNumber,
      body,
    });
  }
}

module.exports = { publishPreviewComment };
