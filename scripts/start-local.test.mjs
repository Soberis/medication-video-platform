import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import test from 'node:test'

const execFileAsync = promisify(execFile)
const scriptPath = fileURLToPath(new URL('start-local.sh', import.meta.url))
const commonScriptPath = fileURLToPath(new URL('local-common.sh', import.meta.url))
const shellScripts = ['local-common.sh', 'start-local.sh', 'status-local.sh', 'stop-local.sh'].map(
  (name) => fileURLToPath(new URL(name, import.meta.url)),
)
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

test('macOS/Linux local scripts have valid Bash syntax', posixOnly, async () => {
  await execFileAsync('bash', ['-n', ...shellScripts])
})

test('help documents controlled restart and synthetic recovery options', posixOnly, async () => {
  const result = await runStartLocal('--help')

  assert.equal(result.code, 0)
  assert.match(result.stdout, /--restart-api/)
  assert.match(result.stdout, /--restart-export-worker/)
  assert.match(result.stdout, /--export-worker-synthetic-fail-first-attempt/)
  assert.match(result.stdout, /--export-worker-synthetic-lease-loss-first-attempt/)
})

test('Export Worker restart requires workers to be enabled', posixOnly, async () => {
  const result = await runStartLocal('--restart-export-worker')

  assert.equal(result.code, 2)
  assert.match(result.stderr, /requires --worker/)
})

test(
  'synthetic failure injection requires an explicit Export Worker restart',
  posixOnly,
  async () => {
    const result = await runStartLocal('--worker', '--export-worker-synthetic-fail-first-attempt')

    assert.equal(result.code, 2)
    assert.match(result.stderr, /requires --restart-export-worker/)
  },
)

test(
  'synthetic lease-loss injection requires an explicit Export Worker restart',
  posixOnly,
  async () => {
    const result = await runStartLocal(
      '--worker',
      '--export-worker-synthetic-lease-loss-first-attempt',
    )

    assert.equal(result.code, 2)
    assert.match(result.stderr, /requires --restart-export-worker/)
  },
)

test(
  'synthetic lease-loss injection requires a controlled Core API restart',
  posixOnly,
  async () => {
    const result = await runStartLocal(
      '--worker',
      '--restart-export-worker',
      '--export-worker-synthetic-lease-loss-first-attempt',
    )

    assert.equal(result.code, 2)
    assert.match(result.stderr, /requires --restart-api/)
  },
)

test('synthetic failure and lease-loss injections are mutually exclusive', posixOnly, async () => {
  const result = await runStartLocal(
    '--worker',
    '--restart-api',
    '--restart-export-worker',
    '--export-worker-synthetic-fail-first-attempt',
    '--export-worker-synthetic-lease-loss-first-attempt',
  )

  assert.equal(result.code, 2)
  assert.match(result.stderr, /mutually exclusive/)
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
