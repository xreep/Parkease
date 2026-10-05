import type { ListingDetail } from '../../../lib/owner'

export type StepProps = {
  listing: ListingDetail
  /** Refreshes the listing, then goes to step `next` (stays put when omitted). */
  onSaved: (next?: number) => void
}

export const isReadOnly = (listing: Pick<ListingDetail, 'status'>) => listing.status === 'SUSPENDED'
