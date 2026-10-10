import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  matchFolderPlatform,
  formatDuration,
  extractAuthorFromFolderName,
  FOLDER_PATTERNS,
} from './import-web-video-batch.mjs'

test('matchFolderPlatform accurately parses Bilibili folder names', () => {
  assert.deepEqual(matchFolderPlatform('BV12tYe6oEBv - 沉浸式体验护士配药 - 一朵小花oi'), {
    platform: 'bilibili',
    videoId: 'BV12tYe6oEBv',
  })
  assert.deepEqual(matchFolderPlatform('bilibili_BV18gKB6rEZU'), {
    platform: 'bilibili',
    videoId: 'BV18gKB6rEZU',
  })
})

test('matchFolderPlatform accurately parses Douyin folder names', () => {
  assert.deepEqual(matchFolderPlatform('douyin_7486754220453023026 - 沉浸式抽取药液 不敢掰只能用笔敲了 - 捞汁鲍鱼🌸'), {
    platform: 'douyin',
    videoId: '7486754220453023026',
  })
  assert.deepEqual(matchFolderPlatform('7486754220453023026'), {
    platform: 'douyin',
    videoId: '7486754220453023026',
  })
})

test('matchFolderPlatform accurately parses Xiaohongshu folder names', () => {
  assert.deepEqual(matchFolderPlatform('xhs_684fc2b8000000002001fa38 - 沉浸式配药脂肪乳~ - 每天都很困'), {
    platform: 'xhs',
    videoId: '684fc2b8000000002001fa38',
  })
  assert.deepEqual(matchFolderPlatform('684fc2b8000000002001fa38'), {
    platform: 'xhs',
    videoId: '684fc2b8000000002001fa38',
  })
})

test('matchFolderPlatform accurately parses YouTube folder names with 11-char IDs', () => {
  assert.deepEqual(matchFolderPlatform('youtube_0AWXZoY1TEU - 當太陽升起時，我們都要比昨天更幸福 - untitled'), {
    platform: 'youtube',
    videoId: '0AWXZoY1TEU',
  })
  assert.deepEqual(matchFolderPlatform('youtube_1Dm_PB841VQ - 一鏡到底四連扎+屁股針 護士 屁股針 配藥 - freddie queensbridge'), {
    platform: 'youtube',
    videoId: '1Dm_PB841VQ',
  })
  assert.deepEqual(matchFolderPlatform('youtube_58eLDg2f-Ng - 無形無相亦無我 - untitled'), {
    platform: 'youtube',
    videoId: '58eLDg2f-Ng',
  })
  assert.deepEqual(matchFolderPlatform('0AWXZoY1TEU'), {
    platform: 'youtube',
    videoId: '0AWXZoY1TEU',
  })
})

test('matchFolderPlatform returns null for unrecognized folder names', () => {
  assert.equal(matchFolderPlatform('random_folder_name'), null)
  assert.equal(matchFolderPlatform('my_personal_video'), null)
})

test('formatDuration converts seconds into mm:ss format', () => {
  assert.equal(formatDuration(39.009), '00:39')
  assert.equal(formatDuration(115), '01:55')
  assert.equal(formatDuration('39.009'), '00:39')
  assert.equal(formatDuration('02:45'), '02:45')
  assert.equal(formatDuration(0), '')
  assert.equal(formatDuration(''), '')
  assert.equal(formatDuration(null), '')
})

test('extractAuthorFromFolderName extracts creator suffix from standard folder convention', () => {
  assert.equal(
    extractAuthorFromFolderName('douyin_7486754220453023026 - 沉浸式抽取药液 不敢掰只能用笔敲了 - 捞汁鲍鱼🌸'),
    '捞汁鲍鱼🌸',
  )
  assert.equal(
    extractAuthorFromFolderName('youtube_1Dm_PB841VQ - 一鏡到底四連扎+屁股針 護士 屁股針 配藥 - freddie queensbridge'),
    'freddie queensbridge',
  )
  assert.equal(
    extractAuthorFromFolderName('youtube_0AWXZoY1TEU - 當太陽升起時 - untitled'),
    null,
  )
})

test('checkVideoHandsPreflight gracefully handles non-existent or invalid files', async () => {
  const { checkVideoHandsPreflight } = await import('./import-web-video-batch.mjs')
  const result = await checkVideoHandsPreflight('non_existent_path_dummy.mp4')
  assert.equal(result, false)
})
