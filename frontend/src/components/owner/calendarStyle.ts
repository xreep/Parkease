import type { BookingStatus } from '../../lib/bookings'

/**
 * Booking bars on the owner calendar. Every pair is explicit for light and dark mode and keeps the text at 4.5:1 or
 * better against its bar (checked in `lib/contrast.test.ts`): white on a deep colour in light mode, a pale bar with a
 * dark text in dark mode.
 */
export const STATUS_STYLE: Partial<Record<BookingStatus, string>> = {
  CONFIRMED: 'bg-emerald-700 text-white dark:bg-emerald-300 dark:text-emerald-950',
  ACTIVE: 'bg-sky-700 text-white dark:bg-sky-300 dark:text-sky-950',
  AWAITING_APPROVAL: 'bg-amber-500 text-slate-900 dark:bg-amber-300 dark:text-slate-900',
  COMPLETED: 'bg-slate-600 text-white dark:bg-slate-300 dark:text-slate-900',
}

/** The calendar only receives live bookings (confirmed, waiting, active, completed); anything else gets a neutral bar. */
export const FALLBACK_STYLE = 'bg-slate-400 text-slate-900'
