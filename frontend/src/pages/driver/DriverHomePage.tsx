import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthProvider'
import { FormError } from '../../components/AuthCard'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { useBookings } from '../../lib/bookings'
import { useDriverStats } from '../../lib/driver'
import { errorMessage } from '../../lib/errors'
import { VEHICLE_TYPE_LABELS, formatINR } from '../../lib/format'
import { formatWindow } from '../../lib/time'
import { useVehicles } from '../../lib/vehicles'

const primaryLink =
  'inline-flex items-center justify-center rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700'

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
      <h2 className="text-lg font-semibold">{title}</h2>
      <div className="mt-4 space-y-4">{children}</div>
    </section>
  )
}

function NextBookingCard() {
  const { data, error, isPending } = useBookings('upcoming', 0, 1)
  const next = data?.content[0]
  return (
    <Card title="Next booking">
      {isPending ? (
        <Spinner className="h-5 w-5 text-brand-600" />
      ) : error ? (
        <FormError message={errorMessage(error)} />
      ) : next ? (
        <>
          <div className="space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <p className="font-mono text-sm font-semibold">{next.bookingCode}</p>
              <StatusBadge kind="booking" status={next.status} />
            </div>
            <p className="font-semibold">{next.listingTitle}</p>
            <p className="text-sm text-slate-600 dark:text-slate-400">{formatWindow(next.startTime, next.endTime)}</p>
            {next.status === 'ACTIVE' && (
              <p className="text-sm font-medium text-sky-700 dark:text-sky-400">Your parking time is active.</p>
            )}
          </div>
          <Link to={`/driver/bookings/${next.id}`} className={primaryLink}>View booking</Link>
        </>
      ) : (
        <>
          <p className="text-sm text-slate-600 dark:text-slate-400">No upcoming bookings.</p>
          <Link to="/search" className={primaryLink}>Find parking</Link>
        </>
      )}
    </Card>
  )
}

function VehiclesCard() {
  const { data, error, isPending } = useVehicles()
  return (
    <Card title="Your vehicles">
      {isPending ? (
        <Spinner className="h-5 w-5 text-brand-600" />
      ) : error ? (
        <FormError message={errorMessage(error)} />
      ) : (
        <div className="space-y-1 text-sm text-slate-600 dark:text-slate-400">
          <p>
            <span className="text-3xl font-bold text-slate-900 dark:text-white">{data.length}</span>{' '}
            {data.length === 1 ? 'vehicle' : 'vehicles'} saved
          </p>
          {data.length > 0 && (
            <p>{data.map((v) => `${v.plateNumber} (${VEHICLE_TYPE_LABELS[v.type]})`).join(', ')}</p>
          )}
        </div>
      )}
      <Link
        to="/driver/vehicles"
        className="inline-block text-sm font-semibold text-brand-700 hover:underline dark:text-brand-400"
      >
        Manage vehicles
      </Link>
    </Card>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div role="group" aria-label={label} className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <p className="text-sm text-slate-600 dark:text-slate-400">{label}</p>
      <p className="mt-1 text-2xl font-bold">{value}</p>
    </div>
  )
}

/** Reviews are waiting: the link goes straight to the form on the booking that can be reviewed. */
function ReviewPrompt({ count, bookingId }: { count: number; bookingId: number }) {
  return (
    <section aria-labelledby="review-prompt" className="rounded-2xl border border-amber-200 bg-amber-50 p-5 dark:border-amber-900 dark:bg-amber-950/40">
      <h2 id="review-prompt" className="sr-only">Rate your parking</h2>
      <p className="font-medium text-amber-900 dark:text-amber-200">
        {count === 1 ? '1 booking is waiting for your review.' : `${count} bookings are waiting for your review.`}
      </p>
      <Link
        to={`/driver/bookings/${bookingId}#review`}
        className="mt-2 inline-block text-sm font-semibold text-brand-700 hover:underline dark:text-brand-400"
      >
        Rate your parking
      </Link>
    </section>
  )
}

function StatsSection() {
  const { data, isPending, error } = useDriverStats()
  if (isPending && !error) return <Spinner className="h-5 w-5 text-brand-600" />
  if (!data) return <p className="text-sm text-slate-600 dark:text-slate-400">Your stats are unavailable right now.</p>
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Bookings" value={String(data.totalBookings)} />
        <Stat label="Completed" value={String(data.completedBookings)} />
        <Stat label="Spent" value={formatINR(data.amountSpent)} />
        <Stat label="Hours parked" value={String(data.hoursParked)} />
      </div>
      {data.pendingReviews > 0 && data.reviewBookingId !== null && (
        <ReviewPrompt count={data.pendingReviews} bookingId={data.reviewBookingId} />
      )}
    </div>
  )
}

export function DriverHomePage() {
  const { user } = useAuth()
  if (!user) return null
  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-semibold">Welcome, {user.name.split(' ')[0]}</h2>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
          Find parking near your destination and manage your bookings here.
        </p>
      </div>
      <StatsSection />
      <div className="grid gap-6 md:grid-cols-2">
        <NextBookingCard />
        <VehiclesCard />
      </div>
    </div>
  )
}
