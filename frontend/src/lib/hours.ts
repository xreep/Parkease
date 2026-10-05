import { z } from 'zod'
import { DAY_NAMES } from './format'
import type { Hours } from './owner'

export type DayValues = { enabled: boolean; openTime: string; closeTime: string }
export type HoursFormValues = { open24x7: boolean; days: DayValues[] }

export const DEFAULT_OPEN = '09:00'
export const DEFAULT_CLOSE = '18:00'

const dayShape = z.object({ enabled: z.boolean(), openTime: z.string(), closeTime: z.string() })

export const hoursSchema = z
  .object({ open24x7: z.boolean(), days: z.array(dayShape).length(7) })
  .superRefine((value, ctx) => {
    if (value.open24x7) return
    if (!value.days.some((d) => d.enabled)) {
      ctx.addIssue({ code: 'custom', path: ['days'], message: 'Choose at least one open day, or switch on Open 24 × 7' })
      return
    }
    value.days.forEach((d, i) => {
      if (!d.enabled) return
      if (!d.openTime || !d.closeTime) {
        ctx.addIssue({ code: 'custom', path: ['days', i, 'closeTime'], message: `${DAY_NAMES[i]}: enter opening and closing times` })
      } else if (d.closeTime <= d.openTime) {
        ctx.addIssue({ code: 'custom', path: ['days', i, 'closeTime'], message: `${DAY_NAMES[i]}: closing time must be after opening time` })
      }
    })
  })

const hhmm = (t: string) => t.slice(0, 5)

/** Builds the form state from the saved hours; days without a rule start unticked with default times. */
export function hoursToForm(hours: Hours | undefined): HoursFormValues {
  return {
    open24x7: hours?.open24x7 ?? false,
    days: DAY_NAMES.map((_, i) => {
      const rule = hours?.rules.find((r) => r.dayOfWeek === i + 1)
      return rule
        ? { enabled: true, openTime: hhmm(rule.openTime), closeTime: hhmm(rule.closeTime) }
        : { enabled: false, openTime: DEFAULT_OPEN, closeTime: DEFAULT_CLOSE }
    }),
  }
}

/** Only ticked days become rules, and none are sent when the listing is open 24 x 7. */
export function formToHours(values: HoursFormValues): Hours {
  return {
    open24x7: values.open24x7,
    rules: values.open24x7
      ? []
      : values.days.flatMap((d, i) => (d.enabled ? [{ dayOfWeek: i + 1, openTime: d.openTime, closeTime: d.closeTime }] : [])),
  }
}
