import axios, { isAxiosError, type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { toast } from 'sonner'
import { tokenStore } from './tokenStore'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api/v1',
})

const SUSPENDED_MESSAGE = 'Your account has been suspended.'
const SERVER_ERROR_MESSAGE = 'Something went wrong on our side. Please try again in a moment.'

/** The problem body of a failed request, also when the request asked for a blob (a download). */
async function problemBody(error: AxiosError): Promise<{ code?: string; detail?: string } | undefined> {
  const data = error.response?.data
  if (data instanceof Blob) {
    try {
      return JSON.parse(await data.text()) as { code?: string; detail?: string }
    } catch {
      return undefined
    }
  }
  return data as { code?: string; detail?: string } | undefined
}

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
 *
 * Tabs share localStorage, and the backend revokes every session if a rotated refresh token is
 * replayed. So a refresh is skipped when the stored access token differs from the one the failed
 * request used (another tab/request already refreshed), and the real call runs under a
 * cross-tab Web Lock with that check repeated inside it.
 */
async function refreshAccessToken(failedToken: string | null): Promise<RefreshResult> {
  const alreadyRefreshed = (): RefreshResult | null => {
    const current = tokenStore.getAccess()
    return current && current !== failedToken ? { token: current } : null
  }

  const run = async (): Promise<RefreshResult> => {
    const reused = alreadyRefreshed()
    if (reused) return reused
    const refreshToken = tokenStore.getRefresh()
    if (!refreshToken) return { expired: true }
    try {
      const { data } = await api.post<{ accessToken: string; refreshToken: string }>('/auth/refresh', { refreshToken })
      tokenStore.set(data.accessToken, data.refreshToken)
      return { token: data.accessToken }
    } catch (e) {
      if (isAxiosError(e) && [401, 403].includes(e.response?.status ?? 0)) {
        const body = e.response?.data as { code?: string; detail?: string } | undefined
        if (e.response?.status === 403 && body?.code === 'ACCOUNT_SUSPENDED') toast.error(body.detail ?? SUSPENDED_MESSAGE)
        tokenStore.clear()
        return { expired: true }
      }
      return { failed: true }
    }
  }

  const reused = alreadyRefreshed()
  if (reused) return reused
  return typeof navigator !== 'undefined' && navigator.locks ? navigator.locks.request('sp-refresh', run) : run()
}

function bearerToken(config: InternalAxiosRequestConfig): string | null {
  const header = config.headers?.Authorization
  return typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : null
}

type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean }

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as RetriableConfig | undefined
    const isAuthCall = original?.url?.startsWith('/auth/') ?? false
    if (error.response?.status === 401 && original && !original._retry && !isAuthCall) {
      original._retry = true
      refreshing ??= refreshAccessToken(bearerToken(original)).finally(() => {
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
    // A suspended account is refused everywhere, public reads included: end the session and say why. Only for the
    // token that is still the stored one, so a late answer to an old request can't sign out a fresh login, and the
    // first of several parallel answers is the only one that acts.
    if (error.response?.status === 403 && original && !isAuthCall) {
      const sent = bearerToken(original)
      const body = await problemBody(error)
      // Checked after reading the body, so of several parallel answers only the first still finds its token stored.
      if (sent !== null && sent === tokenStore.getAccess() && body?.code === 'ACCOUNT_SUSPENDED') {
        tokenStore.clear()
        onSessionExpired?.()
        toast.error(body.detail ?? SUSPENDED_MESSAGE)
      }
    }
    // A server fault (5xx) while loading something is nothing the user did, and pages only show a generic "could not
    // load" for it: add one friendly toast (same id, so parallel failures show once). Writes are left to their own
    // forms, which show the server's message inline.
    if ((error.response?.status ?? 0) >= 500 && original?.method?.toLowerCase() === 'get') {
      toast.error(SERVER_ERROR_MESSAGE, { id: 'server-error' })
    }
    return Promise.reject(error)
  },
)
