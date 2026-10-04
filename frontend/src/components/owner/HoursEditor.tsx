import { useWatch, type UseFormReturn } from 'react-hook-form'
import { DAY_NAMES } from '../../lib/format'
import { DEFAULT_CLOSE, DEFAULT_OPEN, type HoursFormValues } from '../../lib/hours'
import { Button } from '../ui/Button'

type Props = { form: UseFormReturn<HoursFormValues> }

const timeInput =
  'rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-500/30 read-only:opacity-50 dark:border-slate-700 dark:bg-slate-900'

const checkbox = 'h-4 w-4 rounded border-slate-300'

export function HoursEditor({ form }: Props) {
  const { register, control, getValues, setValue, formState: { errors } } = form
  const open24x7 = useWatch({ control, name: 'open24x7' })
  const days = useWatch({ control, name: 'days' })
  const groupError = errors.days?.message ?? errors.days?.root?.message

  const opts = { shouldDirty: true, shouldValidate: form.formState.isSubmitted }

  function copyMonday() {
    const monday = getValues('days.0')
    for (let i = 1; i < DAY_NAMES.length; i++) setValue(`days.${i}`, { ...monday }, opts)
  }

  function weekdays() {
    DAY_NAMES.forEach((_, i) => {
      setValue(`days.${i}`, { enabled: i < 5, openTime: i < 5 ? '08:00' : DEFAULT_OPEN, closeTime: i < 5 ? '22:00' : DEFAULT_CLOSE }, opts)
    })
  }

  return (
    <div className="space-y-4">
      <label className="flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-300">
        <input type="checkbox" className={checkbox} {...register('open24x7')} />
        Open 24 × 7
      </label>

      {!open24x7 && (
        <div className="space-y-3">
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="secondary" className="px-3 py-1.5" onClick={copyMonday}>
              Copy Monday to all days
            </Button>
            <Button type="button" variant="secondary" className="px-3 py-1.5" onClick={weekdays}>
              Weekdays 8 AM – 10 PM
            </Button>
          </div>
          {groupError && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{groupError}</p>}
          <ul className="divide-y divide-slate-200 rounded-2xl border border-slate-200 dark:divide-slate-800 dark:border-slate-800">
            {DAY_NAMES.map((name, i) => {
              const enabled = days?.[i]?.enabled ?? false
              const rowError = errors.days?.[i]?.closeTime?.message
              return (
                <li key={name} className="space-y-2 px-4 py-3">
                  <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
                    <label className="flex w-40 items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-300">
                      <input type="checkbox" className={checkbox} aria-label={`Open on ${name}`} {...register(`days.${i}.enabled`)} />
                      {name}
                    </label>
                    <label className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-400">
                      <span aria-hidden>Opens</span>
                      <input type="time" aria-label={`${name} opens`} className={timeInput} readOnly={!enabled} {...register(`days.${i}.openTime`)} />
                    </label>
                    <label className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-400">
                      <span aria-hidden>Closes</span>
                      <input type="time" aria-label={`${name} closes`} className={timeInput} readOnly={!enabled} aria-invalid={rowError ? true : undefined} {...register(`days.${i}.closeTime`)} />
                    </label>
                  </div>
                  {rowError && <p className="text-sm text-red-600 dark:text-red-400">{rowError}</p>}
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </div>
  )
}
