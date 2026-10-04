import { SlotManager } from '../../../components/owner/SlotManager'
import { StepFooter } from './StepFooter'
import { isReadOnly, type StepProps } from './types'

export function SlotsStep({ listing, onSaved }: StepProps) {
  const hasActiveSlot = listing.slots.some((s) => s.active)
  return (
    <div className="space-y-6">
      <SlotManager listingId={listing.id} slots={listing.slots} readOnly={isReadOnly(listing)} />
      <StepFooter
        listingId={listing.id}
        backStep={2}
        onContinue={() => onSaved(4)}
        blockedHint={hasActiveSlot ? null : 'Add at least one slot to continue'}
      />
    </div>
  )
}
