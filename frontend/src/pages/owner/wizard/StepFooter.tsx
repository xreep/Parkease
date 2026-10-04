import { useNavigate } from 'react-router-dom'
import { Button } from '../../../components/ui/Button'

type Props = {
  listingId: number
  backStep: number
  /** Omit inside a form: the button then submits it as "Save and continue". */
  onContinue?: () => void
  /** Why Continue is disabled; shown beside the button while it is. */
  blockedHint?: string | null
  /** Disables a submitting button (e.g. a read-only listing). */
  disabled?: boolean
  submitting?: boolean
}

export function StepFooter({ listingId, backStep, onContinue, blockedHint = null, disabled = false, submitting = false }: Props) {
  const navigate = useNavigate()
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 pt-4 dark:border-slate-800">
      <Button type="button" variant="secondary" onClick={() => navigate(`/owner/listings/${listingId}/edit?step=${backStep}`)}>
        Back
      </Button>
      <div className="flex items-center gap-3">
        {blockedHint && <p className="text-sm text-slate-500">{blockedHint}</p>}
        {onContinue ? (
          <Button type="button" disabled={blockedHint !== null || disabled} onClick={onContinue}>Continue</Button>
        ) : (
          <Button type="submit" disabled={disabled} loading={submitting}>Save and continue</Button>
        )}
      </div>
    </div>
  )
}
