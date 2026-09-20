/* eslint-disable react-refresh/only-export-components */

import { Input, Modal } from 'antd'
import {
  createContext,
  type PropsWithChildren,
  useCallback,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
} from 'react'

interface PromptOptions {
  title: string
  description: string
  confirmText: string
  inputLabel?: string
  placeholder?: string
  initialValue?: string
  validate?: (value: string) => string | null
}

type PromptFunction = (options: PromptOptions) => Promise<string | null>

const PromptContext = createContext<PromptFunction | null>(null)

export function PromptProvider({ children }: PropsWithChildren) {
  const [activePrompt, setActivePrompt] = useState<PromptOptions | null>(null)
  const [value, setValue] = useState('')
  const [error, setError] = useState('')
  const promptInputId = useId()
  const promptErrorId = `${promptInputId}-error`
  const activeResolver = useRef<((value: string | null) => void) | null>(null)

  const prompt = useCallback<PromptFunction>(
    (options) =>
      new Promise((resolve) => {
        activeResolver.current?.(null)
        activeResolver.current = resolve
        setValue(options.initialValue ?? '')
        setError('')
        setActivePrompt(options)
      }),
    [],
  )

  const close = useCallback((result: string | null) => {
    const resolve = activeResolver.current
    activeResolver.current = null
    setActivePrompt(null)
    setError('')
    resolve?.(result)
  }, [])

  useEffect(
    () => () => {
      activeResolver.current?.(null)
      activeResolver.current = null
    },
    [],
  )

  const confirm = useCallback(() => {
    if (!activePrompt) return
    const validationError = activePrompt.validate?.(value) ?? null
    if (validationError) {
      setError(validationError)
      return
    }
    close(value)
  }, [activePrompt, close, value])

  return (
    <PromptContext.Provider value={prompt}>
      {children}
      <Modal
        open={activePrompt !== null}
        title={activePrompt?.title}
        okText={activePrompt?.confirmText}
        cancelText="取消"
        onOk={confirm}
        onCancel={() => close(null)}
        closable={{ 'aria-label': '关闭' }}
        className="prompt-dialog"
        destroyOnHidden
        width={440}
      >
        <p className="prompt-dialog-description">{activePrompt?.description}</p>
        <label className="prompt-input-label" htmlFor={promptInputId}>
          {activePrompt?.inputLabel ?? '输入内容'}
        </label>
        <Input
          id={promptInputId}
          autoFocus
          value={value}
          placeholder={activePrompt?.placeholder}
          autoComplete="off"
          status={error ? 'error' : undefined}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? promptErrorId : undefined}
          onChange={(event) => {
            setValue(event.target.value)
            if (error) setError('')
          }}
          onPressEnter={confirm}
        />
        {error ? (
          <p id={promptErrorId} className="field-error prompt-field-error" role="alert">
            {error}
          </p>
        ) : null}
      </Modal>
    </PromptContext.Provider>
  )
}

export function usePromptDialog() {
  const context = useContext(PromptContext)
  if (!context) throw new Error('usePromptDialog 必须在 PromptProvider 内使用。')
  return context
}
