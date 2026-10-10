import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { Download } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { FormError } from '../../components/AuthCard'
import { Empty, Loading } from '../../components/admin/common'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { TextField } from '../../components/ui/TextField'
import { invalidateAdminActivity } from '../../lib/admin'
import {
  downloadPayoutsCsv,
  mapAdminError,
  markPaid,
  useAdminPayouts,
  usePayoutEarnings,
  type PayoutOwner,
} from '../../lib/adminManage'
import { errorMessage } from '../../lib/errors'
import { formatINR, plural } from '../../lib/format'
import { useCsvExport } from '../../lib/useCsvExport'
import { formatWindow } from '../../lib/time'

const referenceSchema = z.object({
  reference: z.string().trim().min(3, 'Enter between 3 and 100 characters').max(100, 'Enter between 3 and 100 characters'),
})
type ReferenceValues = z.infer<typeof referenceSchema>

const payoutLine = (o: PayoutOwner) =>
  o.payoutMethod && o.payoutMasked ? `${o.payoutMethod === 'UPI' ? 'UPI' : 'Bank'} · ${o.payoutMasked}` : 'No payout details yet'

function ReferenceForm({
  owner,
  count,
  total,
  onConfirm,
  onClose,
  onBusyChange,
}: {
  owner: PayoutOwner
  count: number
  total: number
  onConfirm: (reference: string) => Promise<void>
  onClose: () => void
  onBusyChange: (busy: boolean) => void
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<ReferenceValues>({
    resolver: zodResolver(referenceSchema),
    defaultValues: { reference: '' },
  })

  async function submit({ reference }: ReferenceValues) {
    setFormError(null)
    onBusyChange(true)
    try {
      await onConfirm(reference)
    } catch (error) {
      setFormError(errorMessage(error))
    } finally {
      onBusyChange(false)
    }
  }

  return (
    <form onSubmit={handleSubmit(submit)} noValidate className="space-y-4">
      <div className="space-y-1 text-sm">
        <p>Record a payout you’ve made outside ParkEase (using the owner’s payout details on file). This doesn’t move money.</p>
        <p>{`Pay ${owner.ownerName} ${formatINR(total)} for ${plural(count, 'earning', 'earnings')}.`}</p>
        <p className="text-slate-500">{payoutLine(owner)}</p>
      </div>
      <FormError message={formError} />
      <TextField label="Payment reference" hint="The UPI or bank transaction id" error={errors.reference?.message} {...register('reference')} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" disabled={isSubmitting} onClick={onClose}>Cancel</Button>
        <Button type="submit" loading={isSubmitting} disabled={count === 0}>Mark as paid</Button>
      </div>
    </form>
  )
}

function OwnerCard({ owner }: { owner: PayoutOwner }) {
  const queryClient = useQueryClient()
  const [expanded, setExpanded] = useState(false)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const { data, error, isPending } = usePayoutEarnings(expanded ? owner.ownerId : undefined)

  // Only earnings still listed count, so a refetch that drops some never leaves a stale id selected.
  const chosen = (data ?? []).filter((e) => !e.disputed && selected.has(e.id))
  const total = Math.round(chosen.reduce((sum, e) => sum + e.net, 0) * 100) / 100
  const payable = (data ?? []).filter((e) => !e.disputed)
  const allSelected = payable.length > 0 && chosen.length === payable.length

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['admin', 'payouts'] }),
      queryClient.invalidateQueries({ queryKey: ['admin', 'payout-earnings'] }),
      invalidateAdminActivity(queryClient),
    ]).then(() => undefined)

  async function confirm(reference: string) {
    try {
      const result = await markPaid({ ownerId: owner.ownerId, earningIds: chosen.map((e) => e.id), reference })
      toast.success(`Marked ${plural(result.paidCount, 'earning', 'earnings')} as paid (${formatINR(result.paidAmount)})`)
      setConfirming(false)
      setSelected(new Set())
      await refresh()
    } catch (e) {
      // The earnings changed under us (paid elsewhere, reversed): show what is true now.
      await refresh()
      throw mapAdminError(e)
    }
  }

  const toggle = (id: number) =>
    setSelected((current) => {
      const next = new Set(current)
      if (!next.delete(id)) next.add(id)
      return next
    })

  return (
    <article aria-label={owner.ownerName} className="space-y-3 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-2">
        <div className="min-w-0 space-y-0.5">
          <h3 className="font-semibold">{owner.ownerName}</h3>
          <p className="break-all text-sm text-slate-600 dark:text-slate-400">{owner.ownerEmail}</p>
          <p className="break-all text-sm text-slate-700 dark:text-slate-300">{payoutLine(owner)}</p>
        </div>
        <div className="text-sm sm:text-right">
          <p className="text-xl font-bold">{formatINR(owner.pendingAmount)}</p>
          <p className="text-slate-500">{plural(owner.earningsCount, 'earning', 'earnings')}</p>
          {owner.disputedAmount > 0 && (
            <p className="font-medium text-amber-700 dark:text-amber-400">{`${formatINR(owner.disputedAmount)} held for open disputes`}</p>
          )}
        </div>
      </div>
      <Button type="button" variant="secondary" className="px-3 py-1.5" aria-expanded={expanded} onClick={() => setExpanded((v) => !v)}>
        {expanded ? 'Hide earnings' : 'Show earnings'}
      </Button>

      {expanded && (
        <section aria-label={`Pending earnings for ${owner.ownerName}`} className="space-y-3 border-t border-slate-200 pt-3 dark:border-slate-800">
          {isPending ? (
            <Loading />
          ) : error ? (
            <FormError message={errorMessage(error)} />
          ) : data.length === 0 ? (
            <Empty>Nothing is pending for this owner any more.</Empty>
          ) : (
            <>
              <label className="flex items-center gap-2 text-sm font-medium">
                <input
                  type="checkbox"
                  className="h-4 w-4 rounded border-slate-300"
                  checked={allSelected}
                  onChange={() => setSelected(allSelected ? new Set() : new Set(payable.map((e) => e.id)))}
                />
                Select all
              </label>
              <ul className="divide-y divide-slate-200 dark:divide-slate-800">
                {data.map((e) => (
                  <li key={e.id} className="flex items-start gap-3 py-2 text-sm">
                    <input
                      type="checkbox"
                      aria-label={e.bookingCode}
                      aria-describedby={e.disputed ? `held-${e.id}` : undefined}
                      disabled={e.disputed}
                      className="mt-1 h-4 w-4 rounded border-slate-300 disabled:opacity-50"
                      checked={selected.has(e.id)}
                      onChange={() => toggle(e.id)}
                    />
                    <div className="min-w-0 flex-1">
                      <p className="font-mono font-semibold">{e.bookingCode}</p>
                      <p className="break-words">{e.listingTitle}</p>
                      <p className="text-xs text-slate-500">{formatWindow(e.startTime, e.endTime)}</p>
                      {e.disputed && <p id={`held-${e.id}`} className="text-xs font-medium text-amber-700 dark:text-amber-400">Held for an open dispute</p>}
                    </div>
                    <p className="font-semibold tabular-nums">{formatINR(e.net)}</p>
                  </li>
                ))}
              </ul>
              <Button type="button" disabled={chosen.length === 0} onClick={() => setConfirming(true)}>
                {chosen.length > 0 ? `Mark selected as paid (${chosen.length} · ${formatINR(total)})` : 'Mark selected as paid'}
              </Button>
            </>
          )}
        </section>
      )}

      <Dialog open={confirming} title="Mark payout as paid" onClose={() => setConfirming(false)} busy={busy}>
        {confirming && (
          <ReferenceForm owner={owner} count={chosen.length} total={total} onConfirm={confirm} onClose={() => setConfirming(false)} onBusyChange={setBusy} />
        )}
      </Dialog>
    </article>
  )
}

export function AdminPayoutsPage() {
  const { data, error, isPending } = useAdminPayouts()
  const csv = useCsvExport(downloadPayoutsCsv, 'The export was cut at the row limit.')

  const total = Math.round((data ?? []).reduce((sum, o) => sum + o.pendingAmount, 0) * 100) / 100

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-xl font-semibold">Payouts</h2>
        <Button type="button" variant="secondary" loading={csv.exporting} onClick={() => void csv.run()}>
          {!csv.exporting && <Download aria-hidden className="h-4 w-4" />}
          Download CSV
        </Button>
      </div>
      <FormError message={csv.error} />

      {isPending ? (
        <Loading />
      ) : error ? (
        <FormError message={errorMessage(error)} />
      ) : data.length === 0 ? (
        <Empty>No payouts are pending.</Empty>
      ) : (
        <>
          <p className="text-sm font-medium text-slate-600 dark:text-slate-400">{`Total pending: ${formatINR(total)}`}</p>
          <div className="space-y-3">
            {data.map((owner) => (
              <OwnerCard key={owner.ownerId} owner={owner} />
            ))}
          </div>
        </>
      )}
    </div>
  )
}
