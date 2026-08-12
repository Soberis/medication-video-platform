import type {
  VideoSummary,
  VideoSummaryAnnotationStatus,
  VideoSummaryCleaningStatus,
  VideoSummaryFilters,
  VideoSummaryHandResult,
  VideoSummaryHandStatus,
  VideoSummaryMediaStatus,
  VideoSummarySource,
} from '@/api/catalog'

export const videoFilterSearchParamNames = [
  'source',
  'cameraNumber',
  'capturedFrom',
  'capturedBefore',
  'mediaStatus',
  'handStatus',
  'handResult',
  'annotationStatus',
  'cleaningStatus',
] as const

const sourceValues = [
  'HOSPITAL_REMOTE',
  'PLATFORM_LOCAL',
] as const satisfies readonly VideoSummarySource[]
const mediaStatusValues = [
  'UPLOADED',
  'INGESTING',
  'INGESTED',
  'INGEST_FAILED',
] as const satisfies readonly VideoSummaryMediaStatus[]
const handStatusValues = [
  'NOT_REQUESTED',
  'QUEUED',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
] as const satisfies readonly VideoSummaryHandStatus[]
const handResultValues = [
  'UNKNOWN',
  'HAND_DETECTED',
  'NO_HAND_DETECTED',
] as const satisfies readonly VideoSummaryHandResult[]
const annotationStatusValues = [
  'NOT_STARTED',
  'ASSIGNED',
  'SUBMITTED',
  'COMPLETED',
] as const satisfies readonly VideoSummaryAnnotationStatus[]
const cleaningStatusValues = [
  'NOT_STARTED',
  'PASS',
  'WARNING',
  'REJECT',
] as const satisfies readonly VideoSummaryCleaningStatus[]

export function parseVideoFilters(searchParams: URLSearchParams): VideoSummaryFilters {
  const filters: VideoSummaryFilters = {}
  const source = parseEnum(searchParams.get('source'), sourceValues)
  const mediaStatus = parseEnum(searchParams.get('mediaStatus'), mediaStatusValues)
  const handStatus = parseEnum(searchParams.get('handStatus'), handStatusValues)
  const handResult = parseEnum(searchParams.get('handResult'), handResultValues)
  const annotationStatus = parseEnum(searchParams.get('annotationStatus'), annotationStatusValues)
  const cleaningStatus = parseEnum(searchParams.get('cleaningStatus'), cleaningStatusValues)
  const cameraNumber = parsePositiveInteger(searchParams.get('cameraNumber'))
  const capturedFrom = parseIsoInstant(searchParams.get('capturedFrom'))
  const capturedBefore = parseIsoInstant(searchParams.get('capturedBefore'))

  if (source !== undefined) filters.source = source
  if (mediaStatus !== undefined) filters.mediaStatus = mediaStatus
  if (handStatus !== undefined) filters.handStatus = handStatus
  if (handResult !== undefined) filters.handResult = handResult
  if (annotationStatus !== undefined) filters.annotationStatus = annotationStatus
  if (cleaningStatus !== undefined) filters.cleaningStatus = cleaningStatus
  if (cameraNumber !== undefined) filters.cameraNumber = cameraNumber
  if (capturedFrom !== undefined) filters.capturedFrom = capturedFrom
  if (capturedBefore !== undefined) filters.capturedBefore = capturedBefore

  return filters
}

export function serializeVideoFilters(filters: VideoSummaryFilters): URLSearchParams {
  const searchParams = new URLSearchParams()
  setEnum(searchParams, 'source', filters.source, sourceValues)
  setEnum(searchParams, 'mediaStatus', filters.mediaStatus, mediaStatusValues)
  setEnum(searchParams, 'handStatus', filters.handStatus, handStatusValues)
  setEnum(searchParams, 'handResult', filters.handResult, handResultValues)
  setEnum(searchParams, 'annotationStatus', filters.annotationStatus, annotationStatusValues)
  setEnum(searchParams, 'cleaningStatus', filters.cleaningStatus, cleaningStatusValues)

  const cameraNumber = parsePositiveInteger(
    filters.cameraNumber === undefined ? null : String(filters.cameraNumber),
  )
  const capturedFrom = parseIsoInstant(filters.capturedFrom ?? null)
  const capturedBefore = parseIsoInstant(filters.capturedBefore ?? null)
  if (cameraNumber !== undefined) searchParams.set('cameraNumber', String(cameraNumber))
  if (capturedFrom !== undefined) searchParams.set('capturedFrom', capturedFrom)
  if (capturedBefore !== undefined) searchParams.set('capturedBefore', capturedBefore)

  return searchParams
}

export function replaceVideoFiltersInSearchParams(
  currentSearchParams: URLSearchParams,
  filters: VideoSummaryFilters,
): URLSearchParams {
  const nextSearchParams = new URLSearchParams(currentSearchParams)
  for (const name of videoFilterSearchParamNames) nextSearchParams.delete(name)
  serializeVideoFilters(filters).forEach((value, name) => nextSearchParams.set(name, value))
  return nextSearchParams
}

function parseEnum<T extends string>(value: string | null, values: readonly T[]) {
  return value !== null && (values as readonly string[]).includes(value) ? (value as T) : undefined
}

function setEnum<T extends string>(
  searchParams: URLSearchParams,
  name: string,
  value: T | undefined,
  values: readonly T[],
) {
  if (value !== undefined && (values as readonly string[]).includes(value)) {
    searchParams.set(name, value)
  }
}

function parsePositiveInteger(value: string | null) {
  if (value === null || !/^[1-9]\d*$/.test(value)) return undefined
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : undefined
}

function parseIsoInstant(value: string | null) {
  if (value === null) return undefined
  const normalized = value.trim()
  const match =
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|([+-])(\d{2}):(\d{2}))$/.exec(
      normalized,
    )
  if (!match) return undefined

  const [
    ,
    yearText,
    monthText,
    dayText,
    hourText,
    minuteText,
    secondText,
    ,
    ,
    ,
    offsetHourText,
    offsetMinuteText,
  ] = match
  const year = Number(yearText)
  const month = Number(monthText)
  const day = Number(dayText)
  const hour = Number(hourText)
  const minute = Number(minuteText)
  const second = Number(secondText)
  if (
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > daysInMonth(year, month) ||
    hour > 23 ||
    minute > 59 ||
    second > 59
  ) {
    return undefined
  }
  if (
    offsetHourText !== undefined &&
    (Number(offsetHourText) > 23 || Number(offsetMinuteText) > 59)
  ) {
    return undefined
  }
  return Number.isFinite(Date.parse(normalized)) ? normalized : undefined
}

function daysInMonth(year: number, month: number) {
  if (month === 2) {
    const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
    return leap ? 29 : 28
  }
  return [4, 6, 9, 11].includes(month) ? 30 : 31
}

export type VideoFilterSearchParam = (typeof videoFilterSearchParamNames)[number]

// Keep this type import in the helper's public module so type-only consumers can discover the
// supported URL state without coupling to the API implementation details.
export type VideoFilterVideoSummary = VideoSummary
