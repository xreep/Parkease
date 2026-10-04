import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
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

let refreshing: Promise<string | null> | null = null

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = tokenStore.getRefresh()
  if (!refreshToken) return null
  try {
    const { data } = await api.post<{ accessToken: string; refreshToken: string }>('/auth/refresh', { refreshToken })
    tokenStore.set(data.accessToken, data.refreshToken)
    return data.accessToken
  } catch {
    tokenStore.clear()
    return null
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
      const token = await refreshing
      if (token) {
        original.headers.Authorization = `Bearer ${token}`
        return api(original)
      }
      tokenStore.clear()
      onSessionExpired?.()
    }
    return Promise.reject(error)
  },
)
