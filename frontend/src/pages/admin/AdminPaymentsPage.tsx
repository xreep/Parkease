import clsx from 'clsx'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { FormError } from '../../components/AuthCard'
import { Empty, Loading, linkClass } from '../../components/admin/common'
import { SearchBox } from '../../components/admin/SearchBox'
import { Button } from '../../components/ui/Button'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { TextField } from '../../components/ui/TextField'
import { panelId, tabId } from '../../components/ui/tabIds'
import { ViewTabs } from '../../components/ui/ViewTabs'
import { invalidateAdminActivity } from '../../lib/admin'
import {
  adminErrorMessage,
  retryRefund,
  useAdminPayments,
  useAdminRefunds,
  type AdminRefund,
  type PaymentFilters,
  type RefundStatus,
} from '../../lib/adminManage'
import type { PaymentStatus } from '../../lib/bookings'
import { PAYMENT_STATUS_LABELS } from '../../lib/driver'
import { errorMessage } from '../../lib/errors'
import { formatDateTime, formatINR } from '../../lib/format'
import { stepBackIfEmpty, withPageReset } from '../../lib/paging'

type View = 'payments' | 'refunds'
const TABS: { value: View; label: string }[] = [
  { value: 'payments', label: 'Payments' },
  { value: 'refunds', label: 'Refunds' },
]
const PAYMENT_STATUSES = Object.keys(PAYMENT_STATUS_LABELS) as PaymentStatus[]

const bookingLink = (id: number, code: string) => (
  <Link to={`/admin/bookings/${id}`} className={clsx('font-mono font-semibold', linkClass)}>{code}</Link>
)

function PaymentsView() {
  const [status, setStatus] = useState<PaymentStatus | ''>('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const filters: PaymentFilters = { ...(status && { status }), ...(q && { q }), ...(from && { from }), ...(to && { to }) }
  const { data, error, isPending, isPlaceholderData } = useAdminPayments(filters, page)
  const filter = withPageReset(setPage)
  stepBackIfEmpty(page, setPage, data?.content, isPlaceholderData)

  return (
    <>
      <SearchBox label="Search payments" placeholder="Booking code, driver email or payment id" onSearch={filter(setQ)} />
      <div className="grid gap-3 sm:grid-cols-3">
        <Select label="Status" value={status} onChange={(e) => filter(setStatus)(e.target.value as PaymentStatus | '')}>
          <option value="">All statuses</option>
          {PAYMENT_STATUSES.map((s) => (
            <option key={s} value={s}>{PAYMENT_STATUS_LABELS[s]}</option>
          ))}
        </Select>
        <TextField label="From" type="date" value={from} max={to || undefined} onChange={(e) => filter(setFrom)(e.target.value)} />
        <TextField label="To" type="date" value={to} min={from || undefined} onChange={(e) => filter(setTo)(e.target.value)} />
      </div>

      {isPending ? (
        <Loading />
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data && data.content.length === 0 ? (
        <Empty>No payments match these filters.</Empty>
      ) : data ? (
        <div className={clsx('space-y-4 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
          <div className="overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-800">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Payments</caption>
              <thead className="bg-slate-50 text-slate-600 dark:bg-slate-900 dark:text-slate-400">
                <tr>
                  {['Booking', 'Driver', 'Gateway', 'Status', 'Amount', 'Refunded', 'Date'].map((h) => (
                    <th key={h} scope="col" className={clsx('whitespace-nowrap px-3 py-2 font-medium', ['Amount', 'Refunded'].includes(h) && 'text-right')}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
                {data.content.map((p) => (
                  <tr key={p.id} className="align-top">
                    <td className="px-3 py-2">{bookingLink(p.bookingId, p.bookingCode)}</td>
                    <td className="break-all px-3 py-2">{p.driverEmail}</td>
                    <td className="px-3 py-2">
                      <p>{p.provider === 'RAZORPAY' ? 'Razorpay' : 'Mock'}</p>
                      <p className="break-all font-mono text-xs text-slate-500">{p.providerPaymentId ?? p.providerOrderId}</p>
                    </td>
                    <td className="px-3 py-2"><StatusBadge kind="payment" status={p.status} /></td>
                    <td className="px-3 py-2 text-right tabular-nums">{formatINR(p.amount)}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{p.refundAmount > 0 ? formatINR(p.refundAmount) : '—'}</td>
                    <td className="whitespace-nowrap px-3 py-2 text-xs text-slate-600 dark:text-slate-400">{formatDateTime(p.capturedAt ?? p.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </div>
      ) : null}
    </>
  )
}

function RefundRow({ refund, onChanged }: { refund: AdminRefund; onChanged: () => Promise<void> }) {
  const [retrying, setRetrying] = useState(false)

  async function retry() {
    setRetrying(true)
    try {
      const result = await retryRefund(refund.id)
      if (result.status === 'FAILED') {
        toast.warning(`The refund failed again${result.lastError ? `: ${result.lastError}` : ''}`)
      } else {
        toast.success('Refund retried')
      }
    } catch (error) {
      toast.error(adminErrorMessage(error))
    } finally {
      // Either way the list is stale: it was processed meanwhile, or the attempt count moved.
      await onChanged()
      setRetrying(false)
    }
  }

  return (
    <tr className="align-top">
      <td className="space-y-2 px-3 py-2">
        <p>{bookingLink(refund.bookingId, refund.bookingCode)}</p>
        {refund.status === 'FAILED' && (
          <Button type="button" className="px-3 py-1.5" loading={retrying} onClick={() => void retry()}>Retry</Button>
        )}
      </td>
      <td className="px-3 py-2 text-right tabular-nums">{formatINR(refund.amount)}</td>
      <td className="px-3 py-2"><StatusBadge kind="refund" status={refund.status} /></td>
      <td className="px-3 py-2 tabular-nums">{refund.attempts}</td>
      <td className="px-3 py-2">
        <p className="break-words">{refund.reason}</p>
        {refund.lastError && <p className="break-words text-xs text-red-600 dark:text-red-400">{refund.lastError}</p>}
      </td>
      <td className="whitespace-nowrap px-3 py-2 text-xs text-slate-600 dark:text-slate-400">{formatDateTime(refund.createdAt)}</td>
    </tr>
  )
}

function RefundsView() {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<RefundStatus | ''>('')
  const [page, setPage] = useState(0)
  const { data, error, isPending, isPlaceholderData } = useAdminRefunds(status || undefined, page)
  stepBackIfEmpty(page, setPage, data?.content, isPlaceholderData)
  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['admin', 'refunds'] }),
      queryClient.invalidateQueries({ queryKey: ['admin', 'payments'] }),
      queryClient.invalidateQueries({ queryKey: ['admin', 'booking'] }),
      invalidateAdminActivity(queryClient),
    ]).then(() => undefined)

  return (
    <>
      <Select
        label="Status"
        className="max-w-xs"
        value={status}
        onChange={(e) => {
          setStatus(e.target.value as RefundStatus | '')
          setPage(0)
        }}
      >
        <option value="">All statuses</option>
        <option value="PENDING">Pending</option>
        <option value="PROCESSED">Processed</option>
        <option value="FAILED">Failed</option>
      </Select>

      {isPending ? (
        <Loading />
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data && data.content.length === 0 ? (
        <Empty>No refunds match this filter.</Empty>
      ) : data ? (
        <div className={clsx('space-y-4 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
          <div className="overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-800">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Refunds</caption>
              <thead className="bg-slate-50 text-slate-600 dark:bg-slate-900 dark:text-slate-400">
                <tr>
                  {['Booking', 'Amount', 'Status', 'Attempts', 'Reason', 'Created'].map((h) => (
                    <th key={h} scope="col" className={clsx('whitespace-nowrap px-3 py-2 font-medium', h === 'Amount' && 'text-right')}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
                {data.content.map((r) => (
                  <RefundRow key={r.id} refund={r} onChanged={refresh} />
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </div>
      ) : null}
    </>
  )
}

export function AdminPaymentsPage() {
  const [view, setView] = useState<View>('payments')
  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Payments</h2>
      <ViewTabs idPrefix="admin-payments" label="Payment views" items={TABS} value={view} onChange={setView} />
      <div role="tabpanel" id={panelId('admin-payments', view)} aria-labelledby={tabId('admin-payments', view)} className="space-y-4">
        {view === 'payments' ? <PaymentsView /> : <RefundsView />}
      </div>
    </div>
  )
}
