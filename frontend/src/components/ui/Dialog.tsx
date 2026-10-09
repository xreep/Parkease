import clsx from 'clsx'
import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { errorMessage } from '../../lib/errors'
import { FormError } from '../AuthCard'
import { Button } from './Button'
import { TextArea } from './TextArea'

export type DialogProps = {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  /** While busy, clicking the backdrop or pressing Escape does not dismiss the dialog. */
  busy?: boolean
  /** A wider panel, for content such as a photo. */
  wide?: boolean
}

export function Dialog({ open, title, onClose, children, busy = false, wide = false }: DialogProps) {
  const titleId = useId()
  const panelRef = useRef<HTMLDivElement>(null)
  const onCloseRef = useRef(onClose)
  const busyRef = useRef(busy)
  useEffect(() => {
    onCloseRef.current = onClose
    busyRef.current = busy
  })

  useEffect(() => {
    if (!open) return
    const previous = document.activeElement as HTMLElement | null
    const panel = panelRef.current
    const focusable = () =>
      Array.from(
        panel?.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])') ?? [],
      ).filter((el) => !el.hasAttribute('disabled'))
    ;(focusable()[0] ?? panel)?.focus()

    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.stopPropagation()
        if (!busyRef.current) onCloseRef.current()
      } else if (e.key === 'Tab') {
        const items = focusable()
        if (items.length === 0) return
        const first = items[0]
        const last = items[items.length - 1]
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault()
          last.focus()
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault()
          first.focus()
        }
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      previous?.focus?.()
    }
  }, [open])

  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-slate-950/50 p-4 sm:items-center" onMouseDown={(e) => {
      if (e.target === e.currentTarget && !busy) onClose()
    }}>
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={clsx('w-full rounded-2xl border border-slate-200 bg-white p-6 shadow-xl outline-none dark:border-slate-800 dark:bg-slate-900', wide ? 'max-w-3xl' : 'max-w-md')}
      >
        <h2 id={titleId} className="text-lg font-semibold">{title}</h2>
        <div className="mt-4">{children}</div>
      </div>
    </div>
  )
}

const DEFAULT_REASON_MAX = 500
const makeReasonSchema = (max: number) =>
  z.object({
    reason: z.string().trim().min(1, 'Please give a reason').max(max, `Use at most ${max} characters`),
  })
type ReasonValues = z.infer<ReturnType<typeof makeReasonSchema>>

export type ReasonDialogProps = {
  open: boolean
  title: string
  confirmLabel: string
  onConfirm: (reason: string) => void | Promise<void>
  onClose: () => void
  /** Shown under the reason field. */
  helper?: string
  /** Longest reason accepted (default 500). */
  maxLength?: number
  /** Shown above the reason field: what the reason is for (e.g. which booking). */
  summary?: ReactNode
}

type ReasonFormProps = Omit<ReasonDialogProps, 'open' | 'title'> & { onBusyChange: (busy: boolean) => void }

function ReasonForm({ confirmLabel, onConfirm, onClose, onBusyChange, helper, maxLength = DEFAULT_REASON_MAX, summary }: ReasonFormProps) {
  const [formError, setFormError] = useState<string | null>(null)
  const schema = useMemo(() => makeReasonSchema(maxLength), [maxLength])
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<ReasonValues>({
    resolver: zodResolver(schema),
    defaultValues: { reason: '' },
  })

  async function submit({ reason }: ReasonValues) {
    setFormError(null)
    onBusyChange(true)
    try {
      await onConfirm(reason)
    } catch (error) {
      setFormError(errorMessage(error))
    } finally {
      onBusyChange(false)
    }
  }

  return (
    <form onSubmit={handleSubmit(submit)} noValidate className="space-y-4">
      {summary}
      <FormError message={formError} />
      <TextArea label="Reason" rows={4} hint={helper} error={errors.reason?.message} {...register('reason')} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" disabled={isSubmitting} onClick={onClose}>Cancel</Button>
        <Button type="submit" variant="danger" loading={isSubmitting}>{confirmLabel}</Button>
      </div>
    </form>
  )
}

/** Asks for a required reason (max 500 chars unless `maxLength`). The form remounts on each open, so it starts empty. */
export function ReasonDialog({ open, title, confirmLabel, onConfirm, onClose, helper, maxLength, summary }: ReasonDialogProps) {
  const [busy, setBusy] = useState(false)
  return (
    <Dialog open={open} title={title} onClose={onClose} busy={busy}>
      <ReasonForm confirmLabel={confirmLabel} onConfirm={onConfirm} onClose={onClose} onBusyChange={setBusy} helper={helper} maxLength={maxLength} summary={summary} />
    </Dialog>
  )
}
