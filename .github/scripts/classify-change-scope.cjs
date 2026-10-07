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

const ALL_MODULES = [
  "common-data-runtime",
  "common-platform-core",
  "common-saga",
  "common-temporal",
  "common-security",
  "common-test-support",
  "common-web-support",
  ...ALL_SERVICES,
  "hosted-environment-identity-controller",
  "load-testing",
];

const MODULE_PATHS = new Map([
  ...ALL_MODULES
    .filter((module) => module !== "load-testing")
    .map((module) => [module, `services/${module}/`]),
  ["load-testing", "dev-tools/load-testing/"],
]);

const BOOTABLE_MODULES = new Set([
  ...ALL_SERVICES,
  "hosted-environment-identity-controller",
]);

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
  "services/common-temporal/",
  "services/common-security/",
  "services/common-test-support/",
  "services/common-web-support/",
  ".github/workflows/",
  ".github/actions/",
  ".github/scripts/",
];

const SHARED_FILES = new Set([
  "build.gradle.kts",
  "settings.gradle.kts",
  "gradle.properties",
  "gradlew",
  "gradlew.bat",
  "dev-tools/validation/run-locked-gradle.sh",
  "dev-tools/validation/gradle-run-supervisor.py",
]);

const POSTGRES_RUNTIME_PROOF_FILES = new Set([
  ".github/scripts/classify-change-scope.cjs",
  ".github/scripts/classify-change-scope.test.cjs",
  ".github/workflows/ci.yml",
  "dev-tools/hosted/preview/validate-preview-artifact.py",
  "dev-tools/tests/ci-lightweight-scope-contract.sh",
  "dev-tools/tests/hosted-gateway-bridge-contract.sh",
  "dev-tools/tests/postgres-runtime-upgrade-contract.sh",
  "dev-tools/validation/test_validate_preview_artifact.py",
  "docker/README.md",
  "docker/docker-compose.yml",
  "docker/pg-dump-cron.Dockerfile",
  "docker/pg-dump-cron.crontab",
  "docker/postgres-data-layout-entrypoint.sh",
  "k8s/helm/firemud/templates/stateful-core.yaml",
  "k8s/helm/firemud/values-hosted-shared.example.yaml",
  "k8s/postgres/pg-dump-cronjob.yaml",
]);

const POSTGRES_RUNTIME_PROOF_PREFIXES = [
  "dev-tools/backups/",
  "dev-tools/restores/",
  "k8s/postgres/",
];

function isDocumentation(file) {
  return (
    file === "AGENTS.md" ||
    file === "mkdocs.yml" ||
    file === "config/docs/requirements.txt" ||
    file.startsWith("design/") ||
    file.startsWith("dev-tools/docs/") ||
    file.endsWith(".md")
  );
}

function isValidationPython(file) {
  return file.startsWith("dev-tools/validation/") && file.endsWith(".py");
}

function isValidationTooling(file) {
  return /^\.github\/scripts\/[^/]+\.test\.cjs$/.test(file);
}

function isRuntimeAuthority(file) {
  return file === ".node-version" || file === ".python-version";
}

function isPostgresRuntimeProofRelevant(file) {
  return (
    POSTGRES_RUNTIME_PROOF_FILES.has(file) ||
    POSTGRES_RUNTIME_PROOF_PREFIXES.some((prefix) => file.startsWith(prefix))
  );
}

function isPythonDependency(file) {
  return file.startsWith("config/python/");
}

function isLightweightEligible(file) {
  return (
    (isDocumentation(file) || isValidationPython(file)) &&
    file !== "dev-tools/docs/generate-erd.sh" &&
    !isRuntimeAuthority(file) &&
    !SHARED_FILES.has(file)
  );
}

function isFrontend(file) {
  return file.startsWith("web-client/") || file.startsWith("config/openapi/");
}

function isUnknownServicePath(file) {
  const match = /^services\/([^/]+)(?:\/|$)/.exec(file);
  return match !== null && !ALL_MODULES.includes(match[1]);
}

function moduleForFile(file) {
  for (const [module, prefix] of MODULE_PATHS) {
    if (file.startsWith(prefix)) {
      return module;
    }
  }
  return null;
}

function classifyChangeScope(inputFiles, options = {}) {
  const files = [...new Set(inputFiles)].sort();
  const forceAll = options.forceAll === true;
  const runAll =
    forceAll ||
    files.some(
      (file) =>
        SHARED_FILES.has(file) ||
        (SHARED_PREFIXES.some((prefix) => file.startsWith(prefix)) &&
          !isValidationTooling(file)) ||
        isUnknownServicePath(file),
    );

  const affectedServices = new Set();
  const affectedModules = new Set();
  if (runAll) {
    ALL_SERVICES.forEach((service) => affectedServices.add(service));
    ALL_MODULES.forEach((module) => affectedModules.add(module));
  } else {
    for (const file of files) {
      const module = moduleForFile(file);
      if (module !== null) {
        affectedModules.add(module);
        if (ALL_SERVICES.includes(module)) {
          affectedServices.add(module);
        }
      }
    }
  }

  const pythonFiles = files.filter((file) => file.endsWith(".py") || isPythonDependency(file));
  const designDocsChanged = files.some((file) => file.startsWith("design/"));
  const validationPythonChanged = files.some(isValidationPython);

  return {
    runAll,
    affectedServices: [...affectedServices],
    affectedModules: [...affectedModules],
    bootableModules: [...affectedModules].filter((module) => BOOTABLE_MODULES.has(module)),
    docsChanged: runAll || files.some((file) => isDocumentation(file) || isRuntimeAuthority(file)),
    frontendChanged:
      runAll ||
      files.some(
        (file) => isFrontend(file) || file === ".node-version" || file === ".python-version",
      ),
    pythonChanged: forceAll || pythonFiles.length > 0,
    designDocsChanged,
    validationPythonChanged,
    postgresRuntimeProofChanged: files.some(isPostgresRuntimeProofRelevant),
    lightweightOnly:
      !forceAll &&
      files.length > 0 &&
      files.every(isLightweightEligible),
  };
}

async function classifyGithubChangeScope(github, context) {
  if (context.eventName === "push") {
    const scope = classifyChangeScope([], { forceAll: true });
    const payload = context.payload || {};
    if (payload.deleted === true) {
      return { ...scope, postgresRuntimeProofChanged: false };
    }

    const before = payload.before;
    const after = payload.after;
    const validSha = (sha) => typeof sha === "string" && /^[0-9a-f]{40}$/i.test(sha);
    const pushRangeUsable =
      payload.forced === false &&
      validSha(before) &&
      validSha(after) &&
      !/^0{40}$/i.test(before) &&
      !/^0{40}$/i.test(after);
    let files = [];
    let fileListComplete = false;

    if (pushRangeUsable) {
      try {
        const response = await github.rest.repos.compareCommitsWithBasehead({
          ...context.repo,
          basehead: `${before}...${after}`,
          per_page: 100,
        });
        const comparison = response?.data;
        const entries = comparison?.files;
        const statusUsable =
          comparison?.status === "ahead" || comparison?.status === "identical";
        const exactRange =
          comparison?.base_commit?.sha?.toLowerCase() === before.toLowerCase() &&
          comparison?.merge_base_commit?.sha?.toLowerCase() === before.toLowerCase();
        const entriesValid =
          Array.isArray(entries) &&
          entries.length < 300 &&
          entries.every((entry) => {
            if (
              !entry ||
              typeof entry !== "object" ||
              typeof entry.filename !== "string" ||
              entry.filename.length === 0 ||
              !["added", "removed", "modified", "renamed"].includes(entry.status)
            ) {
              return false;
            }
            if (entry.status === "renamed") {
              return typeof entry.previous_filename === "string" && entry.previous_filename.length > 0;
            }
            return (
              entry.previous_filename === undefined ||
              (typeof entry.previous_filename === "string" && entry.previous_filename.length > 0)
            );
          });

        fileListComplete = statusUsable && exactRange && entriesValid;
        if (fileListComplete) {
          files = entries.flatMap((entry) =>
            entry.previous_filename === undefined
              ? [entry.filename]
              : [entry.filename, entry.previous_filename],
          );
        }
      } catch {
        // Missing, unavailable, or malformed comparison evidence requires the physical proof.
      }
    }

    return {
      ...scope,
      postgresRuntimeProofChanged: !fileListComplete || files.some(isPostgresRuntimeProofRelevant),
    };
  }

  if (context.eventName !== "pull_request") {
    return {
      ...classifyChangeScope([], { forceAll: true }),
      postgresRuntimeProofChanged: false,
    };
  }

  const fileEntries = await github.paginate(
    github.rest.pulls.listFiles,
    {
      ...context.repo,
      pull_number: context.payload.pull_request.number,
      per_page: 100,
    },
    (response) => response.data,
  );
  const files = [];
  let fileEntriesValid = true;
  for (const entry of fileEntries) {
    if (
      !entry ||
      typeof entry !== "object" ||
      typeof entry.filename !== "string" ||
      entry.filename.length === 0
    ) {
      fileEntriesValid = false;
      continue;
    }
    files.push(entry.filename);
    if (entry.previous_filename !== undefined) {
      if (typeof entry.previous_filename !== "string" || entry.previous_filename.length === 0) {
        fileEntriesValid = false;
      } else {
        files.push(entry.previous_filename);
      }
    }
  }
  const expectedFileCount = context.payload.pull_request.changed_files;
  const fileListComplete =
    fileEntriesValid &&
    Number.isInteger(expectedFileCount) &&
    expectedFileCount === fileEntries.length;

  const scope = classifyChangeScope(files, { forceAll: !fileListComplete });
  return {
    ...scope,
    postgresRuntimeProofChanged:
      !fileListComplete || scope.postgresRuntimeProofChanged,
  };
}

module.exports = {
  ALL_SERVICES,
  ALL_MODULES,
  BOOTABLE_MODULES,
  classifyChangeScope,
  classifyGithubChangeScope,
  isDocumentation,
  isLightweightEligible,
  isPostgresRuntimeProofRelevant,
  isValidationPython,
  isValidationTooling,
  moduleForFile,
};
