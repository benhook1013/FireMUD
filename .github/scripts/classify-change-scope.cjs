"use strict";

const ALL_SERVICES = [
  "account-service",
  "automation-scripting-service",
  "entity-management-service",
  "game-design-service",
  "game-logic-service",
  "game-session-service",
  "logging-admin-service",
  "social-groups-service",
  "spring-cloud-gateway",
  "tcp-proxy-service",
  "world-management-service",
];

const SHARED_PREFIXES = [
  "buildSrc/",
  "gradle/",
  "protos/",
  "config/checkstyle/",
  "config/protobuf/",
  "config/security/",
  "config/spotbugs/",
  "services/common-library/",
  "services/common-data-runtime/",
  "services/common-platform-core/",
  "services/common-saga/",
  "services/common-security/",
  "services/common-test-support/",
  "services/common-web-support/",
];

const SHARED_FILES = new Set([
  "build.gradle.kts",
  "settings.gradle.kts",
  "gradle.properties",
  ".github/workflows/ci.yml",
  ".github/workflows/security.yml",
  ".github/workflows/preview.yml",
  ".github/workflows/zap-baseline.yml",
  ".github/scripts/classify-change-scope.cjs",
]);

function isDocumentation(file) {
  return (
    file === "AGENTS.md" ||
    file === "mkdocs.yml" ||
    file.startsWith("design/") ||
    file.startsWith("dev-tools/docs/") ||
    file.endsWith(".md")
  );
}

function isValidationPython(file) {
  return file.startsWith("dev-tools/validation/") && file.endsWith(".py");
}

function isFrontend(file) {
  return file.startsWith("web-client/") || file.startsWith("config/openapi/");
}

function classifyChangeScope(inputFiles, options = {}) {
  const files = [...new Set(inputFiles)].sort();
  const forceAll = options.forceAll === true;
  const runAll =
    forceAll ||
    files.some(
      (file) =>
        SHARED_FILES.has(file) ||
        SHARED_PREFIXES.some((prefix) => file.startsWith(prefix)),
    );

  const affectedServices = new Set();
  if (runAll) {
    ALL_SERVICES.forEach((service) => affectedServices.add(service));
  } else {
    for (const file of files) {
      for (const service of ALL_SERVICES) {
        if (file.startsWith(`services/${service}/`)) {
          affectedServices.add(service);
        }
      }
    }
  }

  const pythonFiles = files.filter((file) => file.endsWith(".py"));
  const designDocsChanged = files.some((file) => file.startsWith("design/"));
  const validationPythonChanged = files.some(isValidationPython);

  return {
    runAll,
    affectedServices: [...affectedServices],
    docsChanged: runAll || files.some(isDocumentation),
    frontendChanged: runAll || files.some(isFrontend),
    pythonChanged: forceAll || pythonFiles.length > 0,
    designDocsChanged,
    validationPythonChanged,
    lightweightOnly:
      !forceAll &&
      files.length > 0 &&
      files.every((file) => isDocumentation(file) || isValidationPython(file)),
  };
}

async function classifyGithubChangeScope(github, context) {
  if (context.eventName !== "pull_request") {
    return classifyChangeScope([], { forceAll: true });
  }

  const files = await github.paginate(
    github.rest.pulls.listFiles,
    {
      ...context.repo,
      pull_number: context.payload.pull_request.number,
      per_page: 100,
    },
    (response) => response.data.map((file) => file.filename),
  );
  const expectedFileCount = context.payload.pull_request.changed_files;
  const fileListIncomplete =
    Number.isInteger(expectedFileCount) && expectedFileCount !== files.length;

  return classifyChangeScope(files, { forceAll: fileListIncomplete });
}

module.exports = {
  ALL_SERVICES,
  classifyChangeScope,
  classifyGithubChangeScope,
  isDocumentation,
  isValidationPython,
};
