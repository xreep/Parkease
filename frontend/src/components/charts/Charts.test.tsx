import '@testing-library/jest-dom/vitest'
import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { BarChart } from './BarChart'
import { LineChart } from './LineChart'

const points = [
  { label: '1 Oct', value: 100 },
  { label: '2 Oct', value: 0 },
  { label: '3 Oct', value: 300 },
]
const money = (n: number) => `₹${n}`

describe('BarChart', () => {
  it('draws one bar per point, scaled to the largest value', () => {
    render(<BarChart title="Earnings per day" points={points} formatValue={money} />)

    const chart = screen.getByRole('img', { name: 'Earnings per day' })
    const bars = chart.querySelectorAll('rect[data-bar]')
    expect(bars).toHaveLength(3)
    expect(Number(bars[2].getAttribute('height'))).toBeGreaterThan(Number(bars[0].getAttribute('height')))
    expect(Number(bars[1].getAttribute('height'))).toBe(0)
    // Every bar says what it is.
    expect(bars[2].querySelector('title')).toHaveTextContent('3 Oct: ₹300')
    expect(chart.querySelector('title')).toHaveTextContent('Earnings per day')
  })

  it('has a table with the same numbers for screen readers', () => {
    render(<BarChart title="Earnings per day" points={points} formatValue={money} />)

    const table = screen.getByRole('table', { name: 'Earnings per day' })
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(4)
    expect(rows[3]).toHaveTextContent('3 Oct')
    expect(rows[3]).toHaveTextContent('₹300')
  })

  it('labels the axis ends and its top', () => {
    render(<BarChart title="Earnings per day" points={points} formatValue={money} />)

    // The top of the axis is rounded up to a round number.
    expect(screen.getByText('₹500', { selector: '[data-axis="max"]' })).toBeInTheDocument()
    expect(screen.getByText('1 Oct', { selector: '[data-axis="first"]' })).toBeInTheDocument()
    expect(screen.getByText('3 Oct', { selector: '[data-axis="last"]' })).toBeInTheDocument()
  })

  it('copes with nothing but zeros and with no points', () => {
    const { rerender } = render(<BarChart title="Earnings per day" points={points.map((p) => ({ ...p, value: 0 }))} formatValue={money} />)
    const bars = screen.getByRole('img', { name: 'Earnings per day' }).querySelectorAll('rect[data-bar]')
    bars.forEach((bar) => expect(bar.getAttribute('height')).toBe('0'))
    expect(document.body.innerHTML).not.toContain('NaN')

    rerender(<BarChart title="Earnings per day" points={[]} formatValue={money} />)
    expect(screen.getByText('No data for this period.')).toBeInTheDocument()
  })
})

describe('LineChart', () => {
  it('draws a line through every point and marks each one', () => {
    render(<LineChart title="Bookings per day" points={points} formatValue={(n) => String(n)} />)

    const chart = screen.getByRole('img', { name: 'Bookings per day' })
    const line = chart.querySelector('polyline')!
    expect(line.getAttribute('points')!.trim().split(/\s+/)).toHaveLength(3)
    expect(chart.querySelectorAll('line[data-point]')).toHaveLength(3)
    expect(chart.querySelectorAll('line[data-point] title')[2]).toHaveTextContent('3 Oct: 300')
    expect(document.body.innerHTML).not.toContain('NaN')
  })

  it('has a table fallback and handles a single point and all zeros', () => {
    const { rerender } = render(<LineChart title="Bookings per day" points={[{ label: '1 Oct', value: 0 }]} formatValue={(n) => String(n)} />)

    expect(screen.getByRole('table', { name: 'Bookings per day' })).toBeInTheDocument()
    expect(document.body.innerHTML).not.toContain('NaN')

    rerender(<LineChart title="Bookings per day" points={[]} formatValue={(n) => String(n)} />)
    expect(screen.getByText('No data for this period.')).toBeInTheDocument()
  })
})
