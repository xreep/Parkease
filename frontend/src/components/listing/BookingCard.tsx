import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../../auth/AuthProvider'
import { toProblem } from '../../lib/errors'
import { formatINR, VEHICLE_TYPE_LABELS } from '../../lib/format'
import {
  listingHref,
  useQuote,
  type PublicListingDto,
  type QuoteUnavailableReason,
  type VehicleType,
} from '../../lib/search'
import { defaultWindow, fromLocalInputValue, toLocalInputValue } from '../../lib/time'
import { Button } from '../ui/Button'
import { Select } from '../ui/Select'
import { Spinner } from '../ui/Spinner'
import { TextField } from '../ui/TextField'

const DEBOUNCE_MS = 300

const UNAVAILABLE_MESSAGES: Record<QuoteUnavailableReason, string> = {
  CLOSED: 'Closed at these times — check the opening hours.',
  BLOCKED: 'Not available at these times.',
  NO_VEHICLE_SLOTS: 'No slots for this vehicle type.',
  FULLY_BOOKED: 'All slots are taken for these times.',
}

type Selection = { start: string; end: string; vehicle: VehicleType }

/** The window and vehicle from the URL (ISO instants), as picker values; the default window when absent. */
function initialSelection(params: URLSearchParams): Selection {
  const s = params.get('start')
  const e = params.get('end')
  const vehicle = params.get('vehicle')
  const start = s ? new Date(s) : null
  const end = e ? new Date(e) : null
  const fallback = defaultWindow()
  const usable = start && end && !Number.isNaN(start.getTime()) && !Number.isNaN(end.getTime())
  return {
    start: toLocalInputValue(usable ? start : fallback.start),
    end: toLocalInputValue(usable ? end : fallback.end),
    vehicle: vehicle === 'TWO_WHEELER' || vehicle === 'FOUR_WHEELER' ? vehicle : 'FOUR_WHEELER',
  }
}

/** ISO instants for a selection, or null while a picker holds no valid date. */
function toWindow(sel: Selection): { start: string; end: string } | null {
  const start = fromLocalInputValue(sel.start)
  const end = fromLocalInputValue(sel.end)
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return null
  return { start: start.toISOString(), end: end.toISOString() }
}

export function BookingCard({ listing }: { listing: PublicListingDto }) {
  const { user, loading: authLoading } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const [selection, setSelection] = useState(() => initialSelection(searchParams))
  /** The selection the quote is for: follows `selection` 300 ms after the last change. */
  const [settled, setSettled] = useState(selection)

  useEffect(() => {
    const t = setTimeout(() => setSettled(selection), DEBOUNCE_MS)
    return () => clearTimeout(t)
  }, [selection])

  const range = toWindow(settled)
  const { start, end } = range ?? {}
  const quote = useQuote(listing.id, start, end, settled.vehicle)

  // Keep the chosen times in the URL, so a refresh or a shared link shows the same quote.
  useEffect(() => {
    if (!start || !end) return
    setSearchParams(
      (prev) => {
        if (prev.get('start') === start && prev.get('end') === end && prev.get('vehicle') === settled.vehicle) return prev
        const next = new URLSearchParams(prev)
        next.set('start', start)
        next.set('end', end)
        next.set('vehicle', settled.vehicle)
        return next
      },
      { replace: true },
    )
  }, [start, end, settled.vehicle, setSearchParams])

  const change = (patch: Partial<Selection>) => setSelection((s) => ({ ...s, ...patch }))

  const answer = quote.data
  const problem = quote.isError ? toProblem(quote.error) : null
  const unavailable = answer !== undefined && !answer.available
  const canReserve = !authLoading && !unavailable && !problem

  function reserve() {
    const now = toWindow(selection)
    const next = now ? listingHref(listing.id, now.start, now.end, selection.vehicle) : location.pathname + location.search
    navigate(`/login?next=${encodeURIComponent(next)}`)
  }

  const prices = [
    ['hr', listing.pricePerHour],
    ['day', listing.pricePerDay],
    ['month', listing.pricePerMonth],
  ] as const

  return (
    <section
      aria-label="Book this spot"
      className="space-y-4 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900"
    >
      <ul className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        {prices.map(
          ([unit, value]) =>
            value !== null && (
              <li key={unit} className={unit === 'hr' ? 'text-xl font-bold text-brand-700 dark:text-brand-400' : 'text-sm text-slate-600 dark:text-slate-400'}>
                {`${formatINR(value)}/${unit}`}
              </li>
            ),
        )}
      </ul>

      <div className="grid gap-3">
        <TextField
          label="From"
          type="datetime-local"
          step={900}
          value={selection.start}
          onChange={(e) => change({ start: e.target.value })}
        />
        <TextField
          label="Until"
          type="datetime-local"
          step={900}
          value={selection.end}
          onChange={(e) => change({ end: e.target.value })}
        />
        <Select label="Vehicle" value={selection.vehicle} onChange={(e) => change({ vehicle: e.target.value as VehicleType })}>
          <option value="FOUR_WHEELER">{VEHICLE_TYPE_LABELS.FOUR_WHEELER}</option>
          <option value="TWO_WHEELER">{VEHICLE_TYPE_LABELS.TWO_WHEELER}</option>
        </Select>
      </div>

      <div aria-live="polite" className="min-h-16 text-sm">
        {!range ? (
          <p className="text-slate-600 dark:text-slate-400">Choose when you want to park.</p>
        ) : problem ? (
          <p role="alert" className="text-red-600 dark:text-red-400">
            {problem.code === 'INVALID_TIME_RANGE' ? problem.detail : "Couldn't get a price. Try again."}
          </p>
        ) : !answer ? (
          <p className="flex items-center gap-2 text-slate-600 dark:text-slate-400">
            <Spinner className="h-4 w-4" />
            Checking availability…
          </p>
        ) : answer.available ? (
          <div className="space-y-2">
            <p className="font-medium text-emerald-700 dark:text-emerald-400">
              {`${answer.freeSlots} ${answer.freeSlots === 1 ? 'slot' : 'slots'} free`}
            </p>
            <dl className="space-y-1">
              <div className="flex justify-between gap-3">
                <dt className="text-slate-600 dark:text-slate-400">{`Parking (${answer.quote.breakdown})`}</dt>
                <dd>{formatINR(answer.quote.baseAmount)}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-slate-600 dark:text-slate-400">Platform fee</dt>
                <dd>{formatINR(answer.quote.platformFee)}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-slate-600 dark:text-slate-400">GST on fee</dt>
                <dd>{formatINR(answer.quote.gstAmount)}</dd>
              </div>
              <div className="flex justify-between gap-3 border-t border-slate-200 pt-2 text-base font-semibold dark:border-slate-700">
                <dt>Total</dt>
                <dd>{formatINR(answer.quote.totalAmount)}</dd>
              </div>
            </dl>
          </div>
        ) : (
          <p className="text-amber-700 dark:text-amber-400">
            {answer.reason ? UNAVAILABLE_MESSAGES[answer.reason] : UNAVAILABLE_MESSAGES.BLOCKED}
          </p>
        )}
      </div>

      <div className="space-y-2">
        <Button
          type="button"
          className="w-full"
          disabled={!canReserve || user !== null}
          onClick={reserve}
        >
          Reserve
        </Button>
        {user?.role === 'DRIVER' && (
          <p className="text-center text-xs text-slate-600 dark:text-slate-400">Online booking opens in the next update.</p>
        )}
        {user && user.role !== 'DRIVER' && (
          <p className="text-center text-xs text-slate-600 dark:text-slate-400">Sign in as a driver to book.</p>
        )}
      </div>
    </section>
  )
}
