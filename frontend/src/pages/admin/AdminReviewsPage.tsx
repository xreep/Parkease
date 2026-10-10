import clsx from 'clsx'
import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { FormError } from '../../components/AuthCard'
import { Empty, Loading } from '../../components/admin/common'
import { SearchBox } from '../../components/admin/SearchBox'
import { ReviewCard } from '../../components/reviews/ReviewCard'
import { Button } from '../../components/ui/Button'
import { ReasonDialog } from '../../components/ui/Dialog'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { Badge } from '../../components/ui/StatusBadge'
import { invalidateAdminActivity } from '../../lib/admin'
import { adminErrorMessage, hideReview, unhideReview, useAdminReviews, type AdminReview, type ReviewFilters } from '../../lib/adminManage'
import { errorMessage } from '../../lib/errors'
import { stepBackIfEmpty } from '../../lib/paging'
import { usePageTitle } from '../../lib/usePageTitle'

type Show = 'all' | 'visible' | 'hidden'
const HIDDEN: Record<Show, boolean | undefined> = { all: undefined, visible: false, hidden: true }

function ReviewActions({ review, onHide, onChanged }: { review: AdminReview; onHide: () => void; onChanged: () => Promise<void> }) {
  const [busy, setBusy] = useState(false)

  async function unhide() {
    setBusy(true)
    try {
      await unhideReview(review.id)
      toast.success('Review is visible again')
      await onChanged()
    } catch (error) {
      toast.error(adminErrorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mt-3 space-y-2">
      {review.hidden && review.hiddenReason && (
        <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950/50 dark:text-red-300">{review.hiddenReason}</p>
      )}
      {review.hidden ? (
        <Button type="button" variant="secondary" className="px-3 py-1.5" loading={busy} onClick={() => void unhide()}>Unhide</Button>
      ) : (
        <Button type="button" variant="ghost" className="px-3 py-1.5 text-red-600 dark:text-red-400" onClick={onHide}>Hide</Button>
      )}
    </div>
  )
}

export function AdminReviewsPage() {
  usePageTitle('Admin · Reviews')
  const queryClient = useQueryClient()
  const [show, setShow] = useState<Show>('all')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [hiding, setHiding] = useState<AdminReview | null>(null)
  const filters: ReviewFilters = { ...(HIDDEN[show] !== undefined && { hidden: HIDDEN[show] }), ...(q && { q }) }
  const { data, error, isPending, isPlaceholderData } = useAdminReviews(filters, page)

  stepBackIfEmpty(page, setPage, data?.content, isPlaceholderData)

  // Hidden reviews leave the public lists and the rating aggregates.
  const refresh = () =>
    Promise.all([
      ...[['admin', 'reviews'], ['reviews'], ['listing'], ['search'], ['owner', 'reviews']].map((queryKey) => queryClient.invalidateQueries({ queryKey })),
      invalidateAdminActivity(queryClient),
    ]).then(() => undefined)

  async function confirmHide(reason: string) {
    if (!hiding) return
    await hideReview(hiding.id, reason)
    toast.success('Review hidden')
    setHiding(null)
    await refresh()
  }

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Reviews</h2>

      <div className="grid items-end gap-3 sm:grid-cols-[12rem_1fr]">
        <Select
          label="Show"
          value={show}
          onChange={(e) => {
            setShow(e.target.value as Show)
            setPage(0)
          }}
        >
          <option value="all">All reviews</option>
          <option value="visible">Visible only</option>
          <option value="hidden">Hidden only</option>
        </Select>
        <SearchBox
          label="Search reviews"
          placeholder="Comment, listing or booking code"
          onSearch={(value) => {
            setQ(value)
            setPage(0)
          }}
        />
      </div>

      {isPending ? (
        <Loading />
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data && data.content.length === 0 && page === 0 ? (
        <Empty>No reviews match these filters.</Empty>
      ) : data ? (
        <>
          <div className={clsx('space-y-3 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
            {data.content.map((review) => (
              <ReviewCard
                key={review.id}
                review={review}
                label={`Review ${review.bookingCode}`}
                heading={
                  <>
                    <span className="flex flex-wrap items-center gap-2">
                      {review.authorName}
                      {review.hidden && <Badge tone="red">Hidden</Badge>}
                    </span>
                    <span className="block text-sm font-normal text-slate-600 dark:text-slate-400">{review.listingTitle}</span>
                    <span className="block font-mono text-xs font-normal text-slate-500 dark:text-slate-400">{review.bookingCode}</span>
                  </>
                }
                extra={<ReviewActions review={review} onHide={() => setHiding(review)} onChanged={refresh} />}
              />
            ))}
          </div>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      ) : null}

      {hiding && (
        <ReasonDialog
          open
          title="Hide this review?"
          confirmLabel="Hide"
          maxLength={300}
          helper="Hidden reviews leave public lists and the listing’s rating."
          onConfirm={confirmHide}
          onClose={() => setHiding(null)}
        />
      )}
    </div>
  )
}
