import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { AuthCard, FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { TextField } from '../../components/ui/TextField'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'

const schema = z
  .object({
    password: z
      .string()
      .min(8, 'Use at least 8 characters')
      .max(72, 'Use at most 72 characters')
      .regex(/^(?=.*[A-Za-z])(?=.*\d).+$/, 'Include at least one letter and one number'),
    confirmPassword: z.string(),
  })
  .refine((v) => v.password === v.confirmPassword, { message: 'Passwords do not match', path: ['confirmPassword'] })
type FormValues = z.infer<typeof schema>

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const navigate = useNavigate()
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<FormValues>({ resolver: zodResolver(schema) })

  if (!token) {
    return (
      <AuthCard title="Invalid link">
        <p className="text-sm text-slate-600 dark:text-slate-300">This reset link is incomplete.</p>
        <Link to="/forgot-password" className="mt-4 inline-block text-sm font-semibold text-brand-700 hover:underline">Request a new link</Link>
      </AuthCard>
    )
  }

  async function onSubmit(values: FormValues) {
    setFormError(null)
    try {
      await api.post('/auth/reset-password', { token, password: values.password })
      toast.success('Password updated. Please log in with your new password.')
      navigate('/login', { replace: true })
    } catch (error) {
      setFormError(errorMessage(error))
    }
  }

  return (
    <AuthCard title="Choose a new password">
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
        <FormError message={formError} />
        <TextField label="New password" type="password" autoComplete="new-password" error={errors.password?.message} {...register('password')} />
        <TextField label="Confirm new password" type="password" autoComplete="new-password" error={errors.confirmPassword?.message} {...register('confirmPassword')} />
        <Button type="submit" loading={isSubmitting} className="w-full">Reset password</Button>
      </form>
    </AuthCard>
  )
}
