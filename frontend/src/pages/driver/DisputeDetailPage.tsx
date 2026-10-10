import { Link, useParams } from 'react-router-dom'
import { parseId } from '../../lib/params'
import { DisputePageShell } from '../../components/disputes/DisputePageShell'
import { DisputeView } from '../../components/disputes/DisputeView'
import { useMyDispute } from '../../lib/disputes'

export function DisputeDetailPage() {
  const { id } = useParams()
  const query = useMyDispute(parseId(id))
  return (
    <div className="space-y-4">
      <Link to="/driver/disputes" className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-400">← Help</Link>
      <DisputePageShell id={id} query={query}>
        {(dispute) => <DisputeView dispute={dispute} title={`Problem with ${dispute.bookingCode}`} bookingHref={`/driver/bookings/${dispute.bookingId}`} />}
      </DisputePageShell>
    </div>
  )
}
