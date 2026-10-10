import { keepPreviousData, useQuery, type QueryClient } from '@tanstack/react-query'
import { invalidateAdminActivity } from './admin'
import { api } from './api'
import type { BookingEventDto, BookingStatus, PaymentProvider, PaymentStatus } from './bookings'
import { invalidateBookingQueries } from './bookings'
import { downloadCsv } from './download'
import { errorMessage, toProblem } from './errors'
import type { DisputeSummary } from './disputes'
import type { Page } from './owner'
import type { OwnerEarningDto } from './ownerDashboard'
import type { BookingActor } from './bookings'
import type { ReviewDto } from './reviews'

export const ADMIN_PAGE_SIZE = 20

/** Messages for the codes an admin action can answer with; anything else shows the server's own message. */
const CODE_MESSAGES: Record<string, string> = {
  CANNOT_SUSPEND: 'Admins and your own account can’t be suspended.',
  NOT_RETRYABLE: 'This refund can’t be retried — it has already been processed or is still in progress.',
  NOTHING_TO_PAY: 'There is nothing to pay: those earnings are no longer pending payout.',
  EARNING_DISPUTED: 'Some of those earnings are held for an open dispute. Resolve it first.',
}

export function adminErrorMessage(error: unknown): string {
  return CODE_MESSAGES[toProblem(error).code] ?? errorMessage(error)
}

/** Keeps a mapped message when it travels through a dialog, which shows `error.message`. */
export function mapAdminError(error: unknown): Error {
  return new Error(adminErrorMessage(error))
}

const clean = <T extends Record<string, unknown>>(params: T) =>
  Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== '')) as Partial<T>

// ---- Users ----

export type UserRole = 'DRIVER' | 'OWNER' | 'ADMIN'
export type UserStatus = 'ACTIVE' | 'SUSPENDED'

export type AdminUser = {
  id: number
  firstName: string
  lastName: string
  email: string
  phone: string | null
  role: UserRole
  status: UserStatus
  emailVerified: boolean
  createdAt: string
  bookingsCount: number
  listingsCount: number
}

export type UserFilters = { role?: UserRole; status?: UserStatus; q?: string }

export const listUsers = async (filters: UserFilters, page: number) =>
  (await api.get<Page<AdminUser>>('/admin/users', { params: { ...clean(filters), page, size: ADMIN_PAGE_SIZE } })).data
export const suspendUser = async (id: number, reason: string) =>
  (await api.post<AdminUser>(`/admin/users/${id}/suspend`, { reason })).data
export const activateUser = async (id: number) => (await api.post<AdminUser>(`/admin/users/${id}/activate`)).data

export function useAdminUsers(filters: UserFilters, page: number) {
  return useQuery({
    queryKey: ['admin', 'users', filters.role ?? null, filters.status ?? null, filters.q ?? null, page],
    queryFn: () => listUsers(filters, page),
    placeholderData: keepPreviousData,
  })
}

// ---- Reviews ----

export type AdminReview = ReviewDto & {
  listingId: number
  listingTitle: string
  bookingCode: string
  hidden: boolean
  hiddenReason: string | null
}

export type ReviewFilters = { hidden?: boolean; q?: string }

export const listAdminReviews = async (filters: ReviewFilters, page: number) =>
  (await api.get<Page<AdminReview>>('/admin/reviews', { params: { ...clean(filters), page, size: ADMIN_PAGE_SIZE } })).data
export const hideReview = async (id: number, reason: string) =>
  (await api.post<AdminReview>(`/admin/reviews/${id}/hide`, { reason })).data
export const unhideReview = async (id: number) => (await api.post<AdminReview>(`/admin/reviews/${id}/unhide`)).data

export function useAdminReviews(filters: ReviewFilters, page: number) {
  return useQuery({
    queryKey: ['admin', 'reviews', filters.hidden ?? null, filters.q ?? null, page],
    queryFn: () => listAdminReviews(filters, page),
    placeholderData: keepPreviousData,
  })
}

// ---- Locations ----

export type AdminState = {
  id: number
  name: string
  code: string
  slug: string
  type: 'STATE' | 'UT'
  capitalName: string
  citiesCount: number
}

export type CityTier = 1 | 2 | 3

export type AdminCity = {
  id: number
  stateId: number
  stateName: string
  name: string
  slug: string
  lat: number
  lng: number
  capital: boolean
  tier: CityTier
  active: boolean
  listingsCount: number
}

export type CityBody = { name: string; lat: number; lng: number; tier: CityTier; active: boolean }

export const CITY_TIER_LABELS: Record<CityTier, string> = {
  1: 'Tier 1 · Metro',
  2: 'Tier 2 · Capital or large city',
  3: 'Tier 3 · Other',
}

export type StateBody = { name: string; type: AdminState['type']; capitalName: string }

export const createState = async (body: StateBody & { code: string }) => (await api.post<AdminState>('/admin/states', body)).data
export const updateState = async (id: number, body: StateBody) => (await api.patch<AdminState>(`/admin/states/${id}`, body)).data

export const getAdminStates = async () => (await api.get<AdminState[]>('/admin/states')).data
export const listAdminCities = async (stateId: number, page: number) =>
  (await api.get<Page<AdminCity>>('/admin/cities', { params: { stateId, page, size: 100 } })).data
export const createCity = async (stateId: number, body: CityBody) =>
  (await api.post<AdminCity>('/admin/cities', { stateId, ...body })).data
export const updateCity = async (id: number, body: CityBody) => (await api.patch<AdminCity>(`/admin/cities/${id}`, body)).data

export function useAdminStates() {
  return useQuery({ queryKey: ['admin', 'states'], queryFn: getAdminStates })
}

export function useAdminCities(stateId: number | undefined, page: number) {
  return useQuery({
    queryKey: ['admin', 'cities', stateId, page],
    queryFn: () => listAdminCities(stateId!, page),
    enabled: stateId !== undefined,
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[2] === stateId ? previous : undefined),
  })
}

/** City changes also reach the public location lists and the states' counts. */
export function invalidateLocations(queryClient: QueryClient) {
  return Promise.all([
    ...[['admin', 'cities'], ['admin', 'states'], ['states'], ['state'], ['city']].map((queryKey) => queryClient.invalidateQueries({ queryKey })),
    invalidateAdminActivity(queryClient),
  ]).then(() => undefined)
}

// ---- Bookings ----

export type AdminBookingSummary = {
  id: number
  bookingCode: string
  status: BookingStatus
  listingTitle: string
  cityName: string
  driverName: string
  driverEmail: string
  ownerName: string
  startTime: string
  endTime: string
  totalAmount: number
  refundAmount: number
  paymentStatus: PaymentStatus | null
  createdAt: string
}

export type AdminDisputeSummary = DisputeSummary

export type RefundStatus = 'PENDING' | 'PROCESSED' | 'FAILED'

export type AdminBookingDetail = AdminBookingSummary & {
  listingId: number
  slotLabel: string
  /** What a cancellation would still refund: paid minus refunded, 0 when nothing was paid. */
  refundableRemaining: number
  baseAmount: number
  platformFee: number
  gstAmount: number
  cancelReason: string | null
  cancelledBy: BookingActor | null
  payment: {
    id: number
    provider: PaymentProvider
    providerOrderId: string
    providerPaymentId: string | null
    status: PaymentStatus
    amount: number
    capturedAt: string | null
  } | null
  refunds: {
    id: number
    amount: number
    status: RefundStatus
    attempts: number
    providerRefundId: string | null
    reason: string
    createdAt: string
  }[]
  events: BookingEventDto[]
  disputes: AdminDisputeSummary[]
}

export type AdminBookingFilters = { status?: BookingStatus; q?: string; cityId?: number; from?: string; to?: string }

export const listAdminBookings = async (filters: AdminBookingFilters, page: number) =>
  (await api.get<Page<AdminBookingSummary>>('/admin/bookings', { params: { ...clean(filters), page, size: ADMIN_PAGE_SIZE } })).data
export const getAdminBooking = async (id: number) => (await api.get<AdminBookingDetail>(`/admin/bookings/${id}`)).data
export const adminCancelBooking = async (id: number, reason: string) =>
  (await api.post<AdminBookingDetail>(`/admin/bookings/${id}/cancel`, { reason })).data

export function useAdminBookings(filters: AdminBookingFilters, page: number) {
  return useQuery({
    queryKey: ['admin', 'bookings', filters.status ?? null, filters.q ?? null, filters.cityId ?? null, filters.from ?? null, filters.to ?? null, page],
    queryFn: () => listAdminBookings(filters, page),
    placeholderData: keepPreviousData,
  })
}

export function useAdminBooking(id: number | undefined) {
  return useQuery({ queryKey: ['admin', 'booking', id], queryFn: () => getAdminBooking(id!), enabled: id !== undefined })
}

/** A booking that is still running its course (the admin can cancel it). */
export const CANCELLABLE_STATUSES: BookingStatus[] = ['PENDING_PAYMENT', 'AWAITING_APPROVAL', 'CONFIRMED', 'ACTIVE']

export function invalidateAdminBookings(queryClient: QueryClient) {
  return Promise.all([
    ...['bookings', 'booking', 'payments', 'refunds', 'payouts'].map((key) => queryClient.invalidateQueries({ queryKey: ['admin', key] })),
    invalidateAdminActivity(queryClient),
    invalidateBookingQueries(queryClient),
  ]).then(() => undefined)
}

// ---- Payments and refunds ----

export type AdminPayment = {
  id: number
  bookingId: number
  bookingCode: string
  driverEmail: string
  provider: PaymentProvider
  providerOrderId: string
  providerPaymentId: string | null
  status: PaymentStatus
  amount: number
  refundAmount: number
  createdAt: string
  capturedAt: string | null
}

export type AdminRefund = {
  id: number
  paymentId: number
  bookingId: number
  bookingCode: string
  amount: number
  status: RefundStatus
  attempts: number
  providerRefundId: string | null
  reason: string
  createdAt: string
  lastError: string | null
}

export type PaymentFilters = { status?: PaymentStatus; q?: string; from?: string; to?: string }

export const listAdminPayments = async (filters: PaymentFilters, page: number) =>
  (await api.get<Page<AdminPayment>>('/admin/payments', { params: { ...clean(filters), page, size: ADMIN_PAGE_SIZE } })).data
export const listAdminRefunds = async (status: RefundStatus | undefined, page: number) =>
  (await api.get<Page<AdminRefund>>('/admin/refunds', { params: { ...clean({ status }), page, size: ADMIN_PAGE_SIZE } })).data
export const retryRefund = async (id: number) => (await api.post<AdminRefund>(`/admin/refunds/${id}/retry`)).data

export function useAdminPayments(filters: PaymentFilters, page: number) {
  return useQuery({
    queryKey: ['admin', 'payments', filters.status ?? null, filters.q ?? null, filters.from ?? null, filters.to ?? null, page],
    queryFn: () => listAdminPayments(filters, page),
    placeholderData: keepPreviousData,
  })
}

export function useAdminRefunds(status: RefundStatus | undefined, page: number) {
  return useQuery({
    queryKey: ['admin', 'refunds', status ?? null, page],
    queryFn: () => listAdminRefunds(status, page),
    placeholderData: keepPreviousData,
  })
}

// ---- Payouts ----

export type PayoutOwner = {
  ownerId: number
  ownerName: string
  ownerEmail: string
  pendingAmount: number
  earningsCount: number
  payoutMethod: 'UPI' | 'BANK' | null
  payoutMasked: string | null
  /** Pending earnings held back by open disputes. */
  disputedAmount: number
}

/** A pending earning; `disputed` ones can't be paid out until the dispute is resolved. */
export type PayoutEarning = OwnerEarningDto & { disputed: boolean }

export type MarkPaidBody = { ownerId: number; earningIds: number[]; reference: string }
export type MarkPaidResult = { paidCount: number; paidAmount: number }

export const listPayouts = async () => (await api.get<PayoutOwner[]>('/admin/payouts')).data
export const listPayoutEarnings = async (ownerId: number) =>
  (await api.get<PayoutEarning[]>(`/admin/payouts/${ownerId}/earnings`)).data
export const markPaid = async (body: MarkPaidBody) => (await api.post<MarkPaidResult>('/admin/payouts/mark-paid', body)).data

/** The pending payouts as a CSV (the server names the file); `truncated` when it cut the export at its row limit. */
export const downloadPayoutsCsv = () => downloadCsv('/admin/payouts', {}, 'payouts.csv')

export function useAdminPayouts() {
  return useQuery({ queryKey: ['admin', 'payouts'], queryFn: listPayouts })
}

export function usePayoutEarnings(ownerId: number | undefined) {
  return useQuery({
    queryKey: ['admin', 'payout-earnings', ownerId],
    queryFn: () => listPayoutEarnings(ownerId!),
    enabled: ownerId !== undefined,
  })
}
