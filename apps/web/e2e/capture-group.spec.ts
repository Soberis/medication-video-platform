import { AxeBuilder } from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import {
  completedGroupId,
  mockCaptureGroupApi,
  multiCameraGroupId,
  oneCameraGroupId,
  projectId,
} from './fixtures/capture-group'

test.use({ viewport: { width: 1440, height: 900 } })
test.beforeEach(async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
})

test.describe('V1.27 CaptureGroup goldens · 1440', () => {
  test('1440 — CaptureGroup event list', async ({ page }) => {
    await mockCaptureGroupApi(page, 'list')
    await page.goto(`/projects/${projectId}/capture-groups`)
    await expect(page.getByRole('heading', { name: '配药事件' })).toBeVisible()

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-list-1440.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })

  test('1440 — N=1 active annotation workbench', async ({ page }) => {
    await mockCaptureGroupApi(page, 'n1-active')
    await page.goto(`/projects/${projectId}/capture-groups/${oneCameraGroupId}`)
    await expect(page.getByRole('button', { name: '提交修订' })).toBeVisible()

    await expect(page.getByRole('group', { name: '配药事件机位选择' })).toHaveCount(0)
    const currentAction = page.getByRole('region', { name: '当前动作' })
    await expect(currentAction).toBeVisible()
    await expect(currentAction.locator('.capture-group-current-action-form-label')).toHaveText(
      '配药动作标注',
    )
    await expect(page.getByText('手部检测：处理中')).toBeVisible()

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-n1-active-1440.png', {
      fullPage: true,
      animations: 'disabled',
    })

    const primaryView = page.getByRole('combobox', { name: '主视图' })
    await primaryView.focus()
    await expect(primaryView).toBeFocused()
    await expect(primaryView).toHaveAttribute('aria-haspopup', 'listbox')

    await page.getByRole('button', { name: '提交修订' }).click()
    const firstInvalidField = page.getByRole('combobox', {
      name: '第 1 个时间段动作标签',
    })
    await expect(firstInvalidField).toHaveAttribute('aria-invalid', 'true')
    await expect(firstInvalidField).toBeFocused()
  })

  test('1440 — N>1 annotation workbench', async ({ page }) => {
    await mockCaptureGroupApi(page, 'n2-active')
    await page.goto(`/projects/${projectId}/capture-groups/${multiCameraGroupId}`)
    await expect(page.getByRole('button', { name: '提交修订' })).toBeVisible()

    const cameraSelector = page.getByRole('group', { name: '配药事件机位选择' })
    await expect(cameraSelector).toHaveCount(1)
    await expect(cameraSelector.getByRole('button', { name: /机位 / })).toHaveCount(2)

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-n2-active-1440.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })

  test('1440 — completed CaptureGroup', async ({ page }) => {
    await mockCaptureGroupApi(page, 'completed')
    await page.goto(`/projects/${projectId}/capture-groups/${completedGroupId}`)
    await expect(page.getByRole('heading', { name: '当前结果' })).toBeVisible()
    await expect(page.getByText('手部检测：已完成 · 检测到手部')).toBeVisible()

    const workflowDetails = page.locator('details.capture-group-workflow-disclosure')
    await expect(workflowDetails).not.toHaveAttribute('open', '')

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-completed-1440.png', {
      fullPage: true,
      animations: 'disabled',
    })

    const workflowSummary = workflowDetails.locator('summary')
    await workflowSummary.focus()
    await page.keyboard.press('Enter')
    await expect(workflowDetails).toHaveAttribute('open', '')
  })
})

test.describe('V1.27 CaptureGroup goldens · 720', () => {
  test.use({ viewport: { width: 720, height: 900 } })

  test('720 — active workbench', async ({ page }) => {
    await mockCaptureGroupApi(page, 'n2-active')
    await page.goto(`/projects/${projectId}/capture-groups/${multiCameraGroupId}`)
    await expect(page.getByRole('button', { name: '提交修订' })).toBeVisible()
    await expect(page.getByRole('group', { name: '配药事件机位选择' })).toHaveCount(1)
    await expect(page.getByText('手部检测：处理中')).toBeVisible()
    await expectNoHorizontalPageOverflow(page)

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-active-720.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })

  test('720 — completed CaptureGroup', async ({ page }) => {
    await mockCaptureGroupApi(page, 'completed')
    await page.goto(`/projects/${projectId}/capture-groups/${completedGroupId}`)
    await expect(page.getByRole('heading', { name: '当前结果' })).toBeVisible()
    await expect(page.getByText('人工复核已通过 · 训练资格检查通过')).toBeVisible()
    await expectNoHorizontalPageOverflow(page)

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-completed-720.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })
})

async function expectNoSeriousAxeViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze()
  const blocking = results.violations.filter((violation) =>
    ['serious', 'critical'].includes(violation.impact ?? ''),
  )
  const lowerImpact = results.violations.filter(
    (violation) => !['serious', 'critical'].includes(violation.impact ?? ''),
  )
  if (lowerImpact.length) {
    test.info().annotations.push({
      type: 'axe-lower-impact',
      description: lowerImpact.map((violation) => `${violation.id}: ${violation.help}`).join('\n'),
    })
    console.warn(
      `axe lower-impact findings:\n${lowerImpact.map((violation) => `${violation.id}: ${violation.help}`).join('\n')}`,
    )
  }
  expect(blocking, `serious/critical axe violations: ${JSON.stringify(blocking)}`).toEqual([])
}

async function expectNoHorizontalPageOverflow(page: Page) {
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true)
}
