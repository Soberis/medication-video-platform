export type CaptureGroupFocus = 'annotation' | 'review' | 'cleaning'

export const paths = {
  login: '/login',
  projects: '/projects',
  captureGroups: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/capture-groups`,
  captureGroup: (projectId: string, captureGroupId: string) =>
    `/projects/${encodeURIComponent(projectId)}/capture-groups/${encodeURIComponent(captureGroupId)}`,
  annotationTasks: (projectId: string) =>
    `/projects/${encodeURIComponent(projectId)}/annotation-tasks`,
  captureGroupFocus,
  memberships: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/members`,
  annotationLabelLibrary: () => '/settings/annotation-label-library',
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
