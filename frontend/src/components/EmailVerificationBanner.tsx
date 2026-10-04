import { useState } from 'react'
import { MailWarning } from 'lucide-react'
import { toast } from 'sonner'
import { useAuth } from '../auth/AuthProvider'
import { api } from '../lib/api'
import { errorMessage } from '../lib/errors'

export function EmailVerificationBanner() {
  const { user } = useAuth()
  const [sending, setSending] = useState(false)
  if (!user || user.emailVerified) return null

  async function resend() {
    setSending(true)
    try {
      await api.post('/me/resend-verification')
      toast.success(`Verification email sent to ${user!.email}`)
    } catch (error) {
      toast.error(errorMessage(error))
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="border-b border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-2 px-4 py-2 text-sm sm:px-6">
        <MailWarning className="h-4 w-4" />
        <span>Please verify your email address to receive booking confirmations.</span>
        <button type="button" onClick={resend} disabled={sending} className="font-semibold underline disabled:opacity-60">
          {sending ? 'Sending…' : 'Resend email'}
        </button>
      </div>
    </div>
  )
}
