import clsx from 'clsx'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthProvider'
import { FormError } from '../../components/AuthCard'
import { BarChart } from '../../components/charts/BarChart'
import { LineChart } from '../../components/charts/LineChart'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { useOwnerBookings } from '../../lib/bookings'
import { errorMessage } from '../../lib/errors'
import { VEHICLE_TYPE_LABELS, formatCompactINR, formatINR } from '../../lib/format'
import { useMyListings, useOwnerProfile, type OwnerProfile } from '../../lib/owner'
import { STATS_RANGES, useOwnerStats, type OwnerStatsDto, type StatsRange } from '../../lib/ownerDashboard'
import { ratingText } from '../../lib/reviews'
import { formatShortDate, formatWindow } from '../../lib/time'
import { usePageTitle } from '../../lib/usePageTitle'

const primaryLink =
  'inline-flex items-center justify-center rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-800'
const secondaryLink =
  'inline-flex items-center justify-center rounded-lg border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-800 transition hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800'

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
      <h2 className="text-lg font-semibold">{title}</h2>
      <div className="mt-4 space-y-4">{children}</div>
    </section>
  )
}

function VerificationCopy({ profile }: { profile: OwnerProfile }) {
  switch (profile.verificationStatus) {
    case 'UNSUBMITTED':
      return (
        <>
          <p className="text-sm text-slate-600 dark:text-slate-400">Verify your identity to start listing parking.</p>
          <Link to="/owner/verification" className={primaryLink}>Start verification</Link>
        </>
      )
    case 'PENDING':
      return <p className="text-sm text-slate-600 dark:text-slate-400">We're reviewing your document. This usually takes a day.</p>
    case 'REJECTED':
      return (
        <>
          <p className="text-sm text-red-700 dark:text-red-300">
            Your verification was rejected: {profile.rejectionReason ?? 'no reason given'}
          </p>
          <Link to="/owner/verification" className={primaryLink}>Upload again</Link>
        </>
      )
    case 'VERIFIED':
      return <p className="text-sm text-slate-600 dark:text-slate-400">You're verified. You can submit listings for approval.</p>
  }
}

function VerificationCard() {
  const { data: profile, error, isPending } = useOwnerProfile()
  return (
    <Card title="Identity verification">
      {isPending ? (
        <Spinner className="h-5 w-5 text-brand-600" />
      ) : error ? (
        <FormError message={errorMessage(error)} />
      ) : (
        <>
          <StatusBadge kind="verification" status={profile.verificationStatus} />
          <VerificationCopy profile={profile} />
        </>
      )}
    </Card>
  )
}

function ListingsCard() {
  const { data, error, isPending } = useMyListings(0)
  return (
    <Card title="Your listings">
      {isPending ? (
        <Spinner className="h-5 w-5 text-brand-600" />
      ) : error ? (
        <FormError message={errorMessage(error)} />
      ) : (
        <p className="text-sm text-slate-600 dark:text-slate-400">
          <span className="text-3xl font-bold text-slate-900 dark:text-white">{data.totalElements}</span>{' '}
          {data.totalElements === 1 ? 'listing' : 'listings'} in total
        </p>
      )}
      <div className="flex flex-wrap gap-3">
        <Link to="/owner/listings/new" className={primaryLink}>Add a listing</Link>
        <Link to="/owner/listings" className={secondaryLink}>Manage listings</Link>
      </div>
    </Card>
  )
}

/** Only shown when something is waiting, so it never adds a spinner or an error to a quiet dashboard. */
function RequestsCard() {
  const { data } = useOwnerBookings('requests', 0, 1)
  const waiting = data?.totalElements ?? 0
  if (waiting === 0) return null
  return (
    <Card title="Booking requests">
      <p className="font-semibold text-slate-900 dark:text-white">
        {`${waiting} booking ${waiting === 1 ? 'request' : 'requests'} waiting`}
      </p>
      <Link to="/owner/bookings" className={primaryLink}>Review requests</Link>
    </Card>
  )
}

function Kpi({ label, value, note, children }: { label: string; value: string; note?: string; children?: React.ReactNode }) {
  return (
    <div role="group" aria-label={label} className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <p className="text-sm text-slate-600 dark:text-slate-400">{label}</p>
      <p className="mt-1 text-2xl font-bold">{value}</p>
      {note && <p className="text-xs text-slate-500 dark:text-slate-400">{note}</p>}
      {children}
    </div>
  )
}

function Balance({ label, value }: { label: string; value: number }) {
  return (
    <div role="group" aria-label={label}>
      <p className="text-sm text-slate-600 dark:text-slate-400">{label}</p>
      <p className="text-xl font-semibold">{formatINR(value)}</p>
    </div>
  )
}

function StatsView({ stats }: { stats: OwnerStatsDto }) {
  const { totals, balances } = stats
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
        <Kpi label="Earnings" value={formatINR(totals.earningsNet)} note="After commission" />
        <Kpi label="Bookings" value={String(totals.bookings)} note={totals.cancellations > 0 ? `${totals.cancellations} cancelled` : undefined} />
        <Kpi label="Occupancy" value={`${totals.occupancyPercent}%`} />
        <Kpi
          label="Rating"
          value={totals.reviewCount > 0 ? ratingText(totals.avgRating) : '—'}
          note={`${totals.reviewCount} ${totals.reviewCount === 1 ? 'review' : 'reviews'}`}
        />
        <Kpi label="Pending approvals" value={String(stats.pendingApprovals)}>
          {stats.pendingApprovals > 0 && (
            <Link to="/owner/bookings" className="mt-1 inline-block text-sm font-medium text-brand-700 hover:underline dark:text-brand-400">
              Review approvals
            </Link>
          )}
        </Kpi>
      </div>

      <section aria-labelledby="balances-heading" className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h3 id="balances-heading" className="font-semibold">Balances</h3>
          <Link to="/owner/earnings" className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-400">See all earnings</Link>
        </div>
        <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Balance label="Held" value={balances.held} />
          <Balance label="Pending payout" value={balances.pendingPayout} />
          <Balance label="Paid" value={balances.paid} />
        </div>
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <section className="space-y-2">
          <h3 className="font-semibold">Earnings</h3>
          <BarChart
            title="Earnings per day"
            points={stats.series.map((d) => ({ label: formatShortDate(d.date), value: d.earningsNet }))}
            formatValue={formatINR}
            formatAxis={formatCompactINR}
          />
        </section>
        <section className="space-y-2">
          <h3 className="font-semibold">Bookings</h3>
          <LineChart
            title="Bookings per day"
            points={stats.series.map((d) => ({ label: formatShortDate(d.date), value: d.bookings }))}
            formatValue={(n) => String(n)}
          />
        </section>
      </div>

      <section className="space-y-2">
        <h3 className="font-semibold">Upcoming bookings</h3>
        {stats.upcoming.length === 0 ? (
          <p className="text-sm text-slate-600 dark:text-slate-400">No upcoming bookings.</p>
        ) : (
          <ul aria-label="Upcoming bookings" className="divide-y divide-slate-200 rounded-2xl border border-slate-200 bg-white dark:divide-slate-800 dark:border-slate-800 dark:bg-slate-900">
            {stats.upcoming.map((b) => (
              <li key={b.id} className="flex flex-wrap items-start justify-between gap-2 p-4 text-sm">
                <div className="min-w-0 space-y-0.5">
                  <p className="flex flex-wrap items-center gap-2">
                    <span className="font-mono font-semibold">{b.bookingCode}</span>
                    <StatusBadge kind="booking" status={b.status} />
                  </p>
                  <p className="break-words font-medium">{b.listingTitle}</p>
                  <p className="text-slate-600 dark:text-slate-400">{`${formatWindow(b.startTime, b.endTime)} · Slot ${b.slotLabel}`}</p>
                  <p className="text-slate-600 dark:text-slate-400">{`${b.driverFirstName} · ${VEHICLE_TYPE_LABELS[b.vehicleType]}`}</p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

function Overview() {
  const [days, setDays] = useState<StatsRange>(30)
  const { data, error, isPending, isPlaceholderData } = useOwnerStats(days)
  return (
    <section aria-label="Overview" className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h3 className="text-lg font-semibold">Your numbers</h3>
        <Select
          label="Range"
          value={days}
          onChange={(e) => setDays(Number(e.target.value) as StatsRange)}
        >
          {STATS_RANGES.map((n) => (
            <option key={n} value={n}>{`Last ${n} days`}</option>
          ))}
        </Select>
      </div>
      {isPending && !error ? (
        <div className="flex justify-center py-12">
          <Spinner className="h-8 w-8 text-brand-600" />
        </div>
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data ? (
        <div className={clsx('transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
          <StatsView stats={data} />
        </div>
      ) : null}
    </section>
  )
}

export function OwnerHomePage() {
  usePageTitle('Owner dashboard')
  const { user } = useAuth()
  const profile = useOwnerProfile()
  if (!user) return null
  // Until verified, the prompt leads the page; afterwards it is one card among the rest. It waits for the profile, so
  // it never appears in one place and then moves.
  const profileKnown = !profile.isPending
  const needsVerification = profile.data !== undefined && profile.data.verificationStatus !== 'VERIFIED'
  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-semibold">Welcome, {user.name.split(' ')[0]}</h2>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
          Manage your spaces, prices and availability as a parking owner.
        </p>
      </div>
      {needsVerification && <VerificationCard />}
      <Overview />
      <div className="grid gap-6 md:grid-cols-2">
        {profileKnown && !needsVerification && <VerificationCard />}
        <ListingsCard />
        <RequestsCard />
      </div>
    </div>
  )
}
