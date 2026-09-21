import { Modal, Input } from 'antd'

interface CameraExclusionModalProps {
  open: boolean
  cameraNumber: number | null
  reason: string
  isSubmitting: boolean
  onReasonChange: (reason: string) => void
  onConfirm: () => void | Promise<void>
  onCancel: () => void
}

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
        disabled: !reason.trim(),
        loading: isSubmitting,
      }}
      cancelText="取消"
      width={480}
    >
      <p className="form-hint" style={{ marginBottom: 12 }}>
        排除后该机位将不参与本次数据使用与导出，视频与排除记录将完整保留，可随时恢复。
      </p>
      <Input.TextArea
        rows={3}
        placeholder="请输入排除原因（例如：镜头遮挡、画面模糊、无有效操作等）"
        maxLength={500}
        value={reason}
        onChange={(e) => onReasonChange(e.target.value)}
        autoFocus
      />
    </Modal>
  )
}
