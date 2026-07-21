export function csrfHeaders(): Record<string, string> {
  const token = document.cookie
    .split(';')
    .map((entry) => entry.trim())
    .find((entry) => entry.startsWith('XSRF-TOKEN='))
    ?.slice('XSRF-TOKEN='.length)
  return token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {}
}
