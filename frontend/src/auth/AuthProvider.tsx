import { createContext, startTransition, useContext, useEffect, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
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
  const queryClient = useQueryClient()
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(() => tokenStore.getAccess() !== null)

  useEffect(() => {
    setSessionExpiredHandler(() => {
      queryClient.clear()
      setUser(null)
    })
    if (!tokenStore.getAccess()) return
    api
      .get<User>('/me')
      .then((res) => setUser(res.data))
      .catch(() => {
        // Leave user null. Only the api interceptor clears tokens (on a rejected refresh),
        // so a transient failure never destroys a still-valid session.
      })
      .finally(() => setLoading(false))
  }, [queryClient])

  function accept(data: AuthResponse): User {
    tokenStore.set(data.accessToken, data.refreshToken)
    // Never show a previous user's cached data to the new session.
    queryClient.clear()
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
      queryClient.clear()
      // A transition, like the caller's navigate('/'), so the route change wins over RequireRole's redirect.
      startTransition(() => setUser(null))
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
