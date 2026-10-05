import { useId, type ComponentProps } from 'react'
import clsx from 'clsx'

export type TextAreaProps = ComponentProps<'textarea'> & { label: string; error?: string; hint?: string }

export function TextArea({ label, error, hint, id, className, rows = 4, ...props }: TextAreaProps) {
  const autoId = useId()
  const areaId = id ?? autoId
  const messageId = `${areaId}-message`
  return (
    <div className={clsx('space-y-1.5', className)}>
      <label htmlFor={areaId} className="block text-sm font-medium text-slate-700 dark:text-slate-300">
        {label}
      </label>
      <textarea
        id={areaId}
        rows={rows}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || hint ? messageId : undefined}
        className={clsx(
          'block w-full rounded-lg border bg-white px-3 py-2.5 text-sm shadow-sm outline-none transition',
          'placeholder:text-slate-400 focus:ring-2 dark:bg-slate-900',
          error
            ? 'border-red-500 focus:ring-red-500/30'
            : 'border-slate-300 focus:border-brand-500 focus:ring-brand-500/30 dark:border-slate-700',
        )}
        {...props}
      />
      {error ? (
        <p id={messageId} className="text-sm text-red-600 dark:text-red-400">{error}</p>
      ) : hint ? (
        <p id={messageId} className="text-xs text-slate-500">{hint}</p>
      ) : null}
    </div>
  )
}
