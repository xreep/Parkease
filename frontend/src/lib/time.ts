const QUARTER_MS = 15 * 60 * 1000
const HOUR_MS = 60 * 60 * 1000
export const DAY_MS = 24 * HOUR_MS

/** The next quarter hour strictly after `now` (exactly 10:15:00.000 gives 10:30). */
export function nextQuarter(now: Date = new Date()): Date {
  return new Date((Math.floor(now.getTime() / QUARTER_MS) + 1) * QUARTER_MS)
}

/** The quarter hour that `now` falls in (10:07 gives 10:00; exactly 10:15:00.000 stays 10:15). */
export function currentQuarter(now: Date = new Date()): Date {
  return new Date(Math.floor(now.getTime() / QUARTER_MS) * QUARTER_MS)
}

/** Next quarter hour to two hours later. */
export function defaultWindow(now: Date = new Date()): { start: Date; end: Date } {
  const start = nextQuarter(now)
  return { start, end: new Date(start.getTime() + 2 * HOUR_MS) }
}

/** Opening hours are in IST; this says whether the device zone is already IST. */
export function browserIsIst(): boolean {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
  return zone === 'Asia/Kolkata' || zone === 'Asia/Calcutta'
}

/** Shown when the device zone differs: pickers use the device zone, opening hours are in IST. */
export const IST_HINT = 'Times use your device’s time zone. Opening hours are shown in IST.'

const pad = (n: number) => String(n).padStart(2, '0')

/** `YYYY-MM-DDTHH:mm` in local time, the value format of `<input type="datetime-local">`. */
export function toLocalInputValue(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** Parses a datetime-local value as local time. Invalid input gives an Invalid Date. */
export function fromLocalInputValue(s: string): Date {
  return new Date(s)
}

export const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December',
]
const MONTHS = MONTH_NAMES.map((m) => m.slice(0, 3))
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

const dayLabel = (d: Date) => `${WEEKDAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]}`

function timeLabel(d: Date): string {
  const h = d.getHours() % 12 || 12
  return `${h}:${pad(d.getMinutes())} ${d.getHours() < 12 ? 'am' : 'pm'}`
}

const sameDay = (a: Date, b: Date) =>
  a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()

/** "Tue 6 Oct, 10:00 am – 12:00 pm", or with the end day too when the window spans days. */
export function formatWindow(startIso: string, endIso: string): string {
  const start = new Date(startIso)
  const end = new Date(endIso)
  const from = `${dayLabel(start)}, ${timeLabel(start)}`
  return sameDay(start, end) ? `${from} – ${timeLabel(end)}` : `${from} – ${dayLabel(end)}, ${timeLabel(end)}`
}

const unit = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`

/** 120 gives "2 hours", 1620 gives "1 day 3 hours", 45 gives "45 minutes". */
export function durationLabel(minutes: number): string {
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  const mins = Math.round(minutes % 60)
  const parts = [
    ...(days ? [unit(days, 'day')] : []),
    ...(hours ? [unit(hours, 'hour')] : []),
    ...(mins ? [unit(mins, 'minute')] : []),
  ]
  return parts.length ? parts.join(' ') : '0 minutes'
}

const MINUTE_MS = 60_000
const RELATIVE_HOUR_MS = 60 * MINUTE_MS

const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()

/** "Just now", "5 min ago", "3 hr ago", "Yesterday", "3 days ago", then a plain date after a week. */
export function formatRelativeTime(iso: string, now: number = Date.now()): string {
  const then = new Date(iso)
  const diff = Math.max(0, now - then.getTime())
  if (diff < MINUTE_MS) return 'Just now'
  if (diff < RELATIVE_HOUR_MS) return `${Math.floor(diff / MINUTE_MS)} min ago`
  const days = Math.round((startOfDay(new Date(now)) - startOfDay(then)) / DAY_MS)
  if (days === 1) return 'Yesterday'
  if (days < 1) return `${Math.floor(diff / RELATIVE_HOUR_MS)} hr ago`
  if (days < 7) return `${days} days ago`
  return `${then.getDate()} ${MONTHS[then.getMonth()]} ${then.getFullYear()}`
}

// India has no daylight saving, so IST is a fixed +05:30 offset.
const IST_OFFSET_MS = 5.5 * HOUR_MS

/** `YYYY-MM-DD` of the given instant on the IST calendar. */
export function istDate(now: Date = new Date()): string {
  return new Date(now.getTime() + IST_OFFSET_MS).toISOString().slice(0, 10)
}

/** `YYYY-MM-DD` shifted by whole days (pure calendar arithmetic). */
export function addDays(date: string, days: number): string {
  return new Date(new Date(`${date}T00:00:00Z`).getTime() + days * DAY_MS).toISOString().slice(0, 10)
}

/** The instant at which `HH:mm` on an IST calendar day happens. */
export function istInstant(date: string, time: string): Date {
  return new Date(`${date}T${time.slice(0, 5)}:00+05:30`)
}

/** 0 = Sunday .. 6 = Saturday, for a `YYYY-MM-DD` calendar day. */
export function weekdayOf(date: string): number {
  return new Date(`${date}T00:00:00Z`).getUTCDay()
}


/** The `YYYY-MM-DD` of the 1st and the last day of the month holding `date`. */
export function monthBounds(date: string): { first: string; last: string } {
  const first = `${date.slice(0, 7)}-01`
  const [y, m] = first.split('-').map(Number)
  const last = new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10)
  return { first, last }
}

/** "5 Oct" for a `YYYY-MM-DD` calendar day. */
export function formatShortDate(date: string): string {
  return `${Number(date.slice(8))} ${MONTHS[Number(date.slice(5, 7)) - 1]}`
}
