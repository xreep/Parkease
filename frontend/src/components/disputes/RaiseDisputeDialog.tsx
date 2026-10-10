import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { errorMessage, toProblem } from '../../lib/errors'
import {
  DESCRIPTION_MAX,
  DESCRIPTION_MIN,
  DISPUTE_CATEGORY_LABELS,
  invalidateDisputes,
  raiseDispute,
  type DisputeCategory,
} from '../../lib/disputes'
import { FormError } from '../AuthCard'
import { Button } from '../ui/Button'
import { Dialog } from '../ui/Dialog'
import { Select } from '../ui/Select'
import { TextArea } from '../ui/TextArea'

const CATEGORIES = Object.keys(DISPUTE_CATEGORY_LABELS) as [DisputeCategory, ...DisputeCategory[]]

const schema = z.object({
  category: z.enum(CATEGORIES),
  description: z
    .string()
    .trim()
    .min(DESCRIPTION_MIN, `Describe the problem in at least ${DESCRIPTION_MIN} characters`)
    .max(DESCRIPTION_MAX, `Use at most ${DESCRIPTION_MAX} characters`),
})
type Values = z.infer<typeof schema>

function RaiseForm({ bookingId, onClose, onBusyChange }: { bookingId: number; onClose: () => void; onBusyChange: (busy: boolean) => void }) {
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { category: CATEGORIES[0], description: '' },
  })

  async function submit(values: Values) {
    setFormError(null)
    onBusyChange(true)
    try {
      await raiseDispute(bookingId, values)
      toast.success('Report sent')
      await invalidateDisputes(queryClient)
      onClose()
    } catch (error) {
      setFormError(errorMessage(error))
      // The booking changed under us (a report is already open, the window closed): make the page say so.
      if (toProblem(error).status === 409) await invalidateDisputes(queryClient)
    } finally {
      onBusyChange(false)
    }
  }

  return (
    <form onSubmit={handleSubmit(submit)} noValidate className="space-y-4">
      <FormError message={formError} />
      <Select label="What went wrong?" error={errors.category?.message} {...register('category')}>
        {CATEGORIES.map((c) => (
          <option key={c} value={c}>{DISPUTE_CATEGORY_LABELS[c]}</option>
        ))}
      </Select>
      <TextArea
        label="Describe the problem"
        rows={5}
        hint="Say what happened and when. The owner sees this and can respond."
        error={errors.description?.message}
        {...register('description')}
      />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" disabled={isSubmitting} onClick={onClose}>Cancel</Button>
        <Button type="submit" loading={isSubmitting}>Send report</Button>
      </div>
    </form>
  )
}

/** Opens a problem report on a booking. The form remounts on each open, so it starts empty. */
export function RaiseDisputeDialog({ bookingId, open, onClose }: { bookingId: number; open: boolean; onClose: () => void }) {
  const [busy, setBusy] = useState(false)
  return (
    <Dialog open={open} title="Report a problem" onClose={onClose} busy={busy}>
      <RaiseForm bookingId={bookingId} onClose={onClose} onBusyChange={setBusy} />
    </Dialog>
  )
}
