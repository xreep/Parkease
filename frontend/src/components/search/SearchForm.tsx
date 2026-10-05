import { useState, type FormEvent } from 'react'
import clsx from 'clsx'
import { useNavigate } from 'react-router-dom'
import { VEHICLE_TYPE_LABELS } from '../../lib/format'
import type { VehicleType } from '../../lib/owner'
import type { Place } from '../../lib/places'
import { toSearchParams, type SearchParams } from '../../lib/search'
import { defaultWindow, fromLocalInputValue, toLocalInputValue } from '../../lib/time'
import { Button } from '../ui/Button'
import { Select } from '../ui/Select'
import { TextField } from '../ui/TextField'
import { PlaceSearch } from './PlaceSearch'

type Props = {
  initial?: SearchParams
  /** Single-row layout for the search page header. */
  compact?: boolean
  /** Called with the validated search instead of navigating to /search. */
  onSubmit?: (params: SearchParams) => void
}

type Errors = { place?: string; start?: string; end?: string }

const MIN_MINUTES = 60

function initialWindow(initial?: SearchParams) {
  if (initial?.start && initial.end) {
    return { start: toLocalInputValue(new Date(initial.start)), end: toLocalInputValue(new Date(initial.end)) }
  }
  const w = defaultWindow()
  return { start: toLocalInputValue(w.start), end: toLocalInputValue(w.end) }
}

export function SearchForm({ initial, compact = false, onSubmit }: Props) {
  const navigate = useNavigate()
  const [place, setPlace] = useState<Place | null>(() =>
    initial ? { label: initial.place, lat: initial.lat, lng: initial.lng, kind: 'place' } : null,
  )
  const [window, setWindow] = useState(() => initialWindow(initial))
  const [vehicle, setVehicle] = useState<VehicleType>(initial?.vehicle ?? 'FOUR_WHEELER')
  const [errors, setErrors] = useState<Errors>({})

  function validate(): Errors {
    const errs: Errors = {}
    if (!place) errs.place = 'Choose a place from the list'
    const start = fromLocalInputValue(window.start)
    const end = fromLocalInputValue(window.end)
    if (Number.isNaN(start.getTime())) errs.start = 'Choose a start time'
    if (Number.isNaN(end.getTime())) errs.end = 'Choose an end time'
    if (!errs.start && !errs.end) {
      const minutes = (end.getTime() - start.getTime()) / 60_000
      if (minutes <= 0) errs.end = "'Until' must be after 'From'"
      else if (minutes < MIN_MINUTES) errs.end = 'Book at least 1 hour'
    }
    return errs
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    const errs = validate()
    setErrors(errs)
    if (Object.keys(errs).length > 0 || !place) return
    const params: SearchParams = {
      ...initial,
      place: place.label,
      lat: place.lat,
      lng: place.lng,
      start: fromLocalInputValue(window.start).toISOString(),
      end: fromLocalInputValue(window.end).toISOString(),
      vehicle,
      page: undefined,
    }
    if (onSubmit) onSubmit(params)
    else navigate({ pathname: '/search', search: `?${toSearchParams(params).toString()}` })
  }

  return (
    <form
      onSubmit={submit}
      noValidate
      className={clsx(
        'grid gap-4',
        compact ? 'items-start lg:grid-cols-[minmax(0,2fr)_repeat(3,minmax(0,1fr))_auto]' : 'sm:grid-cols-2',
      )}
    >
      <div className={clsx(!compact && 'sm:col-span-2')}>
        <PlaceSearch
          value={place}
          onChange={(p) => {
            setPlace(p)
            if (p) setErrors((er) => ({ ...er, place: undefined }))
          }}
          error={errors.place}
        />
      </div>
      <TextField
        label="From"
        type="datetime-local"
        step={900}
        value={window.start}
        error={errors.start}
        onChange={(e) => setWindow((w) => ({ ...w, start: e.target.value }))}
      />
      <TextField
        label="Until"
        type="datetime-local"
        step={900}
        value={window.end}
        error={errors.end}
        onChange={(e) => setWindow((w) => ({ ...w, end: e.target.value }))}
      />
      <Select label="Vehicle" value={vehicle} onChange={(e) => setVehicle(e.target.value as VehicleType)}>
        <option value="FOUR_WHEELER">{VEHICLE_TYPE_LABELS.FOUR_WHEELER}</option>
        <option value="TWO_WHEELER">{VEHICLE_TYPE_LABELS.TWO_WHEELER}</option>
      </Select>
      <Button type="submit" className={clsx(compact ? 'lg:mt-[1.65rem]' : 'sm:col-span-2')}>
        Search parking
      </Button>
    </form>
  )
}
