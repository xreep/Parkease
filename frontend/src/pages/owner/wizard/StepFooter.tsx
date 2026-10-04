import { useNavigate } from 'react-router-dom'
import { Button } from '../../../components/ui/Button'

type Props = {
  listingId: number
  backStep: number
  onContinue: () => void
  /** Why Continue is disabled; shown beside the button while it is. */
  blockedHint?: string | null
}

export function StepFooter({ listingId, backStep, onContinue, blockedHint = null }: Props) {
  const navigate = useNavigate()
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 pt-4 dark:border-slate-800">
      <Button type="button" variant="secondary" onClick={() => navigate(`/owner/listings/${listingId}/edit?step=${backStep}`)}>
        Back
      </Button>
      <div className="flex items-center gap-3">
        {blockedHint && <p className="text-sm text-slate-500">{blockedHint}</p>}
        <Button type="button" disabled={blockedHint !== null} onClick={onContinue}>Continue</Button>
      </div>
    </div>
  )
}
