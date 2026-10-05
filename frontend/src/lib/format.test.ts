import { describe, expect, it } from 'vitest'
import { formatAddress, formatINR, listingStatusLabel, verificationStatusLabel } from './format'

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

  it('formats an address without repeating the city', () => {
    const base = { cityName: 'Pune', stateName: 'Maharashtra', pincode: '411014' }
    expect(formatAddress({ ...base, address: '14 Viman Nagar Road' })).toBe('14 Viman Nagar Road, Pune, Maharashtra 411014')
    expect(formatAddress({ ...base, address: 'Off Airport Road, Viman Nagar, PUNE' }))
      .toBe('Off Airport Road, Viman Nagar, PUNE, Maharashtra 411014')
  })
})
