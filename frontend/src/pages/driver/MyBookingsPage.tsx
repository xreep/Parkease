import clsx from 'clsx'
import { Link, Navigate, useSearchParams } from 'react-router-dom'
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

type TabView = Exclude<BookingView, 'all'>

const TABS: { value: TabView; label: string }[] = [
  { value: 'upcoming', label: 'Upcoming' },
  { value: 'active', label: 'Active' },
  { value: 'past', label: 'Past' },
  { value: 'cancelled', label: 'Cancelled' },
]

const EMPTY: Record<TabView, string> = {
  upcoming: 'No upcoming bookings.',
  active: 'No active bookings right now.',
  past: 'No past bookings yet.',
  cancelled: 'No cancelled bookings.',
}

const DEFAULT_VIEW: TabView = 'upcoming'
const asView = (raw: string | null): TabView => TABS.find((t) => t.value === raw)?.value ?? DEFAULT_VIEW
/** A zero-based page number from the URL; anything that isn't a whole number from 0 up is the first page. */
const asPage = (raw: string | null): number => (raw !== null && /^\d+$/.test(raw) ? Number(raw) : 0)

/** The query string for a tab and page: the defaults (Upcoming, first page) are left out. */
function search(view: TabView, page: number): Record<string, string> {
  return { ...(view !== DEFAULT_VIEW && { view }), ...(page > 0 && { page: String(page) }) }
}

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
  // The tab and the page live in the URL (`?view=past&page=2`), so a refresh, a shared link and the browser's Back and
  // Forward buttons all restore the same list.
  const [params, setParams] = useSearchParams()
  const view = asView(params.get('view'))
  const page = asPage(params.get('page'))
  const { data, error, isPending, isPlaceholderData } = useBookings(view, page)
  const lastPage = data ? Math.max(data.totalPages - 1, 0) : 0

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Your bookings</h2>
      <ViewTabs
        idPrefix="my-bookings"
        label="Booking views"
        items={TABS}
        value={view}
        // Clicks add a history entry; the arrow keys, which move a tab at a time, replace it.
        onChange={(next, via) => setParams(search(next, 0), { replace: via === 'keyboard' })}
      />

      <div role="tabpanel" id={panelId('my-bookings', view)} aria-labelledby={tabId('my-bookings', view)} className="space-y-4">
        {isPending ? (
          <div className="flex justify-center py-12">
            <Spinner className="h-8 w-8 text-brand-600" />
          </div>
        ) : error ? (
          <FormError message={errorMessage(error)} />
        ) : data.content.length === 0 && page > 0 && !isPlaceholderData ? (
          // Past the last page (a stale link, bookings that have since moved): go to the last page that exists.
          <Navigate to={{ search: new URLSearchParams(search(view, Math.min(page - 1, lastPage))).toString() }} replace />
        ) : data.content.length === 0 ? (
          <div className="space-y-4 rounded-2xl border border-dashed border-slate-300 p-10 text-center dark:border-slate-700">
            <p className="text-slate-600 dark:text-slate-400">{EMPTY[view]}</p>
            {view === 'upcoming' && <Link to="/search" className={primaryLink}>Find parking</Link>}
          </div>
        ) : (
          <>
            <div className={clsx('space-y-3 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
              {data.content.map((b) => (
                <BookingCard key={b.id} booking={b} />
              ))}
            </div>
            <Pagination page={page} totalPages={data.totalPages} onChange={(next) => setParams(search(view, next))} />
          </>
        )}
      </div>
    </div>
  )
}
