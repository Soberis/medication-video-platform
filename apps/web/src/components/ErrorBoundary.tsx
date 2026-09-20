import { Alert, Button } from 'antd'
import { RotateCw } from 'lucide-react'
import { Component, type ErrorInfo, type ReactNode } from 'react'

export interface ErrorBoundaryProps {
  children: ReactNode
  title?: string
  description?: string
  fallback?: ReactNode | ((error: Error, reset: () => void) => ReactNode)
  onReset?: () => void
}

interface ErrorBoundaryState {
  hasError: boolean
  error: Error | null
}

/**
 * Robust error boundary preventing unhandled rendering exceptions from blanking out
 * the entire application or the surrounding video-management workspace.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  override state: ErrorBoundaryState = {
    hasError: false,
    error: null,
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error }
  }

  override componentDidCatch(error: Error, errorInfo: ErrorInfo): void {
    if (process.env.NODE_ENV !== 'production' && process.env.NODE_ENV !== 'test') {
      console.error('[ErrorBoundary caught exception]', error, errorInfo)
    }
  }

  reset = (): void => {
    this.props.onReset?.()
    this.setState({ hasError: false, error: null })
  }

  override render(): ReactNode {
    if (this.state.hasError && this.state.error) {
      if (typeof this.props.fallback === 'function') {
        return this.props.fallback(this.state.error, this.reset)
      }
      if (this.props.fallback !== undefined) {
        return this.props.fallback
      }

      return (
        <div className="error-boundary-fallback" style={{ padding: '16px 0' }}>
          <Alert
            type="warning"
            showIcon
            title={this.props.title ?? '当前模块遇到异常'}
            description={
              <div>
                <p style={{ margin: '4px 0 12px' }}>
                  {this.props.description ??
                    '渲染或解析数据时发生异常，已阻止页面崩溃。您可以尝试重新加载本模块。'}
                </p>
                <Button
                  size="small"
                  icon={<RotateCw size={13} />}
                  onClick={this.reset}
                >
                  重试
                </Button>
              </div>
            }
          />
        </div>
      )
    }

    return this.props.children
  }
}
