import { useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../../auth/AuthProvider'
import { createBooking, invalidateBookingQueries } from '../../lib/bookings'
import { toProblem } from '../../lib/errors'
import { formatINR, VEHICLE_TYPE_LABELS } from '../../lib/format'
import {
  listingHref,
  useQuote,
  type PublicListingDto,
  type QuoteUnavailableReason,
  type VehicleType,
} from '../../lib/search'
import { browserIsIst, defaultWindow, fromLocalInputValue, IST_HINT, toLocalInputValue } from '../../lib/time'
import { useVehicles, type VehicleDto } from '../../lib/vehicles'
import { VehicleForm } from '../vehicles/VehicleForm'
import { Button } from '../ui/Button'
import { Dialog } from '../ui/Dialog'
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

const RESERVE_ERRORS: Record<string, string> = {
  SLOT_UNAVAILABLE: 'Sorry, that slot was just taken. Try different times.',
  TOO_MANY_HOLDS: 'You have unpaid reservations. Complete or wait for them to expire.',
}

type Selection = { start: string; end: string; vehicle: VehicleType }

/** The window and vehicle from the URL (ISO instants), as picker values; the default window when absent. */
function initialSelection(params: URLSearchParams, listing: PublicListingDto): Selection {
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
    vehicle:
      vehicle === 'TWO_WHEELER' || vehicle === 'FOUR_WHEELER'
        ? vehicle
        : listing.slotSummary.fourWheeler > 0 ? 'FOUR_WHEELER' : 'TWO_WHEELER',
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
  const isDriver = user?.role === 'DRIVER'
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const [selection, setSelection] = useState(() => initialSelection(searchParams, listing))
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

  const change = (patch: Partial<Selection>) =>
    setSelection((s) => (Object.entries(patch).every(([k, v]) => s[k as keyof Selection] === v) ? s : { ...s, ...patch }))

  const vehicles = useVehicles(isDriver)
  const ofType = useMemo(
    () => (vehicles.data ?? []).filter((v) => v.type === selection.vehicle),
    [vehicles.data, selection.vehicle],
  )
  /** The driver's pick, if it still fits the vehicle type; otherwise the default (or first) vehicle of that type. */
  const [pickedId, setPickedId] = useState<number | null>(null)
  const vehicle: VehicleDto | undefined = ofType.find((v) => v.id === pickedId) ?? ofType.find((v) => v.isDefault) ?? ofType[0]
  /** Whether the add-vehicle dialog is open, and whether saving should go on to reserve. */
  const [addingVehicle, setAddingVehicle] = useState<'reserve' | 'only' | null>(null)
  const [reserving, setReserving] = useState(false)
  const [reserveError, setReserveError] = useState<string | null>(null)

  const inFlight = useRef(false)

  // With no vehicle type in the URL, start from the type of the driver's default vehicle (the
  // listing-based default stays when they have no vehicles). The vehicle select only offers
  // vehicles of the selected type, so it can never disagree with the type select after that.
  const urlHadVehicle = useRef(searchParams.get('vehicle') !== null)
  const typeTouched = useRef(false)
  const typeInitialised = useRef(false)
  useEffect(() => {
    if (!vehicles.data || typeInitialised.current) return
    typeInitialised.current = true
    if (urlHadVehicle.current || typeTouched.current || vehicles.data.length === 0) return
    const preferred = vehicles.data.find((v) => v.isDefault) ?? vehicles.data[0]
    setSelection((s) => (s.vehicle === preferred.type ? s : { ...s, vehicle: preferred.type }))
  }, [vehicles.data])

  /** The driver has vehicles, just none of the selected type. */
  const missingType = isDriver && (vehicles.data?.length ?? 0) > 0 && ofType.length === 0
  const typeName = VEHICLE_TYPE_LABELS[selection.vehicle].toLowerCase()

  const answer = quote.data
  const problem = quote.isError ? toProblem(quote.error) : null
  const unavailable = answer !== undefined && !answer.available
  // The price on screen is for `settled`; only reserve once it matches what the pickers show.
  const canReserve = !authLoading && !unavailable && !problem && selection === settled && range !== null

  function reserve() {
    if (!isDriver) {
      const now = toWindow(selection)
      const next = now ? listingHref(listing.id, now.start, now.end, selection.vehicle) : location.pathname + location.search
      navigate(`/login?next=${encodeURIComponent(next)}`)
      return
    }
    setReserveError(null)
    if (vehicles.isError) {
      setReserveError("Couldn't load your vehicles. Try again.")
      void vehicles.refetch()
    } else if (!vehicle) setAddingVehicle('reserve')
    else void book(vehicle)
  }

  async function book(chosen: VehicleDto) {
    const when = toWindow(selection)
    if (!when || inFlight.current) return
    inFlight.current = true
    setReserving(true)
    try {
      const checkout = await createBooking({ listingId: listing.id, vehicleId: chosen.id, start: when.start, end: when.end })
      void invalidateBookingQueries(queryClient)
      queryClient.setQueryData(['checkout', String(checkout.booking.id)], checkout)
      navigate(`/checkout/${checkout.booking.id}`)
    } catch (error) {
      const p = toProblem(error)
      setReserveError(RESERVE_ERRORS[p.code] ?? p.detail)
      if (p.code === 'SLOT_UNAVAILABLE') void queryClient.invalidateQueries({ queryKey: ['quote', listing.id] })
      inFlight.current = false
      setReserving(false)
    }
  }

  function vehicleAdded(added: VehicleDto) {
    const thenReserve = addingVehicle === 'reserve'
    setAddingVehicle(null)
    setPickedId(added.id)
    if (added.type !== selection.vehicle) {
      // A different type than the quote is for: show its price first.
      change({ vehicle: added.type })
      return
    }
    if (thenReserve) void book(added)
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
        {!browserIsIst() && <p className="-mt-1 text-xs text-slate-500">{IST_HINT}</p>}
        <Select
          label={isDriver ? 'Vehicle type' : 'Vehicle'}
          value={selection.vehicle}
          onChange={(e) => {
            typeTouched.current = true
            setPickedId(null)
            change({ vehicle: e.target.value as VehicleType })
          }}
        >
          <option value="FOUR_WHEELER">{VEHICLE_TYPE_LABELS.FOUR_WHEELER}</option>
          <option value="TWO_WHEELER">{VEHICLE_TYPE_LABELS.TWO_WHEELER}</option>
        </Select>
        {isDriver && vehicle && (
          <Select label="Vehicle" value={vehicle.id} onChange={(e) => setPickedId(Number(e.target.value))}>
            {ofType.map((v) => (
              <option key={v.id} value={v.id}>
                {`${v.plateNumber}${v.makeModel ? ` · ${v.makeModel}` : ''}`}
              </option>
            ))}
          </Select>
        )}
        {missingType && (
          <div className="space-y-2 rounded-lg bg-amber-50 p-3 text-sm text-amber-800 dark:bg-amber-950/40 dark:text-amber-300">
            <p>{`You have no ${typeName} saved — add one or switch vehicle type`}</p>
            <Button type="button" variant="secondary" onClick={() => setAddingVehicle('only')}>{`Add a ${typeName}`}</Button>
          </div>
        )}
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
          loading={reserving}
          disabled={!canReserve || (user !== null && !isDriver) || (isDriver && (vehicles.isPending || missingType))}
          onClick={reserve}
        >
          Reserve
        </Button>
        {reserveError && (
          <p role="alert" className="text-center text-sm text-red-600 dark:text-red-400">{reserveError}</p>
        )}
        {user && !isDriver && (
          <p className="text-center text-xs text-slate-600 dark:text-slate-400">Sign in as a driver to book.</p>
        )}
      </div>
      <Dialog open={addingVehicle !== null} title="Add your vehicle" onClose={() => setAddingVehicle(null)}>
        <div className="space-y-4">
          <p className="text-sm text-slate-600 dark:text-slate-400">Tell us which vehicle you'll park and we'll reserve your spot.</p>
          <VehicleForm
            defaultType={selection.vehicle}
            submitLabel="Add vehicle"
            showDefault={false}
            onCancel={() => setAddingVehicle(null)}
            onSaved={vehicleAdded}
          />
        </div>
      </Dialog>
    </section>
  )
}
