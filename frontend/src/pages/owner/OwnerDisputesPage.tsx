import { useState } from 'react'
import { FormError } from '../../components/AuthCard'
import { Empty, Loading } from '../../components/admin/common'
import { DisputeList } from '../../components/disputes/DisputeView'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { DISPUTE_STATUS_LABELS, useOwnerDisputes, type DisputeStatus } from '../../lib/disputes'
import { errorMessage } from '../../lib/errors'

export function OwnerDisputesPage() {
  const [status, setStatus] = useState<DisputeStatus | ''>('')
  const [page, setPage] = useState(0)
  const { data, error, isPending } = useOwnerDisputes(status || undefined, page)

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h2 className="text-xl font-semibold">Disputes</h2>
        <Select
          label="Show"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as DisputeStatus | '')
            setPage(0)
          }}
        >
          <option value="">All reports</option>
          {(Object.keys(DISPUTE_STATUS_LABELS) as DisputeStatus[]).map((s) => (
            <option key={s} value={s}>{DISPUTE_STATUS_LABELS[s]}</option>
          ))}
        </Select>
      </div>
      {isPending ? (
        <Loading />
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data && data.content.length === 0 && page === 0 ? (
        <Empty>No reports on your bookings.</Empty>
      ) : data ? (
        <>
          <DisputeList items={data.content} label="Reports" href={(d) => `/owner/disputes/${d.id}`} />
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      ) : null}
    </div>
  )
}
