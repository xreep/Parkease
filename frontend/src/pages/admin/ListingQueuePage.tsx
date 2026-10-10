import { useState } from 'react'
import { Link } from 'react-router-dom'
import { FormError } from '../../components/AuthCard'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { useAdminListings, type AdminListingSummary } from '../../lib/admin'
import { errorMessage } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import type { ListingStatus } from '../../lib/owner'
import { stepBackIfEmpty } from '../../lib/paging'
import { usePageTitle } from '../../lib/usePageTitle'

const FILTERS: { value: ListingStatus; label: string }[] = [
  { value: 'PENDING_REVIEW', label: 'Pending review' },
  { value: 'APPROVED', label: 'Live' },
  { value: 'REJECTED', label: 'Changes needed' },
  { value: 'PAUSED', label: 'Paused' },
  { value: 'SUSPENDED', label: 'Suspended' },
]

const reviewLink =
  'inline-flex items-center justify-center rounded-lg bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-800'

function ListingRow({ listing }: { listing: AdminListingSummary }) {
  return (
    <article aria-label={listing.title} className="flex flex-col gap-4 overflow-hidden rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:flex-row sm:items-center">
      {listing.coverPhotoUrl ? (
        <img src={listing.coverPhotoUrl} alt="" className="h-32 w-full rounded-xl object-cover sm:h-20 sm:w-32 sm:shrink-0" />
      ) : (
        <div aria-hidden className="flex h-32 w-full items-center justify-center rounded-xl bg-slate-100 text-3xl font-bold text-slate-300 dark:bg-slate-800 dark:text-slate-600 sm:h-20 sm:w-32 sm:shrink-0">P</div>
      )}
      <div className="min-w-0 flex-1 space-y-0.5">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="font-semibold">{listing.title}</h3>
          <StatusBadge kind="listing" status={listing.status} />
        </div>
        <p className="text-sm text-slate-500">{`${listing.cityName}, ${listing.stateName}`}</p>
        <p className="text-sm text-slate-700 dark:text-slate-300">
          <span>{listing.ownerName}</span>
          {' · '}
          <span className="break-all">{listing.ownerEmail}</span>
        </p>
        {listing.submittedAt && <p className="text-sm text-slate-500">{formatDateTime(listing.submittedAt)}</p>}
      </div>
      <Link to={`/admin/listings/${listing.id}`} className={reviewLink}>Review</Link>
    </article>
  )
}

export function ListingQueuePage() {
  usePageTitle('Admin · Listing queue')
  const [status, setStatus] = useState<ListingStatus>('PENDING_REVIEW')
  const [page, setPage] = useState(0)
  const { data, error, isPending } = useAdminListings(status, page)

  stepBackIfEmpty(page, setPage, data?.content)

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h2 className="text-xl font-semibold">Listing approvals</h2>
        <Select
          label="Show"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as ListingStatus)
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
          No listings in this list.
        </p>
      ) : (
        <>
          <div className="space-y-3">
            {data.content.map((listing) => (
              <ListingRow key={listing.id} listing={listing} />
            ))}
          </div>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  )
}
