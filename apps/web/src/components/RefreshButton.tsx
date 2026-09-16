import { Button } from 'antd'
import type { ButtonProps } from 'antd'
import { RefreshCw } from 'lucide-react'
import type { ReactNode } from 'react'

export interface RefreshButtonProps extends Omit<ButtonProps, 'children' | 'icon'> {
  label?: ReactNode
}

/** Shared page refresh action with one icon, size, and loading treatment. */
export function RefreshButton({ label = '刷新', ...props }: RefreshButtonProps) {
  return (
    <Button {...props} icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}>
      {label}
    </Button>
  )
}
