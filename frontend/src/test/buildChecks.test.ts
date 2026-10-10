import { describe, expect, it } from 'vitest'
import { checkProductionApiUrl, requiresApiUrl } from '../../buildChecks.ts'

describe('requiresApiUrl', () => {
  it('is on for Vercel builds and when asked for explicitly', () => {
    expect(requiresApiUrl({ VERCEL: '1' })).toBe(true)
    expect(requiresApiUrl({ VITE_REQUIRE_API_URL: 'true' })).toBe(true)
  })

  it('is off for local and CI builds, which have no API to point at', () => {
    expect(requiresApiUrl({})).toBe(false)
    expect(requiresApiUrl({ CI: 'true', GITHUB_ACTIONS: 'true' })).toBe(false)
    expect(requiresApiUrl({ VITE_REQUIRE_API_URL: 'false' })).toBe(false)
  })
})

describe('checkProductionApiUrl', () => {
  it('accepts an https URL', () => {
    expect(() => checkProductionApiUrl('https://parkease-api.onrender.com/api/v1')).not.toThrow()
  })

  it.each([undefined, '', '   '])('rejects a missing URL (%j), which would send every call to the frontend host', (url) => {
    expect(() => checkProductionApiUrl(url)).toThrow(/VITE_API_URL is not set/)
  })

  it.each(['http://parkease-api.onrender.com/api/v1', '/api/v1', 'parkease-api.onrender.com'])('rejects %s: not https', (url) => {
    expect(() => checkProductionApiUrl(url)).toThrow(/https/)
  })
})
