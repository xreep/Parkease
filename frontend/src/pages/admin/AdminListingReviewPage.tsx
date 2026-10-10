import { useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { FormError } from '../../components/AuthCard'
import { AmenityFacts, HoursFacts, PricingFacts, SlotFacts } from '../../components/listing/ListingFacts'
import { LocationPicker } from '../../components/owner/LocationPicker'
import { Button } from '../../components/ui/Button'
import { ReasonDialog } from '../../components/ui/Dialog'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { approveListing, invalidateAdminActivity, reinstateListing, rejectListing, suspendListing, useAdminListing, type AdminListingDetail } from '../../lib/admin'
import { errorMessage } from '../../lib/errors'
import { formatAddress, formatDateTime, LISTING_TYPE_LABELS } from '../../lib/format'
import { usePageTitle } from '../../lib/usePageTitle'

const linkClass = 'text-sm font-medium text-brand-700 hover:underline dark:text-brand-400'
const noop = () => undefined

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
      <h3 className="font-semibold">{title}</h3>
      <div className="mt-3 space-y-1 text-sm text-slate-700 dark:text-slate-300">{children}</div>
    </section>
  )
}

function ReviewContent({ detail }: { detail: AdminListingDetail }) {
  const { listing, owner } = detail
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [approving, setApproving] = useState(false)
  const [rejecting, setRejecting] = useState(false)
  const [suspending, setSuspending] = useState(false)
  const [reinstating, setReinstating] = useState(false)

  // Suspending and reinstating keep the admin on the page, which shows the new status.
  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['admin', 'listings'] }),
      queryClient.invalidateQueries({ queryKey: ['admin', 'listing', listing.id] }),
      queryClient.invalidateQueries({ queryKey: ['search'] }),
      invalidateAdminActivity(queryClient),
    ]).then(() => undefined)

  async function suspend(reason: string) {
    await suspendListing(listing.id, reason)
    setSuspending(false)
    toast.success('Listing suspended')
    await refresh()
  }

  async function reinstate() {
    setReinstating(true)
    try {
      await reinstateListing(listing.id)
      toast.success('Listing reinstated')
      await refresh()
    } catch (error) {
      toast.error(errorMessage(error))
    } finally {
      setReinstating(false)
    }
  }

  const finish = async (message: string) => {
    toast.success(message)
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['admin', 'listings'] }),
      queryClient.invalidateQueries({ queryKey: ['admin', 'listing', listing.id] }),
      invalidateAdminActivity(queryClient),
    ])
    navigate('/admin/listings')
  }

  async function approve() {
    setApproving(true)
    try {
      await approveListing(listing.id)
      await finish('Listing approved')
    } catch (error) {
      toast.error(errorMessage(error))
      setApproving(false)
    }
  }

  async function reject(reason: string) {
    await rejectListing(listing.id, reason)
    setRejecting(false)
    await finish('Listing rejected')
  }

  return (
    <div className="space-y-6">
      <Link to="/admin/listings" className={linkClass}>← Listing approvals</Link>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="text-xl font-semibold">{listing.title}</h2>
          <StatusBadge kind="listing" status={listing.status} />
        </div>
        {listing.status === 'PENDING_REVIEW' && (
          <div className="flex flex-wrap gap-2">
            <Button type="button" loading={approving} disabled={rejecting} onClick={() => void approve()}>Approve</Button>
            <Button type="button" variant="danger" disabled={approving} onClick={() => setRejecting(true)}>Reject</Button>
          </div>
        )}
        {(listing.status === 'APPROVED' || listing.status === 'PAUSED') && (
          <Button type="button" variant="danger" onClick={() => setSuspending(true)}>Suspend</Button>
        )}
        {listing.status === 'SUSPENDED' && (
          <Button type="button" loading={reinstating} onClick={() => void reinstate()}>Reinstate</Button>
        )}
      </div>
      {listing.submittedAt && <p className="-mt-4 text-sm text-slate-500">{`Submitted ${formatDateTime(listing.submittedAt)}`}</p>}

      {listing.photos.length === 0 ? (
        <p className="rounded-xl border border-dashed border-slate-300 p-6 text-center text-sm text-slate-500 dark:border-slate-700">No photos</p>
      ) : (
        <ul aria-label="Photos" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {listing.photos.map((p, i) => (
            <li key={p.id}>
              <a href={p.url} target="_blank" rel="noopener noreferrer">
                <img src={p.url} alt={`Parking photo ${i + 1}`} className="h-48 w-full rounded-xl object-cover" />
              </a>
            </li>
          ))}
        </ul>
      )}

      <div className="grid gap-4 md:grid-cols-2">
        <Section title="Location">
          <p>{LISTING_TYPE_LABELS[listing.listingType]}</p>
          <p>{formatAddress(listing)}</p>
          {listing.description && <p className="text-slate-500">{listing.description}</p>}
          <div className="pt-2">
            <LocationPicker value={{ lat: listing.lat, lng: listing.lng }} center={{ lat: listing.lat, lng: listing.lng }} zoom={15} onChange={noop} readOnly />
          </div>
        </Section>

        <div className="space-y-4">
          <Section title="Owner">
            <div className="flex flex-wrap items-center gap-2">
              <p className="font-medium">{owner.name}</p>
              <StatusBadge kind="verification" status={owner.verificationStatus} />
            </div>
            <p className="break-all">{owner.email}</p>
            {owner.phone && <p>{owner.phone}</p>}
          </Section>
          <Section title="Slots">
            <SlotFacts listing={listing} />
          </Section>
          <Section title="Pricing">
            <PricingFacts listing={listing} viewer="admin" />
          </Section>
        </div>

        <Section title="Opening hours">
          <HoursFacts listing={listing} />
        </Section>
        <Section title="Amenities">
          <AmenityFacts listing={listing} />
        </Section>
      </div>

      <ReasonDialog
        open={rejecting}
        title="Reject this listing?"
        confirmLabel="Reject"
        onConfirm={reject}
        onClose={() => setRejecting(false)}
      />
      <ReasonDialog
        open={suspending}
        title="Suspend this listing?"
        confirmLabel="Suspend"
        helper="The listing stops taking new bookings and the owner is told why. Existing bookings are honoured."
        onConfirm={suspend}
        onClose={() => setSuspending(false)}
      />
    </div>
  )
}

export function AdminListingReviewPage() {
  usePageTitle('Admin · Review listing')
  const { id } = useParams()
  const numeric = Number(id)
  const valid = Number.isInteger(numeric) && numeric > 0
  const { data, error, isPending } = useAdminListing(valid ? numeric : undefined)

  if (!valid) return <FormError message="Listing not found" />
  if (isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner className="h-8 w-8 text-brand-600" />
      </div>
    )
  }
  if (error) return <FormError message={errorMessage(error)} />
  return <ReviewContent detail={data} />
}
