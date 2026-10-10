import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { z } from 'zod'
import { FormError } from '../../components/AuthCard'
import { parseId } from '../../lib/params'
import { DisputePageShell } from '../../components/disputes/DisputePageShell'
import { DisputeView } from '../../components/disputes/DisputeView'
import { Button } from '../../components/ui/Button'
import { TextArea } from '../../components/ui/TextArea'
import { invalidateDisputes, respondToDispute, RESPONSE_MAX, useOwnerDispute, type Dispute } from '../../lib/disputes'
import { errorMessage, toProblem } from '../../lib/errors'
import { usePageTitle } from '../../lib/usePageTitle'

const schema = z.object({
  response: z.string().trim().min(1, 'Write your response').max(RESPONSE_MAX, `Use at most ${RESPONSE_MAX} characters`),
})
type Values = z.infer<typeof schema>

function ResponseSection({ dispute }: { dispute: Dispute }) {
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { response: '' },
  })
  const canRespond = dispute.status !== 'RESOLVED' && dispute.ownerResponse === null

  async function submit({ response }: Values) {
    setFormError(null)
    try {
      await respondToDispute(dispute.id, response)
      toast.success('Response sent')
      await invalidateDisputes(queryClient)
    } catch (error) {
      // Kept here, not in the form: a refetch can remove the form (already answered) and the reason must stay visible.
      setFormError(errorMessage(error))
      if (toProblem(error).status === 409) await invalidateDisputes(queryClient)
    }
  }

  if (!canRespond && !formError) return null
  return (
    <section aria-label="Respond" className="space-y-3 rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
      <FormError message={formError} />
      {canRespond && (
        <form onSubmit={handleSubmit(submit)} noValidate className="space-y-3">
          <TextArea
            label="Your response"
            rows={4}
            hint="You can respond once. The driver and the ParkEase team will see it."
            error={errors.response?.message}
            {...register('response')}
          />
          <Button type="submit" loading={isSubmitting}>Send response</Button>
        </form>
      )}
    </section>
  )
}

export function OwnerDisputeDetailPage() {
  usePageTitle('Dispute details')
  const { id } = useParams()
  const query = useOwnerDispute(parseId(id))
  return (
    <div className="space-y-4">
      <Link to="/owner/disputes" className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-400">← Disputes</Link>
      <DisputePageShell id={id} query={query}>
        {(dispute) => (
          <DisputeView dispute={dispute} title={`Report on ${dispute.bookingCode}`} noResponseText="You have not responded yet.">
            <ResponseSection dispute={dispute} />
          </DisputeView>
        )}
      </DisputePageShell>
    </div>
  )
}
