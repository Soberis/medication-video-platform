import { expect, test, type Route } from '@playwright/test'

import {
  expectNoHorizontalPageOverflow,
  expectNoSeriousAxeViolations,
} from './fixtures/browser-acceptance'
import { mockCaptureGroupApi, projectId } from './fixtures/capture-group'

test.use({ viewport: { width: 1440, height: 900 } })
test.beforeEach(async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
})

test('1440 — Login', async ({ page }) => {
  let loginRequests = 0
  await page.route('**/api/v1/auth/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === '/api/v1/auth/session') {
      return json(route, { error_code: 'UNAUTHENTICATED' }, 401)
    }
    if (pathname === '/api/v1/auth/login') {
      loginRequests += 1
      return json(route, { error_code: 'INVALID_CREDENTIALS' }, 401)
    }
    return route.abort()
  })

  await page.goto('/login')
  const username = page.getByRole('textbox', { name: '账号' })
  const password = page.getByLabel('密码', { exact: true })
  const submit = page.getByRole('button', { name: /登\s*录/ })
  await expect(username).toBeVisible()
  await expect(password).toBeVisible()
  await expect(submit).toBeVisible()

  await expect(page).toHaveScreenshot('login-1440.png', {
    fullPage: true,
    animations: 'disabled',
  })

  await username.focus()
  await page.keyboard.type('synthetic-user')
  await page.keyboard.press('Tab')
  await expect(password).toBeFocused()
  await page.keyboard.type('incorrect-password')
  await page.keyboard.press('Enter')

  await expect(page.getByText('账号或密码不正确。')).toBeVisible()
  await expect.poll(() => loginRequests).toBe(1)
  await submit.focus()
  await expect(submit).toBeFocused()
  await expectNoSeriousAxeViolations(page)
})

test('1440 — AnnotationTaskList', async ({ page }) => {
  await mockCaptureGroupApi(page, 'list')
  await page.goto(`/projects/${projectId}/annotation-tasks`)

  const taskList = page.getByRole('list', { name: '标注任务列表' })
  const taskRows = taskList.getByRole('listitem')
  await expect(taskRows).toHaveCount(2)
  await expect(taskRows.nth(0)).toHaveAccessibleName(/待标注/)
  await expect(taskRows.nth(1)).toHaveAccessibleName(/已完成/)
  await expect(page).toHaveURL(/capture-groups\?view=tasks$/)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('视频')
  await expect(
    page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '标注任务' }),
  ).toHaveCount(0)

  const primaryAction = taskRows.nth(0).getByRole('link', { name: /继续标注/ })
  await primaryAction.focus()
  await expect(primaryAction).toBeFocused()

  const visibleText = await page.locator('body').innerText()
  expect(visibleText).not.toContain('synthetic-annotator')
  expect(visibleText).not.toMatch(/\b(?:ASSIGNED|SUBMITTED|COMPLETED|PREPARE)\b/)

  await expectNoSeriousAxeViolations(page)
  await expect(page).toHaveScreenshot('annotation-task-list-1440.png', {
    fullPage: true,
    animations: 'disabled',
  })
  await taskRows.nth(1).getByText('查看详情', { exact: true }).click()
  const expanded = taskRows.nth(1).locator(':scope > details')
  await expect(expanded).toHaveAttribute('open', '')
  const rowBox = await taskRows.nth(1).boundingBox()
  const detailsBox = await expanded.boundingBox()
  expect(detailsBox!.width).toBeGreaterThan(rowBox!.width * 0.9)
  await expectNoHorizontalPageOverflow(page)
  await page.getByText('待标注', { exact: true }).first().click()
  await expect(taskRows).toHaveCount(1)
})

test('global labels are available without entering a project', async ({ page }) => {
  await mockCaptureGroupApi(page, 'list')
  await page.goto('/settings/annotation-label-library')
  await expect(page.getByRole('heading', { name: '标签库', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '发布标签' })).toBeVisible()
  await expectNoHorizontalPageOverflow(page)
  await expectNoSeriousAxeViolations(page)
})

test('Local Upload — load and select files', async ({ page }) => {
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === '/api/v1/auth/session') {
      return json(route, { username: 'synthetic-admin', role: 'ADMIN' })
    }
    if (pathname === '/api/v1/projects') {
      return json(route, {
        items: [
          {
            project_id: projectId,
            display_name: '合成数据项目',
            status: 'ACTIVE',
            current_role: 'ADMIN',
            created_at: '2026-08-20T01:00:00Z',
          },
        ],
        can_create_projects: true,
      })
    }
    return route.abort()
  })

  await page.goto('/uploads/new')
  await expect(page.getByRole('heading', { name: '上传视频' })).toBeVisible()
  const projectSelect = page.getByRole('combobox', { name: '所属项目' })
  await projectSelect.click()
  await projectSelect.press('ArrowDown')
  await projectSelect.press('Enter')
  await page.getByLabel('视频文件').setInputFiles({
    name: 'synthetic-camera-1.mp4',
    mimeType: 'video/mp4',
    buffer: Buffer.from('synthetic-video'),
  })
  await expect(page.getByText('已选择 1 个视频')).toBeVisible()
  await expect(page.getByRole('button', { name: '开始上传' })).toBeEnabled()

  await expectNoHorizontalPageOverflow(page)
  await expectNoSeriousAxeViolations(page)
})

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}
