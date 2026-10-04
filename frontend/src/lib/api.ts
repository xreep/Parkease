import axios, { isAxiosError, type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { tokenStore } from './tokenStore'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL ?? '/api/v1',
})

let onSessionExpired: (() => void) | null = null

export function setSessionExpiredHandler(handler: () => void) {
  onSessionExpired = handler
}

api.interceptors.request.use((config) => {
  const token = tokenStore.getAccess()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

type RefreshResult = { token: string } | { expired: true } | { failed: true }

let refreshing: Promise<RefreshResult> | null = null

/**
 * Only a 401/403 from the refresh endpoint means the session is really over.
 * Network errors and 5xx are transient: keep the tokens so the user stays signed in.
 */
async function refreshAccessToken(): Promise<RefreshResult> {
  const refreshToken = tokenStore.getRefresh()
  if (!refreshToken) return { expired: true }
  try {
    const { data } = await api.post<{ accessToken: string; refreshToken: string }>('/auth/refresh', { refreshToken })
    tokenStore.set(data.accessToken, data.refreshToken)
    return { token: data.accessToken }
  } catch (e) {
    if (isAxiosError(e) && [401, 403].includes(e.response?.status ?? 0)) {
      tokenStore.clear()
      return { expired: true }
    }
    return { failed: true }
  }
}

type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean }

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as RetriableConfig | undefined
    const isAuthCall = original?.url?.startsWith('/auth/') ?? false
    if (error.response?.status === 401 && original && !original._retry && !isAuthCall) {
      original._retry = true
      refreshing ??= refreshAccessToken().finally(() => {
        refreshing = null
      })
      const result = await refreshing
      if ('token' in result) {
        original.headers.Authorization = `Bearer ${result.token}`
        return api(original)
      }
      if ('expired' in result) {
        tokenStore.clear()
        onSessionExpired?.()
      }
    }
    return Promise.reject(error)
  },
)
