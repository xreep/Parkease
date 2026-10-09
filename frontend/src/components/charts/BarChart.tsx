import { useId } from 'react'
import { ChartFrame } from './ChartFrame'
import { niceMax, type ChartPoint } from './chartScale'

const WIDTH = 600
const HEIGHT = 160

/**
 * One bar per point. The drawing stretches to its container; its axis labels are HTML, and a hidden table repeats
 * the numbers. Each bar has a `<title>` for hover and assistive technology.
 */
export function BarChart({
  title,
  points,
  formatValue,
  className = 'fill-brand-500 dark:fill-brand-400',
}: {
  title: string
  points: ChartPoint[]
  formatValue: (value: number) => string
  className?: string
}) {
  const titleId = useId()
  const top = niceMax(Math.max(0, ...points.map((p) => p.value)))
  const slot = WIDTH / Math.max(points.length, 1)
  const barWidth = Math.max(slot * 0.7, 1)
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
        {points.map((p, i) => {
          const height = Math.max(0, Math.min(p.value / top, 1)) * HEIGHT
          return (
            <rect
              key={i}
              data-bar
              x={i * slot + (slot - barWidth) / 2}
              y={HEIGHT - height}
              width={barWidth}
              height={height}
              className={className}
            >
              <title>{`${p.label}: ${formatValue(p.value)}`}</title>
            </rect>
          )
        })}
      </svg>
    </ChartFrame>
  )
}
