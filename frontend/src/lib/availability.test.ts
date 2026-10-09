import { describe, expect, it } from 'vitest'
import { isClosedForToday, type DayAvailabilityDto } from './availability'

// Friday 9 Oct 2026, 11:30 IST; the next quarter hour is 11:45.
const NOW = new Date('2026-10-09T06:00:00Z')
const day = (overrides: Partial<DayAvailabilityDto> = {}): DayAvailabilityDto => ({
  date: '2026-10-09', level: 'AVAILABLE', openTime: '08:00', closeTime: '20:00', bookedPercent: 0, ...overrides,
})

describe('isClosedForToday', () => {
  it('is true when the next quarter hour is at or past closing time', () => {
    expect(isClosedForToday(day({ closeTime: '11:40' }), NOW)).toBe(true)
    expect(isClosedForToday(day({ closeTime: '11:45' }), NOW)).toBe(true)
  })

  it('is false while there is time left, on other days and without a closing time', () => {
    expect(isClosedForToday(day({ closeTime: '11:46' }), NOW)).toBe(false)
    expect(isClosedForToday(day({ date: '2026-10-10', closeTime: '08:30' }), NOW)).toBe(false)
    expect(isClosedForToday(day({ closeTime: null }), NOW)).toBe(false)
  })

  it('does not guess for hours that run past midnight', () => {
    expect(isClosedForToday(day({ openTime: '18:00', closeTime: '02:00' }), NOW)).toBe(false)
    expect(isClosedForToday(day({ openTime: '00:00', closeTime: '00:00' }), NOW)).toBe(false)
  })
})
