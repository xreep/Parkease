import { PhotoManager } from '../../../components/owner/PhotoManager'
import { StepFooter } from './StepFooter'
import { isReadOnly, type StepProps } from './types'

export function PhotosStep({ listing, onSaved }: StepProps) {
  return (
    <div className="space-y-6">
      <PhotoManager listingId={listing.id} photos={listing.photos} readOnly={isReadOnly(listing)} />
      <StepFooter
        listingId={listing.id}
        backStep={1}
        onContinue={() => onSaved(3)}
        blockedHint={listing.photos.length === 0 ? 'Add at least one photo to continue' : null}
      />
    </div>
  )
}
