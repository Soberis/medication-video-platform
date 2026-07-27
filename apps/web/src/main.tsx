import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App as AntdApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { RouterProvider } from 'react-router'

import { AuthProvider } from '@/app/auth'
import { router } from '@/app/router'
import { PromptProvider } from '@/components/PromptDialog'

import 'antd/dist/reset.css'
import '@/styles.css'

const root = document.getElementById('app')
if (!root) throw new Error('未找到应用挂载节点。')

createRoot(root).render(
  <StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#2f5ec4',
          borderRadius: 6,
          fontFamily: "Inter, 'Noto Sans SC', 'Microsoft YaHei', sans-serif",
        },
      }}
    >
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
