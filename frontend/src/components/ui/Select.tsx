import { useId, type ComponentProps } from 'react'
import clsx from 'clsx'

export type SelectProps = ComponentProps<'select'> & { label: string; error?: string; hint?: string }

export function Select({ label, error, hint, id, className, children, ...props }: SelectProps) {
  const autoId = useId()
  const selectId = id ?? autoId
  const messageId = `${selectId}-message`
  return (
    <div className={clsx('space-y-1.5', className)}>
      <label htmlFor={selectId} className="block text-sm font-medium text-slate-700 dark:text-slate-300">
        {label}
      </label>
      <select
        id={selectId}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || hint ? messageId : undefined}
        className={clsx(
          'block w-full rounded-lg border bg-white px-3 py-2.5 text-sm shadow-sm outline-none transition',
          'focus:ring-2 dark:bg-slate-900',
          error
            ? 'border-red-500 focus:ring-red-500/30'
            : 'border-slate-300 focus:border-brand-500 focus:ring-brand-500/30 dark:border-slate-700',
        )}
        {...props}
      >
        {children}
      </select>
      {error ? (
        <p id={messageId} className="text-sm text-red-600 dark:text-red-400">{error}</p>
      ) : hint ? (
        <p id={messageId} className="text-xs text-slate-500">{hint}</p>
      ) : null}
    </div>
  )
}
