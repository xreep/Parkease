import clsx from 'clsx'
import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { ReasonDialog } from '../../components/ui/Dialog'
import { Pagination } from '../../components/ui/Pagination'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { panelId, tabId } from '../../components/ui/tabIds'
import { ViewTabs } from '../../components/ui/ViewTabs'
import {
  approveBooking,
  invalidateBookingQueries,
  invalidateOwnerBookings,
  ownerCancelBooking,
  rejectBooking,
  useOwnerBookings,
  type OwnerBookingDto,
  type OwnerBookingView,
} from '../../lib/bookings'
import { errorMessage } from '../../lib/errors'
import { VEHICLE_TYPE_LABELS, formatDateTime, formatINR } from '../../lib/format'
import { formatWindow } from '../../lib/time'
import { useNow } from '../../lib/useNow'
import { usePageTitle } from '../../lib/usePageTitle'

const TABS: { value: OwnerBookingView; label: string }[] = [
  { value: 'requests', label: 'Requests' },
  { value: 'upcoming', label: 'Upcoming' },
  { value: 'past', label: 'Past' },
]

const EMPTY: Record<OwnerBookingView, string> = {
  requests: 'No booking requests right now.',
  upcoming: 'No upcoming bookings.',
  past: 'No past bookings yet.',
}

const URGENT_MS = 30 * 60_000

function RespondBy({ deadline }: { deadline: string }) {
  const now = useNow(true, 30_000)
  const urgent = new Date(deadline).getTime() - now < URGENT_MS
  return (
    <p className={clsx('text-sm font-medium', urgent ? 'text-red-600 dark:text-red-400' : 'text-slate-600 dark:text-slate-400')}>
      {`Respond by ${formatDateTime(deadline)}`}
    </p>
  )
}

function RequestActions({ booking, onChanged }: { booking: OwnerBookingDto; onChanged: () => Promise<unknown> }) {
  const [approving, setApproving] = useState(false)
  const [declining, setDeclining] = useState(false)

  async function approve() {
    setApproving(true)
    try {
      await approveBooking(booking.id)
      toast.success('Booking approved')
    } catch (error) {
      toast.error(errorMessage(error))
    } finally {
      // Refetch either way: a failure usually means the request was already decided or expired.
      await onChanged()
      setApproving(false)
    }
  }

  async function decline(reason: string) {
    try {
      await rejectBooking(booking.id, reason)
    } catch (error) {
      // As with approve, a failure usually means the request was already decided or expired:
      // refresh the list, and let the dialog show the reason.
      await onChanged()
      throw error
    }
    toast.success('Booking declined — the driver will be refunded')
    setDeclining(false)
    await onChanged()
  }

  return (
    <>
      <div className="flex flex-wrap gap-2">
        <Button type="button" className="px-3 py-1.5" loading={approving} onClick={() => void approve()}>Approve</Button>
        <Button type="button" variant="secondary" className="px-3 py-1.5" disabled={approving} onClick={() => setDeclining(true)}>
          Decline
        </Button>
      </div>
      <ReasonDialog
        open={declining}
        title="Decline this booking?"
        summary={<BookingSummary booking={booking} />}
        confirmLabel="Decline"
        onConfirm={decline}
        onClose={() => setDeclining(false)}
      />
    </>
  )
}

function UpcomingActions({ booking, onChanged }: { booking: OwnerBookingDto; onChanged: () => Promise<unknown> }) {
  const [cancelling, setCancelling] = useState(false)

  async function cancel(reason: string) {
    try {
      await ownerCancelBooking(booking.id, reason)
    } catch (error) {
      // Usually the booking already started or was cancelled: the refresh can unmount this card (and the dialog
      // with it), so the message also goes out as a toast; the dialog shows it too while it is still open.
      toast.error(errorMessage(error))
      await onChanged()
      throw error
    }
    toast.success('Booking cancelled — the driver will be refunded')
    setCancelling(false)
    await onChanged()
  }

  return (
    <>
      <Button type="button" variant="secondary" className="px-3 py-1.5" onClick={() => setCancelling(true)}>
        Cancel booking
      </Button>
      <ReasonDialog
        open={cancelling}
        title="Cancel this booking?"
        summary={<BookingSummary booking={booking} />}
        confirmLabel="Cancel booking"
        helper="The driver will be refunded in full."
        maxLength={300}
        onConfirm={cancel}
        onClose={() => setCancelling(false)}
      />
    </>
  )
}

/** Which booking a dialog is about: its code, the driver and when. */
function BookingSummary({ booking }: { booking: OwnerBookingDto }) {
  return (
    <div className="space-y-0.5 rounded-lg bg-slate-50 p-3 text-sm dark:bg-slate-800/60">
      <p className="font-mono font-semibold">{booking.bookingCode}</p>
      <p>{`${booking.driverFirstName} · ${booking.listingTitle}`}</p>
      <p className="text-slate-600 dark:text-slate-400">{formatWindow(booking.startTime, booking.endTime)}</p>
    </div>
  )
}

/** What the owner earns from the booking: the net of their earning (zero once it was refunded away), else their share. */
function earningsLine(booking: OwnerBookingDto): string {
  const refundedAway = booking.status === 'CANCELLED' || booking.status === 'REJECTED'
  const net = booking.ownerNet ?? (refundedAway ? 0 : booking.baseAmount)
  return net > 0 ? `You earn ${formatINR(net)}` : 'No earnings — refunded'
}

function BookingCard({ booking, view, onChanged }: { booking: OwnerBookingDto; view: OwnerBookingView; onChanged: () => Promise<unknown> }) {
  const isRequest = view === 'requests' && booking.status === 'AWAITING_APPROVAL'
  const now = useNow(view === 'upcoming', 30_000)
  const canCancel = view === 'upcoming' && booking.status === 'CONFIRMED' && new Date(booking.startTime).getTime() > now
  return (
    <article
      aria-label={booking.bookingCode}
      className="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:flex-row sm:items-start sm:justify-between"
    >
      <div className="min-w-0 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="font-mono text-sm font-semibold">{booking.bookingCode}</p>
          <StatusBadge kind="booking" status={booking.status} />
        </div>
        <p className="break-words font-semibold">{booking.listingTitle}</p>
        <p className="text-sm text-slate-600 dark:text-slate-400">{`${formatWindow(booking.startTime, booking.endTime)} · Slot ${booking.slotLabel}`}</p>
        <p className="text-sm text-slate-600 dark:text-slate-400">
          {`${booking.driverFirstName} · ${VEHICLE_TYPE_LABELS[booking.vehicleType]} · `}
          <span className="font-mono">{booking.plateNumber}</span>
        </p>
        {isRequest && booking.approvalDeadline && <RespondBy deadline={booking.approvalDeadline} />}
      </div>
      <div className="flex flex-col gap-3 sm:items-end">
        <p className="text-lg font-bold">{earningsLine(booking)}</p>
        {isRequest && <RequestActions booking={booking} onChanged={onChanged} />}
        {canCancel && <UpcomingActions booking={booking} onChanged={onChanged} />}
      </div>
    </article>
  )
}

export function OwnerBookingsPage() {
  usePageTitle('Owner bookings')
  const queryClient = useQueryClient()
  const [view, setView] = useState<OwnerBookingView>('requests')
  const [page, setPage] = useState(0)
  const { data, error, isPending, isPlaceholderData } = useOwnerBookings(view, page)
  // The decision also changes the driver's booking and the availability behind quotes and search.
  const refresh = () => Promise.all([invalidateOwnerBookings(queryClient), invalidateBookingQueries(queryClient)])

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Bookings</h2>
      <ViewTabs
        idPrefix="owner-bookings"
        label="Booking views"
        items={TABS}
        value={view}
        onChange={(next) => {
          setView(next)
          setPage(0)
        }}
      />

      <div role="tabpanel" id={panelId('owner-bookings', view)} aria-labelledby={tabId('owner-bookings', view)} className="space-y-4">
        {isPending ? (
          <div className="flex justify-center py-12">
            <Spinner className="h-8 w-8 text-brand-600" />
          </div>
        ) : error ? (
          <FormError message={errorMessage(error)} />
        ) : data.content.length === 0 && page === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 p-10 text-center dark:border-slate-700">
            <p className="text-slate-600 dark:text-slate-400">{EMPTY[view]}</p>
          </div>
        ) : (
          <>
            <div className={clsx('space-y-3 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
              {data.content.map((b) => (
                <BookingCard key={b.id} booking={b} view={view} onChanged={refresh} />
              ))}
            </div>
            <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
          </>
        )}
      </div>
    </div>
  )
}
