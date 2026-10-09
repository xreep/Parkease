import { useId, useState } from 'react'
import { FormError } from '../AuthCard'
import { Button } from '../ui/Button'
import { TextArea } from '../ui/TextArea'
import { toProblem } from '../../lib/errors'
import { REVIEW_COMMENT_MAX, submitReview, type ReviewDto } from '../../lib/reviews'
import { StarPicker } from './StarPicker'

const ERROR_COPY: Record<string, string> = {
  ALREADY_REVIEWED: "You've already reviewed this booking.",
}

/**
 * Rate a completed booking: 1-5 stars and an optional comment. `onPosted` gets the stored review; `onStale` runs when
 * the server says the booking was already reviewed or can't be reviewed, so the parent can refetch what it shows.
 */
export function ReviewForm({
  bookingId,
  onPosted,
  onStale,
}: {
  bookingId: number
  onPosted: (review: ReviewDto) => void
  onStale: () => void
}) {
  const name = useId()
  const [rating, setRating] = useState(0)
  const [comment, setComment] = useState('')
  const [ratingError, setRatingError] = useState<string | undefined>()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (rating === 0) {
      setRatingError('Choose a star rating')
      return
    }
    setBusy(true)
    setError(null)
    try {
      onPosted(await submitReview(bookingId, rating, comment.trim() || undefined))
    } catch (err) {
      const problem = toProblem(err)
      setError(ERROR_COPY[problem.code] ?? problem.detail)
      if (problem.code === 'ALREADY_REVIEWED' || problem.code === 'NOT_REVIEWABLE') onStale()
      setBusy(false)
    }
  }

  return (
    <form onSubmit={(e) => void submit(e)} noValidate className="space-y-4">
      <FormError message={error} />
      <StarPicker
        name={name}
        value={rating}
        error={ratingError}
        onChange={(n) => {
          setRating(n)
          setRatingError(undefined)
        }}
      />
      <TextArea
        label="Comment (optional)"
        rows={4}
        maxLength={REVIEW_COMMENT_MAX}
        value={comment}
        onChange={(e) => setComment(e.target.value)}
        hint={`${comment.length}/${REVIEW_COMMENT_MAX}`}
      />
      <Button type="submit" loading={busy}>Submit review</Button>
    </form>
  )
}
