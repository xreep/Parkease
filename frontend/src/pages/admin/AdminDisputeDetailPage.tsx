import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { z } from 'zod'
import { FormError } from '../../components/AuthCard'
import { parseId } from '../../lib/params'
import { DisputePageShell } from '../../components/disputes/DisputePageShell'
import { DisputeView } from '../../components/disputes/DisputeView'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { TextArea } from '../../components/ui/TextArea'
import { TextField } from '../../components/ui/TextField'
import {
  invalidateAdminDisputes,
  NOTES_MAX,
  resolveDispute,
  reviewDispute,
  useAdminDispute,
  type Dispute,
  type DisputeResolution,
} from '../../lib/disputes'
import { errorMessage, toProblem } from '../../lib/errors'
import { formatINR } from '../../lib/format'
import { usePageTitle } from '../../lib/usePageTitle'

const RESOLUTIONS: DisputeResolution[] = ['REFUND_FULL', 'REFUND_PARTIAL', 'NO_REFUND', 'WARNING']

function makeSchema(remaining: number) {
  return z
    .object({
      resolution: z.enum(['REFUND_FULL', 'REFUND_PARTIAL', 'NO_REFUND', 'WARNING'], { error: 'Choose a resolution' }),
      amount: z.string().trim(),
      notes: z.string().trim().min(1, 'Add notes for the record').max(NOTES_MAX, `Use at most ${NOTES_MAX} characters`),
    })
    .superRefine((v, ctx) => {
      if (v.resolution !== 'REFUND_PARTIAL') return
      const n = Number(v.amount)
      const ok = /^\d+(\.\d{1,2})?$/.test(v.amount) && n > 0 && n <= remaining
      if (!ok) ctx.addIssue({ code: 'custom', path: ['amount'], message: `Enter an amount above ₹0, up to ${formatINR(remaining)}` })
    })
}
type Values = z.infer<ReturnType<typeof makeSchema>>

function ResolveForm({ dispute, onClose, onBusyChange }: { dispute: Dispute; onClose: () => void; onBusyChange: (busy: boolean) => void }) {
  const queryClient = useQueryClient()
  const remaining = dispute.refundableRemaining ?? 0
  const schema = useMemo(() => makeSchema(remaining), [remaining])
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, control, formState: { errors, isSubmitting } } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { amount: '', notes: '' },
  })
  const resolution = useWatch({ control, name: 'resolution' })
  const noRefundLeft = remaining <= 0

  const labels: Record<DisputeResolution, string> = {
    REFUND_FULL: `Full refund (${formatINR(remaining)})`,
    REFUND_PARTIAL: 'Partial refund',
    NO_REFUND: 'No refund',
    WARNING: 'Warning only',
  }

  async function submit(values: Values) {
    setFormError(null)
    onBusyChange(true)
    try {
      await resolveDispute(dispute.id, {
        resolution: values.resolution,
        ...(values.resolution === 'REFUND_PARTIAL' && { amount: Number(values.amount) }),
        notes: values.notes,
      })
      toast.success('Report resolved')
      await invalidateAdminDisputes(queryClient)
      onClose()
    } catch (error) {
      setFormError(errorMessage(error))
      if (toProblem(error).status === 409) await invalidateAdminDisputes(queryClient)
    } finally {
      onBusyChange(false)
    }
  }

  return (
    <form onSubmit={handleSubmit(submit)} noValidate className="space-y-4">
      <FormError message={formError} />
      {dispute.earningStatus === 'PAID' && dispute.earningNet != null && (
        <p className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-950/50 dark:text-amber-300">
          {`The owner has already been paid ${formatINR(dispute.earningNet)} for this booking; a refund now is a platform cost.`}
        </p>
      )}
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium text-slate-700 dark:text-slate-300">Resolution</legend>
        {RESOLUTIONS.map((r) => (
          <label key={r} className="flex items-center gap-2 text-sm">
            <input
              type="radio"
              value={r}
              disabled={noRefundLeft && (r === 'REFUND_FULL' || r === 'REFUND_PARTIAL')}
              className="h-4 w-4 border-slate-300"
              {...register('resolution')}
            />
            {labels[r]}
          </label>
        ))}
        {errors.resolution && <p className="text-sm text-red-600 dark:text-red-400">{errors.resolution.message}</p>}
      </fieldset>
      {resolution === 'REFUND_PARTIAL' && (
        <TextField
          label="Refund amount (₹)"
          inputMode="decimal"
          autoComplete="off"
          hint={`Up to ${formatINR(remaining)} can still be refunded`}
          error={errors.amount?.message}
          {...register('amount')}
        />
      )}
      <TextArea label="Notes" rows={4} hint="Kept in the admin record; not shown to the driver or owner." error={errors.notes?.message} {...register('notes')} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" disabled={isSubmitting} onClick={onClose}>Cancel</Button>
        <Button type="submit" loading={isSubmitting}>Resolve report</Button>
      </div>
    </form>
  )
}

function Content({ dispute }: { dispute: Dispute }) {
  const queryClient = useQueryClient()
  const [reviewing, setReviewing] = useState(false)
  const [resolving, setResolving] = useState(false)
  const [busy, setBusy] = useState(false)

  async function startReview() {
    setReviewing(true)
    try {
      await reviewDispute(dispute.id)
      toast.success('Report is under review')
    } catch (error) {
      toast.error(errorMessage(error))
    } finally {
      await invalidateAdminDisputes(queryClient)
      setReviewing(false)
    }
  }

  const open = dispute.status === 'OPEN'
  const active = dispute.status !== 'RESOLVED'
  return (
    <>
      <DisputeView
        dispute={dispute}
        title={`Report on ${dispute.bookingCode}`}
        bookingHref={`/admin/bookings/${dispute.bookingId}`}
        actions={
          active && (
            <>
              {open && <Button type="button" variant="secondary" loading={reviewing} onClick={() => void startReview()}>Start review</Button>}
              <Button type="button" onClick={() => setResolving(true)}>Resolve</Button>
            </>
          )
        }
      />
      <Dialog open={resolving} title="Resolve this report" onClose={() => setResolving(false)} busy={busy}>
        <ResolveForm dispute={dispute} onClose={() => setResolving(false)} onBusyChange={setBusy} />
      </Dialog>
    </>
  )
}

export function AdminDisputeDetailPage() {
  usePageTitle('Admin · Dispute details')
  const { id } = useParams()
  const query = useAdminDispute(parseId(id))
  return (
    <div className="space-y-4">
      <Link to="/admin/disputes" className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-400">← Disputes</Link>
      <DisputePageShell id={id} query={query}>{(dispute) => <Content dispute={dispute} />}</DisputePageShell>
    </div>
  )
}
