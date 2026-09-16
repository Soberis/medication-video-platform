import { Button } from 'antd'
import { RefreshCw } from 'lucide-react'
import type { ReactNode } from 'react'

export interface RefreshButtonProps {
  loading?: boolean
  onClick?: () => void
  label?: ReactNode
  className?: string
  size?: 'small' | 'middle' | 'large'
  disabled?: boolean
  title?: string
  'aria-label'?: string
}

export function RefreshButton({
  loading = false,
  onClick,
  label = '刷新',
  className,
  size,
  disabled,
  title,
  'aria-label': ariaLabel,
}: RefreshButtonProps) {
  return (
    <Button
      className={className}
      disabled={disabled}
      icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}
      loading={loading}
      onClick={onClick}
      size={size}
      title={title}
      aria-label={ariaLabel}
    >
      {label}
    </Button>
  )
}
