import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { errorMessage, toProblem } from '../../lib/errors'
import { invalidateOwnerReviews, REPLY_MAX, replyToReview } from '../../lib/reviews'
import { Button } from '../ui/Button'
import { TextArea } from '../ui/TextArea'

/** The owner's reply to one review (once: the server answers ALREADY_REPLIED to a second one). */
export function ReplyForm({ reviewId }: { reviewId: number }) {
  const queryClient = useQueryClient()
  const [reply, setReply] = useState('')
  const [error, setError] = useState<string | undefined>()
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    const text = reply.trim()
    if (!text) {
      setError('Write a reply first')
      return
    }
    setBusy(true)
    setError(undefined)
    try {
      await replyToReview(reviewId, text)
      toast.success('Reply posted')
    } catch (err) {
      // One message, one place: the toast for a reply that already exists (the form is about to go away),
      // inline under the field for anything the owner can fix or retry.
      if (toProblem(err).code === 'ALREADY_REPLIED') toast.error("You've already replied to this review.")
      else setError(errorMessage(err))
    } finally {
      // Either way the list now shows the truth: our reply, or the one that was already there.
      await invalidateOwnerReviews(queryClient)
      setBusy(false)
    }
  }

  return (
    <form onSubmit={(e) => void submit(e)} noValidate className="mt-3 space-y-2">
      <TextArea
        label="Your reply"
        rows={3}
        maxLength={REPLY_MAX}
        value={reply}
        error={error}
        onChange={(e) => {
          setReply(e.target.value)
          setError(undefined)
        }}
        hint={`${reply.length}/${REPLY_MAX}`}
      />
      <Button type="submit" className="px-3 py-1.5" loading={busy}>Post reply</Button>
    </form>
  )
}
