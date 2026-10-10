import { useState } from 'react'
import { FormError } from '../../components/AuthCard'
import { Empty, Loading } from '../../components/admin/common'
import { DisputeList } from '../../components/disputes/DisputeView'
import { Pagination } from '../../components/ui/Pagination'
import { useMyDisputes } from '../../lib/disputes'
import { errorMessage } from '../../lib/errors'
import { stepBackIfEmpty } from '../../lib/usePaging'

export function DisputesPage() {
  const [page, setPage] = useState(0)
  const { data, error, isPending, isPlaceholderData } = useMyDisputes(page)
  stepBackIfEmpty(page, setPage, data?.content, isPlaceholderData)

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-semibold">Help</h2>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
          Problems you reported with a booking. To report one, open the booking and press Report a problem.
        </p>
      </div>
      {isPending ? (
        <Loading />
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data && data.content.length === 0 && page === 0 ? (
        <Empty>You haven’t reported any problems.</Empty>
      ) : data ? (
        <>
          <DisputeList items={data.content} label="Your reports" href={(d) => `/driver/disputes/${d.id}`} />
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      ) : null}
    </div>
  )
}
