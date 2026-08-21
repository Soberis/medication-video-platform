import { Button } from 'antd'
import type { ReactNode } from 'react'

interface NoticeStateProps {
  'aria-live'?: 'off' | 'polite' | 'assertive'
  children: ReactNode
  className?: string
}

export function NoticeState({
  'aria-live': ariaLive = 'polite',
  children,
  className = '',
}: NoticeStateProps) {
  return (
    <div className={`workspace-notice ${className}`.trim()} role="status" aria-live={ariaLive}>
      {children}
    </div>
  )
}

export function LoadingState({ children, className = '' }: Omit<NoticeStateProps, 'aria-live'>) {
  return <NoticeState className={className}>{children}</NoticeState>
}

interface ErrorStateProps {
  children: ReactNode
  className?: string
  code?: ReactNode
  onRetry?: () => void
  retryLabel?: ReactNode
}

export function ErrorState({
  children,
  className = '',
  code,
  onRetry,
  retryLabel = '重试',
}: ErrorStateProps) {
  return (
    <div className={`workspace-error ${className}`.trim()} role="alert">
      <span>{children}</span>
      {code ? <strong>{code}</strong> : null}
      {onRetry ? <Button onClick={onRetry}>{retryLabel}</Button> : null}
    </div>
  )
}
