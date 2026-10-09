import { ratingText, type ReviewSummaryDto } from '../../lib/reviews'
import { Stars } from './Stars'

const ROWS = ['5', '4', '3', '2', '1'] as const

/** The average, the count and one bar per star level (highest first). */
export function ReviewSummary({ summary }: { summary: ReviewSummaryDto }) {
  const total = summary.reviewCount
  return (
    <div className="grid items-center gap-4 sm:grid-cols-[auto_minmax(0,1fr)] sm:gap-8">
      <div className="space-y-1">
        <p className="text-4xl font-bold">{ratingText(summary.avgRating)}</p>
        <Stars value={summary.avgRating} />
        <p className="text-sm text-slate-600 dark:text-slate-400">{`${total} ${total === 1 ? 'review' : 'reviews'}`}</p>
      </div>
      <ul aria-label="Rating distribution" className="space-y-1.5">
        {ROWS.map((level) => {
          const count = summary.distribution[level] ?? 0
          const percent = total > 0 ? Math.round((count / total) * 100) : 0
          return (
            <li key={level} className="grid grid-cols-[4.5rem_minmax(0,1fr)_2rem] items-center gap-3 text-sm">
              <span className="text-slate-700 dark:text-slate-300">{`${level} ${level === '1' ? 'star' : 'stars'}`}</span>
              <div className="h-2 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800">
                <div role="presentation" className="h-full rounded-full bg-amber-500" style={{ width: `${percent}%` }} />
              </div>
              <span className="text-right tabular-nums text-slate-600 dark:text-slate-400">{count}</span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
