import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import * as localUploadApi from '@/api/local-upload'
import AppShell from '@/App'
import { LocalUploadProvider } from '@/app/local-upload'
import { paths } from '@/app/paths'
import { renderRoute, renderRouter } from '@/test/render'
import LocalUploadView from '@/views/LocalUploadView'

const project = {
  project_id: '30000000-0000-4000-8000-000000000001',
  display_name: '合成测试项目',
  status: 'ACTIVE',
  current_role: 'MEMBER',
  created_at: '2026-07-20T01:00:00Z',
}
const anotherProject = {
  ...project,
  project_id: '30000000-0000-4000-8000-000000000002',
  display_name: '另一个合成项目',
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('LocalUploadView', () => {
  it('shows each selected local video as a separate queued upload', async () => {
    stubCatalog([project])
    const user = userEvent.setup()
    renderLocalUpload()
    await selectProject(user, '合成测试项目')
    await user.upload(screen.getByLabelText('本机视频'), [
      new File(['video-a'], 'first.mp4', { type: 'video/mp4' }),
      new File(['video-b'], 'second.mp4', { type: 'video/mp4' }),
    ])
    expect(screen.getByText('first.mp4')).toBeInTheDocument()
    expect(screen.getByText('second.mp4')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '开始上传（2 个视频）' })).toBeInTheDocument()
    expect(screen.getByText(/按顺序分别上传/)).toBeInTheDocument()
  })

  it('shows browser transfer progress before platform confirmation completes', async () => {
    let finishUpload: (() => void) | undefined
    const uploadSpy = vi
      .spyOn(localUploadApi, 'uploadPlatformLocalVideo')
      .mockImplementation(async (_request, onProgress) => {
        onProgress({
          phase: 'UPLOADING',
          totalBytes: 100,
          processedBytes: 100,
          uploadedBytes: 40,
          totalParts: 1,
          completedParts: 0,
          batchId: '40000000-0000-4000-8000-000000000001',
        })
        await new Promise<void>((resolve) => {
          finishUpload = resolve
        })
        return {
          batchId: '40000000-0000-4000-8000-000000000001',
          captureSessionId: '50000000-0000-4000-8000-000000000001',
          clipAssetId: '60000000-0000-4000-8000-000000000001',
        }
      })
    stubCatalog([project])
    const user = userEvent.setup()
    renderLocalUpload()

    await selectProject(user, '合成测试项目')
    await user.upload(
      screen.getByLabelText('本机视频'),
      new File(['synthetic-video'], 'in-progress.mp4', { type: 'video/mp4' }),
    )
    await user.click(screen.getByRole('button', { name: '开始上传' }))

    expect(await screen.findByText('已传输 40%')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /正在上传…/ })).toBeDisabled()
    expect(uploadSpy).toHaveBeenCalledTimes(1)

    finishUpload?.()
    expect(await screen.findByText('已确认完成')).toBeInTheDocument()
  })

  it('separates confirmed and failed files after a partial upload result', async () => {
    vi.spyOn(localUploadApi, 'uploadPlatformLocalVideo').mockImplementation(async ({ file }) => {
      if (file.name === 'broken.mp4') {
        throw new localUploadApi.LocalUploadApiError(
          '平台校验到视频文件内容不完整，请重新选择文件后上传。',
          'UPLOAD_FILE_SHA256_MISMATCH',
        )
      }
      return {
        batchId: '40000000-0000-4000-8000-000000000001',
        captureSessionId: '50000000-0000-4000-8000-000000000001',
        clipAssetId: '60000000-0000-4000-8000-000000000001',
      }
    })
    stubCatalog([project])
    const user = userEvent.setup()
    renderLocalUpload()

    await selectProject(user, '合成测试项目')
    await user.upload(screen.getByLabelText('本机视频'), [
      new File(['broken'], 'broken.mp4', { type: 'video/mp4' }),
      new File(['confirmed'], 'confirmed.mp4', { type: 'video/mp4' }),
    ])
    await user.click(screen.getByRole('button', { name: '开始上传（2 个视频）' }))

    expect(await screen.findByText('部分上传完成')).toBeInTheDocument()
    expect(screen.getByText('平台已确认 1 / 2 个视频')).toBeInTheDocument()
    expect(
      screen.getByText('平台已确认 1 个视频，1 个文件需要重新选择后再上传。'),
    ).toBeInTheDocument()
    expect(screen.getByText('broken.mp4')).toBeInTheDocument()
    expect(screen.getByText('confirmed.mp4')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '打开配药事件' })).toHaveAttribute(
      'href',
      paths.captureGroup(project.project_id, '50000000-0000-4000-8000-000000000001'),
    )
  })

  it('uploads multiple selected files separately without inferring one event', async () => {
    const uploadSpy = vi
      .spyOn(localUploadApi, 'uploadPlatformLocalVideo')
      .mockImplementation(async ({ file }) => ({
        batchId: `batch-${file.name}`,
        captureSessionId: file.name === 'first.mp4' ? 'session-1' : 'session-2',
        clipAssetId: null,
      }))
    stubCatalog([project])
    const user = userEvent.setup()
    renderLocalUpload()

    await selectProject(user, '合成测试项目')
    await user.upload(screen.getByLabelText('本机视频'), [
      new File(['video-a'], 'first.mp4', { type: 'video/mp4' }),
      new File(['video-b'], 'second.mp4', { type: 'video/mp4' }),
    ])
    await user.click(screen.getByRole('button', { name: '开始上传（2 个视频）' }))

    expect(await screen.findByText('平台已确认 2 / 2 个视频')).toBeInTheDocument()
    expect(uploadSpy).toHaveBeenCalledTimes(2)
    expect(uploadSpy.mock.calls.map(([request]) => request.file.name)).toEqual([
      'first.mp4',
      'second.mp4',
    ])
    const eventLinks = screen.getAllByRole('link', { name: '打开配药事件' })
    expect(eventLinks).toHaveLength(2)
    expect(eventLinks[0]).toHaveAttribute(
      'href',
      paths.captureGroup(project.project_id, 'session-1'),
    )
    expect(eventLinks[1]).toHaveAttribute(
      'href',
      paths.captureGroup(project.project_id, 'session-2'),
    )
  })

  it('requires a project before local videos can be selected', async () => {
    stubCatalog([project])
    const user = userEvent.setup()
    renderLocalUpload()

    const fileInput = screen.getByLabelText('本机视频')
    expect(fileInput).toBeDisabled()
    expect(screen.getByText('请先选择所属项目，再选择本机视频。')).toBeInTheDocument()

    await selectProject(user, '合成测试项目')

    expect(fileInput).not.toBeDisabled()
    expect(screen.getByText(/平台会按顺序分别上传/)).toBeInTheDocument()
    const captureGroupLink = screen.getByRole('link', { name: '进入配药事件' })
    expect(captureGroupLink).toHaveAttribute('href', paths.captureGroups(project.project_id))
    expect(screen.queryByRole('link', { name: '查看项目视频' })).not.toBeInTheDocument()
  })

  it('uses current event terminology for the confirmed event identity', async () => {
    let finishUpload: (() => void) | undefined
    vi.spyOn(localUploadApi, 'uploadPlatformLocalVideo').mockImplementation(
      async (_request, onProgress) => {
        onProgress({
          phase: 'ASSEMBLING',
          totalBytes: 100,
          processedBytes: 100,
          uploadedBytes: 100,
          totalParts: 1,
          completedParts: 1,
          batchId: '40000000-0000-4000-8000-000000000001',
        })
        await new Promise<void>((resolve) => {
          finishUpload = resolve
        })
        return {
          batchId: '40000000-0000-4000-8000-000000000001',
          captureSessionId: '50000000-0000-4000-8000-000000000001',
          clipAssetId: '60000000-0000-4000-8000-000000000001',
        }
      },
    )
    stubCatalog([project])
    const user = userEvent.setup()
    renderLocalUpload()

    await selectProject(user, '合成测试项目')
    await user.upload(
      screen.getByLabelText('本机视频'),
      new File(['synthetic-video'], 'confirmed.mp4', { type: 'video/mp4' }),
    )
    await user.click(screen.getByRole('button', { name: '开始上传' }))

    expect(await screen.findAllByText('正在建立单机位配药事件记录')).not.toHaveLength(0)
    finishUpload?.()
    expect(await screen.findByText('已确认完成')).toBeInTheDocument()
    await user.click(screen.getByText('查看技术信息'))
    expect(screen.getByText('配药事件 ID')).toBeInTheDocument()
    expect(screen.queryByText('配药操作 ID')).not.toBeInTheDocument()
  })

  it('clears a project that is no longer authorized after refresh', async () => {
    let authorized = true
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((input: RequestInfo | URL) =>
          Promise.resolve(
            jsonResponse(
              String(input) === '/api/v1/projects'
                ? { items: authorized ? [project] : [] }
                : { items: [] },
            ),
          ),
        ),
    )
    const user = userEvent.setup()
    renderLocalUpload()
    await selectProject(user, '合成测试项目')
    authorized = false
    await user.click(screen.getByRole('button', { name: /刷新项目/ }))
    expect(await screen.findByText(/当前账号尚未加入项目/)).toBeInTheDocument()
    expect(screen.queryByText('查看项目视频')).not.toBeInTheDocument()
  })

  it('does not allow a finished upload batch to be submitted again', async () => {
    stubCatalog([project, anotherProject])
    const user = userEvent.setup()
    renderLocalUpload()
    await selectProject(user, '合成测试项目')
    await user.upload(
      screen.getByLabelText('本机视频'),
      new File([], 'empty.mp4', { type: 'video/mp4' }),
    )
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    expect(await screen.findByText('本轮上传失败')).toBeInTheDocument()
    const submit = screen.getByRole('button', { name: /重新选择视频后上传/ })
    expect(submit).toBeDisabled()
    await selectProject(user, '另一个合成项目')
    await waitFor(() => expect(screen.queryByText('empty.mp4')).not.toBeInTheDocument())
    expect(screen.queryByText('本轮上传失败')).not.toBeInTheDocument()
  })

  it('shows platform-confirmed completion and links to the created medication event', async () => {
    vi.spyOn(localUploadApi, 'uploadPlatformLocalVideo').mockResolvedValue({
      batchId: '40000000-0000-4000-8000-000000000001',
      captureSessionId: '50000000-0000-4000-8000-000000000001',
      clipAssetId: '60000000-0000-4000-8000-000000000001',
    })
    stubCatalog([project])
    const user = userEvent.setup()
    renderLocalUpload()

    await selectProject(user, '合成测试项目')
    await user.upload(
      screen.getByLabelText('本机视频'),
      new File(['synthetic-video'], 'confirmed.mp4', { type: 'video/mp4' }),
    )
    await user.click(screen.getByRole('button', { name: '开始上传' }))

    expect(await screen.findByText('已确认完成')).toBeInTheDocument()
    expect(screen.getByText(/平台已确认上传完成/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '打开配药事件' })).toHaveAttribute(
      'href',
      paths.captureGroup(project.project_id, '50000000-0000-4000-8000-000000000001'),
    )
  })

  it('keeps the active upload running when changing routes', async () => {
    let activeSignal: AbortSignal | undefined
    const uploadSpy = vi
      .spyOn(localUploadApi, 'uploadPlatformLocalVideo')
      .mockImplementation((_request, _onProgress, signal) => {
        activeSignal = signal
        return new Promise((_resolve, reject) => {
          signal?.addEventListener(
            'abort',
            () => reject(new DOMException('The upload was cancelled.', 'AbortError')),
            { once: true },
          )
        })
      })
    stubCatalog([project])
    const user = userEvent.setup()
    const view = renderRouter(
      [
        {
          path: '/',
          element: <AppShell />,
          children: [
            { path: 'uploads/new', element: <LocalUploadView /> },
            { path: 'projects', element: <div>其他页面</div> },
          ],
        },
      ],
      ['/uploads/new'],
      { username: 'admin', role: 'ADMIN' },
    )

    await selectProject(user, '合成测试项目')
    await user.upload(
      screen.getByLabelText('本机视频'),
      new File(['synthetic-video'], 'synthetic.mp4', { type: 'video/mp4' }),
    )
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    await waitFor(() => expect(uploadSpy).toHaveBeenCalledTimes(1))

    await view.router.navigate('/projects')
    expect(await screen.findByText('其他页面')).toBeInTheDocument()
    expect(activeSignal?.aborted).toBe(false)
    expect(screen.getByRole('link', { name: '查看上传进度' })).toBeInTheDocument()

    await view.router.navigate('/uploads/new')
    expect(await screen.findByRole('button', { name: /正在上传…/ })).toBeDisabled()

    view.unmount()

    expect(activeSignal?.aborted).toBe(true)
  })
})

function renderLocalUpload() {
  return renderRoute(
    <LocalUploadProvider>
      <LocalUploadView />
    </LocalUploadProvider>,
  )
}

async function selectProject(user: ReturnType<typeof userEvent.setup>, name: string) {
  const select = await screen.findByRole('combobox', { name: '所属项目' })
  await waitFor(() => expect(select).not.toBeDisabled())
  await user.click(select)
  await user.click(await screen.findByText(new RegExp(name)))
}
function stubCatalog(projects: object[]) {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockImplementation((input: RequestInfo | URL) =>
        Promise.resolve(
          jsonResponse(String(input) === '/api/v1/projects' ? { items: projects } : { items: [] }),
        ),
      ),
  )
}
function jsonResponse(body: object) {
  return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
}
