import { Alert, Button, Card, Form, Input } from 'antd'
import { useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'

import { AuthApiError } from '@/api/auth'
import { useAuth } from '@/app/auth'
import { paths } from '@/app/paths'

interface LoginFields {
  username: string
  password: string
}

export default function LoginView() {
  const auth = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [form] = Form.useForm<LoginFields>()
  const [errorMessage, setErrorMessage] = useState('')

  if (!auth.initialized && auth.loading) {
    return (
      <div className="catalog-notice" role="status">
        正在确认登录状态…
      </div>
    )
  }
  if (auth.isAuthenticated) return <Navigate replace to={paths.projects} />

  async function submitLogin(values: LoginFields) {
    setErrorMessage('')
    try {
      await auth.login(values.username.trim(), values.password)
      const redirect = new URLSearchParams(location.search).get('redirect') ?? paths.projects
      const safeRedirect =
        redirect.startsWith('/') && !redirect.startsWith('//') && !redirect.startsWith('/\\')
          ? redirect
          : paths.projects
      await navigate(safeRedirect, { replace: true })
    } catch (error) {
      setErrorMessage(
        error instanceof AuthApiError ? error.message : '暂时无法完成登录，请稍后重试。',
      )
      if (error instanceof AuthApiError && error.errorCode === 'INVALID_CREDENTIALS') {
        form.setFieldValue('password', '')
      }
    }
  }

  return (
    <section className="login-page" aria-labelledby="login-title">
      <Card className="login-card" variant="borderless">
        <h1 id="login-title">登录数据管理平台</h1>
        <p>仅供平台管理人员使用。医院侧采集设备通过独立凭据自动上传视频，不使用此页面。</p>
        {errorMessage || auth.sessionError ? (
          <Alert
            className="login-error"
            type="error"
            title={errorMessage || auth.sessionError?.message}
            showIcon
          />
        ) : null}
        <Form<LoginFields>
          form={form}
          className="login-form"
          layout="vertical"
          requiredMark={false}
          onFinish={(values) => void submitLogin(values)}
        >
          <Form.Item
            label="账号"
            name="username"
            rules={[{ required: true, message: '请输入账号。' }]}
          >
            <Input autoComplete="username" maxLength={128} />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            rules={[{ required: true, message: '请输入密码。' }]}
          >
            <Input.Password autoComplete="current-password" maxLength={256} />
          </Form.Item>
          <Button htmlType="submit" type="primary" loading={auth.loading}>
            登录
          </Button>
        </Form>
      </Card>
    </section>
  )
}
