import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App as AntdApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { RouterProvider } from 'react-router'

import { AuthProvider } from '@/app/auth'
import { router } from '@/app/router'
import { PromptProvider } from '@/components/PromptDialog'
import { applyAppCssVariables, appTheme } from '@/theme/theme'

import 'antd/dist/reset.css'
import '@/styles/globals.css'
import '@/styles/layout.css'
import '@/styles/components.css'
import '@/styles/workbench.css'
import '@/styles/capture-group.css'

const root = document.getElementById('app')
if (!root) throw new Error('未找到应用挂载节点。')

applyAppCssVariables()

createRoot(root).render(
  <StrictMode>
    <ConfigProvider locale={zhCN} theme={appTheme}>
      <AntdApp>
        <AuthProvider>
          <PromptProvider>
            <RouterProvider router={router} />
          </PromptProvider>
        </AuthProvider>
      </AntdApp>
    </ConfigProvider>
  </StrictMode>,
)
