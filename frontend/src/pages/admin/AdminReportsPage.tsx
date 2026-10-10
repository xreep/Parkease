import clsx from 'clsx'
import { Download } from 'lucide-react'
import { useState } from 'react'
import { FormError } from '../../components/AuthCard'
import { Empty, Loading } from '../../components/admin/common'
import { StateCityFilter, type StateCity } from '../../components/admin/StateCityFilter'
import { Button } from '../../components/ui/Button'
import { TextField } from '../../components/ui/TextField'
import { panelId, tabId } from '../../components/ui/tabIds'
import { ViewTabs } from '../../components/ui/ViewTabs'
import {
  downloadReportCsv,
  useReport,
  type ReportDto,
  type ReportFilters,
  type ReportKind,
  type RevenueRow,
  type UsageRow,
} from '../../lib/admin'
import { errorMessage } from '../../lib/errors'
import { formatINR, formatPercent } from '../../lib/format'
import { statsRange } from '../../lib/ownerDashboard'
import { useCsvExport } from '../../lib/useCsvExport'

type Column<Row, Totals> = {
  header: string
  cell: (row: Row) => string
  total: (totals: Totals) => string
  numeric?: boolean
}

const count = (n: number) => String(n)

const USAGE_COLUMNS: Column<UsageRow, ReportDto<UsageRow>['totals']>[] = [
  { header: 'Listings', cell: (r) => count(r.listings), total: (t) => count(t.listings), numeric: true },
  { header: 'Slots', cell: (r) => count(r.slots), total: (t) => count(t.slots), numeric: true },
  { header: 'Bookings', cell: (r) => count(r.bookings), total: (t) => count(t.bookings), numeric: true },
  { header: 'Booked hours', cell: (r) => count(r.bookedHours), total: (t) => count(t.bookedHours), numeric: true },
  { header: 'Utilization', cell: (r) => formatPercent(r.utilizationPercent), total: (t) => formatPercent(t.utilizationPercent), numeric: true },
  { header: 'Cancellations', cell: (r) => count(r.cancellations), total: (t) => count(t.cancellations), numeric: true },
]

const REVENUE_COLUMNS: Column<RevenueRow, ReportDto<RevenueRow>['totals']>[] = [
  { header: 'Bookings', cell: (r) => count(r.bookings), total: (t) => count(t.bookings), numeric: true },
  { header: 'GMV', cell: (r) => formatINR(r.gmv), total: (t) => formatINR(t.gmv), numeric: true },
  { header: 'Platform fees', cell: (r) => formatINR(r.platformFees), total: (t) => formatINR(t.platformFees), numeric: true },
  { header: 'GST', cell: (r) => formatINR(r.gst), total: (t) => formatINR(t.gst), numeric: true },
  { header: 'Refunds', cell: (r) => formatINR(r.refunds), total: (t) => formatINR(t.refunds), numeric: true },
  { header: 'Owner earnings', cell: (r) => formatINR(r.ownerEarnings), total: (t) => formatINR(t.ownerEarnings), numeric: true },
]

type BaseRow = { cityId: number; cityName: string; stateName: string }

function ReportTable<Row extends BaseRow, Totals>({
  title,
  columns,
  rows,
  totals,
}: {
  title: string
  columns: Column<Row, Totals>[]
  rows: Row[]
  totals: Totals
}) {
  return (
    <>
      <div className="hidden overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-800 md:block">
        <table className="w-full text-left text-sm">
          <caption className="sr-only">{title}</caption>
          <thead className="bg-slate-50 text-slate-600 dark:bg-slate-900 dark:text-slate-400">
            <tr>
              <th scope="col" className="px-3 py-2 font-medium">City</th>
              {columns.map((c) => (
                <th key={c.header} scope="col" className={clsx('px-3 py-2 font-medium', c.numeric && 'text-right')}>{c.header}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
            {rows.map((row) => (
              <tr key={row.cityId}>
                <th scope="row" className="px-3 py-2 font-normal">{`${row.cityName}, ${row.stateName}`}</th>
                {columns.map((c) => (
                  <td key={c.header} className={clsx('px-3 py-2', c.numeric && 'text-right tabular-nums')}>{c.cell(row)}</td>
                ))}
              </tr>
            ))}
          </tbody>
          <tfoot className="border-t-2 border-slate-300 bg-slate-50 font-semibold dark:border-slate-700 dark:bg-slate-900">
            <tr>
              <th scope="row" className="px-3 py-2">Total</th>
              {columns.map((c) => (
                <td key={c.header} className={clsx('px-3 py-2', c.numeric && 'text-right tabular-nums')}>{c.total(totals)}</td>
              ))}
            </tr>
          </tfoot>
        </table>
      </div>

      <ul aria-label={`${title} list`} className="space-y-3 md:hidden">
        {rows.map((row) => (
          <li key={row.cityId} className="space-y-2 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <p className="font-semibold">{`${row.cityName}, ${row.stateName}`}</p>
            <dl className="grid grid-cols-2 gap-2 text-sm">
              {columns.map((c) => (
                <div key={c.header}><dt className="text-xs text-slate-500">{c.header}</dt><dd>{c.cell(row)}</dd></div>
              ))}
            </dl>
          </li>
        ))}
        <li className="space-y-2 rounded-2xl border-2 border-slate-300 bg-slate-50 p-4 dark:border-slate-700 dark:bg-slate-900">
          <p className="font-semibold">Total</p>
          <dl className="grid grid-cols-2 gap-2 text-sm">
            {columns.map((c) => (
              <div key={c.header}><dt className="text-xs text-slate-500">{c.header}</dt><dd className="font-semibold">{c.total(totals)}</dd></div>
            ))}
          </dl>
        </li>
      </ul>
    </>
  )
}

function ReportBody({ kind, filters, enabled }: { kind: ReportKind; filters: ReportFilters; enabled: boolean }) {
  const usage = useReport('usage', filters, enabled && kind === 'usage')
  const revenue = useReport('revenue', filters, enabled && kind === 'revenue')
  const query = kind === 'usage' ? usage : revenue
  const { data, error, isPending, isPlaceholderData } = query

  if (!enabled) return <Empty>Choose a valid date range.</Empty>
  if (isPending) return <Loading />
  if (error && !data) return <FormError message={errorMessage(error)} />
  if (!data) return null
  if (data.rows.length === 0) return <Empty>No activity in this period.</Empty>
  return (
    <div className={clsx('transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
      {kind === 'usage' && usage.data ? (
        <ReportTable title="Usage report" columns={USAGE_COLUMNS} rows={usage.data.rows} totals={usage.data.totals} />
      ) : kind === 'revenue' && revenue.data ? (
        <ReportTable title="Revenue report" columns={REVENUE_COLUMNS} rows={revenue.data.rows} totals={revenue.data.totals} />
      ) : null}
    </div>
  )
}

const TABS: { value: ReportKind; label: string }[] = [
  { value: 'usage', label: 'Usage' },
  { value: 'revenue', label: 'Revenue' },
]

export function AdminReportsPage() {
  const initial = statsRange(30)
  const [kind, setKind] = useState<ReportKind>('usage')
  const [from, setFrom] = useState(initial.from)
  const [to, setTo] = useState(initial.to)
  const [place, setPlace] = useState<StateCity>({})
  const filters: ReportFilters = { from, to, ...place }
  const valid = from !== '' && to !== '' && from <= to
  const csv = useCsvExport(() => downloadReportCsv(kind, filters), 'The export was cut at the row limit — narrow the filters.')

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-xl font-semibold">Reports</h2>
        <Button type="button" variant="secondary" loading={csv.exporting} disabled={!valid} onClick={() => void csv.run()}>
          {!csv.exporting && <Download aria-hidden className="h-4 w-4" />}
          Download CSV
        </Button>
      </div>
      <FormError message={csv.error} />

      <ViewTabs idPrefix="admin-reports" label="Reports" items={TABS} value={kind} onChange={setKind} />

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <TextField label="From" type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
        <TextField label="To" type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
        <StateCityFilter value={place} onChange={setPlace} />
      </div>

      <div role="tabpanel" id={panelId('admin-reports', kind)} aria-labelledby={tabId('admin-reports', kind)} className="space-y-4">
        <ReportBody kind={kind} filters={filters} enabled={valid} />
      </div>
    </div>
  )
}
