import clsx from 'clsx'
import { Check, Copy, Download, ExternalLink } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useLocation, useParams, useSearchParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { FormError } from '../../components/AuthCard'
import { BookingQr } from '../../components/booking/BookingQr'
import { CancelBookingDialog } from '../../components/booking/CancelBookingDialog'
import { RaiseDisputeDialog } from '../../components/disputes/RaiseDisputeDialog'
import { OwnerReply } from '../../components/reviews/ReviewCard'
import { ReviewForm } from '../../components/reviews/ReviewForm'
import { Stars } from '../../components/reviews/Stars'
import { Button } from '../../components/ui/Button'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import {
  BOOKING_STATUS_LABELS,
  downloadReceipt,
  useBooking,
  type BookingDetailDto,
  type BookingEventDto,
} from '../../lib/bookings'
import { DISPUTE_CATEGORY_LABELS } from '../../lib/disputes'
import { saveBlob } from '../../lib/download'
import { errorMessage } from '../../lib/errors'
import { VEHICLE_TYPE_LABELS, formatDateTime, formatINR } from '../../lib/format'
import { invalidateReviewQueries, type ReviewDto } from '../../lib/reviews'
import { useNow } from '../../lib/useNow'
import { durationLabel, formatWindow } from '../../lib/time'

const primaryLink =
  'inline-flex items-center justify-center rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700'

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
      <h3 className="text-base font-semibold">{title}</h3>
      <div className="mt-3 space-y-2 text-sm">{children}</div>
    </section>
  )
}

function Row({ label, children, className }: { label: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={clsx('flex items-baseline justify-between gap-4', className)}>
      <dt className="text-slate-600 dark:text-slate-400">{label}</dt>
      <dd className="min-w-0 break-words text-right font-medium">{children}</dd>
    </div>
  )
}

/**
 * A refund is recorded as an event that leaves the status where it was, with a note that starts with "Refund";
 * showing it under the status name would read as a second cancellation. Both must hold: a cancellation whose reason
 * happens to start with "Refund" is still a cancellation.
 */
function eventLabel(event: BookingEventDto): string {
  const isRefund = event.fromStatus === event.toStatus && (event.note ?? '').startsWith('Refund')
  return isRefund ? 'Refund' : BOOKING_STATUS_LABELS[event.toStatus]
}

function SuccessBanner({ booking }: { booking: BookingDetailDto }) {
  let message: string | null = null
  if (booking.status === 'CONFIRMED') {
    message = "You're all set! Show this QR code at the parking entrance."
  } else if (booking.status === 'AWAITING_APPROVAL') {
    const until = booking.approvalDeadline ? formatDateTime(booking.approvalDeadline) : 'the deadline'
    message = `Request sent. The owner has until ${until} to approve. You'll be refunded in full if they don't.`
  }
  if (!message) return null
  return (
    <p role="status" className="rounded-xl bg-emerald-50 px-4 py-3 text-sm font-medium text-emerald-800 dark:bg-emerald-950/50 dark:text-emerald-300">
      {message}
    </p>
  )
}

/** What the booking is doing now, whatever way the driver arrived at the page. */
function StatusNotice({ booking }: { booking: BookingDetailDto }) {
  let message: string | null = null
  let tone = 'bg-sky-50 text-sky-800 dark:bg-sky-950/50 dark:text-sky-300'
  if (booking.status === 'ACTIVE') {
    message = 'Your parking time is active — show your QR code at the entrance.'
  } else if (booking.status === 'COMPLETED') {
    message = 'Completed — thanks for parking with ParkEase.'
    tone = 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300'
  } else if (booking.status === 'CANCELLED') {
    tone = 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300'
    if (booking.cancelledBy === 'DRIVER') message = 'Cancelled by you'
    else if (booking.cancelledBy === 'OWNER') {
      message = booking.cancelReason ? `Cancelled by the owner: ${booking.cancelReason}` : 'Cancelled by the owner'
    }
  }
  if (!message) return null
  return (
    <p role="status" className={clsx('rounded-xl px-4 py-3 text-sm font-medium', tone)}>
      {message}
    </p>
  )
}

function CopyCodeButton({ code }: { code: string }) {
  const [copied, setCopied] = useState(false)
  async function copy() {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      toast.error('Could not copy the code')
    }
  }
  return (
    <>
      <Button type="button" variant="secondary" className="px-3 py-1.5" onClick={() => void copy()}>
        {copied ? <Check aria-hidden className="h-4 w-4" /> : <Copy aria-hidden className="h-4 w-4" />}
        {copied ? 'Copied' : 'Copy code'}
      </Button>
      <span role="status" aria-live="polite" className="sr-only">{copied ? 'Booking code copied' : ''}</span>
    </>
  )
}

function ReceiptButton({ booking }: { booking: BookingDetailDto }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function download() {
    setBusy(true)
    setError(null)
    try {
      const blob = await downloadReceipt(booking.id)
      saveBlob(blob, `ParkEase-${booking.invoiceNumber}.pdf`)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-2">
      <FormError message={error} />
      <Button type="button" variant="secondary" loading={busy} onClick={() => void download()}>
        {!busy && <Download aria-hidden className="h-4 w-4" />}
        Download receipt
      </Button>
    </div>
  )
}

/**
 * Rate a completed booking, or read the review that was left. `id="review"` is the target of `#review` links (the
 * "review your parking" notification), which scroll here.
 */
function ReviewPanel({ booking }: { booking: BookingDetailDto }) {
  const queryClient = useQueryClient()
  const { hash } = useLocation()
  // What was just posted shows at once; the refetch that follows then agrees with it.
  const [posted, setPosted] = useState<ReviewDto | null>(null)
  const review = posted ?? booking.review
  const show = review !== null || booking.reviewable

  useEffect(() => {
    if (hash === '#review' && show) document.getElementById('review')?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  }, [hash, show])

  if (!show) return null
  const title = review ? 'Your review' : 'Rate your parking'
  return (
    <section
      id="review"
      aria-labelledby="review-heading"
      className="scroll-mt-24 rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900"
    >
      <h3 id="review-heading" className="text-base font-semibold">{title}</h3>
      <div className="mt-3 text-sm">
        {review ? (
          <div className="space-y-2">
            <Stars value={review.rating} />
            {review.comment ? (
              <p className="whitespace-pre-line break-words">{review.comment}</p>
            ) : (
              <p className="text-slate-500">No comment</p>
            )}
            <OwnerReply review={review} />
          </div>
        ) : (
          <ReviewForm
            bookingId={booking.id}
            onPosted={(r) => {
              setPosted(r)
              void invalidateReviewQueries(queryClient)
            }}
            onStale={() => void invalidateReviewQueries(queryClient)}
          />
        )}
      </div>
    </section>
  )
}

/** The problems reported for this booking and where each one stands. */
function DisputesPanel({ booking }: { booking: BookingDetailDto }) {
  if (booking.disputes.length === 0) return null
  return (
    <section
      aria-labelledby="problems-heading"
      className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900"
    >
      <h3 id="problems-heading" className="text-base font-semibold">Problems reported</h3>
      <ul className="mt-3 space-y-2 text-sm">
        {booking.disputes.map((d) => (
          <li key={d.id} className="flex flex-wrap items-center justify-between gap-2">
            <Link to={`/driver/disputes/${d.id}`} className="font-medium text-brand-700 hover:underline dark:text-brand-400">
              {DISPUTE_CATEGORY_LABELS[d.category]}
            </Link>
            <span className="flex items-center gap-2">
              <StatusBadge kind="dispute" status={d.status} />
              <span className="text-xs text-slate-500">{formatDateTime(d.createdAt)}</span>
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}

function BookingContent({ booking, isNew }: { booking: BookingDetailDto; isNew: boolean }) {
  const minutes = (new Date(booking.endTime).getTime() - new Date(booking.startTime).getTime()) / 60_000
  const showQr = booking.status === 'CONFIRMED' || booking.status === 'ACTIVE'
  const [cancelling, setCancelling] = useState(false)
  const [reporting, setReporting] = useState(false)
  const now = useNow(booking.status === 'PENDING_PAYMENT' || booking.status === 'CONFIRMED', 5_000)
  const holdValid =
    booking.status === 'PENDING_PAYMENT' && booking.holdExpiresAt !== null && new Date(booking.holdExpiresAt).getTime() > now
  const cancellable =
    booking.status === 'PENDING_PAYMENT' ||
    booking.status === 'AWAITING_APPROVAL' ||
    (booking.status === 'CONFIRMED' && new Date(booking.startTime).getTime() > now)
  const cancelledBy = booking.cancelledBy ? ` (${booking.cancelledBy.toLowerCase()})` : ''

  return (
    <div className="space-y-6">
      {isNew && <SuccessBanner booking={booking} />}
      <StatusNotice booking={booking} />

      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge kind="booking" status={booking.status} />
            <span className="text-sm text-slate-500">Booking code</span>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <p className="break-all font-mono text-3xl font-bold tracking-wider">{booking.bookingCode}</p>
            <CopyCodeButton code={booking.bookingCode} />
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {holdValid && <Link to={`/checkout/${booking.id}`} className={primaryLink}>Complete payment</Link>}
          {booking.invoiceNumber && <ReceiptButton booking={booking} />}
          {booking.disputable && (
            <Button type="button" variant="secondary" onClick={() => setReporting(true)}>Report a problem</Button>
          )}
          {cancellable && (
            <Button type="button" variant="secondary" onClick={() => setCancelling(true)}>Cancel booking</Button>
          )}
        </div>
      </div>

      <div className="grid items-start gap-6 md:grid-cols-[minmax(0,1fr)_minmax(0,20rem)]">
        <div className="min-w-0 space-y-6">
          <Panel title="Parking">
            <h4 className="text-lg font-semibold">
              <Link to={`/listings/${booking.listingId}`} className="text-brand-700 hover:underline dark:text-brand-400">
                {booking.listingTitle}
              </Link>
            </h4>
            <p className="text-slate-600 dark:text-slate-400">{booking.address}</p>
            <a
              href={`https://www.google.com/maps/dir/?api=1&destination=${booking.lat},${booking.lng}`}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 font-medium text-brand-700 hover:underline dark:text-brand-400"
            >
              Get directions
              <ExternalLink aria-hidden className="h-3.5 w-3.5" />
            </a>
            <dl className="space-y-2 pt-2">
              <Row label="When">{formatWindow(booking.startTime, booking.endTime)}</Row>
              <Row label="Duration">{durationLabel(minutes)}</Row>
              <Row label="Vehicle">
                <span className="font-mono">{booking.plateNumber}</span>
                {` · ${VEHICLE_TYPE_LABELS[booking.vehicleType]}`}
              </Row>
              <Row label="Slot">{booking.slotLabel}</Row>
            </dl>
          </Panel>

          <Panel title="Price">
            <dl className="space-y-2">
              <Row label={booking.pricingBreakdown}>{formatINR(booking.baseAmount)}</Row>
              <Row label="Platform fee">{formatINR(booking.platformFee)}</Row>
              <Row label="GST">{formatINR(booking.gstAmount)}</Row>
              <Row label="Total" className="border-t border-slate-200 pt-2 dark:border-slate-800">
                <span className="text-base font-bold">{formatINR(booking.totalAmount)}</span>
              </Row>
            </dl>
            {booking.refundAmount > 0 && (
              <p className="font-medium text-emerald-700 dark:text-emerald-400">{`Refunded ${formatINR(booking.refundAmount)}`}</p>
            )}
            {booking.cancelReason && booking.cancelledBy !== 'OWNER' && (
              <p className="rounded-lg bg-slate-100 px-3 py-2 text-slate-700 dark:bg-slate-800 dark:text-slate-300">
                {`Reason${cancelledBy}: ${booking.cancelReason}`}
              </p>
            )}
          </Panel>
        </div>

        <div className="min-w-0 space-y-6">
          {showQr && <BookingQr bookingCode={booking.bookingCode} />}
          <ReviewPanel booking={booking} />
          <DisputesPanel booking={booking} />
          <Panel title="Timeline">
            <ol aria-label="Booking timeline" className="space-y-3">
              {booking.events.map((event, i) => (
                <li key={`${event.at}-${i}`} className="border-l-2 border-brand-200 pl-3 dark:border-brand-800">
                  <p className="font-medium">{`${eventLabel(event)} · ${formatDateTime(event.at)}`}</p>
                  {event.note && <p className="text-slate-600 dark:text-slate-400">{event.note}</p>}
                </li>
              ))}
            </ol>
          </Panel>
        </div>
      </div>
      <RaiseDisputeDialog bookingId={booking.id} open={reporting} onClose={() => setReporting(false)} />
      {cancelling && (
        <CancelBookingDialog
          bookingId={booking.id}
          fees={booking.platformFee + booking.gstAmount}
          onClose={() => setCancelling(false)}
        />
      )}
    </div>
  )
}

export function BookingDetailPage() {
  const { id } = useParams()
  const [params] = useSearchParams()
  const { data, error, isPending } = useBooking(id, true, true)

  if (isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner className="h-8 w-8 text-brand-600" />
      </div>
    )
  }
  if (!data) {
    return (
      <div className="space-y-4">
        <FormError message={errorMessage(error)} />
        <Link to="/driver/bookings" className="text-sm font-semibold text-brand-700 hover:underline dark:text-brand-400">
          Back to your bookings
        </Link>
      </div>
    )
  }
  return <BookingContent booking={data} isNew={params.get('new') === '1'} />
}
