import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { ReasonDialog } from '../../components/ui/Dialog'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { invalidateAdminActivity, getOwnerDocumentUrl, rejectOwner, useAdminOwners, verifyOwner, type AdminOwner } from '../../lib/admin'
import { errorMessage } from '../../lib/errors'
import { DOCUMENT_TYPE_LABELS, formatDateTime } from '../../lib/format'
import { openInNewTab } from '../../lib/openDocument'
import type { VerificationStatus } from '../../lib/owner'
import { stepBackIfEmpty } from '../../lib/paging'
import { usePageTitle } from '../../lib/usePageTitle'

const FILTERS: { value: VerificationStatus; label: string }[] = [
  { value: 'PENDING', label: 'Pending' },
  { value: 'VERIFIED', label: 'Verified' },
  { value: 'REJECTED', label: 'Rejected' },
]

function OwnerRow({ owner, onReject, onChanged }: { owner: AdminOwner; onReject: () => void; onChanged: () => Promise<void> }) {
  const [busy, setBusy] = useState<'document' | 'verify' | null>(null)

  async function viewDocument() {
    setBusy('document')
    try {
      await openInNewTab(() => getOwnerDocumentUrl(owner.userId))
    } catch (error) {
      toast.error(errorMessage(error))
    } finally {
      setBusy(null)
    }
  }

  async function verify() {
    setBusy('verify')
    try {
      await verifyOwner(owner.userId)
      toast.success(`${owner.name} verified`)
    } catch (error) {
      toast.error(errorMessage(error))
    } finally {
      await onChanged()
      setBusy(null)
    }
  }

  return (
    <article aria-label={owner.name} className="space-y-3 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-2">
        <div className="min-w-0 space-y-0.5">
          <h3 className="font-semibold">{owner.name}</h3>
          <p className="break-all text-sm text-slate-600 dark:text-slate-400">{owner.email}</p>
          {owner.phone && <p className="text-sm text-slate-600 dark:text-slate-400">{owner.phone}</p>}
        </div>
        <div className="space-y-0.5 text-sm text-slate-700 dark:text-slate-300 sm:text-right">
          <p>{owner.documentType ? DOCUMENT_TYPE_LABELS[owner.documentType] : 'No document'}</p>
          {owner.documentSubmittedAt && <p className="text-slate-500 dark:text-slate-400">{formatDateTime(owner.documentSubmittedAt)}</p>}
          <p>{owner.hasPayoutDetails ? 'Payout details added' : 'No payout details'}</p>
          <p>{`${owner.listingCount} ${owner.listingCount === 1 ? 'listing' : 'listings'}`}</p>
        </div>
      </div>
      {owner.verificationStatus === 'REJECTED' && owner.rejectionReason && (
        <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950/50 dark:text-red-300">{owner.rejectionReason}</p>
      )}
      <div className="flex flex-wrap gap-2">
        {owner.hasDocument && (
          <Button type="button" variant="secondary" className="px-3 py-1.5" loading={busy === 'document'} disabled={busy !== null} onClick={() => void viewDocument()}>
            View document
          </Button>
        )}
        {owner.verificationStatus === 'PENDING' && (
          <>
            <Button type="button" className="px-3 py-1.5" loading={busy === 'verify'} disabled={busy !== null} onClick={() => void verify()}>Verify</Button>
            <Button type="button" variant="ghost" className="px-3 py-1.5 text-red-600 dark:text-red-400" disabled={busy !== null} onClick={onReject}>Reject</Button>
          </>
        )}
      </div>
    </article>
  )
}

export function OwnerQueuePage() {
  usePageTitle('Admin · Owner queue')
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<VerificationStatus>('PENDING')
  const [page, setPage] = useState(0)
  const [rejecting, setRejecting] = useState<AdminOwner | null>(null)
  const { data, error, isPending } = useAdminOwners(status, page)

  stepBackIfEmpty(page, setPage, data?.content)

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['admin', 'owners'] }),
      invalidateAdminActivity(queryClient),
    ]).then(() => undefined)

  async function confirmReject(reason: string) {
    if (!rejecting) return
    await rejectOwner(rejecting.userId, reason)
    toast.success(`${rejecting.name} rejected`)
    setRejecting(null)
    await refresh()
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h2 className="text-xl font-semibold">Owner verification</h2>
        <Select
          label="Show"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as VerificationStatus)
            setPage(0)
          }}
        >
          {FILTERS.map((f) => (
            <option key={f.value} value={f.value}>{f.label}</option>
          ))}
        </Select>
      </div>

      {isPending ? (
        <div className="flex justify-center py-12">
          <Spinner className="h-8 w-8 text-brand-600" />
        </div>
      ) : error ? (
        <FormError message={errorMessage(error)} />
      ) : data.content.length === 0 && page === 0 ? (
        <p className="rounded-2xl border border-dashed border-slate-300 p-10 text-center text-slate-600 dark:border-slate-700 dark:text-slate-400">
          No owners in this list.
        </p>
      ) : (
        <>
          <div className="space-y-3">
            {data.content.map((owner) => (
              <OwnerRow key={owner.userId} owner={owner} onReject={() => setRejecting(owner)} onChanged={refresh} />
            ))}
          </div>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}

      {rejecting && (
        <ReasonDialog
          open
          title={`Reject ${rejecting.name}?`}
          confirmLabel="Reject"
          onConfirm={confirmReject}
          onClose={() => setRejecting(null)}
        />
      )}
    </div>
  )
}
