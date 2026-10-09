import clsx from 'clsx'
import { BOOKING_STATUS_LABELS, type BookingStatus } from '../../lib/bookings'
import { listingStatusLabel, verificationStatusLabel, type ListingStatus, type VerificationStatus } from '../../lib/format'

const tones = {
  emerald: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300',
  amber: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300',
  red: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
  slate: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
  sky: 'bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-300',
} as const

const listingTone: Record<ListingStatus, keyof typeof tones> = {
  DRAFT: 'slate',
  PENDING_REVIEW: 'amber',
  APPROVED: 'emerald',
  REJECTED: 'red',
  PAUSED: 'sky',
  SUSPENDED: 'red',
}

const verificationTone: Record<VerificationStatus, keyof typeof tones> = {
  UNSUBMITTED: 'slate',
  PENDING: 'amber',
  VERIFIED: 'emerald',
  REJECTED: 'red',
}

const bookingTone: Record<BookingStatus, keyof typeof tones> = {
  PENDING_PAYMENT: 'amber',
  AWAITING_APPROVAL: 'amber',
  CONFIRMED: 'emerald',
  ACTIVE: 'sky',
  COMPLETED: 'slate',
  CANCELLED: 'slate',
  REJECTED: 'red',
  EXPIRED: 'slate',
}

export type StatusBadgeProps =
  | { kind: 'listing'; status: ListingStatus }
  | { kind: 'verification'; status: VerificationStatus }
  | { kind: 'booking'; status: BookingStatus }

export function StatusBadge(props: StatusBadgeProps) {
  const label =
    props.kind === 'listing'
      ? listingStatusLabel(props.status)
      : props.kind === 'verification'
        ? verificationStatusLabel(props.status)
        : BOOKING_STATUS_LABELS[props.status]
  const tone =
    props.kind === 'listing'
      ? listingTone[props.status]
      : props.kind === 'verification'
        ? verificationTone[props.status]
        : bookingTone[props.status]
  return (
    <span className={clsx('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold', tones[tone])}>
      {label}
    </span>
  )
}
