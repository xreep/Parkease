import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { FormError } from '../../components/AuthCard'
import { MockPaymentDialog } from '../../components/booking/MockPaymentDialog'
import { Button } from '../../components/ui/Button'
import { Spinner } from '../../components/ui/Spinner'
import {
  invalidateBookingQueries,
  mockPay,
  useBooking,
  useCheckout,
  verifyPayment,
  type BookingDetailDto,
  type CheckoutDto,
  type VerifyBody,
} from '../../lib/bookings'
import { errorMessage, toProblem } from '../../lib/errors'
import { CANCELLATION_POLICIES, formatINR, VEHICLE_TYPE_LABELS } from '../../lib/format'
import { loadRazorpay, openRazorpay } from '../../lib/razorpay'
import { listingHref, usePublicListing } from '../../lib/search'
import { durationLabel, formatWindow } from '../../lib/time'

const primaryLink =
  'inline-flex items-center justify-center rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700'

const RED_UNDER_SECONDS = 120

/** Whole seconds left until `deadline`, ticking once a second; 0 once it has passed. */
function useSecondsLeft(deadline: string | null): number | null {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  if (!deadline) return null
  return Math.max(0, Math.ceil((new Date(deadline).getTime() - now) / 1000))
}

const pad = (n: number) => String(n).padStart(2, '0')
const clock = (seconds: number) => `${pad(Math.floor(seconds / 60))}:${pad(seconds % 60)}`

/** Narrows the rejection of {@link openRazorpay} without depending on class identity (tests mock the module). */
function razorpayOutcome(error: unknown): { kind: 'dismissed' } | { kind: 'failed'; description: string } | null {
  if (typeof error !== 'object' || error === null || !('kind' in error)) return null
  if (error.kind === 'dismissed') return { kind: 'dismissed' }
  if (error.kind === 'failed') {
    const description = 'description' in error && typeof error.description === 'string' ? error.description : 'Unknown error'
    return { kind: 'failed', description }
  }
  return null
}

function ExpiredView({ booking }: { booking: BookingDetailDto | undefined }) {
  const href = booking ? listingHref(booking.listingId, booking.startTime, booking.endTime, booking.vehicleType) : '/search'
  return (
    <section className="mx-auto max-w-lg space-y-4 px-4 py-16 text-center">
      <h1 className="text-2xl font-bold tracking-tight">Your reservation expired</h1>
      <p className="text-slate-600 dark:text-slate-400">
        We held the slot for a few minutes but didn't receive a payment. The slot is free for others again.
      </p>
      <Link to={href} className={primaryLink}>Search again</Link>
    </section>
  )
}

function Row({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className={strong ? 'flex justify-between gap-3 border-t border-slate-200 pt-2 text-base font-semibold dark:border-slate-700' : 'flex justify-between gap-3'}>
      <dt className={strong ? undefined : 'text-slate-600 dark:text-slate-400'}>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

type Notice = { kind: 'error' | 'info'; text: string }

function CheckoutView({ checkout }: { checkout: CheckoutDto }) {
  const { booking, payment } = checkout
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: listing } = usePublicListing(booking.listingId)
  const secondsLeft = useSecondsLeft(booking.holdExpiresAt)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [mockOpen, setMockOpen] = useState(false)
  const [mockError, setMockError] = useState<string | null>(null)
  /** A payment the provider accepted but whose confirmation with our server failed. */
  const [unverified, setUnverified] = useState<VerifyBody | null>(null)

  if (secondsLeft === 0) return <ExpiredView booking={booking} />

  const total = formatINR(booking.totalAmount)
  const policy = CANCELLATION_POLICIES.find((p) => p.value === listing?.cancellationPolicy)
  const minutes = (new Date(booking.endTime).getTime() - new Date(booking.startTime).getTime()) / 60_000

  async function confirmWith(body: VerifyBody) {
    const confirmed = await verifyPayment(body)
    queryClient.setQueryData(['booking', booking.id], confirmed)
    void invalidateBookingQueries(queryClient)
    navigate(`/driver/bookings/${booking.id}?new=1`, { replace: true })
  }

  async function verify(body: VerifyBody, onError: (message: string) => void) {
    try {
      await confirmWith(body)
      setUnverified(null)
    } catch (error) {
      setUnverified(body)
      onError(errorMessage(error))
    }
  }

  async function payWithRazorpay() {
    setNotice(null)
    setBusy(true)
    try {
      if (!(await loadRazorpay()) || !payment.keyId) {
        setNotice({ kind: 'error', text: "We couldn't load the payment window. Check your connection and try again." })
        return
      }
      let paid
      try {
        paid = await openRazorpay({
          key: payment.keyId,
          amount: payment.amount,
          currency: payment.currency,
          order_id: payment.orderId,
          name: payment.name,
          description: payment.description,
          prefill: { name: payment.prefill.name, email: payment.prefill.email, contact: payment.prefill.contact ?? undefined },
          theme: { color: '#059669' },
        })
      } catch (error) {
        const outcome = razorpayOutcome(error)
        if (outcome?.kind === 'dismissed') {
          setNotice({ kind: 'info', text: 'Payment cancelled. You can try again while your slot is held.' })
        } else if (outcome?.kind === 'failed') {
          setNotice({ kind: 'error', text: `Payment failed: ${outcome.description}` })
        } else {
          setNotice({ kind: 'error', text: errorMessage(error) })
        }
        return
      }
      await verify(
        { bookingId: booking.id, orderId: paid.razorpay_order_id, paymentId: paid.razorpay_payment_id, signature: paid.razorpay_signature },
        (text) => setNotice({ kind: 'error', text }),
      )
    } finally {
      setBusy(false)
    }
  }

  async function payWithMock() {
    setMockError(null)
    setBusy(true)
    try {
      let signed
      try {
        signed = await mockPay(booking.id)
      } catch (error) {
        setMockError(errorMessage(error))
        return
      }
      await verify({ bookingId: booking.id, ...signed }, setMockError)
    } finally {
      setBusy(false)
    }
  }

  function pay() {
    if (payment.provider === 'MOCK') {
      setNotice(null)
      setMockError(null)
      setMockOpen(true)
    } else {
      void payWithRazorpay()
    }
  }

  function closeMock() {
    setMockOpen(false)
    if (!unverified) setNotice({ kind: 'info', text: 'Payment cancelled. You can try again while your slot is held.' })
  }

  async function retryConfirmation() {
    if (!unverified) return
    setNotice(null)
    setBusy(true)
    try {
      await verify(unverified, (text) => setNotice({ kind: 'error', text }))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div aria-hidden={mockOpen || undefined} className="mx-auto max-w-5xl px-4 py-8 sm:px-6 sm:py-10">
        <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">Review and pay</h1>
        <div className="mt-6 grid gap-6 lg:grid-cols-[1fr_22rem]">
          <section aria-label="Reservation" className="overflow-hidden rounded-2xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
            {booking.coverPhotoUrl ? (
              <img src={booking.coverPhotoUrl} alt="" className="h-48 w-full object-cover" />
            ) : (
              <div aria-hidden className="flex h-48 w-full items-center justify-center bg-slate-100 text-5xl font-bold text-slate-300 dark:bg-slate-800 dark:text-slate-600">P</div>
            )}
            <div className="space-y-5 p-5">
              <div>
                <h2 className="text-xl font-semibold">{booking.listingTitle}</h2>
                <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">{booking.address}</p>
              </div>
              <div className="space-y-1 text-sm">
                <p className="font-medium">{formatWindow(booking.startTime, booking.endTime)}</p>
                <p className="text-slate-600 dark:text-slate-400">{durationLabel(minutes)}</p>
              </div>
              <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-slate-600 dark:text-slate-400">Vehicle</dt>
                  <dd>
                    <span className="font-mono font-semibold">{booking.plateNumber}</span>
                    {` · ${VEHICLE_TYPE_LABELS[booking.vehicleType]}`}
                  </dd>
                </div>
                <div>
                  <dt className="text-slate-600 dark:text-slate-400">Slot</dt>
                  <dd>{booking.slotLabel}</dd>
                </div>
              </dl>
              {policy && (
                <p className="text-sm text-slate-600 dark:text-slate-400">
                  <span className="font-medium text-slate-800 dark:text-slate-200">{`${policy.label} cancellation`}</span>
                  {`: ${policy.help}`}
                </p>
              )}
            </div>
          </section>

          <aside className="h-fit space-y-4 rounded-2xl border border-slate-200 bg-white p-5 lg:sticky lg:top-20 dark:border-slate-800 dark:bg-slate-900">
            <dl className="space-y-1 text-sm">
              <Row label={`Parking (${booking.pricingBreakdown})`} value={formatINR(booking.baseAmount)} />
              <Row label="Platform fee" value={formatINR(booking.platformFee)} />
              <Row label="GST on fee" value={formatINR(booking.gstAmount)} />
              <Row label="Total" value={total} strong />
            </dl>
            {secondsLeft !== null && (
              <p
                role="timer"
                className={secondsLeft < RED_UNDER_SECONDS ? 'text-sm font-semibold text-red-600 dark:text-red-400' : 'text-sm font-medium text-slate-700 dark:text-slate-300'}
              >
                {`Slot held for ${clock(secondsLeft)}`}
              </p>
            )}
            {notice?.kind === 'error' && <FormError message={notice.text} />}
            {notice?.kind === 'info' && <p role="status" className="text-sm text-amber-700 dark:text-amber-400">{notice.text}</p>}
            {unverified ? (
              <Button type="button" className="w-full" loading={busy} onClick={() => void retryConfirmation()}>Confirm my payment</Button>
            ) : (
              <Button type="button" className="w-full" loading={busy && !mockOpen} disabled={busy} onClick={pay}>{`Pay ${total}`}</Button>
            )}
            <p className="text-xs text-slate-500">Your slot is reserved until the timer runs out. Payments are processed securely by Razorpay.</p>
          </aside>
        </div>
      </div>
      <MockPaymentDialog
        open={mockOpen}
        amount={total}
        busy={busy}
        error={mockError}
        onPay={() => void payWithMock()}
        onFail={() => {
          setMockOpen(false)
          setNotice({ kind: 'error', text: 'Payment failed: Simulated failure' })
        }}
        onClose={closeMock}
      />
    </>
  )
}

export function CheckoutPage() {
  const { bookingId } = useParams()
  const { data, error, isPending } = useCheckout(bookingId)
  const problem = error ? toProblem(error) : null
  const expired = problem?.code === 'HOLD_EXPIRED' || problem?.status === 410
  const { data: expiredBooking } = useBooking(bookingId, expired)

  if (isPending) {
    return (
      <div className="flex justify-center py-24">
        <Spinner className="h-8 w-8 text-brand-600" />
      </div>
    )
  }
  if (expired) return <ExpiredView booking={expiredBooking} />
  if (problem || !data) {
    return (
      <section className="mx-auto max-w-lg space-y-4 px-4 py-16">
        <FormError message={problem?.detail ?? 'Something went wrong'} />
        <Link to="/driver/bookings" className={primaryLink}>View my bookings</Link>
      </section>
    )
  }
  return <CheckoutView checkout={data} />
}
