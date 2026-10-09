import clsx from 'clsx'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { FormError } from '../../components/AuthCard'
import { Pagination } from '../../components/ui/Pagination'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { panelId, tabId } from '../../components/ui/tabIds'
import { ViewTabs } from '../../components/ui/ViewTabs'
import { useBookings, type BookingSummaryDto, type BookingView } from '../../lib/bookings'
import { errorMessage } from '../../lib/errors'
import { VEHICLE_TYPE_LABELS, formatINR } from '../../lib/format'
import { formatWindow } from '../../lib/time'

const primaryLink =
  'inline-flex items-center justify-center rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700'
const smallLink =
  'inline-flex items-center justify-center rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-semibold text-slate-800 transition hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800'

const TABS: { value: BookingView; label: string }[] = [
  { value: 'upcoming', label: 'Upcoming' },
  { value: 'past', label: 'Past' },
]

function BookingCard({ booking }: { booking: BookingSummaryDto }) {
  return (
    <article
      aria-label={booking.bookingCode}
      className="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="min-w-0 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="font-mono text-sm font-semibold">{booking.bookingCode}</p>
          <StatusBadge kind="booking" status={booking.status} />
        </div>
        <p className="break-words font-semibold">{booking.listingTitle}</p>
        <p className="text-sm text-slate-600 dark:text-slate-400">{formatWindow(booking.startTime, booking.endTime)}</p>
        {booking.status === 'ACTIVE' && (
          <p className="text-sm font-medium text-sky-700 dark:text-sky-400">Active now — show your QR code at the entrance.</p>
        )}
        <p className="text-sm text-slate-600 dark:text-slate-400">
          <span className="font-mono">{booking.plateNumber}</span>
          {` · ${VEHICLE_TYPE_LABELS[booking.vehicleType]}`}
        </p>
      </div>
      <div className="flex items-center justify-between gap-4 sm:flex-col sm:items-end">
        <p className="text-lg font-bold">{formatINR(booking.totalAmount)}</p>
        <Link to={`/driver/bookings/${booking.id}`} className={smallLink}>View</Link>
      </div>
    </article>
  )
}

export function MyBookingsPage() {
  const [view, setView] = useState<BookingView>('upcoming')
  const [page, setPage] = useState(0)
  const { data, error, isPending, isPlaceholderData } = useBookings(view, page)

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Your bookings</h2>
      <ViewTabs
        idPrefix="my-bookings"
        label="Booking views"
        items={TABS}
        value={view}
        onChange={(next) => {
          setView(next)
          setPage(0)
        }}
      />

      <div role="tabpanel" id={panelId('my-bookings', view)} aria-labelledby={tabId('my-bookings', view)} className="space-y-4">
        {isPending ? (
          <div className="flex justify-center py-12">
            <Spinner className="h-8 w-8 text-brand-600" />
          </div>
        ) : error ? (
          <FormError message={errorMessage(error)} />
        ) : data.content.length === 0 && page === 0 ? (
          <div className="space-y-4 rounded-2xl border border-dashed border-slate-300 p-10 text-center dark:border-slate-700">
            <p className="text-slate-600 dark:text-slate-400">
              {view === 'upcoming' ? 'No upcoming bookings.' : 'No past bookings yet.'}
            </p>
            {view === 'upcoming' && <Link to="/search" className={primaryLink}>Find parking</Link>}
          </div>
        ) : (
          <>
            <div className={clsx('space-y-3 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
              {data.content.map((b) => (
                <BookingCard key={b.id} booking={b} />
              ))}
            </div>
            <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
          </>
        )}
      </div>
    </div>
  )
}
