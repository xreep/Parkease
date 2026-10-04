const ACCESS = 'sp_access'
const REFRESH = 'sp_refresh'

function read(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

export const tokenStore = {
  getAccess: () => read(ACCESS),
  getRefresh: () => read(REFRESH),
  set(access: string, refresh: string) {
    try {
      localStorage.setItem(ACCESS, access)
      localStorage.setItem(REFRESH, refresh)
    } catch {
      // storage unavailable — the session will last only for this page load
    }
  },
  clear() {
    try {
      localStorage.removeItem(ACCESS)
      localStorage.removeItem(REFRESH)
    } catch {
      // ignore
    }
  },
}
