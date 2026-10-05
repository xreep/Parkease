import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from './api'
import { AMENITY_LABELS, LISTING_TYPE_LABELS, VEHICLE_TYPE_LABELS } from './format'
import type { Amenity, ListingType, Page, VehicleType } from './owner'

export type { Amenity, ListingType, VehicleType }

export type PricingMode = 'HOURLY' | 'DAILY' | 'MONTHLY' | 'MIXED'
export type SearchSort = 'distance' | 'price' | 'rating'
export type CancellationPolicy = 'FLEXIBLE' | 'MODERATE' | 'STRICT'

export type QuoteDto = {
  pricingMode: PricingMode
  durationMinutes: number
  baseAmount: number
  platformFee: number
  gstAmount: number
  totalAmount: number
  /** e.g. "2 days + 3 hours" */
  breakdown: string
}

export type SearchResultDto = {
  id: number
  title: string
  listingType: ListingType
  address: string
  cityName: string
  stateName: string
  lat: number
  lng: number
  distanceKm: number
  coverPhotoUrl: string | null
  pricePerHour: number
  pricePerDay: number | null
  pricePerMonth: number | null
  amenities: Amenity[]
  open24x7: boolean
  avgRating: number
  reviewCount: number
  totalSlots: number
  freeSlots: number | null
  quote: QuoteDto | null
}

export type SearchResponse = Page<SearchResultDto> & {
  center: { lat: number; lng: number }
  radiusKm: number
  window: { start: string; end: string } | null
}

export type PublicListingDto = {
  id: number
  title: string
  description: string | null
  listingType: ListingType
  address: string
  pincode: string
  lat: number
  lng: number
  cityName: string
  citySlug: string
  stateName: string
  stateSlug: string
  photos: { id: number; url: string }[]
  amenities: Amenity[]
  rules: string | null
  cancellationPolicy: CancellationPolicy
  autoApprove: boolean
  open24x7: boolean
  /** dayOfWeek: 1 = Monday .. 7 = Sunday; times are "HH:mm". */
  hours: { dayOfWeek: number; openTime: string; closeTime: string }[]
  pricePerHour: number
  pricePerDay: number | null
  pricePerMonth: number | null
  slotSummary: { twoWheeler: number; fourWheeler: number; small: number; medium: number; large: number }
  avgRating: number
  reviewCount: number
  ownerFirstName: string
}

export type QuoteUnavailableReason = 'CLOSED' | 'BLOCKED' | 'NO_VEHICLE_SLOTS' | 'FULLY_BOOKED'

export type ListingQuoteResponse = {
  available: boolean
  reason: QuoteUnavailableReason | null
  freeSlots: number
  totalSlots: number
  quote: QuoteDto
}

/** Search state as it lives in the URL. `start`/`end` are ISO instants. */
export type SearchParams = {
  place: string
  lat: number
  lng: number
  start?: string
  end?: string
  vehicle?: VehicleType
  radius?: number
  types?: ListingType[]
  amenities?: Amenity[]
  maxPrice?: number
  open24x7?: boolean
  sort?: SearchSort
  page?: number
}

export const DEFAULT_RADIUS_KM = 5
export const DEFAULT_SORT: SearchSort = 'distance'

const SORTS: readonly SearchSort[] = ['distance', 'price', 'rating']

const isKey = <T extends object>(obj: T, v: string): v is Extract<keyof T, string> => Object.hasOwn(obj, v)

function finiteNumber(raw: string | null): number | undefined {
  if (raw === null || raw.trim() === '') return undefined
  const n = Number(raw)
  return Number.isFinite(n) ? n : undefined
}

/** Reads `/search` URL params. Null when lat/lng are missing or not valid coordinates. */
export function parseSearchParams(usp: URLSearchParams): SearchParams | null {
  const lat = finiteNumber(usp.get('lat'))
  const lng = finiteNumber(usp.get('lng'))
  if (lat === undefined || lng === undefined || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null

  const p: SearchParams = { place: usp.get('place') ?? '', lat, lng }
  const start = usp.get('start')
  const end = usp.get('end')
  if (start && end) {
    p.start = start
    p.end = end
  }
  const vehicle = usp.get('vehicle')
  if (vehicle && isKey(VEHICLE_TYPE_LABELS, vehicle)) p.vehicle = vehicle
  const radius = finiteNumber(usp.get('radius'))
  if (radius !== undefined && radius > 0 && radius !== DEFAULT_RADIUS_KM) p.radius = radius
  const types = usp.getAll('types').filter((t): t is ListingType => isKey(LISTING_TYPE_LABELS, t))
  if (types.length) p.types = types
  const amenities = usp.getAll('amenities').filter((a): a is Amenity => isKey(AMENITY_LABELS, a))
  if (amenities.length) p.amenities = amenities
  const maxPrice = finiteNumber(usp.get('maxPrice'))
  if (maxPrice !== undefined && maxPrice > 0) p.maxPrice = maxPrice
  if (usp.get('open24x7') === 'true') p.open24x7 = true
  const sort = SORTS.find((s) => s === usp.get('sort'))
  if (sort && sort !== DEFAULT_SORT) p.sort = sort
  const page = finiteNumber(usp.get('page'))
  if (page !== undefined && Number.isInteger(page) && page > 0) p.page = page
  return p
}

/** Builds `/search` URL params, leaving out empty and default values. Arrays repeat their key. */
export function toSearchParams(p: SearchParams): URLSearchParams {
  const usp = new URLSearchParams()
  usp.set('place', p.place)
  usp.set('lat', String(p.lat))
  usp.set('lng', String(p.lng))
  if (p.start && p.end) {
    usp.set('start', p.start)
    usp.set('end', p.end)
  }
  if (p.vehicle) usp.set('vehicle', p.vehicle)
  if (p.radius !== undefined && p.radius !== DEFAULT_RADIUS_KM) usp.set('radius', String(p.radius))
  p.types?.forEach((t) => usp.append('types', t))
  p.amenities?.forEach((a) => usp.append('amenities', a))
  if (p.maxPrice !== undefined && p.maxPrice > 0) usp.set('maxPrice', String(p.maxPrice))
  if (p.open24x7) usp.set('open24x7', 'true')
  if (p.sort && p.sort !== DEFAULT_SORT) usp.set('sort', p.sort)
  if (p.page) usp.set('page', String(p.page))
  return usp
}

export type SearchApiQuery = {
  lat: number
  lng: number
  start?: string
  end?: string
  vehicleType?: VehicleType
  radiusKm?: number
  types?: ListingType[]
  amenities?: Amenity[]
  maxPricePerHour?: number
  open24x7?: boolean
  sort?: SearchSort
  page?: number
}

/** Query parameters for `GET /search` (no `place`; `vehicle`, `radius`, `maxPrice` use the API's names). */
export function toApiQuery(p: SearchParams): SearchApiQuery {
  const q: SearchApiQuery = { lat: p.lat, lng: p.lng }
  if (p.start && p.end) {
    q.start = p.start
    q.end = p.end
  }
  if (p.vehicle) q.vehicleType = p.vehicle
  if (p.radius !== undefined) q.radiusKm = p.radius
  if (p.types?.length) q.types = p.types
  if (p.amenities?.length) q.amenities = p.amenities
  if (p.maxPrice !== undefined && p.maxPrice > 0) q.maxPricePerHour = p.maxPrice
  if (p.open24x7) q.open24x7 = true
  if (p.sort) q.sort = p.sort
  if (p.page) q.page = p.page
  return q
}

/** Spring binds repeated keys (`types=A&types=B`), not axios' default `types[]=A`. */
const repeatKeys = { indexes: null }

export function useSearch(p: SearchParams) {
  const query = toApiQuery(p)
  return useQuery({
    queryKey: ['search', toSearchParams(p).toString()],
    queryFn: async ({ signal }) =>
      (await api.get<SearchResponse>('/search', { params: query, paramsSerializer: repeatKeys, signal })).data,
    staleTime: 30_000,
    placeholderData: keepPreviousData,
  })
}

export function usePublicListing(id: number | string) {
  return useQuery({
    queryKey: ['listing', id],
    queryFn: async () => (await api.get<PublicListingDto>(`/listings/${id}`)).data,
  })
}

export function useQuote(id: number | string, start?: string, end?: string, vehicle?: VehicleType) {
  return useQuery({
    queryKey: ['quote', id, start, end, vehicle],
    queryFn: async () =>
      (await api.get<ListingQuoteResponse>(`/listings/${id}/quote`, { params: { start, end, vehicleType: vehicle } })).data,
    enabled: Boolean(start && end),
  })
}
