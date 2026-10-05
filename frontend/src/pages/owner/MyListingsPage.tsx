import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { errorMessage } from '../../lib/errors'
import { formatINR } from '../../lib/format'
import { deleteListing, pauseListing, resumeListing, useMyListings, type ListingStatus, type ListingSummary } from '../../lib/owner'

const primaryLink =
  'inline-flex items-center justify-center rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700'
const smallLink =
  'inline-flex items-center justify-center rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-semibold text-slate-800 transition hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800'

/** Mirrors the server: a listing can be deleted unless it is live or suspended. */
const DELETABLE: ListingStatus[] = ['DRAFT', 'PENDING_REVIEW', 'REJECTED', 'PAUSED']

function DeleteDialog({ listing, onClose, onDeleted }: { listing: ListingSummary; onClose: () => void; onDeleted: () => void }) {
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  async function confirm() {
    setBusy(true)
    setFormError(null)
    try {
      await deleteListing(listing.id)
      toast.success('Listing deleted')
      onDeleted()
    } catch (error) {
      setFormError(errorMessage(error))
      setBusy(false)
    }
  }

  return (
    <Dialog open title={`Delete ${listing.title}? This can't be undone.`} onClose={onClose} busy={busy}>
      <div className="space-y-4">
        <FormError message={formError} />
        <p className="text-sm text-slate-600 dark:text-slate-400">The listing, its photos and its slots will be removed.</p>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
          <Button type="button" variant="danger" loading={busy} onClick={() => void confirm()}>Delete listing</Button>
        </div>
      </div>
    </Dialog>
  )
}

function ListingCard({ listing, onChanged, onDelete }: { listing: ListingSummary; onChanged: (id: number) => Promise<void>; onDelete: () => void }) {
  const [busy, setBusy] = useState(false)

  async function changeStatus(kind: 'pause' | 'resume') {
    setBusy(true)
    try {
      await (kind === 'pause' ? pauseListing : resumeListing)(listing.id)
      toast.success(kind === 'pause' ? 'Listing paused' : 'Listing resumed')
    } catch (error) {
      toast.error(errorMessage(error))
    } finally {
      await onChanged(listing.id)
      setBusy(false)
    }
  }

  return (
    <article aria-label={listing.title} className="flex flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
      {listing.coverPhotoUrl ? (
        <img src={listing.coverPhotoUrl} alt="" className="h-40 w-full object-cover" />
      ) : (
        <div aria-hidden className="flex h-40 w-full items-center justify-center bg-slate-100 text-4xl font-bold text-slate-300 dark:bg-slate-800 dark:text-slate-600">P</div>
      )}
      <div className="flex flex-1 flex-col gap-3 p-4">
        <div className="space-y-1">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="font-semibold">{listing.title}</h3>
            <StatusBadge kind="listing" status={listing.status} />
          </div>
          <p className="text-sm text-slate-500">{`${listing.cityName}, ${listing.stateName}`}</p>
          <p className="text-sm text-slate-700 dark:text-slate-300">
            <span>{listing.pricePerHour === null ? 'No price yet' : `${formatINR(listing.pricePerHour)}/hr`}</span>
            {' · '}
            <span>{`${listing.slotCount} ${listing.slotCount === 1 ? 'slot' : 'slots'}`}</span>
          </p>
          {listing.status === 'REJECTED' && listing.rejectionReason && (
            <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950/50 dark:text-red-300">{listing.rejectionReason}</p>
          )}
        </div>
        <div className="mt-auto flex flex-wrap gap-2">
          <Link to={`/owner/listings/${listing.id}/edit?step=1`} className={smallLink}>Edit</Link>
          <Link to={`/owner/listings/${listing.id}/blocks`} className={smallLink}>Blocked times</Link>
          {listing.status === 'APPROVED' && (
            <Button type="button" variant="secondary" className="px-3 py-1.5" loading={busy} onClick={() => void changeStatus('pause')}>Pause</Button>
          )}
          {listing.status === 'PAUSED' && (
            <Button type="button" variant="secondary" className="px-3 py-1.5" loading={busy} onClick={() => void changeStatus('resume')}>Resume</Button>
          )}
          {DELETABLE.includes(listing.status) && (
            <Button type="button" variant="ghost" className="px-3 py-1.5 text-red-600 dark:text-red-400" disabled={busy} onClick={onDelete}>Delete</Button>
          )}
        </div>
      </div>
    </article>
  )
}

export function MyListingsPage() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [deleting, setDeleting] = useState<ListingSummary | null>(null)
  const { data, error, isPending } = useMyListings(page)

  // The wizard reads ['owner','listing',id] with a long staleTime, so a status change must invalidate it too.
  const refresh = async (id: number) => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['owner', 'listings'] }),
      queryClient.invalidateQueries({ queryKey: ['owner', 'listing', id] }),
    ])
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-xl font-semibold">My listings</h2>
        <Link to="/owner/listings/new" className={primaryLink}>Add a listing</Link>
      </div>

      {isPending ? (
        <div className="flex justify-center py-12">
          <Spinner className="h-8 w-8 text-brand-600" />
        </div>
      ) : error ? (
        <FormError message={errorMessage(error)} />
      ) : data.content.length === 0 && page === 0 ? (
        <div className="space-y-4 rounded-2xl border border-dashed border-slate-300 p-10 text-center dark:border-slate-700">
          <p className="text-slate-600 dark:text-slate-400">You haven't added any parking yet.</p>
          <Link to="/owner/listings/new" className={primaryLink}>Add your first listing</Link>
        </div>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {data.content.map((listing) => (
              <ListingCard key={listing.id} listing={listing} onChanged={refresh} onDelete={() => setDeleting(listing)} />
            ))}
          </div>
          {data.totalPages > 1 && (
            <div className="flex items-center justify-between gap-3">
              <Button type="button" variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
              <p className="text-sm text-slate-500">{`Page ${page + 1} of ${data.totalPages}`}</p>
              <Button type="button" variant="secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</Button>
            </div>
          )}
        </>
      )}

      {deleting && (
        <DeleteDialog
          listing={deleting}
          onClose={() => setDeleting(null)}
          onDeleted={() => {
            const { id } = deleting
            setDeleting(null)
            if (data && data.content.length === 1 && page > 0) setPage(page - 1)
            queryClient.removeQueries({ queryKey: ['owner', 'listing', id] })
            void queryClient.invalidateQueries({ queryKey: ['owner', 'listings'] })
          }}
        />
      )}
    </div>
  )
}
