import clsx from 'clsx'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { FormError } from '../../components/AuthCard'
import { Empty, Loading, linkClass } from '../../components/admin/common'
import { SearchBox } from '../../components/admin/SearchBox'
import { StateCityFilter, type StateCity } from '../../components/admin/StateCityFilter'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { TextField } from '../../components/ui/TextField'
import { useAdminBookings, type AdminBookingFilters } from '../../lib/adminManage'
import { BOOKING_STATUS_LABELS, type BookingStatus } from '../../lib/bookings'
import { errorMessage } from '../../lib/errors'
import { formatINR } from '../../lib/format'
import { formatWindow } from '../../lib/time'
import { stepBackIfEmpty, withPageReset } from '../../lib/paging'

const STATUSES = Object.keys(BOOKING_STATUS_LABELS) as BookingStatus[]

export function AdminBookingsPage() {
  const [status, setStatus] = useState<BookingStatus | ''>('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [place, setPlace] = useState<StateCity>({})
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const filters: AdminBookingFilters = {
    ...(status && { status }),
    ...(q && { q }),
    ...(place.cityId !== undefined && { cityId: place.cityId }),
    ...(from && { from }),
    ...(to && { to }),
  }
  const { data, error, isPending, isPlaceholderData } = useAdminBookings(filters, page)

  const filter = withPageReset(setPage)
  stepBackIfEmpty(page, setPage, data?.content, isPlaceholderData)

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Bookings</h2>

      <div className="space-y-3">
        <SearchBox label="Search bookings" placeholder="Booking code, driver email or listing" onSearch={filter(setQ)} />
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <Select label="Status" value={status} onChange={(e) => filter(setStatus)(e.target.value as BookingStatus | '')}>
            <option value="">All statuses</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>{BOOKING_STATUS_LABELS[s]}</option>
            ))}
          </Select>
          <TextField label="From" type="date" value={from} max={to || undefined} onChange={(e) => filter(setFrom)(e.target.value)} />
          <TextField label="To" type="date" value={to} min={from || undefined} onChange={(e) => filter(setTo)(e.target.value)} />
          <StateCityFilter value={place} onChange={filter(setPlace)} />
        </div>
        <p className="text-xs text-slate-500">Pick a city to filter by place — the state only narrows the city list.</p>
      </div>

      {isPending ? (
        <Loading />
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data && data.content.length === 0 ? (
        <Empty>No bookings match these filters.</Empty>
      ) : data ? (
        <div className={clsx('space-y-4 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
          <div className="overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-800">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Bookings</caption>
              <thead className="bg-slate-50 text-slate-600 dark:bg-slate-900 dark:text-slate-400">
                <tr>
                  {['Booking', 'Listing', 'Driver', 'Parking time', 'Total', 'Payment'].map((h) => (
                    <th key={h} scope="col" className={clsx('whitespace-nowrap px-3 py-2 font-medium', h === 'Total' && 'text-right')}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
                {data.content.map((b) => (
                  <tr key={b.id} className="align-top">
                    <td className="space-y-1 px-3 py-2">
                      <Link to={`/admin/bookings/${b.id}`} className={clsx('font-mono font-semibold', linkClass)}>{b.bookingCode}</Link>
                      <p><StatusBadge kind="booking" status={b.status} /></p>
                    </td>
                    <td className="px-3 py-2">
                      <p className="break-words">{b.listingTitle}</p>
                      <p className="text-xs text-slate-500">{b.cityName}</p>
                    </td>
                    <td className="px-3 py-2">
                      <p>{b.driverName}</p>
                      <p className="break-all text-xs text-slate-500">{b.driverEmail}</p>
                    </td>
                    <td className="px-3 py-2 text-xs text-slate-600 dark:text-slate-400">{formatWindow(b.startTime, b.endTime)}</td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      <p>{formatINR(b.totalAmount)}</p>
                      {b.refundAmount > 0 && <p className="text-xs text-slate-500">{`Refunded ${formatINR(b.refundAmount)}`}</p>}
                    </td>
                    <td className="px-3 py-2">{b.paymentStatus ? <StatusBadge kind="payment" status={b.paymentStatus} /> : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </div>
      ) : null}
    </div>
  )
}
