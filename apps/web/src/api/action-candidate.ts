import { csrfHeaders } from '@/api/http'

export interface ActionCandidate {
  action_candidate_id: string
  ai_job_id: string
  project_id: string
  capture_group_id: string
  clip_asset_id: string
  start_ms: number
  end_ms: number
  action_label: string
  confidence: number
  source_window: number
  stitched_windows: number[]
  created_at: string
}

export interface ActionCandidateJob {
  ai_job_id: string
  project_id: string
  capture_group_id: string
  clip_asset_id: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  attempt: number
  created_at: string
  updated_at: string
}

export async function fetchActionCandidates(
  projectId: string,
  captureGroupId: string,
): Promise<ActionCandidate[]> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-groups/${encodeURIComponent(captureGroupId)}/action-candidates`,
    {
      method: 'GET',
      headers: {
        Accept: 'application/json',
        ...csrfHeaders(),
      },
    },
  )
  if (!response.ok) {
    if (response.status === 404) return []
    throw new Error(`Failed to fetch action candidates: ${response.status}`)
  }
  return (await response.json()) as ActionCandidate[]
}

export async function fetchActionCandidateJob(
  projectId: string,
  captureGroupId: string,
): Promise<ActionCandidateJob | null> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-groups/${encodeURIComponent(captureGroupId)}/action-candidate-job`,
    {
      method: 'GET',
      headers: {
        Accept: 'application/json',
        ...csrfHeaders(),
      },
    },
  )
  if (!response.ok) {
    if (response.status === 204 || response.status === 404) return null
    throw new Error(`Failed to fetch action candidate job: ${response.status}`)
  }
  if (response.status === 204) return null
  return (await response.json()) as ActionCandidateJob
}

export async function dispatchActionCandidateJob(
  projectId: string,
  captureGroupId: string,
): Promise<ActionCandidateJob> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-groups/${encodeURIComponent(captureGroupId)}/action-candidate-jobs`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        ...csrfHeaders(),
      },
    },
  )
  if (!response.ok) {
    throw new Error(`Failed to dispatch action candidate job: ${response.status}`)
  }
  return (await response.json()) as ActionCandidateJob
}
