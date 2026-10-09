import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from './api'
import type { BookingStatus, OwnerBookingDto } from './bookings'
import { blobProblem, filenameFrom, saveBlob } from './download'
import type { Page } from './owner'
import { addDays, istDate } from './time'

export type OwnerStatsDto = {
  from: string
  to: string
  totals: {
    earningsNet: number
    bookings: number
    cancellations: number
    occupancyPercent: number
    avgRating: number
    reviewCount: number
  }
  balances: { held: number; pendingPayout: number; paid: number }
  pendingApprovals: number
  upcoming: OwnerBookingDto[]
  series: { date: string; earningsNet: number; bookings: number }[]
}

export type EarningStatus = 'HELD' | 'PENDING_PAYOUT' | 'PAID' | 'REVERSED'

export const EARNING_STATUS_LABELS: Record<EarningStatus, string> = {
  HELD: 'Held',
  PENDING_PAYOUT: 'Pending payout',
  PAID: 'Paid',
  REVERSED: 'Reversed',
}

export type OwnerEarningDto = {
  id: number
  bookingId: number
  bookingCode: string
  listingTitle: string
  startTime: string
  endTime: string
  gross: number
  commission: number
  net: number
  status: EarningStatus
  paidAt: string | null
  payoutReference: string | null
}

export type EarningsFilters = { status?: EarningStatus; from?: string; to?: string }

export type OwnerEarningsDto = {
  totals: { held: number; pendingPayout: number; paid: number; reversedCount: number }
  earnings: Page<OwnerEarningDto>
}

export type OwnerCalendarDto = {
  listingId: number
  from: string
  to: string
  slots: { id: number; label: string }[]
  bookings: {
    id: number
    bookingCode: string
    slotId: number
    startTime: string
    endTime: string
    status: BookingStatus
    driverName: string
  }[]
  blocks: { id: number; slotId: number | null; startTime: string; endTime: string; reason: string | null }[]
}

export const STATS_RANGES = [7, 30, 90] as const
export type StatsRange = (typeof STATS_RANGES)[number]
export const EARNINGS_PAGE_SIZE = 20

/** The API takes IST dates; a range of n days ends today and includes it. */
export function statsRange(days: number, now: Date = new Date()): { from: string; to: string } {
  const to = istDate(now)
  return { from: addDays(to, -(days - 1)), to }
}

export const getOwnerStats = async (from: string, to: string) =>
  (await api.get<OwnerStatsDto>('/owner/stats', { params: { from, to } })).data

const earningsParams = (filters: EarningsFilters) => ({
  ...(filters.status && { status: filters.status }),
  ...(filters.from && { from: filters.from }),
  ...(filters.to && { to: filters.to }),
})

export const getOwnerEarnings = async (filters: EarningsFilters, page = 0, size = EARNINGS_PAGE_SIZE) =>
  (await api.get<OwnerEarningsDto>('/owner/earnings', { params: { ...earningsParams(filters), page, size } })).data

/** Every row for the filters as a CSV; the server names the file. */
export async function downloadEarningsCsv(filters: EarningsFilters): Promise<void> {
  try {
    const response = await api.get<Blob>('/owner/earnings', {
      params: { ...earningsParams(filters), format: 'csv' },
      responseType: 'blob',
    })
    saveBlob(response.data, filenameFrom(response.headers['content-disposition']) ?? 'earnings.csv')
  } catch (error) {
    throw await blobProblem(error)
  }
}

export const getOwnerCalendar = async (listingId: number, from: string, to: string) =>
  (await api.get<OwnerCalendarDto>('/owner/calendar', { params: { listingId, from, to } })).data

export function useOwnerStats(days: StatsRange) {
  const { from, to } = statsRange(days)
  return useQuery({
    queryKey: ['owner', 'stats', from, to],
    queryFn: () => getOwnerStats(from, to),
    placeholderData: keepPreviousData,
  })
}

export function useOwnerEarnings(filters: EarningsFilters, page: number) {
  return useQuery({
    queryKey: ['owner', 'earnings', filters.status ?? null, filters.from ?? null, filters.to ?? null, page],
    queryFn: () => getOwnerEarnings(filters, page),
    placeholderData: keepPreviousData,
  })
}

export function useOwnerCalendar(listingId: number | undefined, from: string, to: string) {
  return useQuery({
    queryKey: ['owner', 'calendar', listingId, from, to],
    queryFn: () => getOwnerCalendar(listingId!, from, to),
    enabled: listingId !== undefined,
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[2] === listingId ? previous : undefined),
  })
}
