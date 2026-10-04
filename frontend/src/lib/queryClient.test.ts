import { AxiosError, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import { shouldRetry } from './queryClient'

function httpError(status: number) {
  return new AxiosError('failed', undefined, undefined, undefined, { status, data: {} } as AxiosResponse)
}

describe('shouldRetry', () => {
  it('never retries client errors', () => {
    expect(shouldRetry(0, httpError(404))).toBe(false)
    expect(shouldRetry(0, httpError(401))).toBe(false)
  })

  it('retries network errors and 5xx twice at most', () => {
    const network = new AxiosError('Network Error')
    expect(shouldRetry(0, network)).toBe(true)
    expect(shouldRetry(1, network)).toBe(true)
    expect(shouldRetry(2, network)).toBe(false)
    expect(shouldRetry(0, httpError(500))).toBe(true)
    expect(shouldRetry(2, httpError(500))).toBe(false)
  })
})
