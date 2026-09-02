import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import test from 'node:test'

const execFileAsync = promisify(execFile)
const scriptPath = fileURLToPath(new URL('start-local.sh', import.meta.url))
const powershellScriptPath = fileURLToPath(new URL('start-local.ps1', import.meta.url))
const commonScriptPath = fileURLToPath(new URL('local-common.sh', import.meta.url))
const qualityGateScriptPath = fileURLToPath(new URL('quality-gate.sh', import.meta.url))
const shellScripts = [
  'check-environment.sh',
  'local-common.sh',
  'quality-gate.sh',
  'start-local.sh',
  'status-local.sh',
  'stop-local.sh',
].map((name) => fileURLToPath(new URL(name, import.meta.url)))
const posixOnly = {
  skip: process.platform === 'win32' ? 'POSIX shell tests require a POSIX host' : false,
}

async function runStartLocal(...args) {
  try {
    const result = await execFileAsync('bash', [scriptPath, ...args])
    return { ...result, code: 0 }
  } catch (error) {
    return {
      code: error.code,
      stdout: error.stdout ?? '',
      stderr: error.stderr ?? '',
    }
  }
}

async function runQualityGate(...args) {
  try {
    const result = await execFileAsync('bash', [qualityGateScriptPath, ...args])
    return { ...result, code: 0 }
  } catch (error) {
    return {
      code: error.code,
      stdout: error.stdout ?? '',
      stderr: error.stderr ?? '',
    }
  }
}

test('macOS/Linux local scripts have valid Bash syntax', posixOnly, async () => {
  await execFileAsync('bash', ['-n', ...shellScripts])
})

test('macOS/Linux quality gate exposes a native strict mode', posixOnly, async () => {
  const result = await runQualityGate('--help')

  assert.equal(result.code, 0)
  assert.match(result.stdout, /--strict/)
  assert.match(result.stdout, /PowerShell/i)
})

test('macOS/Linux quality gate rejects unknown options before running checks', posixOnly, async () => {
  const result = await runQualityGate('--unknown-option')

  assert.equal(result.code, 2)
  assert.match(result.stderr, /Unknown option/)
  assert.doesNotMatch(result.stdout, /\[(?:PASS|FAIL|SKIP)\]/)
})

test('macOS/Linux quality gate resolves a repository path containing spaces', posixOnly, async (context) => {
  const temporaryDirectory = await mkdtemp(join(tmpdir(), 'medication quality gate '))
  const linkedRepository = join(temporaryDirectory, 'repository with spaces')
  const repositoryRoot = fileURLToPath(new URL('..', import.meta.url))

  context.after(async () => {
    await rm(temporaryDirectory, { recursive: true, force: true })
  })

  await symlink(repositoryRoot, linkedRepository, 'dir')
  const result = await execFileAsync('bash', [
    join(linkedRepository, 'scripts', 'quality-gate.sh'),
    '--help',
  ])

  assert.match(result.stdout, /--strict/)
})

test('help documents controlled restart options', posixOnly, async () => {
  const result = await runStartLocal('--help')

  assert.equal(result.code, 0)
  assert.match(result.stdout, /--restart-api/)
  assert.match(result.stdout, /--media-worker-concurrency/)
  assert.match(result.stdout, /--ai-worker-concurrency/)
})

test('local startup verifies every host dependency port before application startup', async () => {
  const [source, powershellSource] = await Promise.all([
    readFile(scriptPath, 'utf8'),
    readFile(powershellScriptPath, 'utf8'),
  ])

  assert.match(source, /--ai-worker\s+Enable the AI Worker after Core API\./)
  assert.match(source, /"postgres\|PostgreSQL\|\$\{POSTGRES_PORT:-15432\}"/)
  assert.match(source, /"s3\|S3\|\$\{S3_PORT:-9000\}"/)
  assert.match(source, /"rabbitmq\|RabbitMQ\|\$\{RABBITMQ_PORT:-5672\}"/)
  assert.match(source, /--force-recreate "\$service_name"/)
  assert.match(powershellSource, /\$requiredHostPorts = @\(/)
  assert.match(powershellSource, /\$requiredHostPort\.Service/)
  assert.match(powershellSource, /Core API process exited before becoming healthy/)

  const dependencyPortChecks = source.indexOf('required_host_ports=')
  const applicationStartup = source.indexOf('mkdir -p "$LOG_DIRECTORY"')
  assert.ok(dependencyPortChecks >= 0)
  assert.ok(applicationStartup > dependencyPortChecks)
})

test('default startup keeps Media Worker enabled without a compatibility flag', async () => {
  const [source, powershellSource] = await Promise.all([
    readFile(scriptPath, 'utf8'),
    readFile(powershellScriptPath, 'utf8'),
  ])

  assert.doesNotMatch(source, /start_worker|Legacy compatibility flag/)
  assert.doesNotMatch(powershellSource, /\$Worker\b/)
  assert.match(source, /if ! \$infrastructure_only; then/)
  assert.match(source, /uv run --directory "\$PROJECT_ROOT\/workers\/media-worker" media-worker/)
  assert.match(powershellSource, /if \(-not \$InfrastructureOnly\) \{/)
  assert.match(powershellSource, /uv run --directory workers\/media-worker media-worker/)
})

test('local startup defaults to two bounded worker replicas', async () => {
  const [source, powershellSource] = await Promise.all([
    readFile(scriptPath, 'utf8'),
    readFile(powershellScriptPath, 'utf8'),
  ])

  assert.match(source, /--media-worker-concurrency COUNT/)
  assert.match(source, /--ai-worker-concurrency COUNT/)
  assert.match(source, /media_worker_concurrency=2/)
  assert.match(source, /ai_worker_concurrency=2/)
  assert.match(source, /worker_index <= media_worker_concurrency/)
  assert.match(source, /worker_index <= ai_worker_concurrency/)
  assert.match(powershellSource, /\[int\]\$MediaWorkerConcurrency = 2/)
  assert.match(powershellSource, /\[int\]\$AiWorkerConcurrency = 2/)
  assert.match(powershellSource, /\$workerIndex -le \$MediaWorkerConcurrency/)
  assert.match(powershellSource, /\$workerIndex -le \$AiWorkerConcurrency/)
})

test('infrastructure-only startup exits before Media Worker startup', async () => {
  const [source, powershellSource] = await Promise.all([
    readFile(scriptPath, 'utf8'),
    readFile(powershellScriptPath, 'utf8'),
  ])
  const infrastructureOnlyExit = source.indexOf('if $infrastructure_only; then')
  const mediaWorkerStartup = source.indexOf('uv run --directory "$PROJECT_ROOT/workers/media-worker" media-worker')
  const powershellInfrastructureOnlyExit = powershellSource.indexOf('if ($InfrastructureOnly) {')
  const powershellMediaWorkerStartup = powershellSource.indexOf('Start-LocalProcess $workerKey')

  assert.notEqual(infrastructureOnlyExit, -1)
  assert.notEqual(mediaWorkerStartup, -1)
  assert.ok(infrastructureOnlyExit < mediaWorkerStartup)
  assert.match(source.slice(infrastructureOnlyExit, mediaWorkerStartup), /exit 0/)
  assert.notEqual(powershellInfrastructureOnlyExit, -1)
  assert.notEqual(powershellMediaWorkerStartup, -1)
  assert.ok(powershellInfrastructureOnlyExit < powershellMediaWorkerStartup)
  assert.match(powershellSource.slice(powershellInfrastructureOnlyExit, powershellMediaWorkerStartup), /return/)
})

test('AI Worker startup remains configuration-gated', async () => {
  const [source, powershellSource] = await Promise.all([
    readFile(scriptPath, 'utf8'),
    readFile(powershellScriptPath, 'utf8'),
  ])

  assert.match(source, /AI_HAND_POSE_ENABLED:-false/)
  assert.match(source, /AI_HAND_POSE_MODEL_PATH/)
  assert.match(source, /AI_HAND_POSE_MODEL_SHA256/)
  assert.match(source, /AI Worker startup requires AI_HAND_POSE_ENABLED=true/)
  assert.match(powershellSource, /AI_HAND_POSE_ENABLED/)
  assert.match(powershellSource, /AI_HAND_POSE_MODEL_PATH/)
  assert.match(powershellSource, /AI_HAND_POSE_MODEL_SHA256/)
})

test('start-local rejects unknown arguments', posixOnly, async () => {
  const result = await runStartLocal('--unknown-option')

  assert.equal(result.code, 2)
  assert.match(result.stderr, /Unknown option: --unknown-option/)
})

test('start-local rejects invalid worker replica counts before startup', posixOnly, async () => {
  const result = await runStartLocal('--media-worker-concurrency', '0')

  assert.equal(result.code, 2)
  assert.match(result.stderr, /--media-worker-concurrency must be an integer from 1 to 8/)
})

test('infrastructure-only mode rejects application restart options', posixOnly, async () => {
  const result = await runStartLocal('--infrastructure-only', '--restart-api')

  assert.equal(result.code, 2)
  assert.match(result.stderr, /cannot start or restart applications/)
})

test(
  'managed stop terminates only the selected process and preserves other records',
  posixOnly,
  async (context) => {
    const runtimeDirectory = await mkdtemp(join(tmpdir(), 'medication-local-scripts-'))
    const runtimeFile = join(runtimeDirectory, 'managed-processes.tsv')
    const target = spawn('sleep', ['60'], { stdio: 'ignore' })
    const keeper = spawn('sleep', ['60'], { stdio: 'ignore' })

    context.after(async () => {
      for (const processHandle of [target, keeper]) {
        try {
          processHandle.kill('SIGKILL')
        } catch {
          // The helper may already have stopped the target tree.
        }
      }
      await rm(runtimeDirectory, { recursive: true, force: true })
    })

    await writeFile(
      runtimeFile,
      [
        `target\t${target.pid}\tTarget process\t${runtimeDirectory}/target.log`,
        `keeper\t${keeper.pid}\tKeeper process\t${runtimeDirectory}/keeper.log`,
        '',
      ].join('\n'),
    )

    await execFileAsync('bash', [
      '-c',
      'source "$1"; LOG_DIRECTORY="$2"; RUNTIME_FILE="$3"; stop_managed_process target "target process"',
      'managed-stop-test',
      commonScriptPath,
      runtimeDirectory,
      runtimeFile,
    ])

    assert.throws(() => process.kill(target.pid, 0))
    assert.doesNotThrow(() => process.kill(keeper.pid, 0))
    const remainingRecords = await readFile(runtimeFile, 'utf8')
    assert.doesNotMatch(remainingRecords, /^target\t/m)
    assert.match(remainingRecords, /^keeper\t/m)
  },
)
