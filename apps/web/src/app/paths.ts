export const paths = {
  login: '/login',
  projects: '/projects',
  videos: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/videos`,
  asset: (projectId: string, assetId: string) =>
    `/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}`,
  annotationTasks: (projectId: string) =>
    `/projects/${encodeURIComponent(projectId)}/annotation-tasks`,
  annotationTask: (projectId: string, taskId: string) =>
    `/projects/${encodeURIComponent(projectId)}/annotation-tasks/${encodeURIComponent(taskId)}`,
  memberships: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/members`,
  accounts: '/admin/users',
  uploads: '/uploads',
  localUpload: '/uploads/new',
} as const
