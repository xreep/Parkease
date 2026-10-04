export type Role = 'DRIVER' | 'OWNER' | 'ADMIN'

export type User = {
  id: number
  name: string
  email: string
  phone: string | null
  role: Role
  emailVerified: boolean
  avatarUrl: string | null
}

export type AuthResponse = { accessToken: string; refreshToken: string; expiresIn: number; user: User }

export type RegisterInput = {
  name: string
  email: string
  password: string
  phone?: string
  role: 'DRIVER' | 'OWNER'
}

export function homeFor(role: Role): string {
  if (role === 'ADMIN') return '/admin'
  if (role === 'OWNER') return '/owner'
  return '/driver'
}
