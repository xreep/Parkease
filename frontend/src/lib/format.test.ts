import { describe, expect, it } from 'vitest'
import { formatINR, listingStatusLabel, verificationStatusLabel } from './format'

describe('format helpers', () => {
  it('formats rupees', () => {
    expect(formatINR(30)).toBe('₹30')
    expect(formatINR(1250.5)).toMatch(/^₹1,250\.50?$/)
    expect(formatINR('99.5')).toMatch(/^₹99\.50?$/)
    expect(formatINR(null)).toBe('—')
  })

  it('labels statuses', () => {
    expect(listingStatusLabel('APPROVED')).toBe('Live')
    expect(listingStatusLabel('REJECTED')).toBe('Changes needed')
    expect(verificationStatusLabel('PENDING')).toBe('Under review')
  })
})
