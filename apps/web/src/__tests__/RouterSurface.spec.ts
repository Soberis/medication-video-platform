import { describe, expect, it } from 'vitest'

import { router } from '@/app/router'

interface RouteSurface {
  path?: string
  children?: RouteSurface[]
}

function collectPaths(routes: RouteSurface[]): string[] {
  return routes.flatMap((route) => [
    ...(route.path ? [route.path] : []),
    ...(route.children ? collectPaths(route.children) : []),
  ])
}

describe('router surface', () => {
  it('registers only the unified CaptureGroup workflow for project media and annotation', () => {
    const registeredPaths = collectPaths(router.routes)

    expect(registeredPaths).toContain('/projects/:projectId/capture-groups')
    expect(registeredPaths).toContain('/projects/:projectId/capture-groups/:captureGroupId')
    expect(registeredPaths).toContain('/projects/:projectId/annotation-tasks')
    expect(registeredPaths).not.toContain('/projects/:projectId/videos')
    expect(registeredPaths).not.toContain('/projects/:projectId/assets/:id')
    expect(registeredPaths).not.toContain('/projects/:projectId/annotation-tasks/:taskId')
  })
})
