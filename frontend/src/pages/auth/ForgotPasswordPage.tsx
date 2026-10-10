import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Link } from 'react-router-dom'
import { MailCheck } from 'lucide-react'
import { AuthCard, FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { TextField } from '../../components/ui/TextField'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import { usePageTitle } from '../../lib/usePageTitle'

const schema = z.object({ email: z.email('Enter a valid email') })
type FormValues = z.infer<typeof schema>

export function ForgotPasswordPage() {
  usePageTitle('Forgot your password?')
  const [sent, setSent] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<FormValues>({ resolver: zodResolver(schema) })

  async function onSubmit(values: FormValues) {
    setFormError(null)
    try {
      await api.post('/auth/forgot-password', values)
      setSent(true)
    } catch (error) {
      setFormError(errorMessage(error))
    }
  }

  if (sent) {
    return (
      <AuthCard title="Check your inbox">
        <div className="flex gap-3 text-sm text-slate-600 dark:text-slate-300">
          <MailCheck className="h-6 w-6 shrink-0 text-brand-600" />
          <p>If an account exists for that email, we have sent a link to reset your password. It expires in 30 minutes.</p>
        </div>
        <Link to="/login" className="mt-6 inline-block text-sm font-semibold text-brand-700 hover:underline dark:text-brand-400">Back to log in</Link>
      </AuthCard>
    )
  }

  return (
    <AuthCard title="Forgot your password?" subtitle="Enter your email and we will send you a reset link.">
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
        <FormError message={formError} />
        <TextField label="Email" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
        <Button type="submit" loading={isSubmitting} className="w-full">Send reset link</Button>
      </form>
    </AuthCard>
  )
}
