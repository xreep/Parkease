import clsx from 'clsx'
import { useState } from 'react'
import { FormError } from '../../components/AuthCard'
import { ReplyForm } from '../../components/reviews/ReplyForm'
import { ReviewCard } from '../../components/reviews/ReviewCard'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { Badge } from '../../components/ui/StatusBadge'
import { errorMessage } from '../../lib/errors'
import { useMyListings } from '../../lib/owner'
import { useOwnerReviews } from '../../lib/reviews'
import { usePageTitle } from '../../lib/usePageTitle'

const ALL = 'all'

export function OwnerReviewsPage() {
  usePageTitle('Owner reviews')
  const [listing, setListing] = useState<string>(ALL)
  const [page, setPage] = useState(0)
  const listingId = listing === ALL ? undefined : Number(listing)
  const listings = useMyListings(0, 100)
  const { data, error, isPending, isPlaceholderData } = useOwnerReviews(listingId, page)

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Reviews</h2>
      <Select
        label="Listing"
        className="max-w-xs"
        value={listing}
        onChange={(e) => {
          setListing(e.target.value)
          setPage(0)
        }}
      >
        <option value={ALL}>All listings</option>
        {listings.data?.content.map((l) => (
          <option key={l.id} value={l.id}>{l.title}</option>
        ))}
      </Select>

      {isPending ? (
        <div className="flex justify-center py-12">
          <Spinner className="h-8 w-8 text-brand-600" />
        </div>
      ) : error ? (
        <FormError message={errorMessage(error)} />
      ) : data.content.length === 0 && page === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 p-10 text-center dark:border-slate-700">
          <p className="text-slate-600 dark:text-slate-400">No reviews yet.</p>
        </div>
      ) : (
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
                      {review.hidden && <Badge tone="red">Hidden by ParkEase</Badge>}
                    </span>
                    <span className="block text-sm font-normal text-slate-600 dark:text-slate-400">{review.listingTitle}</span>
                    <span className="block font-mono text-xs font-normal text-slate-500 dark:text-slate-400">{review.bookingCode}</span>
                  </>
                }
                extra={
                  review.hidden ? (
                    <p className="mt-3 rounded-lg bg-slate-100 px-3 py-2 text-sm text-slate-700 dark:bg-slate-800 dark:text-slate-300">
                      This review is hidden and doesn’t count towards your rating.
                    </p>
                  ) : review.ownerReply ? undefined : (
                    <ReplyForm reviewId={review.id} />
                  )
                }
              />
            ))}
          </div>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  )
}
