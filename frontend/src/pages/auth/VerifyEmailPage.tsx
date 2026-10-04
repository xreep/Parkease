import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { CheckCircle2, XCircle } from 'lucide-react'
import { AuthCard } from '../../components/AuthCard'
import { Spinner } from '../../components/ui/Spinner'
import { useAuth } from '../../auth/AuthProvider'
import type { User } from '../../auth/types'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'

type Status = { state: 'loading' } | { state: 'done' } | { state: 'failed'; message: string }

export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const { user, setUser } = useAuth()
  const [status, setStatus] = useState<Status>(token ? { state: 'loading' } : { state: 'failed', message: 'This verification link is incomplete.' })
  const started = useRef(false)

  useEffect(() => {
    // Tokens are single-use: guard against StrictMode running this effect twice.
    if (!token || started.current) return
    started.current = true
    api
      .post('/auth/verify-email', { token })
      .then(() => setStatus({ state: 'done' }))
      .catch((error) => setStatus({ state: 'failed', message: errorMessage(error) }))
  }, [token])

  useEffect(() => {
    if (status.state === 'done' && user && !user.emailVerified) {
      api.get<User>('/me').then((res) => setUser(res.data)).catch(() => undefined)
    }
  }, [status.state, user, setUser])

  if (status.state === 'loading') {
    return (
      <AuthCard title="Verifying your email…">
        <Spinner className="h-6 w-6 text-brand-600" />
      </AuthCard>
    )
  }

  if (status.state === 'failed') {
    return (
      <AuthCard title="Verification failed">
        <div className="flex gap-3 text-sm text-slate-600 dark:text-slate-300">
          <XCircle className="h-6 w-6 shrink-0 text-red-500" />
          <p>{status.message} You can request a new link from your account page.</p>
        </div>
      </AuthCard>
    )
  }

  return (
    <AuthCard title="Email verified">
      <div className="flex gap-3 text-sm text-slate-600 dark:text-slate-300">
        <CheckCircle2 className="h-6 w-6 shrink-0 text-brand-600" />
        <p>Thanks! Your email address is confirmed.</p>
      </div>
      <Link to={user ? '/account' : '/login'} className="mt-6 inline-block text-sm font-semibold text-brand-700 hover:underline dark:text-brand-400">
        {user ? 'Go to your account' : 'Log in'}
      </Link>
    </AuthCard>
  )
}
