import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Building2, Car } from 'lucide-react'
import clsx from 'clsx'
import { toast } from 'sonner'
import { AuthCard, FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { TextField } from '../../components/ui/TextField'
import { useAuth } from '../../auth/AuthProvider'
import { homeFor } from '../../auth/types'
import { toProblem } from '../../lib/errors'

const schema = z
  .object({
    role: z.enum(['DRIVER', 'OWNER']),
    name: z.string().trim().min(2, 'Enter your full name').max(100),
    email: z.email('Enter a valid email'),
    phone: z.string().trim().regex(/^([6-9]\d{9})?$/, 'Enter a 10-digit mobile number'),
    password: z
      .string()
      .min(8, 'Use at least 8 characters')
      .max(72, 'Use at most 72 characters')
      .regex(/^(?=.*[A-Za-z])(?=.*\d).+$/, 'Include at least one letter and one number'),
    confirmPassword: z.string(),
  })
  .refine((v) => v.password === v.confirmPassword, { message: 'Passwords do not match', path: ['confirmPassword'] })
type FormValues = z.infer<typeof schema>
type ServerField = 'name' | 'email' | 'phone' | 'password'

const roleOptions = [
  { value: 'DRIVER', title: 'I need parking', body: 'Find and book slots', Icon: Car },
  { value: 'OWNER', title: 'I have parking', body: 'Earn from unused space', Icon: Building2 },
] as const

export function RegisterPage() {
  const { register: registerUser } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register, handleSubmit, watch, setError,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { role: params.get('role') === 'OWNER' ? 'OWNER' : 'DRIVER', phone: '' },
  })
  const role = watch('role')

  async function onSubmit(values: FormValues) {
    setFormError(null)
    try {
      const user = await registerUser({
        role: values.role,
        name: values.name,
        email: values.email,
        password: values.password,
        phone: values.phone || undefined,
      })
      toast.success('Account created! Check your email to verify your address.')
      navigate(homeFor(user.role), { replace: true })
    } catch (error) {
      const problem = toProblem(error)
      problem.fieldErrors.forEach((f) => setError(f.field as ServerField, { message: f.message }))
      setFormError(problem.detail)
    }
  }

  return (
    <AuthCard title="Create your account" subtitle="Join SmartPark in under a minute.">
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
        <FormError message={formError} />
        <fieldset>
          <legend className="mb-2 text-sm font-medium text-slate-700 dark:text-slate-300">I want to</legend>
          <div className="grid grid-cols-2 gap-3">
            {roleOptions.map(({ value, title, body, Icon }) => (
              <label
                key={value}
                className={clsx(
                  'cursor-pointer rounded-xl border p-3 transition',
                  role === value
                    ? 'border-brand-600 bg-brand-50 ring-2 ring-brand-600/20 dark:bg-brand-900/30'
                    : 'border-slate-300 hover:border-slate-400 dark:border-slate-700',
                )}
              >
                <input type="radio" value={value} className="sr-only" {...register('role')} />
                <Icon className="h-5 w-5 text-brand-600" />
                <span className="mt-2 block text-sm font-semibold">{title}</span>
                <span className="block text-xs text-slate-500">{body}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <TextField label="Full name" autoComplete="name" error={errors.name?.message} {...register('name')} />
        <TextField label="Email" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
        <TextField label="Mobile number (optional)" type="tel" inputMode="numeric" autoComplete="tel-national"
          placeholder="9876543210" error={errors.phone?.message} {...register('phone')} />
        <TextField label="Password" type="password" autoComplete="new-password" hint="At least 8 characters with a letter and a number"
          error={errors.password?.message} {...register('password')} />
        <TextField label="Confirm password" type="password" autoComplete="new-password"
          error={errors.confirmPassword?.message} {...register('confirmPassword')} />
        <Button type="submit" loading={isSubmitting} className="w-full">Create account</Button>
      </form>
      <p className="mt-6 text-center text-sm text-slate-500">
        Already have an account?{' '}
        <Link to="/login" className="font-semibold text-brand-700 hover:underline dark:text-brand-400">Log in</Link>
      </p>
    </AuthCard>
  )
}
