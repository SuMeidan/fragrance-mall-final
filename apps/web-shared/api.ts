import { ref } from 'vue'
import type { Actor, Login } from './models'

export class HttpError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public traceId = '',
  ) {
    super(message)
  }
}
export function createClient(role: 'buyer' | 'admin') {
  const key = `scent.${role}.token`
  const token = ref(sessionStorage.getItem(key) || '')
  const user = ref<Actor | null>(null)
  function clear() {
    token.value = ''
    user.value = null
    sessionStorage.removeItem(key)
  }
  async function request<T>(
    path: string,
    init: RequestInit = {},
    authenticated = true,
  ): Promise<T> {
    const headers = new Headers(init.headers)
    if (init.body && !(init.body instanceof FormData))
      headers.set('Content-Type', 'application/json')
    if (authenticated && token.value)
      headers.set('Authorization', `Bearer ${token.value}`)
    let response: Response
    try {
      response = await fetch(`/api/v1${path}`, {
        ...init,
        headers,
        signal: init.signal || AbortSignal.timeout(25000),
      })
    } catch {
      throw new HttpError(0, 'NETWORK_ERROR', '连接超时或网络中断，请重试。')
    }
    const body = await response.json().catch(() => null)
    if (!response.ok || !body || body.resultCode >= 400) {
      if (authenticated && response.status === 401) clear()
      throw new HttpError(
        response.status,
        body?.errorCode || 'BAD_RESPONSE',
        body?.message || '服务暂时不可用',
        body?.traceId || response.headers.get('X-Trace-Id') || '',
      )
    }
    return body.data as T
  }
  async function login(loginName: string, password: string) {
    const result = await request<Login>(
      `/auth/${role === 'admin' ? 'admin-login' : 'login'}`,
      { method: 'POST', body: JSON.stringify({ loginName, password }) },
      false,
    )
    if (result.user.role !== role.toUpperCase())
      throw new Error('账号角色不匹配')
    token.value = result.token
    user.value = result.user
    sessionStorage.setItem(key, result.token)
  }
  async function me() {
    if (!token.value) return null
    user.value = await request<Actor>('/auth/me')
    if (user.value.role !== role.toUpperCase()) {
      clear()
      throw new Error('请使用正确的账号入口')
    }
    return user.value
  }
  async function logout() {
    try {
      await request('/auth/logout', { method: 'POST' })
    } finally {
      clear()
    }
  }
  return { token, user, request, login, me, logout }
}
export function query(
  values: Record<string, string | number | null | undefined>,
) {
  const params = new URLSearchParams()
  Object.entries(values).forEach(([key, value]) => {
    if (value !== '' && value != null) params.set(key, String(value))
  })
  return '?' + params.toString()
}
export function errorText(error: unknown) {
  if (error instanceof HttpError)
    return `${error.message}${error.traceId ? `（追踪号 ${error.traceId}）` : ''}`
  return error instanceof Error ? error.message : '操作失败，请重试'
}
export function safeReturn(value: unknown, fallback = '/') {
  return typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    !/[\\\u0000-\u001f]/.test(value) &&
    !/^\/(login|register)([/?#]|$)/.test(value)
    ? value
    : fallback
}
