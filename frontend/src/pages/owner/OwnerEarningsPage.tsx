import clsx from 'clsx'
import { Download } from 'lucide-react'
import { useState } from 'react'
import { FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { TextField } from '../../components/ui/TextField'
import { errorMessage } from '../../lib/errors'
import { formatDateTime, formatINR } from '../../lib/format'
import {
  downloadEarningsCsv,
  EARNING_STATUS_LABELS,
  useOwnerEarnings,
  type EarningStatus,
  type OwnerEarningDto,
} from '../../lib/ownerDashboard'
import { formatWindow } from '../../lib/time'
import { useCsvExport } from '../../lib/useCsvExport'

const STATUSES = Object.keys(EARNING_STATUS_LABELS) as EarningStatus[]

function Total({ label, value }: { label: string; value: string }) {
  return (
    <div role="group" aria-label={label} className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <p className="text-sm text-slate-600 dark:text-slate-400">{label}</p>
      <p className="mt-1 text-xl font-bold">{value}</p>
    </div>
  )
}

function paidLine(e: OwnerEarningDto): string | null {
  if (!e.paidAt) return null
  return `${formatDateTime(e.paidAt)}${e.payoutReference ? ` · ${e.payoutReference}` : ''}`
}

function EarningsTable({ earnings }: { earnings: OwnerEarningDto[] }) {
  return (
    <div className="hidden overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-800 md:block">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">Earnings</caption>
        <thead className="bg-slate-50 text-slate-600 dark:bg-slate-900 dark:text-slate-400">
          <tr>
            {['Booking', 'Listing and time', 'Gross', 'Commission', 'Net', 'Status', 'Paid'].map((h) => (
              <th key={h} scope="col" className={clsx('px-3 py-2 font-medium', ['Gross', 'Commission', 'Net'].includes(h) && 'text-right')}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
          {earnings.map((e) => (
            <tr key={e.id}>
              <td className="px-3 py-2 font-mono font-semibold">{e.bookingCode}</td>
              <td className="px-3 py-2">
                <p className="break-words">{e.listingTitle}</p>
                <p className="text-xs text-slate-500">{formatWindow(e.startTime, e.endTime)}</p>
              </td>
              <td className="px-3 py-2 text-right tabular-nums">{formatINR(e.gross)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{formatINR(e.commission)}</td>
              <td className="px-3 py-2 text-right font-semibold tabular-nums">{formatINR(e.net)}</td>
              <td className="px-3 py-2"><StatusBadge kind="earning" status={e.status} /></td>
              <td className="px-3 py-2 text-xs text-slate-600 dark:text-slate-400">{paidLine(e) ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function EarningsCards({ earnings }: { earnings: OwnerEarningDto[] }) {
  return (
    <ul aria-label="Earnings list" className="space-y-3 md:hidden">
      {earnings.map((e) => (
        <li key={e.id} className="space-y-2 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="font-mono text-sm font-semibold">{e.bookingCode}</p>
            <StatusBadge kind="earning" status={e.status} />
          </div>
          <p className="break-words text-sm font-medium">{e.listingTitle}</p>
          <p className="text-xs text-slate-500">{formatWindow(e.startTime, e.endTime)}</p>
          <dl className="grid grid-cols-3 gap-2 text-sm">
            <div><dt className="text-xs text-slate-500">Gross</dt><dd>{formatINR(e.gross)}</dd></div>
            <div><dt className="text-xs text-slate-500">Commission</dt><dd>{formatINR(e.commission)}</dd></div>
            <div><dt className="text-xs text-slate-500">Net</dt><dd className="font-semibold">{formatINR(e.net)}</dd></div>
          </dl>
          {paidLine(e) && <p className="text-xs text-slate-600 dark:text-slate-400">{`Paid ${paidLine(e)}`}</p>}
        </li>
      ))}
    </ul>
  )
}

export function OwnerEarningsPage() {
  const [status, setStatus] = useState<EarningStatus | ''>('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const filters = { ...(status && { status }), ...(from && { from }), ...(to && { to }) }
  const { data, error, isPending, isPlaceholderData } = useOwnerEarnings(filters, page)

  const filter = <T,>(set: (value: T) => void) => (value: T) => {
    set(value)
    setPage(0)
  }

  const csv = useCsvExport(() => downloadEarningsCsv(filters), 'Only the newest 10,000 rows were exported — narrow the dates.')

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-xl font-semibold">Earnings</h2>
        <Button type="button" variant="secondary" loading={csv.exporting} onClick={() => void csv.run()}>
          {!csv.exporting && <Download aria-hidden className="h-4 w-4" />}
          Download CSV
        </Button>
      </div>
      <FormError message={csv.error} />

      <div className="grid gap-3 sm:grid-cols-3">
        <Select label="Status" value={status} onChange={(e) => filter(setStatus)(e.target.value as EarningStatus | '')}>
          <option value="">All statuses</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>{EARNING_STATUS_LABELS[s]}</option>
          ))}
        </Select>
        <TextField label="From" type="date" value={from} max={to || undefined} onChange={(e) => filter(setFrom)(e.target.value)} />
        <TextField label="To" type="date" value={to} min={from || undefined} onChange={(e) => filter(setTo)(e.target.value)} />
      </div>

      {isPending ? (
        <div className="flex justify-center py-12">
          <Spinner className="h-8 w-8 text-brand-600" />
        </div>
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data ? (
        <>
          <section aria-label="Totals" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Total label="Held" value={formatINR(data.totals.held)} />
            <Total label="Pending payout" value={formatINR(data.totals.pendingPayout)} />
            <Total label="Paid" value={formatINR(data.totals.paid)} />
            <Total label="Reversed" value={String(data.totals.reversedCount)} />
          </section>
          {data.earnings.content.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-slate-300 p-10 text-center dark:border-slate-700">
              <p className="text-slate-600 dark:text-slate-400">No earnings match these filters.</p>
            </div>
          ) : (
            <div className={clsx('space-y-4 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
              <EarningsTable earnings={data.earnings.content} />
              <EarningsCards earnings={data.earnings.content} />
              <Pagination page={page} totalPages={data.earnings.totalPages} onChange={setPage} />
            </div>
          )}
        </>
      ) : null}
    </div>
  )
}
