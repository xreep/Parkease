import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, type FieldPath } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { FormError } from '../../components/AuthCard'
import { Loading } from '../../components/admin/common'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { TextField } from '../../components/ui/TextField'
import { invalidateAdminActivity, saveSettings, useAdminSettings, type PlatformSettings, type PriceGuideline } from '../../lib/admin'
import { toProblem } from '../../lib/errors'

const MAX_HOURLY = 100_000
const TIER_NAMES: Record<PriceGuideline['tier'], string> = { 1: 'Metro', 2: 'Large city', 3: 'Other city' }
const TIER_HELP: Record<PriceGuideline['tier'], string> = {
  1: 'Metro cities',
  2: 'State capitals and large cities',
  3: 'Every other city',
}

/** A number typed as text: blank or non-numeric fails the same check as out of range. */
const bounded = (min: number, max: number, message: string, whole = false) =>
  z.string().trim().refine((v) => {
    if (v === '') return false
    const n = Number(v)
    return Number.isFinite(n) && n >= min && n <= max && (!whole || Number.isInteger(n))
  }, message)

/** A percentage in range with at most two decimals (the server keeps two). */
const percent = (max: number, label: string) =>
  bounded(0, max, `${label} must be between 0 and ${max}`).refine((v) => !/\.\d{3,}/.test(v), `${label} can have at most 2 decimals`)

const hourly = z.string().trim().refine((v) => {
  if (v === '' || !/^\d+(\.\d{1,2})?$/.test(v)) return false
  const n = Number(v)
  return n > 0 && n <= MAX_HOURLY
}, `Enter an amount above ₹0, up to ₹${MAX_HOURLY.toLocaleString('en-IN')}`)

const schema = z.object({
  platformFeePercent: percent(50, 'Fee'),
  gstPercent: percent(28, 'GST'),
  holdMinutes: bounded(5, 60, 'Hold must be between 5 and 60 minutes', true),
  approvalHours: bounded(1, 24, 'Approval window must be between 1 and 24 hours', true),
  requestMinLeadMinutes: bounded(0, 240, 'Lead time must be between 0 and 240 minutes', true),
  priceGuidelines: z
    .array(z.object({ tier: z.number(), minHourly: hourly, maxHourly: hourly }))
    .superRefine((rows, ctx) => {
      rows.forEach((row, i) => {
        const min = Number(row.minHourly)
        const max = Number(row.maxHourly)
        if (row.minHourly !== '' && row.maxHourly !== '' && Number.isFinite(min) && Number.isFinite(max) && min > max) {
          ctx.addIssue({ code: 'custom', path: [i, 'minHourly'], message: 'Minimum can’t be above the maximum' })
        }
      })
    }),
})
type Values = z.infer<typeof schema>

const toValues = (s: PlatformSettings): Values => ({
  platformFeePercent: String(s.platformFeePercent),
  gstPercent: String(s.gstPercent),
  holdMinutes: String(s.holdMinutes),
  approvalHours: String(s.approvalHours),
  requestMinLeadMinutes: String(s.requestMinLeadMinutes),
  priceGuidelines: [...s.priceGuidelines]
    .sort((a, b) => a.tier - b.tier)
    .map((g) => ({ tier: g.tier, minHourly: String(g.minHourly), maxHourly: String(g.maxHourly) })),
})

const toSettings = (v: Values): PlatformSettings => ({
  platformFeePercent: Number(v.platformFeePercent),
  gstPercent: Number(v.gstPercent),
  holdMinutes: Number(v.holdMinutes),
  approvalHours: Number(v.approvalHours),
  requestMinLeadMinutes: Number(v.requestMinLeadMinutes),
  priceGuidelines: v.priceGuidelines.map((g) => ({
    tier: g.tier as PriceGuideline['tier'],
    minHourly: Number(g.minHourly),
    maxHourly: Number(g.maxHourly),
  })),
})

const SIMPLE_FIELDS = ['platformFeePercent', 'gstPercent', 'holdMinutes', 'approvalHours', 'requestMinLeadMinutes']

/** A server field path (`priceGuidelines[1].maxHourly`) as a form path, or null when the form has no such field. */
function formPath(field: string): FieldPath<Values> | null {
  const path = field.replace(/\[(\d+)\]/g, '.$1')
  if (SIMPLE_FIELDS.includes(path) || /^priceGuidelines\.\d+\.(minHourly|maxHourly)$/.test(path)) return path as FieldPath<Values>
  return null
}

function SettingsForm({ settings }: { settings: PlatformSettings }) {
  const queryClient = useQueryClient()
  const [pending, setPending] = useState<Values | null>(null)
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, setError, reset, formState: { errors } } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: toValues(settings),
  })

  async function confirm() {
    if (!pending) return
    setSaving(true)
    setFormError(null)
    try {
      const saved = await saveSettings(toSettings(pending))
      queryClient.setQueryData(['admin', 'settings'], saved)
      void invalidateAdminActivity(queryClient)
      reset(toValues(saved))
      toast.success('Settings saved')
    } catch (error) {
      const problem = toProblem(error)
      const general: string[] = []
      for (const fe of problem.fieldErrors) {
        const path = formPath(fe.field)
        if (path) setError(path, { message: fe.message })
        else general.push(fe.message)
      }
      if (problem.fieldErrors.length === 0 || general.length > 0) setFormError(general.length > 0 ? general.join(' ') : problem.detail)
    } finally {
      setSaving(false)
      setPending(null)
    }
  }

  const guidelineErrors = errors.priceGuidelines

  return (
    <form onSubmit={handleSubmit((values) => setPending(values))} noValidate className="space-y-8">
      <FormError message={formError} />

      <section aria-labelledby="pricing-rules" className="space-y-4 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <h3 id="pricing-rules" className="font-semibold">Fees and booking rules</h3>
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Platform fee (%)" type="number" inputMode="decimal" step="any" hint="Charged on the parking amount (0 to 50)" error={errors.platformFeePercent?.message} {...register('platformFeePercent')} />
          <TextField label="GST (%)" type="number" inputMode="decimal" step="any" hint="Applied on the platform fee (0 to 28)" error={errors.gstPercent?.message} {...register('gstPercent')} />
          <TextField label="Payment hold (minutes)" type="number" inputMode="numeric" hint="How long a slot is held for payment (5 to 60)" error={errors.holdMinutes?.message} {...register('holdMinutes')} />
          <TextField label="Owner approval window (hours)" type="number" inputMode="numeric" hint="How long owners have to answer a request (1 to 24)" error={errors.approvalHours?.message} {...register('approvalHours')} />
          <TextField label="Minimum booking lead time (minutes)" type="number" inputMode="numeric" hint="Earliest start after booking (0 to 240)" error={errors.requestMinLeadMinutes?.message} {...register('requestMinLeadMinutes')} />
        </div>
      </section>

      <section aria-labelledby="price-guidelines" className="space-y-4 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <div>
          <h3 id="price-guidelines" className="font-semibold">Hourly price guidelines</h3>
          <p className="text-sm text-slate-600 dark:text-slate-400">Owners see a warning outside the range for their city tier. It never blocks them.</p>
        </div>
        {toValues(settings).priceGuidelines.map((row, i) => {
          const tier = row.tier as PriceGuideline['tier']
          return (
            <fieldset key={row.tier} className="space-y-2">
              <legend className="text-sm font-medium">{`${TIER_NAMES[tier]} · ${TIER_HELP[tier]}`}</legend>
              <div className="grid gap-4 sm:grid-cols-2">
                <TextField label={`${TIER_NAMES[tier]} minimum (₹ per hour)`} type="number" inputMode="decimal" step="any" error={guidelineErrors?.[i]?.minHourly?.message} {...register(`priceGuidelines.${i}.minHourly`)} />
                <TextField label={`${TIER_NAMES[tier]} maximum (₹ per hour)`} type="number" inputMode="decimal" step="any" error={guidelineErrors?.[i]?.maxHourly?.message} {...register(`priceGuidelines.${i}.maxHourly`)} />
              </div>
            </fieldset>
          )
        })}
      </section>

      <div className="flex justify-end">
        <Button type="submit">Save settings</Button>
      </div>

      <Dialog open={pending !== null} title="Save these settings?" onClose={() => setPending(null)} busy={saving}>
        <div className="space-y-4">
          <p className="text-sm text-slate-700 dark:text-slate-300">
            The new values apply to new bookings only. Bookings that already exist keep the amounts they were made with.
          </p>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" disabled={saving} onClick={() => setPending(null)}>Cancel</Button>
            <Button type="button" loading={saving} onClick={() => void confirm()}>Save</Button>
          </div>
        </div>
      </Dialog>
    </form>
  )
}

export function AdminSettingsPage() {
  const { data, error, isPending } = useAdminSettings()
  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Settings</h2>
      {isPending ? <Loading /> : error ? <FormError message={toProblem(error).detail} /> : <SettingsForm settings={data} />}
    </div>
  )
}
