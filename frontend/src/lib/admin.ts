import { useQuery } from '@tanstack/react-query'
import { api } from './api'
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
