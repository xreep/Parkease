import { QueryClient } from '@tanstack/react-query'
import { AxiosError, type AxiosResponse } from 'axios'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient, shouldRetry } from './queryClient'

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

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

describe('server error toast', () => {
  const failing = (status: number) => () => Promise.reject(httpError(status))
  let client: QueryClient
  let day = 0

  beforeEach(() => {
    vi.mocked(toast.error).mockClear()
    vi.useFakeTimers({ toFake: ['Date'] })
    // A day after the previous test: the throttle is module-wide, so each test needs a clear window of its own.
    day += 1
    vi.setSystemTime(new Date(Date.UTC(2030, 0, day)))
    client = createQueryClient()
  })
  afterEach(() => {
    vi.useRealTimers()
    client.clear()
  })

  const load = (key: string, fn: () => Promise<unknown>, meta?: Record<string, unknown>) =>
    client.fetchQuery({ queryKey: [key], queryFn: fn, retry: false, meta }).catch(() => undefined)

  it('shows one friendly toast when a load fails with a 5xx', async () => {
    await load('a', failing(500))

    expect(toast.error).toHaveBeenCalledOnce()
    expect(vi.mocked(toast.error).mock.calls[0]).toEqual(['Something went wrong on our side. Please try again in a moment.', { id: 'server-error' }])
  })

  it('toasts only the final failure, not each retry attempt', async () => {
    const queryFn = vi.fn(failing(503))
    // The app's own retry rule: 5xx is retried twice, with a backoff; use a tiny delay here.
    await client.fetchQuery({ queryKey: ['retry'], queryFn, retry: 2, retryDelay: 1 }).catch(() => undefined)

    expect(queryFn).toHaveBeenCalledTimes(3)
    expect(toast.error).toHaveBeenCalledOnce()
  })

  it('stays quiet for client errors, network failures and queries marked silent', async () => {
    await load('404', failing(404))
    await load('net', () => Promise.reject(new AxiosError('Network Error')))
    await load('poll', failing(500), { silent: true })

    expect(toast.error).not.toHaveBeenCalled()
  })

  it('shows at most one toast per minute', async () => {
    await load('first', failing(500))
    vi.setSystemTime(Date.now() + 59_000)
    await load('second', failing(500))
    expect(toast.error).toHaveBeenCalledTimes(1)

    vi.setSystemTime(Date.now() + 2_000)
    await load('third', failing(500))
    expect(toast.error).toHaveBeenCalledTimes(2)
  })
})
