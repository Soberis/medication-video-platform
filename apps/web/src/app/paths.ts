export const paths = {
  login: '/login',
  projects: '/projects',
  sessions: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/sessions`,
  session: (projectId: string, sessionId: string) =>
    `/projects/${encodeURIComponent(projectId)}/sessions/${encodeURIComponent(sessionId)}`,
  assets: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/assets`,
  asset: (projectId: string, assetId: string) =>
    `/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}`,
  annotationTasks: (projectId: string) =>
    `/projects/${encodeURIComponent(projectId)}/annotation-tasks`,
  datasets: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/datasets`,
  dataset: (projectId: string, datasetVersionId: string) =>
    `/projects/${encodeURIComponent(projectId)}/datasets/${encodeURIComponent(datasetVersionId)}`,
  eventLogs: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/event-logs`,
  folders: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/folders`,
  memberships: (projectId: string) => `/projects/${encodeURIComponent(projectId)}/members`,
  accounts: '/admin/users',
  uploads: '/uploads',
  localUpload: '/uploads/new',
} as const
