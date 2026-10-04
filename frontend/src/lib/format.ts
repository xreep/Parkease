const inr = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 2,
  minimumFractionDigits: 0,
})

export function formatINR(value: number | string | null): string {
  if (value === null || value === undefined || value === '') return '—'
  const n = typeof value === 'string' ? Number(value) : value
  return Number.isFinite(n) ? inr.format(n) : '—'
}

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
