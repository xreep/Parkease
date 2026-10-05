import { useCallback } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { ListingStatus, VerificationStatus } from './format'
import { uploadFile } from './uploads'

export type { ListingStatus, VerificationStatus }
export type DocumentType =
  | 'AADHAAR' | 'PAN' | 'DRIVING_LICENCE' | 'PASSPORT' | 'VOTER_ID' | 'PROPERTY_DOCUMENT' | 'UTILITY_BILL'
export type ListingType = 'METRO' | 'OFFICE' | 'COMMERCIAL' | 'RESIDENTIAL' | 'EVENT'
export type CancellationPolicy = 'FLEXIBLE' | 'MODERATE' | 'STRICT'
export type Amenity = 'COVERED' | 'CCTV' | 'EV_CHARGING' | 'SECURITY_GUARD' | 'WHEELCHAIR_ACCESS' | 'WELL_LIT'
export type VehicleType = 'TWO_WHEELER' | 'FOUR_WHEELER'
export type SlotSize = 'SMALL' | 'MEDIUM' | 'LARGE'

export type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number }

export type SignedUrl = { url: string; expiresAt: string }

export type OwnerProfile = {
  verificationStatus: VerificationStatus
  documentType: DocumentType | null
  hasDocument: boolean
  documentSubmittedAt: string | null
  rejectionReason: string | null
  verifiedAt: string | null
  payoutUpi: string | null
  payoutAccountName: string | null
  payoutIfsc: string | null
  payoutBankAccountLast4: string | null
}

/** bankAccount: omitted keeps the saved account, "" clears it, a value replaces it. */
export type PayoutBody = { upiId: string; bankAccount?: string; ifsc: string; accountName: string }

export type Photo = { id: number; url: string; sortOrder: number }
export type Slot = { id: number; label: string; vehicleType: VehicleType; size: SlotSize; active: boolean }
/** dayOfWeek: 1 = Monday .. 7 = Sunday; times are "HH:mm". */
export type HoursRule = { dayOfWeek: number; openTime: string; closeTime: string }
export type Hours = { open24x7: boolean; rules: HoursRule[] }
export type Block = {
  id: number
  slotId: number | null
  slotLabel: string | null
  startTime: string
  endTime: string
  reason: string | null
}

export type ListingSummary = {
  id: number
  title: string
  status: ListingStatus
  cityName: string
  stateName: string
  coverPhotoUrl: string | null
  pricePerHour: number | null
  slotCount: number
  rejectionReason: string | null
  updatedAt: string
}

export type ListingDetail = {
  id: number
  title: string
  description: string | null
  address: string
  pincode: string
  lat: number
  lng: number
  listingType: ListingType
  cityId: number
  cityName: string
  stateName: string
  status: ListingStatus
  rejectionReason: string | null
  open24x7: boolean
  rules: string | null
  autoApprove: boolean
  pricePerHour: number | null
  pricePerDay: number | null
  pricePerMonth: number | null
  cancellationPolicy: CancellationPolicy | null
  amenities: Amenity[]
  photos: Photo[]
  slots: Slot[]
  hours: HoursRule[]
  submittedAt: string | null
  approvedAt: string | null
  updatedAt: string
}

export type BasicsBody = {
  cityId: number
  title: string
  description: string
  address: string
  pincode: string
  lat: number
  lng: number
  listingType: ListingType
}

export type PricingBody = {
  pricePerHour: number
  pricePerDay: number | null
  pricePerMonth: number | null
  cancellationPolicy: CancellationPolicy
  autoApprove: boolean
  amenities: Amenity[]
  rules: string
}

export type SlotBody = { label: string; vehicleType: VehicleType; size: SlotSize; active?: boolean }
export type BulkSlotBody = { prefix: string; startNumber: number; count: number; vehicleType: VehicleType; size: SlotSize }
export type BlockBody = { slotId: number | null; startTime: string; endTime: string; reason?: string }

const base = '/owner/listings'

export const getOwnerProfile = async () => (await api.get<OwnerProfile>('/owner/profile')).data
export const savePayout = async (body: PayoutBody) => (await api.put<OwnerProfile>('/owner/profile/payout', body)).data
export const submitDocument = (type: DocumentType, file: File, onProgress?: (pct: number) => void) =>
  uploadFile<OwnerProfile>('/owner/verification', file, { documentType: type }, onProgress)
export const getDocumentUrl = async () => (await api.get<SignedUrl>('/owner/verification/document-url')).data

export const listMyListings = async (page: number) =>
  (await api.get<Page<ListingSummary>>(base, { params: { page, size: 20 } })).data
export const getListing = async (id: number) => (await api.get<ListingDetail>(`${base}/${id}`)).data
export const createListing = async (body: BasicsBody) => (await api.post<ListingDetail>(base, body)).data
export const updateBasics = async (id: number, body: BasicsBody) => (await api.put<ListingDetail>(`${base}/${id}`, body)).data
export const deleteListing = async (id: number) => {
  await api.delete(`${base}/${id}`)
}
export const savePricing = async (id: number, body: PricingBody) =>
  (await api.put<ListingDetail>(`${base}/${id}/pricing`, body)).data

export const uploadPhoto = (id: number, file: File, onProgress?: (pct: number) => void) =>
  uploadFile<Photo>(`${base}/${id}/photos`, file, {}, onProgress)
export const deletePhoto = async (id: number, photoId: number) => {
  await api.delete(`${base}/${id}/photos/${photoId}`)
}
export const reorderPhotos = async (id: number, photoIds: number[]) =>
  (await api.put<Photo[]>(`${base}/${id}/photos/order`, { photoIds })).data

export const listSlots = async (id: number) => (await api.get<Slot[]>(`${base}/${id}/slots`)).data
export const addSlot = async (id: number, body: SlotBody) => (await api.post<Slot>(`${base}/${id}/slots`, body)).data
export const addSlotsBulk = async (id: number, body: BulkSlotBody) =>
  (await api.post<Slot[]>(`${base}/${id}/slots/bulk`, body)).data
export const updateSlot = async (id: number, slotId: number, body: SlotBody) =>
  (await api.put<Slot>(`${base}/${id}/slots/${slotId}`, body)).data
export const deleteSlot = async (id: number, slotId: number) => {
  await api.delete(`${base}/${id}/slots/${slotId}`)
}

export const getHours = async (id: number) => (await api.get<Hours>(`${base}/${id}/hours`)).data
export const saveHours = async (id: number, body: Hours) => (await api.put<Hours>(`${base}/${id}/hours`, body)).data

export const listBlocks = async (id: number) => (await api.get<Block[]>(`${base}/${id}/blocks`)).data
export const addBlock = async (id: number, body: BlockBody) => (await api.post<Block>(`${base}/${id}/blocks`, body)).data
export const deleteBlock = async (id: number, blockId: number) => {
  await api.delete(`${base}/${id}/blocks/${blockId}`)
}

export const submitListing = async (id: number) => (await api.post<ListingDetail>(`${base}/${id}/submit`)).data
export const pauseListing = async (id: number) => (await api.post<ListingDetail>(`${base}/${id}/pause`)).data
export const resumeListing = async (id: number) => (await api.post<ListingDetail>(`${base}/${id}/resume`)).data

export function useOwnerProfile() {
  return useQuery({ queryKey: ['owner', 'profile'], queryFn: getOwnerProfile })
}

export function useMyListings(page: number) {
  return useQuery({ queryKey: ['owner', 'listings', page], queryFn: () => listMyListings(page) })
}

export function useListing(id: number | undefined) {
  return useQuery({
    queryKey: ['owner', 'listing', id],
    queryFn: () => getListing(id!),
    enabled: id !== undefined,
  })
}

export function useHours(id: number | undefined) {
  return useQuery({ queryKey: ['owner', 'hours', id], queryFn: () => getHours(id!), enabled: id !== undefined })
}

export function useBlocks(id: number | undefined) {
  return useQuery({ queryKey: ['owner', 'blocks', id], queryFn: () => listBlocks(id!), enabled: id !== undefined })
}

/** Returns a function that refetches the listing and the owner's listing list (after any edit). */
export function useRefreshListing(id: number) {
  const queryClient = useQueryClient()
  return useCallback(
    () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['owner', 'listing', id] }),
        queryClient.invalidateQueries({ queryKey: ['owner', 'listings'] }),
      ]).then(() => undefined),
    [queryClient, id],
  )
}
