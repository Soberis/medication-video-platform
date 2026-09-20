import { cleanup } from '@testing-library/react'
import * as matchers from '@testing-library/jest-dom/matchers'
import { afterEach, expect } from 'vitest'

expect.extend(matchers)

afterEach(cleanup)

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
})

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

globalThis.ResizeObserver = ResizeObserverStub

const nativeGetPropertyValue = window.CSSStyleDeclaration.prototype.getPropertyValue
window.CSSStyleDeclaration.prototype.getPropertyValue = function (name: string) {
  const value = nativeGetPropertyValue.call(this, name)
  if (
    name === 'border-bottom-width' ||
    name === 'border-top-width' ||
    name === 'border-left-width' ||
    name === 'border-right-width' ||
    name === 'border-width'
  ) {
    if (value === 'medium' || value === 'thin' || value === 'thick' || !value) {
      return '0px'
    }
  }
  if (name === 'line-height' && (!value || value.startsWith('var('))) {
    return '20px'
  }
  if (name === 'box-sizing' && !value) {
    return 'border-box'
  }
  if (
    (name.startsWith('padding-') || name.startsWith('border-')) &&
    (!value || Number.isNaN(parseFloat(value)))
  ) {
    return '0px'
  }
  return value
}
