import { useListingReviews } from '../../lib/reviews'
import { Button } from '../ui/Button'
import { Spinner } from '../ui/Spinner'
import { ReviewCard } from './ReviewCard'
import { ReviewSummary } from './ReviewSummary'

/** The listing page's reviews: rating summary, then the reviews a page at a time. */
export function ListingReviews({ listingId }: { listingId: number | string }) {
  const { data, isPending, error, hasNextPage, fetchNextPage, isFetchingNextPage } = useListingReviews(listingId)

  return (
    <section aria-labelledby="reviews-heading" className="space-y-4">
      <h2 id="reviews-heading" className="text-lg font-semibold">Reviews</h2>
      {isPending && !error ? (
        <Spinner className="h-5 w-5 text-brand-600" />
      ) : !data ? (
        <p className="text-sm text-red-600 dark:text-red-400">Could not load reviews.</p>
      ) : data.pages[0].summary.reviewCount === 0 ? (
        <p className="text-slate-600 dark:text-slate-400">No reviews yet</p>
      ) : (
        <>
          <ReviewSummary summary={data.pages[0].summary} />
          <div className="space-y-3">
            {data.pages
              .flatMap((p) => p.reviews.content)
              .map((review) => (
                <ReviewCard key={review.id} review={review} label={`Review by ${review.authorName}`} />
              ))}
          </div>
          {error && <p className="text-sm text-red-600 dark:text-red-400">Could not load more reviews.</p>}
          {hasNextPage && (
            <Button type="button" variant="secondary" loading={isFetchingNextPage} onClick={() => void fetchNextPage()}>
              Show more reviews
            </Button>
          )}
        </>
      )}
    </section>
  )
}
