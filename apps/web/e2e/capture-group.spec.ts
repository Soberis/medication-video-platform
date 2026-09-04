import { expect, test } from '@playwright/test'

import {
  expectNoHorizontalPageOverflow,
  expectNoSeriousAxeViolations,
} from './fixtures/browser-acceptance'
import {
  completedGroupId,
  mockCaptureGroupApi,
  multiCameraGroupId,
  oneCameraGroupId,
  projectId,
} from './fixtures/capture-group'

const syntheticPlaybackUrl =
  'data:video/mp4;base64,' +
  [
    'AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAONbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAB9AAAQAAAQAA',
    'AAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAA',
    'Ard0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAB9AAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAA',
    'AAAAAAAAAAAAAABAAAAAAKAAAABaAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAfQAAAQAAABAAAAAAIvbWRpYQAAACBtZGhk',
    'AAAAAAAAAAAAAAAAAAAoAAAAUABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAAB2m1p',
    'bmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAZpzdGJsAAAAwnN0c2QA',
    'AAAAAAAAAQAAALJhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAKAAWgBIAAAASAAAAAAAAAABFUxhdmM2Mi4yOC4xMDIgbGli',
    'eDI2NAAAAAAAAAAAAAAAGP//AAAAOGF2Y0MBZAAM/+EAG2dkAAyscgRCjfkwEQAAAwABAAADAAoPFCmEYAEABmjoQ48si/34+AAA',
    'AAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAAC4AAAAAAAAAAYc3R0cwAAAAAAAAABAAAACgAACAAAAAAUc3RzcwAAAAAAAAAB',
    'AAAAAQAAADhjdHRzAAAAAAAAAAUAAAABAAAQAAAAAAEAAFAAAAAAAQAAIAAAAAADAAAAAAAAAAQAAAgAAAAAHHN0c2MAAAAAAAAA',
    'AQAAAAEAAAAKAAAAAQAAADxzdHN6AAAAAAAAAAAAAAAKAAAANwAAABEAAAAOAAAADgAAAA4AAAAOAAAADgAAAA4AAAAOAAAADgAA',
    'ABRzdGNvAAAAAAAAAAEAAAO9AAAAYnVkdGEAAABabWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAt',
    'aWxzdAAAACWpdG9vAAAAHWRhdGEAAAABAAAAAExhdmY2Mi4xMi4xMDIAAAAIZnJlZQAAAMBtZGF0AAAAM2WIgQACf/7q4/zLLY6H',
    'NOrfJbldTT0hMRL3GW2p9vz+1MlfKUX2po6nubIEjAAQIDZx4QAAAA1BmgktiH///qmWAOaAAAAACkGeEIcQS/8ADUkAAAAKAZ4Y',
    'Joh//wBJQAAAAAoBnhhGiH//AElBAAAACgGeGGaIf/8ASUEAAAAKAZ4YrUh//wBJQQAAAAoBnhjNSH//AElBAAAACgGeGO1If/8A',
    'SUAAAAAKAZ4ZDUh//wBJQA==',
  ].join('')

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

    const pageActions = page.getByRole('toolbar', { name: '页面操作' })
    await expect(pageActions.getByRole('button', { name: '导出手部检测证据' })).toHaveCount(0)
    const evidenceDetails = page.locator('details.capture-group-focused-details')
    await evidenceDetails.locator('summary').click()
    await expect(evidenceDetails.getByRole('button', { name: '导出手部检测证据' })).toBeVisible()
    await evidenceDetails.locator('summary').click()

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

    const evidenceDetails = page.locator('details.capture-group-focused-details')
    await evidenceDetails.locator('summary').click()
    await expect(evidenceDetails.getByRole('button', { name: '导出手部检测证据' })).toBeVisible()
    await evidenceDetails.locator('summary').click()

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

    const evidenceDetails = page.locator('details.capture-group-focused-details')
    await evidenceDetails.locator('summary').click()
    await expect(evidenceDetails.getByRole('button', { name: '导出手部检测证据' })).toBeVisible()
    await evidenceDetails.locator('summary').click()

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

test.describe('CaptureGroup event ledger responsive widths', () => {
  for (const width of [1440, 1280, 1024, 768, 720]) {
    test(`${width} — keeps the event ledger within the page`, async ({ page }) => {
      await mockCaptureGroupApi(page, 'list')
      await page.setViewportSize({ width, height: 900 })
      await page.goto(`/projects/${projectId}/capture-groups`)

      await expect(page.getByRole('list', { name: '配药事件列表' })).toBeVisible()
      await expectNoHorizontalPageOverflow(page)
    })
  }
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

    const evidenceDetails = page.locator('details.capture-group-focused-details')
    await evidenceDetails.locator('summary').click()
    await expect(evidenceDetails.getByRole('button', { name: '导出手部检测证据' })).toBeVisible()
    await expectNoHorizontalPageOverflow(page)
    await evidenceDetails.locator('summary').click()

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
    await expect(page.getByText('人工复核已通过，训练资格检查通过')).toBeVisible()
    await expectNoHorizontalPageOverflow(page)

    const evidenceDetails = page.locator('details.capture-group-focused-details')
    await evidenceDetails.locator('summary').click()
    await expect(evidenceDetails.getByRole('button', { name: '导出手部检测证据' })).toBeVisible()
    await expectNoHorizontalPageOverflow(page)
    await evidenceDetails.locator('summary').click()

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-completed-720.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })
})

test.describe('native playback controls', () => {
  for (const width of [1440, 1100, 1024, 720]) {
    test(`${width} — keeps native controls while limiting browser-owned actions`, async ({
      page,
    }) => {
      await mockCaptureGroupApi(page, 'n1-active')
      await page.route('**/playback-grants', (route) =>
        route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            grant_id: `playback-controls-${width}`,
            purpose: 'DATA_REVIEW',
            media_url: syntheticPlaybackUrl,
            expires_at: '2099-01-01T00:00:00Z',
            content_type: 'video/mp4',
          }),
        }),
      )
      await page.setViewportSize({ width, height: 900 })
      await page.goto(`/projects/${projectId}/capture-groups/${oneCameraGroupId}`)
      await page.getByRole('button', { name: '播放视频' }).click()

      const player = page.getByLabel('视频预览')
      await expect(player).toBeVisible()
      await expect(player).toHaveJSProperty('controls', true)
      await expect(player).toHaveAttribute(
        'controlslist',
        'nodownload nofullscreen noremoteplayback',
      )
      await expect(player).toHaveAttribute('disablepictureinpicture', '')
      await expect(player).toHaveJSProperty('disablePictureInPicture', true)
      await expectNoHorizontalPageOverflow(page)

      if (width !== 1440) return

      await player.evaluate((element) => (element as HTMLVideoElement).play())
      await expect
        .poll(() => player.evaluate((element) => (element as HTMLVideoElement).currentTime))
        .toBeGreaterThan(0)
      await player.evaluate((element) => {
        const video = element as HTMLVideoElement
        video.pause()
        video.currentTime = 1
        video.dispatchEvent(new Event('timeupdate'))
      })
      await expect(page.getByLabel('当前播放位置')).toContainText('00:01.000')

      await player.evaluate((element) => element.blur())
      await page.keyboard.press('Space')
      await expect
        .poll(() => player.evaluate((element) => (element as HTMLVideoElement).paused))
        .toBe(false)
      await page.keyboard.press('Space')
      await expect
        .poll(() => player.evaluate((element) => (element as HTMLVideoElement).paused))
        .toBe(true)

      await player.focus()
      await expect(player).toBeFocused()
      await page.keyboard.press('Space')
      await expect
        .poll(() => player.evaluate((element) => (element as HTMLVideoElement).paused))
        .toBe(false)
      await player.evaluate((element) => (element as HTMLVideoElement).pause())

      await page.getByRole('button', { name: '全屏查看视频和骨架' }).click()
      await expect
        .poll(() =>
          page.locator('.video-stage').evaluate((stage) => document.fullscreenElement === stage),
        )
        .toBe(true)
      await page.keyboard.press('Escape')
    })
  }
})
