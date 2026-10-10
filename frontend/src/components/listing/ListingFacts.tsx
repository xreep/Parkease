import { AMENITY_LABELS, DAY_NAMES, formatINR } from '../../lib/format'
import type { CancellationPolicy, ListingDetail } from '../../lib/owner'

const POLICY_LABELS: Record<CancellationPolicy, string> = { FLEXIBLE: 'Flexible', MODERATE: 'Moderate', STRICT: 'Strict' }

/** Read-only fact blocks shared by the owner's review step and the admin review page. */

export function PhotoFacts({ listing }: { listing: ListingDetail }) {
  if (listing.photos.length === 0) return <p className="text-slate-500 dark:text-slate-400">No photos yet</p>
  return (
    <ul className="flex flex-wrap gap-2" aria-label="Photos">
      {listing.photos.map((p, i) => (
        <li key={p.id}>
          <img src={p.url} alt={`Parking photo ${i + 1}`} className="h-16 w-24 rounded-lg object-cover" />
        </li>
      ))}
    </ul>
  )
}

export function SlotFacts({ listing }: { listing: ListingDetail }) {
  const active = listing.slots.filter((s) => s.active)
  const cars = active.filter((s) => s.vehicleType === 'FOUR_WHEELER').length
  return <p>{`${active.length} ${active.length === 1 ? 'slot' : 'slots'} · ${cars} car · ${active.length - cars} two-wheeler`}</p>
}

export function PricingFacts({ listing, viewer = 'owner' }: { listing: ListingDetail; viewer?: 'owner' | 'admin' }) {
  const prices = [
    ['hr', listing.pricePerHour],
    ['day', listing.pricePerDay],
    ['month', listing.pricePerMonth],
  ] as const
  return (
    <>
      {listing.pricePerHour === null ? (
        <p className="text-slate-500 dark:text-slate-400">No pricing yet</p>
      ) : (
        <>
          {prices.map(([unit, value]) => value !== null && <p key={unit}>{`${formatINR(value)}/${unit}`}</p>)}
          {listing.cancellationPolicy && <p>{`${POLICY_LABELS[listing.cancellationPolicy]} cancellation`}</p>}
          <p>{listing.autoApprove ? 'Bookings are approved automatically' : viewer === 'owner' ? 'You approve each booking' : 'The owner approves each booking'}</p>
        </>
      )}
      {listing.rules && <p className="text-slate-500 dark:text-slate-400">{`Rules: ${listing.rules}`}</p>}
    </>
  )
}

export function HoursFacts({ listing }: { listing: ListingDetail }) {
  if (listing.open24x7) return <p>Open 24 × 7</p>
  const hours = [...listing.hours].sort((a, b) => a.dayOfWeek - b.dayOfWeek)
  if (hours.length === 0) return <p className="text-slate-500 dark:text-slate-400">No opening hours yet</p>
  return (
    <>
      {hours.map((h) => (
        <p key={h.dayOfWeek}>{`${DAY_NAMES[h.dayOfWeek - 1]}: ${h.openTime.slice(0, 5)} – ${h.closeTime.slice(0, 5)}`}</p>
      ))}
    </>
  )
}

export function AmenityFacts({ listing }: { listing: ListingDetail }) {
  if (listing.amenities.length === 0) return <p className="text-slate-500 dark:text-slate-400">None selected</p>
  return (
    <ul className="flex flex-wrap gap-2">
      {listing.amenities.map((a) => (
        <li key={a} className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-medium dark:bg-slate-800">
          {AMENITY_LABELS[a]}
        </li>
      ))}
    </ul>
  )
}
