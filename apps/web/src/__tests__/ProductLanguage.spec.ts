import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const srcRoot = dirname(dirname(fileURLToPath(import.meta.url)))

const forbiddenProductLanguage = [
  '配药事件',
  '数据组',
  '渐进详情',
  '事件准入',
  '事件级',
  '事件清洗',
  '事件质量',
  '事件工作台',
  '当前动作',
  '完整流程',
  '主流程',
  '上传流程',
  '配药操作',
] as const

function runtimeFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name)

    if (entry.isDirectory()) {
      return entry.name === '__tests__' ? [] : runtimeFiles(path)
    }

    return /\.(ts|tsx)$/.test(entry.name) ? [path] : []
  })
}

describe('V1.29 product language', () => {
  it('keeps active Web runtime free of internal workflow jargon', () => {
    const violations = runtimeFiles(srcRoot).flatMap((path) => {
      const source = readFileSync(path, 'utf8')

      return forbiddenProductLanguage
        .filter((term) => source.includes(term))
        .map((term) => `${path}:${term}`)
    })

    expect(violations).toEqual([])
  })
})
