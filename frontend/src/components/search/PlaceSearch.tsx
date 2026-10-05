import { useEffect, useId, useState, type FocusEvent, type KeyboardEvent } from 'react'
import clsx from 'clsx'
import { MapPin } from 'lucide-react'
import { searchCities, searchNominatim, type Place } from '../../lib/places'

const DEBOUNCE_MS = 300
const MIN_PLACE_CHARS = 3

type Results = { cities: Place[]; places: Place[]; done: boolean }
const EMPTY: Results = { cities: [], places: [], done: false }

type Props = {
  value: Place | null
  onChange: (place: Place | null) => void
  label?: string
  placeholder?: string
  error?: string
}

export function PlaceSearch({
  value,
  onChange,
  label = 'Where are you going?',
  placeholder = 'City, area or landmark',
  error,
}: Props) {
  const id = useId()
  const listboxId = `${id}-listbox`
  const messageId = `${id}-message`
  const [text, setText] = useState(value?.label ?? '')
  /** The text the user typed and the lookup has not settled yet; null once a place is chosen. */
  const [typed, setTyped] = useState<string | null>(null)
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  const [results, setResults] = useState<Results>(EMPTY)

  // Keep the box in step when the chosen place is changed from outside (e.g. the URL).
  const valueLabel = value?.label
  const [syncedLabel, setSyncedLabel] = useState(valueLabel)
  if (valueLabel !== syncedLabel) {
    setSyncedLabel(valueLabel)
    if (valueLabel !== undefined) setText(valueLabel)
  }

  useEffect(() => {
    const q = typed?.trim() ?? ''
    if (!q) return
    const controller = new AbortController()
    const timer = setTimeout(async () => {
      const [cities, places] = await Promise.allSettled([
        searchCities(q, controller.signal),
        q.length >= MIN_PLACE_CHARS ? searchNominatim(q, controller.signal) : Promise.resolve([]),
      ])
      if (controller.signal.aborted) return
      setResults({
        cities: cities.status === 'fulfilled' ? cities.value : [],
        places: places.status === 'fulfilled' ? places.value : [],
        done: true,
      })
      setActive(-1)
    }, DEBOUNCE_MS)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [typed])

  const options = [...results.cities, ...results.places]
  const showList = open && typed !== null && typed.trim() !== ''
  const optionId = (i: number) => `${id}-option-${i}`

  function choose(place: Place) {
    setText(place.label)
    setTyped(null)
    setOpen(false)
    setActive(-1)
    onChange(place)
  }

  function handleInput(next: string) {
    setText(next)
    setTyped(next)
    setOpen(true)
    setResults((r) => (next.trim() ? { ...r, done: false } : EMPTY))
    setActive(-1)
    if (value) onChange(null)
  }

  function handleKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      if (options.length === 0) return
      e.preventDefault()
      setOpen(true)
      const down = e.key === 'ArrowDown'
      setActive((i) => (down ? (i + 1) % options.length : i <= 0 ? options.length - 1 : i - 1))
    } else if (e.key === 'Enter' && showList && active >= 0 && options[active]) {
      e.preventDefault()
      choose(options[active])
    } else if (e.key === 'Escape' && open) {
      e.preventDefault()
      setOpen(false)
      setActive(-1)
    }
  }

  function handleBlur(e: FocusEvent<HTMLDivElement>) {
    if (!e.currentTarget.contains(e.relatedTarget)) setOpen(false)
  }

  const optionRow = (place: Place, index: number) => (
    <li
      key={`${place.kind}-${place.label}-${place.lat}-${place.lng}`}
      id={optionId(index)}
      role="option"
      aria-selected={index === active}
      onMouseDown={(e) => e.preventDefault()}
      onMouseEnter={() => setActive(index)}
      onClick={() => choose(place)}
      className={clsx(
        'flex cursor-pointer items-center gap-2 px-3 py-2 text-sm',
        index === active ? 'bg-brand-50 text-brand-900 dark:bg-brand-950/60 dark:text-brand-100' : 'text-slate-800 dark:text-slate-200',
      )}
    >
      <MapPin aria-hidden className="h-4 w-4 shrink-0 text-slate-400" />
      <span className="min-w-0 break-words">{place.label}</span>
    </li>
  )

  const heading = (title: string) => (
    <li role="presentation" className="px-3 pt-2 pb-1 text-xs font-semibold tracking-wide text-slate-500 uppercase dark:text-slate-400">
      {title}
    </li>
  )

  return (
    <div className="relative space-y-1.5" onBlur={handleBlur}>
      <label htmlFor={id} className="block text-sm font-medium text-slate-700 dark:text-slate-300">
        {label}
      </label>
      <input
        id={id}
        type="text"
        role="combobox"
        autoComplete="off"
        aria-autocomplete="list"
        aria-expanded={showList}
        aria-controls={listboxId}
        aria-activedescendant={showList && active >= 0 ? optionId(active) : undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? messageId : undefined}
        value={text}
        placeholder={placeholder}
        onChange={(e) => handleInput(e.target.value)}
        onFocus={() => typed && setOpen(true)}
        onKeyDown={handleKeyDown}
        className={clsx(
          'block w-full rounded-lg border bg-white px-3 py-2.5 text-sm shadow-sm outline-none transition',
          'placeholder:text-slate-400 focus:ring-2 dark:bg-slate-900',
          error
            ? 'border-red-500 focus:ring-red-500/30'
            : 'border-slate-300 focus:border-brand-500 focus:ring-brand-500/30 dark:border-slate-700',
        )}
      />
      <ul
        id={listboxId}
        role="listbox"
        aria-label={label}
        hidden={!showList}
        className="absolute z-30 mt-1 max-h-72 w-full overflow-auto rounded-lg border border-slate-200 bg-white py-1 shadow-lg dark:border-slate-700 dark:bg-slate-900"
      >
        {results.cities.length > 0 && heading('Cities')}
        {results.cities.map((p, i) => optionRow(p, i))}
        {results.places.length > 0 && heading('Places')}
        {results.places.map((p, i) => optionRow(p, results.cities.length + i))}
        {options.length === 0 && (
          <li role="presentation" className="px-3 py-2 text-sm text-slate-500 dark:text-slate-400">
            {results.done ? 'No matches' : 'Searching…'}
          </li>
        )}
      </ul>
      {error && (
        <p id={messageId} className="text-sm text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  )
}
