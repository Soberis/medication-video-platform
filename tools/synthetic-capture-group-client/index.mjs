import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { generateSyntheticVideo } from '../synthetic-upload-client/synthetic-video.mjs'
import { PlatformApiError, SyntheticUploadClient, normalizeCameraNumbers } from '../synthetic-upload-client/upload-client.mjs'
import { assertCaptureGroupIdentity, assertCleaningResult, assertNoSplitLeakage, assertReviewContext, assertSameCameraNumbers, validateTrainingDataV3Document } from './assertions.mjs'
import { buildScenarioFixture, getScenario, SCENARIO_NAMES } from './scenarios.mjs'
import { SyntheticCaptureGroupWorkflowClient } from './workflow-client.mjs'

const DEFAULT_BASE_URL = 'http://localhost:8080'
const DEFAULT_PROJECT_ID = '00000000-0000-0000-0000-000000000001'
const DEFAULT_TIMEOUT_MS = 120_000

function field(value, snakeCaseName, camelCaseName = snakeCaseName) {
  return value?.[snakeCaseName] ?? value?.[camelCaseName]
}

function requireField(value, snakeCaseName, camelCaseName = snakeCaseName) {
  const result = field(value, snakeCaseName, camelCaseName)
  if (result === undefined || result === null) {
    throw new Error(`API response omitted ${snakeCaseName}`)
  }
  return result
}

function assertEqual(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label} mismatch: expected ${expected}, received ${actual}`)
  }
}

function assertApiError(error, expectedStatus, expectedCode, operation) {
  if (!(error instanceof PlatformApiError)) {
    throw error
  }
  assertEqual(error.status, expectedStatus, `${operation} HTTP status`)
  assertEqual(error.errorCode, expectedCode, `${operation} error code`)
}

function qualityReasonCode(decision) {
  return decision === 'QUALIFIED'
    ? 'MEETS_REQUIREMENTS'
    : decision === 'REJECTED'
      ? 'BLURRY_OR_OUT_OF_FOCUS'
      : 'UNCERTAIN_QUALITY'
}

function receiptState(response) {
  return requireField(response, 'receipt_state', 'receiptState')
}

function taskGroupId(task) {
  return field(task, 'capture_group_id', 'captureGroupId')
}

function annotationTaskId(task) {
  return requireField(task, 'annotation_task_id', 'annotationTaskId')
}

function annotationRevisionId(revision) {
  return requireField(revision, 'annotation_revision_id', 'annotationRevisionId')
}

function revisionNumber(revision) {
  return requireField(revision, 'revision_number', 'revisionNumber')
}

function listItems(response) {
  const items = field(response, 'items')
  if (!Array.isArray(items)) {
    throw new Error('Annotation Task list response did not contain items')
  }
  return items
}

function groupTasks(response, captureGroupId) {
  return listItems(response).filter((task) => taskGroupId(task) === captureGroupId)
}

function safeViewSummary(view, quality) {
  return {
    camera_number: view.cameraNumber,
    clip_asset_id: view.clipAssetId,
    quality: field(quality, 'decision'),
  }
}

function summarizeCandidate(candidate) {
  return {
    status: field(candidate?.job, 'status'),
    hand_result: field(candidate?.job, 'hand_result', 'handResult'),
    candidate_count: Array.isArray(candidate?.candidates?.candidates) ? candidate.candidates.candidates.length : null,
  }
}

function summarizeGroupQuality(groupQuality) {
  if (!groupQuality) {
    return null
  }
  return {
    result: field(groupQuality, 'result'),
    revision: field(groupQuality, 'revision'),
    input_version: field(groupQuality, 'input_version', 'inputVersion'),
  }
}

function summarizeReview(review) {
  if (!review) {
    return null
  }
  return {
    decision: field(review, 'decision'),
    annotation_revision_id: field(review, 'annotation_revision_id', 'annotationRevisionId'),
    revision: field(review, 'revision'),
  }
}

function summarizeCleaning(cleaning) {
  if (!cleaning) {
    return null
  }
  return {
    outcome: field(cleaning, 'outcome'),
    capture_group_cleaning_id: field(cleaning, 'capture_group_cleaning_id', 'captureGroupCleaningId'),
    participating_camera_numbers: (field(cleaning, 'participating_views', 'participatingViews') ?? [])
      .map((view) => field(view, 'camera_number', 'cameraNumber')),
    excluded_camera_numbers: (field(cleaning, 'excluded_views', 'excludedViews') ?? [])
      .map((view) => field(view, 'camera_number', 'cameraNumber')),
  }
}

function baseSummary(fixture, projectId, captureGroupId) {
  return {
    scenario: fixture.name,
    project_id: projectId,
    capture_group_id: captureGroupId,
    expected_camera_numbers: fixture.expectedCameraNumbers,
    present_camera_numbers: fixture.presentCameraNumbers,
    missing_camera_numbers: fixture.missingCameraNumbers,
    receipt_state: null,
    views: [],
    group_quality: null,
    annotation_task_id: null,
    revision: null,
    review: null,
    cleaning: null,
    export: null,
    candidates: {},
  }
}

function assertReceipt(response, fixture) {
  assertEqual(receiptState(response), fixture.receiptAfterFinalize, 'CaptureGroup receipt state')
  assertCaptureGroupIdentity(response, fixture)
}

function assertTaskHasStableAssignee(memberTask, adminTask) {
  const memberAssignee = field(memberTask, 'assignee_actor_id', 'assigneeActorId')
  const adminAssignee = field(adminTask, 'assignee_actor_id', 'assigneeActorId')
  if (!memberAssignee || memberAssignee !== adminAssignee) {
    throw new Error('CaptureGroup AnnotationTask assignee is not stable across authorized reads')
  }
}

async function makeGeneratedVideos(fixture, workDirectory, videoGenerator) {
  const videoBytesByCamera = new Map()
  for (const cameraNumber of fixture.presentCameraNumbers) {
    const outputPath = join(workDirectory, `camera-${cameraNumber}.mp4`)
    await videoGenerator(outputPath)
    const generatedBytes = await readFile(outputPath)
    if (generatedBytes.length === 0) {
      throw new Error(`Synthetic video generation returned an empty file for Camera ${cameraNumber}`)
    }
    const bytes = fixture.mediaMode === 'truncated'
      ? generatedBytes.subarray(0, Math.min(generatedBytes.length, 128))
      : generatedBytes
    videoBytesByCamera.set(cameraNumber, bytes)
  }
  return videoBytesByCamera
}

async function verifyCandidatePath(workflow, fixture, uploaded, summary, timeoutMs) {
  if (!fixture.candidateExpected) {
    return
  }
  for (const view of uploaded.views) {
    const candidate = await workflow.waitForHandPose(
      view.clipAssetId,
      fixture.candidateExpected.status,
      { timeoutMs, expectedHandResult: fixture.candidateExpected.handResult },
    )
    summary.candidates[view.cameraNumber] = summarizeCandidate(candidate)
  }
}

async function verifyMediaPath(workflow, fixture, uploaded, summary, timeoutMs) {
  for (const view of uploaded.views) {
    const result = await workflow.waitForMediaProbe(view.clipAssetId, { timeoutMs })
    assertEqual(result.job.status, fixture.mediaProbeExpected, `Camera ${view.cameraNumber} Media Probe status`)
    summary.views.push({
      camera_number: view.cameraNumber,
      clip_asset_id: view.clipAssetId,
      media_status: result.asset.status,
    })
  }
  return summary.views
}

async function verifyGroupQualityAndTasks(workflow, fixture, captureGroupId, summary) {
  const groupQuality = await workflow.recordGroupQuality(
    captureGroupId,
    fixture.groupQuality,
    `Synthetic acceptance: ${fixture.name}`,
    0,
  )
  assertEqual(field(groupQuality, 'result'), fixture.groupQuality, 'CaptureGroup Quality result')
  summary.group_quality = summarizeGroupQuality(groupQuality)

  const adminTasks = groupTasks(await workflow.listAnnotationTasks('admin'), captureGroupId)
  if (fixture.stopAfter === 'group-quality') {
    assertEqual(adminTasks.length, 0, 'non-admitting group AnnotationTask count')
    return null
  }
  assertEqual(adminTasks.length, 1, 'CaptureGroup AnnotationTask count')
  const task = adminTasks[0]
  const memberTasks = groupTasks(await workflow.listAnnotationTasks('member'), captureGroupId)
  assertEqual(memberTasks.length, 1, 'member-visible CaptureGroup AnnotationTask count')
  assertTaskHasStableAssignee(memberTasks[0], task)
  summary.annotation_task_id = annotationTaskId(task)
  return task
}

async function verifyGroupInputChange(workflow, fixture, uploaded, task, summary) {
  const change = fixture.inputChangeAfterReview
  if (!change) {
    return { fixture, groupQuality: null }
  }
  const changedView = uploaded.views.find((view) => view.cameraNumber === change.cameraNumber)
  if (!changedView) {
    throw new Error(`Input-change Camera ${change.cameraNumber} is not present in the uploaded group`)
  }
  const changedQuality = await workflow.recordViewQuality(
    changedView.clipAssetId,
    change.viewQuality,
    qualityReasonCode(change.viewQuality),
    0,
  )
  assertEqual(field(changedQuality, 'decision'), change.viewQuality, 'changed human View Quality')
  const intermediateGroupQuality = await workflow.recordGroupQuality(
    summary.capture_group_id,
    change.intermediateGroupQuality,
    `Synthetic acceptance input change: ${fixture.name}`,
    1,
  )
  assertEqual(field(intermediateGroupQuality, 'result'), change.intermediateGroupQuality, 'input-change intermediate Group Quality')
  const finalGroupQuality = await workflow.recordGroupQuality(
    summary.capture_group_id,
    change.finalGroupQuality,
    `Synthetic acceptance requalification: ${fixture.name}`,
    2,
  )
  assertEqual(field(finalGroupQuality, 'result'), change.finalGroupQuality, 'input-change final Group Quality')

  const adminTasks = groupTasks(await workflow.listAnnotationTasks('admin'), summary.capture_group_id)
  assertEqual(adminTasks.length, 1, 'input-change AnnotationTask count')
  assertEqual(annotationTaskId(adminTasks[0]), annotationTaskId(task), 'input-change AnnotationTask identity')
  assertEqual(field(adminTasks[0], 'status'), 'ASSIGNED', 'input-change task status')
  const memberTasks = groupTasks(await workflow.listAnnotationTasks('member'), summary.capture_group_id)
  assertEqual(memberTasks.length, 1, 'input-change member AnnotationTask count')
  assertTaskHasStableAssignee(memberTasks[0], adminTasks[0])
  if (field(adminTasks[0], 'annotation_revision', 'annotationRevision') !== undefined
    && field(adminTasks[0], 'annotation_revision', 'annotationRevision') !== null) {
    throw new Error('Input-change rework task unexpectedly exposed a stale current Annotation Revision')
  }

  const changedFixture = {
    ...fixture,
    groupQuality: change.finalGroupQuality,
    qualityByCamera: { ...fixture.qualityByCamera, [change.cameraNumber]: change.viewQuality },
  }
  summary.views = summary.views.map((view) => view.camera_number === change.cameraNumber
    ? { ...view, quality: change.viewQuality }
    : view)
  return { fixture: changedFixture, groupQuality: finalGroupQuality }
}

async function verifyReviewAndCleaning(workflow, fixture, uploaded, task, summary, timeoutMs) {
  const primaryView = uploaded.views.find((view) => view.cameraNumber === fixture.primaryCameraNumber)
  if (!primaryView) {
    throw new Error(`Primary Camera ${fixture.primaryCameraNumber} is not available in this scenario`)
  }
  const selectedTask = await workflow.selectPrimaryView(annotationTaskId(task), primaryView.clipAssetId)
  assertEqual(field(selectedTask, 'primary_clip_asset_id', 'primaryClipAssetId'), primaryView.clipAssetId, 'Primary View')
  assertEqual(field(selectedTask, 'primary_camera_number', 'primaryCameraNumber'), fixture.primaryCameraNumber, 'Primary camera number')

  let submittedRevision = null
  let finalReview = null
  let activeFixture = fixture
  for (let index = 0; index < fixture.reviewSequence.length; index += 1) {
    if (index > 0 && fixture.inputChangeAfterReview && index === 1) {
      const changedInput = await verifyGroupInputChange(workflow, fixture, uploaded, task, summary)
      activeFixture = changedInput.fixture
      summary.group_quality = summarizeGroupQuality(changedInput.groupQuality)
    }
    submittedRevision = await workflow.submitRevision(annotationTaskId(task), fixture.actionSegments)
    const number = revisionNumber(submittedRevision)
    assertEqual(number, index + 1, 'append-only Annotation Revision number')
    finalReview = await workflow.reviewRevision(
      annotationRevisionId(submittedRevision),
      fixture.reviewSequence[index],
      fixture.reviewSequence[index] === 'APPROVED' ? 'Synthetic acceptance approved' : 'Synthetic acceptance requires rework',
    )
    assertEqual(field(finalReview, 'decision'), fixture.reviewSequence[index], 'human Review decision')
    assertReviewContext(finalReview, activeFixture, number)
    if (fixture.reviewSequence[index] === 'REJECTED') {
      const reworkTasks = groupTasks(await workflow.listAnnotationTasks('admin'), field(task, 'capture_group_id', 'captureGroupId'))
      assertEqual(reworkTasks.length, 1, 'rework AnnotationTask count')
      assertEqual(annotationTaskId(reworkTasks[0]), annotationTaskId(task), 'rework AnnotationTask identity')
      assertTaskHasStableAssignee(
        groupTasks(await workflow.listAnnotationTasks('member'), field(task, 'capture_group_id', 'captureGroupId'))[0],
        reworkTasks[0],
      )
    }
  }

  if (!submittedRevision || !finalReview) {
    throw new Error('Scenario did not submit and review an Annotation Revision')
  }
  summary.revision = revisionNumber(submittedRevision)
  summary.review = summarizeReview(finalReview)

  if (fixture.reviewSequence.at(-1) !== 'APPROVED') {
    try {
      await workflow.recheckCaptureGroupCleaning(annotationRevisionId(submittedRevision))
      throw new Error('A REJECTED Annotation Review unexpectedly produced a Cleaning result')
    }
    catch (error) {
      if (error instanceof Error && error.message.includes('unexpectedly produced')) {
        throw error
      }
      assertApiError(error, 409, 'ANNOTATION_GROUP_CLEANING_RECHECK_NOT_READY', 'rejected Review Cleaning gate')
    }
    const download = await workflow.downloadTrainingDataV3()
    if (download.document.events?.some((event) => event.capture_group_id === summary.capture_group_id
      && event.annotation_revision_id === annotationRevisionId(submittedRevision))) {
      throw new Error('A non-PASS synthetic revision appeared in training-data.v3')
    }
    summary.export = { schema_version: download.document.schema_version, exported: false }
    return
  }

  if (fixture.expectedFinalRevision && summary.revision !== fixture.expectedFinalRevision) {
    throw new Error(`Final Annotation Revision mismatch: expected ${fixture.expectedFinalRevision}, received ${summary.revision}`)
  }
  const cleaning = await workflow.recheckCaptureGroupCleaning(annotationRevisionId(submittedRevision))
  assertCleaningResult(cleaning, activeFixture)
  summary.cleaning = summarizeCleaning(cleaning)

  const preview = await workflow.previewTrainingDataV3()
  assertEqual(field(preview, 'schema_version', 'schemaVersion'), 'training-data.v3', 'training-data.v3 preview schema')
  assertEqual(field(preview, 'split_unit', 'splitUnit'), 'capture_group', 'training-data.v3 split unit')
  if (field(preview, 'event_count', 'eventCount') < 1) {
    throw new Error('training-data.v3 preview omitted the approved synthetic event')
  }
  const download = await workflow.downloadTrainingDataV3()
  if (!download.contentType?.startsWith('application/vnd.medication.training-data.v3+json')) {
    throw new Error(`Unexpected training-data.v3 content type: ${download.contentType}`)
  }
  const event = validateTrainingDataV3Document(download.document, {
    captureGroupId: summary.capture_group_id,
    annotationRevisionId: annotationRevisionId(submittedRevision),
    revisionNumber: summary.revision,
    primaryCameraNumber: fixture.primaryCameraNumber,
    primaryClipAssetId: uploaded.views.find((view) => view.cameraNumber === fixture.primaryCameraNumber).clipAssetId,
  })
  summary.export = {
    schema_version: download.document.schema_version,
    file_name: download.fileName,
    event_revision: event.revision,
    readback: true,
  }

  const leakingAssignments = [
    { capture_group_id: summary.capture_group_id, split: 'train' },
    { capture_group_id: summary.capture_group_id, split: 'validation' },
  ]
  try {
    assertNoSplitLeakage(leakingAssignments)
    throw new Error('The local split guard accepted a CaptureGroup assigned to two splits')
  }
  catch (error) {
    if (error instanceof Error && error.message.includes('local split guard accepted')) {
      throw error
    }
    assertEqual(error.errorCode, 'TRAINING_DATA_SPLIT_LEAKAGE', 'local split guard error code')
  }
  try {
    await workflow.validateTrainingDataV3Splits(leakingAssignments)
    throw new Error('The API split guard accepted a CaptureGroup assigned to two splits')
  }
  catch (error) {
    if (error instanceof Error && error.message.includes('API split guard accepted')) {
      throw error
    }
    assertApiError(error, 422, 'TRAINING_DATA_SPLIT_LEAKAGE', 'API split leakage guard')
  }
  const validSplit = await workflow.validateTrainingDataV3Splits([
    { capture_group_id: summary.capture_group_id, split: 'train' },
  ])
  assertEqual(field(validSplit, 'split_unit', 'splitUnit'), 'capture_group', 'valid split unit')
  assertEqual(field(validSplit, 'valid'), true, 'valid split assignment')
}

export async function executeScenario({
  fixture,
  projectId,
  adminUploadClient,
  uploadClient,
  workflow,
  workDirectory,
  videoGenerator = generateSyntheticVideo,
  timeoutMs = DEFAULT_TIMEOUT_MS,
}) {
  const expected = normalizeCameraNumbers(fixture.expectedCameraNumbers)
  const present = normalizeCameraNumbers(fixture.presentCameraNumbers)
  assertSameCameraNumbers(expected, fixture.expectedCameraNumbers, 'scenario expected')
  assertSameCameraNumbers(present, fixture.presentCameraNumbers, 'scenario present')
  const cameraSet = await adminUploadClient.provisionSyntheticCameraSet({
    cameraNumbers: expected,
    cameraSetKey: `synthetic-acceptance-camera-set-n${expected.length}-${fixture.name}-${fixture.sourceBatchId.slice(-24)}`,
    layoutVersion: 'synthetic-capture-group-v1',
  })
  assertSameCameraNumbers(cameraSet.channels.map((channel) => channel.cameraNumber), expected, 'provisioned')
  const videoBytesByCamera = await makeGeneratedVideos(fixture, workDirectory, videoGenerator)
  const uploaded = await uploadClient.uploadSyntheticCaptureGroup({
    cameraSet,
    captureGroupKey: fixture.captureGroupKey,
    expectedCameraNumbers: expected,
    presentCameraNumbers: present,
    videoBytesByCamera,
    capturedAt: fixture.capturedAt,
    deviceId: fixture.deviceId,
    sourceBatchId: fixture.sourceBatchId,
  })
  const captureGroupId = uploaded.captureGroupId
  const summary = baseSummary(fixture, projectId, captureGroupId)
  const beforeFinalize = await workflow.getCaptureGroup(captureGroupId)
  assertEqual(receiptState(beforeFinalize), 'RECEIVING', 'pre-finalize receipt state')
  assertCaptureGroupIdentity(beforeFinalize, fixture)
  if (!fixture.finalizeReceipt) {
    summary.receipt_state = receiptState(beforeFinalize)
    if (fixture.verifyReceivingQualityGate) {
      await verifyMediaPath(workflow, fixture, uploaded, summary, timeoutMs)
      const qualities = {}
      for (const view of uploaded.views) {
        const decision = fixture.qualityByCamera[view.cameraNumber]
        const quality = await workflow.recordViewQuality(view.clipAssetId, decision, qualityReasonCode(decision), 0)
        assertEqual(field(quality, 'decision'), decision, `human View Quality Camera ${view.cameraNumber}`)
        qualities[view.cameraNumber] = quality
      }
      summary.views = uploaded.views.map((view) => safeViewSummary(view, qualities[view.cameraNumber]))
      try {
        await workflow.recordGroupQuality(captureGroupId, fixture.groupQuality, `Synthetic acceptance: ${fixture.name}`, 0)
        throw new Error('A RECEIVING synthetic group was unexpectedly admitted')
      }
      catch (error) {
        if (error instanceof Error && error.message.includes('unexpectedly admitted')) {
          throw error
        }
        assertApiError(error, 409, fixture.expectedGroupQualityError, 'RECEIVING group-quality gate')
      }
      summary.group_quality = { result: 'NOT_ADMITTED', error_code: fixture.expectedGroupQualityError }
    }
    else {
      summary.views = uploaded.views.map((view) => ({ camera_number: view.cameraNumber, clip_asset_id: view.clipAssetId }))
    }
    return summary
  }

  const finalized = await workflow.finalizeCaptureGroup(captureGroupId)
  assertReceipt(finalized, fixture)
  summary.receipt_state = receiptState(finalized)
  await verifyMediaPath(workflow, fixture, uploaded, summary, timeoutMs)
  if (fixture.stopAfter === 'media') {
    const afterProbe = await workflow.getCaptureGroup(captureGroupId)
    assertEqual(receiptState(afterProbe), fixture.receiptAfterFinalize, 'receipt after Media Probe failure')
    summary.views = uploaded.views.map((view) => ({
      camera_number: view.cameraNumber,
      clip_asset_id: view.clipAssetId,
      media_status: field(afterProbe.streams?.find((stream) => field(stream, 'camera_number') === view.cameraNumber), 'stream_status', 'streamStatus'),
    }))
    return summary
  }
  await verifyCandidatePath(workflow, fixture, uploaded, summary, timeoutMs)
  const qualities = {}
  for (const view of uploaded.views) {
    const decision = fixture.qualityByCamera[view.cameraNumber]
    const quality = await workflow.recordViewQuality(view.clipAssetId, decision, qualityReasonCode(decision), 0)
    assertEqual(field(quality, 'decision'), decision, `human View Quality Camera ${view.cameraNumber}`)
    qualities[view.cameraNumber] = quality
  }
  summary.views = uploaded.views.map((view) => safeViewSummary(view, qualities[view.cameraNumber]))

  if (fixture.expectedGroupQualityError) {
    try {
      await workflow.recordGroupQuality(captureGroupId, fixture.groupQuality, `Synthetic acceptance: ${fixture.name}`, 0)
      throw new Error('A zero-qualified synthetic group was unexpectedly admitted')
    }
    catch (error) {
      if (error instanceof Error && error.message.includes('unexpectedly admitted')) {
        throw error
      }
      assertApiError(error, 409, fixture.expectedGroupQualityError, 'zero-qualified group gate')
    }
    summary.group_quality = { result: 'NOT_ADMITTED', error_code: fixture.expectedGroupQualityError }
    return summary
  }

  const task = await verifyGroupQualityAndTasks(workflow, fixture, captureGroupId, summary)
  if (!task) {
    return summary
  }
  await verifyReviewAndCleaning(workflow, fixture, uploaded, task, summary, timeoutMs)
  return summary
}

export function parseArgs(argv) {
  let scenario = 'n5-missing-camera'
  let help = false
  let list = false
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--help' || argument === '-h') {
      help = true
      continue
    }
    if (argument === '--list') {
      list = true
      continue
    }
    if (argument === '--scenario') {
      const value = argv[index + 1]
      if (!value || value.startsWith('-')) {
        throw new TypeError('--scenario requires a scenario name')
      }
      getScenario(value)
      scenario = value
      index += 1
      continue
    }
    throw new TypeError(`Unsupported argument ${argument}; synthetic media and tokens are environment-only`)
  }
  return { scenario, help, list }
}

function printUsage() {
  console.log('Usage: pnpm run synthetic-capture-group -- --scenario <name>')
  console.log('       pnpm run synthetic-capture-group -- --list')
  console.log(`Scenarios: ${SCENARIO_NAMES.join(', ')}`)
  console.log('Set RUN_SYNTHETIC_CAPTURE_GROUP_E2E=true to enable service calls.')
}

function printSummary(summary) {
  console.log(JSON.stringify(summary, null, 2))
}

export async function main(argv = process.argv.slice(2), environment = process.env) {
  const options = parseArgs(argv)
  if (options.help || options.list) {
    printUsage()
    return 0
  }
  if (environment.RUN_SYNTHETIC_CAPTURE_GROUP_E2E !== 'true') {
    throw new Error('Refusing service E2E: set RUN_SYNTHETIC_CAPTURE_GROUP_E2E=true explicitly')
  }
  const bearerToken = environment.DEV_BEARER_TOKEN
  const adminBearerToken = environment.DEV_ADMIN_BEARER_TOKEN
  if (!bearerToken || !adminBearerToken) {
    throw new Error('DEV_BEARER_TOKEN and DEV_ADMIN_BEARER_TOKEN are required through the environment')
  }
  const projectId = environment.DEV_PROJECT_ID || DEFAULT_PROJECT_ID
  const baseUrl = environment.PLATFORM_BASE_URL || DEFAULT_BASE_URL
  const timeoutMs = Number(environment.SYNTHETIC_E2E_TIMEOUT_MS || DEFAULT_TIMEOUT_MS)
  const fixture = buildScenarioFixture(options.scenario, `run-${Date.now()}`)
  const workDirectory = await mkdtemp(join(tmpdir(), 'medication-synthetic-capture-group-'))
  try {
    const adminUploadClient = new SyntheticUploadClient({
      baseUrl,
      projectId,
      bearerToken: adminBearerToken,
    })
    const uploadClient = new SyntheticUploadClient({
      baseUrl,
      projectId,
      bearerToken,
    })
    const workflow = new SyntheticCaptureGroupWorkflowClient({
      baseUrl,
      projectId,
      bearerToken,
      adminBearerToken,
    })
    const summary = await executeScenario({
      fixture,
      projectId,
      adminUploadClient,
      uploadClient,
      workflow,
      workDirectory,
      timeoutMs,
    })
    printSummary(summary)
    return 0
  }
  finally {
    await rm(workDirectory, { recursive: true, force: true })
  }
}

const thisFile = fileURLToPath(import.meta.url)
const invokedFile = process.argv[1] ? resolve(process.argv[1]) : null
if (invokedFile && resolve(thisFile) === invokedFile) {
  main().catch((error) => {
    if (error instanceof PlatformApiError) {
      console.error(`${error.message}${error.requestId ? `; request_id=${error.requestId}` : ''}`)
    }
    else {
      console.error(error instanceof Error ? error.message : String(error))
    }
    process.exitCode = 1
  })
}
