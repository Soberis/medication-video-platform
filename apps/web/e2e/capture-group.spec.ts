import { expect, test } from '@playwright/test'

import { type ActionCandidateJob } from '@/api/action-candidate'
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

for (const width of [1366, 1440]) {
  test(`${width} — video first workspace with unsaved navigation guard`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 1366 ? 768 : 900 })
    await mockCaptureGroupApi(page, 'n2-active')
    await page.goto(`/projects/${projectId}/capture-groups/${multiCameraGroupId}`)
    await expect(page.getByRole('region', { name: '动作标注' })).toBeVisible()
    await expect(page.getByRole('button', { name: /提交(标注|修订)/ })).toBeVisible()
    await expectNoHorizontalPageOverflow(page)
    await expectNoSeriousAxeViolations(page)
    const rendering = await page.evaluate(() => ({
      devicePixelRatio: window.devicePixelRatio,
      fontFamily: getComputedStyle(document.body).fontFamily,
      microsoftYaHei: document.fonts.check('14px "Microsoft YaHei"'),
    }))
    await test.info().attach('rendering.json', {
      body: JSON.stringify(rendering),
      contentType: 'application/json',
    })
    await expect(page).toHaveScreenshot(`capture-group-viewing-${width}.png`, {
      fullPage: true,
      animations: 'disabled',
    })
    await page.getByLabel('开始时间（毫秒）', { exact: true }).fill('123')
    await page.getByRole('button', { name: '刷新', exact: true }).click()
    await expect(page.getByLabel('开始时间（毫秒）', { exact: true })).toHaveValue('123')
    await page.getByRole('link', { name: '视频', exact: true }).first().click()
    const dialog = page.getByRole('dialog', { name: '离开并丢弃未提交的标注？' })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: '继续编辑' }).click()
    await expect(page.getByLabel('开始时间（毫秒）', { exact: true })).toHaveValue('123')
    await expectNoHorizontalPageOverflow(page)
  })
}

test('the annotation workspace opens without writing anything', async ({ page }) => {
  await mockCaptureGroupApi(page, 'unassigned')
  const mutations: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/') && request.method() !== 'GET') mutations.push(request.url())
  })
  await page.goto(`/projects/${projectId}/capture-groups/${oneCameraGroupId}`)
  await expect(page.getByRole('region', { name: '动作标注' })).toBeVisible()
  await expect(page.getByRole('button', { name: '导出数据' })).toHaveCount(0)
  await expectNoSeriousAxeViolations(page)
  await expectNoHorizontalPageOverflow(page)
  expect(mutations).toEqual([])
  await expect(page).toHaveScreenshot('capture-group-unassigned-1440.png', {
    fullPage: true,
    animations: 'disabled',
  })
})

test.describe('V1.27 CaptureGroup goldens · 1440', () => {
  test('1440 — CaptureGroup event list', async ({ page }) => {
    await mockCaptureGroupApi(page, 'list')
    await page.goto(`/projects/${projectId}/capture-groups`)
    await expect(page.getByRole('heading', { name: '视频' })).toBeVisible()

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-list-1440.png', {
      fullPage: true,
      animations: 'disabled',
    })
    await page.getByRole('button', { name: '导出数据' }).click()
    await page
      .getByRole('checkbox', { name: /选择导出/ })
      .last()
      .check()
    await expect(page.getByRole('button', { name: '导出项目全部合格数据' })).toHaveCount(0)
    await expect(page.getByText('已选择 1 条', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '下载 HDF5' })).toBeEnabled()
    await expect(page).toHaveScreenshot('capture-group-export-1440.png', {
      fullPage: true,
      animations: 'disabled',
    })
    const exportDialog = page.getByRole('dialog', { name: '导出数据', exact: true })
    await expectNoSeriousAxeViolations(page)
    await expectNoHorizontalPageOverflow(page)
    await expect(exportDialog).toContainText('不包含 MP4、对象存储地址')
    await exportDialog.getByRole('button', { name: '取消' }).click()
  })

  test('1440 — N=1 active annotation workbench', async ({ page }) => {
    await mockCaptureGroupApi(page, 'n1-active')
    await page.goto(`/projects/${projectId}/capture-groups/${oneCameraGroupId}`)
    await expect(page.getByRole('button', { name: /提交(标注|修订)/ })).toBeVisible()

    await expect(page.getByText('当前视图', { exact: true })).toHaveCount(0)
    await expect(page.getByText('人工查看', { exact: true })).toHaveCount(0)
    await expect(page.locator('.capture-group-workspace[data-stage="ANNOTATION"]')).toHaveCount(1)
    await expect(page.getByRole('group', { name: '采集记录机位选择' })).toHaveCount(0)
    await expect(page.getByRole('region', { name: '动作标注' })).toBeVisible()

    const pageActions = page.getByRole('toolbar', { name: '页面操作' })
    await expect(pageActions.getByRole('button', { name: '导出人工标注' })).toHaveCount(0)
    await expect(pageActions.getByRole('button', { name: '导出数据' })).toHaveCount(0)
    const evidenceDetails = page.locator('details.capture-group-focused-details')
    await evidenceDetails.locator('summary').click()
    await expect(page.getByText('手部检测：检测中')).toBeVisible()
    await expect(evidenceDetails.getByRole('button', { name: '导出手部关键点' })).toHaveCount(0)
    await evidenceDetails.locator('summary').click()

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-n1-active-1440.png', {
      fullPage: true,
      animations: 'disabled',
    })

    await expect(page.getByRole('combobox', { name: '主视图' })).toHaveCount(0)

    await page.getByRole('button', { name: /提交(标注|修订)/ }).click()
    const firstInvalidField = page.getByRole('combobox', {
      name: '第 1 个时间段动作标签',
    })
    await expect(firstInvalidField).toHaveAttribute('aria-invalid', 'true')
    await expect(firstInvalidField).toBeFocused()
  })

  test('1440 — N>1 annotation workbench', async ({ page }) => {
    await mockCaptureGroupApi(page, 'n2-active')
    await page.goto(`/projects/${projectId}/capture-groups/${multiCameraGroupId}`)
    await expect(page.getByRole('button', { name: /提交(标注|修订)/ })).toBeVisible()

    const cameraSelector = page.getByRole('group', { name: '采集记录机位选择' })
    await expect(cameraSelector).toHaveCount(1)
    await expect(cameraSelector.getByRole('button', { name: /机位 / })).toHaveCount(2)

    const evidenceDetails = page.locator('details.capture-group-focused-details')
    await evidenceDetails.locator('summary').click()
    await expect(
      page.getByRole('toolbar', { name: '页面操作' }).getByRole('button', { name: '导出人工标注' }),
    ).toHaveCount(0)
    await expect(
      page.getByRole('toolbar', { name: '页面操作' }).getByRole('button', { name: '导出数据' }),
    ).toHaveCount(0)
    await expect(page.getByText('手部检测：检测中')).toBeVisible()
    await expect(evidenceDetails.getByRole('button', { name: '导出手部关键点' })).toHaveCount(0)
    await evidenceDetails.locator('summary').click()

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-n2-active-1440.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })

  test('1440 — completed CaptureGroup', async ({ page }) => {
    await mockCaptureGroupApi(page, 'completed')
    await page.goto(`/projects/${projectId}/capture-groups/${completedGroupId}?focus=review`)
    await expect(page.locator('.capture-group-workspace[data-stage="COMPLETED"]')).toHaveCount(1)
    const reviewRegion = page.getByRole('region', { name: '标注复核' })
    await expect(reviewRegion).toBeVisible()
    const reviewToggle = reviewRegion.getByRole('button', {
      name: '查看复核详情',
    })
    await expect(reviewToggle).toBeVisible()

    await expect(
      reviewRegion.locator('details.capture-group-review-detail-disclosure'),
    ).toHaveCount(0)

    const evidenceDetails = page.locator('details.capture-group-focused-details')
    await evidenceDetails.locator('summary').click()
    await expect(page.getByText('手部检测：已完成 · 检测到手部')).toBeVisible()
    await expect(
      page.getByRole('toolbar', { name: '页面操作' }).getByRole('button', { name: '导出数据' }),
    ).toHaveCount(0)
    await expect(evidenceDetails.getByRole('button', { name: '导出手部关键点' })).toHaveCount(0)
    await evidenceDetails.locator('summary').click()

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-completed-1440.png', {
      fullPage: true,
      animations: 'disabled',
    })

    await reviewToggle.click()
    await expect(
      reviewRegion.locator('details.capture-group-review-detail-disclosure'),
    ).toBeVisible()
  })
})

test('project export selects every candidate across pages without expanding the main video list', async ({
  page,
}) => {
  await mockCaptureGroupApi(page, 'list')
  const groupRead = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/v1/projects/${projectId}/capture-groups`,
  )
  const taskRead = page.waitForResponse((response) =>
    new URL(response.url()).pathname.endsWith('/annotation-tasks'),
  )
  await page.goto(`/projects/${projectId}/capture-groups`)
  const groupData = await (await groupRead).json()
  const taskData = await (await taskRead).json()
  const candidates = Array.from({ length: 21 }, (_, index) => ({
    ...groupData.items[1],
    capture_group_id: `60000000-0000-4000-8000-${String(index + 100).padStart(12, '0')}`,
    capture_group_key: `候选视频 ${index + 1}`,
  }))
  await page.route(`**/api/v1/projects/${projectId}/capture-groups?**`, (route) => {
    const next = new URL(route.request().url()).searchParams.has('cursor')
    return route.fulfill({
      json: {
        items: next ? candidates.slice(20) : candidates.slice(0, 20),
        next_cursor: next ? null : 'export-page-2',
      },
    })
  })
  await page.route(`**/api/v1/projects/${projectId}/annotation-tasks`, (route) =>
    route.fulfill({
      json: {
        ...taskData,
        items: candidates.map((group) => ({
          ...taskData.items.find((task: { status: string }) => task.status === 'COMPLETED'),
          capture_group_id: group.capture_group_id,
        })),
      },
    }),
  )
  let downloadedIds: string[] = []
  await page.route('**/human-demonstrations/hdf5/**', (route) => {
    downloadedIds = new URL(route.request().url()).searchParams.getAll('capture_group_id')
    return route.fulfill({
      contentType: 'application/x-hdf5',
      headers: { 'Content-Disposition': 'attachment; filename="synthetic.hdf5"' },
      body: 'synthetic download fixture',
    })
  })
  await page.getByRole('button', { name: '导出数据' }).click()
  const dialog = page.getByRole('dialog', { name: '导出数据', exact: true })
  await expect(dialog.getByRole('checkbox', { name: /选择导出/ })).toHaveCount(21)
  await dialog.getByRole('checkbox', { name: '全选', exact: true }).focus()
  await page.keyboard.press('Space')
  await expect(dialog.getByText('已选择 21 条', { exact: true })).toBeVisible()
  await expectNoHorizontalPageOverflow(page)
  await expectNoSeriousAxeViolations(page)
  const download = page.waitForEvent('download')
  await dialog.getByRole('button', { name: '下载 HDF5' }).click()
  expect((await download).suggestedFilename()).toBe('synthetic.hdf5')
  expect(downloadedIds).toEqual(candidates.map((group) => group.capture_group_id))
  await expect(page.getByRole('list', { name: '采集记录列表' }).getByRole('listitem')).toHaveCount(
    2,
  )
})

test.describe('CaptureGroup event ledger responsive widths', () => {
  for (const width of [1440, 1280, 1024, 768, 720]) {
    test(`${width} — keeps the event ledger within the page`, async ({ page }) => {
      await mockCaptureGroupApi(page, 'list')
      await page.setViewportSize({ width, height: 900 })
      await page.goto(`/projects/${projectId}/capture-groups`)

      await expect(page.getByRole('list', { name: '采集记录列表' })).toBeVisible()
      await expectNoHorizontalPageOverflow(page)
    })
  }
})

test.describe('V1.27 CaptureGroup goldens · 720', () => {
  test.use({ viewport: { width: 720, height: 900 } })

  test('720 — active workbench', async ({ page }) => {
    await mockCaptureGroupApi(page, 'n2-active')
    await page.goto(`/projects/${projectId}/capture-groups/${multiCameraGroupId}`)
    await expect(page.getByRole('button', { name: /提交(标注|修订)/ })).toBeVisible()
    await expect(page.getByRole('group', { name: '采集记录机位选择' })).toHaveCount(1)
    await expectNoHorizontalPageOverflow(page)

    const evidenceDetails = page.locator('details.capture-group-focused-details')
    await evidenceDetails.locator('summary').click()
    await expect(
      page.getByRole('toolbar', { name: '页面操作' }).getByRole('button', { name: '导出人工标注' }),
    ).toHaveCount(0)
    await expect(
      page.getByRole('toolbar', { name: '页面操作' }).getByRole('button', { name: '导出数据' }),
    ).toHaveCount(0)
    await expect(page.getByText('手部检测：检测中')).toBeVisible()
    await expect(evidenceDetails.getByRole('button', { name: '导出手部关键点' })).toHaveCount(0)
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
    await page.goto(`/projects/${projectId}/capture-groups/${completedGroupId}?focus=review`)
    await expect(page.locator('.capture-group-workspace[data-stage="COMPLETED"]')).toHaveCount(1)
    const reviewRegion = page.getByRole('region', { name: '标注复核' })
    await expect(reviewRegion).toBeVisible()
    const reviewToggle = reviewRegion.getByRole('button', {
      name: '查看复核详情',
    })
    await expect(reviewToggle).toBeVisible()
    await expectNoHorizontalPageOverflow(page)

    const evidenceDetails = page.locator('details.capture-group-focused-details')
    await evidenceDetails.locator('summary').click()
    await expect(page.getByText('手部检测：已完成 · 检测到手部')).toBeVisible()
    await expect(
      page.getByRole('toolbar', { name: '页面操作' }).getByRole('button', { name: '导出数据' }),
    ).toHaveCount(0)
    await expect(evidenceDetails.getByRole('button', { name: '导出手部关键点' })).toHaveCount(0)
    await expectNoHorizontalPageOverflow(page)
    await evidenceDetails.locator('summary').click()

    await expectNoSeriousAxeViolations(page)
    await expect(page).toHaveScreenshot('capture-group-completed-720.png', {
      fullPage: true,
      animations: 'disabled',
    })
    await reviewToggle.click()
    await expect(
      reviewRegion.locator('details.capture-group-review-detail-disclosure'),
    ).toBeVisible()
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
      await expect(page.getByLabel('当前播放位置')).toContainText('00:01')

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

test.describe('automatic action candidate discovery and retry', () => {
  for (const width of [1440, 720]) {
    test(`${width} — automatically discovers background action job without human task assignment`, async ({
      page,
    }) => {
      let hasDiscovered = false
      await mockCaptureGroupApi(page, 'unassigned')

      await page.route('**/action-candidate-job', (route) => {
        if (!hasDiscovered) {
          return route.fulfill({ status: 204 })
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            ai_job_id: 'discovered-action-job',
            project_id: projectId,
            capture_group_id: oneCameraGroupId,
            clip_asset_id: '50000000-0000-4000-8000-000000000001',
            status: 'RUNNING',
            attempt: 1,
            created_at: '2026-08-20T01:00:02Z',
            updated_at: '2026-08-20T01:00:02Z',
          }),
        })
      })

      await page.setViewportSize({ width, height: 900 })
      await page.goto(`/projects/${projectId}/capture-groups/${oneCameraGroupId}`)

      await expect(page.getByRole('region', { name: '动作标注' })).toBeVisible()
      await expect(page.getByText('等待自动分析')).toBeVisible()
      await expect(page.getByRole('button', { name: '启动自动标注' })).toHaveCount(0)

      hasDiscovered = true
      await expect(page.getByText('动作分析中')).toBeVisible({ timeout: 15000 })
      await expectNoHorizontalPageOverflow(page)
      await expectNoSeriousAxeViolations(page)
    })

    test(`${width} — retries failed action job without creating or requiring an annotation task`, async ({
      page,
    }) => {
      let retried = false
      let currentJob: ActionCandidateJob = {
        ai_job_id: 'failed-action-job',
        project_id: projectId,
        capture_group_id: oneCameraGroupId,
        clip_asset_id: '50000000-0000-4000-8000-000000000001',
        status: 'FAILED',
        attempt: 1,
        created_at: '2026-08-20T01:00:02Z',
        updated_at: '2026-08-20T01:00:02Z',
      }

      await mockCaptureGroupApi(page, 'unassigned', {
        actionJob: currentJob,
      })

      const taskCreations: string[] = []
      page.on('request', (request) => {
        if (request.url().includes('/annotation-tasks') && request.method() === 'POST') {
          taskCreations.push(request.url())
        }
      })

      await page.route('**/action-candidate-job', (route) => {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(currentJob),
        })
      })

      await page.route('**/action-candidate-jobs', (route) => {
        if (route.request().method() === 'POST') {
          retried = true
          currentJob = {
            ...currentJob,
            ai_job_id: 'retried-action-job',
            status: 'QUEUED',
            attempt: 2,
          }
          return route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify(currentJob),
          })
        }
        return route.continue()
      })

      await page.setViewportSize({ width, height: 900 })
      await page.goto(`/projects/${projectId}/capture-groups/${oneCameraGroupId}`)

      await expect(page.getByText('动作分析失败', { exact: true })).toBeVisible()
      const retryButton = page.getByRole('button', { name: '重试动作分析' })
      await expect(retryButton).toBeVisible()
      await retryButton.click()

      await expect.poll(() => retried).toBe(true)
      expect(taskCreations).toHaveLength(0)
      await expect(page.getByText('等待处理', { exact: true })).toBeVisible()
      await expectNoHorizontalPageOverflow(page)
      await expectNoSeriousAxeViolations(page)
    })
  }
})
