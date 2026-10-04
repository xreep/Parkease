import { useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { FormError } from '../../../components/AuthCard'
import { Button } from '../../../components/ui/Button'
import { errorMessage, missingParts, toProblem } from '../../../lib/errors'
import { AMENITY_LABELS, DAY_NAMES, formatINR, LISTING_TYPE_LABELS } from '../../../lib/format'
import { pauseListing, resumeListing, submitListing, useOwnerProfile, type CancellationPolicy } from '../../../lib/owner'
import type { StepProps } from './types'

const POLICY_LABELS: Record<CancellationPolicy, string> = { FLEXIBLE: 'Flexible', MODERATE: 'Moderate', STRICT: 'Strict' }

const MISSING_LINKS: Record<string, { label: string; step: number }> = {
  PHOTOS: { label: 'Add photos', step: 2 },
  SLOTS: { label: 'Add slots', step: 3 },
  PRICING: { label: 'Set pricing', step: 4 },
  AVAILABILITY: { label: 'Set opening hours', step: 5 },
}

const linkClass = 'text-sm font-medium text-brand-700 hover:underline dark:text-brand-400'

function SummaryCard({ title, listingId, step, children }: { title: string; listingId: number; step: number; children: ReactNode }) {
  return (
    <section className="rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
      <div className="flex items-center justify-between gap-3">
        <h3 className="font-semibold">{title}</h3>
        <Link to={`/owner/listings/${listingId}/edit?step=${step}`} className={linkClass} aria-label={`Edit ${title.toLowerCase()}`}>
          Edit
        </Link>
      </div>
      <div className="mt-3 space-y-1 text-sm text-slate-700 dark:text-slate-300">{children}</div>
    </section>
  )
}

export function ReviewStep({ listing, onSaved }: StepProps) {
  const navigate = useNavigate()
  const { data: profile } = useOwnerProfile()
  const [busy, setBusy] = useState<'submit' | 'pause' | 'resume' | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [missing, setMissing] = useState<string[]>([])

  const verified = profile?.verificationStatus === 'VERIFIED'
  const activeSlots = listing.slots.filter((s) => s.active)
  const cars = activeSlots.filter((s) => s.vehicleType === 'FOUR_WHEELER').length
  const twoWheelers = activeSlots.length - cars
  const hours = [...listing.hours].sort((a, b) => a.dayOfWeek - b.dayOfWeek)
  const prices = [
    ['hr', listing.pricePerHour],
    ['day', listing.pricePerDay],
    ['month', listing.pricePerMonth],
  ] as const

  async function submit() {
    setBusy('submit')
    setFormError(null)
    setMissing([])
    try {
      await submitListing(listing.id)
      toast.success('Listing submitted for approval')
      onSaved()
      navigate('/owner/listings')
    } catch (error) {
      const parts = toProblem(error).code === 'LISTING_INCOMPLETE' ? missingParts(error) : []
      if (parts.length > 0) setMissing(parts)
      else setFormError(errorMessage(error))
    } finally {
      setBusy(null)
    }
  }

  async function changeStatus(kind: 'pause' | 'resume') {
    setBusy(kind)
    try {
      await (kind === 'pause' ? pauseListing : resumeListing)(listing.id)
      toast.success(kind === 'pause' ? 'Listing paused' : 'Listing resumed')
      onSaved()
    } catch (error) {
      toast.error(errorMessage(error))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="space-y-6">
      <div className="grid gap-4 md:grid-cols-2">
        <SummaryCard title="Location" listingId={listing.id} step={1}>
          <p className="font-medium">{listing.title}</p>
          <p>{LISTING_TYPE_LABELS[listing.listingType]}</p>
          <p>{`${listing.address}, ${listing.cityName}, ${listing.stateName} ${listing.pincode}`}</p>
          <p className="text-slate-500">{`Lat ${listing.lat.toFixed(5)}, Lng ${listing.lng.toFixed(5)}`}</p>
        </SummaryCard>

        <SummaryCard title="Photos" listingId={listing.id} step={2}>
          {listing.photos.length === 0 ? (
            <p className="text-slate-500">No photos yet</p>
          ) : (
            <ul className="flex flex-wrap gap-2" aria-label="Photos">
              {listing.photos.map((p, i) => (
                <li key={p.id}>
                  <img src={p.url} alt={`Parking photo ${i + 1}`} className="h-16 w-24 rounded-lg object-cover" />
                </li>
              ))}
            </ul>
          )}
        </SummaryCard>

        <SummaryCard title="Slots" listingId={listing.id} step={3}>
          <p>{`${activeSlots.length} ${activeSlots.length === 1 ? 'slot' : 'slots'} · ${cars} car · ${twoWheelers} two-wheeler`}</p>
        </SummaryCard>

        <SummaryCard title="Pricing" listingId={listing.id} step={4}>
          {listing.pricePerHour === null ? (
            <p className="text-slate-500">No pricing yet</p>
          ) : (
            <>
              {prices.map(([unit, value]) => value !== null && <p key={unit}>{`${formatINR(value)}/${unit}`}</p>)}
              {listing.cancellationPolicy && <p>{`${POLICY_LABELS[listing.cancellationPolicy]} cancellation`}</p>}
              <p>{listing.autoApprove ? 'Bookings are approved automatically' : 'You approve each booking'}</p>
            </>
          )}
          {listing.rules && <p className="text-slate-500">{`Rules: ${listing.rules}`}</p>}
        </SummaryCard>

        <SummaryCard title="Opening hours" listingId={listing.id} step={5}>
          {listing.open24x7 ? (
            <p>Open 24 × 7</p>
          ) : hours.length === 0 ? (
            <p className="text-slate-500">No opening hours yet</p>
          ) : (
            hours.map((h) => (
              <p key={h.dayOfWeek}>{`${DAY_NAMES[h.dayOfWeek - 1]}: ${h.openTime.slice(0, 5)} – ${h.closeTime.slice(0, 5)}`}</p>
            ))
          )}
        </SummaryCard>

        <SummaryCard title="Amenities" listingId={listing.id} step={4}>
          {listing.amenities.length === 0 ? (
            <p className="text-slate-500">None selected</p>
          ) : (
            <ul className="flex flex-wrap gap-2">
              {listing.amenities.map((a) => (
                <li key={a} className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-medium dark:bg-slate-800">
                  {AMENITY_LABELS[a]}
                </li>
              ))}
            </ul>
          )}
        </SummaryCard>
      </div>

      <div className="space-y-3 border-t border-slate-200 pt-4 dark:border-slate-800">
        <FormError message={formError} />
        {missing.length > 0 && (
          <div role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950/50 dark:text-red-300">
            <p>Finish these steps before submitting:</p>
            <ul className="mt-1 list-disc pl-5">
              {missing.map((part) => {
                const target = MISSING_LINKS[part]
                return (
                  <li key={part}>
                    {target ? (
                      <Link to={`/owner/listings/${listing.id}/edit?step=${target.step}`} className="font-medium underline">
                        {target.label}
                      </Link>
                    ) : (
                      part
                    )}
                  </li>
                )
              })}
            </ul>
          </div>
        )}

        <div className="flex flex-wrap items-center justify-between gap-3">
          <Button type="button" variant="secondary" onClick={() => navigate(`/owner/listings/${listing.id}/edit?step=5`)}>
            Back
          </Button>

          {(listing.status === 'DRAFT' || listing.status === 'REJECTED') && (
            <div className="flex flex-wrap items-center gap-3">
              {profile && !verified && (
                <p className="text-sm text-amber-800 dark:text-amber-300">
                  <span>Verify your identity before submitting</span>{' '}
                  <Link to="/owner/verification" className="font-medium underline">Verify now</Link>
                </p>
              )}
              <Button type="button" disabled={!verified} loading={busy === 'submit'} onClick={() => void submit()}>
                Submit for approval
              </Button>
            </div>
          )}
          {listing.status === 'PENDING_REVIEW' && (
            <p className="text-sm font-medium text-amber-700 dark:text-amber-400">Waiting for approval</p>
          )}
          {listing.status === 'APPROVED' && (
            <div className="flex flex-wrap items-center gap-3">
              <p className="text-sm font-medium text-emerald-700 dark:text-emerald-400">This listing is live.</p>
              <Button type="button" variant="secondary" loading={busy === 'pause'} onClick={() => void changeStatus('pause')}>
                Pause listing
              </Button>
            </div>
          )}
          {listing.status === 'PAUSED' && (
            <div className="flex flex-wrap items-center gap-3">
              <p className="text-sm text-slate-600 dark:text-slate-400">This listing is paused and hidden from drivers.</p>
              <Button type="button" loading={busy === 'resume'} onClick={() => void changeStatus('resume')}>
                Resume listing
              </Button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
