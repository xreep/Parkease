import type { ComponentProps } from 'react'
import clsx from 'clsx'
import { Spinner } from './Spinner'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'

const variants: Record<Variant, string> = {
  primary: 'bg-brand-700 text-white shadow-sm hover:bg-brand-800 focus-visible:outline-brand-700',
  secondary:
    'border border-slate-300 bg-white text-slate-800 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800',
  ghost: 'text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800',
  danger: 'bg-red-600 text-white hover:bg-red-700 focus-visible:outline-red-600',
}

export type ButtonProps = ComponentProps<'button'> & { variant?: Variant; loading?: boolean }

export function Button({ variant = 'primary', loading = false, disabled, className, children, ...props }: ButtonProps) {
  return (
    <button
      className={clsx(
        'inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-semibold transition',
        'focus-visible:outline-2 focus-visible:outline-offset-2 disabled:cursor-not-allowed disabled:opacity-60',
        variants[variant],
        className,
      )}
      disabled={disabled || loading}
      {...props}
    >
      {loading && <Spinner className="h-4 w-4" />}
      {children}
    </button>
  )
}
