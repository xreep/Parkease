/** A round upper bound for the axis (1, 2, 5 times a power of ten), at least 1 so an all-zero series still has a scale. */
export function niceMax(max: number): number {
  if (!(max > 0)) return 1
  const power = 10 ** Math.floor(Math.log10(max))
  const unit = max / power
  return (unit <= 1 ? 1 : unit <= 2 ? 2 : unit <= 5 ? 5 : 10) * power
}

export type ChartPoint = { label: string; value: number }
