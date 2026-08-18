import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { DatasetExport, DatasetVersionDetail } from '@/api/catalog'
import { clearDatasetDraftIntent, getDatasetDraftIntent } from '@/app/dataset-draft-intent'
import { renderRoute } from '@/test/render'
import AssetDetailView from '@/views/AssetDetailView'
import DatasetVersionDetailView from '@/views/DatasetVersionDetailView'
import SessionDetailView from '@/views/SessionDetailView'
import SessionListView from '@/views/SessionListView'

const projectId = '30000000-0000-4000-8000-000000000001'
const sessionId = '40000000-0000-4000-8000-000000000001'
const assetId = '50000000-0000-4000-8000-000000000001'

afterEach(() => {
  vi.useRealTimers()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

describe('catalog views', () => {
  it('distinguishes bound, ingested and pending quality facts in the session list', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: RequestInfo | URL) =>
        Promise.resolve(
          String(input) === `/api/v1/projects/${projectId}`
            ? jsonResponse(project('ADMIN'))
            : jsonResponse({
                items: [
                  {
                    ...captureSessionListItem(sessionId),
                    expected_camera_count: 2,
                    bound_stream_count: 1,
                    received_camera_count: 0,
                    assembly_status: 'ASSEMBLING',
                    quality_status: 'PENDING',
                  },
                ],
                next_cursor: null,
              }),
        ),
      ),
    )
    renderSessionList()
    expect(await screen.findByText('已绑定 1 / 2')).toBeInTheDocument()
    expect(screen.getByText('已入库 0')).toBeInTheDocument()
    expect(screen.getByText('待检测')).toBeInTheDocument()
    expect(screen.getByText('组装中')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining(`/api/v1/projects/${projectId}/capture-sessions`),
      expect.any(Object),
    )
  }, 15_000)

  it('lets an administrator create one draft from selected sessions in list order', async () => {
    const firstSessionId = '40000000-0000-4000-8000-000000000011'
    const secondSessionId = '40000000-0000-4000-8000-000000000012'
    const datasetVersionId = '91000000-0000-4000-8000-000000000010'
    let createBody: unknown
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === `/api/v1/projects/${projectId}`)
        return Promise.resolve(jsonResponse(project('ADMIN')))
      if (url === `/api/v1/projects/${projectId}/dataset-versions` && init?.method === 'POST') {
        createBody = JSON.parse(String(init.body))
        return Promise.resolve(
          jsonResponse(
            {
              dataset_version_id: datasetVersionId,
              project_id: projectId,
              display_name: '两条配药操作候选',
              status: 'DRAFT',
              created_by: 'local-admin',
              created_at: '2026-07-28T03:00:00Z',
            },
            201,
          ),
        )
      }
      return Promise.resolve(
        jsonResponse({
          items: [
            captureSessionListItem(firstSessionId, 'synthetic-edge-01'),
            captureSessionListItem(secondSessionId, 'synthetic-edge-02'),
          ],
          next_cursor: null,
        }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderSessionList()

    expect(await screen.findByText('已选择 0 条配药操作')).toBeInTheDocument()
    const checkboxes = await screen.findAllByRole('checkbox')
    expect(checkboxes).toHaveLength(3)
    const firstSessionCheckbox = checkboxes[1]
    const secondSessionCheckbox = checkboxes[2]
    if (!firstSessionCheckbox || !secondSessionCheckbox) {
      throw new Error('Expected one checkbox for each listed capture session.')
    }
    await user.click(secondSessionCheckbox)
    await user.click(firstSessionCheckbox)
    expect(screen.getByText('已选择 2 条配药操作')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '创建数据集草稿' }))
    await user.clear(screen.getByLabelText('草稿名称'))
    await user.type(screen.getByLabelText('草稿名称'), '两条配药操作候选')
    await user.click(screen.getByRole('button', { name: '创建草稿' }))

    await waitFor(() =>
      expect(createBody).toEqual({
        display_name: '两条配药操作候选',
        capture_session_ids: [firstSessionId, secondSessionId],
      }),
    )
  })

  it('does not expose multi-session draft selection to a project member', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: RequestInfo | URL) =>
        Promise.resolve(
          String(input) === `/api/v1/projects/${projectId}`
            ? jsonResponse(project('MEMBER'))
            : jsonResponse({
                items: [captureSessionListItem(sessionId)],
                next_cursor: null,
              }),
        ),
      ),
    )

    renderSessionList()

    expect(await screen.findByText('synthetic-edge-01')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建数据集草稿' })).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('keeps the session list usable and retries when project role lookup fails', async () => {
    let roleRequestFails = true
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input)
        if (url === `/api/v1/projects/${projectId}`) {
          return Promise.resolve(
            roleRequestFails
              ? jsonResponse(
                  {
                    error_code: 'PROJECT_ROLE_UNAVAILABLE',
                    error_message: 'Synthetic failure.',
                    retryable: true,
                  },
                  503,
                )
              : jsonResponse(project('ADMIN')),
          )
        }
        return Promise.resolve(
          jsonResponse({
            items: [captureSessionListItem(sessionId)],
            next_cursor: null,
          }),
        )
      }),
    )
    const user = userEvent.setup()

    renderSessionList()

    expect(await screen.findByText('synthetic-edge-01')).toBeInTheDocument()
    expect(screen.getByText('暂时无法核对项目管理员权限，无法创建数据集草稿。')).toBeInTheDocument()
    expect(screen.getByText('PROJECT_ROLE_UNAVAILABLE')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建数据集草稿' })).not.toBeInTheDocument()

    roleRequestFails = false
    await user.click(screen.getByRole('button', { name: '重试权限核对' }))

    expect(await screen.findByText('已选择 0 条配药操作')).toBeInTheDocument()
  })

  it('does not navigate back to an old project when its draft request resolves after a route change', async () => {
    const otherProjectId = '30000000-0000-4000-8000-000000000002'
    const datasetVersionId = '91000000-0000-4000-8000-000000000020'
    let resolveDraftRequest: ((response: Response) => void) | undefined
    let draftRequestSignal: AbortSignal | undefined
    let draftIdempotencyKey: string | undefined
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === `/api/v1/projects/${projectId}/dataset-versions` && init?.method === 'POST') {
        draftRequestSignal = init.signal as AbortSignal | undefined
        draftIdempotencyKey = (init.headers as Record<string, string>)['Idempotency-Key']
        return new Promise<Response>((resolve) => {
          resolveDraftRequest = resolve
        })
      }
      if (url === `/api/v1/projects/${projectId}` || url === `/api/v1/projects/${otherProjectId}`) {
        return Promise.resolve(jsonResponse(project('ADMIN')))
      }
      return Promise.resolve(
        jsonResponse({
          items: [captureSessionListItem(sessionId)],
          next_cursor: null,
        }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    const { router } = renderSessionList()
    expect(await screen.findByText('已选择 0 条配药操作')).toBeInTheDocument()
    const sessionCheckbox = (await screen.findAllByRole('checkbox'))[1]
    if (!sessionCheckbox) throw new Error('Expected a checkbox for the listed capture session.')
    await user.click(sessionCheckbox)
    await user.click(screen.getByRole('button', { name: '创建数据集草稿' }))
    await user.click(screen.getByRole('button', { name: '创建草稿' }))
    await waitFor(() => expect(resolveDraftRequest).toBeTypeOf('function'))

    await router.navigate(`/projects/${otherProjectId}/sessions`)
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining(`/api/v1/projects/${otherProjectId}/capture-sessions`),
        expect.any(Object),
      ),
    )
    expect(draftRequestSignal?.aborted).toBe(false)
    resolveDraftRequest?.(
      jsonResponse(
        {
          dataset_version_id: datasetVersionId,
          project_id: projectId,
          display_name: '旧项目草稿',
          status: 'DRAFT',
          created_by: 'local-admin',
          created_at: '2026-07-28T03:00:00Z',
        },
        201,
      ),
    )

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(`/projects/${otherProjectId}/sessions`),
    )
    const pendingIntent = getDatasetDraftIntent(projectId, '配药操作训练候选', [sessionId])
    expect(pendingIntent.idempotencyKey).toBe(draftIdempotencyKey)
    clearDatasetDraftIntent(pendingIntent)
  })

  it('shows a concise, evidence-based dataset readiness preview on the session detail page', async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) =>
      Promise.resolve(
        String(input).endsWith('/dataset-admission-preview')
          ? jsonResponse({
              capture_session_id: sessionId,
              eligible: false,
              assembly_status: 'COMPLETE',
              quality_status: 'PENDING',
              expected_camera_count: 1,
              received_camera_count: 1,
              blocking_reasons: ['SESSION_QUALITY_NOT_PASS'],
              streams: [
                {
                  camera_stream_id: '41000000-0000-4000-8000-000000000001',
                  clip_asset_id: assetId,
                  camera_index: 0,
                  eligible: false,
                  stream_status: 'INGESTED',
                  stream_quality_status: 'PENDING',
                  asset_status: 'INGESTED',
                  asset_deleted: false,
                  quality_review_decision: 'UNREVIEWED',
                  annotation_revision_id: null,
                  annotation_review_decision: 'UNREVIEWED',
                  post_cleaning_decision: 'MISSING',
                  annotation_document_sha256: null,
                  label_schema_digest: null,
                  blocking_reasons: ['QUALITY_REVIEW_NOT_QUALIFIED', 'ANNOTATION_REVISION_MISSING'],
                },
              ],
            })
          : jsonResponse(captureSessionDetail()),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    renderSessionDetail()
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('配药操作详情')
    expect(screen.getAllByText('synthetic-edge-01').length).toBeGreaterThan(0)
    expect(screen.getByText('训练数据准备度')).toBeInTheDocument()
    expect(screen.getByText('尚未准备完成')).toBeInTheDocument()
    expect(screen.getByText('本次配药操作的技术检测尚未通过')).toBeInTheDocument()
    expect(screen.getByText('有视频尚未通过人工质量判断')).toBeInTheDocument()
    expect(screen.getByText('有视频尚未提交人工配药动作标注')).toBeInTheDocument()
    expect(document.querySelectorAll('.detail-card')).toHaveLength(4)
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-sessions/${sessionId}`,
      expect.any(Object),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-sessions/${sessionId}/dataset-admission-preview`,
      expect.any(Object),
    )
  })

  it('does not offer dataset-draft creation to a project member', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input)
        if (url === `/api/v1/projects/${projectId}`)
          return Promise.resolve(jsonResponse(project('MEMBER')))
        if (url.endsWith('/dataset-admission-preview')) {
          return Promise.resolve(
            jsonResponse({
              capture_session_id: sessionId,
              eligible: true,
              assembly_status: 'COMPLETE',
              quality_status: 'PASS',
              expected_camera_count: 1,
              received_camera_count: 1,
              blocking_reasons: [],
              streams: [
                {
                  camera_stream_id: '41000000-0000-4000-8000-000000000001',
                  clip_asset_id: assetId,
                  camera_index: 0,
                  eligible: true,
                  stream_status: 'INGESTED',
                  stream_quality_status: 'PASS',
                  asset_status: 'INGESTED',
                  asset_deleted: false,
                  quality_review_decision: 'QUALIFIED',
                  annotation_revision_id: '42000000-0000-4000-8000-000000000001',
                  annotation_review_decision: 'APPROVED',
                  post_cleaning_decision: 'PASS',
                  annotation_document_sha256: 'a'.repeat(64),
                  label_schema_digest: 'b'.repeat(64),
                  blocking_reasons: [],
                },
              ],
            }),
          )
        }
        return Promise.resolve(jsonResponse(captureSessionDetail()))
      }),
    )

    renderSessionDetail()

    expect(await screen.findByText('当前证据满足训练数据准入条件')).toBeInTheDocument()
    expect(screen.getByText('当前项目管理员可据此创建候选草稿。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建数据集草稿' })).not.toBeInTheDocument()
  })

  it('keeps readiness facts and lets an administrator retry a failed project role lookup', async () => {
    let roleRequestFails = true
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === `/api/v1/projects/${projectId}`) {
        return Promise.resolve(
          roleRequestFails
            ? jsonResponse(
                {
                  error_code: 'PROJECT_ROLE_UNAVAILABLE',
                  error_message: 'Synthetic failure.',
                  retryable: true,
                },
                503,
              )
            : jsonResponse(project('ADMIN')),
        )
      }
      if (url.endsWith('/dataset-admission-preview')) {
        return Promise.resolve(jsonResponse(eligibleAdmissionPreview()))
      }
      return Promise.resolve(jsonResponse(captureSessionDetail()))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderSessionDetail()

    expect(await screen.findByText('当前证据满足训练数据准入条件')).toBeInTheDocument()
    expect(screen.getByText('项目权限暂时无法核对，暂不提供创建草稿操作。')).toBeInTheDocument()
    expect(screen.getByText('PROJECT_ROLE_UNAVAILABLE')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建数据集草稿' })).not.toBeInTheDocument()

    roleRequestFails = false
    await user.click(screen.getByRole('button', { name: '重试权限核对' }))

    expect(await screen.findByRole('button', { name: '创建数据集草稿' })).toBeInTheDocument()
    expect(screen.queryByText('PROJECT_ROLE_UNAVAILABLE')).not.toBeInTheDocument()
  })

  it('shows the persisted draft evidence rather than current media URLs', async () => {
    const datasetVersionId = '91000000-0000-4000-8000-000000000001'
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          dataset_version_id: datasetVersionId,
          project_id: projectId,
          display_name: '配药操作训练候选',
          status: 'DRAFT',
          created_by: 'local-admin',
          created_at: '2026-07-28T03:00:00Z',
          split_plan: null,
          can_create_split_plan: false,
          build_specification: null,
          can_create_build_specification: false,
          members: [
            {
              capture_session_id: sessionId,
              candidate_index: 0,
              expected_camera_count: 1,
              received_camera_count: 1,
              assembly_status: 'COMPLETE',
              quality_status: 'PASS',
              streams: [
                {
                  camera_stream_id: '42000000-0000-4000-8000-000000000001',
                  clip_asset_id: assetId,
                  camera_index: 0,
                  asset_sha256: 'a'.repeat(64),
                  quality_review_decision: 'QUALIFIED',
                  quality_review_version: 2,
                  annotation_revision_id: '43000000-0000-4000-8000-000000000001',
                  annotation_document_sha256: 'b'.repeat(64),
                  label_schema_digest: 'c'.repeat(64),
                  cleaning_result_id: '44000000-0000-4000-8000-000000000001',
                  cleaning_run_id: '45000000-0000-4000-8000-000000000001',
                  cleaning_rule_set_digest: 'd'.repeat(64),
                  post_cleaning_outcome: 'PASS',
                },
              ],
            },
          ],
        }),
      ),
    )

    renderDatasetDetail(datasetVersionId)

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('配药操作训练候选')
    expect(screen.getByText('候选配药操作')).toBeInTheDocument()
    expect(screen.getByText('不可变候选快照')).toBeInTheDocument()
    expect(screen.getByText('1 / 1 路')).toBeInTheDocument()
    expect(document.body.innerHTML).not.toContain('media_url')
  })

  it('retries one immutable split command with the same idempotency key and renders the fixed plan', async () => {
    const datasetVersionId = '91000000-0000-4000-8000-000000000001'
    const secondSessionId = '41000000-0000-4000-8000-000000000002'
    const splitPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/split-plans`
    const detail = datasetVersionDetail(datasetVersionId, '配药操作训练候选')
    detail.can_create_split_plan = true
    detail.members.push({
      ...detail.members[0]!,
      capture_session_id: secondSessionId,
      candidate_index: 1,
      streams: [],
    })
    let splitAttempts = 0
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === splitPath) {
        splitAttempts += 1
        if (splitAttempts === 1) {
          return Promise.resolve(
            jsonResponse(
              {
                error_code: 'DATASET_SPLIT_PLAN_TEMPORARILY_UNAVAILABLE',
                retryable: true,
                request_id: 'request-dataset-split-0001',
              },
              503,
            ),
          )
        }
        return Promise.resolve(
          jsonResponse(
            {
              dataset_split_plan_id: '92000000-0000-4000-8000-000000000001',
              dataset_version_id: datasetVersionId,
              policy_version: 'CAPTURE_SESSION_HASH_RANK_V1',
              split_seed: 20260729,
              pilot_count: 1,
              holdout_count: 1,
              created_by: 'local-admin',
              created_at: '2026-07-29T03:00:00Z',
              assignments: [
                {
                  capture_session_id: sessionId,
                  candidate_index: 0,
                  leakage_group_type: 'CAPTURE_SESSION',
                  leakage_group_id: sessionId,
                  split: 'PILOT',
                  assignment_rank: 1,
                },
                {
                  capture_session_id: secondSessionId,
                  candidate_index: 1,
                  leakage_group_type: 'CAPTURE_SESSION',
                  leakage_group_id: secondSessionId,
                  split: 'HOLDOUT',
                  assignment_rank: 0,
                },
              ],
            },
            201,
          ),
        )
      }
      return Promise.resolve(jsonResponse(detail))
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('dataset-split-plan-0001') })
    const user = userEvent.setup()

    renderDatasetDetail(datasetVersionId)

    expect(await screen.findByRole('button', { name: '固定划分' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '固定划分' }))
    const confirmDialog = await screen.findByRole('dialog')
    await user.click(within(confirmDialog).getByRole('button', { name: '固定划分' }))

    expect(
      await screen.findByText('DATASET_SPLIT_PLAN_TEMPORARILY_UNAVAILABLE'),
    ).toBeInTheDocument()
    expect(screen.getByText('请求标识：request-dataset-split-0001')).toBeInTheDocument()

    fireEvent.click(within(confirmDialog).getByRole('button', { name: /固定划分/ }))

    expect(await screen.findByText('划分种子')).toBeInTheDocument()
    expect(screen.getByText('20260729')).toBeInTheDocument()
    expect(screen.getAllByText('试用组')).not.toHaveLength(0)
    expect(screen.getAllByText('留出组')).not.toHaveLength(0)
    expect(screen.queryByText('固定当前划分')).not.toBeInTheDocument()
    const splitCalls = fetchMock.mock.calls.filter(([input]) => String(input) === splitPath)
    expect(splitCalls).toHaveLength(2)
    for (const [, init] of splitCalls) {
      expect(init).toEqual(
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ holdout_count: 1 }),
          headers: expect.objectContaining({ 'Idempotency-Key': 'dataset-split-plan-0001' }),
        }),
      )
    }
  })

  it('checks current admission only after an explicit action and explains changed evidence', async () => {
    const datasetVersionId = '91000000-0000-4000-8000-000000000001'
    const currentAdmissionPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/current-admission`
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === currentAdmissionPath) {
        return Promise.resolve(
          jsonResponse({
            dataset_version_id: datasetVersionId,
            all_members_currently_admission_eligible: false,
            build_readiness: {
              ready_for_build: false,
              blocking_reasons: [
                'SELECTION_RULE_NOT_FIXED',
                'LABEL_SCHEMA_NOT_FIXED',
                'CLEANING_RULE_SET_NOT_FIXED',
                'SPLIT_PLAN_NOT_FIXED',
                'EXPORT_PROFILE_NOT_FIXED',
                'CURRENT_ADMISSION_NOT_ELIGIBLE',
                'SNAPSHOT_EVIDENCE_CHANGED',
              ],
              immutable_evidence_still_current: false,
              uniform_label_schema_digest: 'c'.repeat(64),
              uniform_cleaning_rule_set_digest: 'd'.repeat(64),
              dataset_split_plan_id: null,
              build_specification: null,
            },
            members: [
              {
                capture_session_id: sessionId,
                candidate_index: 0,
                current_admission: {
                  capture_session_id: sessionId,
                  eligible: false,
                  assembly_status: 'COMPLETE',
                  quality_status: 'PENDING',
                  expected_camera_count: 1,
                  received_camera_count: 1,
                  blocking_reasons: ['SESSION_QUALITY_NOT_PASS'],
                  streams: [
                    {
                      camera_stream_id: '42000000-0000-4000-8000-000000000001',
                      clip_asset_id: assetId,
                      camera_index: 0,
                      eligible: false,
                      stream_status: 'INGESTED',
                      stream_quality_status: 'PENDING',
                      asset_status: 'INGESTED',
                      asset_deleted: false,
                      quality_review_decision: 'UNREVIEWED',
                      annotation_revision_id: '43000000-0000-4000-8000-000000000001',
                      annotation_review_decision: 'APPROVED',
                      post_cleaning_decision: 'PASS',
                      annotation_document_sha256: 'a'.repeat(64),
                      label_schema_digest: 'b'.repeat(64),
                      blocking_reasons: ['QUALITY_REVIEW_NOT_QUALIFIED'],
                    },
                  ],
                },
                evidence_changes: [
                  {
                    scope: 'SESSION',
                    camera_stream_id: null,
                    change_codes: ['SESSION_QUALITY_STATUS_CHANGED'],
                  },
                  {
                    scope: 'STREAM',
                    camera_stream_id: '42000000-0000-4000-8000-000000000001',
                    change_codes: ['STREAM_POST_CLEANING_OUTCOME_CHANGED', 'UNKNOWN_CHANGE_CODE'],
                  },
                ],
              },
            ],
          }),
        )
      }
      return Promise.resolve(
        jsonResponse(datasetVersionDetail(datasetVersionId, '配药操作训练候选')),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderDatasetDetail(datasetVersionId)

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('配药操作训练候选')
    expect(fetchMock).not.toHaveBeenCalledWith(currentAdmissionPath, expect.any(Object))
    expect(screen.getByText('尚未检查构建准备。')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '检查构建准备' }))

    expect(await screen.findByText('尚未具备开始构建条件')).toBeInTheDocument()
    expect(screen.getByText('尚未固定候选选择规则')).toBeInTheDocument()
    expect(screen.getByText('尚未固定必需导出配置')).toBeInTheDocument()
    expect(await screen.findByText('当前有阻断')).toBeInTheDocument()
    expect(screen.getByText('本次配药操作的技术检测尚未通过')).toBeInTheDocument()
    expect(screen.getByText('有视频尚未通过人工质量判断')).toBeInTheDocument()
    expect(screen.getByText(/技术检测状态已变化/)).toBeInTheDocument()
    expect(screen.getByText(/后置清洗结论已变化/)).toBeInTheDocument()
    expect(screen.getByText(/UNKNOWN_CHANGE_CODE/)).toBeInTheDocument()
    expect(screen.getByText(/通过本检查也不代表已经冻结或可以导出/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /拆分|冻结|导出/ })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      currentAdmissionPath,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('keeps the immutable snapshot visible and retries a failed current-admission check', async () => {
    const datasetVersionId = '91000000-0000-4000-8000-000000000001'
    const currentAdmissionPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/current-admission`
    let currentAdmissionAttempts = 0
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === currentAdmissionPath) {
        currentAdmissionAttempts += 1
        return Promise.resolve(
          currentAdmissionAttempts === 1
            ? jsonResponse(
                {
                  error_code: 'DATASET_VERSION_CURRENT_ADMISSION_UNAVAILABLE',
                  retryable: true,
                  request_id: 'request-current-admission-0001',
                },
                503,
              )
            : jsonResponse({
                dataset_version_id: datasetVersionId,
                all_members_currently_admission_eligible: true,
                build_readiness: {
                  ready_for_build: false,
                  blocking_reasons: ['SELECTION_RULE_NOT_FIXED', 'EXPORT_PROFILE_NOT_FIXED'],
                  immutable_evidence_still_current: true,
                  uniform_label_schema_digest: 'c'.repeat(64),
                  uniform_cleaning_rule_set_digest: 'd'.repeat(64),
                  dataset_split_plan_id: '92000000-0000-4000-8000-000000000001',
                  build_specification: null,
                },
                members: [],
              }),
        )
      }
      return Promise.resolve(
        jsonResponse(datasetVersionDetail(datasetVersionId, '配药操作训练候选')),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderDatasetDetail(datasetVersionId)

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('配药操作训练候选')
    await user.click(screen.getByRole('button', { name: '检查构建准备' }))

    expect(
      await screen.findByText('DATASET_VERSION_CURRENT_ADMISSION_UNAVAILABLE'),
    ).toBeInTheDocument()
    expect(screen.getByText('请求标识：request-current-admission-0001')).toBeInTheDocument()
    expect(screen.getByText('候选配药操作')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /重\s*试/ }))

    expect(await screen.findByText('尚未具备开始构建条件')).toBeInTheDocument()
    expect(currentAdmissionAttempts).toBe(2)
  })

  it('fixes the minimum build configuration only after explicit confirmation', async () => {
    const datasetVersionId = '91000000-0000-4000-8000-000000000001'
    const buildPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/build-specifications`
    const detail = datasetVersionDetail(datasetVersionId, '配药操作训练候选')
    detail.split_plan = {
      dataset_split_plan_id: '92000000-0000-4000-8000-000000000001',
      dataset_version_id: datasetVersionId,
      policy_version: 'CAPTURE_SESSION_HASH_RANK_V1',
      split_seed: 20260729,
      pilot_count: 1,
      holdout_count: 1,
      created_by: 'local-admin',
      created_at: '2026-07-29T03:00:00Z',
      assignments: [],
    }
    detail.can_create_build_specification = true
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === buildPath) {
        return Promise.resolve(
          jsonResponse(
            {
              dataset_build_specification_id: '93000000-0000-4000-8000-000000000001',
              dataset_version_id: datasetVersionId,
              selection_rule_version: 'EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1',
              label_schema_digest: 'c'.repeat(64),
              cleaning_rule_set_digest: 'd'.repeat(64),
              dataset_split_plan_id: detail.split_plan?.dataset_split_plan_id,
              export_profile_key: 'p0-dataset-export-v2',
              export_profile_digest: 'e'.repeat(64),
              created_by: 'local-admin',
              created_at: '2026-07-29T04:00:00Z',
            },
            201,
          ),
        )
      }
      return Promise.resolve(jsonResponse(detail))
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('dataset-build-spec-0001') })
    const user = userEvent.setup()

    renderDatasetDetail(datasetVersionId)

    expect(await screen.findByRole('button', { name: '固定构建配置' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '固定构建配置' }))
    const confirmDialog = await screen.findByRole('dialog')
    expect(within(confirmDialog).getByText(/固定后如需调整，应新建草稿/)).toBeInTheDocument()
    await user.click(within(confirmDialog).getByRole('button', { name: '固定构建配置' }))

    expect(await screen.findByText('P0 训练包')).toBeInTheDocument()
    expect(screen.getByText('查看构建配置标识')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '固定构建配置' })).not.toBeInTheDocument()
    expect(screen.getByText('尚未检查构建准备。')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      buildPath,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({ 'Idempotency-Key': 'dataset-build-spec-0001' }),
      }),
    )
  })

  it('starts, seals and validates a ready build only after separate confirmations', async () => {
    const datasetVersionId = '91000000-0000-4000-8000-000000000001'
    const currentAdmissionPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/current-admission`
    const buildPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/build`
    const snapshotPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/build-snapshots`
    const validationPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/validations`
    const detail = datasetVersionDetail(datasetVersionId, '配药操作训练候选')
    const buildSpecification = {
      dataset_build_specification_id: '93000000-0000-4000-8000-000000000001',
      dataset_version_id: datasetVersionId,
      selection_rule_version: 'EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1' as const,
      label_schema_digest: 'c'.repeat(64),
      cleaning_rule_set_digest: 'd'.repeat(64),
      dataset_split_plan_id: '92000000-0000-4000-8000-000000000001',
      export_profile_key: 'p0-dataset-export-v2' as const,
      export_profile_digest: 'e'.repeat(64),
      created_by: 'local-admin',
      created_at: '2026-07-29T04:00:00Z',
    }
    detail.build_specification = buildSpecification
    const buildSnapshot = {
      dataset_build_snapshot_id: '94000000-0000-4000-8000-000000000001',
      dataset_version_id: datasetVersionId,
      dataset_build_specification_id: buildSpecification.dataset_build_specification_id,
      dataset_split_plan_id: buildSpecification.dataset_split_plan_id,
      snapshot_schema_version: 'DATASET_RELATIONAL_SNAPSHOT_V1' as const,
      input_variant_policy: 'RAW_CLIP_ASSET_V1' as const,
      member_count: 1,
      stream_count: 1,
      snapshot_digest: 'f'.repeat(64),
      created_by: 'local-admin',
      created_at: '2026-07-29T06:00:00Z',
    }
    const validation = {
      dataset_version_validation_id: '95000000-0000-4000-8000-000000000001',
      dataset_version_id: datasetVersionId,
      dataset_build_snapshot_id: buildSnapshot.dataset_build_snapshot_id,
      validation_profile_version: 'DATASET_FREEZE_VALIDATION_V1' as const,
      outcome: 'PASS' as const,
      reason_codes: [],
      executed_checks: [
        'CURRENT_ADMISSION',
        'REFERENCE_INTEGRITY',
        'SNAPSHOT_DIGEST',
        'SPLIT_ISOLATION',
        'BUILD_CONTRACT',
      ] as const,
      validated_snapshot_digest: buildSnapshot.snapshot_digest,
      validated_by: 'local-admin',
      validated_at: '2026-07-30T01:00:00Z',
    }
    const freezeApproval = {
      approval_request_id: '96000000-0000-4000-8000-000000000001',
      validation_id: validation.dataset_version_validation_id,
      status: 'PENDING' as const,
      requested_by: 'local-admin',
      requested_at: '2026-07-30T01:00:00Z',
      approval_decision_id: null,
      approved_by: null,
      approved_at: null,
    }
    let started = false
    let snapshotted = false
    let validated = false
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === currentAdmissionPath) {
        return Promise.resolve(
          jsonResponse({
            dataset_version_id: datasetVersionId,
            all_members_currently_admission_eligible: true,
            members: [],
            build_readiness: {
              ready_for_build: true,
              blocking_reasons: [],
              immutable_evidence_still_current: true,
              uniform_label_schema_digest: 'c'.repeat(64),
              uniform_cleaning_rule_set_digest: 'd'.repeat(64),
              dataset_split_plan_id: buildSpecification.dataset_split_plan_id,
              build_specification: buildSpecification,
            },
            can_start_build: true,
          }),
        )
      }
      if (String(input) === buildPath) {
        started = true
        return Promise.resolve(
          jsonResponse({
            dataset_version_id: datasetVersionId,
            status: 'BUILDING',
            started_by: 'local-admin',
            started_at: '2026-07-29T05:00:00Z',
          }),
        )
      }
      if (String(input) === snapshotPath) {
        snapshotted = true
        return Promise.resolve(jsonResponse(buildSnapshot, 201))
      }
      if (String(input) === validationPath) {
        validated = true
        return Promise.resolve(jsonResponse(validation, 201))
      }
      return Promise.resolve(
        jsonResponse({
          ...detail,
          status: snapshotted ? 'VALIDATING' : started ? 'BUILDING' : 'DRAFT',
          build_snapshot: snapshotted ? buildSnapshot : null,
          can_create_build_snapshot: started && !snapshotted,
          validation: validated ? validation : null,
          can_validate: snapshotted && !validated,
          freeze_approval: validated ? freezeApproval : null,
          can_freeze: false,
        }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', {
      randomUUID: vi
        .fn()
        .mockReturnValueOnce('dataset-build-start-0001')
        .mockReturnValueOnce('dataset-build-snapshot-0001')
        .mockReturnValueOnce('dataset-validation-0001'),
    })
    const user = userEvent.setup()

    renderDatasetDetail(datasetVersionId)

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('配药操作训练候选')
    await user.click(screen.getByRole('button', { name: '检查构建准备' }))
    expect(await screen.findByRole('button', { name: '开始构建' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '开始构建' }))
    const confirmDialog = await screen.findByRole('dialog')
    expect(within(confirmDialog).getByText(/不会冻结或导出数据/)).toBeInTheDocument()
    await user.click(within(confirmDialog).getByRole('button', { name: '开始构建' }))

    expect(await screen.findByText('构建中')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '开始构建' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '生成构建快照' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      buildPath,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({ 'Idempotency-Key': 'dataset-build-start-0001' }),
      }),
    )

    await user.click(screen.getByRole('button', { name: '生成构建快照' }))
    const snapshotDialog = (await screen.findAllByRole('dialog')).find(
      (dialog) => within(dialog).queryAllByText('生成当前构建快照？').length > 0,
    )
    expect(snapshotDialog).toBeDefined()
    if (!snapshotDialog) throw new Error('Snapshot confirmation dialog was not rendered.')
    expect(within(snapshotDialog).getByText(/不会冻结或导出数据/)).toBeInTheDocument()
    await user.click(within(snapshotDialog).getByRole('button', { name: '生成构建快照' }))

    expect(await screen.findByText('校验中')).toBeInTheDocument()
    expect(screen.getByText('已封存')).toBeInTheDocument()
    expect(screen.getByText('查看构建快照标识')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '生成构建快照' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '执行冻结前校验' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      snapshotPath,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({ 'Idempotency-Key': 'dataset-build-snapshot-0001' }),
      }),
    )

    await user.click(screen.getByRole('button', { name: '执行冻结前校验' }))
    const validationDialog = (await screen.findAllByRole('dialog')).find(
      (dialog) => within(dialog).queryAllByText('执行冻结前校验？').length > 0,
    )
    expect(validationDialog).toBeDefined()
    if (!validationDialog) throw new Error('Validation confirmation dialog was not rendered.')
    expect(within(validationDialog).getByText(/通过也不会自动冻结/)).toBeInTheDocument()
    await user.click(within(validationDialog).getByRole('button', { name: '执行冻结前校验' }))

    expect(await screen.findByText('冻结前校验已通过')).toBeInTheDocument()
    expect(screen.getByText(/仍需单独审批后才能冻结/)).toBeInTheDocument()
    expect(screen.getByText('等待管理员审批')).toBeInTheDocument()
    expect(screen.getByText(/当前项目管理员处理/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '批准并冻结' })).not.toBeInTheDocument()
    const validationFacts = screen.getByText('查看校验标识').closest('details')
    expect(validationFacts).not.toBeNull()
    if (!validationFacts) throw new Error('Validation facts were not rendered.')
    expect(within(validationFacts).getByText('执行人')).toBeInTheDocument()
    expect(within(validationFacts).getByText('local-admin')).toBeInTheDocument()
    expect(within(validationFacts).getByText('执行时间')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '执行冻结前校验' })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      validationPath,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({ 'Idempotency-Key': 'dataset-validation-0001' }),
      }),
    )
  })

  it('lets one administrator revalidate, self-approve and freeze one version atomically', async () => {
    const datasetVersionId = '91000000-0000-4000-8000-000000000001'
    const freezePath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/freezes`
    const detail = datasetVersionDetail(datasetVersionId, '配药操作训练候选')
    const validation = {
      dataset_version_validation_id: '95000000-0000-4000-8000-000000000001',
      dataset_version_id: datasetVersionId,
      dataset_build_snapshot_id: '94000000-0000-4000-8000-000000000001',
      validation_profile_version: 'DATASET_FREEZE_VALIDATION_V1' as const,
      outcome: 'PASS' as const,
      reason_codes: [],
      executed_checks: [
        'CURRENT_ADMISSION',
        'REFERENCE_INTEGRITY',
        'SNAPSHOT_DIGEST',
        'SPLIT_ISOLATION',
        'BUILD_CONTRACT',
      ] as const,
      validated_snapshot_digest: 'f'.repeat(64),
      validated_by: 'local-admin',
      validated_at: '2026-07-30T01:00:00Z',
    }
    const pendingApproval = {
      approval_request_id: '96000000-0000-4000-8000-000000000001',
      validation_id: validation.dataset_version_validation_id,
      status: 'PENDING' as const,
      requested_by: 'local-admin',
      requested_at: '2026-07-30T01:00:00Z',
      approval_decision_id: null,
      approved_by: null,
      approved_at: null,
    }
    const approvedApproval = {
      ...pendingApproval,
      status: 'APPROVED' as const,
      approval_decision_id: '97000000-0000-4000-8000-000000000001',
      approved_by: 'local-admin',
      approved_at: '2026-07-30T02:00:00Z',
    }
    let frozen = false
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === freezePath && init?.method === 'POST') {
        frozen = true
        return Promise.resolve(
          jsonResponse(
            {
              dataset_version_id: datasetVersionId,
              project_id: projectId,
              status: 'FROZEN',
              validation_id: validation.dataset_version_validation_id,
              approval_request_id: pendingApproval.approval_request_id,
              approval_decision_id: approvedApproval.approval_decision_id,
              frozen_by: 'local-admin',
              frozen_at: approvedApproval.approved_at,
            },
            201,
          ),
        )
      }
      return Promise.resolve(
        jsonResponse({
          ...detail,
          status: frozen ? 'FROZEN' : 'VALIDATING',
          validation,
          can_validate: false,
          freeze_approval: frozen ? approvedApproval : pendingApproval,
          can_freeze: !frozen,
        }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValue('dataset-freeze-0001'),
    })
    const user = userEvent.setup()

    renderDatasetDetail(datasetVersionId)

    expect(await screen.findByText('等待管理员审批')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '批准并冻结' }))
    const confirmDialog = await screen.findByRole('dialog')
    expect(within(confirmDialog).getByText(/冻结后不可修改，只能新建版本/)).toBeInTheDocument()
    expect(within(confirmDialog).getByText(/不会导出数据/)).toBeInTheDocument()
    await user.click(within(confirmDialog).getByRole('button', { name: '批准并冻结' }))

    expect(await screen.findByText('数据集已冻结')).toBeInTheDocument()
    expect(screen.getByText('已冻结')).toBeInTheDocument()
    expect(screen.getByText('管理员自审（SELF_ADMIN_APPROVAL）')).toBeInTheDocument()
    expect(screen.getByText('技术校验通过，管理员审批已完成。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '批准并冻结' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '检查当前证据' })).not.toBeInTheDocument()
    const approvalFacts = screen.getByText('查看冻结审批记录').closest('details')
    expect(approvalFacts).not.toBeNull()
    if (!approvalFacts) throw new Error('Freeze approval facts were not rendered.')
    expect(within(approvalFacts).getByText('审批人')).toBeInTheDocument()
    expect(within(approvalFacts).getAllByText('local-admin')).toHaveLength(2)
    expect(fetchMock).toHaveBeenCalledWith(
      freezePath,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({ 'Idempotency-Key': 'dataset-freeze-0001' }),
      }),
    )
  })

  it('shows a requested dataset export as pending approval rather than queued work', async () => {
    const datasetVersionId = '91000000-0000-4000-8000-000000000001'
    const readinessPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/approval-readiness`
    const detail = datasetVersionDetail(datasetVersionId, '配药操作训练候选')
    detail.status = 'FROZEN'
    detail.export_request = datasetExport(datasetVersionId, {
      export_profile_key: 'p0-dataset-export-v1',
    })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === readinessPath) {
        return Promise.resolve(
          jsonResponse({
            dataset_export_id: detail.export_request?.dataset_export_id,
            dataset_version_id: datasetVersionId,
            approval_request_id: detail.export_request?.approval.approval_request_id,
            ready_for_approval: false,
            blocking_reasons: ['SELF_APPROVAL_FORBIDDEN'],
            checked_at: '2026-07-30T03:00:00Z',
          }),
        )
      }
      return Promise.resolve(jsonResponse(detail))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderDatasetDetail(datasetVersionId)

    expect(await screen.findByText('导出申请等待管理员审批')).toBeInTheDocument()
    expect(screen.getByText(/尚未进入后台队列/)).toBeInTheDocument()
    expect(screen.getByText('内部模型训练')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '提交导出申请' })).not.toBeInTheDocument()
    const exportFacts = screen.getByText('查看导出申请标识').closest('details')
    expect(exportFacts).not.toBeNull()
    if (!exportFacts) throw new Error('Dataset export facts were not rendered.')
    expect(within(exportFacts).getByText('p0-dataset-export-v1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '检查审批准备' }))
    expect(await screen.findByText('审批准备仍有阻断项')).toBeInTheDocument()
    expect(screen.getByText('当前账号没有管理员自审权限')).toBeInTheDocument()
    expect(screen.getByText('暂不可审批')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '批准并开始导出' })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      readinessPath,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('self-approves a ready dataset export and exposes the queued state without polling', async () => {
    const datasetVersionId = '91000000-0000-4000-8000-000000000001'
    const readinessPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/approval-readiness`
    const approvalPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/approvals`
    const detail = datasetVersionDetail(datasetVersionId, '配药操作训练候选')
    detail.status = 'FROZEN'
    detail.export_request = datasetExport(datasetVersionId, { requested_by: 'local-admin' })
    const queuedExport = datasetExport(datasetVersionId, {
      status: 'QUEUED',
      approved_by: 'local-admin',
      approved_at: '2026-07-30T03:01:00Z',
      approval: {
        ...datasetExport(datasetVersionId).approval,
        status: 'APPROVED',
        requested_by: 'local-admin',
        approval_decision_id: '9a000000-0000-4000-8000-000000000001',
        decided_by: 'local-admin',
        decided_at: '2026-07-30T03:01:00Z',
      },
      attempt: {
        dataset_export_attempt_id: '9b000000-0000-4000-8000-000000000001',
        attempt: 1,
        status: 'QUEUED',
        started_at: null,
        completed_at: null,
      },
    })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === readinessPath) {
        return Promise.resolve(
          jsonResponse({
            dataset_export_id: detail.export_request?.dataset_export_id,
            dataset_version_id: datasetVersionId,
            approval_request_id: detail.export_request?.approval.approval_request_id,
            ready_for_approval: true,
            blocking_reasons: [],
            checked_at: '2026-07-30T03:00:00Z',
          }),
        )
      }
      if (path === approvalPath && init?.method === 'POST') {
        return Promise.resolve(jsonResponse(queuedExport, 201))
      }
      return Promise.resolve(jsonResponse(detail))
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValue('dataset-export-approval-0001'),
    })
    const user = userEvent.setup()

    renderDatasetDetail(datasetVersionId)

    await user.click(await screen.findByRole('button', { name: '检查审批准备' }))
    expect(await screen.findByText('审批证据当前满足条件')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '批准并开始导出' }))
    const confirmDialog = await screen.findByRole('dialog')
    await user.click(within(confirmDialog).getByRole('button', { name: '批准并开始导出' }))

    expect(await screen.findByText('已批准，等待生成训练包')).toBeInTheDocument()
    expect(screen.getByText('local-admin · 管理员自审（SELF_ADMIN_APPROVAL）')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      approvalPath,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({ 'Idempotency-Key': 'dataset-export-approval-0001' }),
      }),
    )
  })

  it('downloads an available dataset export through one transient authorization', async () => {
    const datasetVersionId = '91000000-0000-4000-8000-000000000001'
    const downloadPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/download-authorizations`
    const detail = datasetVersionDetail(datasetVersionId, '配药操作训练候选')
    detail.status = 'FROZEN'
    detail.export_request = datasetExport(datasetVersionId, {
      status: 'AVAILABLE',
      approved_by: 'independent-admin',
      approved_at: '2026-07-30T03:01:00Z',
      completed_at: '2026-07-30T03:03:00Z',
      artifact: {
        export_artifact_id: '9c000000-0000-4000-8000-000000000001',
        file_name: 'dataset-export.zip',
        media_type: 'application/zip',
        size_bytes: 1024,
        sha256: 'a'.repeat(64),
        created_at: '2026-07-30T03:03:00Z',
      },
    })
    const downloadUrl = 'https://storage.invalid/dataset-export.zip?signature=synthetic'
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === downloadPath && init?.method === 'POST') {
        return Promise.resolve(
          jsonResponse({
            dataset_export_id: detail.export_request?.dataset_export_id,
            export_artifact_id: detail.export_request?.artifact?.export_artifact_id,
            download_url: downloadUrl,
            expires_at: '2026-07-30T03:08:00Z',
            file_name: 'dataset-export.zip',
            media_type: 'application/zip',
            size_bytes: 1024,
            sha256: 'a'.repeat(64),
          }),
        )
      }
      return Promise.resolve(jsonResponse(detail))
    })
    vi.stubGlobal('fetch', fetchMock)
    const anchorClick = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined)
    const user = userEvent.setup()

    renderDatasetDetail(datasetVersionId)

    await user.click(await screen.findByRole('button', { name: '下载训练包' }))
    const confirmDialog = await screen.findByRole('dialog')
    await user.click(within(confirmDialog).getByRole('button', { name: '下载训练包' }))

    await waitFor(() => expect(anchorClick).toHaveBeenCalledOnce())
    expect((anchorClick.mock.instances[0] as HTMLAnchorElement).href).toBe(downloadUrl)
    expect(document.body.contains(anchorClick.mock.instances[0] as HTMLAnchorElement)).toBe(false)
    expect(fetchMock).toHaveBeenCalledWith(
      downloadPath,
      expect.objectContaining({ method: 'POST', cache: 'no-store' }),
    )
    anchorClick.mockRestore()
  })

  it('submits one explicit future expiry for a frozen dataset export request', async () => {
    const datasetVersionId = '91000000-0000-4000-8000-000000000001'
    const exportPath = `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/exports`
    const detail = datasetVersionDetail(datasetVersionId, '配药操作训练候选')
    detail.status = 'FROZEN'
    detail.can_request_export = true
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === exportPath && init?.method === 'POST') {
        const body = JSON.parse(String(init.body)) as { expires_at: string }
        return Promise.resolve(
          jsonResponse(
            {
              dataset_export_id: '98000000-0000-4000-8000-000000000001',
              project_id: projectId,
              dataset_version_id: datasetVersionId,
              status: 'REQUESTED',
              purpose: 'MODEL_TRAINING',
              expires_at: body.expires_at,
              dataset_build_specification_id: '93000000-0000-4000-8000-000000000001',
              dataset_build_snapshot_id: '94000000-0000-4000-8000-000000000001',
              snapshot_digest: 'f'.repeat(64),
              dataset_version_validation_id: '95000000-0000-4000-8000-000000000001',
              export_profile_key: 'p0-dataset-export-v2',
              export_profile_digest: 'e'.repeat(64),
              approval: {
                approval_request_id: '99000000-0000-4000-8000-000000000001',
                validation_id: '95000000-0000-4000-8000-000000000001',
                status: 'PENDING',
                requested_by: 'local-admin',
                requested_at: '2026-07-30T02:00:00Z',
                approval_decision_id: null,
                decided_by: null,
                decided_at: null,
              },
              requested_by: 'local-admin',
              requested_at: '2026-07-30T02:00:00Z',
              approved_by: null,
              approved_at: null,
              started_at: null,
              completed_at: null,
              attempt: null,
              artifact: null,
            },
            201,
          ),
        )
      }
      return Promise.resolve(jsonResponse(detail))
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValue('dataset-export-request-0001'),
    })
    const user = userEvent.setup()

    renderDatasetDetail(datasetVersionId)

    const expiryInput = await screen.findByPlaceholderText('选择到期时间')
    fireEvent.change(expiryInput, { target: { value: '2099-08-06 10:00' } })
    fireEvent.keyDown(expiryInput, { key: 'Enter', code: 'Enter' })
    await user.click(screen.getByRole('button', { name: '提交导出申请' }))
    const confirmDialog = await screen.findByRole('dialog')
    expect(within(confirmDialog).getByText(/不会立即生成或下载文件/)).toBeInTheDocument()
    await user.click(within(confirmDialog).getByRole('button', { name: '提交申请' }))

    expect(await screen.findByText('导出申请等待管理员审批')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      exportPath,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({ 'Idempotency-Key': 'dataset-export-request-0001' }),
      }),
    )
    const exportCall = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input) === exportPath && (init as RequestInit | undefined)?.method === 'POST',
    )
    expect(JSON.parse(String((exportCall?.[1] as RequestInit | undefined)?.body))).toMatchObject({
      purpose: 'MODEL_TRAINING',
      expires_at: expect.stringMatching(/^2099-08-06T/),
    })
  })

  it('aborts an in-flight current-admission check when leaving the draft detail route', async () => {
    const firstDatasetVersionId = '91000000-0000-4000-8000-000000000001'
    const secondDatasetVersionId = '92000000-0000-4000-8000-000000000001'
    const currentAdmissionPath = `/api/v1/projects/${projectId}/dataset-versions/${firstDatasetVersionId}/current-admission`
    let currentAdmissionSignal: AbortSignal | undefined
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === currentAdmissionPath) {
        currentAdmissionSignal = init?.signal as AbortSignal | undefined
        return new Promise<Response>(() => undefined)
      }
      const datasetVersionId = String(input).endsWith(`/${secondDatasetVersionId}`)
        ? secondDatasetVersionId
        : firstDatasetVersionId
      return Promise.resolve(
        jsonResponse(datasetVersionDetail(datasetVersionId, '配药操作训练候选')),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    const { router } = renderDatasetDetail(firstDatasetVersionId)
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('配药操作训练候选')

    await user.click(screen.getByRole('button', { name: '检查构建准备' }))
    await waitFor(() => expect(currentAdmissionSignal).toBeDefined())
    await router.navigate(`/projects/${projectId}/datasets/${secondDatasetVersionId}`)

    await waitFor(() => expect(currentAdmissionSignal?.aborted).toBe(true))
  })

  it('clears previous draft evidence before a different draft route finishes loading', async () => {
    const firstDatasetVersionId = '91000000-0000-4000-8000-000000000001'
    const secondDatasetVersionId = '92000000-0000-4000-8000-000000000001'
    let resolveSecondRequest: ((response: Response) => void) | undefined
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input).endsWith(`/dataset-versions/${firstDatasetVersionId}`)) {
        return Promise.resolve(jsonResponse(datasetVersionDetail(firstDatasetVersionId, '草稿 A')))
      }
      return new Promise<Response>((resolve) => {
        resolveSecondRequest = resolve
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const { router } = renderDatasetDetail(firstDatasetVersionId)
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('草稿 A')

    await router.navigate(`/projects/${projectId}/datasets/${secondDatasetVersionId}`)
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/dataset-versions/${secondDatasetVersionId}`,
        expect.any(Object),
      ),
    )
    expect(screen.queryByRole('heading', { name: '草稿 A' })).not.toBeInTheDocument()
    expect(screen.getByText('正在读取不可变候选快照…')).toBeInTheDocument()

    resolveSecondRequest?.(jsonResponse(datasetVersionDetail(secondDatasetVersionId, '草稿 B')))
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('草稿 B')
  })

  it('clears previous draft evidence before a different project route finishes loading', async () => {
    const otherProjectId = '31000000-0000-4000-8000-000000000001'
    const firstDatasetVersionId = '91000000-0000-4000-8000-000000000001'
    const secondDatasetVersionId = '93000000-0000-4000-8000-000000000001'
    let resolveSecondRequest: ((response: Response) => void) | undefined
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input).endsWith(`/dataset-versions/${firstDatasetVersionId}`)) {
        return Promise.resolve(
          jsonResponse(datasetVersionDetail(firstDatasetVersionId, '项目 A 草稿')),
        )
      }
      return new Promise<Response>((resolve) => {
        resolveSecondRequest = resolve
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const { router } = renderDatasetDetail(firstDatasetVersionId)
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('项目 A 草稿')

    await router.navigate(`/projects/${otherProjectId}/datasets/${secondDatasetVersionId}`)
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${otherProjectId}/dataset-versions/${secondDatasetVersionId}`,
        expect.any(Object),
      ),
    )
    expect(screen.queryByRole('heading', { name: '项目 A 草稿' })).not.toBeInTheDocument()
    expect(screen.getByText('正在读取不可变候选快照…')).toBeInTheDocument()

    resolveSecondRequest?.(
      jsonResponse(datasetVersionDetail(secondDatasetVersionId, '项目 B 草稿')),
    )
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('项目 B 草稿')
  })

  it('keeps the session facts available when the readiness preview temporarily fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: RequestInfo | URL) =>
        Promise.resolve(
          String(input).endsWith('/dataset-admission-preview')
            ? jsonResponse(
                {
                  error_code: 'DATASET_ADMISSION_PREVIEW_UNAVAILABLE',
                  error_message: 'Synthetic failure.',
                  retryable: true,
                },
                503,
              )
            : jsonResponse(captureSessionDetail()),
        ),
      ),
    )
    renderSessionDetail()
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('配药操作详情')
    expect(screen.getByText('采集概况')).toBeInTheDocument()
    expect(screen.getByText('暂时无法读取视频数据，请稍后重试。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument()
  })

  it('lets a member start hand-pose detection from the selected session camera', async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/dataset-admission-preview'))
        return Promise.resolve(jsonResponse(eligibleAdmissionPreview()))
      if (url.endsWith('/hand-pose-candidates')) {
        return Promise.resolve(
          jsonResponse(
            {
              error_code: 'AI_HAND_POSE_JOB_NOT_FOUND',
              error_message: 'No hand-pose job exists yet.',
            },
            404,
          ),
        )
      }
      if (url.endsWith('/hand-pose-detections') && init?.method === 'POST') {
        return Promise.resolve(
          jsonResponse(
            {
              ai_job_id: 'b1000000-0000-4000-8000-000000000001',
              project_id: projectId,
              clip_asset_id: assetId,
              algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
              algorithm_version: 'v1',
              model_sha256: 'a'.repeat(64),
              input_sha256: 'b'.repeat(64),
              input_size_bytes: 8_388_608,
              sampling_fps: 10,
              status: 'QUEUED',
              requested_by: 'local-synthetic-admin',
              created_at: '2026-08-10T01:00:00Z',
              updated_at: '2026-08-10T01:00:00Z',
            },
            202,
          ),
        )
      }
      return Promise.resolve(jsonResponse(captureSessionDetail()))
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('session-hand-pose-ui-0001') })
    const user = userEvent.setup()

    renderSessionDetail()

    await user.click(await screen.findByRole('button', { name: '检测相机 1 的手部姿态' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/videos/${assetId}/hand-pose-detections`,
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({ 'Idempotency-Key': 'session-hand-pose-ui-0001' }),
        }),
      ),
    )
    expect(await screen.findByText(/手部检测：正在检测，页面会自动更新。/)).toBeInTheDocument()
  })

  it('offers direct viewing for an uploaded video without exposing storage or using an identifier as the page title', async () => {
    stubAsset({ role: 'MEMBER', status: 'UPLOADED' })
    renderAssetDetail()
    const heading = await screen.findByRole('heading', { level: 1 })
    expect(heading).toHaveTextContent('视频详情')
    const headingCopy = heading.parentElement?.parentElement
    expect(headingCopy).not.toHaveTextContent('相机')
    expect(headingCopy).not.toHaveTextContent('资产 ID')
    expect(screen.getByRole('link', { name: '视频管理' })).toHaveAttribute(
      'href',
      `/projects/${projectId}/videos`,
    )
    expect(screen.getByText('资产 ID')).toBeInTheDocument()
    expect(screen.getByText('可以直接查看视频')).toBeInTheDocument()
    expect(screen.getByText(/视频已完成上传完整性校验/)).toBeInTheDocument()
    expect(screen.getAllByText('90000000-0000-4000-8000-000000000001').length).toBeGreaterThan(0)
    expect(screen.getByText(/尚未绑定到 CaptureSession/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '播放视频' })).toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    expect(document.body.innerHTML).not.toContain('object_key')
    expect(document.body.innerHTML).not.toContain('storage_bucket')
  })

  it('does not offer the destructive action to a project member', async () => {
    stubAsset({ role: 'MEMBER', status: 'UPLOADED' })
    renderAssetDetail()
    await screen.findByText('可以直接查看视频')
    expect(screen.queryByRole('button', { name: '删除视频' })).not.toBeInTheDocument()
  })

  it('does not show the folder classification surface in LEAN-P0', async () => {
    const fetchMock = stubAsset({ role: 'ADMIN', status: 'UPLOADED' })
    renderAssetDetail()
    expect(await screen.findByText('视频详情')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '更新分类' })).not.toBeInTheDocument()
    expect(screen.queryByText('视频分类')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/asset-folders'))).toBe(
      false,
    )
  })

  it('lets a project member request playback only after an explicit click', async () => {
    const fetchMock = stubAsset({
      role: 'MEMBER',
      status: 'INGESTED',
      mediaReport: verifiedMediaReport(),
      quality: unreviewedQuality(),
      playback: true,
    })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    expect(await screen.findByRole('button', { name: '播放视频' })).toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '播放视频' }))
    const video = await screen.findByLabelText('视频预览')
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/playback-grants`,
      expect.objectContaining({ method: 'POST' }),
    )
    expect(video).toHaveAttribute('src', expect.stringContaining('signature=synthetic'))
    expect(video).toHaveAttribute('preload', 'none')
    expect(screen.getByText('人工查看')).toBeInTheDocument()
    expect(screen.getByText('完整解码已验证')).toBeInTheDocument()
    expect(screen.getByText('0:02')).toBeInTheDocument()
    expect(screen.queryByText('人工隐私评估')).not.toBeInTheDocument()
    fireEvent.error(video)
    expect(await screen.findByText(/视频加载失败/)).toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新获取播放授权' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '保存质量结论' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/clip-assets/${assetId}/quality-review`,
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({
            decision: 'QUALIFIED',
            reason_code: 'MEETS_REQUIREMENTS',
            expected_version: 0,
          }),
        }),
      ),
    )
    expect((await screen.findAllByText('符合训练数据要求')).length).toBeGreaterThan(0)
  })

  it('loads minimized asset activity only after an explicit request', async () => {
    const fetchMock = stubAsset({
      role: 'MEMBER',
      status: 'UPLOADED',
      activity: true,
    })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    const button = await screen.findByRole('button', { name: '查看记录' })
    expect(
      fetchMock.mock.calls.some(([input]) =>
        String(input).includes(`/clip-assets/${assetId}/activity`),
      ),
    ).toBe(false)

    await user.click(button)

    expect(await screen.findByText('获取视频播放授权')).toBeInTheDocument()
    expect(screen.getByText('用户 · reviewer')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/activity?limit=20`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    expect(screen.queryByText('must-not-be-returned')).not.toBeInTheDocument()
  })

  it('lets a project member play an uploaded video without requiring a manual media-detection step', async () => {
    stubAsset({
      role: 'MEMBER',
      status: 'UPLOADED',
      playback: true,
      mediaProbe: true,
    })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    expect(await screen.findByText('上传完成，待检测')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '播放视频' }))
    expect(await screen.findByLabelText('视频预览')).toBeInTheDocument()
    expect(screen.queryByText('检测媒体信息')).not.toBeInTheDocument()
    expect(screen.getByLabelText('视频预览')).toBeInTheDocument()
  })

  it('lets an administrator recover a confirmed missing media probe delivery from the asset detail', async () => {
    const fetchMock = stubAsset({ role: 'ADMIN', status: 'UPLOADED', deliveryRecovery: true })
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('media-recovery-ui-0001') })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'admin', role: 'ADMIN' })

    await user.click(await screen.findByRole('button', { name: '恢复已丢失的检测任务' }))
    await user.type(screen.getByPlaceholderText('例如：INC-20260805-001'), 'INC-20260805-001')
    await user.click(screen.getByRole('button', { name: '确认恢复' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/clip-assets/${assetId}/media-probe-delivery-recoveries`,
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ change_reference: 'INC-20260805-001' }),
          headers: expect.objectContaining({ 'Idempotency-Key': 'media-recovery-ui-0001' }),
        }),
      ),
    )
    expect(await screen.findByText(/媒体检测已恢复为第 2 次尝试/)).toBeInTheDocument()
  })

  it('lets an administrator create one task only after the video is human-qualified', async () => {
    const membership = {
      membership_id: 'd0000000-0000-4000-8000-000000000001',
      project_id: projectId,
      actor_id: 'local-synthetic-uploader',
      role: 'MEMBER',
      status: 'ACTIVE',
      version: 0,
      updated_at: '2026-07-23T01:00:00Z',
    }
    const fetchMock = stubAsset({
      role: 'ADMIN',
      status: 'INGESTED',
      mediaReport: verifiedMediaReport(),
      quality: {
        ...unreviewedQuality(),
        quality_review_id: 'c0000000-0000-4000-8000-000000000001',
        decision: 'QUALIFIED',
        reason_code: 'MEETS_REQUIREMENTS',
        reviewed_by: 'local-synthetic-uploader',
        reviewed_at: '2026-07-23T01:00:00Z',
      },
      memberships: [membership],
      annotationTask: true,
    })
    const user = userEvent.setup()
    renderAssetDetail()
    expect(
      await screen.findByText('该视频已通过人工质量判断。请选择项目成员并创建配药动作标注。'),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '创建配药动作标注' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/clip-assets/${assetId}/annotation-tasks`,
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ assignee_actor_id: 'local-synthetic-uploader' }),
        }),
      ),
    )
    expect(await screen.findByText('配药动作标注已创建并分配')).toBeInTheDocument()
  })

  it('renders persisted annotation, human review and cleaning facts after a detail refresh', async () => {
    stubAsset({
      role: 'ADMIN',
      status: 'INGESTED',
      quality: {
        ...unreviewedQuality(),
        decision: 'QUALIFIED',
        reason_code: 'MEETS_REQUIREMENTS',
      },
      existingAnnotationTask: completedAnnotationTask(),
    })

    renderAssetDetail()

    const workflow = await screen.findByRole('complementary', { name: '视频处理流程' })
    expect(within(workflow).getByText('人工复核')).toBeInTheDocument()
    expect(within(workflow).getAllByText('已通过清洗')).toHaveLength(2)
    expect(within(workflow).getAllByText('已通过清洗，可参与训练数据导出')).toHaveLength(2)
    expect(within(workflow).getByText('当前结果')).toBeInTheDocument()
    expect(within(workflow).queryByText('查看复核与清洗结果')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建配药动作标注' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看配药动作标注' })).toBeInTheDocument()
    expect(document.querySelector('.workbench-primary')?.tagName).toBe('SECTION')
  })

  it('keeps a historical Cleaning PASS behind a current rejected quality decision', async () => {
    stubAsset({
      role: 'ADMIN',
      status: 'INGESTED',
      quality: {
        ...unreviewedQuality(),
        quality_review_id: 'c0000000-0000-4000-8000-000000000002',
        decision: 'REJECTED',
        reason_code: 'OTHER_QUALITY_FAILURE',
        reviewed_by: 'local-synthetic-admin',
        reviewed_at: '2026-08-11T01:00:00Z',
        version: 1,
      },
      existingAnnotationTask: completedAnnotationTask(),
    })

    renderAssetDetail()

    expect(await screen.findByRole('button', { name: '查看质量结论' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '导出训练数据' })).not.toBeInTheDocument()
    expect(
      screen.getByText('历史清洗已通过；当前质量结论未确认或未通过，暂不能参与训练数据导出'),
    ).toBeInTheDocument()
  })

  it('keeps automatic hand-pose detection auxiliary and does not add a manual start action', async () => {
    const fetchMock = stubAsset({ role: 'MEMBER', status: 'INGESTED', handPose: true })
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })

    expect(await screen.findByText('手部姿态检测')).toBeInTheDocument()
    expect(screen.getByText(/结果只是手部预标注候选，仍须人工确认/)).toBeInTheDocument()
    expect(await screen.findByText('等待自动手部检测')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '检测手部姿态' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新检测' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下一步：人工质量判断' })).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).endsWith('/hand-pose-detections') && init?.method === 'POST',
      ),
    ).toBe(false)
  })

  it('does not offer an AssetDetail retry when the failed run is not retryable', async () => {
    stubAsset({
      role: 'MEMBER',
      status: 'INGESTED',
      handPoseState: failedHandPoseJob(false),
    })
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })

    expect(await screen.findByText('手部检测失败')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重试检测' })).not.toBeInTheDocument()
    expect(screen.getByText(/AI_HAND_POSE_INFERENCE_FAILED/)).toBeInTheDocument()
    expect(screen.getAllByText(/当前失败不可重试，可继续人工处理/)).toHaveLength(2)
  })

  it('uses the AssetDetail retry endpoint only for a retryable failed run', async () => {
    const fetchMock = stubAsset({
      role: 'MEMBER',
      status: 'INGESTED',
      handPoseState: failedHandPoseJob(true),
    })
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('asset-retry-ui-0001') })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })

    expect(await screen.findByText(/AI_HAND_POSE_INFERENCE_FAILED/)).toBeInTheDocument()
    expect(screen.getByText(/可以重新检测/)).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: '重新检测' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/videos/${assetId}/hand-pose-detections/retry`,
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({ 'Idempotency-Key': 'asset-retry-ui-0001' }),
        }),
      ),
    )
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).endsWith('/hand-pose-detections') && init?.method === 'POST',
      ),
    ).toBe(false)
  })

  it('polls media ingest until it becomes stable, then stops', async () => {
    vi.useFakeTimers()
    let detailReads = 0
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === `/api/v1/projects/${projectId}`)
        return Promise.resolve(jsonResponse(project('MEMBER')))
      if (url === `/api/v1/projects/${projectId}/clip-assets/${assetId}`) {
        detailReads += 1
        return Promise.resolve(
          jsonResponse(assetDetail(detailReads === 1 ? 'INGESTING' : 'INGESTED')),
        )
      }
      if (url.endsWith('/media-report')) return Promise.resolve(jsonResponse(verifiedMediaReport()))
      if (url.endsWith('/quality-review')) return Promise.resolve(jsonResponse(unreviewedQuality()))
      if (url.endsWith('/annotation-tasks'))
        return Promise.resolve(jsonResponse(emptyAnnotationTaskList()))
      if (url.endsWith('/hand-pose-candidates'))
        return Promise.resolve(jsonResponse({ error_code: 'AI_HAND_POSE_JOB_NOT_FOUND' }, 404))
      return Promise.resolve(jsonResponse(assetDetail('INGESTING')))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getByText('视频检测中')).toBeInTheDocument()
    expect(detailReads).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_500)
    })
    expect(screen.getByText('媒体检测完成')).toBeInTheDocument()
    expect(screen.getByText('视频检测完成')).toBeInTheDocument()
    expect(detailReads).toBe(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
    })
    expect(detailReads).toBe(2)
  })

  it('polls a running hand-pose job and keeps the human quality action available', async () => {
    vi.useFakeTimers()
    let handPoseReads = 0
    let detailReads = 0
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === `/api/v1/projects/${projectId}`)
        return Promise.resolve(jsonResponse(project('MEMBER')))
      if (url === `/api/v1/projects/${projectId}/clip-assets/${assetId}`) {
        detailReads += 1
        return Promise.resolve(jsonResponse(assetDetail('INGESTED')))
      }
      if (url.endsWith('/media-report')) return Promise.resolve(jsonResponse(verifiedMediaReport()))
      if (url.endsWith('/quality-review')) return Promise.resolve(jsonResponse(unreviewedQuality()))
      if (url.endsWith('/annotation-tasks'))
        return Promise.resolve(jsonResponse(emptyAnnotationTaskList()))
      if (url.endsWith('/hand-pose-candidates')) {
        handPoseReads += 1
        return Promise.resolve(
          jsonResponse({
            job: handPoseReads === 1 ? runningHandPoseJob() : succeededHandPoseJob('HAND_DETECTED'),
            candidates: [],
          }),
        )
      }
      return Promise.resolve(jsonResponse(assetDetail('INGESTED')))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getByText('手部检测中')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下一步：人工质量判断' })).toBeInTheDocument()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_500)
    })
    expect(screen.getByText('已发现手部')).toBeInTheDocument()
    expect(screen.getByText('手部检测完成')).toBeInTheDocument()
    expect(handPoseReads).toBe(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
    })
    expect(handPoseReads).toBe(2)
    expect(detailReads).toBe(2)
  })

  it('keeps the human quality step when MediaPipe finds no hand', async () => {
    stubAsset({
      role: 'MEMBER',
      status: 'INGESTED',
      handPoseState: succeededHandPoseJob('NO_HAND_DETECTED'),
    })
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })

    expect(await screen.findByText('未发现手部，可继续人工查看和标注')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下一步：人工质量判断' })).toBeInTheDocument()
  })
})

function renderSessionList() {
  return renderRoute(<SessionListView />, {
    route: `/projects/${projectId}/sessions`,
    path: '/projects/:projectId/sessions',
  })
}
function renderSessionDetail() {
  return renderRoute(<SessionDetailView />, {
    route: `/projects/${projectId}/sessions/${sessionId}`,
    path: '/projects/:projectId/sessions/:id',
  })
}
function renderDatasetDetail(datasetVersionId: string) {
  return renderRoute(<DatasetVersionDetailView />, {
    route: `/projects/${projectId}/datasets/${datasetVersionId}`,
    path: '/projects/:projectId/datasets/:id',
  })
}
function renderAssetDetail(session?: { username: string; role: 'ADMIN' | 'MEMBER' }) {
  return renderRoute(<AssetDetailView />, {
    route: `/projects/${projectId}/assets/${assetId}`,
    path: '/projects/:projectId/assets/:id',
    session,
  })
}
function stubAsset(options: {
  role: 'ADMIN' | 'MEMBER'
  status: 'UPLOADED' | 'INGESTING' | 'INGESTED' | 'INGEST_FAILED'
  memberships?: object[]
  mediaReport?: object
  quality?: object
  playback?: boolean
  mediaProbe?: boolean
  handPose?: boolean
  handPoseState?: object
  deliveryRecovery?: boolean
  annotationTask?: boolean
  existingAnnotationTask?: object
  activity?: boolean
}) {
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith(`/api/v1/projects/${projectId}`))
      return Promise.resolve(
        jsonResponse({
          project_id: projectId,
          display_name: '合成测试项目',
          status: 'ACTIVE',
          current_role: options.role,
          created_at: '2026-07-15T01:03:03Z',
        }),
      )
    if (url.endsWith('/memberships'))
      return Promise.resolve(jsonResponse({ items: options.memberships ?? [] }))
    if (url.endsWith('/media-report'))
      return Promise.resolve(jsonResponse(options.mediaReport ?? verifiedMediaReport()))
    if (url.includes(`/clip-assets/${assetId}/activity`) && options.activity)
      return Promise.resolve(
        jsonResponse({
          items: [
            {
              event_id: 'f0000000-0000-4000-8000-000000000001',
              occurred_at_utc: '2026-07-29T02:00:00Z',
              actor_type: 'USER',
              actor_id: 'reviewer',
              action: 'CLIP_ASSET_PLAYBACK_GRANTED',
              object_type: 'ClipAsset',
              object_id: assetId,
              object_version: '1',
              outcome: 'SUCCEEDED',
              reason_code: 'DATA_REVIEW',
              request_id: 'playback-request-0001',
            },
          ],
          next_cursor: null,
        }),
      )
    if (url.endsWith('/playback-grants') && options.playback)
      return Promise.resolve(
        jsonResponse({
          grant_id: 'b0000000-0000-4000-8000-000000000001',
          purpose: 'DATA_REVIEW',
          media_url: 'https://storage.invalid/review.mp4?signature=synthetic',
          expires_at: new Date(Date.now() + 300_000).toISOString(),
          content_type: 'video/mp4',
        }),
      )
    if (url.endsWith('/media-probe-jobs') && options.mediaProbe)
      return Promise.resolve(
        jsonResponse(
          {
            media_job_id: 'b0000000-0000-4000-8000-000000000001',
            project_id: projectId,
            clip_asset_id: assetId,
            job_type: 'MEDIA_PROBE',
            status: 'QUEUED',
          },
          202,
        ),
      )
    if (url.endsWith('/hand-pose-detections') && options.handPose)
      return Promise.resolve(
        jsonResponse(
          {
            ai_job_id: 'b1000000-0000-4000-8000-000000000001',
            project_id: projectId,
            clip_asset_id: assetId,
            algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
            algorithm_version: 'v1',
            model_sha256: 'a'.repeat(64),
            input_sha256: 'b'.repeat(64),
            input_size_bytes: 8_388_608,
            sampling_fps: 10,
            status: 'QUEUED',
            requested_by: 'local-synthetic-admin',
            created_at: '2026-08-10T01:00:00Z',
            updated_at: '2026-08-10T01:00:00Z',
          },
          202,
        ),
      )
    if (url.endsWith('/hand-pose-candidates') && options.handPoseState)
      return Promise.resolve(jsonResponse({ job: options.handPoseState, candidates: [] }))
    if (url.endsWith('/hand-pose-detections/retry') && options.handPoseState)
      return Promise.resolve(
        jsonResponse({ ...options.handPoseState, status: 'QUEUED', run_summary: null }, 202),
      )
    if (url.endsWith('/media-probe-delivery-recoveries') && options.deliveryRecovery)
      return Promise.resolve(
        jsonResponse(
          {
            recovery_id: 'c0000000-0000-4000-8000-000000000001',
            media_job_id: 'b0000000-0000-4000-8000-000000000001',
            source_message_id: 'd0000000-0000-4000-8000-000000000001',
            source_attempt: 1,
            new_attempt: 2,
            resulting_job_status: 'QUEUED',
            created_at: '2026-08-05T01:02:03Z',
          },
          202,
        ),
      )
    if (url.endsWith('/quality-review')) {
      const current = options.quality ?? unreviewedQuality()
      return Promise.resolve(
        jsonResponse(
          init?.method === 'PUT'
            ? {
                ...current,
                quality_review_id: 'c0000000-0000-4000-8000-000000000001',
                decision: 'QUALIFIED',
                reason_code: 'MEETS_REQUIREMENTS',
                reviewed_by: 'reviewer',
                reviewed_at: '2026-07-22T01:02:03Z',
              }
            : current,
        ),
      )
    }
    if (url.endsWith('/annotation-tasks') && init?.method === 'POST' && options.annotationTask)
      return Promise.resolve(
        jsonResponse(
          {
            annotation_task_id: 'e0000000-0000-4000-8000-000000000001',
            project_id: projectId,
            clip_asset_id: assetId,
            assignee_actor_id: 'local-synthetic-uploader',
            created_by: 'local-synthetic-admin',
            status: 'ASSIGNED',
            created_at: '2026-07-23T01:00:00Z',
            updated_at: '2026-07-23T01:00:00Z',
            version: 0,
          },
          201,
        ),
      )
    if (url.endsWith(`/api/v1/projects/${projectId}/annotation-tasks`) && init?.method === 'GET')
      return Promise.resolve(
        jsonResponse({
          items: options.existingAnnotationTask ? [options.existingAnnotationTask] : [],
          progress: {
            scope: options.role === 'ADMIN' ? 'PROJECT' : 'ASSIGNEE',
            total: options.existingAnnotationTask ? 1 : 0,
            awaiting_submission: 0,
            rework_required: 0,
            awaiting_review: 0,
            completed: options.existingAnnotationTask ? 1 : 0,
          },
        }),
      )
    return Promise.resolve(jsonResponse(assetDetail(options.status)))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
function assetDetail(status: 'UPLOADED' | 'INGESTING' | 'INGESTED' | 'INGEST_FAILED') {
  return {
    clip_asset_id: assetId,
    batch_id: '60000000-0000-4000-8000-000000000001',
    source_upload_file_id: '80000000-0000-4000-8000-000000000001',
    client_file_id: '90000000-0000-4000-8000-000000000001',
    content_type: 'video/mp4',
    size_bytes: 8_388_608,
    sha256: 'a'.repeat(64),
    status,
    folder_id: null,
    session_binding: null,
    created_at: '2026-07-15T01:03:03Z',
    updated_at: '2026-07-15T01:03:03Z',
  }
}

function runningHandPoseJob() {
  return {
    ...succeededHandPoseJob('UNKNOWN'),
    status: 'RUNNING',
    run_summary: null,
  }
}

function succeededHandPoseJob(handResult: 'HAND_DETECTED' | 'NO_HAND_DETECTED' | 'UNKNOWN') {
  return {
    ai_job_id: 'b1000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: assetId,
    algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
    algorithm_version: 'v1',
    model_sha256: 'a'.repeat(64),
    input_sha256: 'b'.repeat(64),
    input_size_bytes: 8_388_608,
    sampling_fps: 10,
    profile_sha256: 'c'.repeat(64),
    status: 'SUCCEEDED',
    hand_result: handResult,
    run_summary: {
      analysis_complete: true,
      hand_result: handResult,
      total_video_frames: 10,
      inference_frame_count: 10,
      frames_with_hand: handResult === 'HAND_DETECTED' ? 10 : 0,
      frames_without_hand: handResult === 'NO_HAND_DETECTED' ? 10 : 0,
      left_hand_detection_count: handResult === 'HAND_DETECTED' ? 5 : 0,
      right_hand_detection_count: handResult === 'HAND_DETECTED' ? 5 : 0,
      invalid_landmark_count: 0,
      processing_duration_ms: 100,
      started_at: '2026-08-11T02:00:00Z',
      completed_at: '2026-08-11T02:00:01Z',
      failure_reason: null,
      retryable: false,
    },
    requested_by: 'local-synthetic-admin',
    created_at: '2026-08-11T02:00:00Z',
    updated_at: '2026-08-11T02:00:01Z',
  }
}

function failedHandPoseJob(retryable: boolean) {
  return {
    ai_job_id: 'b1000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: assetId,
    algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
    algorithm_version: 'v1',
    model_sha256: 'a'.repeat(64),
    input_sha256: 'b'.repeat(64),
    input_size_bytes: 8_388_608,
    sampling_fps: 10,
    profile_sha256: 'c'.repeat(64),
    status: 'FAILED',
    hand_result: 'UNKNOWN',
    run_summary: {
      analysis_complete: false,
      hand_result: 'UNKNOWN',
      total_video_frames: 10,
      inference_frame_count: 1,
      frames_with_hand: 0,
      frames_without_hand: 1,
      left_hand_detection_count: 0,
      right_hand_detection_count: 0,
      invalid_landmark_count: 0,
      processing_duration_ms: 10,
      started_at: '2026-08-11T02:00:00Z',
      completed_at: '2026-08-11T02:00:01Z',
      failure_reason: 'AI_HAND_POSE_INFERENCE_FAILED',
      retryable,
    },
    requested_by: 'local-synthetic-admin',
    created_at: '2026-08-11T02:00:00Z',
    updated_at: '2026-08-11T02:00:01Z',
  }
}

function completedAnnotationTask() {
  return {
    annotation_task_id: 'e0000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: assetId,
    label_schema_id: 'e1000000-0000-4000-8000-000000000001',
    label_schema_digest: 'a'.repeat(64),
    assignee_actor_id: 'local-synthetic-uploader',
    created_by: 'local-synthetic-admin',
    status: 'COMPLETED',
    can_submit: false,
    can_review: false,
    can_recheck_post_cleaning: false,
    annotation_revision: {
      annotation_revision_id: 'e2000000-0000-4000-8000-000000000001',
      label_schema_id: 'e1000000-0000-4000-8000-000000000001',
      label_schema_digest: 'a'.repeat(64),
      revision_number: 1,
      annotation_document: {
        schema_version: 'annotation-document.v1',
        segments: [{ start_ms: 0, end_ms: 1_000, label: 'PREPARE' }],
      },
      submitted_by: 'local-synthetic-uploader',
      submitted_at: '2026-08-10T01:01:00Z',
      review: {
        annotation_review_id: 'e3000000-0000-4000-8000-000000000001',
        project_id: projectId,
        annotation_revision_id: 'e2000000-0000-4000-8000-000000000001',
        decision: 'APPROVED',
        review_note: null,
        reviewed_by: 'local-synthetic-admin',
        reviewed_at: '2026-08-10T01:02:00Z',
      },
      post_cleaning_result: {
        cleaning_result_id: 'e4000000-0000-4000-8000-000000000001',
        cleaning_run_id: 'e5000000-0000-4000-8000-000000000001',
        rule_set_key: 'p0-post-annotation',
        rule_set_version: 'v1',
        rule_set_digest: 'b'.repeat(64),
        outcome: 'PASS',
        reason_codes: [],
        executed_checks: ['REVIEW_APPROVED'],
        evaluated_at: '2026-08-10T01:02:01Z',
      },
    },
    created_at: '2026-08-10T01:00:00Z',
    updated_at: '2026-08-10T01:02:01Z',
    version: 2,
  }
}
function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function emptyAnnotationTaskList() {
  return {
    items: [],
    progress: {
      scope: 'ASSIGNEE',
      total: 0,
      awaiting_submission: 0,
      rework_required: 0,
      awaiting_review: 0,
      completed: 0,
    },
  }
}
function unreviewedQuality() {
  return {
    quality_review_id: null,
    project_id: projectId,
    clip_asset_id: assetId,
    decision: 'UNREVIEWED',
    reason_code: null,
    reviewed_by: null,
    reviewed_at: null,
    version: 0,
  }
}
function verifiedMediaReport() {
  return {
    media_report_id: 'a0000000-0000-4000-8000-000000000001',
    media_job_id: 'b0000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: assetId,
    container_format: 'mov',
    duration_microseconds: 2_000_000,
    video_codec: 'h264',
    video_profile: 'High',
    width: 1280,
    height: 720,
    pixel_format: 'yuv420p',
    primary_video_bitrate_bps: 2_000_000,
    average_frame_rate_numerator: 30,
    average_frame_rate_denominator: 1,
    frame_count: 60,
    variable_frame_rate: false,
    audio_stream_count: 1,
    decode_verified: true,
    input_digest_verified: true,
    warning_codes: ['AUDIO_PRESENT'],
    created_at: '2026-07-22T01:02:03Z',
  }
}

function captureSessionListItem(captureSessionId: string, deviceId = 'synthetic-edge-01') {
  return {
    capture_session_id: captureSessionId,
    batch_id: '60000000-0000-4000-8000-000000000001',
    device_id: deviceId,
    camera_set_id: '70000000-0000-4000-8000-000000000001',
    camera_set_key: 'dispensing-cabin-a',
    camera_set_configuration_version: 1,
    camera_layout_version: 'layout-v1',
    expected_camera_count: 1,
    bound_stream_count: 1,
    received_camera_count: 1,
    ingest_failed_stream_count: 0,
    sync_group_id: 'sync-group-01',
    device_time_utc: '2026-07-15T01:02:03Z',
    server_receive_time: '2026-07-15T01:03:03Z',
    assembly_status: 'COMPLETE',
    quality_status: 'PASS',
    created_at: '2026-07-15T01:03:03Z',
  }
}

function captureSessionDetail() {
  return {
    capture_session_id: sessionId,
    project_id: projectId,
    batch_id: '60000000-0000-4000-8000-000000000001',
    device_id: 'synthetic-edge-01',
    metadata_upload_file_id: null,
    metadata_schema_version: '1.0',
    metadata_sha256: null,
    expected_camera_count: 1,
    received_camera_count: 1,
    sync_group_id: 'sync-group-01',
    device_time_utc: '2026-07-15T01:02:03Z',
    device_timezone: 'Asia/Shanghai',
    server_receive_time: '2026-07-15T01:03:03Z',
    assembly_status: 'COMPLETE',
    quality_status: 'PENDING',
    camera_set_snapshot: {
      camera_set_snapshot_id: '70000000-0000-4000-8000-000000000001',
      source_camera_set_id: '71000000-0000-4000-8000-000000000001',
      camera_set_key: 'dispensing-cabin-a',
      configuration_version: 1,
      layout_version: 'layout-v1',
      expected_camera_count: 1,
      members: [
        {
          camera_channel_id: '72000000-0000-4000-8000-000000000001',
          camera_index: 0,
          camera_role: 'overview',
          mount_position: 'top',
          enabled: true,
        },
      ],
    },
    streams: [
      {
        camera_stream_id: '41000000-0000-4000-8000-000000000001',
        clip_asset_id: assetId,
        client_file_id: '90000000-0000-4000-8000-000000000001',
        camera_channel_id: '72000000-0000-4000-8000-000000000001',
        camera_index: 0,
        camera_role: 'overview',
        mount_position: 'top',
        record_start_utc: '2026-07-15T01:02:03Z',
        time_offset_ms: 0,
        sync_status: 'PASS',
        stream_status: 'INGESTED',
        quality_status: 'PENDING',
      },
    ],
    created_at: '2026-07-15T01:03:03Z',
  }
}

function project(currentRole: 'ADMIN' | 'MEMBER') {
  return {
    project_id: projectId,
    display_name: '合成配药项目',
    status: 'ACTIVE',
    current_role: currentRole,
    created_at: '2026-07-15T01:00:00Z',
  }
}

function eligibleAdmissionPreview() {
  return {
    capture_session_id: sessionId,
    eligible: true,
    assembly_status: 'COMPLETE',
    quality_status: 'PASS',
    expected_camera_count: 1,
    received_camera_count: 1,
    blocking_reasons: [],
    streams: [
      {
        camera_stream_id: '41000000-0000-4000-8000-000000000001',
        clip_asset_id: assetId,
        camera_index: 0,
        eligible: true,
        stream_status: 'INGESTED',
        stream_quality_status: 'PASS',
        asset_status: 'INGESTED',
        asset_deleted: false,
        quality_review_decision: 'QUALIFIED',
        annotation_revision_id: '42000000-0000-4000-8000-000000000001',
        annotation_review_decision: 'APPROVED',
        post_cleaning_decision: 'PASS',
        annotation_document_sha256: 'a'.repeat(64),
        label_schema_digest: 'b'.repeat(64),
        blocking_reasons: [],
      },
    ],
  }
}

function datasetVersionDetail(datasetVersionId: string, displayName: string): DatasetVersionDetail {
  return {
    dataset_version_id: datasetVersionId,
    project_id: projectId,
    display_name: displayName,
    status: 'DRAFT',
    created_by: 'local-admin',
    created_at: '2026-07-28T03:00:00Z',
    split_plan: null,
    can_create_split_plan: false,
    build_specification: null,
    can_create_build_specification: false,
    build_snapshot: null,
    can_create_build_snapshot: false,
    validation: null,
    can_validate: false,
    freeze_approval: null,
    can_freeze: false,
    export_request: null,
    can_request_export: false,
    members: [
      {
        capture_session_id: sessionId,
        candidate_index: 0,
        expected_camera_count: 1,
        received_camera_count: 1,
        assembly_status: 'COMPLETE',
        quality_status: 'PASS',
        streams: [
          {
            camera_stream_id: '42000000-0000-4000-8000-000000000001',
            clip_asset_id: assetId,
            camera_index: 0,
            asset_sha256: 'a'.repeat(64),
            quality_review_decision: 'QUALIFIED',
            quality_review_version: 2,
            annotation_revision_id: '43000000-0000-4000-8000-000000000001',
            annotation_document_sha256: 'b'.repeat(64),
            label_schema_digest: 'c'.repeat(64),
            cleaning_result_id: '44000000-0000-4000-8000-000000000001',
            cleaning_run_id: '45000000-0000-4000-8000-000000000001',
            cleaning_rule_set_digest: 'd'.repeat(64),
            post_cleaning_outcome: 'PASS',
          },
        ],
      },
    ],
  }
}

function datasetExport(
  datasetVersionId: string,
  overrides: Partial<DatasetExport> = {},
): DatasetExport {
  return {
    dataset_export_id: '98000000-0000-4000-8000-000000000001',
    project_id: projectId,
    dataset_version_id: datasetVersionId,
    status: 'REQUESTED',
    purpose: 'MODEL_TRAINING',
    expires_at: '2026-08-06T02:00:00Z',
    dataset_build_specification_id: '93000000-0000-4000-8000-000000000001',
    dataset_build_snapshot_id: '94000000-0000-4000-8000-000000000001',
    snapshot_digest: 'f'.repeat(64),
    dataset_version_validation_id: '95000000-0000-4000-8000-000000000001',
    export_profile_key: 'p0-dataset-export-v2',
    export_profile_digest: 'e'.repeat(64),
    approval: {
      approval_request_id: '99000000-0000-4000-8000-000000000001',
      validation_id: '95000000-0000-4000-8000-000000000001',
      status: 'PENDING',
      requested_by: 'local-admin',
      requested_at: '2026-07-30T02:00:00Z',
      approval_decision_id: null,
      decided_by: null,
      decided_at: null,
    },
    requested_by: 'local-admin',
    requested_at: '2026-07-30T02:00:00Z',
    approved_by: null,
    approved_at: null,
    started_at: null,
    completed_at: null,
    attempt: null,
    artifact: null,
    ...overrides,
  }
}
