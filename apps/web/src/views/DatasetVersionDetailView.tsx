import { App as AntdApp, Button, Card, DatePicker, Descriptions, Empty, InputNumber, Table, Tag } from 'antd'
import type { Dayjs } from 'dayjs'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  approveDatasetExport,
  authorizeDatasetExportDownload,
  CatalogApiError,
  createDatasetBuildSnapshot,
  createDatasetBuildSpecification,
  createDatasetSplitPlan,
  freezeDatasetVersion,
  getDatasetExportApprovalReadiness,
  getDatasetVersion,
  getDatasetVersionCurrentAdmission,
  requestDatasetExport,
  startDatasetVersionBuild,
  validateDatasetVersion,
  type DatasetAdmissionPreview,
  type DatasetExport,
  type DatasetExportApprovalBlocker,
  type DatasetExportApprovalReadiness,
  type DatasetVersionDetail,
  type DatasetVersionBuildBlocker,
  type DatasetVersionCurrentAdmission,
  type DatasetVersionCurrentAdmissionEvidenceChange,
  type DatasetVersionCurrentAdmissionMember,
  type DatasetVersionFreezeApproval,
  type DatasetVersionMemberSession,
  type DatasetVersionMemberStream,
  type DatasetVersionStatus,
  type DatasetVersionValidationReason,
  type DatasetSplitAssignment,
} from '@/api/catalog'
import { paths } from '@/app/paths'
import {
  assemblyStatusLabels,
  formatBytes,
  formatDateTime,
  qualityStatusLabels,
  shortId,
  statusTone,
} from '@/catalog-presentation'

const admissionReasonLabels: Record<string, string> = {
  SESSION_ASSEMBLY_INCOMPLETE: '本次配药操作的视频流尚未组装完整',
  SESSION_STREAM_COUNT_MISMATCH: '已接收的视频流数量与相机配置不一致',
  SESSION_QUALITY_NOT_PASS: '本次配药操作的技术检测尚未通过',
  STREAM_NOT_INGESTED: '有相机视频尚未完成媒体入库',
  STREAM_QUALITY_REJECTED: '有相机视频未通过技术检测',
  CLIP_ASSET_NOT_INGESTED: '有视频资产尚未完成媒体检测',
  CLIP_ASSET_DELETED: '有视频已经删除',
  QUALITY_REVIEW_NOT_QUALIFIED: '有视频尚未通过人工质量判断',
  QUALITY_REVIEW_CHANGED_AFTER_POST_CLEANING: '人工质量结论已变更，需要重新完成清洗检查',
  ANNOTATION_REVISION_MISSING: '有视频尚未提交人工标注',
  ANNOTATION_REVIEW_NOT_APPROVED: '有标注尚未通过独立复核',
  POST_CLEANING_MISSING: '有已复核标注尚未完成清洗检查',
  POST_CLEANING_WARNING: '有标注清洗结果需要人工确认',
  POST_CLEANING_REJECT: '有标注未通过清洗检查',
}

const evidenceChangeLabels: Record<string, string> = {
  SESSION_EXPECTED_CAMERA_COUNT_CHANGED: '预期相机数量已变化',
  SESSION_RECEIVED_CAMERA_COUNT_CHANGED: '已接收相机数量已变化',
  SESSION_ASSEMBLY_STATUS_CHANGED: '组装状态已变化',
  SESSION_QUALITY_STATUS_CHANGED: '技术检测状态已变化',
  STREAM_ADDED: '发现新增视频流',
  STREAM_REMOVED: '原有视频流已移除',
  STREAM_ASSET_SHA256_CHANGED: '视频资产摘要已变化',
  STREAM_QUALITY_REVIEW_DECISION_CHANGED: '人工质量结论已变化',
  STREAM_QUALITY_REVIEW_VERSION_CHANGED: '人工质量版本已变化',
  STREAM_ANNOTATION_REVISION_CHANGED: '标注修订已变化',
  STREAM_ANNOTATION_DOCUMENT_SHA256_CHANGED: '标注文档摘要已变化',
  STREAM_LABEL_SCHEMA_DIGEST_CHANGED: '标签规范摘要已变化',
  STREAM_CLEANING_RESULT_CHANGED: '清洗结果已变化',
  STREAM_CLEANING_RUN_CHANGED: '清洗运行已变化',
  STREAM_CLEANING_RULE_SET_DIGEST_CHANGED: '清洗规则摘要已变化',
  STREAM_POST_CLEANING_OUTCOME_CHANGED: '后置清洗结论已变化',
}

const buildBlockerLabels: Record<DatasetVersionBuildBlocker, string> = {
  SELECTION_RULE_NOT_FIXED: '尚未固定候选选择规则',
  LABEL_SCHEMA_NOT_FIXED: '尚未把统一标签规范固定为数据集配置',
  LABEL_SCHEMA_NOT_UNIFORM: '候选视频使用了不同的标签规范',
  CLEANING_RULE_SET_NOT_FIXED: '尚未把统一清洗规则固定为数据集配置',
  CLEANING_RULE_SET_NOT_UNIFORM: '候选视频使用了不同的清洗规则',
  SPLIT_PLAN_NOT_FIXED: '尚未固定试用组和留出组划分',
  EXPORT_PROFILE_NOT_FIXED: '尚未固定必需导出配置',
  CURRENT_ADMISSION_NOT_ELIGIBLE: '至少一条候选操作当前不再满足准入条件',
  SNAPSHOT_EVIDENCE_CHANGED: '当前事实与草稿固定证据存在变化',
}

const datasetVersionStatusLabels: Record<DatasetVersionStatus, string> = {
  DRAFT: '草稿',
  BUILDING: '构建中',
  VALIDATING: '校验中',
  FROZEN: '已冻结',
}

const validationReasonLabels: Record<DatasetVersionValidationReason, string> = {
  CURRENT_ADMISSION_NOT_ELIGIBLE: '至少一条配药操作当前不再满足数据集准入条件',
  SNAPSHOT_EVIDENCE_CHANGED: '当前视频、标注、质量或清洗事实与固定快照不一致',
  SNAPSHOT_REFERENCE_MISMATCH: '快照引用或数量与当前版本不一致',
  SNAPSHOT_STRUCTURE_INVALID: '固定成员、视频、划分或构建配置关系不完整',
  SNAPSHOT_DIGEST_MISMATCH: '重新计算的关系快照摘要与封存摘要不一致',
  SPLIT_LEAKAGE_DETECTED: '同一配药操作泄漏组出现在不同数据划分中',
  BUILD_CONTRACT_MISMATCH: '固定构建规范与当前批准的 P0 契约不一致',
}

const exportApprovalBlockerLabels: Record<DatasetExportApprovalBlocker, string> = {
  APPROVER_ADMIN_REQUIRED: '只有当前项目管理员可以批准导出',
  SELF_APPROVAL_FORBIDDEN: '导出申请人不能审批自己的申请',
  EXPORT_AUTHORIZATION_EXPIRED: '本次训练用途授权已经到期',
  DATASET_VERSION_NOT_FROZEN: '数据集版本当前不是冻结状态',
  EXPORT_REQUEST_INVALID: '导出申请与当前数据集版本不一致',
  APPROVAL_REQUEST_INVALID: '待审批记录与导出申请不一致',
  EXPORT_PROFILE_NOT_EXECUTABLE: '该历史导出配置当前没有经过批准的真实生成器',
  FROZEN_EVIDENCE_INVALID: '冻结版本缺少一致的构建、快照或校验证据',
  SNAPSHOT_VALIDATION_MISMATCH: '冻结校验与当前快照不一致',
  CURRENT_ADMISSION_NOT_ELIGIBLE: validationReasonLabels.CURRENT_ADMISSION_NOT_ELIGIBLE,
  SNAPSHOT_EVIDENCE_CHANGED: validationReasonLabels.SNAPSHOT_EVIDENCE_CHANGED,
  SNAPSHOT_REFERENCE_MISMATCH: validationReasonLabels.SNAPSHOT_REFERENCE_MISMATCH,
  SNAPSHOT_STRUCTURE_INVALID: validationReasonLabels.SNAPSHOT_STRUCTURE_INVALID,
  SNAPSHOT_DIGEST_MISMATCH: validationReasonLabels.SNAPSHOT_DIGEST_MISMATCH,
  SPLIT_LEAKAGE_DETECTED: validationReasonLabels.SPLIT_LEAKAGE_DETECTED,
  BUILD_CONTRACT_MISMATCH: validationReasonLabels.BUILD_CONTRACT_MISMATCH,
}

type ExportApprovalReadinessState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; value: DatasetExportApprovalReadiness }
  | { status: 'error'; errorCode: string; errorMessage: string; requestId: string | null }

export default function DatasetVersionDetailView() {
  const { projectId = '', id: datasetVersionId = '' } = useParams()
  return <DatasetVersionDetailContent key={`${projectId}:${datasetVersionId}`} />
}

function DatasetVersionDetailContent() {
  const { projectId = '', id: datasetVersionId = '' } = useParams()
  const { message, modal } = AntdApp.useApp()
  const [detail, setDetail] = useState<DatasetVersionDetail | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [requestId, setRequestId] = useState<string | null>(null)
  const [currentAdmission, setCurrentAdmission] = useState<DatasetVersionCurrentAdmission | null>(null)
  const [currentAdmissionState, setCurrentAdmissionState] = useState<'idle' | 'loading' | 'success' | 'error'>(
    'idle',
  )
  const [currentAdmissionErrorCode, setCurrentAdmissionErrorCode] = useState('')
  const [currentAdmissionErrorMessage, setCurrentAdmissionErrorMessage] = useState('')
  const [currentAdmissionRequestId, setCurrentAdmissionRequestId] = useState<string | null>(null)
  const [holdoutCount, setHoldoutCount] = useState(1)
  const [splitPlanState, setSplitPlanState] = useState<'idle' | 'creating' | 'error'>('idle')
  const [splitPlanErrorCode, setSplitPlanErrorCode] = useState('')
  const [splitPlanErrorMessage, setSplitPlanErrorMessage] = useState('')
  const [splitPlanRequestId, setSplitPlanRequestId] = useState<string | null>(null)
  const [buildSpecificationState, setBuildSpecificationState] = useState<'idle' | 'creating' | 'error'>('idle')
  const [buildSpecificationErrorCode, setBuildSpecificationErrorCode] = useState('')
  const [buildSpecificationErrorMessage, setBuildSpecificationErrorMessage] = useState('')
  const [buildSpecificationRequestId, setBuildSpecificationRequestId] = useState<string | null>(null)
  const [buildStartState, setBuildStartState] = useState<'idle' | 'starting' | 'error'>('idle')
  const [buildStartErrorCode, setBuildStartErrorCode] = useState('')
  const [buildStartErrorMessage, setBuildStartErrorMessage] = useState('')
  const [buildStartRequestId, setBuildStartRequestId] = useState<string | null>(null)
  const [buildSnapshotState, setBuildSnapshotState] = useState<'idle' | 'creating' | 'error'>('idle')
  const [buildSnapshotErrorCode, setBuildSnapshotErrorCode] = useState('')
  const [buildSnapshotErrorMessage, setBuildSnapshotErrorMessage] = useState('')
  const [buildSnapshotRequestId, setBuildSnapshotRequestId] = useState<string | null>(null)
  const [validationState, setValidationState] = useState<'idle' | 'validating' | 'error'>('idle')
  const [validationErrorCode, setValidationErrorCode] = useState('')
  const [validationErrorMessage, setValidationErrorMessage] = useState('')
  const [validationRequestId, setValidationRequestId] = useState<string | null>(null)
  const [freezeState, setFreezeState] = useState<'idle' | 'freezing' | 'error'>('idle')
  const [freezeErrorCode, setFreezeErrorCode] = useState('')
  const [freezeErrorMessage, setFreezeErrorMessage] = useState('')
  const [freezeRequestId, setFreezeRequestId] = useState<string | null>(null)
  const [exportExpiresAt, setExportExpiresAt] = useState<Dayjs | null>(null)
  const [exportRequestState, setExportRequestState] = useState<'idle' | 'requesting' | 'error'>('idle')
  const [exportErrorCode, setExportErrorCode] = useState('')
  const [exportErrorMessage, setExportErrorMessage] = useState('')
  const [exportRequestId, setExportRequestId] = useState<string | null>(null)
  const activeRequest = useRef<AbortController | null>(null)
  const currentAdmissionRequest = useRef<AbortController | null>(null)
  const splitPlanIntent = useRef<{ holdoutCount: number; idempotencyKey: string } | null>(null)
  const buildSpecificationIntent = useRef<string | null>(null)
  const buildStartIntent = useRef<string | null>(null)
  const buildSnapshotIntent = useRef<string | null>(null)
  const validationIntent = useRef<string | null>(null)
  const freezeIntent = useRef<string | null>(null)
  const exportIntent = useRef<{ expiresAt: string; idempotencyKey: string } | null>(null)

  const streamCount = useMemo(
    () => detail?.members.reduce((total, member) => total + member.streams.length, 0) ?? 0,
    [detail],
  )

  const loadDetail = useCallback(async () => {
    activeRequest.current?.abort()
    const request = new AbortController()
    activeRequest.current = request
    setLoadState('loading')
    setErrorCode('')
    setErrorMessage('')
    setRequestId(null)
    try {
      const response = await getDatasetVersion(projectId, datasetVersionId, request.signal)
      if (activeRequest.current !== request) return
      setDetail(response)
      setLoadState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      setDetail(null)
      setLoadState('error')
      if (error instanceof CatalogApiError) {
        setErrorCode(error.errorCode)
        setErrorMessage(error.message)
        setRequestId(error.requestId)
      } else {
        setErrorCode('DATASET_VERSION_DETAIL_FAILED')
        setErrorMessage('暂时无法读取数据集草稿证据，请稍后重试。')
      }
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }, [datasetVersionId, projectId])

  const checkCurrentAdmission = useCallback(async () => {
    currentAdmissionRequest.current?.abort()
    const request = new AbortController()
    currentAdmissionRequest.current = request
    setCurrentAdmission(null)
    setCurrentAdmissionState('loading')
    setCurrentAdmissionErrorCode('')
    setCurrentAdmissionErrorMessage('')
    setCurrentAdmissionRequestId(null)
    setBuildStartState('idle')
    setBuildStartErrorCode('')
    setBuildStartErrorMessage('')
    setBuildStartRequestId(null)
    try {
      const response = await getDatasetVersionCurrentAdmission(projectId, datasetVersionId, request.signal)
      if (currentAdmissionRequest.current !== request) return
      setCurrentAdmission(response)
      setCurrentAdmissionState('success')
    } catch (error) {
      if (request.signal.aborted || currentAdmissionRequest.current !== request) return
      setCurrentAdmission(null)
      setCurrentAdmissionState('error')
      if (error instanceof CatalogApiError) {
        setCurrentAdmissionErrorCode(error.errorCode)
        setCurrentAdmissionErrorMessage(error.message)
        setCurrentAdmissionRequestId(error.requestId)
      } else {
        setCurrentAdmissionErrorCode('DATASET_VERSION_CURRENT_ADMISSION_FAILED')
        setCurrentAdmissionErrorMessage('暂时无法检查构建准备，请稍后重试。')
      }
    } finally {
      if (currentAdmissionRequest.current === request) currentAdmissionRequest.current = null
    }
  }, [datasetVersionId, projectId])

  const createSplit = useCallback(async () => {
    const intent =
      splitPlanIntent.current?.holdoutCount === holdoutCount
        ? splitPlanIntent.current
        : { holdoutCount, idempotencyKey: crypto.randomUUID() }
    splitPlanIntent.current = intent
    setSplitPlanState('creating')
    setSplitPlanErrorCode('')
    setSplitPlanErrorMessage('')
    setSplitPlanRequestId(null)
    try {
      const splitPlan = await createDatasetSplitPlan(
        projectId,
        datasetVersionId,
        intent.holdoutCount,
        intent.idempotencyKey,
      )
      setDetail((current) =>
        current ? { ...current, split_plan: splitPlan, can_create_split_plan: false } : current,
      )
      splitPlanIntent.current = null
      setSplitPlanState('idle')
      void message.success('数据划分已固定到当前草稿。')
    } catch (error) {
      setSplitPlanState('error')
      if (error instanceof CatalogApiError) {
        setSplitPlanErrorCode(error.errorCode)
        setSplitPlanErrorMessage(error.message)
        setSplitPlanRequestId(error.requestId)
      } else {
        setSplitPlanErrorCode('DATASET_SPLIT_PLAN_FAILED')
        setSplitPlanErrorMessage('暂时无法生成数据划分，请稍后重试。')
      }
    }
  }, [datasetVersionId, holdoutCount, message, projectId])

  const confirmSplit = useCallback(() => {
    modal.confirm({
      title: '固定当前数据划分？',
      content: `将 ${holdoutCount} 组设为留出组，其余设为试用组。划分在当前草稿内不可修改；如需其他划分，应新建草稿。`,
      okText: '固定划分',
      cancelText: '取消',
      onOk: createSplit,
    })
  }, [createSplit, holdoutCount, modal])

  const fixBuildSpecification = useCallback(async () => {
    const idempotencyKey = buildSpecificationIntent.current ?? crypto.randomUUID()
    buildSpecificationIntent.current = idempotencyKey
    setBuildSpecificationState('creating')
    setBuildSpecificationErrorCode('')
    setBuildSpecificationErrorMessage('')
    setBuildSpecificationRequestId(null)
    try {
      const specification = await createDatasetBuildSpecification(
        projectId,
        datasetVersionId,
        idempotencyKey,
      )
      setDetail((current) =>
        current
          ? {
              ...current,
              build_specification: specification,
              can_create_build_specification: false,
            }
          : current,
      )
      setCurrentAdmission(null)
      setCurrentAdmissionState('idle')
      buildSpecificationIntent.current = null
      setBuildSpecificationState('idle')
      void message.success('构建配置已固定到当前草稿。')
    } catch (error) {
      setBuildSpecificationState('error')
      if (error instanceof CatalogApiError) {
        setBuildSpecificationErrorCode(error.errorCode)
        setBuildSpecificationErrorMessage(error.message)
        setBuildSpecificationRequestId(error.requestId)
      } else {
        setBuildSpecificationErrorCode('DATASET_BUILD_SPECIFICATION_FAILED')
        setBuildSpecificationErrorMessage('暂时无法固定构建配置，请稍后重试。')
      }
    }
  }, [datasetVersionId, message, projectId])

  const confirmBuildSpecification = useCallback(() => {
    modal.confirm({
      title: '固定当前构建配置？',
      content: '将当前候选快照、统一标签规范、清洗规则、数据划分和 P0 必需导出绑定到该草稿。固定后如需调整，应新建草稿。',
      okText: '固定构建配置',
      cancelText: '取消',
      onOk: fixBuildSpecification,
    })
  }, [fixBuildSpecification, modal])

  const startBuild = useCallback(async () => {
    const idempotencyKey = buildStartIntent.current ?? crypto.randomUUID()
    buildStartIntent.current = idempotencyKey
    setBuildStartState('starting')
    setBuildStartErrorCode('')
    setBuildStartErrorMessage('')
    setBuildStartRequestId(null)
    try {
      await startDatasetVersionBuild(projectId, datasetVersionId, idempotencyKey)
      buildStartIntent.current = null
      setBuildStartState('idle')
      setCurrentAdmission(null)
      setCurrentAdmissionState('idle')
      await loadDetail()
      void message.success('数据集已进入构建阶段。')
    } catch (error) {
      setBuildStartState('error')
      if (error instanceof CatalogApiError) {
        setBuildStartErrorCode(error.errorCode)
        setBuildStartErrorMessage(error.message)
        setBuildStartRequestId(error.requestId)
      } else {
        setBuildStartErrorCode('DATASET_VERSION_BUILD_FAILED')
        setBuildStartErrorMessage('暂时无法开始构建，请稍后重试。')
      }
    }
  }, [datasetVersionId, loadDetail, message, projectId])

  const confirmBuildStart = useCallback(() => {
    modal.confirm({
      title: '开始构建当前数据集？',
      content: '系统会再次核对当前准入和固定证据。通过后版本进入构建阶段，既有划分和配置不能修改；此操作不会冻结或导出数据。',
      okText: '开始构建',
      cancelText: '取消',
      onOk: startBuild,
    })
  }, [modal, startBuild])

  const sealBuildSnapshot = useCallback(async () => {
    const idempotencyKey = buildSnapshotIntent.current ?? crypto.randomUUID()
    buildSnapshotIntent.current = idempotencyKey
    setBuildSnapshotState('creating')
    setBuildSnapshotErrorCode('')
    setBuildSnapshotErrorMessage('')
    setBuildSnapshotRequestId(null)
    try {
      await createDatasetBuildSnapshot(projectId, datasetVersionId, idempotencyKey)
      buildSnapshotIntent.current = null
      setBuildSnapshotState('idle')
      setCurrentAdmission(null)
      setCurrentAdmissionState('idle')
      await loadDetail()
      void message.success('构建快照已生成，数据集进入校验阶段。')
    } catch (error) {
      setBuildSnapshotState('error')
      if (error instanceof CatalogApiError) {
        setBuildSnapshotErrorCode(error.errorCode)
        setBuildSnapshotErrorMessage(error.message)
        setBuildSnapshotRequestId(error.requestId)
      } else {
        setBuildSnapshotErrorCode('DATASET_BUILD_SNAPSHOT_FAILED')
        setBuildSnapshotErrorMessage('暂时无法生成构建快照，请稍后重试。')
      }
    }
  }, [datasetVersionId, loadDetail, message, projectId])

  const confirmBuildSnapshot = useCallback(() => {
    modal.confirm({
      title: '生成当前构建快照？',
      content: '系统会封存已固定的配药操作、视频、标注、清洗和划分摘要。完成后版本进入校验阶段；此操作不会冻结或导出数据。',
      okText: '生成构建快照',
      cancelText: '取消',
      onOk: sealBuildSnapshot,
    })
  }, [modal, sealBuildSnapshot])

  const runValidation = useCallback(async () => {
    const idempotencyKey = validationIntent.current ?? crypto.randomUUID()
    validationIntent.current = idempotencyKey
    setValidationState('validating')
    setValidationErrorCode('')
    setValidationErrorMessage('')
    setValidationRequestId(null)
    try {
      const result = await validateDatasetVersion(projectId, datasetVersionId, idempotencyKey)
      validationIntent.current = null
      setValidationState('idle')
      setCurrentAdmission(null)
      setCurrentAdmissionState('idle')
      await loadDetail()
      if (result.outcome === 'PASS') {
        void message.success('冻结前校验已通过，已提交独立审批。')
      } else {
        void message.warning('冻结前校验未通过，请根据结果新建数据集版本。')
      }
    } catch (error) {
      setValidationState('error')
      if (error instanceof CatalogApiError) {
        setValidationErrorCode(error.errorCode)
        setValidationErrorMessage(error.message)
        setValidationRequestId(error.requestId)
      } else {
        setValidationErrorCode('DATASET_VERSION_VALIDATION_FAILED')
        setValidationErrorMessage('暂时无法执行冻结前校验，请稍后重试。')
      }
    }
  }, [datasetVersionId, loadDetail, message, projectId])

  const confirmValidation = useCallback(() => {
    modal.confirm({
      title: '执行冻结前校验？',
      content:
        '系统会核对当前准入、固定引用、快照摘要、数据划分和构建契约，并保存一次不可覆盖的结果。未通过时应新建版本；通过也不会自动冻结，只会提交独立审批请求。',
      okText: '执行冻结前校验',
      cancelText: '取消',
      onOk: runValidation,
    })
  }, [modal, runValidation])

  const runFreeze = useCallback(async () => {
    const idempotencyKey = freezeIntent.current ?? crypto.randomUUID()
    freezeIntent.current = idempotencyKey
    setFreezeState('freezing')
    setFreezeErrorCode('')
    setFreezeErrorMessage('')
    setFreezeRequestId(null)
    try {
      await freezeDatasetVersion(projectId, datasetVersionId, idempotencyKey)
      freezeIntent.current = null
      setFreezeState('idle')
      setCurrentAdmission(null)
      setCurrentAdmissionState('idle')
      await loadDetail()
      void message.success('独立审批已记录，数据集版本已冻结。')
    } catch (error) {
      setFreezeState('error')
      if (error instanceof CatalogApiError) {
        setFreezeErrorCode(error.errorCode)
        setFreezeErrorMessage(error.message)
        setFreezeRequestId(error.requestId)
      } else {
        setFreezeErrorCode('DATASET_VERSION_FREEZE_FAILED')
        setFreezeErrorMessage('暂时无法批准并冻结数据集版本，请稍后重试。')
      }
    }
  }, [datasetVersionId, loadDetail, message, projectId])

  const confirmFreeze = useCallback(() => {
    modal.confirm({
      title: '批准并冻结当前数据集版本？',
      content:
        '系统会重新核对全部技术证据，由当前管理员记录独立审批并立即冻结。冻结后不可修改，只能新建版本；此操作不会导出数据。',
      okText: '批准并冻结',
      cancelText: '取消',
      onOk: runFreeze,
    })
  }, [modal, runFreeze])

  const submitExportRequest = useCallback(async () => {
    if (!exportExpiresAt || exportExpiresAt.valueOf() <= Date.now()) {
      void message.error('请选择晚于当前时间的授权到期时间。')
      return
    }
    const expiresAt = exportExpiresAt.toISOString()
    const intent =
      exportIntent.current?.expiresAt === expiresAt
        ? exportIntent.current
        : { expiresAt, idempotencyKey: crypto.randomUUID() }
    exportIntent.current = intent
    setExportRequestState('requesting')
    setExportErrorCode('')
    setExportErrorMessage('')
    setExportRequestId(null)
    try {
      const exportRequest = await requestDatasetExport(
        projectId,
        datasetVersionId,
        intent.expiresAt,
        intent.idempotencyKey,
      )
      setDetail((current) =>
        current
          ? {
              ...current,
              export_request: exportRequest,
              can_request_export: false,
            }
          : current,
      )
      exportIntent.current = null
      setExportExpiresAt(null)
      setExportRequestState('idle')
      void message.success('导出申请已提交，正在等待独立审批。')
    } catch (error) {
      setExportRequestState('error')
      if (error instanceof CatalogApiError) {
        setExportErrorCode(error.errorCode)
        setExportErrorMessage(error.message)
        setExportRequestId(error.requestId)
      } else {
        setExportErrorCode('DATASET_EXPORT_REQUEST_FAILED')
        setExportErrorMessage('暂时无法提交导出申请，请稍后重试。')
      }
    }
  }, [datasetVersionId, exportExpiresAt, message, projectId])

  const confirmExportRequest = useCallback(() => {
    if (!exportExpiresAt || exportExpiresAt.valueOf() <= Date.now()) {
      void message.error('请选择晚于当前时间的授权到期时间。')
      return
    }
    modal.confirm({
      title: '提交数据集导出申请？',
      content: `申请用途为“内部模型训练”，授权到期时间为 ${exportExpiresAt.format('YYYY-MM-DD HH:mm')}。提交后只记录待审批申请，不会立即生成或下载文件。`,
      okText: '提交申请',
      cancelText: '取消',
      onOk: submitExportRequest,
    })
  }, [exportExpiresAt, message, modal, submitExportRequest])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadDetail(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
      activeRequest.current = null
      currentAdmissionRequest.current?.abort()
      currentAdmissionRequest.current = null
    }
  }, [loadDetail])

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to={paths.datasets(projectId)}>数据集版本</Link>
        <span aria-hidden="true">/</span>
        <span>证据详情</span>
      </nav>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取不可变候选快照…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{errorCode}</strong>
          <span>{errorMessage}</span>
          {requestId ? <small>请求标识：{requestId}</small> : null}
          <Button onClick={() => void loadDetail()}>重试</Button>
        </div>
      ) : null}
      {loadState === 'success' && detail ? (
        <>
          <section className="page-heading detail-heading" aria-labelledby="dataset-version-title">
            <div>
              <p className="app-eyebrow">
                {detail.status === 'DRAFT'
                  ? '不可变候选快照'
                  : detail.status === 'BUILDING'
                    ? '数据集构建'
                    : detail.status === 'VALIDATING'
                      ? '数据集校验'
                      : '冻结数据集'}
              </p>
              <h1 id="dataset-version-title">{detail.display_name}</h1>
              <p>
                {detail.status === 'DRAFT'
                  ? '以下证据只反映创建草稿时的事实；数据划分与构建配置可依次固定，冻结和导出仍须后续验证。'
                  : detail.status === 'BUILDING'
                    ? '构建配置已经封存；生成不可变快照后才能进入校验，冻结和导出仍须后续完成。'
                    : detail.status === 'VALIDATING'
                      ? '构建快照已经封存并进入校验阶段；当前状态不代表已经冻结或可以导出。'
                      : '版本已完成独立审批并冻结；成员、标注、规则和划分不可修改，当前仍未导出。'}
              </p>
            </div>
            <div className="heading-tags">
              <Tag color={detail.status === 'FROZEN' ? 'success' : detail.status === 'DRAFT' ? 'blue' : 'processing'}>
                {datasetVersionStatusLabels[detail.status]}
              </Tag>
            </div>
          </section>
          <Card
            className="detail-card"
            title={<strong>{detail.status === 'DRAFT' ? '草稿事实' : '版本事实'}</strong>}
            variant="outlined"
          >
            <Descriptions
              column={{ xs: 1, sm: 2, lg: 4 }}
              items={[
                { key: 'creator', label: '创建人', children: detail.created_by },
                { key: 'created', label: '创建时间', children: formatDateTime(detail.created_at) },
                { key: 'sessions', label: '候选操作', children: `${detail.members.length} 组` },
                { key: 'streams', label: '候选视频流', children: `${streamCount} 路` },
              ]}
            />
            <details className="technical-facts">
              <summary>{detail.status === 'DRAFT' ? '查看草稿标识' : '查看版本标识'}</summary>
              <dl>
                <div>
                  <dt>DatasetVersion ID</dt>
                  <dd>
                    <code>{detail.dataset_version_id}</code>
                  </dd>
                </div>
              </dl>
            </details>
          </Card>
          <Card className="detail-card" title={<strong>数据划分</strong>} variant="outlined">
            <p className="form-hint">
              每条配药操作只会进入一个组，避免同一操作同时出现在试用组和留出组；结果只属于当前草稿。
            </p>
            {detail.split_plan ? (
              <SplitPlanEvidence projectId={projectId} splitPlan={detail.split_plan} />
            ) : detail.members.length < 2 ? (
              <p>当前只有一条候选配药操作，暂时无法同时建立试用组和留出组。</p>
            ) : detail.can_create_split_plan ? (
              <div className="dataset-draft-selection-toolbar">
                <div>
                  <strong>固定当前划分</strong>
                  <span>至少保留一组作为试用组；固定后如需调整，应新建数据集草稿。</span>
                </div>
                <div className="dataset-draft-selection-actions">
                  <label htmlFor="dataset-holdout-count">留出组数</label>
                  <InputNumber
                    id="dataset-holdout-count"
                    min={1}
                    max={detail.members.length - 1}
                    precision={0}
                    value={holdoutCount}
                    onChange={(value) => setHoldoutCount(value ?? 1)}
                  />
                  <Button
                    type="primary"
                    loading={splitPlanState === 'creating'}
                    onClick={confirmSplit}
                  >
                    固定划分
                  </Button>
                </div>
              </div>
            ) : (
              <p>尚未建立数据划分，请由项目管理员完成。</p>
            )}
            {splitPlanState === 'error' ? (
              <div className="catalog-error" role="alert">
                <strong>{splitPlanErrorCode}</strong>
                <span>{splitPlanErrorMessage}</span>
                {splitPlanRequestId ? <small>请求标识：{splitPlanRequestId}</small> : null}
              </div>
            ) : null}
          </Card>
          <Card
            className="detail-card"
            extra={
              detail.status === 'FROZEN' ? null : (
                <Button
                  disabled={
                    buildStartState === 'starting'
                    || buildSnapshotState === 'creating'
                    || validationState === 'validating'
                    || freezeState === 'freezing'
                  }
                  loading={currentAdmissionState === 'loading'}
                  onClick={() => void checkCurrentAdmission()}
                >
                  {detail.status === 'DRAFT' ? '检查构建准备' : '检查当前证据'}
                </Button>
              )
            }
            title={<strong>构建准备</strong>}
            variant="outlined"
          >
            <p className="form-hint">
              {detail.status === 'DRAFT'
                ? '只读取当前事实并核对草稿条件，不修改候选快照。通过本检查也不代表已经冻结或可以导出。'
                : detail.status === 'FROZEN'
                  ? '以下记录共同证明该版本经过技术校验、独立审批并冻结；冻结不会自动导出数据。'
                  : '固定证据与当前事实分开显示；构建和校验不会自动冻结或导出数据。'}
            </p>
            {detail.build_specification ? (
              <BuildSpecificationEvidence specification={detail.build_specification} />
            ) : detail.can_create_build_specification ? (
              <div className="dataset-draft-selection-toolbar">
                <div>
                  <strong>固定构建配置</strong>
                  <span>绑定当前候选、统一规则、数据划分和 P0 必需导出；固定后不可修改。</span>
                </div>
                <Button
                  type="primary"
                  loading={buildSpecificationState === 'creating'}
                  onClick={confirmBuildSpecification}
                >
                  固定构建配置
                </Button>
              </div>
            ) : detail.split_plan ? (
              <p>尚未固定构建配置，请由项目管理员确认候选视频使用统一的标签规范和清洗规则。</p>
            ) : (
              <p>请先固定数据划分，再固定构建配置。</p>
            )}
            {detail.build_snapshot ? (
              <BuildSnapshotEvidence snapshot={detail.build_snapshot} />
            ) : detail.can_create_build_snapshot ? (
              <div className="dataset-draft-selection-toolbar">
                <div>
                  <strong>生成构建快照</strong>
                  <span>封存当前成员、视频、标注、清洗和划分摘要，并进入校验阶段。</span>
                </div>
                <Button
                  type="primary"
                  loading={buildSnapshotState === 'creating'}
                  onClick={confirmBuildSnapshot}
                >
                  生成构建快照
                </Button>
              </div>
            ) : detail.status === 'BUILDING' ? (
              <p>构建配置已经封存，请由项目管理员生成构建快照。</p>
            ) : null}
            {detail.validation ? (
              <ValidationEvidence
                validation={detail.validation}
                approved={detail.freeze_approval?.status === 'APPROVED'}
              />
            ) : detail.can_validate ? (
              <div className="dataset-draft-selection-toolbar">
                <div>
                  <strong>执行冻结前校验</strong>
                  <span>核对当前准入、引用、摘要、数据划分和构建契约；结果只属于当前版本。</span>
                </div>
                <Button
                  type="primary"
                  loading={validationState === 'validating'}
                  onClick={confirmValidation}
                >
                  执行冻结前校验
                </Button>
              </div>
            ) : detail.status === 'VALIDATING' ? (
              <p>构建快照已封存，请由项目管理员执行冻结前校验。</p>
            ) : null}
            {detail.freeze_approval ? (
              <FreezeApprovalEvidence
                approval={detail.freeze_approval}
                canFreeze={detail.can_freeze}
                freezing={freezeState === 'freezing'}
                onFreeze={confirmFreeze}
              />
            ) : null}
            {buildSpecificationState === 'error' ? (
              <div className="catalog-error" role="alert">
                <strong>{buildSpecificationErrorCode}</strong>
                <span>{buildSpecificationErrorMessage}</span>
                {buildSpecificationRequestId ? <small>请求标识：{buildSpecificationRequestId}</small> : null}
              </div>
            ) : null}
            {buildSnapshotState === 'error' ? (
              <div className="catalog-error" role="alert">
                <strong>{buildSnapshotErrorCode}</strong>
                <span>{buildSnapshotErrorMessage}</span>
                {buildSnapshotRequestId ? <small>请求标识：{buildSnapshotRequestId}</small> : null}
              </div>
            ) : null}
            {validationState === 'error' ? (
              <div className="catalog-error" role="alert">
                <strong>{validationErrorCode}</strong>
                <span>{validationErrorMessage}</span>
                {validationRequestId ? <small>请求标识：{validationRequestId}</small> : null}
              </div>
            ) : null}
            {freezeState === 'error' ? (
              <div className="catalog-error" role="alert">
                <strong>{freezeErrorCode}</strong>
                <span>{freezeErrorMessage}</span>
                {freezeRequestId ? <small>请求标识：{freezeRequestId}</small> : null}
              </div>
            ) : null}
            {detail.status !== 'FROZEN' && currentAdmissionState === 'idle' ? (
              <p>{detail.status === 'DRAFT' ? '尚未检查构建准备。' : '尚未检查当前证据。'}</p>
            ) : null}
            {detail.status !== 'FROZEN' && currentAdmissionState === 'loading' ? (
              <div className="catalog-notice" role="status" aria-live="polite">
                正在检查构建准备…
              </div>
            ) : null}
            {detail.status !== 'FROZEN' && currentAdmissionState === 'error' ? (
              <div className="catalog-error" role="alert">
                <strong>{currentAdmissionErrorCode}</strong>
                <span>{currentAdmissionErrorMessage}</span>
                {currentAdmissionRequestId ? <small>请求标识：{currentAdmissionRequestId}</small> : null}
                <Button onClick={() => void checkCurrentAdmission()}>重试</Button>
              </div>
            ) : null}
            {detail.status !== 'FROZEN' && currentAdmissionState === 'success' && currentAdmission ? (
              <CurrentAdmissionEvidence
                projectId={projectId}
                admission={currentAdmission}
                status={detail.status}
                starting={buildStartState === 'starting'}
                onStart={confirmBuildStart}
              />
            ) : null}
            {buildStartState === 'error' ? (
              <div className="catalog-error" role="alert">
                <strong>{buildStartErrorCode}</strong>
                <span>{buildStartErrorMessage}</span>
                {buildStartRequestId ? <small>请求标识：{buildStartRequestId}</small> : null}
              </div>
            ) : null}
          </Card>
          {detail.status === 'FROZEN' ? (
            <Card className="detail-card" title={<strong>数据集导出</strong>} variant="outlined">
              <p className="form-hint">
                当前 P0 只允许申请内部模型训练用途。申请、独立审批、后台生成和可下载是不同状态。
              </p>
              {detail.export_request ? (
                <ExportRequestEvidence
                  exportRequest={detail.export_request}
                  onChange={(exportRequest) =>
                    setDetail((current) => (current ? { ...current, export_request: exportRequest } : current))
                  }
                  onRefresh={loadDetail}
                />
              ) : detail.can_request_export ? (
                <div className="dataset-draft-selection-toolbar">
                  <div>
                    <strong>申请 P0 训练包</strong>
                    <span>绑定当前冻结版本和固定导出配置，并明确授权到期时间。</span>
                  </div>
                  <div className="dataset-draft-selection-actions">
                    <DatePicker
                      aria-label="导出授权到期时间"
                      disabledDate={(current) => current.endOf('day').valueOf() < Date.now()}
                      format="YYYY-MM-DD HH:mm"
                      minuteStep={5}
                      placeholder="选择到期时间"
                      showNow={false}
                      showTime
                      value={exportExpiresAt}
                      onChange={setExportExpiresAt}
                    />
                    <Button
                      type="primary"
                      disabled={!exportExpiresAt}
                      loading={exportRequestState === 'requesting'}
                      onClick={confirmExportRequest}
                    >
                      提交导出申请
                    </Button>
                  </div>
                </div>
              ) : (
                <p>尚未提交导出申请，请由项目管理员处理。</p>
              )}
              {exportRequestState === 'error' ? (
                <div className="catalog-error" role="alert">
                  <strong>{exportErrorCode}</strong>
                  <span>{exportErrorMessage}</span>
                  {exportRequestId ? <small>请求标识：{exportRequestId}</small> : null}
                </div>
              ) : null}
            </Card>
          ) : null}
          <Card
            className="detail-card table-card"
            variant="outlined"
            title={
              <div className="card-header-row">
                <strong>候选配药操作</strong>
                <span>展开行可核对每路视频的固定摘要与标注清洗证据</span>
              </div>
            }
          >
            <Table<DatasetVersionMemberSession>
              dataSource={detail.members}
              rowKey="capture_session_id"
              pagination={false}
              scroll={{ x: 940 }}
              expandable={{
                rowExpandable: (member) => member.streams.length > 0,
                expandedRowRender: (member) => <StreamEvidence projectId={projectId} streams={member.streams} />,
              }}
              columns={[
                {
                  title: '候选顺序',
                  width: 100,
                  render: (_, row) => row.candidate_index + 1,
                },
                {
                  title: '配药操作',
                  width: 220,
                  render: (_, row) => (
                    <Link className="text-link table-action-link" to={paths.session(projectId, row.capture_session_id)}>
                      {shortId(row.capture_session_id)}
                    </Link>
                  ),
                },
                {
                  title: '视频完整性',
                  width: 150,
                  render: (_, row) => `${row.received_camera_count} / ${row.expected_camera_count} 路`,
                },
                {
                  title: '组装状态',
                  width: 140,
                  render: (_, row) => (
                    <Tag color={statusTone(row.assembly_status)}>
                      {assemblyStatusLabels[row.assembly_status]}
                    </Tag>
                  ),
                },
                {
                  title: '技术检测',
                  width: 140,
                  render: (_, row) => (
                    <Tag color={statusTone(row.quality_status)}>
                      {qualityStatusLabels[row.quality_status]}
                    </Tag>
                  ),
                },
                {
                  title: '已固定视频流',
                  width: 130,
                  render: (_, row) => `${row.streams.length} 路`,
                },
              ]}
            />
          </Card>
        </>
      ) : null}
    </>
  )
}

function ExportRequestEvidence({
  exportRequest,
  onChange,
  onRefresh,
}: {
  exportRequest: DatasetExport
  onChange: (exportRequest: DatasetExport) => void
  onRefresh: () => Promise<void>
}) {
  const { message, modal } = AntdApp.useApp()
  const [readinessState, setReadinessState] = useState<ExportApprovalReadinessState>({ status: 'idle' })
  const [operationState, setOperationState] = useState<
    'idle' | 'approving' | 'refreshing' | 'downloading' | 'error'
  >('idle')
  const [operationError, setOperationError] = useState<{
    code: string
    message: string
    requestId: string | null
  } | null>(null)
  const readinessRequest = useRef<AbortController | null>(null)
  const operationRequest = useRef<AbortController | null>(null)
  const approvalIntent = useRef<string | null>(null)

  const checkReadiness = useCallback(async () => {
    readinessRequest.current?.abort()
    const request = new AbortController()
    readinessRequest.current = request
    setReadinessState({ status: 'loading' })
    try {
      const response = await getDatasetExportApprovalReadiness(
        exportRequest.project_id,
        exportRequest.dataset_version_id,
        request.signal,
      )
      setReadinessState({ status: 'success', value: response })
    } catch (error) {
      if (request.signal.aborted) return
      if (error instanceof CatalogApiError) {
        setReadinessState({
          status: 'error',
          errorCode: error.errorCode,
          errorMessage: error.message,
          requestId: error.requestId,
        })
      } else {
        setReadinessState({
          status: 'error',
          errorCode: 'DATASET_EXPORT_APPROVAL_READINESS_FAILED',
          errorMessage: '暂时无法检查导出审批准备，请稍后重试。',
          requestId: null,
        })
      }
    } finally {
      if (readinessRequest.current === request) readinessRequest.current = null
    }
  }, [exportRequest.dataset_version_id, exportRequest.project_id])

  const approve = useCallback(async () => {
    const idempotencyKey = approvalIntent.current ?? crypto.randomUUID()
    approvalIntent.current = idempotencyKey
    operationRequest.current?.abort()
    const request = new AbortController()
    operationRequest.current = request
    setOperationState('approving')
    setOperationError(null)
    try {
      const response = await approveDatasetExport(
        exportRequest.project_id,
        exportRequest.dataset_version_id,
        idempotencyKey,
        request.signal,
      )
      approvalIntent.current = null
      setReadinessState({ status: 'idle' })
      setOperationState('idle')
      onChange(response)
      void message.success('导出已批准，训练包已进入后台生成队列。')
    } catch (error) {
      if (request.signal.aborted) return
      setOperationState('error')
      setOperationError(toExportOperationError(error, '暂时无法批准导出，请稍后重试。'))
    } finally {
      if (operationRequest.current === request) operationRequest.current = null
    }
  }, [exportRequest.dataset_version_id, exportRequest.project_id, message, onChange])

  const confirmApproval = useCallback(() => {
    modal.confirm({
      title: '批准并开始生成训练包？',
      content: `批准后会立即记录审批并进入后台队列。训练包只用于内部模型训练，授权到期时间为 ${formatDateTime(exportRequest.expires_at)}。`,
      okText: '批准并开始导出',
      cancelText: '取消',
      onOk: approve,
    })
  }, [approve, exportRequest.expires_at, modal])

  const refresh = useCallback(async () => {
    setOperationState('refreshing')
    setOperationError(null)
    await onRefresh()
    setOperationState('idle')
  }, [onRefresh])

  const download = useCallback(async () => {
    operationRequest.current?.abort()
    const request = new AbortController()
    operationRequest.current = request
    setOperationState('downloading')
    setOperationError(null)
    try {
      const grant = await authorizeDatasetExportDownload(
        exportRequest.project_id,
        exportRequest.dataset_version_id,
        request.signal,
      )
      const link = document.createElement('a')
      link.href = grant.download_url
      link.download = grant.file_name
      link.rel = 'noopener'
      link.hidden = true
      document.body.append(link)
      link.click()
      link.remove()
      setOperationState('idle')
      void message.success('短期下载授权已签发。')
    } catch (error) {
      if (request.signal.aborted) return
      setOperationState('error')
      setOperationError(toExportOperationError(error, '暂时无法下载训练包，请稍后重试。'))
    } finally {
      if (operationRequest.current === request) operationRequest.current = null
    }
  }, [exportRequest.dataset_version_id, exportRequest.project_id, message])

  const confirmDownload = useCallback(() => {
    modal.confirm({
      title: '下载内部训练包？',
      content:
        '系统会记录本次下载授权，并签发一个短期地址。训练包包含冻结的原始视频，只能在批准的内部模型训练范围内使用。',
      okText: '下载训练包',
      cancelText: '取消',
      onOk: download,
    })
  }, [download, modal])

  useEffect(
    () => () => {
      readinessRequest.current?.abort()
      operationRequest.current?.abort()
    },
    [],
  )

  const status = exportStatusPresentation(exportRequest.status)
  const canRefresh = ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(exportRequest.status)

  return (
    <>
      <div className={`readiness-result${exportRequest.status === 'AVAILABLE' ? ' readiness-result-ready' : ''}`}>
        <div>
          <strong>{status.title}</strong>
          <span>{status.description}</span>
          {exportRequest.attempt ? (
            <small>
              第 {exportRequest.attempt.attempt} 次生成 · {exportRequest.attempt.status}
            </small>
          ) : null}
        </div>
        <div className="dataset-draft-selection-actions">
          <Tag color={status.color}>{status.label}</Tag>
          {canRefresh ? (
            <Button loading={operationState === 'refreshing'} onClick={() => void refresh()}>
              刷新状态
            </Button>
          ) : null}
          {exportRequest.status === 'AVAILABLE' ? (
            <Button type="primary" loading={operationState === 'downloading'} onClick={confirmDownload}>
              下载训练包
            </Button>
          ) : null}
        </div>
      </div>
      <Descriptions
        column={{ xs: 1, sm: 2, lg: 4 }}
        items={[
          { key: 'purpose', label: '用途', children: '内部模型训练' },
          { key: 'expiry', label: '授权到期', children: formatDateTime(exportRequest.expires_at) },
          { key: 'requester', label: '申请人', children: exportRequest.requested_by },
          {
            key: 'approver',
            label: '审批人',
            children: exportRequest.approved_by ?? '等待独立审批',
          },
          ...(exportRequest.artifact
            ? [
                {
                  key: 'artifact',
                  label: '训练包',
                  children: `${formatBytes(exportRequest.artifact.size_bytes)} · SHA-256 已核对`,
                },
              ]
            : []),
        ]}
      />
      {exportRequest.status === 'REQUESTED' ? (
        <div className="dataset-draft-selection-toolbar">
          <div>
            <strong>独立审批</strong>
            <span>先核对职责分离、授权有效期和冻结证据；批准时会在同一事务内再次检查。</span>
          </div>
          <Button loading={readinessState.status === 'loading'} onClick={() => void checkReadiness()}>
            检查审批准备
          </Button>
        </div>
      ) : null}
      {readinessState.status === 'success' ? (
        <div
          className={`readiness-result${readinessState.value.ready_for_approval ? ' readiness-result-ready' : ''}`}
        >
          <div>
            <strong>{readinessState.value.ready_for_approval ? '审批证据当前满足条件' : '审批准备仍有阻断项'}</strong>
            <span>
              {readinessState.value.ready_for_approval
                ? '批准时会再次核对这些事实，并原子记录审批、首次任务和后台消息。'
                : `本次检查发现 ${readinessState.value.blocking_reasons.length} 项阻断。`}
            </span>
            {readinessState.value.blocking_reasons.length > 0 ? (
              <ul>
                {readinessState.value.blocking_reasons.map((reason) => (
                  <li key={reason}>{exportApprovalBlockerLabels[reason]}</li>
                ))}
              </ul>
            ) : null}
          </div>
          <div className="dataset-draft-selection-actions">
            <Tag color={readinessState.value.ready_for_approval ? 'success' : 'warning'}>
              {readinessState.value.ready_for_approval ? '可以审批' : '暂不可审批'}
            </Tag>
            {readinessState.value.ready_for_approval ? (
              <Button type="primary" loading={operationState === 'approving'} onClick={confirmApproval}>
                批准并开始导出
              </Button>
            ) : null}
          </div>
        </div>
      ) : null}
      {operationState === 'error' && operationError ? (
        <div className="catalog-error" role="alert">
          <strong>{operationError.code}</strong>
          <span>{operationError.message}</span>
          {operationError.requestId ? <small>请求标识：{operationError.requestId}</small> : null}
        </div>
      ) : null}
      {readinessState.status === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{readinessState.errorCode}</strong>
          <span>{readinessState.errorMessage}</span>
          {readinessState.requestId ? <small>请求标识：{readinessState.requestId}</small> : null}
        </div>
      ) : null}
      <details className="technical-facts">
        <summary>查看导出申请标识</summary>
        <dl>
          <div>
            <dt>DatasetExport ID</dt>
            <dd>
              <code>{exportRequest.dataset_export_id}</code>
            </dd>
          </div>
          <div>
            <dt>ApprovalRequest ID</dt>
            <dd>
              <code>{exportRequest.approval.approval_request_id}</code>
            </dd>
          </div>
          <div>
            <dt>Validation ID</dt>
            <dd>
              <code>{exportRequest.dataset_version_validation_id}</code>
            </dd>
          </div>
          <div>
            <dt>构建快照摘要</dt>
            <dd>
              <code>{exportRequest.snapshot_digest}</code>
            </dd>
          </div>
          <div>
            <dt>导出配置</dt>
            <dd>
              <code>{exportRequest.export_profile_key}</code>
            </dd>
          </div>
          <div>
            <dt>导出配置摘要</dt>
            <dd>
              <code>{exportRequest.export_profile_digest}</code>
            </dd>
          </div>
          {exportRequest.approval.approval_decision_id ? (
            <div>
              <dt>ApprovalDecision ID</dt>
              <dd>
                <code>{exportRequest.approval.approval_decision_id}</code>
              </dd>
            </div>
          ) : null}
          {exportRequest.attempt ? (
            <div>
              <dt>ExportAttempt ID</dt>
              <dd>
                <code>{exportRequest.attempt.dataset_export_attempt_id}</code>
              </dd>
            </div>
          ) : null}
          {exportRequest.artifact ? (
            <div>
              <dt>训练包 SHA-256</dt>
              <dd>
                <code>{exportRequest.artifact.sha256}</code>
              </dd>
            </div>
          ) : null}
        </dl>
      </details>
    </>
  )
}

function exportStatusPresentation(status: DatasetExport['status']) {
  switch (status) {
    case 'REQUESTED':
      return {
        title: '导出申请等待独立审批',
        description: '用途和有效期已经固定，尚未进入后台队列。',
        label: '待审批',
        color: 'processing',
      } as const
    case 'QUEUED':
      return {
        title: '已批准，等待生成训练包',
        description: '审批决定和首次任务已经记录，后台 Worker 尚未开始读取冻结输入。',
        label: '等待生成',
        color: 'processing',
      } as const
    case 'RUNNING':
      return {
        title: '正在生成训练包',
        description: '后台正在核对冻结输入、生成 ZIP 并上传；完成前不会提供下载。',
        label: '生成中',
        color: 'processing',
      } as const
    case 'RETRY_WAIT':
      return {
        title: '生成暂时中断',
        description: '本次后台任务未完成，系统已安排有上限的重试。',
        label: '等待重试',
        color: 'warning',
      } as const
    case 'AVAILABLE':
      return {
        title: '训练包已通过校验',
        description: '文件集合、冻结视频摘要和 ZIP 摘要均已由服务端核对，可以申请短期下载。',
        label: '可下载',
        color: 'success',
      } as const
    case 'FAILED':
      return {
        title: '训练包生成未完成',
        description: '后台任务已停止，当前没有可下载文件；请根据审计记录处理。',
        label: '生成失败',
        color: 'error',
      } as const
  }
}

function toExportOperationError(error: unknown, fallback: string) {
  if (error instanceof CatalogApiError) {
    return { code: error.errorCode, message: error.message, requestId: error.requestId }
  }
  return { code: 'DATASET_EXPORT_OPERATION_FAILED', message: fallback, requestId: null }
}

function BuildSpecificationEvidence({
  specification,
}: {
  specification: NonNullable<DatasetVersionDetail['build_specification']>
}) {
  return (
    <>
      <Descriptions
        column={{ xs: 1, sm: 2, lg: 4 }}
        items={[
          { key: 'selection', label: '候选规则', children: '当前草稿快照' },
          { key: 'split', label: '数据划分', children: '已绑定' },
          { key: 'export', label: '必需导出', children: 'P0 训练包' },
          { key: 'creator', label: '固定人', children: specification.created_by },
        ]}
      />
      <details className="technical-facts">
        <summary>查看构建配置标识</summary>
        <dl>
          <div>
            <dt>BuildSpecification ID</dt>
            <dd>
              <code>{specification.dataset_build_specification_id}</code>
            </dd>
          </div>
          <div>
            <dt>选择规则版本</dt>
            <dd>
              <code>{specification.selection_rule_version}</code>
            </dd>
          </div>
          <div>
            <dt>标签规范摘要</dt>
            <dd>
              <code>{specification.label_schema_digest}</code>
            </dd>
          </div>
          <div>
            <dt>清洗规则摘要</dt>
            <dd>
              <code>{specification.cleaning_rule_set_digest}</code>
            </dd>
          </div>
          <div>
            <dt>导出配置</dt>
            <dd>
              <code>{specification.export_profile_key}</code>
            </dd>
          </div>
          <div>
            <dt>导出配置摘要</dt>
            <dd>
              <code>{specification.export_profile_digest}</code>
            </dd>
          </div>
        </dl>
      </details>
    </>
  )
}

function BuildSnapshotEvidence({
  snapshot,
}: {
  snapshot: NonNullable<DatasetVersionDetail['build_snapshot']>
}) {
  return (
    <>
      <Descriptions
        column={{ xs: 1, sm: 2, lg: 4 }}
        items={[
          { key: 'state', label: '构建快照', children: '已封存' },
          { key: 'members', label: '配药操作', children: `${snapshot.member_count} 组` },
          { key: 'streams', label: '视频流', children: `${snapshot.stream_count} 路` },
          { key: 'creator', label: '生成人', children: snapshot.created_by },
        ]}
      />
      <details className="technical-facts">
        <summary>查看构建快照标识</summary>
        <dl>
          <div>
            <dt>BuildSnapshot ID</dt>
            <dd>
              <code>{snapshot.dataset_build_snapshot_id}</code>
            </dd>
          </div>
          <div>
            <dt>快照规范</dt>
            <dd>
              <code>{snapshot.snapshot_schema_version}</code>
            </dd>
          </div>
          <div>
            <dt>输入策略</dt>
            <dd>
              <code>{snapshot.input_variant_policy}</code>
            </dd>
          </div>
          <div>
            <dt>快照摘要</dt>
            <dd>
              <code>{snapshot.snapshot_digest}</code>
            </dd>
          </div>
        </dl>
      </details>
    </>
  )
}

function ValidationEvidence({
  validation,
  approved,
}: {
  validation: NonNullable<DatasetVersionDetail['validation']>
  approved: boolean
}) {
  const passed = validation.outcome === 'PASS'
  return (
    <>
      <div className={`readiness-result${passed ? ' readiness-result-ready' : ''}`}>
        <div>
          <strong>{passed ? '冻结前校验已通过' : '冻结前校验未通过'}</strong>
          <span>
            {passed
              ? approved
                ? '技术校验通过，独立审批已完成。'
                : '当前版本已通过技术校验，仍需单独审批后才能冻结。'
              : '当前版本不能进入冻结审批；请根据原因修正数据后新建版本。'}
          </span>
          {validation.reason_codes.length > 0 ? (
            <ul>
              {validation.reason_codes.map((reason) => (
                <li key={reason}>{validationReasonLabels[reason]}</li>
              ))}
            </ul>
          ) : null}
        </div>
        <Tag color={passed ? 'success' : 'error'}>{passed ? '通过' : '未通过'}</Tag>
      </div>
      <details className="technical-facts">
        <summary>查看校验标识</summary>
        <dl>
          <div>
            <dt>Validation ID</dt>
            <dd>
              <code>{validation.dataset_version_validation_id}</code>
            </dd>
          </div>
          <div>
            <dt>校验规范</dt>
            <dd>
              <code>{validation.validation_profile_version}</code>
            </dd>
          </div>
          <div>
            <dt>快照摘要</dt>
            <dd>
              <code>{validation.validated_snapshot_digest}</code>
            </dd>
          </div>
          <div>
            <dt>执行检查</dt>
            <dd>
              <code>{validation.executed_checks.join(', ')}</code>
            </dd>
          </div>
          <div>
            <dt>执行人</dt>
            <dd>{validation.validated_by}</dd>
          </div>
          <div>
            <dt>执行时间</dt>
            <dd>{formatDateTime(validation.validated_at)}</dd>
          </div>
        </dl>
      </details>
    </>
  )
}

function FreezeApprovalEvidence({
  approval,
  canFreeze,
  freezing,
  onFreeze,
}: {
  approval: DatasetVersionFreezeApproval
  canFreeze: boolean
  freezing: boolean
  onFreeze: () => void
}) {
  const approved = approval.status === 'APPROVED'
  return (
    <>
      <div className={`readiness-result${approved ? ' readiness-result-ready' : ''}`}>
        <div>
          <strong>{approved ? '数据集已冻结' : '等待独立审批'}</strong>
          <span>
            {approved
              ? '审批决定已记录，当前版本保持不可变；如需调整，请新建数据集版本。'
              : canFreeze
                ? '技术校验已经通过。批准时会重新核对全部证据，并在同一事务内冻结版本。'
                : '需由校验执行人以外的另一位项目管理员处理。'}
          </span>
        </div>
        <div className="dataset-draft-selection-actions">
          <Tag color={approved ? 'success' : 'processing'}>{approved ? '已批准' : '待审批'}</Tag>
          {canFreeze ? (
            <Button type="primary" loading={freezing} onClick={onFreeze}>
              批准并冻结
            </Button>
          ) : null}
        </div>
      </div>
      <details className="technical-facts">
        <summary>查看冻结审批记录</summary>
        <dl>
          <div>
            <dt>ApprovalRequest ID</dt>
            <dd>
              <code>{approval.approval_request_id}</code>
            </dd>
          </div>
          <div>
            <dt>Validation ID</dt>
            <dd>
              <code>{approval.validation_id}</code>
            </dd>
          </div>
          <div>
            <dt>申请人</dt>
            <dd>{approval.requested_by}</dd>
          </div>
          <div>
            <dt>申请时间</dt>
            <dd>{formatDateTime(approval.requested_at)}</dd>
          </div>
          {approved ? (
            <>
              <div>
                <dt>ApprovalDecision ID</dt>
                <dd>
                  <code>{approval.approval_decision_id}</code>
                </dd>
              </div>
              <div>
                <dt>审批人</dt>
                <dd>{approval.approved_by ?? '未提供'}</dd>
              </div>
              <div>
                <dt>审批时间</dt>
                <dd>{formatDateTime(approval.approved_at)}</dd>
              </div>
            </>
          ) : null}
        </dl>
      </details>
    </>
  )
}

function SplitPlanEvidence({
  projectId,
  splitPlan,
}: {
  projectId: string
  splitPlan: NonNullable<DatasetVersionDetail['split_plan']>
}) {
  return (
    <>
      <Descriptions
        column={{ xs: 1, sm: 2, lg: 4 }}
        items={[
          { key: 'pilot', label: '试用组', children: `${splitPlan.pilot_count} 组` },
          { key: 'holdout', label: '留出组', children: `${splitPlan.holdout_count} 组` },
          { key: 'seed', label: '划分种子', children: splitPlan.split_seed },
          { key: 'creator', label: '固定人', children: splitPlan.created_by },
        ]}
      />
      <Table<DatasetSplitAssignment>
        dataSource={splitPlan.assignments}
        rowKey="capture_session_id"
        pagination={false}
        size="small"
        columns={[
          {
            title: '候选顺序',
            width: 110,
            render: (_, row) => row.candidate_index + 1,
          },
          {
            title: '配药操作',
            render: (_, row) => (
              <Link className="text-link table-action-link" to={paths.session(projectId, row.capture_session_id)}>
                {shortId(row.capture_session_id)}
              </Link>
            ),
          },
          {
            title: '分组',
            width: 130,
            render: (_, row) => (
              <Tag color={row.split === 'HOLDOUT' ? 'gold' : 'blue'}>
                {row.split === 'HOLDOUT' ? '留出组' : '试用组'}
              </Tag>
            ),
          },
        ]}
      />
      <details className="technical-facts">
        <summary>查看划分标识</summary>
        <dl>
          <div>
            <dt>策略版本</dt>
            <dd>
              <code>{splitPlan.policy_version}</code>
            </dd>
          </div>
          <div>
            <dt>SplitPlan ID</dt>
            <dd>
              <code>{splitPlan.dataset_split_plan_id}</code>
            </dd>
          </div>
        </dl>
      </details>
    </>
  )
}

function CurrentAdmissionEvidence({
  projectId,
  admission,
  status,
  starting,
  onStart,
}: {
  projectId: string
  admission: DatasetVersionCurrentAdmission
  status: DatasetVersionStatus
  starting: boolean
  onStart: () => void
}) {
  const ready = admission.build_readiness.ready_for_build
  const afterBuildStart = status !== 'DRAFT'
  return (
    <>
      <div className={`readiness-result${ready ? ' readiness-result-ready' : ''}`}>
        <div>
          <strong>
            {afterBuildStart
              ? ready
                ? '当前构建证据仍一致'
                : '构建证据需要处理'
              : ready
                ? '已具备开始构建条件'
                : '尚未具备开始构建条件'}
          </strong>
          <span>
            {ready
              ? afterBuildStart
                ? '固定配置与当前事实仍然一致，后续仍须完成构建校验。'
                : '草稿配置与当前证据已通过构建前检查。'
              : `还有 ${admission.build_readiness.blocking_reasons.length} 项需要处理。`}
          </span>
          {admission.build_readiness.blocking_reasons.length > 0 ? (
            <ul>
              {admission.build_readiness.blocking_reasons.map((reason) => (
                <li key={reason}>{buildBlockerLabels[reason]}</li>
              ))}
            </ul>
          ) : null}
        </div>
        {admission.can_start_build && status === 'DRAFT' ? (
          <Button type="primary" loading={starting} onClick={onStart}>
            开始构建
          </Button>
        ) : null}
      </div>
      <Table<DatasetVersionCurrentAdmissionMember>
        dataSource={admission.members}
        rowKey="capture_session_id"
        pagination={false}
        scroll={{ x: 1220 }}
        columns={[
          {
            title: '候选顺序',
            width: 100,
            render: (_, row) => row.candidate_index + 1,
          },
          {
            title: '配药操作',
            width: 190,
            render: (_, row) => (
              <Link className="text-link table-action-link" to={paths.session(projectId, row.capture_session_id)}>
                {shortId(row.capture_session_id)}
              </Link>
            ),
          },
          {
            title: '当前结果',
            width: 150,
            render: (_, row) => (
              <Tag color={row.current_admission.eligible ? 'success' : 'warning'}>
                {row.current_admission.eligible ? '仍满足前提' : '当前有阻断'}
              </Tag>
            ),
          },
          {
            title: '当前阻断原因',
            width: 350,
            render: (_, row) => <AdmissionBlockingReasons admission={row.current_admission} />,
          },
          {
            title: '固定证据变化',
            width: 430,
            render: (_, row) => <AdmissionEvidenceChanges changes={row.evidence_changes} />,
          },
        ]}
      />
    </>
  )
}

function AdmissionBlockingReasons({ admission }: { admission: DatasetAdmissionPreview }) {
  const reasonCodes = new Set(admission.blocking_reasons)
  for (const stream of admission.streams) {
    for (const reason of stream.blocking_reasons) reasonCodes.add(reason)
  }
  if (reasonCodes.size === 0) return <span>无当前阻断</span>
  return (
    <>
      {[...reasonCodes].map((code) => (
        <Tag key={code} color="warning" title={code}>
          {admissionReasonLabels[code] ?? code}
        </Tag>
      ))}
    </>
  )
}

function AdmissionEvidenceChanges({ changes }: { changes: DatasetVersionCurrentAdmissionEvidenceChange[] }) {
  const changeItems = changes.flatMap((change) =>
    change.change_codes.map((code) => ({
      code,
      scope: change.scope,
      cameraStreamId: change.camera_stream_id,
    })),
  )
  if (changeItems.length === 0) return <span>固定证据未发生变化</span>
  return (
    <>
      {changeItems.map((change, index) => {
        const source =
          change.scope === 'SESSION'
            ? '操作事实'
            : change.cameraStreamId
              ? `视频流 ${shortId(change.cameraStreamId)}`
              : '视频流'
        return (
          <Tag key={`${change.scope}:${change.cameraStreamId ?? 'none'}:${change.code}:${index}`} title={change.code}>
            {source} · {evidenceChangeLabels[change.code] ?? change.code}
          </Tag>
        )
      })}
    </>
  )
}

function StreamEvidence({ projectId, streams }: { projectId: string; streams: DatasetVersionMemberStream[] }) {
  if (streams.length === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该候选操作没有固定视频流。" />
  return (
    <div className="dataset-stream-evidence">
      <Table<DatasetVersionMemberStream>
        dataSource={streams}
        rowKey="camera_stream_id"
        pagination={false}
        size="small"
        scroll={{ x: 1180 }}
        columns={[
          { title: '相机序号', dataIndex: 'camera_index', width: 100 },
          {
            title: '视频资产',
            width: 180,
            render: (_, row) => (
              <Link className="text-link table-action-link" to={paths.asset(projectId, row.clip_asset_id)}>
                {shortId(row.clip_asset_id)}
              </Link>
            ),
          },
          {
            title: '人工质量',
            width: 150,
            render: (_, row) => `合格 · v${row.quality_review_version}`,
          },
          { title: '独立复核', width: 130, render: () => <Tag color="success">已通过</Tag> },
          { title: '后置清洗', width: 130, render: () => <Tag color="success">通过</Tag> },
          {
            title: '证据摘要',
            width: 180,
            render: (_, row) => (
              <details className="technical-facts compact-facts">
                <summary>查看摘要</summary>
                <dl>
                  <div>
                    <dt>资产 SHA-256</dt>
                    <dd>
                      <code>{row.asset_sha256}</code>
                    </dd>
                  </div>
                  <div>
                    <dt>标注文档 SHA-256</dt>
                    <dd>
                      <code>{row.annotation_document_sha256}</code>
                    </dd>
                  </div>
                  <div>
                    <dt>标签规范摘要</dt>
                    <dd>
                      <code>{row.label_schema_digest}</code>
                    </dd>
                  </div>
                  <div>
                    <dt>清洗规则摘要</dt>
                    <dd>
                      <code>{row.cleaning_rule_set_digest}</code>
                    </dd>
                  </div>
                </dl>
              </details>
            ),
          },
        ]}
      />
    </div>
  )
}
