import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from './api'
import type { PaymentStatus } from './bookings'
import type { Page } from './owner'

export type DriverPaymentDto = {
  id: number
  bookingId: number
  bookingCode: string
  listingTitle: string
  amount: number
  status: PaymentStatus
  refundAmount: number
  paidAt: string | null
  invoiceNumber: string | null
  receiptAvailable: boolean
}

export type DriverStatsDto = {
  totalBookings: number
  completedBookings: number
  /** Captured minus refunded. */
  amountSpent: number
  hoursParked: number
  pendingReviews: number
}

export const PAYMENTS_PAGE_SIZE = 20

export const PAYMENT_STATUS_LABELS: Record<PaymentStatus, string> = {
  CREATED: 'Pending',
  CAPTURED: 'Paid',
  FAILED: 'Failed',
  REFUNDED: 'Refunded',
  PARTIALLY_REFUNDED: 'Partially refunded',
}

export const getPayments = async (page = 0, size = PAYMENTS_PAGE_SIZE) =>
  (await api.get<Page<DriverPaymentDto>>('/me/payments', { params: { page, size } })).data
export const getDriverStats = async () => (await api.get<DriverStatsDto>('/me/stats')).data

export function usePayments(page: number) {
  return useQuery({
    queryKey: ['driver', 'payments', page],
    queryFn: () => getPayments(page),
    placeholderData: keepPreviousData,
  })
}

export function useDriverStats() {
  return useQuery({ queryKey: ['driver', 'stats'], queryFn: getDriverStats })
}
