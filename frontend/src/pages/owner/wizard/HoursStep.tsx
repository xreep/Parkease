import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { FormError } from '../../../components/AuthCard'
import { HoursEditor } from '../../../components/owner/HoursEditor'
import { Spinner } from '../../../components/ui/Spinner'
import { errorMessage } from '../../../lib/errors'
import { formToHours, hoursSchema, hoursToForm, type HoursFormValues } from '../../../lib/hours'
import { saveHours, useHours, type Hours } from '../../../lib/owner'
import { StepFooter } from './StepFooter'
import { isReadOnly, type StepProps } from './types'

function HoursForm({ listing, hours, onSaved }: StepProps & { hours: Hours }) {
  const queryClient = useQueryClient()
  const readOnly = isReadOnly(listing)
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<HoursFormValues>({
    resolver: zodResolver(hoursSchema),
    defaultValues: hoursToForm(hours),
  })

  async function onSubmit(values: HoursFormValues) {
    setFormError(null)
    try {
      const saved = await saveHours(listing.id, formToHours(values))
      queryClient.setQueryData(['owner', 'hours', listing.id], saved)
      onSaved(6)
    } catch (error) {
      setFormError(errorMessage(error))
    }
  }

  return (
    <form onSubmit={form.handleSubmit(onSubmit)} noValidate className="space-y-5">
      <FormError message={formError} />
      <fieldset disabled={readOnly} className="space-y-5">
        <HoursEditor form={form} />
      </fieldset>
      <StepFooter listingId={listing.id} backStep={4} disabled={readOnly} submitting={form.formState.isSubmitting} />
    </form>
  )
}

export function HoursStep(props: StepProps) {
  const { data, error, isPending } = useHours(props.listing.id)
  if (isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner className="h-8 w-8 text-brand-600" />
      </div>
    )
  }
  if (error) return <FormError message={errorMessage(error)} />
  return <HoursForm {...props} hours={data} />
}
