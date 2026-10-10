import clsx from 'clsx'
import { BOOKING_STATUS_LABELS, type BookingStatus, type PaymentStatus } from '../../lib/bookings'
import { PAYMENT_STATUS_LABELS } from '../../lib/driver'
import { EARNING_STATUS_LABELS, type EarningStatus } from '../../lib/ownerDashboard'
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

const earningTone: Record<EarningStatus, keyof typeof tones> = {
  HELD: 'amber',
  PENDING_PAYOUT: 'sky',
  PAID: 'emerald',
  REVERSED: 'slate',
}

const paymentTone: Record<PaymentStatus, keyof typeof tones> = {
  CREATED: 'amber',
  CAPTURED: 'emerald',
  FAILED: 'red',
  REFUNDED: 'sky',
  PARTIALLY_REFUNDED: 'sky',
}

const userTone = { ACTIVE: 'emerald', SUSPENDED: 'red' } as const
const userLabels = { ACTIVE: 'Active', SUSPENDED: 'Suspended' } as const

const refundTone = { PENDING: 'amber', PROCESSED: 'emerald', FAILED: 'red' } as const
const refundLabels = { PENDING: 'Pending', PROCESSED: 'Processed', FAILED: 'Failed' } as const

const disputeTone = { OPEN: 'amber', UNDER_REVIEW: 'sky', RESOLVED: 'emerald' } as const
const disputeLabels = { OPEN: 'Open', UNDER_REVIEW: 'Under review', RESOLVED: 'Resolved' } as const

/** A pill in one of the status tones, for states that have no dedicated kind. */
export function Badge({ tone, children }: { tone: keyof typeof tones; children: string }) {
  return (
    <span className={clsx('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold', tones[tone])}>
      {children}
    </span>
  )
}

export type StatusBadgeProps =
  | { kind: 'listing'; status: ListingStatus }
  | { kind: 'verification'; status: VerificationStatus }
  | { kind: 'booking'; status: BookingStatus }
  | { kind: 'earning'; status: EarningStatus }
  | { kind: 'payment'; status: PaymentStatus }
  | { kind: 'user'; status: 'ACTIVE' | 'SUSPENDED' }
  | { kind: 'refund'; status: 'PENDING' | 'PROCESSED' | 'FAILED' }
  | { kind: 'dispute'; status: 'OPEN' | 'UNDER_REVIEW' | 'RESOLVED' }

function describe(props: StatusBadgeProps): { label: string; tone: keyof typeof tones } {
  switch (props.kind) {
    case 'listing':
      return { label: listingStatusLabel(props.status), tone: listingTone[props.status] }
    case 'verification':
      return { label: verificationStatusLabel(props.status), tone: verificationTone[props.status] }
    case 'earning':
      return { label: EARNING_STATUS_LABELS[props.status], tone: earningTone[props.status] }
    case 'payment':
      return { label: PAYMENT_STATUS_LABELS[props.status], tone: paymentTone[props.status] }
    case 'user':
      return { label: userLabels[props.status], tone: userTone[props.status] }
    case 'refund':
      return { label: refundLabels[props.status], tone: refundTone[props.status] }
    case 'dispute':
      return { label: disputeLabels[props.status], tone: disputeTone[props.status] }
    case 'booking':
      return { label: BOOKING_STATUS_LABELS[props.status], tone: bookingTone[props.status] }
  }
}

export function StatusBadge(props: StatusBadgeProps) {
  const { label, tone } = describe(props)
  return <Badge tone={tone}>{label}</Badge>
}
