import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ExternalLink } from 'lucide-react'
import { AvailabilityCalendar } from '../components/listing/AvailabilityCalendar'
import { BookingCard } from '../components/listing/BookingCard'
import { PhotoGallery } from '../components/listing/PhotoGallery'
import { LocationPicker } from '../components/owner/LocationPicker'
import { ListingReviews } from '../components/reviews/ListingReviews'
import { Spinner } from '../components/ui/Spinner'
import { toProblem } from '../lib/errors'
import {
  AMENITY_LABELS,
  CANCELLATION_POLICIES,
  REFUND_NOTE,
  DAY_NAMES,
  formatAddress,
  LISTING_TYPE_LABELS,
} from '../lib/format'
import { windowForDay } from '../lib/availability'
import { usePublicListing, type PublicListingDto } from '../lib/search'
import { toLocalInputValue } from '../lib/time'
import { NotFoundPage } from './NotFoundPage'
import { usePageTitle } from '../lib/usePageTitle'

const noop = () => undefined

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-2">
      <h2 className="text-lg font-semibold">{title}</h2>
      {children}
    </section>
  )
}

function SlotsSection({ summary }: { summary: PublicListingDto['slotSummary'] }) {
  const sizes = [
    [summary.small, 'small'],
    [summary.medium, 'medium'],
    [summary.large, 'large'],
  ] as const
  const bySize = sizes.filter(([n]) => n > 0).map(([n, label]) => `${n} ${label}`)
  return (
    <Section title="Slots">
      <p>{`${summary.fourWheeler} car · ${summary.twoWheeler} two-wheeler`}</p>
      {bySize.length > 0 && <p className="text-sm text-slate-600 dark:text-slate-400">{bySize.join(' · ')}</p>}
    </Section>
  )
}

function HoursSection({ listing }: { listing: PublicListingDto }) {
  return (
    <Section title="Opening hours (IST)">
      {listing.open24x7 ? (
        <p>Open 24 × 7</p>
      ) : (
        <dl className="grid max-w-xs grid-cols-[auto_1fr] gap-x-6 gap-y-1">
          {DAY_NAMES.map((name, i) => {
            const rule = listing.hours.find((h) => h.dayOfWeek === i + 1)
            return (
              <div key={name} className="contents">
                <dt>{name}</dt>
                <dd className={rule ? undefined : 'text-slate-500'}>
                  {rule ? `${rule.openTime.slice(0, 5)} – ${rule.closeTime.slice(0, 5)}` : 'Closed'}
                </dd>
              </div>
            )
          })}
        </dl>
      )}
    </Section>
  )
}

function ListingView({ id }: { id: string }) {
  const { data: listing, isPending, error } = usePublicListing(id)
  // An unknown listing renders the 404 page, which sets its own title.
  usePageTitle(error && toProblem(error).status === 404 ? undefined : (listing?.title ?? 'Parking spot'))
  const [requested, setRequested] = useState<{ start: string; end: string } | null>(null)

  if (isPending && !error) {
    return (
      <div className="flex justify-center py-24">
        <Spinner className="h-8 w-8 text-brand-600" />
      </div>
    )
  }
  if (error) {
    if (toProblem(error).status === 404) return <NotFoundPage />
    return <p className="px-4 py-24 text-center text-red-600">Could not load this listing. Please refresh.</p>
  }

  const policy = CANCELLATION_POLICIES.find((p) => p.value === listing.cancellationPolicy)
  const here = { lat: listing.lat, lng: listing.lng }

  return (
    <div className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
      <div className="mb-4 space-y-1">
        <h1 className="text-2xl font-bold tracking-tight break-words sm:text-3xl">{listing.title}</h1>
        <p className="flex flex-wrap items-center gap-x-3 text-slate-600 dark:text-slate-400">
          <span>{`${LISTING_TYPE_LABELS[listing.listingType]} · ${listing.cityName}, ${listing.stateName}`}</span>
          {listing.reviewCount > 0 && (
            <span className="font-medium text-amber-600 dark:text-amber-400">{`★ ${listing.avgRating.toFixed(1)} (${listing.reviewCount})`}</span>
          )}
        </p>
      </div>

      <PhotoGallery photos={listing.photos} title={listing.title} />

      <div className="mt-8 grid items-start gap-8 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="min-w-0 space-y-8">
          {listing.description && (
            <Section title="About">
              <p className="whitespace-pre-line break-words">{listing.description}</p>
            </Section>
          )}

          <Section title="Location">
            <p>{formatAddress(listing)}</p>
            <LocationPicker value={here} center={here} onChange={noop} readOnly />
            <a
              href={`https://www.google.com/maps/dir/?api=1&destination=${listing.lat},${listing.lng}`}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-sm font-medium text-brand-700 hover:underline dark:text-brand-400"
            >
              Get directions
              <ExternalLink aria-hidden className="h-3.5 w-3.5" />
            </a>
          </Section>

          <SlotsSection summary={listing.slotSummary} />
          <HoursSection listing={listing} />
          <AvailabilityCalendar
            listingId={listing.id}
            onPick={(day) => {
              const { start, end } = windowForDay(day)
              setRequested({ start: toLocalInputValue(start), end: toLocalInputValue(end) })
            }}
          />

          {listing.amenities.length > 0 && (
            <Section title="Amenities">
              <ul className="flex flex-wrap gap-2">
                {listing.amenities.map((a) => (
                  <li key={a} className="rounded-full bg-slate-100 px-3 py-1 text-sm font-medium dark:bg-slate-800">
                    {AMENITY_LABELS[a]}
                  </li>
                ))}
              </ul>
            </Section>
          )}

          {listing.rules && (
            <Section title="Rules">
              <p className="whitespace-pre-line break-words">{listing.rules}</p>
            </Section>
          )}

          <Section title="Cancellation policy">
            {policy && (
              <p>
                <span className="font-medium">{policy.label}</span>
                {`: ${policy.help}`}
              </p>
            )}
            <p className="text-sm text-slate-600 dark:text-slate-400">{REFUND_NOTE}</p>
            <p className="text-sm text-slate-600 dark:text-slate-400">
              {listing.autoApprove ? 'Bookings are approved automatically.' : 'The owner approves each booking.'}
            </p>
          </Section>

          <ListingReviews listingId={listing.id} />

          <p className="text-sm text-slate-600 dark:text-slate-400">{`Hosted by ${listing.ownerFirstName}`}</p>
          <Link to="/search" className="inline-block text-sm font-medium text-brand-700 hover:underline dark:text-brand-400">
            Search for more parking
          </Link>
        </div>

        <aside className="lg:sticky lg:top-20">
          <BookingCard listing={listing} requested={requested} />
        </aside>
      </div>
    </div>
  )
}

export function ListingPage() {
  const { id = '' } = useParams()
  return /^\d+$/.test(id) ? <ListingView id={id} /> : <NotFoundPage />
}
