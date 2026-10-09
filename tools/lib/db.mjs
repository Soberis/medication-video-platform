import { spawnSync, execSync } from 'node:child_process'

export const DOCKER_CONTAINER = process.env.POSTGRES_CONTAINER || 'medication-video-platform-postgres-1'
export const DB_USER = process.env.POSTGRES_USER || 'medication_local'
export const DB_NAME = process.env.POSTGRES_DB || 'medication_platform'

/**
 * 检查 PostgreSQL 容器是否正常运行且接受连接
 */
export function isPostgresReady() {
  try {
    const out = execSync(
      `docker exec -i ${DOCKER_CONTAINER} pg_isready -U ${DB_USER} -d ${DB_NAME}`,
      { encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'], timeout: 3000 }
    )
    return out.includes('accepting connections')
  } catch {
    return false
  }
}

/**
 * 执行原生 SQL 语句
 */
export function runPsql(sql) {
  const result = spawnSync('docker', [
    'exec',
    '-i',
    DOCKER_CONTAINER,
    'psql',
    '-U',
    DB_USER,
    '-d',
    DB_NAME,
    '-v',
    'ON_ERROR_STOP=1',
    '-X',
    '-t',
    '-A',
  ], {
    input: sql,
    encoding: 'utf-8',
  })

  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(result.stderr || result.stdout)
  return result.stdout.trim()
}

/**
 * 执行 SQL 并以 JSON 数组形式解析返回
 */
export function runPsqlJson(sql) {
  const cleanSql = sql.trim().replace(/;\s*$/, '')
  const jsonSql = `SELECT coalesce(json_agg(t), '[]'::json) FROM (${cleanSql}) t;`
  const raw = runPsql(jsonSql)
  if (!raw || raw === '') return []
  try {
    return JSON.parse(raw)
  } catch {
    return []
  }
}
