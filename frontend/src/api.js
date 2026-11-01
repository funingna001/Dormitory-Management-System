import { ref } from 'vue'
export const currentUser = ref(null)
export const mustChangePassword = ref(false)
let token
// CSRF 令牌只放在当前页面内存中，提交修改请求时再带给后端。
export async function refreshCsrf() {
  const res = await fetch('/api/auth/csrf', { credentials: 'include', cache: 'no-store' })
  if (!res.ok) throw new Error('无法连接服务，请稍后重试')
  token = await res.json()
}
export async function synchronizeSession() {
  // 只重新读取登录状态，不会再次提交账号和密码。
  await refreshCsrf()
  const data = await api('/auth/me')
  currentUser.value = data.user
  mustChangePassword.value = !!data.mustChangePassword
}
export async function api(path, options = {}) {
  const method = options.method || 'GET'
  const headers = new Headers(options.headers)
  let body = options.body
  if (body && !(body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(body)
  }
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    if (!token) await refreshCsrf()
    headers.set(token.headerName, token.token)
  }
  const res = await fetch('/api' + path, { ...options, method, headers, body, credentials: 'include', cache: 'no-store' })
  if (!res.ok) {
    if (res.status === 401 && path !== '/auth/login') { currentUser.value = null; token = undefined }
    if (res.status === 403 && !['GET','HEAD','OPTIONS'].includes(method)) {
      // 会话过期时，修改请求可能先返回 403。用查询确认状态，不重试原来的修改。
      token = undefined
      const session = await fetch('/api/auth/me', { credentials: 'include', cache: 'no-store' }).catch(() => null)
      if (session?.status === 401) currentUser.value = null
    }
    const error = await res.json().catch(() => ({}))
    const failure = new Error(error.message || `请求失败（${res.status}）`)
    failure.status = res.status
    failure.retryAfter = res.headers.get('Retry-After')
    throw failure
  }
  if (options.download) return res.blob()
  return res.json()
}
export async function download(kind) {
  const blob = await api('/export/' + kind, { download: true })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `宿舍管理-${kind}.xlsx`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
