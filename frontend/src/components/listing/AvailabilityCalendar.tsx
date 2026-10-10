import clsx from 'clsx'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useState } from 'react'
import {
  AVAILABILITY_HORIZON_DAYS,
  DAY_LEVEL_LABELS,
  isClosedForToday,
  useAvailability,
  type DayAvailabilityDto,
  type DayLevel,
} from '../../lib/availability'
import { addDays, istDate, MONTH_NAMES, monthBounds, weekdayOf } from '../../lib/time'
import { Spinner } from '../ui/Spinner'

const WEEKDAYS = [
  ['Sun', 'Sunday'], ['Mon', 'Monday'], ['Tue', 'Tuesday'], ['Wed', 'Wednesday'], ['Thu', 'Thursday'], ['Fri', 'Friday'], ['Sat', 'Saturday'],
] as const

const LEVELS: DayLevel[] = ['AVAILABLE', 'LIMITED', 'FULL', 'CLOSED']

/** Colour says it at a glance; the symbol and the words say it for everyone else. */
const LEVEL_STYLE: Record<DayLevel, { cell: string; symbol: string }> = {
  AVAILABLE: { cell: 'bg-emerald-100 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-200', symbol: '✓' },
  LIMITED: { cell: 'bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200', symbol: '◐' },
  FULL: { cell: 'bg-red-100 text-red-900 dark:bg-red-950 dark:text-red-200', symbol: '✕' },
  CLOSED: { cell: 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400', symbol: '–' },
}

const IDLE = 'bg-transparent text-slate-400 dark:text-slate-600'

/**
 * A month of day-level availability for a listing: today through today + 90 days, one month at a time. Picking a
 * day that isn't closed calls `onPick`; past days, days beyond the horizon and closed days can't be picked.
 */
export function AvailabilityCalendar({
  listingId,
  onPick,
}: {
  listingId: number | string
  onPick: (day: DayAvailabilityDto) => void
}) {
  const today = istDate()
  const last = addDays(today, AVAILABILITY_HORIZON_DAYS)
  const thisMonth = monthBounds(today).first
  const lastMonth = monthBounds(last).first
  const [month, setMonth] = useState(thisMonth)
  const [picked, setPicked] = useState<string | null>(null)

  const { first, last: monthEnd } = monthBounds(month)
  const from = first > today ? first : today
  const to = monthEnd < last ? monthEnd : last
  const { data, isPending, error } = useAvailability(listingId, from, to)
  const byDate = new Map((data?.days ?? []).map((d) => [d.date, d]))

  const [year, monthNumber] = month.split('-').map(Number)
  const blanks = weekdayOf(first)
  const dayCount = Number(monthEnd.slice(8))
  const cells: (string | null)[] = [
    ...Array<null>(blanks).fill(null),
    ...Array.from({ length: dayCount }, (_, i) => `${month.slice(0, 7)}-${String(i + 1).padStart(2, '0')}`),
  ]
  const rows: (string | null)[][] = []
  for (let i = 0; i < cells.length; i += 7) {
    const row = cells.slice(i, i + 7)
    rows.push([...row, ...Array<null>(7 - row.length).fill(null)])
  }

  function step(delta: -1 | 1) {
    setMonth(monthBounds(addDays(delta === 1 ? monthEnd : first, delta)).first)
  }

  function dayCell(date: string) {
    const dayNumber = Number(date.slice(8))
    const spoken = `${dayNumber} ${MONTH_NAMES[monthNumber - 1]} ${year}`
    const day = byDate.get(date)
    if (date < today || date > last || !day) {
      const reason = date < today ? 'Past' : date > last ? 'Not open for booking yet' : error ? 'Unavailable' : 'Loading'
      return (
        <button type="button" disabled aria-label={`${spoken}, ${reason}`} className={clsx('h-11 w-full rounded-lg text-sm', IDLE)}>
          {dayNumber}
        </button>
      )
    }
    const style = LEVEL_STYLE[day.level]
    const closedToday = day.level !== 'CLOSED' && isClosedForToday(day)
    const closed = day.level === 'CLOSED' || closedToday
    return (
      <button
        type="button"
        disabled={closed}
        aria-label={`${spoken}, ${closedToday ? 'Closed for today' : DAY_LEVEL_LABELS[day.level]}`}
        onClick={() => {
          setPicked(date)
          onPick(day)
        }}
        className={clsx(
          'flex h-11 w-full flex-col items-center justify-center rounded-lg text-sm font-medium leading-none transition',
          'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-600',
          style.cell,
          closed ? 'cursor-not-allowed' : 'hover:brightness-95',
          picked === date && 'ring-2 ring-brand-600',
          date === today && 'font-bold underline',
        )}
      >
        <span>{dayNumber}</span>
        <span aria-hidden className="mt-0.5 text-[10px]">{style.symbol}</span>
      </button>
    )
  }

  return (
    <section aria-labelledby="availability-heading" className="space-y-3">
      <h2 id="availability-heading" className="text-lg font-semibold">Availability</h2>
      <div className="max-w-sm space-y-3">
        <div className="flex items-center justify-between gap-2">
          <button
            type="button"
            aria-label="Previous month"
            disabled={month <= thisMonth}
            onClick={() => step(-1)}
            className="rounded-lg p-2 text-slate-700 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-40 dark:text-slate-200 dark:hover:bg-slate-800"
          >
            <ChevronLeft aria-hidden className="h-5 w-5" />
          </button>
          <h3 aria-live="polite" className="flex items-center gap-2 font-semibold">
            {`${MONTH_NAMES[monthNumber - 1]} ${year}`}
            {isPending && !error && <Spinner className="h-4 w-4 text-brand-600" />}
          </h3>
          <button
            type="button"
            aria-label="Next month"
            disabled={month >= lastMonth}
            onClick={() => step(1)}
            className="rounded-lg p-2 text-slate-700 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-40 dark:text-slate-200 dark:hover:bg-slate-800"
          >
            <ChevronRight aria-hidden className="h-5 w-5" />
          </button>
        </div>
        {error && <p role="status" className="text-sm text-red-600 dark:text-red-400">Could not load availability.</p>}
        <table className="w-full table-fixed border-separate border-spacing-1">
          <thead>
            <tr>
              {WEEKDAYS.map(([short, long]) => (
                <th key={long} scope="col" abbr={long} className="pb-1 text-xs font-medium text-slate-500 dark:text-slate-400">{short}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={i}>
                {row.map((date, j) => (
                  <td key={j} className="p-0">{date && dayCell(date)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        <ul aria-label="Availability legend" className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-600 dark:text-slate-400">
          {LEVELS.map((level) => (
            <li key={level} className="flex items-center gap-1.5">
              <span aria-hidden className={clsx('inline-flex h-5 w-5 items-center justify-center rounded text-[10px]', LEVEL_STYLE[level].cell)}>
                {LEVEL_STYLE[level].symbol}
              </span>
              {DAY_LEVEL_LABELS[level]}
            </li>
          ))}
        </ul>
        <p role="status" className="text-xs text-slate-500 dark:text-slate-400">
          {picked
            ? `Booking card set to ${Number(picked.slice(8))} ${MONTH_NAMES[Number(picked.slice(5, 7)) - 1]}.`
            : 'Dates are in IST. Pick a day to fill in the booking card.'}
        </p>
      </div>
    </section>
  )
}
