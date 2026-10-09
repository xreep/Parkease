import { useParams } from 'react-router-dom'
import { FormError } from '../../components/AuthCard'
import { Spinner } from '../../components/ui/Spinner'
import { useBooking } from '../../lib/bookings'
import { errorMessage } from '../../lib/errors'

/** Placeholder until the full booking page lands (Task 11). */
export function BookingDetailPage() {
  const { id } = useParams()
  const { data, error, isPending } = useBooking(id)
  return (
    <section className="space-y-2">
      {isPending ? (
        <Spinner className="h-6 w-6 text-brand-600" />
      ) : error ? (
        <FormError message={errorMessage(error)} />
      ) : (
        <>
          <h2 className="text-xl font-semibold">{data.listingTitle}</h2>
          <p className="font-mono text-sm">{data.bookingCode}</p>
        </>
      )}
    </section>
  )
}
