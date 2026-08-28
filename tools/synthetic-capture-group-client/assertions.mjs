function field(value, snakeCaseName, camelCaseName = snakeCaseName) {
  return value?.[snakeCaseName] ?? value?.[camelCaseName]
}

function cameraNumber(view) {
  return field(view, 'camera_number', 'cameraNumber')
}

function asSortedNumbers(values) {
  return [...values].sort((left, right) => left - right)
}

function sameNumbers(actual, expected) {
  return JSON.stringify(asSortedNumbers(actual)) === JSON.stringify(asSortedNumbers(expected))
}

export function assertSameCameraNumbers(actual, expected, label) {
  if (!Array.isArray(actual) || !sameNumbers(actual, expected)) {
    throw new Error(`${label} camera numbers differ: expected ${expected.join(',')}, received ${actual?.join?.(',')}`)
  }
}

export function assertCaptureGroupIdentity(group, fixture) {
  const groupKey = field(group, 'capture_group_key', 'captureGroupKey')
  if (groupKey !== fixture.captureGroupKey) {
    throw new Error(`CaptureGroup key mismatch: expected ${fixture.captureGroupKey}, received ${groupKey}`)
  }
  assertSameCameraNumbers(
    field(group, 'expected_camera_numbers', 'expectedCameraNumbers'),
    fixture.expectedCameraNumbers,
    'expected',
  )
  assertSameCameraNumbers(
    field(group, 'received_camera_numbers', 'receivedCameraNumbers'),
    fixture.presentCameraNumbers,
    'received',
  )
  assertSameCameraNumbers(
    field(group, 'missing_camera_numbers', 'missingCameraNumbers'),
    fixture.missingCameraNumbers,
    'missing',
  )
  const streams = field(group, 'streams')
  if (!Array.isArray(streams)) {
    throw new Error('CaptureGroup detail did not expose streams')
  }
  assertSameCameraNumbers(streams.map(cameraNumber), fixture.presentCameraNumbers, 'stream')
  const streamNumbers = streams.map(cameraNumber)
  if (new Set(streamNumbers).size !== streamNumbers.length) {
    throw new Error('CaptureGroup detail contains duplicate camera numbers')
  }
}

export function assertReviewContext(review, fixture, revisionNumber) {
  if (field(review, 'capture_group_id', 'captureGroupId') === undefined) {
    throw new Error('Review response did not expose capture_group_id')
  }
  if (field(review, 'revision') !== revisionNumber) {
    throw new Error(`Review revision mismatch: expected ${revisionNumber}`)
  }
  if (!field(review, 'primary_clip_asset_id', 'primaryClipAssetId')) {
    throw new Error('Review response did not expose the Primary View')
  }
  if (!field(review, 'confirmed_view_set', 'confirmedViewSet')) {
    throw new Error('Review response did not expose the confirmed View Set')
  }
  if (!Array.isArray(field(review, 'qualified_views', 'qualifiedViews'))) {
    throw new Error('Review response did not expose qualified views')
  }
  if (!Array.isArray(field(review, 'rejected_missing_views', 'rejectedMissingViews'))) {
    throw new Error('Review response did not expose rejected/missing views')
  }
  if (!Array.isArray(field(review, 'candidate_evidence', 'candidateEvidence'))) {
    throw new Error('Review response did not expose candidate evidence')
  }
  const qualifiedNumbers = field(review, 'qualified_views', 'qualifiedViews').map(cameraNumber)
  const expectedQualified = Object.entries(fixture.qualityByCamera)
    .filter(([, decision]) => decision === 'QUALIFIED')
    .map(([number]) => Number(number))
  assertSameCameraNumbers(qualifiedNumbers, expectedQualified, 'qualified view')
}

export function assertCleaningResult(cleaning, fixture) {
  const outcome = field(cleaning, 'outcome')
  if (outcome !== fixture.cleaningOutcome) {
    throw new Error(`Cleaning outcome mismatch: expected ${fixture.cleaningOutcome}, received ${outcome}`)
  }
  if (field(cleaning, 'annotation_review_decision', 'annotationReviewDecision') !== 'APPROVED') {
    throw new Error('Cleaning did not record an APPROVED human Review')
  }
  if (!Array.isArray(field(cleaning, 'primary_interval', 'primaryInterval'))
    || field(cleaning, 'primary_interval', 'primaryInterval').length !== fixture.actionSegments.length) {
    throw new Error('Cleaning did not preserve the Primary View formal interval')
  }
  const primaryInterval = field(cleaning, 'primary_interval', 'primaryInterval')
  fixture.actionSegments.forEach((segment, index) => {
    if (primaryInterval[index]?.start_ms !== segment.startMs || primaryInterval[index]?.end_ms !== segment.endMs
      || primaryInterval[index]?.label !== segment.label) {
      throw new Error('Cleaning Primary View interval differs from the submitted annotation')
    }
  })
  const participating = field(cleaning, 'participating_views', 'participatingViews')
  const excluded = field(cleaning, 'excluded_views', 'excludedViews')
  if (!Array.isArray(participating) || !Array.isArray(excluded)) {
    throw new Error('Cleaning did not expose participating and excluded views')
  }
  const expectedParticipating = Object.entries(fixture.qualityByCamera)
    .filter(([, decision]) => decision === 'QUALIFIED')
    .map(([number]) => Number(number))
  const expectedExcluded = fixture.missingCameraNumbers.concat(
    Object.entries(fixture.qualityByCamera)
      .filter(([, decision]) => decision !== 'QUALIFIED')
      .map(([number]) => Number(number)),
  )
  assertSameCameraNumbers(participating.map(cameraNumber), expectedParticipating, 'participating')
  assertSameCameraNumbers(excluded.map(cameraNumber), expectedExcluded, 'excluded')
  for (const view of [...participating, ...excluded]) {
    const number = cameraNumber(view)
    if (number === fixture.primaryCameraNumber) {
      continue
    }
    if (field(view, 'action_aligned_interval', 'actionAlignedInterval') !== false
      || field(view, 'interval_source', 'intervalSource') !== 'EVENT_LEVEL_EVIDENCE') {
      throw new Error(`Supporting Camera ${number} was not marked event-level evidence`)
    }
    if (field(view, 'interval') !== undefined && field(view, 'interval') !== null
      || field(view, 'action_interval', 'actionInterval') !== undefined
      || field(view, 'action_start_ms', 'actionStartMs') !== undefined
      || field(view, 'action_end_ms', 'actionEndMs') !== undefined) {
      throw new Error(`Supporting Camera ${number} contains a fabricated action-aligned interval`)
    }
  }
  for (const view of excluded) {
    if (field(view, 'retained') !== true || field(view, 'downstream_included', 'downstreamIncluded') !== false) {
      throw new Error(`Excluded Camera ${cameraNumber(view)} was not retained/excluded explicitly`)
    }
  }
  const mappingProvenance = field(cleaning, 'mapping_provenance', 'mappingProvenance')
  if (!Array.isArray(mappingProvenance) || mappingProvenance.length !== 0) {
    throw new Error('Synthetic V1.25 Cleaning unexpectedly introduced a supporting time mapping')
  }
}

export function validateTrainingDataV3Document(document, expected) {
  if (document?.schema_version !== 'training-data.v3' || document?.split_unit !== 'capture_group') {
    throw new Error('Training-data readback is not the capture_group-scoped v3 contract')
  }
  if (!Array.isArray(document.events)) {
    throw new Error('Training-data v3 readback did not contain events')
  }
  const event = document.events.find((candidate) => candidate.capture_group_id === expected.captureGroupId
    && candidate.annotation_revision_id === expected.annotationRevisionId)
  if (!event) {
    throw new Error('Training-data v3 readback did not contain the eligible synthetic event')
  }
  if (event.revision !== expected.revisionNumber || event.primary_camera_number !== expected.primaryCameraNumber) {
    throw new Error('Training-data v3 revision or primary camera identity is incorrect')
  }
  if (event.primary_clip_asset_id !== expected.primaryClipAssetId
    || !event.confirmed_view_set
    || !event.input_version
    || !event.group_quality_review_id
    || !event.annotation_review_id
    || !event.capture_group_cleaning_id
    || !event.cleaning_rule_set_key
    || !event.cleaning_rule_set_version
    || !event.cleaning_rule_set_digest
    || !event.cleaning_input_fingerprint
    || !event.cleaning_evaluated_by
    || !event.cleaning_evaluated_at
    || !Array.isArray(event.candidate_provenance)) {
    throw new Error('Training-data v3 omitted required review, cleaning or candidate provenance')
  }
  if (event.cleaning_outcome !== 'PASS' || event.review_decision !== 'APPROVED') {
    throw new Error('Training-data v3 exported a non-approved/non-PASS event')
  }
  if (!Array.isArray(event.primary_interval) || event.primary_interval.length === 0) {
    throw new Error('Training-data v3 omitted the authoritative primary interval')
  }
  for (const view of [...(event.participating_views ?? []), ...(event.excluded_views ?? [])]) {
    if (view.camera_number === expected.primaryCameraNumber) {
      continue
    }
    if (view.interval !== undefined && view.interval !== null
      || view.action_interval !== undefined
      || view.action_start_ms !== undefined
      || view.action_end_ms !== undefined
      || view.mapping_provenance !== undefined) {
      throw new Error(`Training-data v3 fabricated a supporting interval for Camera ${view.camera_number}`)
    }
    if (view.interval_source !== 'EVENT_LEVEL_EVIDENCE' || view.action_aligned_interval !== false) {
      throw new Error(`Training-data v3 lost supporting evidence time-safety for Camera ${view.camera_number}`)
    }
  }
  return event
}

export function assertNoSplitLeakage(assignments) {
  const splitByGroup = new Map()
  for (const assignment of assignments) {
    const groupId = assignment.capture_group_id ?? assignment.captureGroupId
    const split = assignment.split
    if (!groupId || !split) {
      throw new TypeError('A split assignment requires capture_group_id and split')
    }
    const previous = splitByGroup.get(groupId)
    if (previous && previous !== split) {
      const error = new Error(`CaptureGroup ${groupId} is assigned to both ${previous} and ${split}`)
      error.errorCode = 'TRAINING_DATA_SPLIT_LEAKAGE'
      throw error
    }
    splitByGroup.set(groupId, split)
  }
  return { splitUnit: 'capture_group', assignmentCount: assignments.length, valid: true }
}
