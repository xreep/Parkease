import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { isAxiosError } from 'axios'
import { api, setSessionExpiredHandler } from '../lib/api'
import { tokenStore } from '../lib/tokenStore'
import type { AuthResponse, RegisterInput, User } from './types'

type AuthContextValue = {
  user: User | null
  loading: boolean
  login: (email: string, password: string) => Promise<User>
  register: (input: RegisterInput) => Promise<User>
  logout: () => Promise<void>
  setUser: (user: User) => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(() => tokenStore.getAccess() !== null)

  useEffect(() => {
    setSessionExpiredHandler(() => setUser(null))
    if (!tokenStore.getAccess()) return
    api
      .get<User>('/me')
      .then((res) => setUser(res.data))
      .catch((e) => {
        // Only a rejected token ends the session; network/5xx errors keep it for a later retry.
        if (isAxiosError(e) && [401, 403].includes(e.response?.status ?? 0)) tokenStore.clear()
      })
      .finally(() => setLoading(false))
  }, [])

  function accept(data: AuthResponse): User {
    tokenStore.set(data.accessToken, data.refreshToken)
    setUser(data.user)
    return data.user
  }

  const value: AuthContextValue = {
    user,
    loading,
    login: async (email, password) => accept((await api.post<AuthResponse>('/auth/login', { email, password })).data),
    register: async (input) => accept((await api.post<AuthResponse>('/auth/register', input)).data),
    logout: async () => {
      const refreshToken = tokenStore.getRefresh()
      tokenStore.clear()
      setUser(null)
      if (refreshToken) await api.post('/auth/logout', { refreshToken }).catch(() => undefined)
    },
    setUser,
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
