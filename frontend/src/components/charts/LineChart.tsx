import { useId } from 'react'
import { ChartFrame } from './ChartFrame'
import { niceMax, type ChartPoint } from './chartScale'

const WIDTH = 600
const HEIGHT = 160
const PAD = 6

/** A line through one point per entry; same structure and accessibility as the bar chart. */
export function LineChart({
  title,
  points,
  formatValue,
  className = 'stroke-emerald-600 dark:stroke-emerald-400',
}: {
  title: string
  points: ChartPoint[]
  formatValue: (value: number) => string
  className?: string
}) {
  const titleId = useId()
  const top = niceMax(Math.max(0, ...points.map((p) => p.value)))
  const x = (i: number) => (points.length === 1 ? WIDTH / 2 : PAD + (i * (WIDTH - 2 * PAD)) / (points.length - 1))
  const y = (value: number) => HEIGHT - PAD - (Math.max(0, Math.min(value / top, 1)) * (HEIGHT - 2 * PAD))
  return (
    <ChartFrame title={title} points={points} max={formatValue(top)} formatValue={formatValue}>
      <svg
        role="img"
        aria-labelledby={titleId}
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        preserveAspectRatio="none"
        className="block h-40 w-full"
      >
        <title id={titleId}>{title}</title>
        {[0.5, 1].map((fraction) => (
          <line key={fraction} x1={0} x2={WIDTH} y1={HEIGHT * (1 - fraction)} y2={HEIGHT * (1 - fraction)} className="stroke-slate-200 dark:stroke-slate-800" strokeWidth={1} vectorEffect="non-scaling-stroke" />
        ))}
        <polyline
          points={points.map((p, i) => `${x(i).toFixed(1)},${y(p.value).toFixed(1)}`).join(' ')}
          fill="none"
          className={className}
          strokeWidth={2}
          strokeLinejoin="round"
          vectorEffect="non-scaling-stroke"
        />
        {points.map((p, i) => (
          // A zero-length round-capped line is a dot that stays round when the drawing is stretched.
          <line
            key={i}
            data-point
            x1={x(i)}
            x2={x(i)}
            y1={y(p.value)}
            y2={y(p.value)}
            className={className}
            strokeWidth={5}
            strokeLinecap="round"
            vectorEffect="non-scaling-stroke"
          >
            <title>{`${p.label}: ${formatValue(p.value)}`}</title>
          </line>
        ))}
      </svg>
    </ChartFrame>
  )
}
