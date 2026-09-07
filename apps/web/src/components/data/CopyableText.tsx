import { App as AntdApp, Button } from 'antd'

export function CopyableText({ value, label = '复制' }: { value: string; label?: string }) {
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
      <code>{value}</code>
      <Button type="link" size="small" aria-label={`${label} ${value}`} onClick={() => void copy()}>
        {label}
      </Button>
    </span>
  )
}
