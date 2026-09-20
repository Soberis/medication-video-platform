import { App as AntdApp, Button } from 'antd'
import { Check, Copy } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

interface CopyableTextProps {
  className?: string
  compact?: boolean
  displayValue?: string
  hideCode?: boolean
  label?: string
  value: string
}

export function CopyableText({
  value,
  displayValue = value,
  label = '复制',
  compact = false,
  hideCode = false,
  className = '',
}: CopyableTextProps) {
  const { message } = AntdApp.useApp()
  const [copied, setCopied] = useState(false)
  const timerRef = useRef<number | null>(null)

  useEffect(() => {
    return () => {
      if (timerRef.current) window.clearTimeout(timerRef.current)
    }
  }, [])

  async function copy(event: React.MouseEvent) {
    event.stopPropagation()
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      if (timerRef.current) window.clearTimeout(timerRef.current)
      timerRef.current = window.setTimeout(() => setCopied(false), 1500)
      void message.success('已复制到剪贴板。')
    } catch {
      void message.error('复制未完成，请手动选择文本。')
    }
  }

  const tooltipText = copied ? '已复制' : label === '复制' ? '复制完整标识' : label

  return (
    <span className={`copyable-text ${compact ? 'copyable-text-compact' : ''} ${className}`.trim()}>
      {!hideCode ? (
        <code title={displayValue === value ? undefined : value}>{displayValue}</code>
      ) : null}
      {compact ? (
        <Button
          type="text"
          size="small"
          className="copyable-text-btn copyable-text-btn-compact"
          aria-label={`${label} ${value}`}
          title={tooltipText}
          icon={
            copied ? <Check size={12} className="copyable-text-success-icon" /> : <Copy size={12} />
          }
          onClick={(e) => void copy(e)}
        />
      ) : (
        <Button
          type="link"
          size="small"
          className="copyable-text-btn"
          aria-label={`${label} ${value}`}
          onClick={(e) => void copy(e)}
        >
          {label}
        </Button>
      )}
    </span>
  )
}
