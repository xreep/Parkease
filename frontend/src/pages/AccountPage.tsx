import { useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { toast } from 'sonner'
import { FormError } from '../components/AuthCard'
import { Button } from '../components/ui/Button'
import { TextField } from '../components/ui/TextField'
import { useAuth } from '../auth/AuthProvider'
import type { User } from '../auth/types'
import { api } from '../lib/api'
import { errorMessage } from '../lib/errors'

const profileSchema = z.object({
  name: z.string().trim().min(2, 'Enter your full name').max(100),
  phone: z.string().trim().regex(/^([6-9]\d{9})?$/, 'Enter a 10-digit mobile number'),
})
type ProfileValues = z.infer<typeof profileSchema>

const passwordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Enter your current password'),
    newPassword: z
      .string()
      .min(8, 'Use at least 8 characters')
      .max(72, 'Use at most 72 characters')
      .regex(/^(?=.*[A-Za-z])(?=.*\d).+$/, 'Include at least one letter and one number'),
    confirmPassword: z.string(),
  })
  .refine((v) => v.newPassword === v.confirmPassword, { message: 'Passwords do not match', path: ['confirmPassword'] })
type PasswordValues = z.infer<typeof passwordSchema>

const roleLabel = { DRIVER: 'Driver', OWNER: 'Parking owner', ADMIN: 'Administrator' } as const

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
      <h2 className="text-lg font-semibold">{title}</h2>
      <div className="mt-4">{children}</div>
    </section>
  )
}

function ProfileForm({ user }: { user: User }) {
  const { setUser } = useAuth()
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<ProfileValues>({
    resolver: zodResolver(profileSchema),
    defaultValues: { name: user.name, phone: user.phone ?? '' },
  })

  async function onSubmit(values: ProfileValues) {
    setFormError(null)
    try {
      const { data } = await api.patch<User>('/me', values)
      setUser(data)
      toast.success('Profile updated')
    } catch (error) {
      setFormError(errorMessage(error))
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
      <FormError message={formError} />
      <TextField label="Full name" error={errors.name?.message} {...register('name')} />
      <TextField label="Email" value={user.email} disabled readOnly hint="Email cannot be changed" />
      <TextField label="Mobile number" type="tel" inputMode="numeric" error={errors.phone?.message} {...register('phone')} />
      <Button type="submit" loading={isSubmitting}>Save changes</Button>
    </form>
  )
}

function PasswordForm() {
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, reset, formState: { errors, isSubmitting } } = useForm<PasswordValues>({
    resolver: zodResolver(passwordSchema),
  })

  async function onSubmit(values: PasswordValues) {
    setFormError(null)
    try {
      await api.post('/me/password', { currentPassword: values.currentPassword, newPassword: values.newPassword })
      reset()
      toast.success('Password changed')
    } catch (error) {
      setFormError(errorMessage(error))
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
      <FormError message={formError} />
      <TextField label="Current password" type="password" autoComplete="current-password" error={errors.currentPassword?.message} {...register('currentPassword')} />
      <TextField label="New password" type="password" autoComplete="new-password" error={errors.newPassword?.message} {...register('newPassword')} />
      <TextField label="Confirm new password" type="password" autoComplete="new-password" error={errors.confirmPassword?.message} {...register('confirmPassword')} />
      <Button type="submit" variant="secondary" loading={isSubmitting}>Change password</Button>
    </form>
  )
}

export function AccountPage() {
  const { user } = useAuth()
  if (!user) return null
  return (
    <div className="mx-auto max-w-2xl space-y-6 px-4 py-10 sm:px-6">
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Your account</h1>
        <p className="mt-1 text-sm text-slate-500">
          {roleLabel[user.role]} · {user.emailVerified ? 'Email verified' : 'Email not verified'}
        </p>
      </div>
      <Section title="Profile"><ProfileForm user={user} /></Section>
      <Section title="Password"><PasswordForm /></Section>
    </div>
  )
}
