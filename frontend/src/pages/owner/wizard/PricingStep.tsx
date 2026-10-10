import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { FormError } from '../../../components/AuthCard'
import { TextArea } from '../../../components/ui/TextArea'
import { TextField } from '../../../components/ui/TextField'
import { errorMessage, toProblem } from '../../../lib/errors'
import { usePricingGuideline } from '../../../lib/disputes'
import { AMENITY_LABELS, CANCELLATION_POLICIES, REFUND_NOTE, formatINR } from '../../../lib/format'
import { savePricing, type Amenity, type ListingDetail, type PricingBody } from '../../../lib/owner'
import { StepFooter } from './StepFooter'
import { isReadOnly, type StepProps } from './types'

const amenityKeys = Object.keys(AMENITY_LABELS) as [Amenity, ...Amenity[]]

const MAX_HOUR = 10_000
const MAX_DAY = 100_000
const MAX_MONTH = 1_000_000

const inRupees = (max: number, what: string) => `${what} must be between ₹1 and ₹${max.toLocaleString('en-IN')}`

/** A rupee amount with at most 2 decimals, or '' when the field is optional and left empty. */
function validAmount(value: string, max: number): boolean {
  return /^\d+(\.\d{1,2})?$/.test(value) && Number(value) >= 1 && Number(value) <= max
}

const optionalAmount = (max: number, what: string) =>
  z.string().trim().refine((v) => v === '' || validAmount(v, max), inRupees(max, what))

const schema = z
  .object({
    pricePerHour: z
      .string()
      .trim()
      .min(1, 'Enter the hourly price')
      .refine((v) => v === '' || validAmount(v, MAX_HOUR), inRupees(MAX_HOUR, 'Hourly price')),
    pricePerDay: optionalAmount(MAX_DAY, 'Daily price'),
    pricePerMonth: optionalAmount(MAX_MONTH, 'Monthly price'),
    cancellationPolicy: z.enum(['FLEXIBLE', 'MODERATE', 'STRICT']),
    autoApprove: z.boolean(),
    amenities: z.array(z.enum(amenityKeys)),
    rules: z.string().trim().max(2000, 'Use at most 2000 characters'),
  })
  .superRefine((v, ctx) => {
    const hour = validAmount(v.pricePerHour, MAX_HOUR) ? Number(v.pricePerHour) : null
    const day = v.pricePerDay !== '' && validAmount(v.pricePerDay, MAX_DAY) ? Number(v.pricePerDay) : null
    const month = v.pricePerMonth !== '' && validAmount(v.pricePerMonth, MAX_MONTH) ? Number(v.pricePerMonth) : null
    if (hour !== null && day !== null && day < hour) {
      ctx.addIssue({ code: 'custom', path: ['pricePerDay'], message: "Daily price can't be lower than the hourly price" })
    }
    if (month !== null && day !== null && month < day) {
      ctx.addIssue({ code: 'custom', path: ['pricePerMonth'], message: "Monthly price can't be lower than the daily price" })
    } else if (month !== null && day === null && hour !== null && month < hour) {
      ctx.addIssue({ code: 'custom', path: ['pricePerMonth'], message: "Monthly price can't be lower than the hourly price" })
    }
  })
type Values = z.infer<typeof schema>

const FIELD_NAMES: readonly string[] = ['pricePerHour', 'pricePerDay', 'pricePerMonth', 'rules']

const amountText = (n: number | null) => (n === null ? '' : String(n))

function defaults(listing: ListingDetail): Values {
  return {
    pricePerHour: amountText(listing.pricePerHour),
    pricePerDay: amountText(listing.pricePerDay),
    pricePerMonth: amountText(listing.pricePerMonth),
    cancellationPolicy: listing.cancellationPolicy ?? 'MODERATE',
    autoApprove: listing.autoApprove,
    amenities: listing.amenities,
    rules: listing.rules ?? '',
  }
}

export function PricingStep({ listing, onSaved }: StepProps) {
  const readOnly = isReadOnly(listing)
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, setError, control, formState: { errors, isSubmitting } } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: defaults(listing),
  })

  const guideline = usePricingGuideline(listing.cityId).data
  const hourly = useWatch({ control, name: 'pricePerHour' })
  const typed = validAmount(hourly.trim(), MAX_HOUR) ? Number(hourly.trim()) : null
  const range = guideline ? `${formatINR(guideline.minHourly)} to ${formatINR(guideline.maxHourly)}` : ''
  const outside = guideline && typed !== null ? (typed < guideline.minHourly ? 'below' : typed > guideline.maxHourly ? 'above' : null) : null

  async function onSubmit(values: Values) {
    setFormError(null)
    const body: PricingBody = {
      pricePerHour: Number(values.pricePerHour),
      pricePerDay: values.pricePerDay === '' ? null : Number(values.pricePerDay),
      pricePerMonth: values.pricePerMonth === '' ? null : Number(values.pricePerMonth),
      cancellationPolicy: values.cancellationPolicy,
      autoApprove: values.autoApprove,
      amenities: values.amenities,
      rules: values.rules,
    }
    try {
      await savePricing(listing.id, body)
      onSaved(5)
    } catch (error) {
      const problem = toProblem(error)
      let mapped = false
      for (const fe of problem.fieldErrors) {
        if (FIELD_NAMES.includes(fe.field)) {
          setError(fe.field as keyof Values, { message: fe.message })
          mapped = true
        }
      }
      if (!mapped) setFormError(errorMessage(error))
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-6">
      <FormError message={formError} />
      <fieldset disabled={readOnly} className="space-y-6">
        <div className="grid gap-4 sm:grid-cols-3">
          <TextField label="Price per hour (₹)" inputMode="decimal" autoComplete="off" hint={guideline ? `Usual range in ${listing.cityName}: ${range} per hour` : undefined} error={errors.pricePerHour?.message} {...register('pricePerHour')} />
          <TextField label="Price per day (₹, optional)" inputMode="decimal" autoComplete="off" error={errors.pricePerDay?.message} {...register('pricePerDay')} />
          <TextField label="Price per month (₹, optional)" inputMode="decimal" autoComplete="off" error={errors.pricePerMonth?.message} {...register('pricePerMonth')} />
        </div>
        {outside && typed !== null && (
          <p role="status" className="-mt-3 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-950/50 dark:text-amber-300">
            {`${formatINR(typed)} is ${outside} the usual range for ${listing.cityName} (${range}). You can still save this price.`}
          </p>
        )}

        <fieldset className="space-y-2">
          <legend className="text-sm font-medium text-slate-700 dark:text-slate-300">Cancellation policy</legend>
          {CANCELLATION_POLICIES.map((p) => (
            <div key={p.value} className="flex items-start gap-3 rounded-xl border border-slate-200 p-3 dark:border-slate-800">
              <input
                id={`policy-${p.value}`}
                type="radio"
                value={p.value}
                aria-describedby={`policy-${p.value}-help`}
                className="mt-1 h-4 w-4 border-slate-300"
                {...register('cancellationPolicy')}
              />
              <div>
                <label htmlFor={`policy-${p.value}`} className="text-sm font-medium">{p.label}</label>
                <p id={`policy-${p.value}-help`} className="text-sm text-slate-500">{p.help}</p>
              </div>
            </div>
          ))}
          <p className="text-sm text-slate-500">{REFUND_NOTE}</p>
        </fieldset>

        <label className="flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-300">
          <input type="checkbox" className="h-4 w-4 rounded border-slate-300" {...register('autoApprove')} />
          Approve bookings automatically
        </label>

        <fieldset className="space-y-2">
          <legend className="text-sm font-medium text-slate-700 dark:text-slate-300">Amenities</legend>
          <div className="grid gap-2 sm:grid-cols-3">
            {amenityKeys.map((a) => (
              <label key={a} className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
                <input type="checkbox" value={a} className="h-4 w-4 rounded border-slate-300" {...register('amenities')} />
                {AMENITY_LABELS[a]}
              </label>
            ))}
          </div>
        </fieldset>

        <TextArea label="Parking rules (optional)" rows={4} error={errors.rules?.message} {...register('rules')} />
      </fieldset>
      <StepFooter listingId={listing.id} backStep={3} disabled={readOnly} submitting={isSubmitting} />
    </form>
  )
}
