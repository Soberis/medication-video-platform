import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { ErrorBoundary } from '@/components/ErrorBoundary'

function Bomb({ shouldThrow }: { shouldThrow: boolean }) {
  if (shouldThrow) {
    throw new Error('Simulation rendering crash')
  }
  return <div>Component running normally</div>
}

function FlakyContainer() {
  const [hasError, setHasError] = useState(true)
  return (
    <ErrorBoundary
      title="工作台局部异常"
      onReset={() => setHasError(false)}
    >
      <Bomb shouldThrow={hasError} />
    </ErrorBoundary>
  )
}

describe('ErrorBoundary', () => {
  it('renders children when no error occurs', () => {
    render(
      <ErrorBoundary>
        <div>Hello World</div>
      </ErrorBoundary>,
    )
    expect(screen.getByText('Hello World')).toBeInTheDocument()
  })

  it('catches render error and displays localized fallback card with retry action', () => {
    // Suppress console.error in React 19 for expected boundary errors
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})

    render(
      <ErrorBoundary title="测试错误边界">
        <Bomb shouldThrow />
      </ErrorBoundary>,
    )

    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('测试错误边界')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument()
    expect(screen.queryByText('Component running normally')).toBeNull()

    spy.mockRestore()
  })

  it('allows user to recover via onReset and reset button', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})

    render(<FlakyContainer />)
    expect(screen.getByText('工作台局部异常')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(screen.getByText('Component running normally')).toBeInTheDocument()

    spy.mockRestore()
  })

  it('supports custom function fallback', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})

    render(
      <ErrorBoundary fallback={(err) => <div>Custom fallback: {err.message}</div>}>
        <Bomb shouldThrow />
      </ErrorBoundary>,
    )

    expect(screen.getByText('Custom fallback: Simulation rendering crash')).toBeInTheDocument()

    spy.mockRestore()
  })
})
