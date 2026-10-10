import clsx from 'clsx'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { BOOKING_STATUS_LABELS, type BookingStatus } from '../../lib/bookings'
import { errorMessage } from '../../lib/errors'
import { useMyListings } from '../../lib/owner'
import { FALLBACK_STYLE, STATUS_STYLE } from '../../components/owner/calendarStyle'
import { useOwnerCalendar, type OwnerCalendarDto } from '../../lib/ownerDashboard'
import { addDays, DAY_MS, formatShortDate, istDate, istInstant, weekdayOf } from '../../lib/time'
import { usePageTitle } from '../../lib/usePageTitle'

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
/** The API serves at most 14 days; a week is shown at a time. */
const WEEK_DAYS = 7

const LEGEND: BookingStatus[] = ['CONFIRMED', 'ACTIVE', 'AWAITING_APPROVAL', 'COMPLETED']

const HATCHED =
  'bg-[repeating-linear-gradient(135deg,rgb(148_163_184/0.55)_0,rgb(148_163_184/0.55)_4px,transparent_4px,transparent_8px)] border border-slate-400 text-slate-700 dark:text-slate-200'

/** The Monday of the IST week holding `date`. */
const mondayOf = (date: string) => addDays(date, -((weekdayOf(date) + 6) % 7))

type Segment = { left: number; width: number }

/** The part of [start, end) inside the IST day, as percentages of the day; null when they don't meet. */
function segmentOn(day: string, startIso: string, endIso: string): Segment | null {
  const dayStart = istInstant(day, '00:00').getTime()
  const from = Math.max(new Date(startIso).getTime(), dayStart)
  const to = Math.min(new Date(endIso).getTime(), dayStart + DAY_MS)
  if (to <= from) return null
  return { left: ((from - dayStart) / DAY_MS) * 100, width: ((to - from) / DAY_MS) * 100 }
}

const place = (s: Segment) => ({ left: `${s.left}%`, width: `${s.width}%` })

const clock = (iso: string) =>
  new Intl.DateTimeFormat('en-IN', { timeZone: 'Asia/Kolkata', hour: 'numeric', minute: '2-digit', hour12: true }).format(new Date(iso))

function DayCell({ day, slotId, calendar }: { day: string; slotId: number; calendar: OwnerCalendarDto }) {
  return (
    <td className="relative h-11 border-l border-slate-200 p-0 dark:border-slate-800">
      {calendar.blocks
        .filter((b) => b.slotId === null || b.slotId === slotId)
        .map((b) => {
          const seg = segmentOn(day, b.startTime, b.endTime)
          if (!seg) return null
          const label = b.reason ? `Blocked: ${b.reason}` : 'Blocked'
          return (
            <div
              key={`block-${b.id}`}
              data-block
              title={`${label} · ${clock(b.startTime)} – ${clock(b.endTime)}`}
              style={place(seg)}
              className={clsx('absolute inset-y-0.5 overflow-hidden rounded px-1 text-[10px] leading-8', HATCHED)}
            >
              <span className="sr-only">{label}</span>
            </div>
          )
        })}
      {calendar.bookings
        .filter((b) => b.slotId === slotId)
        .map((b) => {
          const seg = segmentOn(day, b.startTime, b.endTime)
          if (!seg) return null
          return (
            <div
              key={`booking-${b.id}`}
              data-booking
              data-status={b.status}
              title={`${b.bookingCode} · ${b.driverName} · ${BOOKING_STATUS_LABELS[b.status]} · ${clock(b.startTime)} – ${clock(b.endTime)}`}
              style={{ ...place(seg), minWidth: '1.25rem' }}
              className={clsx(
                'absolute inset-y-1 overflow-hidden whitespace-nowrap rounded px-1 text-[10px] font-semibold leading-8',
                STATUS_STYLE[b.status] ?? FALLBACK_STYLE,
              )}
            >
              {b.bookingCode}
              <span className="sr-only">{`, ${BOOKING_STATUS_LABELS[b.status]}, ${b.driverName}`}</span>
            </div>
          )
        })}
    </td>
  )
}

function Grid({ calendar, days, today }: { calendar: OwnerCalendarDto; days: string[]; today: string }) {
  if (calendar.slots.length === 0) {
    return (
      <div className="rounded-2xl border border-dashed border-slate-300 p-10 text-center dark:border-slate-700">
        <p className="text-slate-600 dark:text-slate-400">This listing has no slots yet.</p>
      </div>
    )
  }
  return (
    <div className="overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-800">
      <table className="w-full min-w-[44rem] table-fixed border-collapse text-sm">
        <caption className="sr-only">Slots by day</caption>
        <thead className="bg-slate-50 text-xs text-slate-600 dark:bg-slate-900 dark:text-slate-400">
          <tr>
            <th scope="col" className="sticky left-0 z-10 w-20 bg-slate-50 px-2 py-2 text-left font-medium dark:bg-slate-900">Slot</th>
            {days.map((d) => (
              <th key={d} scope="col" className={clsx('px-1 py-2 font-medium', d === today && 'text-brand-700 dark:text-brand-400')}>
                {`${WEEKDAYS[weekdayOf(d)]} ${Number(d.slice(8))}`}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {calendar.slots.map((slot) => (
            <tr key={slot.id} className="border-t border-slate-200 dark:border-slate-800">
              <th scope="row" className="sticky left-0 z-10 w-20 bg-white px-2 py-2 text-left font-medium dark:bg-slate-950">{slot.label}</th>
              {days.map((d) => (
                <DayCell key={d} day={d} slotId={slot.id} calendar={calendar} />
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Legend() {
  return (
    <ul aria-label="Calendar legend" className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-600 dark:text-slate-400">
      {LEGEND.map((status) => (
        <li key={status} className="flex items-center gap-1.5">
          <span aria-hidden className={clsx('h-3 w-5 rounded', STATUS_STYLE[status])} />
          {BOOKING_STATUS_LABELS[status]}
        </li>
      ))}
      <li className="flex items-center gap-1.5">
        <span aria-hidden className={clsx('h-3 w-5 rounded', HATCHED)} />
        Blocked
      </li>
    </ul>
  )
}

export function OwnerCalendarPage() {
  usePageTitle('Owner calendar')
  const listings = useMyListings(0, 100)
  const [picked, setPicked] = useState<number | null>(null)
  const today = istDate()
  const [weekStart, setWeekStart] = useState(() => mondayOf(today))

  const options = listings.data?.content ?? []
  const listingId = picked ?? options[0]?.id
  const weekEnd = addDays(weekStart, WEEK_DAYS - 1)
  const days = Array.from({ length: WEEK_DAYS }, (_, i) => addDays(weekStart, i))
  const { data, error, isPending, isPlaceholderData } = useOwnerCalendar(listingId, weekStart, weekEnd)

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Calendar</h2>

      {listings.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner className="h-8 w-8 text-brand-600" />
        </div>
      ) : listings.error ? (
        <FormError message={errorMessage(listings.error)} />
      ) : options.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 p-10 text-center dark:border-slate-700">
          <p className="text-slate-600 dark:text-slate-400">Add a listing to see its calendar.</p>
          <Link to="/owner/listings/new" className="mt-3 inline-block font-semibold text-brand-700 hover:underline dark:text-brand-400">Add a listing</Link>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-end justify-between gap-3">
            <Select label="Listing" value={listingId} onChange={(e) => setPicked(Number(e.target.value))}>
              {options.map((l) => (
                <option key={l.id} value={l.id}>{l.title}</option>
              ))}
            </Select>
            <div className="flex flex-wrap items-center gap-2">
              <Button type="button" variant="secondary" className="px-2.5" aria-label="Previous week" onClick={() => setWeekStart(addDays(weekStart, -WEEK_DAYS))}>
                <ChevronLeft aria-hidden className="h-4 w-4" />
              </Button>
              <p aria-live="polite" className="text-center text-sm font-medium sm:min-w-40">
                {`${formatShortDate(weekStart)} – ${formatShortDate(weekEnd)} ${weekEnd.slice(0, 4)}`}
              </p>
              <Button type="button" variant="secondary" className="px-2.5" aria-label="Next week" onClick={() => setWeekStart(addDays(weekStart, WEEK_DAYS))}>
                <ChevronRight aria-hidden className="h-4 w-4" />
              </Button>
              <Button type="button" variant="ghost" onClick={() => setWeekStart(mondayOf(today))}>This week</Button>
            </div>
          </div>

          {isPending ? (
            <div className="flex justify-center py-12">
              <Spinner className="h-8 w-8 text-brand-600" />
            </div>
          ) : error && !data ? (
            <FormError message={errorMessage(error)} />
          ) : data ? (
            <div className={clsx('space-y-3 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
              <Grid calendar={data} days={days} today={today} />
              <Legend />
              <p className="text-xs text-slate-500 dark:text-slate-400">All times are in IST.</p>
            </div>
          ) : null}
        </>
      )}
    </div>
  )
}
