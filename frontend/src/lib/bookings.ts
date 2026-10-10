import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { api } from './api'
import { blobProblem } from './download'
import type { DisputeSummary } from './disputes'
import { invalidateNotifications } from './notifications'
import type { CancellationPolicy, Page, VehicleType } from './owner'
import type { ReviewDto } from './reviews'
import type { PricingMode } from './search'

export type BookingStatus =
  | 'PENDING_PAYMENT'
  | 'AWAITING_APPROVAL'
  | 'CONFIRMED'
  | 'ACTIVE'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REJECTED'
  | 'EXPIRED'

export const BOOKING_STATUS_LABELS: Record<BookingStatus, string> = {
  PENDING_PAYMENT: 'Awaiting payment',
  AWAITING_APPROVAL: 'Waiting for owner',
  CONFIRMED: 'Confirmed',
  ACTIVE: 'Active',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  REJECTED: 'Declined',
  EXPIRED: 'Expired',
}

export type PaymentStatus = 'CREATED' | 'CAPTURED' | 'FAILED' | 'REFUNDED' | 'PARTIALLY_REFUNDED'
export type BookingActor = 'DRIVER' | 'OWNER' | 'SYSTEM' | 'ADMIN'

export type BookingSummaryDto = {
  id: number
  bookingCode: string
  status: BookingStatus
  listingId: number
  listingTitle: string
  cityName: string
  coverPhotoUrl: string | null
  startTime: string
  endTime: string
  vehicleType: VehicleType
  plateNumber: string
  totalAmount: number
  createdAt: string
}

export type BookingEventDto = {
  fromStatus: BookingStatus | null
  toStatus: BookingStatus
  actor: BookingActor
  note: string | null
  at: string
}

export type BookingDetailDto = BookingSummaryDto & {
  address: string
  lat: number
  lng: number
  slotLabel: string
  pricingMode: PricingMode
  pricingBreakdown: string
  baseAmount: number
  platformFee: number
  gstAmount: number
  refundAmount: number
  holdExpiresAt: string | null
  approvalDeadline: string | null
  confirmedAt: string | null
  cancelReason: string | null
  cancelledBy: BookingActor | null
  paymentStatus: PaymentStatus | null
  invoiceNumber: string | null
  autoApprove: boolean
  ownerFirstName: string
  events: BookingEventDto[]
  /** The driver can review it now (completed, not yet reviewed). */
  reviewable: boolean
  review: ReviewDto | null
  /** Problem reports raised for this booking. */
  disputes: DisputeSummary[]
  /** The driver could raise a problem report now. */
  disputable: boolean
}

/**
 * A booking as the listing's owner sees it: their share (`baseAmount`), what of it they still earn (`ownerNet`: null
 * without an earning, 0 once the booking was refunded away) and the driver's first name only.
 */
export type OwnerBookingDto = {
  id: number
  bookingCode: string
  status: BookingStatus
  listingId: number
  listingTitle: string
  slotLabel: string
  startTime: string
  endTime: string
  vehicleType: VehicleType
  plateNumber: string
  driverFirstName: string
  baseAmount: number
  ownerNet: number | null
  approvalDeadline: string | null
  createdAt: string
}

export type OwnerBookingView = 'requests' | 'upcoming' | 'past'

export type PaymentProvider = 'RAZORPAY' | 'MOCK'

export type CheckoutPayment = {
  provider: PaymentProvider
  orderId: string
  /** In paise. */
  amount: number
  currency: string
  keyId: string | null
  name: string
  description: string
  prefill: { name: string; email: string; contact: string | null }
}

export type CheckoutDto = { booking: BookingDetailDto; payment: CheckoutPayment }

export type BookingRequest = { listingId: number; vehicleId: number; start: string; end: string }
export type VerifyBody = { bookingId: number; orderId: string; paymentId: string; signature: string }
export type MockPayResponse = { orderId: string; paymentId: string; signature: string }
export type BookingView = 'upcoming' | 'active' | 'past' | 'cancelled' | 'all'

export const createBooking = async (body: BookingRequest) => (await api.post<CheckoutDto>('/bookings', body)).data
export const getCheckout = async (id: number | string) => (await api.get<CheckoutDto>(`/bookings/${id}/checkout`)).data
export const getBooking = async (id: number | string) => (await api.get<BookingDetailDto>(`/bookings/${id}`)).data
export const listBookings = async (view: BookingView, page = 0, size = 20) =>
  (await api.get<Page<BookingSummaryDto>>('/bookings', { params: { view, page, size } })).data
export const listOwnerBookings = async (view: OwnerBookingView, page = 0, size = 20) =>
  (await api.get<Page<OwnerBookingDto>>('/owner/bookings', { params: { view, page, size } })).data
export const approveBooking = async (id: number) =>
  (await api.post<OwnerBookingDto>(`/owner/bookings/${id}/approve`)).data
export const rejectBooking = async (id: number, reason: string) =>
  (await api.post<OwnerBookingDto>(`/owner/bookings/${id}/reject`, { reason })).data
/** Why not, or how much comes back: `reason` is set when `cancellable` is false, `policy` only when it decides the refund. */
export type CancellationPreview = {
  cancellable: boolean
  reason: string | null
  policy: CancellationPolicy | null
  refundPercent: number
  refundAmount: number
  nonRefundableAmount: number
  hoursBeforeStart: number
}

export const getCancellationPreview = async (id: number | string) =>
  (await api.get<CancellationPreview>(`/bookings/${id}/cancellation-preview`)).data
/** The reason is optional for a driver. */
export const cancelBooking = async (id: number, reason?: string) =>
  (await api.post<BookingDetailDto>(`/bookings/${id}/cancel`, reason ? { reason } : {})).data
/** An owner may only cancel a confirmed booking that has not started; the reason is required. */
export const ownerCancelBooking = async (id: number, reason: string) =>
  (await api.post<OwnerBookingDto>(`/owner/bookings/${id}/cancel`, { reason })).data
/** The receipt PDF. A failed blob request carries its problem as a Blob, so its detail is unwrapped here. */
export async function downloadReceipt(id: number | string): Promise<Blob> {
  try {
    return (await api.get<Blob>(`/bookings/${id}/receipt`, { responseType: 'blob' })).data
  } catch (error) {
    throw await blobProblem(error)
  }
}
export const verifyPayment = async (body: VerifyBody) => (await api.post<BookingDetailDto>('/payments/verify', body)).data
export const mockPay = async (bookingId: number) =>
  (await api.post<MockPayResponse>('/payments/mock/pay', { bookingId })).data

/**
 * Refetches everything a booking or a status change can affect: the driver's lists and details,
 * the driver's payments and stats, and the availability shown by quotes and search results. Checkouts are left alone: one is fixed
 * until it is paid or expires, and refetching it right after paying would only fail.
 */
export function invalidateBookingQueries(queryClient: QueryClient) {
  return Promise.all([
    ...['bookings', 'booking', 'quote', 'search', 'availability', 'driver'].map((key) => queryClient.invalidateQueries({ queryKey: [key] })),
    // Booking changes (a cancellation, an approval) leave a notification behind.
    invalidateNotifications(queryClient),
  ]).then(() => undefined)
}

export function useInvalidateBookings() {
  const queryClient = useQueryClient()
  return useCallback(() => invalidateBookingQueries(queryClient), [queryClient])
}

export function useOwnerBookings(view: OwnerBookingView, page = 0, size = 20) {
  return useQuery({
    queryKey: ['owner', 'bookings', view, page, size],
    queryFn: () => listOwnerBookings(view, page, size),
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[2] === view ? previous : undefined),
  })
}

export function invalidateOwnerBookings(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['owner', 'bookings'] }),
    // A decision also changes the dashboard figures, the earnings ledger and the slot calendar.
    ...['stats', 'earnings', 'calendar'].map((key) => queryClient.invalidateQueries({ queryKey: ['owner', key] })),
  ]).then(() => undefined)
}

/** Statuses that change without the driver doing anything (payment confirming, the owner replying). */
const IN_FLIGHT: BookingStatus[] = ['PENDING_PAYMENT', 'AWAITING_APPROVAL']
export const BOOKING_REFRESH_MS = 15_000

export function useBookings(view: BookingView, page = 0, size = 20, enabled = true) {
  return useQuery({
    queryKey: ['bookings', view, page, size],
    queryFn: () => listBookings(view, page, size),
    // Keep the old page visible while paging, but never another tab's list.
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[1] === view ? previous : undefined),
    enabled,
  })
}

/** `poll` refetches every 15 s while the booking is still waiting on payment or on the owner. */
export function useBooking(id: number | string | undefined, enabled = true, poll = false) {
  return useQuery({
    queryKey: ['booking', id],
    queryFn: () => getBooking(id!),
    enabled: enabled && id !== undefined,
    refetchInterval: poll
      ? (query) => (query.state.data && IN_FLIGHT.includes(query.state.data.status) ? BOOKING_REFRESH_MS : false)
      : false,
  })
}

/** What cancelling would refund right now. Never cached: the answer changes as the start time nears. */
export function useCancellationPreview(id: number | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['booking', id, 'cancellation-preview'],
    queryFn: () => getCancellationPreview(id!),
    enabled: enabled && id !== undefined,
    retry: false,
    staleTime: 0,
    gcTime: 0,
  })
}

export function useCheckout(id: number | string | undefined) {
  return useQuery({
    queryKey: ['checkout', id],
    queryFn: () => getCheckout(id!),
    enabled: id !== undefined,
    retry: false,
    // The hold is time-limited and the order is fixed: don't refetch behind the countdown, and don't
    // keep a checkout around once the page is left (it may be paid or expired by the time we return).
    staleTime: 60_000,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
}
