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
  expect(visibleText).not.toMatch(/\b(?:ASSIGNED|SUBMITTED|COMPLETED|acquire_item)\b/)

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

test('AccountManagement — management table columns stay bounded', async ({ page }) => {
  await mockCaptureGroupApi(page, 'list')
  await page.route('**/api/v1/admin/**', (route) => {
    const { pathname } = new URL(route.request().url())
    if (pathname === '/api/v1/admin/accounts') {
      return json(route, {
        items: [
          {
            username: 'synthetic-admin',
            role: 'ADMIN',
            enabled: true,
            version: 1,
            created_at: '2026-08-20T01:00:00Z',
            updated_at: '2026-08-20T01:00:00Z',
          },
          {
            username: 'synthetic-reviewer',
            role: 'MEMBER',
            enabled: false,
            version: 2,
            created_at: '2026-08-21T01:00:00Z',
            updated_at: '2026-08-22T01:00:00Z',
          },
        ],
      })
    }
    return route.abort()
  })

  await page.goto('/admin/users')
  await expect(page.getByRole('heading', { name: '平台账号列表' })).toBeVisible()

  // 账号列必须保持窄稳：一旦它吸收右栏多余宽度，角色/状态/操作会被顶到
  // 1080p 屏幕的右端，视线要横跨半屏才能读完一行。
  const accountHeader = await page.getByRole('columnheader', { name: '账号' }).boundingBox()
  expect(accountHeader!.width).toBeLessThanOrEqual(320)

  // 末列负责吸收多余宽度，所以每行的操作始终可达。
  await expect(
    page.getByRole('button', { name: '启用账号：synthetic-reviewer' }),
  ).toBeVisible()
  await expect(page.getByText('管理员不可停用')).toBeVisible()

  // 双栏必须齐平：`.table-card` 的 16px margin-top 曾把右栏整卡推低，
  // 两栏标题又因基线对齐再差 3px。
  const leftCard = await page.locator('.account-create-panel').boundingBox()
  const rightCard = await page.locator('.account-table-card').boundingBox()
  expect(Math.abs(leftCard!.y - rightCard!.y)).toBeLessThanOrEqual(1)
  const leftTitle = await page.getByRole('heading', { name: '创建账号' }).boundingBox()
  const rightTitle = await page.getByRole('heading', { name: '平台账号列表' }).boundingBox()
  expect(Math.abs(leftTitle!.y - rightTitle!.y)).toBeLessThanOrEqual(1)

  await expectNoHorizontalPageOverflow(page)
  await expectNoSeriousAxeViolations(page)
})

test('AccountManagement — no page-level horizontal scroll on narrow viewports', async ({ page }) => {
  await mockCaptureGroupApi(page, 'list')
  await page.route('**/api/v1/admin/**', (route) => {
    const { pathname } = new URL(route.request().url())
    if (pathname === '/api/v1/admin/accounts') {
      return json(route, {
        items: [
          {
            username: 'synthetic-admin',
            role: 'ADMIN',
            enabled: true,
            version: 1,
            created_at: '2026-08-20T01:00:00Z',
            updated_at: '2026-08-20T01:00:00Z',
          },
          {
            username: 'synthetic-reviewer',
            role: 'MEMBER',
            enabled: true,
            version: 2,
            created_at: '2026-08-21T01:00:00Z',
            updated_at: '2026-08-22T01:00:00Z',
          },
        ],
      })
    }
    return route.abort()
  })

  for (const width of [1280, 1100, 1024, 768, 500]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/admin/users')
    await expect(page.getByRole('heading', { name: '平台账号列表' })).toBeVisible()

    // 窄屏不许出现页面级横滚：卡片自身必须收进视口，溢出只留给 antd 的表格容器。
    // 1140 以下应改为上下堆叠；左卡若停在屏幕外，说明网格子项把整行顶开了。
    const leftCard = await page.locator('.account-create-panel').boundingBox()
    const rightCard = await page.locator('.account-table-card').boundingBox()
    expect(leftCard!.x, `${width}: 左卡左缘应在视口内`).toBeGreaterThanOrEqual(0)
    expect(leftCard!.x + leftCard!.width, `${width}: 左卡右缘应在视口内`).toBeLessThanOrEqual(width)
    expect(rightCard!.x + rightCard!.width, `${width}: 右卡右缘应在视口内`).toBeLessThanOrEqual(width)
    if (width < 1140) {
      expect(Math.abs(leftCard!.x - rightCard!.x), `${width}: 应改为上下堆叠`).toBeLessThanOrEqual(1)
    }

    await expectNoHorizontalPageOverflow(page)
  }
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
  await expect(page.getByText('已选择 1 个视频', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '开始上传' })).toBeEnabled()

  await expectNoHorizontalPageOverflow(page)
  await expectNoSeriousAxeViolations(page)
})

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}
