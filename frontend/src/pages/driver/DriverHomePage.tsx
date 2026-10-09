import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthProvider'
import { FormError } from '../../components/AuthCard'
import { Spinner } from '../../components/ui/Spinner'
import { useBookings } from '../../lib/bookings'
import { errorMessage } from '../../lib/errors'
import { VEHICLE_TYPE_LABELS } from '../../lib/format'
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
            <p className="font-mono text-sm font-semibold">{next.bookingCode}</p>
            <p className="font-semibold">{next.listingTitle}</p>
            <p className="text-sm text-slate-600 dark:text-slate-400">{formatWindow(next.startTime, next.endTime)}</p>
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
      <div className="grid gap-6 md:grid-cols-2">
        <NextBookingCard />
        <VehiclesCard />
      </div>
    </div>
  )
}
