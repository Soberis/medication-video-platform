import { matchPath } from 'react-router'

import { paths } from '@/app/paths'

export const APP_TITLE = '配药视频平台'

const CAPTURE_GROUPS_PATH = '/projects/:projectId/capture-groups'
const CAPTURE_GROUP_PATH = '/projects/:projectId/capture-groups/:captureGroupId'
const MEMBERSHIPS_PATH = '/projects/:projectId/members'

/** 按当前路由解析浏览器标签页标题；未匹配的路径回落到品牌名。 */
export function documentTitleFor(pathname: string): string {
  if (matchPath({ path: paths.projects, end: true }, pathname)) return `项目 · ${APP_TITLE}`
  if (matchPath({ path: CAPTURE_GROUPS_PATH, end: true }, pathname)) return `视频 · ${APP_TITLE}`
  if (matchPath({ path: CAPTURE_GROUP_PATH, end: true }, pathname))
    return `采集记录详情 · ${APP_TITLE}`
  if (matchPath({ path: paths.localUpload, end: true }, pathname)) return `上传视频 · ${APP_TITLE}`
  if (matchPath({ path: paths.uploads, end: true }, pathname)) return `上传记录 · ${APP_TITLE}`
  if (matchPath({ path: MEMBERSHIPS_PATH, end: true }, pathname)) return `项目成员 · ${APP_TITLE}`
  if (matchPath({ path: paths.annotationLabelLibrary(), end: true }, pathname))
    return `标签库 · ${APP_TITLE}`
  if (matchPath({ path: paths.accounts, end: true }, pathname)) return `账号管理 · ${APP_TITLE}`
  return APP_TITLE
}
