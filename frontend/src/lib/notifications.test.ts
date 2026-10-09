import { describe, expect, it } from 'vitest'
import { isSafeAppLink } from './notifications'

describe('isSafeAppLink', () => {
  it('accepts plain app-relative paths', () => {
    expect(isSafeAppLink('/driver/bookings/91')).toBe(true)
    expect(isSafeAppLink('/notifications?x=1#top')).toBe(true)
    expect(isSafeAppLink('/')).toBe(true)
  })

  it('rejects anything that could leave the app', () => {
    for (const link of ['//evil.com', '/\\evil.com', '/\\\\evil.com', 'javascript:alert(1)', 'https://x', 'driver/bookings', '', ' /driver', '/driver /x', '/dri\tver', '/\u0000x', '/a\nb', null]) {
      expect(isSafeAppLink(link), String(link)).toBe(false)
    }
  })
})
