/// <reference types="vite/client" />

declare module '../../tools/clean-invalid-videos.mjs' {
  export function deleteCaptureGroup(sessionIdOrKey: string): string
  export function deleteCaptureGroupsBatch(sessionIds: string[]): number
  export function getCaptureGroupsPipelineStatus(projectId?: string): { stats: unknown; items: unknown }
  export function cleanInvalidCaptureGroups(options?: { ids?: string[]; projectId?: string }): { ok: boolean; deletedCount: number }
  export function listFailedCaptureGroups(): unknown[]
}


