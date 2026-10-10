import { useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { FormError } from '../../components/AuthCard'
import { Loading, linkClass } from '../../components/admin/common'
import { Button } from '../../components/ui/Button'
import { ReasonDialog } from '../../components/ui/Dialog'
import { StatusBadge } from '../../components/ui/StatusBadge'
import {
  adminCancelBooking,
  CANCELLABLE_STATUSES,
  invalidateAdminBookings,
  mapAdminError,
  useAdminBooking,
  type AdminBookingDetail,
} from '../../lib/adminManage'
import { BOOKING_STATUS_LABELS } from '../../lib/bookings'
import { DISPUTE_CATEGORY_LABELS } from '../../lib/disputes'
import { errorMessage } from '../../lib/errors'
import { formatDateTime, formatINR } from '../../lib/format'
import { formatWindow } from '../../lib/time'
import { usePageTitle } from '../../lib/usePageTitle'

const ACTOR_LABELS = { DRIVER: 'Driver', OWNER: 'Owner', SYSTEM: 'System', ADMIN: 'Admin' } as const

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
      <h3 className="font-semibold">{title}</h3>
      <div className="mt-3 text-sm text-slate-700 dark:text-slate-300">{children}</div>
    </section>
  )
}

function Facts({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
      {rows.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-slate-500 dark:text-slate-400">{label}</dt>
          <dd className="min-w-0 break-words text-right sm:text-left">{value}</dd>
        </div>
      ))}
    </dl>
  )
}

function CancelSummary({ booking }: { booking: AdminBookingDetail }) {
  const refund = booking.refundableRemaining
  const unpaid = booking.payment === null || booking.payment.status === 'CREATED'
  if (unpaid) return <p className="text-sm">Nothing has been paid — the hold will be released.</p>
  if (refund > 0) {
    return (
      <p className="text-sm">
        The driver will be refunded <strong>{formatINR(refund)}</strong> (everything paid that has not been refunded yet). Both sides are told.
      </p>
    )
  }
  return <p className="text-sm">Everything paid has already been refunded.</p>
}

function BookingContent({ booking }: { booking: AdminBookingDetail }) {
  const queryClient = useQueryClient()
  const [cancelling, setCancelling] = useState(false)

  async function cancel(reason: string) {
    try {
      await adminCancelBooking(booking.id, reason)
    } catch (error) {
      throw mapAdminError(error)
    }
    toast.success('Booking cancelled')
    setCancelling(false)
    await invalidateAdminBookings(queryClient)
  }

  return (
    <div className="space-y-6">
      <Link to="/admin/bookings" className={`text-sm ${linkClass}`}>← Bookings</Link>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="font-mono text-xl font-semibold">{booking.bookingCode}</h2>
          <StatusBadge kind="booking" status={booking.status} />
        </div>
        {CANCELLABLE_STATUSES.includes(booking.status) && (
          <Button type="button" variant="danger" onClick={() => setCancelling(true)}>Cancel booking</Button>
        )}
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <Section title="Booking">
          <Facts
            rows={[
              ['Listing', booking.listingTitle],
              ['City', booking.cityName],
              ['Slot', `Slot ${booking.slotLabel}`],
              ['Time', formatWindow(booking.startTime, booking.endTime)],
              ['Driver', booking.driverName],
              ['Driver email', booking.driverEmail],
              ['Owner', booking.ownerName],
              ['Created', formatDateTime(booking.createdAt)],
              ...(booking.cancelledBy ? ([['Cancelled by', ACTOR_LABELS[booking.cancelledBy]]] as [string, ReactNode][]) : []),
              ...(booking.cancelReason ? ([['Reason', booking.cancelReason]] as [string, ReactNode][]) : []),
            ]}
          />
        </Section>

        <Section title="Amounts">
          <Facts
            rows={[
              ['Parking', formatINR(booking.baseAmount)],
              ['Platform fee', formatINR(booking.platformFee)],
              ['GST', formatINR(booking.gstAmount)],
              ['Total', <strong key="t">{formatINR(booking.totalAmount)}</strong>],
              ...(booking.refundAmount > 0 ? ([['Refunded', formatINR(booking.refundAmount)]] as [string, ReactNode][]) : []),
            ]}
          />
        </Section>

        <Section title="Payment">
          {booking.payment ? (
            <Facts
              rows={[
                ['Status', <StatusBadge key="s" kind="payment" status={booking.payment.status} />],
                ['Provider', booking.payment.provider === 'RAZORPAY' ? 'Razorpay' : 'Mock'],
                ['Order', <span key="o" className="font-mono text-xs">{booking.payment.providerOrderId}</span>],
                ['Payment', booking.payment.providerPaymentId ? <span key="p" className="font-mono text-xs">{booking.payment.providerPaymentId}</span> : '—'],
                ['Amount', formatINR(booking.payment.amount)],
                ['Captured', booking.payment.capturedAt ? formatDateTime(booking.payment.capturedAt) : '—'],
              ]}
            />
          ) : (
            <p>No payment was started for this booking.</p>
          )}
        </Section>

        <Section title="Refunds">
          {booking.refunds.length === 0 ? (
            <p>No refunds.</p>
          ) : (
            <ul className="divide-y divide-slate-200 dark:divide-slate-800">
              {booking.refunds.map((r) => (
                <li key={r.id} className="space-y-0.5 py-2 first:pt-0 last:pb-0">
                  <p className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold">{formatINR(r.amount)}</span>
                    <StatusBadge kind="refund" status={r.status} />
                  </p>
                  <p>{r.reason}</p>
                  <p className="text-xs text-slate-500 dark:text-slate-400">{`${r.attempts} ${r.attempts === 1 ? 'attempt' : 'attempts'} · ${formatDateTime(r.createdAt)}`}</p>
                </li>
              ))}
            </ul>
          )}
        </Section>

        <Section title="Disputes">
          {booking.disputes.length === 0 ? (
            <p>No disputes.</p>
          ) : (
            <ul className="space-y-2">
              {booking.disputes.map((d) => (
                <li key={d.id} className="flex flex-wrap items-center gap-2">
                  <Link to={`/admin/disputes/${d.id}`} className={linkClass}>{DISPUTE_CATEGORY_LABELS[d.category]}</Link>
                  <StatusBadge kind="dispute" status={d.status} />
                  <span className="text-xs text-slate-500 dark:text-slate-400">{formatDateTime(d.createdAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </Section>

        <Section title="History">
          <ul aria-label="Timeline" className="space-y-3">
            {booking.events.map((e, i) => (
              <li key={i} className="space-y-0.5">
                <p className="font-medium">
                  {e.fromStatus ? `${BOOKING_STATUS_LABELS[e.fromStatus]} → ` : ''}
                  {BOOKING_STATUS_LABELS[e.toStatus]}
                </p>
                {e.note && <p>{e.note}</p>}
                <p className="text-xs text-slate-500 dark:text-slate-400">{`${ACTOR_LABELS[e.actor]} · ${formatDateTime(e.at)}`}</p>
              </li>
            ))}
          </ul>
        </Section>
      </div>

      <ReasonDialog
        open={cancelling}
        title="Cancel this booking?"
        confirmLabel="Cancel booking"
        cancelLabel="Keep booking"
        maxLength={300}
        summary={<CancelSummary booking={booking} />}
        onConfirm={cancel}
        onClose={() => setCancelling(false)}
      />
    </div>
  )
}

export function AdminBookingDetailPage() {
  const { id } = useParams()
  const numeric = Number(id)
  const valid = Number.isInteger(numeric) && numeric > 0
  const { data, error, isPending } = useAdminBooking(valid ? numeric : undefined)
  usePageTitle(data ? `Admin · Booking ${data.bookingCode}` : 'Admin · Booking details')

  if (!valid) return <FormError message="Booking not found" />
  if (isPending) return <Loading />
  if (error) return <FormError message={errorMessage(error)} />
  return <BookingContent booking={data} />
}
