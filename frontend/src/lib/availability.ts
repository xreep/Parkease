import { useQuery } from '@tanstack/react-query'
import { api } from './api'
import { istInstant, nextQuarter } from './time'

export type DayLevel = 'AVAILABLE' | 'LIMITED' | 'FULL' | 'CLOSED'

export type DayAvailabilityDto = {
  /** YYYY-MM-DD, an IST calendar day. */
  date: string
  level: DayLevel
  openTime: string | null
  closeTime: string | null
  bookedPercent: number
}

export type AvailabilityDto = { listingId: number; days: DayAvailabilityDto[] }

export const DAY_LEVEL_LABELS: Record<DayLevel, string> = {
  AVAILABLE: 'Available',
  LIMITED: 'Limited',
  FULL: 'Full',
  CLOSED: 'Closed',
}

/** How far ahead a driver can book, and so how far the calendar goes. */
export const AVAILABILITY_HORIZON_DAYS = 90

export const getAvailability = async (listingId: number | string, from: string, to: string) =>
  (await api.get<AvailabilityDto>(`/listings/${listingId}/availability`, { params: { from, to } })).data

export function useAvailability(listingId: number | string, from: string, to: string) {
  return useQuery({
    queryKey: ['availability', String(listingId), from, to],
    queryFn: () => getAvailability(listingId, from, to),
    staleTime: 60_000,
  })
}

const TWO_HOURS_MS = 2 * 60 * 60 * 1000

/**
 * The booking window to suggest for a picked day: from opening time (or the next quarter hour, if that has passed)
 * for two hours, but never past the closing time.
 */
export function windowForDay(day: DayAvailabilityDto, now: Date = new Date()): { start: Date; end: Date } {
  const open = istInstant(day.date, day.openTime ?? '00:00')
  const earliest = nextQuarter(now)
  const start = open.getTime() < earliest.getTime() ? earliest : open
  let end = new Date(start.getTime() + TWO_HOURS_MS)
  if (day.closeTime) {
    const close = istInstant(day.date, day.closeTime)
    if (close.getTime() > start.getTime() && close.getTime() < end.getTime()) end = close
  }
  return { start, end }
}
