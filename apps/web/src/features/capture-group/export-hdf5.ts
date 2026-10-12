import { downloadHumanDemonstrationHdf5 } from '@/api/annotation'

export async function triggerHdf5Download(
  projectId: string,
  captureGroupIds?: string[],
  signal?: AbortSignal,
): Promise<string> {
  const { blob, fileName } = signal
    ? await downloadHumanDemonstrationHdf5(projectId, captureGroupIds, signal)
    : await downloadHumanDemonstrationHdf5(projectId, captureGroupIds)
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = fileName
  link.rel = 'noopener'
  link.hidden = true
  document.body.append(link)
  try {
    link.click()
  } finally {
    URL.revokeObjectURL(objectUrl)
    link.remove()
  }
  return fileName
}
