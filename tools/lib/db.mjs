import { spawnSync, execSync } from 'node:child_process'
import net from 'node:net'

export const DOCKER_CONTAINER = process.env.POSTGRES_CONTAINER || 'medication-video-platform-postgres-1'
export const DB_USER = process.env.POSTGRES_USER || 'medication_local'
export const DB_NAME = process.env.POSTGRES_DB || 'medication_platform'
export const DB_PORT = parseInt(process.env.POSTGRES_PORT || '15432', 10)

/**
 * 检查 PostgreSQL 端口是否正常监听并接受连接 (极速原生 TCP 探测，0 进程开销，永不 OOM)
 */
export async function isPostgresReady() {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host: '127.0.0.1', port: DB_PORT, timeout: 2000 })
    socket.once('connect', () => {
      socket.destroy()
      resolve(true)
    })
    socket.once('timeout', () => {
      socket.destroy()
      resolve(false)
    })
    socket.once('error', () => {
      socket.destroy()
      resolve(false)
    })
  })
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
