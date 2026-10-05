import { describe, expect, it } from 'vitest'
import { currentQuarter, defaultWindow, durationLabel, formatWindow, fromLocalInputValue, nextQuarter, toLocalInputValue } from './time'

describe('time helpers', () => {
  it('rounds up to the next quarter hour', () => {
    expect(nextQuarter(new Date(2026, 9, 6, 10, 7, 30))).toEqual(new Date(2026, 9, 6, 10, 15))
    expect(nextQuarter(new Date(2026, 9, 6, 10, 0, 0, 1))).toEqual(new Date(2026, 9, 6, 10, 15))
  })

  it('floors to the current quarter hour', () => {
    expect(currentQuarter(new Date(2026, 9, 6, 10, 7, 30))).toEqual(new Date(2026, 9, 6, 10, 0))
    expect(currentQuarter(new Date(2026, 9, 6, 10, 15, 0, 0))).toEqual(new Date(2026, 9, 6, 10, 15))
    expect(currentQuarter(new Date(2026, 9, 6, 10, 29, 59, 999))).toEqual(new Date(2026, 9, 6, 10, 15))
  })

  it('is always strictly in the future', () => {
    expect(nextQuarter(new Date(2026, 9, 6, 10, 15, 0, 0))).toEqual(new Date(2026, 9, 6, 10, 30))
    expect(nextQuarter(new Date(2026, 9, 6, 23, 50))).toEqual(new Date(2026, 9, 7, 0, 0))
  })

  it('defaults the window to next quarter plus two hours', () => {
    const { start, end } = defaultWindow(new Date(2026, 9, 6, 10, 7))
    expect(start).toEqual(new Date(2026, 9, 6, 10, 15))
    expect(end).toEqual(new Date(2026, 9, 6, 12, 15))
  })

  it('converts to and from datetime-local values', () => {
    const d = new Date(2026, 9, 6, 9, 5)
    expect(toLocalInputValue(d)).toBe('2026-10-06T09:05')
    expect(fromLocalInputValue('2026-10-06T09:05')).toEqual(d)
  })

  it('formats a window on the same day', () => {
    const start = new Date(2026, 9, 6, 10, 0).toISOString()
    const end = new Date(2026, 9, 6, 12, 0).toISOString()
    expect(formatWindow(start, end)).toBe('Tue 6 Oct, 10:00 am – 12:00 pm')
  })

  it('formats a window spanning days', () => {
    const start = new Date(2026, 9, 6, 10, 0).toISOString()
    const end = new Date(2026, 9, 8, 21, 30).toISOString()
    expect(formatWindow(start, end)).toBe('Tue 6 Oct, 10:00 am – Thu 8 Oct, 9:30 pm')
  })

  it('formats noon and midnight', () => {
    const start = new Date(2026, 9, 6, 0, 0).toISOString()
    const end = new Date(2026, 9, 6, 12, 0).toISOString()
    expect(formatWindow(start, end)).toBe('Tue 6 Oct, 12:00 am – 12:00 pm')
  })

  it('labels durations', () => {
    expect(durationLabel(120)).toBe('2 hours')
    expect(durationLabel(60)).toBe('1 hour')
    expect(durationLabel(1620)).toBe('1 day 3 hours')
    expect(durationLabel(2880)).toBe('2 days')
    expect(durationLabel(45)).toBe('45 minutes')
    expect(durationLabel(90)).toBe('1 hour 30 minutes')
  })
})
