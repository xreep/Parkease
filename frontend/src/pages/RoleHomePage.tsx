import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import type { Role } from '../auth/types'

const copy: Record<Role, { label: string; body: string }> = {
  DRIVER: { label: 'Driver', body: 'Search for parking near your destination and manage your bookings here.' },
  OWNER: { label: 'Parking owner', body: 'List your parking spaces, set prices and track your earnings here.' },
  ADMIN: { label: 'Administrator', body: 'Verify owners and listings, monitor bookings and view reports here.' },
}

/** Landing page per role; later phases replace each with a full dashboard. */
export function RoleHomePage() {
  const { user } = useAuth()
  if (!user) return null
  const { label, body } = copy[user.role]
  return (
    <section className="mx-auto max-w-4xl px-4 py-12 sm:px-6">
      <p className="text-sm font-semibold uppercase tracking-wide text-brand-700 dark:text-brand-400">{label}</p>
      <h1 className="mt-1 text-3xl font-bold tracking-tight">Welcome, {user.name.split(' ')[0]}</h1>
      <p className="mt-2 text-slate-600 dark:text-slate-400">{body}</p>
      <Link to="/account" className="mt-6 inline-block text-sm font-semibold text-brand-700 hover:underline dark:text-brand-400">
        Manage your account →
      </Link>
    </section>
  )
}
