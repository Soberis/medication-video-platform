import { describe, expect, it } from 'vitest'

import { APP_TITLE, documentTitleFor } from '@/app/document-title'

describe('documentTitleFor', () => {
  it('maps every app route to a scoped tab title', () => {
    expect(documentTitleFor('/projects')).toBe(`项目 · ${APP_TITLE}`)
    expect(documentTitleFor('/projects/p-1/capture-groups')).toBe(`视频 · ${APP_TITLE}`)
    expect(documentTitleFor('/projects/p-1/capture-groups/cg-1')).toBe(
      `采集记录详情 · ${APP_TITLE}`,
    )
    expect(documentTitleFor('/uploads/new')).toBe(`上传视频 · ${APP_TITLE}`)
    expect(documentTitleFor('/uploads')).toBe(`上传记录 · ${APP_TITLE}`)
    expect(documentTitleFor('/projects/p-1/members')).toBe(`项目成员 · ${APP_TITLE}`)
    expect(documentTitleFor('/settings/annotation-label-library')).toBe(`标签库 · ${APP_TITLE}`)
    expect(documentTitleFor('/admin/users')).toBe(`账号管理 · ${APP_TITLE}`)
  })

  it('falls back to the brand title for unmatched or legacy paths', () => {
    expect(documentTitleFor('/login')).toBe(APP_TITLE)
    expect(documentTitleFor('/projects/p-1/annotation-tasks')).toBe(APP_TITLE)
    expect(documentTitleFor('/unknown/route')).toBe(APP_TITLE)
  })
})
