export type CaptureGroupFocus = 'annotation' | 'review' | 'cleaning'

export const paths = {
  login: '/login',
  projects: '/projects',
  videos: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/videos`,
  captureGroups: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/capture-groups`,
  captureGroup: (projectId: string, captureGroupId: string) =>
    `/projects/${encodeURIComponent(projectId)}/capture-groups/${encodeURIComponent(captureGroupId)}`,
  asset: (projectId: string, assetId: string) =>
    `/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}`,
  annotationTasks: (projectId: string) =>
    `/projects/${encodeURIComponent(projectId)}/annotation-tasks`,
  annotationTask: (projectId: string, taskId: string) =>
    `/projects/${encodeURIComponent(projectId)}/annotation-tasks/${encodeURIComponent(taskId)}`,
  captureGroupFocus,
  memberships: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/members`,
  accounts: '/admin/users',
  uploads: '/uploads',
  localUpload: '/uploads/new',
} as const

export function captureGroupFocus(
  projectId: string,
  captureGroupId: string,
  focus: CaptureGroupFocus,
) {
  return `${paths.captureGroup(projectId, captureGroupId)}?focus=${focus}`
}
