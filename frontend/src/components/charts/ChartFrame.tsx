import type { ReactNode } from 'react'
import type { ChartPoint } from './chartScale'

/**
 * The shared body of a chart: the drawing (stretched to the width, so no text lives inside it), HTML axis labels
 * under and beside it, and a table with the same numbers for screen readers.
 */
export function ChartFrame({
  title,
  points,
  max,
  formatValue,
  children,
}: {
  title: string
  points: ChartPoint[]
  max: string
  formatValue: (value: number) => string
  children: ReactNode
}) {
  if (points.length === 0) {
    return <p className="rounded-lg border border-dashed border-slate-300 p-6 text-center text-sm text-slate-500 dark:border-slate-700">No data for this period.</p>
  }
  const middle = points[Math.floor((points.length - 1) / 2)]
  return (
    <figure className="space-y-1">
      <div className="flex gap-2">
        <span data-axis="max" className="w-14 shrink-0 text-right text-xs tabular-nums text-slate-500">{max}</span>
        <div className="min-w-0 flex-1 border-b border-l border-slate-300 dark:border-slate-700">{children}</div>
      </div>
      <div className="ml-16 flex justify-between gap-2 text-xs text-slate-500">
        <span data-axis="first">{points[0].label}</span>
        {points.length > 2 && <span className="hidden sm:inline">{middle.label}</span>}
        {points.length > 1 && <span data-axis="last">{points[points.length - 1].label}</span>}
      </div>
      <table className="sr-only">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Day</th>
            <th scope="col">Value</th>
          </tr>
        </thead>
        <tbody>
          {points.map((p, i) => (
            <tr key={i}>
              <th scope="row">{p.label}</th>
              <td>{formatValue(p.value)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  )
}
