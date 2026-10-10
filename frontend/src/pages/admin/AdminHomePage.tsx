import clsx from 'clsx'
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { FormError } from '../../components/AuthCard'
import { Empty, Loading, primaryLink } from '../../components/admin/common'
import { BarChart } from '../../components/charts/BarChart'
import { LineChart } from '../../components/charts/LineChart'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { useAdminStats, useQueues, type AdminStatsDto } from '../../lib/admin'
import { errorMessage } from '../../lib/errors'
import { formatCompactINR, formatINR, formatPercent, plural } from '../../lib/format'
import { STATS_RANGES, type StatsRange } from '../../lib/ownerDashboard'
import { formatShortDate } from '../../lib/time'
import { usePageTitle } from '../../lib/usePageTitle'


function Kpi({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div role="group" aria-label={label} className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <p className="text-sm text-slate-600 dark:text-slate-400">{label}</p>
      <p className="mt-1 text-2xl font-bold">{value}</p>
      {note && <p className="text-xs text-slate-500 dark:text-slate-400">{note}</p>}
    </div>
  )
}

function QueueCard({ summary, to, action }: { summary: string; to: string; action: string }) {
  return (
    <section className="flex flex-col gap-4 rounded-2xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
      <p className="text-lg font-semibold">{summary}</p>
      <div>
        <Link to={to} className={primaryLink}>{action}</Link>
      </div>
    </section>
  )
}

function Queues() {
  const { data, error, isPending } = useQueues()
  if (isPending) return <Spinner className="h-5 w-5 text-brand-600" />
  if (error) return <FormError message={errorMessage(error)} />
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <QueueCard
        summary={`${plural(data.pendingOwners, 'owner', 'owners')} waiting for verification`}
        to="/admin/owners"
        action="Review owners"
      />
      <QueueCard
        summary={`${plural(data.pendingListings, 'listing', 'listings')} waiting for approval`}
        to="/admin/listings"
        action="Review listings"
      />
    </div>
  )
}

function TopTable({ title, nameHeader, rows }: { title: string; nameHeader: string; rows: { key: number; name: string; bookings: number; gmv: number }[] }) {
  return (
    <section className="space-y-2">
      <h3 className="font-semibold">{title}</h3>
      {rows.length === 0 ? (
        <Empty>No bookings in this period.</Empty>
      ) : (
        <div className="overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-800">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">{title}</caption>
            <thead className="bg-slate-50 text-slate-600 dark:bg-slate-900 dark:text-slate-400">
              <tr>
                <th scope="col" className="px-3 py-2 font-medium">{nameHeader}</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Bookings</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">GMV</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
              {rows.map((r) => (
                <tr key={r.key}>
                  <td className="px-3 py-2">{r.name}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{r.bookings}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{formatINR(r.gmv)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function ChartSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-2">
      <h3 className="font-semibold">{title}</h3>
      {children}
    </section>
  )
}

function StatsView({ stats }: { stats: AdminStatsDto }) {
  const { users, listings, bookings, money, series } = stats
  const points = (pick: (d: AdminStatsDto['series'][number]) => number) =>
    series.map((d) => ({ label: formatShortDate(d.date), value: pick(d) }))
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Kpi label="Users" value={String(users.drivers + users.owners)} note={`${users.newDrivers + users.newOwners} new`} />
        <Kpi label="Listings" value={String(listings.approved)} note={`${listings.pendingReview} awaiting review`} />
        <Kpi label="Bookings" value={String(bookings.created)} note={`${bookings.cancelled} cancelled`} />
        <Kpi label="Conversion" value={formatPercent(bookings.conversionPercent)} note={`${bookings.confirmed} confirmed`} />
        <Kpi label="Utilization" value={formatPercent(bookings.utilizationPercent)} />
        <Kpi label="GMV" value={formatINR(money.gmv)} note="After refunds" />
        <Kpi label="Revenue" value={formatINR(money.platformRevenue)} note="Platform fees" />
        <Kpi label="Refunds" value={formatINR(money.refunds)} />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <ChartSection title="Bookings">
          <BarChart title="Bookings per day" points={points((d) => d.bookings)} formatValue={(n) => String(n)} />
        </ChartSection>
        <ChartSection title="GMV and revenue">
          <LineChart title="GMV per day" points={points((d) => d.gmv)} formatValue={formatINR} formatAxis={formatCompactINR} />
          <LineChart
            title="Platform revenue per day"
            points={points((d) => d.revenue)}
            formatValue={formatINR}
            formatAxis={formatCompactINR}
            className="stroke-amber-500 dark:stroke-amber-400"
          />
        </ChartSection>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <TopTable
          title="Top states"
          nameHeader="State"
          rows={stats.topStates.map((s) => ({ key: s.stateId, name: s.name, bookings: s.bookings, gmv: s.gmv }))}
        />
        <TopTable
          title="Top cities"
          nameHeader="City"
          rows={stats.topCities.map((c) => ({ key: c.cityId, name: `${c.name}, ${c.stateName}`, bookings: c.bookings, gmv: c.gmv }))}
        />
      </div>
    </div>
  )
}

export function AdminHomePage() {
  usePageTitle('Admin · Overview')
  const [days, setDays] = useState<StatsRange>(30)
  const { data, error, isPending, isPlaceholderData } = useAdminStats(days)

  return (
    <div className="space-y-8">
      <section aria-label="Platform numbers" className="space-y-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <h2 className="text-xl font-semibold">Platform numbers</h2>
          <Select label="Range" value={days} onChange={(e) => setDays(Number(e.target.value) as StatsRange)}>
            {STATS_RANGES.map((n) => (
              <option key={n} value={n}>{`Last ${n} days`}</option>
            ))}
          </Select>
        </div>
        {isPending && !error ? (
          <Loading />
        ) : error && !data ? (
          <FormError message={errorMessage(error)} />
        ) : data ? (
          <div className={clsx('transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
            <StatsView stats={data} />
          </div>
        ) : null}
      </section>

      <section aria-label="Waiting for review" className="space-y-3">
        <h2 className="text-xl font-semibold">Waiting for review</h2>
        <Queues />
      </section>
    </div>
  )
}
