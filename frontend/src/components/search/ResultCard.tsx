import clsx from 'clsx'
import { Car } from 'lucide-react'
import { Link } from 'react-router-dom'
import { AMENITY_LABELS, formatINR, LISTING_TYPE_LABELS } from '../../lib/format'
import { listingHref, type SearchResultDto, type VehicleType } from '../../lib/search'

const MAX_AMENITIES = 3

type Props = {
  result: SearchResultDto
  /** The searched window and vehicle, carried on to the listing page. */
  start?: string
  end?: string
  vehicle?: VehicleType
  highlighted: boolean
  onHighlight: (id: number | null) => void
}

export function ResultCard({ result: r, start, end, vehicle, highlighted, onHighlight }: Props) {
  const shown = r.amenities.slice(0, MAX_AMENITIES)
  const extra = r.amenities.length - shown.length
  const titleId = `result-${r.id}-title`

  return (
    <article
      id={`result-${r.id}`}
      aria-labelledby={titleId}
      data-highlighted={highlighted ? 'true' : 'false'}
      onMouseEnter={() => onHighlight(r.id)}
      onMouseLeave={() => onHighlight(null)}
      onFocus={() => onHighlight(r.id)}
      onBlur={() => onHighlight(null)}
      className={clsx(
        'flex gap-3 rounded-xl border bg-white p-3 transition dark:bg-slate-900',
        highlighted
          ? 'border-brand-500 shadow-md ring-2 ring-brand-500/30'
          : 'border-slate-200 hover:border-slate-300 dark:border-slate-800 dark:hover:border-slate-700',
      )}
    >
      <div className="h-24 w-28 shrink-0 overflow-hidden rounded-lg bg-slate-100 sm:h-28 sm:w-36 dark:bg-slate-800">
        {r.coverPhotoUrl ? (
          <img src={r.coverPhotoUrl} alt="" loading="lazy" className="h-full w-full object-cover" />
        ) : (
          <div aria-hidden className="grid h-full w-full place-items-center text-slate-400">
            <Car className="h-8 w-8" />
          </div>
        )}
      </div>

      <div className="min-w-0 flex-1 space-y-1.5">
        <div className="flex items-start justify-between gap-2">
          <h3 id={titleId} className="min-w-0 font-semibold leading-snug">
            <Link
              to={listingHref(r.id, start, end, vehicle)}
              className="break-words hover:text-brand-700 hover:underline dark:hover:text-brand-400"
            >
              {r.title}
            </Link>
          </h3>
          {r.reviewCount > 0 && (
            <span className="shrink-0 text-sm font-medium text-amber-600 dark:text-amber-400">
              {`★ ${r.avgRating.toFixed(1)} (${r.reviewCount})`}
            </span>
          )}
        </div>

        <p className="text-sm text-slate-600 dark:text-slate-400">
          {`${LISTING_TYPE_LABELS[r.listingType]} · ${r.distanceKm} km`}
        </p>

        <p className="flex flex-wrap items-baseline gap-x-3 text-sm">
          <span className="font-semibold text-brand-700 dark:text-brand-400">{`${formatINR(r.pricePerHour)}/hr`}</span>
          {r.pricePerDay !== null && <span className="text-slate-600 dark:text-slate-400">{`${formatINR(r.pricePerDay)}/day`}</span>}
          {r.pricePerMonth !== null && (
            <span className="text-slate-600 dark:text-slate-400">{`${formatINR(r.pricePerMonth)}/month`}</span>
          )}
        </p>

        {shown.length > 0 && (
          <ul className="flex flex-wrap gap-1.5">
            {shown.map((a) => (
              <li key={a} className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-700 dark:bg-slate-800 dark:text-slate-300">
                {AMENITY_LABELS[a]}
              </li>
            ))}
            {extra > 0 && (
              <li className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-700 dark:bg-slate-800 dark:text-slate-300">
                {`+${extra}`}
              </li>
            )}
          </ul>
        )}

        <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-sm">
          <span className="text-slate-600 dark:text-slate-400">
            {r.freeSlots !== null ? `${r.freeSlots} of ${r.totalSlots} slots free` : `${r.totalSlots} slots`}
          </span>
          {r.quote && <span className="font-semibold">{`${formatINR(r.quote.totalAmount)} total`}</span>}
        </div>
      </div>
    </article>
  )
}

export function ResultCardSkeleton() {
  return (
    <div aria-hidden className="flex animate-pulse gap-3 rounded-xl border border-slate-200 p-3 dark:border-slate-800">
      <div className="h-24 w-28 shrink-0 rounded-lg bg-slate-200 sm:h-28 sm:w-36 dark:bg-slate-800" />
      <div className="flex-1 space-y-2.5 py-1">
        <div className="h-4 w-3/4 rounded bg-slate-200 dark:bg-slate-800" />
        <div className="h-3 w-1/2 rounded bg-slate-200 dark:bg-slate-800" />
        <div className="h-3 w-2/3 rounded bg-slate-200 dark:bg-slate-800" />
        <div className="h-3 w-1/3 rounded bg-slate-200 dark:bg-slate-800" />
      </div>
    </div>
  )
}
