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
