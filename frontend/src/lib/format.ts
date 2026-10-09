import type { CancellationPolicy } from './owner'

const inr = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0,
})
const inrPaise = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

export function formatINR(value: number | string | null): string {
  if (value === null || value === undefined || value === '') return '—'
  const n = typeof value === 'string' ? Number(value) : value
  if (!Number.isFinite(n)) return '—'
  // Whole rupees read cleanest without decimals; anything with paise always shows two digits (₹610.80, not ₹610.8).
  return Number.isInteger(n) ? inr.format(n) : inrPaise.format(n)
}

/** "₹50k", "₹2.5L", "₹1Cr": short enough for a chart axis, in lakhs and crores. */
export function formatCompactINR(value: number): string {
  const units: [number, string][] = [[10_000_000, 'Cr'], [100_000, 'L'], [1_000, 'k']]
  const [size, suffix] = units.find(([s]) => value >= s) ?? [1, '']
  return `₹${Number((value / size).toFixed(1))}${suffix}`
}

/** "42.5%": one decimal at most, none when whole. */
export const formatPercent = (n: number): string => `${Number(n.toFixed(1))}%`

const dateTime = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' })

export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso))
}

export const LISTING_STATUS_LABELS = {
  DRAFT: 'Draft',
  PENDING_REVIEW: 'Pending review',
  APPROVED: 'Live',
  REJECTED: 'Changes needed',
  PAUSED: 'Paused',
  SUSPENDED: 'Suspended',
} as const

export const VERIFICATION_STATUS_LABELS = {
  UNSUBMITTED: 'Not submitted',
  PENDING: 'Under review',
  VERIFIED: 'Verified',
  REJECTED: 'Rejected',
} as const

export type ListingStatus = keyof typeof LISTING_STATUS_LABELS
export type VerificationStatus = keyof typeof VERIFICATION_STATUS_LABELS

export const listingStatusLabel = (s: ListingStatus): string => LISTING_STATUS_LABELS[s]
export const verificationStatusLabel = (s: VerificationStatus): string => VERIFICATION_STATUS_LABELS[s]

/** Index 0 is dayOfWeek 1 (Monday). */
export const DAY_NAMES = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

export const AMENITY_LABELS = {
  COVERED: 'Covered',
  CCTV: 'CCTV',
  EV_CHARGING: 'EV charging',
  SECURITY_GUARD: 'Security guard',
  WHEELCHAIR_ACCESS: 'Wheelchair access',
  WELL_LIT: 'Well lit',
} as const

export const LISTING_TYPE_LABELS = {
  METRO: 'Metro / transit hub',
  OFFICE: 'Office complex',
  COMMERCIAL: 'Market / commercial',
  RESIDENTIAL: 'Residential',
  EVENT: 'Event venue',
} as const

export const DOCUMENT_TYPE_LABELS = {
  AADHAAR: 'Aadhaar card',
  PAN: 'PAN card',
  DRIVING_LICENCE: 'Driving licence',
  PASSPORT: 'Passport',
  VOTER_ID: 'Voter ID',
  PROPERTY_DOCUMENT: 'Property document',
  UTILITY_BILL: 'Utility bill',
} as const

export const VEHICLE_TYPE_LABELS = {
  FOUR_WHEELER: 'Car',
  TWO_WHEELER: 'Two-wheeler',
} as const

export const SLOT_SIZE_LABELS = {
  SMALL: 'Small',
  MEDIUM: 'Medium',
  LARGE: 'Large',
} as const

/** "address, city, state PIN", leaving out the city when the address already mentions it. */
export function formatAddress(l: { address: string; cityName: string; stateName: string; pincode: string }): string {
  const mentionsCity = l.address.toLowerCase().includes(l.cityName.toLowerCase())
  const parts = [l.address, ...(mentionsCity ? [] : [l.cityName]), l.stateName]
  return `${parts.join(', ')} ${l.pincode}`
}

export const CANCELLATION_POLICIES: { value: CancellationPolicy; label: string; help: string }[] = [
  { value: 'FLEXIBLE', label: 'Flexible', help: 'Full refund up to 1 hour before start, 50% after' },
  { value: 'MODERATE', label: 'Moderate', help: 'Full refund up to 24 hours before start, 50% from 24 to 2 hours before, none within 2 hours' },
  { value: 'STRICT', label: 'Strict', help: '50% refund up to 48 hours before start, none after' },
]

/** Shown wherever a cancellation policy is described. */
export const REFUND_NOTE = "Refunds apply to the parking charge; platform fee and GST aren't refunded on driver cancellations."
