import { createHash } from 'node:crypto'
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { chmod, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

export const VIEWPORTS = [
  [1366, 768],
  [1920, 1080],
  [2560, 1440],
  [3840, 2160],
]

const EDGE_PATH = '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const SELF_ADMIN_MARKER = '管理员自审（SELF_ADMIN_APPROVAL）'

export function assertPreflightEnvironment(environment = process.env) {
  if (environment.RUN_P0_WEB_PREFLIGHT !== 'true') {
    throw new Error('Set RUN_P0_WEB_PREFLIGHT=true to run the local browser precheck')
  }
  if (environment.DATA_PROFILE !== 'synthetic') {
    throw new Error('P0 Web precheck requires DATA_PROFILE=synthetic')
  }
  const baseUrl = requireLoopbackUrl(
    environment.P0_WEB_PREFLIGHT_BASE_URL || 'http://127.0.0.1:5173',
  )
  return {
    baseUrl,
    username: requireText(environment.WEB_PREFLIGHT_USERNAME, 'WEB_PREFLIGHT_USERNAME'),
    password: requireText(environment.WEB_PREFLIGHT_PASSWORD, 'WEB_PREFLIGHT_PASSWORD'),
    edgePath: environment.P0_WEB_PREFLIGHT_EDGE_PATH || EDGE_PATH,
    projectId: requireUuid(environment.P0_WEB_PREFLIGHT_PROJECT_ID, 'P0_WEB_PREFLIGHT_PROJECT_ID'),
    annotationTaskId: requireUuid(
      environment.P0_WEB_PREFLIGHT_ANNOTATION_TASK_ID,
      'P0_WEB_PREFLIGHT_ANNOTATION_TASK_ID',
    ),
    datasetVersionId: requireUuid(
      environment.P0_WEB_PREFLIGHT_DATASET_VERSION_ID,
      'P0_WEB_PREFLIGHT_DATASET_VERSION_ID',
    ),
  }
}

export function assertPageCheck(check) {
  if (check.horizontalOverflow) throw new Error('Page-level horizontal overflow was detected')
  if (!check.focusVisible) throw new Error('First keyboard focus target is not visible')
  if (check.missingTexts.length)
    throw new Error(`Missing page text: ${check.missingTexts.join(', ')}`)
  if (!check.specificEvidence) throw new Error('Required self-admin evidence is incomplete')
}

export async function runPreflight(environment = process.env, cwd = process.cwd()) {
  const config = assertPreflightEnvironment(environment)
  if (!existsSync(config.edgePath))
    throw new Error(`Microsoft Edge is not available at ${config.edgePath}`)

  const runDirectory = await createRunDirectory(cwd)
  const profileDirectory = await mkdtemp(path.join(tmpdir(), 'medication-web-preflight-'))
  let edge
  let cdp
  let sessionId
  let loggedIn = false
  const evidence = {
    schema_version: 'p0-web-local-precheck-v1',
    outcome: 'PRECHECK_FAILED',
    data_profile: 'synthetic',
    base_url: config.baseUrl,
    fixture: {
      project_id: config.projectId,
      annotation_task_id: config.annotationTaskId,
      dataset_version_id: config.datasetVersionId,
    },
    limitations: [
      'This is a local precheck, not formal UAT.',
      'It only creates and clears an authentication session while reading login, annotation, and dataset pages.',
      'It does not upload, play, download, create, freeze, approve, or export data.',
    ],
    checks: [],
  }

  try {
    await chmod(profileDirectory, 0o700)
    edge = await launchEdge(config.edgePath, profileDirectory, environment)
    cdp = await CdpClient.connect(edge.webSocketUrl)
    const version = await cdp.call('Browser.getVersion')
    evidence.browser = {
      product: version.product || edge.browser,
      protocol_version: version.protocolVersion || null,
    }

    const target = await cdp.call('Target.createTarget', { url: 'about:blank' })
    sessionId = (
      await cdp.call('Target.attachToTarget', { targetId: target.targetId, flatten: true })
    ).sessionId
    await cdp.call('Page.enable', {}, sessionId)
    await cdp.call('Runtime.enable', {}, sessionId)

    for (const [width, height] of VIEWPORTS) {
      await setViewport(cdp, sessionId, width, height)
      await navigate(cdp, sessionId, `${config.baseUrl}/login`)
      const check = await inspectLogin(cdp, sessionId)
      assertPageCheck(check)
      check.screenshot = await capture(cdp, sessionId, runDirectory, `login-${width}x${height}.png`)
      evidence.checks.push({ page: 'login', width, height, ...check })
    }

    const annotationPath = `/projects/${config.projectId}/annotation-tasks`
    await navigateWithoutExpectation(cdp, sessionId, `${config.baseUrl}${annotationPath}`)
    await waitFor(
      cdp,
      sessionId,
      `location.pathname === '/login' && new URLSearchParams(location.search).get('redirect') === ${JSON.stringify(annotationPath)}`,
    )
    evidence.unauthenticated_redirect = true

    await login(cdp, sessionId, config.username, config.password)
    await waitFor(cdp, sessionId, `location.pathname === ${JSON.stringify(annotationPath)}`, 20_000)
    const session = await readSession(cdp, sessionId)
    if (session.username !== config.username || session.role !== 'ADMIN') {
      throw new Error('The browser did not authenticate the requested ADMIN account')
    }
    loggedIn = true
    evidence.session = { username_matches_input: true, role: 'ADMIN' }

    for (const [width, height] of VIEWPORTS) {
      await setViewport(cdp, sessionId, width, height)
      await navigate(cdp, sessionId, `${config.baseUrl}${annotationPath}`)
      const check = await inspectAnnotation(cdp, sessionId, config.annotationTaskId)
      assertPageCheck(check)
      await revealAnnotationEvidence(cdp, sessionId, config.annotationTaskId)
      check.screenshot = await capture(
        cdp,
        sessionId,
        runDirectory,
        `annotation-${width}x${height}.png`,
      )
      evidence.checks.push({ page: 'annotation', width, height, ...check })
    }

    const datasetPath = `/projects/${config.projectId}/datasets/${config.datasetVersionId}`
    for (const [width, height] of VIEWPORTS) {
      await setViewport(cdp, sessionId, width, height)
      await navigate(cdp, sessionId, `${config.baseUrl}${datasetPath}`)
      const check = await inspectDataset(cdp, sessionId)
      assertPageCheck(check)
      await revealDatasetEvidence(cdp, sessionId, '构建准备')
      check.freeze_screenshot = await capture(
        cdp,
        sessionId,
        runDirectory,
        `dataset-freeze-${width}x${height}.png`,
      )
      await revealDatasetEvidence(cdp, sessionId, '数据集导出')
      check.export_screenshot = await capture(
        cdp,
        sessionId,
        runDirectory,
        `dataset-export-${width}x${height}.png`,
      )
      evidence.checks.push({ page: 'dataset', width, height, ...check })
    }

    evidence.logout = await logout(cdp, sessionId)
    loggedIn = false
    evidence.outcome = 'PRECHECK_PASSED'
  } catch (error) {
    evidence.failure = safeError(error)
  }

  if (loggedIn && cdp && sessionId) {
    try {
      evidence.logout = await logout(cdp, sessionId)
    } catch {
      evidence.logout = { completed: false }
    }
  }
  try {
    await close(cdp, edge, profileDirectory)
  } catch (error) {
    evidence.cleanup_failure = safeError(error)
    if (evidence.outcome === 'PRECHECK_PASSED') {
      evidence.outcome = 'PRECHECK_FAILED'
      evidence.failure = evidence.cleanup_failure
    }
  }
  await writePrivateJson(
    runDirectory,
    evidence.outcome === 'PRECHECK_PASSED' ? 'evidence.json' : 'failure.json',
    evidence,
  )

  if (evidence.outcome !== 'PRECHECK_PASSED')
    throw new Error(evidence.failure || 'Browser precheck failed')
  return {
    outcome: evidence.outcome,
    evidence_path: path.join(runDirectory, 'evidence.json'),
    checks: evidence.checks.length,
  }
}

async function inspectLogin(cdp, sessionId) {
  await waitFor(
    cdp,
    sessionId,
    "document.querySelector('#login-title')?.textContent === '登录数据管理平台'",
  )
  return inspect(cdp, sessionId, ['登录数据管理平台', '账号', '密码', '登录'], 'true')
}

async function inspectAnnotation(cdp, sessionId, taskId) {
  await waitFor(
    cdp,
    sessionId,
    "document.querySelector('#annotation-task-title')?.textContent === '标注任务'",
  )
  const evidence = `(() => {
    const row = [...document.querySelectorAll('tr[data-row-key]')].find((item) => item.getAttribute('data-row-key') === ${JSON.stringify(taskId)})
    const text = row?.innerText || ''
    return Boolean(row) && text.includes('复核通过') && text.includes(${JSON.stringify(SELF_ADMIN_MARKER)}) && text.includes('当前规则检查通过')
  })()`
  return inspect(cdp, sessionId, ['标注任务', SELF_ADMIN_MARKER], evidence)
}

async function inspectDataset(cdp, sessionId) {
  await waitFor(cdp, sessionId, "document.querySelector('#dataset-version-title') !== null")
  const evidence = `(() => {
    const cards = [...document.querySelectorAll('.detail-card')]
    const textFor = (title) => cards.find((card) => card.querySelector('.ant-card-head-title')?.innerText.includes(title))?.innerText || ''
    const freeze = textFor('构建准备')
    const exported = textFor('数据集导出')
    return freeze.includes('数据集已冻结') && freeze.includes(${JSON.stringify(SELF_ADMIN_MARKER)}) && exported.includes('训练包已通过校验') && exported.includes('可下载') && exported.includes(${JSON.stringify(SELF_ADMIN_MARKER)})
  })()`
  return inspect(
    cdp,
    sessionId,
    ['数据集已冻结', '训练包已通过校验', '可下载', SELF_ADMIN_MARKER],
    evidence,
  )
}

async function inspect(cdp, sessionId, requiredTexts, evidenceExpression) {
  await waitFor(
    cdp,
    sessionId,
    `(${JSON.stringify(requiredTexts)}).every((value) => (document.body?.innerText || '').includes(value))`,
  )
  await evaluate(cdp, sessionId, 'scrollTo(0, 0); true')
  const focusVisible = await focusFirstElement(cdp, sessionId)
  return evaluate(
    cdp,
    sessionId,
    `(() => {
    const root = document.scrollingElement || document.documentElement
    const text = document.body?.innerText || ''
    return {
      horizontalOverflow: root.scrollWidth > root.clientWidth + 1,
      focusVisible: ${JSON.stringify(focusVisible)},
      missingTexts: ${JSON.stringify(requiredTexts)}.filter((value) => !text.includes(value)),
      specificEvidence: Boolean(${evidenceExpression}),
    }
  })()`,
  )
}

async function focusFirstElement(cdp, sessionId) {
  await evaluate(cdp, sessionId, 'document.activeElement?.blur(); true')
  await cdp.call(
    'Input.dispatchKeyEvent',
    { type: 'rawKeyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 },
    sessionId,
  )
  await cdp.call(
    'Input.dispatchKeyEvent',
    { type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 },
    sessionId,
  )
  return evaluate(
    cdp,
    sessionId,
    `(() => {
    const rect = document.activeElement?.getBoundingClientRect()
    return Boolean(rect && rect.width > 0 && rect.height > 0 && rect.left >= 0 && rect.top >= 0 && rect.right <= innerWidth && rect.bottom <= innerHeight)
  })()`,
  )
}

async function navigate(cdp, sessionId, url) {
  const result = await cdp.call('Page.navigate', { url }, sessionId)
  if (result.errorText) throw new Error(`Navigation failed: ${result.errorText}`)
  const target = new URL(url)
  await waitFor(
    cdp,
    sessionId,
    `location.origin === ${JSON.stringify(target.origin)} && location.pathname === ${JSON.stringify(target.pathname)}`,
  )
  await waitFor(cdp, sessionId, "document.readyState === 'complete'")
}

async function navigateWithoutExpectation(cdp, sessionId, url) {
  const result = await cdp.call('Page.navigate', { url }, sessionId)
  if (result.errorText) throw new Error(`Navigation failed: ${result.errorText}`)
  await waitFor(cdp, sessionId, "document.readyState === 'complete'")
}

async function login(cdp, sessionId, username, password) {
  const documentResult = await cdp.call(
    'Runtime.evaluate',
    { expression: 'document', returnByValue: false },
    sessionId,
  )
  const objectId = documentResult.result?.objectId
  if (!objectId) throw new Error('Login form is unavailable')
  await cdp.call(
    'Runtime.callFunctionOn',
    {
      objectId,
      functionDeclaration: `function(username, password) {
      const set = (element, value) => {
        const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
        setter.call(element, value)
        element.dispatchEvent(new Event('input', { bubbles: true }))
        element.dispatchEvent(new Event('change', { bubbles: true }))
      }
      const usernameInput = this.querySelector('input[autocomplete=username]')
      const passwordInput = this.querySelector('input[autocomplete=current-password]')
      const submit = this.querySelector('button[type=submit]')
      if (!usernameInput || !passwordInput || !submit) throw new Error('Login inputs are unavailable')
      set(usernameInput, username)
      set(passwordInput, password)
      submit.click()
    }`,
      arguments: [{ value: username }, { value: password }],
    },
    sessionId,
  )
}

async function readSession(cdp, sessionId) {
  const session = await evaluate(
    cdp,
    sessionId,
    `fetch('/api/v1/auth/session', { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
    .then(async (response) => ({ status: response.status, body: await response.json().catch(() => null) }))`,
  )
  if (session?.status !== 200) throw new Error('Browser session check failed')
  return session.body
}

async function logout(cdp, sessionId) {
  const result = await evaluate(
    cdp,
    sessionId,
    `(() => {
    const token = document.cookie.split(';').map((item) => item.trim()).find((item) => item.startsWith('XSRF-TOKEN='))?.slice(11)
    const headers = { Accept: 'application/json' }
    if (token) headers['X-XSRF-TOKEN'] = decodeURIComponent(token)
    return fetch('/api/v1/auth/logout', { method: 'POST', headers, credentials: 'same-origin' })
      .then((response) => ({ completed: response.ok, status: response.status }))
  })()`,
  )
  if (!result?.completed) throw new Error('Browser logout failed')
  return result
}

async function revealAnnotationEvidence(cdp, sessionId, taskId) {
  const visible = await evaluate(
    cdp,
    sessionId,
    `(() => {
    const row = [...document.querySelectorAll('tr[data-row-key]')].find((item) => item.getAttribute('data-row-key') === ${JSON.stringify(taskId)})
    const marker = [...(row?.querySelectorAll('*') || [])]
      .filter((item) => item.innerText?.includes(${JSON.stringify(SELF_ADMIN_MARKER)}))
      .sort((left, right) => left.innerText.length - right.innerText.length)[0]
    marker?.scrollIntoView({ block: 'center', inline: 'nearest' })
    const rect = marker?.getBoundingClientRect()
    return Boolean(rect && rect.width > 0 && rect.height > 0 && rect.top >= 0 && rect.bottom <= innerHeight)
  })()`,
  )
  if (!visible) throw new Error('Annotation self-admin marker is not visible for the screenshot')
}

async function revealDatasetEvidence(cdp, sessionId, title) {
  const visible = await evaluate(
    cdp,
    sessionId,
    `(() => {
    const card = [...document.querySelectorAll('.detail-card')].find((item) => item.querySelector('.ant-card-head-title')?.innerText.includes(${JSON.stringify(title)}))
    const marker = [...(card?.querySelectorAll('*') || [])]
      .filter((item) => item.innerText?.includes(${JSON.stringify(SELF_ADMIN_MARKER)}))
      .sort((left, right) => left.innerText.length - right.innerText.length)[0]
    marker?.scrollIntoView({ block: 'center', inline: 'nearest' })
    const rect = marker?.getBoundingClientRect()
    return Boolean(rect && rect.width > 0 && rect.height > 0 && rect.top >= 0 && rect.bottom <= innerHeight)
  })()`,
  )
  if (!visible) throw new Error(`${title} self-admin marker is not visible for the screenshot`)
}

async function capture(cdp, sessionId, directory, name) {
  const response = await cdp.call(
    'Page.captureScreenshot',
    { format: 'png', fromSurface: true, captureBeyondViewport: false },
    sessionId,
  )
  const bytes = Buffer.from(response.data, 'base64')
  await writeFile(path.join(directory, name), bytes, { mode: 0o600 })
  return { file: name, sha256: createHash('sha256').update(bytes).digest('hex') }
}

async function launchEdge(edgePath, profileDirectory, environment) {
  const port = await reservePort()
  const child = spawn(
    edgePath,
    [
      '--headless=new',
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-background-networking',
      '--remote-debugging-address=127.0.0.1',
      `--remote-debugging-port=${port}`,
      `--user-data-dir=${profileDirectory}`,
      'about:blank',
    ],
    { stdio: 'ignore', env: edgeEnvironment(environment) },
  )
  const metadata = await waitForEdge(port, child)
  if (!String(metadata.Browser || '').startsWith('Edg/'))
    throw new Error('The CDP endpoint is not Microsoft Edge')
  return { child, browser: metadata.Browser, webSocketUrl: metadata.webSocketDebuggerUrl }
}

function edgeEnvironment(environment) {
  return Object.fromEntries(
    ['PATH', 'HOME', 'TMPDIR', 'LANG']
      .filter((key) => environment[key])
      .map((key) => [key, environment[key]]),
  )
}

async function reservePort() {
  const server = createServer()
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const address = server.address()
  await new Promise((resolve) => server.close(resolve))
  if (!address || typeof address === 'string') throw new Error('Could not reserve CDP port')
  return address.port
}

async function waitForEdge(port, child) {
  const deadline = Date.now() + 10_000
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error('Microsoft Edge exited during startup')
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`, {
        signal: AbortSignal.timeout(500),
      })
      if (response.ok) return await response.json()
    } catch {
      // Edge is still starting.
    }
    await delay(100)
  }
  throw new Error('Timed out waiting for Microsoft Edge')
}

async function setViewport(cdp, sessionId, width, height) {
  await cdp.call(
    'Emulation.setDeviceMetricsOverride',
    {
      width,
      height,
      screenWidth: width,
      screenHeight: height,
      deviceScaleFactor: 1,
      mobile: false,
    },
    sessionId,
  )
}

async function waitFor(cdp, sessionId, expression, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      if (await evaluate(cdp, sessionId, expression)) return
    } catch {
      // A page navigation briefly replaces the execution context.
    }
    await delay(100)
  }
  throw new Error('Timed out waiting for the expected browser state')
}

async function evaluate(cdp, sessionId, expression) {
  const result = await cdp.call(
    'Runtime.evaluate',
    { expression, returnByValue: true, awaitPromise: true },
    sessionId,
  )
  if (result.exceptionDetails) throw new Error('Browser expression failed')
  return result.result?.value
}

async function createRunDirectory(cwd) {
  const reportsDirectory = path.join(path.resolve(cwd), 'reports', 'web-preflight')
  await mkdir(reportsDirectory, { recursive: true, mode: 0o700 })
  const directory = await mkdtemp(path.join(reportsDirectory, 'run-'))
  await chmod(directory, 0o700)
  return directory
}

async function close(cdp, edge, profileDirectory) {
  if (cdp) {
    try {
      await cdp.call('Browser.close', {}, undefined, 2_000)
    } catch {}
    cdp.close()
  }
  if (edge?.child.exitCode === null) edge.child.kill('SIGTERM')
  await rm(profileDirectory, { recursive: true, force: true, maxRetries: 2, retryDelay: 100 })
}

async function writePrivateJson(directory, name, value) {
  await writeFile(path.join(directory, name), `${JSON.stringify(value, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
  })
}

function requireLoopbackUrl(value) {
  const url = new URL(value)
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname) ||
    url.username ||
    url.password ||
    url.pathname !== '/' ||
    url.search ||
    url.hash
  ) {
    throw new Error(
      'P0_WEB_PREFLIGHT_BASE_URL must be a loopback origin without credentials or path',
    )
  }
  return url.origin
}

function requireText(value, name) {
  if (!value?.trim()) throw new Error(`${name} is required`)
  return value.trim()
}

function requireUuid(value, name) {
  if (!UUID_PATTERN.test(value || '')) throw new Error(`${name} must be a UUID`)
  return value.toLowerCase()
}

function safeError(error) {
  return (error instanceof Error ? error.message : String(error))
    .replace(/[\r\n]+/g, ' ')
    .slice(0, 240)
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

class CdpClient {
  constructor(socket) {
    this.socket = socket
    this.nextId = 1
    this.pending = new Map()
    socket.addEventListener('message', (event) => this.receive(event.data))
  }

  static async connect(url) {
    const socket = new WebSocket(url)
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Timed out opening CDP')), 5_000)
      socket.addEventListener(
        'open',
        () => {
          clearTimeout(timer)
          resolve()
        },
        { once: true },
      )
      socket.addEventListener(
        'error',
        () => {
          clearTimeout(timer)
          reject(new Error('Could not open CDP'))
        },
        { once: true },
      )
    })
    return new CdpClient(socket)
  }

  call(method, params = {}, sessionId, timeoutMs = 20_000) {
    const id = this.nextId++
    const message = { id, method, params }
    if (sessionId) message.sessionId = sessionId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`CDP timeout: ${method}`))
      }, timeoutMs)
      this.pending.set(id, { resolve, reject, timer })
      this.socket.send(JSON.stringify(message))
    })
  }

  receive(data) {
    const message = JSON.parse(data)
    const pending = this.pending.get(message.id)
    if (!pending) return
    clearTimeout(pending.timer)
    this.pending.delete(message.id)
    if (message.error) pending.reject(new Error(message.error.message))
    else pending.resolve(message.result || {})
  }

  close() {
    this.socket.close()
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  runPreflight()
    .then((result) => process.stdout.write(`${JSON.stringify(result)}\n`))
    .catch((error) => {
      process.stderr.write(`P0_WEB_PRECHECK_FAILED: ${safeError(error)}\n`)
      process.exitCode = 1
    })
}
