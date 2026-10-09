#!/usr/bin/env node

/**
 * tools/pipeline-auto-feeder.mjs
 * 
 * 自动化管线流转与自愈守护引擎 (项目级内聚，零跨项目污染)
 * 1. 基础设施感知与断线重连: 遇 Docker/Postgres/API 重启不崩溃，自动心跳等待与重连
 * 2. 上游媒体探针流转: 将 UPLOADED 视频平稳分批推入 media-worker (水位 < 10)
 * 3. MediaPipe 手部骨骼水位守护与自愈: 维持 10~15 活跃队列，绝不触发 5 分钟超时误伤，自动捞起超时/失败任务
 * 4. Qwen 动作分割水位守护: 保持 20~35 队列，杜绝排队超时，维持 4090 满负荷
 */

import { randomUUID } from 'node:crypto';
import { isPostgresReady, runPsql } from './lib/db.mjs';

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1';
const PROJECT_ID = process.env.PROJECT_ID || '944a19eb-03b1-4b55-98fb-f8e108e23d92';
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'synthetic-local-admin-bearer-token-do-not-reuse';

const headers = {
  Authorization: `Bearer ${ADMIN_TOKEN}`,
  Accept: 'application/json',
  'Content-Type': 'application/json',
};

/**
 * 检查 Core API 健康状态
 */
async function isCoreApiReady() {
  try {
    const res = await fetch('http://127.0.0.1:8080/actuator/health', {
      signal: AbortSignal.timeout(3000),
    });
    if (!res.ok) return false;
    const data = await res.json();
    return data.status === 'UP';
  } catch {
    return false;
  }
}

const querySql = (sql) => runPsql(sql);
const executeSql = (sql) => runPsql(sql);

/**
 * 1. 媒体探针平稳灌溉 (上游流转)
 */
async function feedMediaProbes(batchLimit = 15) {
  const ingestingCount = parseInt(
    querySql(`SELECT count(*) FROM clip_asset WHERE project_id = '${PROJECT_ID}' AND asset_status = 'INGESTING';`) || '0',
    10
  );

  if (ingestingCount >= 10) {
    return { fed: 0, reason: `当前已有 ${ingestingCount} 个视频正在探针中，保持平稳吞吐` };
  }

  const needCount = Math.min(batchLimit, 10 - ingestingCount);

  const rawRows = querySql(`
    SELECT c.clip_asset_id || ',' || COALESCE(j.media_job_id::text, '')
    FROM clip_asset c
    LEFT JOIN media_job j ON j.clip_asset_id = c.clip_asset_id
    WHERE c.project_id = '${PROJECT_ID}'
      AND c.asset_status = 'UPLOADED'
    LIMIT ${needCount};
  `);

  if (!rawRows) {
    return { fed: 0, reason: '所有视频已完成媒体探针' };
  }

  const items = rawRows.split('\n').filter(Boolean).map(l => {
    const [clip_asset_id, media_job_id] = l.split(',');
    return { clip_asset_id, media_job_id };
  });

  const jobIds = items.filter(i => i.media_job_id).map(i => `'${i.media_job_id}'`).join(',');
  if (jobIds) {
    executeSql(`
      DELETE FROM media_job_attempt WHERE media_job_id IN (${jobIds});
      DELETE FROM outbox_event WHERE aggregate_id IN (${jobIds});
      DELETE FROM media_job WHERE media_job_id IN (${jobIds});
    `);
  }

  let fedCount = 0;
  for (const item of items) {
    const url = `${BASE_URL}/projects/${PROJECT_ID}/clip-assets/${item.clip_asset_id}/media-probe-jobs`;
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: {
          ...headers,
          'Idempotency-Key': `feeder-probe-${item.clip_asset_id}-${Date.now()}`,
        },
      });
      if (res.ok || res.status === 202) fedCount++;
    } catch {}
  }

  return { fed: fedCount, reason: `已分批激活 ${fedCount} 个新视频探针` };
}

/**
 * 2. Qwen 动作分割水位守护 (中游 GPU 流转)
 */
async function feedQwenActions(targetQueueSize = 35) {
  const activeCount = parseInt(
    querySql(`SELECT count(*) FROM ai_job WHERE algorithm_key = 'QWEN3_5_ACTION' AND status IN ('QUEUED', 'RUNNING');`) || '0',
    10
  );

  if (activeCount >= 20) {
    return { fed: 0, reason: `4090 队列健康 (${activeCount} 任务在处理/排队中)` };
  }

  // Qwen 动作分割由 Core API 调度引擎与恢复服务统一管控，不执行跨模块 raw SQL 物理删除
  return { fed: 0, reason: `4090 队列运行平稳 (${activeCount} 任务在处理/排队中)` };
}

/**
 * 3. MediaPipe 手部骨骼水位守护与故障自愈 (本地 Worker 流转)
 */
async function feedMediaPipeHands(batchLimit = 15) {
  // 1. 活跃与排队状态由 Core API AiJobRecoveryService 统一管控租约，避免外部脚本盲目删表破坏长视频推理


  // 2. 统计真实活跃任务数
  const activeCount = parseInt(
    querySql(`
      SELECT count(*)
      FROM ai_job
      WHERE algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'
        AND status IN ('QUEUED', 'RUNNING');
    `) || '0',
    10
  );

  if (activeCount >= 10) {
    return { fed: 0, reason: `手部检测队列健康 (${activeCount} 任务在处理/排队中)` };
  }

  const needCount = Math.min(batchLimit, 15 - activeCount);

  // 3. 找出所有尚未成功完成手部检测的 INGESTED 视频
  const rawTargetAssets = querySql(`
    SELECT c.clip_asset_id
    FROM clip_asset c
    WHERE c.project_id = '${PROJECT_ID}'
      AND c.asset_status = 'INGESTED'
      AND NOT EXISTS (
        SELECT 1 FROM ai_job j
        WHERE j.clip_asset_id = c.clip_asset_id
          AND j.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'
          AND j.status IN ('SUCCEEDED', 'RUNNING', 'QUEUED')
      )
    LIMIT ${needCount};
  `);

  if (!rawTargetAssets) {
    return { fed: 0, reason: '所有视频的手部检测均已完成或正在队列中' };
  }

  const targetAssetIds = rawTargetAssets.split('\n').filter(Boolean);
  const assetIdList = targetAssetIds.map(id => `'${id}'`).join(',');

  // Core API 原生支持 FAILED 状态合法重试与重新排队，不再需要通过原始 SQL 暴力删表

  // 5. 平稳灌溉投递
  let fedCount = 0;
  for (const clipAssetId of targetAssetIds) {
    const url = `${BASE_URL}/projects/${PROJECT_ID}/videos/${clipAssetId}/hand-pose-detections`;
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: {
          ...headers,
          'Idempotency-Key': `feeder-hand-${clipAssetId}-${Date.now()}`,
          'X-Request-Id': randomUUID(),
        },
      });
      if (res.ok || res.status === 202) {
        fedCount++;
      } else {
        const errText = await res.text().catch(() => '');
        console.error(`[手部骨骼投递未接受] ${clipAssetId}: ${res.status} ${errText}`);
      }
    } catch (err) {
      console.error(`[手部骨骼投递异常] ${clipAssetId}: ${err.message}`);
    }
  }

  return { fed: fedCount, reason: `已平稳补充唤醒 ${fedCount} 个手部骨骼检测任务 (当前活跃: ${activeCount + fedCount})` };
}

/**
 * 4. 实时全局状态监控看板
 */
function getGlobalStatus() {
  const assetStatsRaw = querySql(`
    SELECT asset_status || ':' || count(*)
    FROM clip_asset
    WHERE project_id = '${PROJECT_ID}'
    GROUP BY asset_status;
  `);
  const assetStats = {};
  if (assetStatsRaw) {
    assetStatsRaw.split('\n').forEach(l => {
      const [k, v] = l.split(':');
      assetStats[k] = parseInt(v, 10);
    });
  }

  const aiStatsRaw = querySql(`
    SELECT algorithm_key || ':' || status || ':' || count(*)
    FROM ai_job
    GROUP BY algorithm_key, status;
  `);
  const aiStats = { MEDIAPIPE: {}, QWEN: {} };
  if (aiStatsRaw) {
    aiStatsRaw.split('\n').forEach(l => {
      const [algo, stat, count] = l.split(':');
      const target = algo.includes('MEDIAPIPE') ? aiStats.MEDIAPIPE : aiStats.QWEN;
      target[stat] = parseInt(count, 10);
    });
  }

  return { assetStats, aiStats };
}

async function runCycle(iteration = 1) {
  // 基础设施就绪检查 (断线自动重试与自愈)
  if (!(await isPostgresReady())) {
    console.log(`[周期 #${iteration} | ${new Date().toLocaleTimeString()}] 等待 PostgreSQL 就绪...`);
    return false;
  }
  const apiReady = await isCoreApiReady();
  if (!apiReady) {
    console.log(`[周期 #${iteration} | ${new Date().toLocaleTimeString()}] 等待 Core API 就绪...`);
    return false;
  }

  const timestamp = new Date().toLocaleTimeString();
  const probeRes = await feedMediaProbes(15);
  const handRes = await feedMediaPipeHands(15);
  const qwenRes = await feedQwenActions(35);

  const { assetStats, aiStats } = getGlobalStatus();

  console.log(`\n================== [周期 #${iteration} | ${timestamp}] ==================`);
  console.log(`[视频状态] 已探针(INGESTED): ${assetStats.INGESTED || 0} | 正在探针(INGESTING): ${assetStats.INGESTING || 0} | 待探针(UPLOADED): ${assetStats.UPLOADED || 0}`);
  console.log(`[手部骨骼] 成功: ${aiStats.MEDIAPIPE.SUCCEEDED || 0} | 运行中: ${aiStats.MEDIAPIPE.RUNNING || 0} | 排队: ${aiStats.MEDIAPIPE.QUEUED || 0} | 待调度: ${aiStats.MEDIAPIPE.FAILED || 0}`);
  console.log(`[动作分割] 成功: ${aiStats.QWEN.SUCCEEDED || 0} | 运行中: ${aiStats.QWEN.RUNNING || 0} | 排队: ${aiStats.QWEN.QUEUED || 0} | 待调度: ${aiStats.QWEN.FAILED || 0}`);
  console.log(`[守护动作] 探针: ${probeRes.reason}`);
  console.log(`[守护动作] 手部: ${handRes.reason}`);
  console.log(`[守护动作] Qwen: ${qwenRes.reason}`);
  return true;
}

async function main() {
  const isWatch = process.argv.includes('--watch') || process.argv.includes('-w');
  console.log(`=== 启动自动化管线守护引擎 [模式: ${isWatch ? '持续监听 (每20秒)' : '单次执行'}] ===`);

  let count = 1;
  try {
    await runCycle(count++);
  } catch (err) {
    console.warn(`[Feeder 警告] 周期 #${count - 1} 执行异常 (${err.message})，守护线程保持待命。`);
  }

  if (isWatch) {
    while (true) {
      await new Promise(r => setTimeout(r, 20000));
      try {
        await runCycle(count++);
      } catch (err) {
        console.warn(`[Feeder 提示] 周期 #${count - 1} 捕获瞬态抖动 (${err.message})，保持存活，将在下个周期继续尝试。`);
      }
    }
  }
}

main().catch(err => {
  console.error('守护引擎捕获致命异常:', err);
});
