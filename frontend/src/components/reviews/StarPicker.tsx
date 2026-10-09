import clsx from 'clsx'
import { Star } from 'lucide-react'

const STARS = [1, 2, 3, 4, 5]

/**
 * Pick 1 to 5 stars. Native radio buttons, so the arrow keys, Tab and screen readers work without extra code; the
 * inputs are visually hidden and the star next to each one is its label.
 */
export function StarPicker({
  name,
  value,
  onChange,
  error,
}: {
  name: string
  value: number
  onChange: (value: number) => void
  error?: string
}) {
  return (
    <div className="space-y-1.5">
      <div role="radiogroup" aria-label="Your rating" aria-invalid={error ? true : undefined} className="flex gap-1">
        {STARS.map((n) => (
          <label key={n} className="cursor-pointer">
            <input
              type="radio"
              name={name}
              value={n}
              checked={value === n}
              onChange={() => onChange(n)}
              aria-label={`${n} ${n === 1 ? 'star' : 'stars'}`}
              className="peer sr-only"
            />
            <Star
              aria-hidden
              className={clsx(
                'h-8 w-8 rounded transition peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-brand-600',
                n <= value ? 'fill-amber-500 text-amber-500' : 'fill-transparent text-slate-400 hover:text-amber-400 dark:text-slate-500',
              )}
            />
          </label>
        ))}
      </div>
      {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}
    </div>
  )
}
