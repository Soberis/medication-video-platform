import { Input, Modal, Space, Tag } from 'antd'

interface CameraExclusionModalProps {
  open: boolean
  cameraNumber: number | null
  reason: string
  hasAnnotationToDiscard: boolean
  isSubmitted: boolean
  isSubmitting: boolean
  onReasonChange: (reason: string) => void
  onConfirm: () => void | Promise<void>
  onCancel: () => void
}

const COMMON_EXCLUSION_REASONS = [
  '镜头遮挡/反光',
  '未对准操作区',
  '画面模糊或无法辨识',
  '视频缺失或无法播放',
  '无可确认的目标动作',
]

export function CameraExclusionModal({
  open,
  cameraNumber,
  reason,
  hasAnnotationToDiscard,
  isSubmitted,
  isSubmitting,
  onReasonChange,
  onConfirm,
  onCancel,
}: CameraExclusionModalProps) {
  return (
    <Modal
      title={`排除本机位 ${cameraNumber ?? ''}`}
      open={open}
      onOk={() => void onConfirm()}
      onCancel={onCancel}
      okText="确认排除本机位"
      okButtonProps={{
        danger: true,
        loading: isSubmitting,
        disabled: !reason.trim(),
      }}
      cancelText="取消"
      width={480}
    >
      <p className="form-hint camera-exclusion-scope-hint">
        排除只作用于本次标注修订：该机位的动作标注和对应手部样本不进入本次复核与导出；原视频、AI
        候选、手部检测事实和历史记录完整保留。取消排除后会回到待处理，AI
        候选会重新作为待核对内容显示，仍需重新确认标注。
      </p>
      {hasAnnotationToDiscard ? (
        <p className="form-hint camera-exclusion-draft-warning">
          {isSubmitted
            ? '当前机位已提交人工标注；确认排除后，本次修订不再纳入这些动作和对应手部样本，恢复后需重新确认。'
            : '当前机位有未提交草稿；确认排除后，动作段和备注会清空。'}
        </p>
      ) : null}
      <div className="camera-exclusion-reason-shortcuts">
        <span className="camera-exclusion-reason-label">常用原因：</span>
        <Space size={[4, 8]} wrap>
          {COMMON_EXCLUSION_REASONS.map((item) => (
            <Tag
              key={item}
              style={{ cursor: 'pointer', userSelect: 'none' }}
              color={reason === item ? 'blue' : undefined}
              onClick={() => onReasonChange(item)}
            >
              {item}
            </Tag>
          ))}
        </Space>
      </div>
      <label className="camera-exclusion-reason-field">
        <span>排除原因（必填）</span>
        <Input.TextArea
          rows={3}
          placeholder="说明为什么本次不纳入该机位，例如：镜头遮挡导致动作无法确认。"
          maxLength={1000}
          value={reason}
          onChange={(e) => onReasonChange(e.target.value)}
          autoFocus
          aria-required="true"
        />
      </label>
    </Modal>
  )
}
