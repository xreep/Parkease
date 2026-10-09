import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { cancelBooking, invalidateBookingQueries, useCancellationPreview, type CancellationPreview } from '../../lib/bookings'
import { errorMessage } from '../../lib/errors'
import { CANCELLATION_POLICIES, REFUND_NOTE, formatINR } from '../../lib/format'
import { FormError } from '../AuthCard'
import { Button } from '../ui/Button'
import { Dialog } from '../ui/Dialog'
import { Spinner } from '../ui/Spinner'
import { TextArea } from '../ui/TextArea'

const REASON_MAX = 300

/**
 * The headline and the explanation for what cancelling does right now, read from the preview alone. A cancellable
 * preview without a policy is a booking the owner has not accepted (refunded in full) or one nothing was charged for.
 */
function outcome(preview: CancellationPreview): { headline: string; detail: string | null } {
  if (!preview.policy) {
    if (preview.refundAmount === 0) {
      return { headline: 'Nothing has been charged yet.', detail: 'The slot is released as soon as you cancel.' }
    }
    return {
      headline: `You'll get ${formatINR(preview.refundAmount)} back (${preview.refundPercent}%)`,
      detail: "The owner hasn't accepted this request yet, so you're refunded in full.",
    }
  }
  const policy = CANCELLATION_POLICIES.find((p) => p.value === preview.policy)
  const headline =
    preview.refundAmount > 0
      ? `You'll get ${formatINR(preview.refundAmount)} back (${preview.refundPercent}%)`
      : `No refund applies — ${policy?.label ?? preview.policy} policy`
  return { headline, detail: policy ? `${policy.label} policy: ${policy.help}` : null }
}

/** What the toast says once the server has answered: its refund, and the preview's if the two differ. */
function cancelledMessage(refunded: number, preview: CancellationPreview): string {
  const refund = refunded > 0 ? ` — ${formatINR(refunded)} will be refunded` : ''
  const differs = Math.abs(refunded - preview.refundAmount) >= 0.005
  return `Booking cancelled${refund}${differs ? ` (the preview showed ${formatINR(preview.refundAmount)})` : ''}`
}

/** Shows what a cancellation would refund and, once confirmed, cancels with an optional reason. Mount it to open it. */
export function CancelBookingDialog({
  bookingId,
  fees,
  onClose,
}: {
  bookingId: number
  /** The platform fee and GST of the booking: the part of what is not refunded that is never refunded. */
  fees: number
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const { data: preview, error: previewError } = useCancellationPreview(bookingId, true)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function confirm() {
    if (!preview) return
    setBusy(true)
    setError(null)
    try {
      const cancelled = await cancelBooking(bookingId, reason.trim() || undefined)
      toast.success(cancelledMessage(cancelled.refundAmount, preview))
      onClose()
    } catch (e) {
      // A 409 usually means the booking changed under us (it started, or was already cancelled): show why.
      setError(errorMessage(e))
    } finally {
      setBusy(false)
      await invalidateBookingQueries(queryClient)
    }
  }

  const result = preview?.cancellable ? outcome(preview) : null

  return (
    <Dialog open title="Cancel this booking?" onClose={onClose} busy={busy}>
      <div className="space-y-4">
        <FormError message={error} />
        {!preview ? (
          previewError ? (
            <FormError message={errorMessage(previewError)} />
          ) : (
            <div className="flex justify-center py-6">
              <Spinner className="h-6 w-6 text-brand-600" />
            </div>
          )
        ) : !preview.cancellable ? (
          error === preview.reason ? null : <p role="status" className="text-sm text-slate-700 dark:text-slate-300">{preview.reason ?? "This booking can't be cancelled."}</p>
        ) : result ? (
          <div className="space-y-2 text-sm">
            <p role="status" className="text-base font-semibold">{result.headline}</p>
            {preview.nonRefundableAmount > 0 && (
              <p className="text-slate-700 dark:text-slate-300">
                {`Not refunded: ${formatINR(preview.nonRefundableAmount)}${
                  fees > 0 ? ` — includes platform fee and GST ${formatINR(Math.round(fees * 100) / 100)}` : ''
                }`}
              </p>
            )}
            {result.detail && <p className="text-slate-600 dark:text-slate-400">{result.detail}</p>}
            {preview.policy && <p className="text-xs text-slate-500">{REFUND_NOTE}</p>}
          </div>
        ) : null}
        {preview?.cancellable && (
          <TextArea
            label="Reason (optional)"
            rows={3}
            maxLength={REASON_MAX}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" disabled={busy} onClick={onClose}>
            {preview && !preview.cancellable ? 'Close' : 'Keep booking'}
          </Button>
          {preview?.cancellable && (
            <Button type="button" variant="danger" loading={busy} onClick={() => void confirm()}>
              Cancel booking
            </Button>
          )}
        </div>
      </div>
    </Dialog>
  )
}
