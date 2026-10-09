import { keepPreviousData, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { api } from './api'
import type { Page, VehicleType } from './owner'
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
}

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
export type BookingView = 'upcoming' | 'past'

export const createBooking = async (body: BookingRequest) => (await api.post<CheckoutDto>('/bookings', body)).data
export const getCheckout = async (id: number | string) => (await api.get<CheckoutDto>(`/bookings/${id}/checkout`)).data
export const getBooking = async (id: number | string) => (await api.get<BookingDetailDto>(`/bookings/${id}`)).data
export const listBookings = async (view: BookingView, page = 0, size = 20) =>
  (await api.get<Page<BookingSummaryDto>>('/bookings', { params: { view, page, size } })).data
export const verifyPayment = async (body: VerifyBody) => (await api.post<BookingDetailDto>('/payments/verify', body)).data
export const mockPay = async (bookingId: number) =>
  (await api.post<MockPayResponse>('/payments/mock/pay', { bookingId })).data

/**
 * Refetches everything a booking or a status change can affect: the driver's lists and details,
 * and the availability shown by quotes and search results.
 */
export function invalidateBookingQueries(queryClient: QueryClient) {
  return Promise.all(
    ['bookings', 'booking', 'checkout', 'quote', 'search'].map((key) => queryClient.invalidateQueries({ queryKey: [key] })),
  ).then(() => undefined)
}

export function useInvalidateBookings() {
  const queryClient = useQueryClient()
  return useCallback(() => invalidateBookingQueries(queryClient), [queryClient])
}

export function useBookings(view: BookingView, page = 0, size = 20, enabled = true) {
  return useQuery({
    queryKey: ['bookings', view, page, size],
    queryFn: () => listBookings(view, page, size),
    placeholderData: keepPreviousData,
    enabled,
  })
}

export function useBooking(id: number | string | undefined, enabled = true) {
  return useQuery({
    queryKey: ['booking', id],
    queryFn: () => getBooking(id!),
    enabled: enabled && id !== undefined,
  })
}

export function useCheckout(id: number | string | undefined) {
  return useQuery({
    queryKey: ['checkout', id],
    queryFn: () => getCheckout(id!),
    enabled: id !== undefined,
    retry: false,
    // The hold is time-limited and the order is fixed: never silently refetch behind the countdown.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })
}
