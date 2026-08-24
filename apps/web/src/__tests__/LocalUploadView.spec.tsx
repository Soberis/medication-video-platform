import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import * as localUploadApi from '@/api/local-upload'
import { paths } from '@/app/paths'
import { renderRoute } from '@/test/render'
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
    renderRoute(<LocalUploadView />)
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

  it('requires a project before local videos can be selected', async () => {
    stubCatalog([project])
    const user = userEvent.setup()
    renderRoute(<LocalUploadView />)

    const fileInput = screen.getByLabelText('本机视频')
    expect(fileInput).toBeDisabled()
    expect(screen.getByText('请先选择所属项目，再选择本机视频。')).toBeInTheDocument()

    await selectProject(user, '合成测试项目')

    expect(fileInput).not.toBeDisabled()
    expect(screen.getByText(/平台会按顺序分别上传/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看项目视频' })).toHaveAttribute(
      'href',
      `/projects/${project.project_id}/videos`,
    )
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
    renderRoute(<LocalUploadView />)
    await selectProject(user, '合成测试项目')
    authorized = false
    await user.click(screen.getByRole('button', { name: /刷新项目/ }))
    expect(await screen.findByText(/当前账号尚未加入项目/)).toBeInTheDocument()
    expect(screen.queryByText('查看项目视频')).not.toBeInTheDocument()
  })

  it('does not allow a finished upload batch to be submitted again', async () => {
    stubCatalog([project, anotherProject])
    const user = userEvent.setup()
    renderRoute(<LocalUploadView />)
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

  it('shows platform-confirmed completion and links to the created video asset', async () => {
    vi.spyOn(localUploadApi, 'uploadPlatformLocalVideo').mockResolvedValue({
      batchId: '40000000-0000-4000-8000-000000000001',
      captureSessionId: '50000000-0000-4000-8000-000000000001',
      clipAssetId: '60000000-0000-4000-8000-000000000001',
    })
    stubCatalog([project])
    const user = userEvent.setup()
    renderRoute(<LocalUploadView />)

    await selectProject(user, '合成测试项目')
    await user.upload(
      screen.getByLabelText('本机视频'),
      new File(['synthetic-video'], 'confirmed.mp4', { type: 'video/mp4' }),
    )
    await user.click(screen.getByRole('button', { name: '开始上传' }))

    expect(await screen.findByText('已确认完成')).toBeInTheDocument()
    expect(screen.getByText(/平台已确认上传完成/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看视频资产' })).toHaveAttribute(
      'href',
      paths.asset(project.project_id, '60000000-0000-4000-8000-000000000001'),
    )
  })

  it('aborts the active hash or upload request when leaving the page', async () => {
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
    const view = renderRoute(<LocalUploadView />)

    await selectProject(user, '合成测试项目')
    await user.upload(
      screen.getByLabelText('本机视频'),
      new File(['synthetic-video'], 'synthetic.mp4', { type: 'video/mp4' }),
    )
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    await waitFor(() => expect(uploadSpy).toHaveBeenCalledTimes(1))

    view.unmount()

    expect(activeSignal?.aborted).toBe(true)
  })
})

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
