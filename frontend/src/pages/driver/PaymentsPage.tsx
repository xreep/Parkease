import clsx from 'clsx'
import { Download } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { Pagination } from '../../components/ui/Pagination'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { downloadReceipt } from '../../lib/bookings'
import { saveBlob } from '../../lib/download'
import { usePayments, type DriverPaymentDto } from '../../lib/driver'
import { errorMessage } from '../../lib/errors'
import { formatDateTime, formatINR } from '../../lib/format'

function ReceiptButton({ payment }: { payment: DriverPaymentDto }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function download() {
    setBusy(true)
    setError(null)
    try {
      saveBlob(await downloadReceipt(payment.bookingId), `ParkEase-${payment.invoiceNumber ?? payment.bookingCode}.pdf`)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-2">
      <FormError message={error} />
      <Button type="button" variant="secondary" className="px-3 py-1.5" loading={busy} onClick={() => void download()}>
        {!busy && <Download aria-hidden className="h-4 w-4" />}
        Download receipt
      </Button>
    </div>
  )
}

function PaymentCard({ payment }: { payment: DriverPaymentDto }) {
  return (
    <article
      aria-label={`Payment for ${payment.bookingCode}`}
      className="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:flex-row sm:items-start sm:justify-between"
    >
      <div className="min-w-0 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <Link to={`/driver/bookings/${payment.bookingId}`} className="font-mono text-sm font-semibold text-brand-700 hover:underline dark:text-brand-400">
            {payment.bookingCode}
          </Link>
          <StatusBadge kind="payment" status={payment.status} />
        </div>
        <p className="break-words font-semibold">{payment.listingTitle}</p>
        {payment.paidAt && <p className="text-sm text-slate-600 dark:text-slate-400">{formatDateTime(payment.paidAt)}</p>}
        {payment.invoiceNumber && <p className="text-xs text-slate-500">{`Invoice ${payment.invoiceNumber}`}</p>}
      </div>
      <div className="flex flex-col gap-2 sm:items-end">
        <p className="text-lg font-bold">{formatINR(payment.amount)}</p>
        {payment.refundAmount > 0 && (
          <p className="text-sm font-medium text-emerald-700 dark:text-emerald-400">{`Refunded ${formatINR(payment.refundAmount)}`}</p>
        )}
        {payment.receiptAvailable && <ReceiptButton payment={payment} />}
      </div>
    </article>
  )
}

export function PaymentsPage() {
  const [page, setPage] = useState(0)
  const { data, error, isPending, isPlaceholderData } = usePayments(page)

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Payments</h2>
      {isPending ? (
        <div className="flex justify-center py-12">
          <Spinner className="h-8 w-8 text-brand-600" />
        </div>
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data && data.content.length === 0 && page === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 p-10 text-center dark:border-slate-700">
          <p className="text-slate-600 dark:text-slate-400">No payments yet.</p>
        </div>
      ) : data ? (
        <>
          <div className={clsx('space-y-3 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
            {data.content.map((p) => (
              <PaymentCard key={p.id} payment={p} />
            ))}
          </div>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      ) : null}
    </div>
  )
}
