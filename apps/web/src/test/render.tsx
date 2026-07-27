import { App as AntdApp, ConfigProvider } from 'antd'
import { render } from '@testing-library/react'
import type { ReactNode } from 'react'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'

import type { PlatformSession } from '@/api/auth'
import { AuthProvider } from '@/app/auth'
import { PromptProvider } from '@/components/PromptDialog'

interface RenderRouteOptions {
  route?: string
  path?: string
  session?: PlatformSession | null
}

export function renderRoute(element: ReactNode, options: RenderRouteOptions = {}) {
  const route = options.route ?? '/'
  return renderRouter([{ path: options.path ?? '*', element }], [route], options.session ?? null)
}

export function renderRouter(
  routes: RouteObject[],
  initialEntries: string[],
  session: PlatformSession | null = null,
) {
  const router = createMemoryRouter(routes, { initialEntries })
  return {
    router,
    ...render(
      <ConfigProvider>
        <AntdApp>
          <AuthProvider initialSession={session} initiallyInitialized>
            <PromptProvider>
              <RouterProvider router={router} />
            </PromptProvider>
          </AuthProvider>
        </AntdApp>
      </ConfigProvider>,
    ),
  }
}
