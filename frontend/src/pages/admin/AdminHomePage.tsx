import { Link } from 'react-router-dom'
import { FormError } from '../../components/AuthCard'
import { Spinner } from '../../components/ui/Spinner'
import { useQueues } from '../../lib/admin'
import { errorMessage } from '../../lib/errors'

const primaryLink =
  'inline-flex items-center justify-center rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700'

function QueueCard({ summary, to, action }: { summary: string; to: string; action: string }) {
  return (
    <section className="flex flex-col gap-4 rounded-2xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
      <p className="text-lg font-semibold">{summary}</p>
      <div>
        <Link to={to} className={primaryLink}>{action}</Link>
      </div>
    </section>
  )
}

export function AdminHomePage() {
  const { data, error, isPending } = useQueues()

  if (isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner className="h-8 w-8 text-brand-600" />
      </div>
    )
  }
  if (error) return <FormError message={errorMessage(error)} />

  return (
    <div className="space-y-6">
      <div className="grid gap-6 md:grid-cols-2">
        <QueueCard
          summary={`${data.pendingOwners} ${data.pendingOwners === 1 ? 'owner' : 'owners'} waiting for verification`}
          to="/admin/owners"
          action="Review owners"
        />
        <QueueCard
          summary={`${data.pendingListings} ${data.pendingListings === 1 ? 'listing' : 'listings'} waiting for approval`}
          to="/admin/listings"
          action="Review listings"
        />
      </div>
      <p className="text-sm text-slate-500">More admin tools (users, bookings, reports) arrive in a later phase.</p>
    </div>
  )
}
