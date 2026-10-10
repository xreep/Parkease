import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiBaseUrl } from './apiBase'

describe('apiBaseUrl', () => {
  it('defaults to the dev proxy path', () => {
    expect(apiBaseUrl(undefined)).toBe('/api/v1')
    expect(apiBaseUrl('')).toBe('/api/v1')
    expect(apiBaseUrl('   ')).toBe('/api/v1')
  })

  it('uses the configured API origin, without a trailing slash', () => {
    expect(apiBaseUrl('https://parkease-api.onrender.com/api/v1')).toBe('https://parkease-api.onrender.com/api/v1')
    expect(apiBaseUrl('https://parkease-api.onrender.com/api/v1/')).toBe('https://parkease-api.onrender.com/api/v1')
    expect(apiBaseUrl(' https://parkease-api.onrender.com/api/v1 ')).toBe('https://parkease-api.onrender.com/api/v1')
  })
})

describe('the shared API client', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.resetModules()
  })

  it('sends every request (including uploads, receipts and CSV exports, which all use it) to VITE_API_URL', async () => {
    vi.stubEnv('VITE_API_URL', 'https://parkease-api.onrender.com/api/v1/')
    vi.resetModules()
    const { api } = await import('./api')
    expect(api.defaults.baseURL).toBe('https://parkease-api.onrender.com/api/v1')
  })

  it('falls back to /api/v1 when VITE_API_URL is not set', async () => {
    vi.stubEnv('VITE_API_URL', '')
    vi.resetModules()
    const { api } = await import('./api')
    expect(api.defaults.baseURL).toBe('/api/v1')
  })
})
