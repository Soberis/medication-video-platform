import { Button, Empty } from 'antd'
import type { ReactNode } from 'react'

export interface NoticeStateProps {
  'aria-busy'?: boolean
  'aria-live'?: 'off' | 'polite' | 'assertive'
  children: ReactNode
  className?: string
}

export function NoticeState({
  'aria-busy': ariaBusy,
  'aria-live': ariaLive = 'polite',
  children,
  className = '',
}: NoticeStateProps) {
  return (
    <div
      className={`workspace-notice ${className}`.trim()}
      role="status"
      aria-live={ariaLive}
      aria-busy={ariaBusy || undefined}
    >
      {children}
    </div>
  )
}

export type LoadingStateProps = Omit<NoticeStateProps, 'aria-busy' | 'aria-live'>

export function LoadingState({ children, className = '' }: LoadingStateProps) {
  return (
    <NoticeState className={`workspace-loading-state ${className}`.trim()} aria-busy>
      {children}
    </NoticeState>
  )
}

export interface EmptyStateProps {
  children?: ReactNode
  className?: string
  description?: ReactNode
}

/** Shared empty-state presentation for list and collection views. */
export function EmptyState({ children, className = '', description }: EmptyStateProps) {
  const emptyStateClassName = ['workspace-empty-state', className].filter(Boolean).join(' ')

  return (
    <Empty
      className={emptyStateClassName}
      description={description}
      image={Empty.PRESENTED_IMAGE_SIMPLE}
    >
      {children}
    </Empty>
  )
}

export interface ErrorStateProps {
  children: ReactNode
  className?: string
  /** 错误码仅用于内部追踪，不渲染到界面。 */
  code?: string
  onRetry?: () => void
  retryLabel?: ReactNode
}

export function ErrorState({
  children,
  className = '',
  onRetry,
  retryLabel = '重试',
}: ErrorStateProps) {
  return (
    <div className={`workspace-error workspace-error-state ${className}`.trim()} role="alert">
      <div className="workspace-error-message">{children}</div>
      {onRetry ? <Button onClick={onRetry}>{retryLabel}</Button> : null}
    </div>
  )
}
