import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthProvider'
import { FormError } from '../../components/AuthCard'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { useOwnerBookings } from '../../lib/bookings'
import { errorMessage } from '../../lib/errors'
import { useMyListings, useOwnerProfile, type OwnerProfile } from '../../lib/owner'

const primaryLink =
  'inline-flex items-center justify-center rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700'
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

export function OwnerHomePage() {
  const { user } = useAuth()
  if (!user) return null
  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-semibold">Welcome, {user.name.split(' ')[0]}</h2>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
          Manage your spaces, prices and availability as a parking owner.
        </p>
      </div>
      <div className="grid gap-6 md:grid-cols-2">
        <VerificationCard />
        <ListingsCard />
        <RequestsCard />
      </div>
    </div>
  )
}
