import { useEffect, useRef, useState } from 'react'
import { AMENITY_LABELS, LISTING_TYPE_LABELS } from '../../lib/format'
import { activeFilterCount, NO_FILTERS, RADIUS_OPTIONS, type Filters } from '../../lib/searchFilters'
import { Button } from '../ui/Button'
import { Dialog } from '../ui/Dialog'
import { Select } from '../ui/Select'
import { TextField } from '../ui/TextField'

const PRICE_DEBOUNCE_MS = 500

const toggle = <T,>(list: T[], item: T): T[] => (list.includes(item) ? list.filter((x) => x !== item) : [...list, item])

function CheckboxGroup<T extends string>({
  legend,
  options,
  selected,
  onToggle,
}: {
  legend: string
  options: Record<T, string>
  selected: T[]
  onToggle: (value: T) => void
}) {
  return (
    <fieldset className="min-w-0 space-y-1.5">
      <legend className="text-sm font-medium text-slate-700 dark:text-slate-300">{legend}</legend>
      {(Object.keys(options) as T[]).map((key) => (
        <label key={key} className="flex items-center gap-2 py-0.5 text-sm">
          <input
            type="checkbox"
            checked={selected.includes(key)}
            onChange={() => onToggle(key)}
            className="h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500"
          />
          {options[key]}
        </label>
      ))}
    </fieldset>
  )
}

type PanelProps = {
  value: Filters
  onChange: (patch: Partial<Filters>) => void
  onClear: () => void
  /** Shows an "Apply filters" button (the mobile drawer); without it every change is live. */
  onApply?: () => void
}

export function FiltersPanel({ value, onChange, onClear, onApply }: PanelProps) {
  const [priceText, setPriceText] = useState(value.maxPrice?.toString() ?? '')

  // Follow the value when it is changed from outside (Clear filters, the URL).
  const [syncedMax, setSyncedMax] = useState(value.maxPrice)
  if (value.maxPrice !== syncedMax) {
    setSyncedMax(value.maxPrice)
    setPriceText(value.maxPrice?.toString() ?? '')
  }

  /** Empty clears the limit; anything that is not a positive number is left alone until fixed. */
  function commitPrice(text: string) {
    const trimmed = text.trim()
    const n = trimmed === '' ? undefined : Number(trimmed)
    if (n !== undefined && !(Number.isFinite(n) && n > 0)) return
    if (n !== value.maxPrice) onChange({ maxPrice: n })
  }

  // Typing applies after a short pause; blur and Enter apply at once.
  const commitRef = useRef(commitPrice)
  useEffect(() => {
    commitRef.current = commitPrice
  })
  useEffect(() => {
    const timer = setTimeout(() => commitRef.current(priceText), PRICE_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [priceText])

  const radiusOptions = RADIUS_OPTIONS.includes(value.radius as (typeof RADIUS_OPTIONS)[number])
    ? [...RADIUS_OPTIONS]
    : [...RADIUS_OPTIONS, value.radius].sort((a, b) => a - b)

  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <Select label="Distance" value={value.radius} onChange={(e) => onChange({ radius: Number(e.target.value) })}>
          {radiusOptions.map((km) => (
            <option key={km} value={km}>{`${km} km`}</option>
          ))}
        </Select>
        <TextField
          label="Max price per hour"
          type="number"
          inputMode="numeric"
          min={1}
          step={1}
          placeholder="No limit"
          value={priceText}
          onChange={(e) => setPriceText(e.target.value)}
          onBlur={() => commitPrice(priceText)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              commitPrice(priceText)
            }
          }}
        />
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <CheckboxGroup
          legend="Parking type"
          options={LISTING_TYPE_LABELS}
          selected={value.types}
          onToggle={(t) => onChange({ types: toggle(value.types, t) })}
        />
        <CheckboxGroup
          legend="Amenities"
          options={AMENITY_LABELS}
          selected={value.amenities}
          onToggle={(a) => onChange({ amenities: toggle(value.amenities, a) })}
        />
      </div>
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={value.open24x7}
          onChange={(e) => onChange({ open24x7: e.target.checked })}
          className="h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500"
        />
        Open 24 × 7 only
      </label>
      <div className="flex flex-wrap gap-2">
        {onApply && <Button type="button" onClick={onApply}>Apply filters</Button>}
        <Button type="button" variant="secondary" disabled={activeFilterCount(value) === 0} onClick={onClear}>
          Clear filters
        </Button>
      </div>
    </div>
  )
}

function DrawerBody({ value, onApply, onClose }: { value: Filters; onApply: (f: Filters) => void; onClose: () => void }) {
  const [draft, setDraft] = useState(value)
  return (
    <div className="max-h-[70vh] overflow-y-auto pr-1">
      <FiltersPanel
        value={draft}
        onChange={(patch) => setDraft((d) => ({ ...d, ...patch }))}
        onClear={() => setDraft(NO_FILTERS)}
        onApply={() => {
          onApply(draft)
          onClose()
        }}
      />
    </div>
  )
}

/** Mobile filters: edits stay in a draft until "Apply filters". The body remounts on every open. */
export function FiltersDrawer({
  open,
  onClose,
  value,
  onApply,
}: {
  open: boolean
  onClose: () => void
  value: Filters
  onApply: (f: Filters) => void
}) {
  return (
    <Dialog open={open} title="Filters" onClose={onClose}>
      <DrawerBody value={value} onApply={onApply} onClose={onClose} />
    </Dialog>
  )
}
