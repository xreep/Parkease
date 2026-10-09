import { describe, expect, it } from 'vitest'
import { CANCELLATION_POLICIES, REFUND_NOTE, formatAddress, formatINR, listingStatusLabel, verificationStatusLabel } from './format'

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

  it('describes each cancellation policy exactly as the backend applies it', () => {
    expect(CANCELLATION_POLICIES.map((p) => p.help)).toEqual([
      'Full refund up to 1 hour before start, 50% after',
      'Full refund up to 24 hours before start, 50% from 24 to 2 hours before, none within 2 hours',
      '50% refund up to 48 hours before start, none after',
    ])
    expect(REFUND_NOTE).toBe("Refunds apply to the parking charge; platform fee and GST aren't refunded on driver cancellations.")
  })
})
