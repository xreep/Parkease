import { keepPreviousData, useQuery, type QueryClient } from '@tanstack/react-query'
import { api } from './api'
import { invalidateBookingQueries } from './bookings'
import type { Page } from './owner'

export type DisputeCategory = 'NO_ACCESS' | 'SLOT_OCCUPIED' | 'OVERSTAY' | 'DAMAGE' | 'PAYMENT' | 'OTHER'
export type DisputeStatus = 'OPEN' | 'UNDER_REVIEW' | 'RESOLVED'
export type DisputeResolution = 'REFUND_FULL' | 'REFUND_PARTIAL' | 'NO_REFUND' | 'WARNING'

export const DISPUTE_CATEGORY_LABELS: Record<DisputeCategory, string> = {
  NO_ACCESS: 'No access',
  SLOT_OCCUPIED: 'Slot occupied',
  OVERSTAY: 'Overstay',
  DAMAGE: 'Damage',
  PAYMENT: 'Payment',
  OTHER: 'Other',
}

export const DISPUTE_STATUS_LABELS: Record<DisputeStatus, string> = {
  OPEN: 'Open',
  UNDER_REVIEW: 'Under review',
  RESOLVED: 'Resolved',
}

export const DISPUTE_RESOLUTION_LABELS: Record<DisputeResolution, string> = {
  REFUND_FULL: 'Full refund',
  REFUND_PARTIAL: 'Partial refund',
  NO_REFUND: 'No refund',
  WARNING: 'Warning issued',
}

export const DESCRIPTION_MIN = 10
export const DESCRIPTION_MAX = 2000
export const RESPONSE_MAX = 1000
export const NOTES_MAX = 1000
export const DISPUTES_PAGE_SIZE = 20

export type DisputeSummary = {
  id: number
  bookingId: number
  bookingCode: string
  listingTitle: string
  category: DisputeCategory
  status: DisputeStatus
  createdAt: string
  resolvedAt: string | null
}

/** `adminNotes` and `refundableRemaining` are only filled for admins. */
export type Dispute = DisputeSummary & {
  description: string
  raisedByName: string
  ownerResponse: string | null
  ownerRespondedAt: string | null
  resolution: DisputeResolution | null
  resolutionAmount: number | null
  adminNotes: string | null
  refundableRemaining: number | null
}

export type ResolveBody = { resolution: DisputeResolution; amount?: number; notes: string }

// Driver
export const raiseDispute = async (bookingId: number, body: { category: DisputeCategory; description: string }) =>
  (await api.post<Dispute>(`/bookings/${bookingId}/disputes`, body)).data
export const listMyDisputes = async (page: number) =>
  (await api.get<Page<DisputeSummary>>('/disputes', { params: { page, size: DISPUTES_PAGE_SIZE } })).data
export const getMyDispute = async (id: number) => (await api.get<Dispute>(`/disputes/${id}`)).data

// Owner
export const listOwnerDisputes = async (status: DisputeStatus | undefined, page: number) =>
  (await api.get<Page<DisputeSummary>>('/owner/disputes', { params: { ...(status && { status }), page, size: DISPUTES_PAGE_SIZE } })).data
export const getOwnerDispute = async (id: number) => (await api.get<Dispute>(`/owner/disputes/${id}`)).data
export const respondToDispute = async (id: number, response: string) =>
  (await api.post<Dispute>(`/owner/disputes/${id}/respond`, { response })).data

// Admin
export const listAdminDisputes = async (status: DisputeStatus | undefined, page: number) =>
  (await api.get<Page<DisputeSummary>>('/admin/disputes', { params: { ...(status && { status }), page, size: DISPUTES_PAGE_SIZE } })).data
export const getAdminDispute = async (id: number) => (await api.get<Dispute>(`/admin/disputes/${id}`)).data
export const reviewDispute = async (id: number) => (await api.post<Dispute>(`/admin/disputes/${id}/review`)).data
export const resolveDispute = async (id: number, body: ResolveBody) =>
  (await api.post<Dispute>(`/admin/disputes/${id}/resolve`, body)).data

export function useMyDisputes(page: number) {
  return useQuery({ queryKey: ['disputes', 'mine', page], queryFn: () => listMyDisputes(page), placeholderData: keepPreviousData })
}
export function useMyDispute(id: number | undefined) {
  return useQuery({ queryKey: ['disputes', 'one', id], queryFn: () => getMyDispute(id!), enabled: id !== undefined })
}
export function useOwnerDisputes(status: DisputeStatus | undefined, page: number) {
  return useQuery({
    queryKey: ['owner', 'disputes', status ?? null, page],
    queryFn: () => listOwnerDisputes(status, page),
    placeholderData: keepPreviousData,
  })
}
export function useOwnerDispute(id: number | undefined) {
  return useQuery({ queryKey: ['owner', 'dispute', id], queryFn: () => getOwnerDispute(id!), enabled: id !== undefined })
}
export function useAdminDisputes(status: DisputeStatus | undefined, page: number) {
  return useQuery({
    queryKey: ['admin', 'disputes', status ?? null, page],
    queryFn: () => listAdminDisputes(status, page),
    placeholderData: keepPreviousData,
  })
}
export function useAdminDispute(id: number | undefined) {
  return useQuery({ queryKey: ['admin', 'dispute', id], queryFn: () => getAdminDispute(id!), enabled: id !== undefined })
}

/** A new or changed dispute shows on the booking (driver, admin), in each role's list and on the owner's side. */
export function invalidateDisputes(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['disputes'] }),
    queryClient.invalidateQueries({ queryKey: ['owner', 'disputes'] }),
    queryClient.invalidateQueries({ queryKey: ['owner', 'dispute'] }),
    queryClient.invalidateQueries({ queryKey: ['admin', 'disputes'] }),
    queryClient.invalidateQueries({ queryKey: ['admin', 'dispute'] }),
    queryClient.invalidateQueries({ queryKey: ['admin', 'booking'] }),
    invalidateBookingQueries(queryClient),
  ]).then(() => undefined)
}

/** The advisory hourly price range for a city's tier. */
export type PriceGuideline = { tier: 1 | 2 | 3; minHourly: number; maxHourly: number }

export const getPricingGuideline = async (cityId: number) =>
  (await api.get<PriceGuideline>('/pricing-guidelines', { params: { cityId } })).data

export function usePricingGuideline(cityId: number | undefined) {
  return useQuery({
    queryKey: ['pricing-guideline', cityId],
    queryFn: () => getPricingGuideline(cityId!),
    enabled: cityId !== undefined,
    staleTime: 5 * 60_000,
  })
}
