import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { AuthCard, FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { TextField } from '../../components/ui/TextField'
import { useAuth } from '../../auth/AuthProvider'
import { homeFor } from '../../auth/types'
import { errorMessage } from '../../lib/errors'
import { usePageTitle } from '../../lib/usePageTitle'

const schema = z.object({
  email: z.email('Enter a valid email'),
  password: z.string().min(1, 'Enter your password'),
})
type FormValues = z.infer<typeof schema>

/** Only allow same-site relative redirects. */
function safeNext(next: string | null): string | null {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : null
}

export function LoginPage() {
  usePageTitle('Log in')
  const { login } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<FormValues>({
    resolver: zodResolver(schema),
  })

  async function onSubmit(values: FormValues) {
    setFormError(null)
    try {
      const user = await login(values.email, values.password)
      toast.success(`Welcome back, ${user.name.split(' ')[0]}!`)
      navigate(safeNext(params.get('next')) ?? homeFor(user.role), { replace: true })
    } catch (error) {
      setFormError(errorMessage(error))
    }
  }

  return (
    <AuthCard title="Log in" subtitle="Welcome back. Find and manage your parking.">
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
        <FormError message={formError} />
        <TextField label="Email" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
        <TextField label="Password" type="password" autoComplete="current-password" error={errors.password?.message} {...register('password')} />
        <div className="text-right text-sm">
          <Link to="/forgot-password" className="font-medium text-brand-700 hover:underline dark:text-brand-400">
            Forgot password?
          </Link>
        </div>
        <Button type="submit" loading={isSubmitting} className="w-full">Log in</Button>
      </form>
      <p className="mt-6 text-center text-sm text-slate-500 dark:text-slate-400">
        New to ParkEase?{' '}
        <Link to="/register" className="font-semibold text-brand-700 hover:underline dark:text-brand-400">Create an account</Link>
      </p>
    </AuthCard>
  )
}
