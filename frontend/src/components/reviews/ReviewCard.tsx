import type { ReactNode } from 'react'
import { formatDateTime } from '../../lib/format'
import type { ReviewDto } from '../../lib/reviews'
import { Stars } from './Stars'

/** The owner's answer under a review. */
export function OwnerReply({ review }: { review: ReviewDto }) {
  if (!review.ownerReply) return null
  return (
    <div className="mt-3 rounded-lg bg-slate-50 p-3 text-sm dark:bg-slate-800/60">
      <p className="font-medium">Reply from the owner</p>
      <p className="mt-1 whitespace-pre-line break-words">{review.ownerReply}</p>
      {review.ownerRepliedAt && <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">{formatDateTime(review.ownerRepliedAt)}</p>}
    </div>
  )
}

/** One review: who, how many stars, when, what they wrote and the owner's reply. `extra` goes under the reply. */
export function ReviewCard({
  review,
  label,
  heading,
  extra,
}: {
  review: ReviewDto
  /** The accessible name of the article. */
  label: string
  /** Replaces the author name as the headline. */
  heading?: ReactNode
  extra?: ReactNode
}) {
  return (
    <article aria-label={label} className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1">
        <div className="min-w-0 space-y-1">
          <p className="break-words font-semibold">{heading ?? review.authorName}</p>
          <Stars value={review.rating} />
        </div>
        <p className="text-xs text-slate-500 dark:text-slate-400">{formatDateTime(review.createdAt)}</p>
      </div>
      {review.comment ? (
        <p className="mt-3 whitespace-pre-line break-words text-sm">{review.comment}</p>
      ) : (
        <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">No comment</p>
      )}
      <OwnerReply review={review} />
      {extra}
    </article>
  )
}
