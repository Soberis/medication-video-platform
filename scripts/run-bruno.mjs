import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { createRequire } from "node:module";
import { loadEnvFile } from "node:process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const collectionDirectory = path.join(
  repositoryRoot,
  "api-tests",
  "bruno",
  "medication-platform",
);
const dotenvFile = path.join(collectionDirectory, ".env");
const localEnvironmentFiles = [
  path.join(repositoryRoot, ".env"),
  dotenvFile,
  path.join(repositoryRoot, ".env.example"),
];
for (const environmentFile of localEnvironmentFiles) {
  if (!existsSync(environmentFile)) {
    continue;
  }
  loadEnvFile(environmentFile);
}

const bearerToken = process.env.DEV_BEARER_TOKEN;
const adminBearerToken = process.env.DEV_ADMIN_BEARER_TOKEN;
const projectId = process.env.DEV_PROJECT_ID;
if (!bearerToken || !adminBearerToken || !projectId) {
  console.error(
    "DEV_BEARER_TOKEN, DEV_ADMIN_BEARER_TOKEN and DEV_PROJECT_ID are required in the process environment, repository .env, Bruno collection .env, or .env.example.",
  );
  process.exit(2);
}

const require = createRequire(import.meta.url);
const brunoCli = require.resolve("@usebruno/cli/bin/bru.js");
const result = spawnSync(
  process.execPath,
  [
    brunoCli,
    "run",
    "--env",
    "local",
    "--env-var",
    `devBearerToken=${bearerToken}`,
    "--env-var",
    `devAdminBearerToken=${adminBearerToken}`,
    "--env-var",
    `projectId=${projectId}`,
  ],
  {
    cwd: collectionDirectory,
    env: process.env,
    encoding: "utf8",
    shell: false,
  },
);

if (result.error) {
  console.error(`Unable to start Bruno CLI: ${result.error.message}`);
  process.exit(1);
}

if (result.stdout) {
  process.stdout.write(result.stdout);
}
if (result.stderr) {
  process.stderr.write(result.stderr);
}

const runnerOutput = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
if (/Skipping invalid file|parseBruRequest error/.test(runnerOutput)) {
  console.error("Bruno skipped one or more invalid request files; the API test run is not valid.");
  process.exit(1);
}

process.exit(result.status ?? 1);
