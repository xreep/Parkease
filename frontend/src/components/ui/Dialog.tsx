import { useEffect, useId, useRef, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Button } from './Button'
import { TextArea } from './TextArea'

export type DialogProps = { open: boolean; title: string; onClose: () => void; children: ReactNode }

export function Dialog({ open, title, onClose, children }: DialogProps) {
  const titleId = useId()
  const panelRef = useRef<HTMLDivElement>(null)
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
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
        onCloseRef.current()
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
      if (e.target === e.currentTarget) onClose()
    }}>
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-xl outline-none dark:border-slate-800 dark:bg-slate-900"
      >
        <h2 id={titleId} className="text-lg font-semibold">{title}</h2>
        <div className="mt-4">{children}</div>
      </div>
    </div>
  )
}

const reasonSchema = z.object({
  reason: z.string().trim().min(1, 'Please give a reason').max(500, 'Use at most 500 characters'),
})
type ReasonValues = z.infer<typeof reasonSchema>

export type ReasonDialogProps = {
  open: boolean
  title: string
  confirmLabel: string
  onConfirm: (reason: string) => void | Promise<void>
  onClose: () => void
}

function ReasonForm({ confirmLabel, onConfirm, onClose }: Omit<ReasonDialogProps, 'open' | 'title'>) {
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<ReasonValues>({
    resolver: zodResolver(reasonSchema),
    defaultValues: { reason: '' },
  })
  return (
    <form onSubmit={handleSubmit(({ reason }) => onConfirm(reason))} noValidate className="space-y-4">
      <TextArea label="Reason" rows={4} error={errors.reason?.message} {...register('reason')} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
        <Button type="submit" variant="danger" loading={isSubmitting}>{confirmLabel}</Button>
      </div>
    </form>
  )
}

/** Asks for a required reason (max 500 chars). The form remounts on each open, so it starts empty. */
export function ReasonDialog({ open, title, confirmLabel, onConfirm, onClose }: ReasonDialogProps) {
  return (
    <Dialog open={open} title={title} onClose={onClose}>
      <ReasonForm confirmLabel={confirmLabel} onConfirm={onConfirm} onClose={onClose} />
    </Dialog>
  )
}
