const QUARTER_MS = 15 * 60 * 1000
const HOUR_MS = 60 * 60 * 1000

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

const pad = (n: number) => String(n).padStart(2, '0')

/** `YYYY-MM-DDTHH:mm` in local time, the value format of `<input type="datetime-local">`. */
export function toLocalInputValue(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** Parses a datetime-local value as local time. Invalid input gives an Invalid Date. */
export function fromLocalInputValue(s: string): Date {
  return new Date(s)
}

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

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
