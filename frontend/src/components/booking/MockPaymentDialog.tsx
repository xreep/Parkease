import { FormError } from '../AuthCard'
import { Button } from '../ui/Button'
import { Dialog } from '../ui/Dialog'

export type MockPaymentDialogProps = {
  open: boolean
  /** The formatted total, e.g. "₹89.44". */
  amount: string
  busy: boolean
  error: string | null
  onPay: () => void
  onFail: () => void
  onClose: () => void
}

/** Stands in for the card window while Razorpay keys aren't configured. */
export function MockPaymentDialog({ open, amount, busy, error, onPay, onFail, onClose }: MockPaymentDialogProps) {
  return (
    <Dialog open={open} title="Test payment" onClose={onClose} busy={busy}>
      <div className="space-y-4">
        <FormError message={error} />
        <p className="text-sm text-slate-600 dark:text-slate-400">Razorpay keys aren't configured, so this simulates a payment.</p>
        <p className="text-3xl font-bold">{amount}</p>
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button type="button" variant="ghost" disabled={busy} onClick={onClose}>Cancel</Button>
          <Button type="button" variant="secondary" disabled={busy} onClick={onFail}>Simulate failure</Button>
          <Button type="button" loading={busy} onClick={onPay}>{`Pay ${amount}`}</Button>
        </div>
      </div>
    </Dialog>
  )
}
