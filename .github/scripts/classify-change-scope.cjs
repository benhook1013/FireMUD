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
]);

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

function isPythonDependency(file) {
  return file.startsWith("config/python/");
}

function isLightweightEligible(file) {
  return (
    (isDocumentation(file) || isValidationPython(file)) &&
    file !== "dev-tools/docs/generate-erd.sh" &&
    !isRuntimeAuthority(file)
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
    lightweightOnly:
      !forceAll &&
      files.length > 0 &&
      files.every(isLightweightEligible),
  };
}

async function classifyGithubChangeScope(github, context) {
  if (context.eventName !== "pull_request") {
    return classifyChangeScope([], { forceAll: true });
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

  return classifyChangeScope(files, { forceAll: !fileListComplete });
}

module.exports = {
  ALL_SERVICES,
  ALL_MODULES,
  BOOTABLE_MODULES,
  classifyChangeScope,
  classifyGithubChangeScope,
  isDocumentation,
  isLightweightEligible,
  isValidationPython,
  isValidationTooling,
  moduleForFile,
};
