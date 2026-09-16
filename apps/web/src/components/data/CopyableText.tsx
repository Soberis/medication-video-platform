import { App as AntdApp, Button } from 'antd'

interface CopyableTextProps {
  displayValue?: string
  label?: string
  value: string
}

export function CopyableText({ value, displayValue = value, label = '复制' }: CopyableTextProps) {
  const { message } = AntdApp.useApp()

  async function copy() {
    try {
      await navigator.clipboard.writeText(value)
      void message.success('已复制到剪贴板。')
    } catch {
      void message.error('复制未完成，请手动选择文本。')
    }
  }

  return (
    <span className="copyable-text">
      <code title={displayValue === value ? undefined : value}>{displayValue}</code>
      <Button type="link" size="small" aria-label={`${label} ${value}`} onClick={() => void copy()}>
        {label}
      </Button>
    </span>
  )
}
