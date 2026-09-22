import { Modal, Input, Tag, Space } from 'antd'

interface CameraExclusionModalProps {
  open: boolean
  cameraNumber: number | null
  reason: string
  isSubmitting: boolean
  onReasonChange: (reason: string) => void
  onConfirm: () => void | Promise<void>
  onCancel: () => void
}

const COMMON_EXCLUSION_REASONS = [
  '镜头遮挡/反光',
  '未对准操作区',
  '无有效手部动作',
  '画面模糊',
  '人工排除',
]

export function CameraExclusionModal({
  open,
  cameraNumber,
  reason,
  isSubmitting,
  onReasonChange,
  onConfirm,
  onCancel,
}: CameraExclusionModalProps) {
  return (
    <Modal
      title={`排除机位 ${cameraNumber ?? ''}`}
      open={open}
      onOk={() => void onConfirm()}
      onCancel={onCancel}
      okText="确认排除"
      okButtonProps={{
        danger: true,
        loading: isSubmitting,
      }}
      cancelText="取消"
      width={480}
    >
      <p className="form-hint" style={{ marginBottom: 12 }}>
        排除后该机位将不参与本次多机位动作核对与复核，视频与记录完整保留，可随时恢复。
      </p>
      <div style={{ marginBottom: 10 }}>
        <span style={{ fontSize: 12, color: 'var(--text-secondary, #666)', marginRight: 8 }}>
          快捷原因：
        </span>
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
      <Input.TextArea
        rows={3}
        placeholder="可直接点击上方标签，或输入排除原因（选填，默认：人工排除）"
        maxLength={500}
        value={reason}
        onChange={(e) => onReasonChange(e.target.value)}
        autoFocus
      />
    </Modal>
  )
}
