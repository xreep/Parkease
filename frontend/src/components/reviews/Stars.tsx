import clsx from 'clsx'
import { Star } from 'lucide-react'
import { ratingText } from '../../lib/reviews'

const FIVE = [0, 1, 2, 3, 4]

/** A read-only rating out of five (fractions fill part of a star). The text alternative carries the number. */
export function Stars({ value, className }: { value: number; className?: string }) {
  const filled = Math.max(0, Math.min(5, value)) * 20
  const row = (tone: string) => (
    <span className={clsx('flex gap-0.5', tone)}>
      {FIVE.map((i) => (
        <Star key={i} aria-hidden className="h-4 w-4 shrink-0 fill-current" />
      ))}
    </span>
  )
  return (
    <span role="img" aria-label={`${ratingText(value)} out of 5 stars`} className={clsx('relative inline-flex', className)}>
      {row('text-slate-300 dark:text-slate-700')}
      <span className="absolute inset-y-0 left-0 overflow-hidden" style={{ width: `${filled}%` }}>
        {row('text-amber-500')}
      </span>
    </span>
  )
}
