import { runPsqlJson, runPsql } from './lib/db.mjs'
import { deleteCaptureGroup } from './clean-invalid-videos.mjs'
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'

// 从生成的 CSV 读取 65 个不合格视频
const CSV_PATH = resolve('exports/reports/human_demonstration_hdf5_v2_selection_20261012T030550Z_demo_quality_breakdown.csv')

async function main() {
  const isExecute = process.argv.includes('--execute')
  console.log(`================== 批量剔除不合格视频脚本 ==================`)
  console.log(`模式: ${isExecute ? '【真实执行删除】' : '【DRY-RUN 预览模式 (加 --execute 真实生效)】'}`)

  if (!existsSync(CSV_PATH)) {
    console.error(`未找到质检 CSV 文件: ${CSV_PATH}`)
    process.exit(1)
  }

  const content = readFileSync(CSV_PATH, 'utf-8')
  const lines = content.split('\n').filter(Boolean)
  const header = lines[0].split(',')

  const unqualifiedList = []

  for (let i = 1; i < lines.length; i++) {
    const cols = lines[i].split(',')
    if (cols.length < 13) continue
    const demoId = cols[0].replace(/^\uFEFF/, '').trim()
    const groupKey = cols[2]?.trim()
    const groupId = cols[3]?.trim()
    const frames = parseInt(cols[4], 10)
    const duration = parseFloat(cols[5])
    const bothPct = parseFloat(cols[6])
    const jumpPct = parseFloat(cols[9])
    const actionCount = parseInt(cols[12], 10)

    const reasons = []
    if (bothPct === 0) reasons.push('完全无双手(0%)')
    else if (bothPct < 5.0 && actionCount <= 5) reasons.push(`双手极低(${bothPct}%)且动作过少(${actionCount}段)`)
    
    if (actionCount <= 2) reasons.push(`动作残缺(${actionCount}段)`)
    if (jumpPct >= 12.0) reasons.push(`严重跳帧(${jumpPct}%)`)
    if (frames < 50) reasons.push(`时长过短(${duration}s)`)

    if (reasons.length > 0) {
      unqualifiedList.push({ demoId, groupKey, groupId, reasons: reasons.join('; '), duration, bothPct, actionCount, jumpPct })
    }
  }

  console.log(`拟剔除不合格样本总计: ${unqualifiedList.length} 条\n`)
  unqualifiedList.forEach((item, idx) => {
    console.log(`${(idx + 1).toString().padStart(2, ' ')}. [${item.demoId}] ${item.groupKey.padEnd(30, ' ')} | 动作:${item.actionCount}段 | 双手:${item.bothPct}% | 跳帧:${item.jumpPct}% | 时长:${item.duration}s | 原因: ${item.reasons}`)
  })

  if (!isExecute) {
    console.log(`\n⚠️ 当前为 DRY-RUN 预览模式。若确认全部剔除，请运行: node tools/delete-unqualified-batch.mjs --execute`)
    return
  }

  console.log(`\n🚀 开始执行批量删除数据库采集会话及关联资产...`)
  let deletedCount = 0
  for (const item of unqualifiedList) {
    try {
      await deleteCaptureGroup(item.groupKey || item.groupId)
      deletedCount++
      console.log(`✅ [${deletedCount}/${unqualifiedList.length}] 已剔除: ${item.groupKey}`)
    } catch (err) {
      console.error(`❌ 删除失败 [${item.groupKey}]:`, err.message)
    }
  }
  console.log(`\n🎉 批量剔除完成！共成功清理 ${deletedCount} 条不合格视频。`)
}

main().catch(console.error)
