import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from './api'
import { blobProblem, filenameFrom, saveBlob } from './download'
import { statsRange, type StatsRange } from './ownerDashboard'
import type { ListingDetail, ListingStatus, DocumentType, Page, SignedUrl, VerificationStatus, ListingSummary } from './owner'

export type AdminOwner = {
  userId: number
  name: string
  email: string
  phone: string | null
  verificationStatus: VerificationStatus
  documentType: DocumentType | null
  hasDocument: boolean
  documentSubmittedAt: string | null
  rejectionReason: string | null
  verifiedAt: string | null
  hasPayoutDetails: boolean
  listingCount: number
}

export type AdminListingSummary = ListingSummary & {
  ownerId: number
  ownerName: string
  ownerEmail: string
  submittedAt: string | null
}

export type AdminListingDetail = {
  listing: ListingDetail
  owner: { id: number; name: string; email: string; phone: string | null; verificationStatus: VerificationStatus }
}

export type QueueCounts = { pendingOwners: number; pendingListings: number }

export const getQueues = async () => (await api.get<QueueCounts>('/admin/queues')).data
export const listOwners = async (status: VerificationStatus, page: number) =>
  (await api.get<Page<AdminOwner>>('/admin/owners', { params: { status, page, size: 20 } })).data
export const getOwnerDocumentUrl = async (userId: number) =>
  (await api.get<SignedUrl>(`/admin/owners/${userId}/document-url`)).data
export const verifyOwner = async (userId: number) => (await api.post<AdminOwner>(`/admin/owners/${userId}/verify`)).data
export const rejectOwner = async (userId: number, reason: string) =>
  (await api.post<AdminOwner>(`/admin/owners/${userId}/reject`, { reason })).data

export const listListings = async (status: ListingStatus, page: number) =>
  (await api.get<Page<AdminListingSummary>>('/admin/listings', { params: { status, page, size: 20 } })).data
export const getAdminListing = async (id: number) => (await api.get<AdminListingDetail>(`/admin/listings/${id}`)).data
export const approveListing = async (id: number) =>
  (await api.post<AdminListingDetail>(`/admin/listings/${id}/approve`)).data
export const rejectListing = async (id: number, reason: string) =>
  (await api.post<AdminListingDetail>(`/admin/listings/${id}/reject`, { reason })).data

export function useQueues() {
  return useQuery({ queryKey: ['admin', 'queues'], queryFn: getQueues })
}

export function useAdminOwners(status: VerificationStatus, page: number) {
  return useQuery({ queryKey: ['admin', 'owners', status, page], queryFn: () => listOwners(status, page) })
}

export function useAdminListings(status: ListingStatus, page: number) {
  return useQuery({ queryKey: ['admin', 'listings', status, page], queryFn: () => listListings(status, page) })
}

export function useAdminListing(id: number | undefined) {
  return useQuery({ queryKey: ['admin', 'listing', id], queryFn: () => getAdminListing(id!), enabled: id !== undefined })
}

// ---- Overview, reports, settings and audit log ----

export type AdminStatsDto = {
  from: string
  to: string
  users: { drivers: number; owners: number; newDrivers: number; newOwners: number; suspended: number }
  listings: { approved: number; pendingReview: number; suspended: number; paused: number }
  bookings: { created: number; confirmed: number; conversionPercent: number; cancelled: number; utilizationPercent: number }
  money: { gmv: number; platformRevenue: number; refunds: number; ownerEarnings: number }
  topStates: { stateId: number; name: string; bookings: number; gmv: number }[]
  topCities: { cityId: number; name: string; stateName: string; bookings: number; gmv: number }[]
  series: { date: string; bookings: number; gmv: number; revenue: number }[]
}

export const getAdminStats = async (from: string, to: string) =>
  (await api.get<AdminStatsDto>('/admin/stats', { params: { from, to } })).data

export function useAdminStats(days: StatsRange) {
  const { from, to } = statsRange(days)
  return useQuery({
    queryKey: ['admin', 'stats', from, to],
    queryFn: () => getAdminStats(from, to),
    placeholderData: keepPreviousData,
  })
}

export type ReportKind = 'usage' | 'revenue'

export type UsageRow = {
  cityId: number
  cityName: string
  stateName: string
  listings: number
  slots: number
  bookings: number
  bookedHours: number
  utilizationPercent: number
  cancellations: number
}

export type RevenueRow = {
  cityId: number
  cityName: string
  stateName: string
  bookings: number
  gmv: number
  platformFees: number
  gst: number
  refunds: number
  ownerEarnings: number
}

export type ReportDto<Row, Totals = Omit<Row, 'cityId' | 'cityName' | 'stateName'>> = {
  from: string
  to: string
  rows: Row[]
  totals: Totals
}

export type UsageReport = ReportDto<UsageRow>
export type RevenueReport = ReportDto<RevenueRow>

export type ReportFilters = { from: string; to: string; stateId?: number; cityId?: number }

const reportParams = (filters: ReportFilters) => ({
  from: filters.from,
  to: filters.to,
  ...(filters.stateId !== undefined && { stateId: filters.stateId }),
  ...(filters.cityId !== undefined && { cityId: filters.cityId }),
})

export const getReport = async <K extends ReportKind>(kind: K, filters: ReportFilters) =>
  (await api.get<K extends 'usage' ? UsageReport : RevenueReport>(`/admin/reports/${kind}`, { params: reportParams(filters) })).data

/** The report as a CSV (the server names the file); `truncated` when it cut the export at its row limit. */
export async function downloadReportCsv(kind: ReportKind, filters: ReportFilters): Promise<{ truncated: boolean }> {
  try {
    const response = await api.get<Blob>(`/admin/reports/${kind}`, {
      params: { ...reportParams(filters), format: 'csv' },
      responseType: 'blob',
    })
    saveBlob(response.data, filenameFrom(response.headers['content-disposition']) ?? `${kind}-report.csv`)
    return { truncated: String(response.headers['x-truncated']).toLowerCase() === 'true' }
  } catch (error) {
    throw await blobProblem(error)
  }
}

export function useReport<K extends ReportKind>(kind: K, filters: ReportFilters, enabled: boolean) {
  return useQuery({
    queryKey: ['admin', 'report', kind, filters.from, filters.to, filters.stateId ?? null, filters.cityId ?? null],
    queryFn: () => getReport(kind, filters),
    enabled,
    placeholderData: keepPreviousData,
  })
}

export type PriceGuideline = { tier: 1 | 2 | 3; minHourly: number; maxHourly: number }

export type PlatformSettings = {
  platformFeePercent: number
  gstPercent: number
  holdMinutes: number
  approvalHours: number
  requestMinLeadMinutes: number
  priceGuidelines: PriceGuideline[]
}

export const getSettings = async () => (await api.get<PlatformSettings>('/admin/settings')).data
export const saveSettings = async (settings: PlatformSettings) =>
  (await api.put<PlatformSettings>('/admin/settings', settings)).data

export function useAdminSettings() {
  return useQuery({ queryKey: ['admin', 'settings'], queryFn: getSettings })
}

export type AdminAction = {
  id: number
  adminName: string
  action: string
  targetType: string
  targetId: number | string
  details: string | null
  createdAt: string
}

export type AuditFilters = { action?: string; targetType?: string }
export const AUDIT_PAGE_SIZE = 20

export const listAudit = async (filters: AuditFilters, page: number) =>
  (await api.get<Page<AdminAction>>('/admin/audit', {
    params: {
      ...(filters.action && { action: filters.action }),
      ...(filters.targetType && { targetType: filters.targetType }),
      page,
      size: AUDIT_PAGE_SIZE,
    },
  })).data

export function useAdminAudit(filters: AuditFilters, page: number) {
  return useQuery({
    queryKey: ['admin', 'audit', filters.action ?? null, filters.targetType ?? null, page],
    queryFn: () => listAudit(filters, page),
    placeholderData: keepPreviousData,
  })
}
