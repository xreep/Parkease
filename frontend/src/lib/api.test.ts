import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import { api, setSessionExpiredHandler } from './api'
import { tokenStore } from './tokenStore'

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

describe('api client', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    setSessionExpiredHandler(() => {})
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  it('attaches the access token', async () => {
    tokenStore.set('a1', 'r1')
    mock.onGet('/me').reply((config) => [config.headers?.Authorization === 'Bearer a1' ? 200 : 401, { ok: true }])

    const res = await api.get('/me')

    expect(res.status).toBe(200)
  })

  it('refreshes once on 401 and retries the request', async () => {
    tokenStore.set('old', 'r1')
    mock.onGet('/me').reply((config) =>
      config.headers?.Authorization === 'Bearer new' ? [200, { id: 1 }] : [401, { code: 'UNAUTHORIZED' }],
    )
    mock.onPost('/auth/refresh').reply(200, { accessToken: 'new', refreshToken: 'r2' })

    const res = await api.get('/me')

    expect(res.data).toEqual({ id: 1 })
    expect(tokenStore.getAccess()).toBe('new')
    expect(tokenStore.getRefresh()).toBe('r2')
  })

  it('clears tokens and notifies when the refresh fails', async () => {
    tokenStore.set('old', 'bad')
    const onExpired = vi.fn()
    setSessionExpiredHandler(onExpired)
    mock.onGet('/me').reply(401, { code: 'UNAUTHORIZED' })
    mock.onPost('/auth/refresh').reply(401, { code: 'INVALID_REFRESH_TOKEN' })

    await expect(api.get('/me')).rejects.toBeTruthy()

    expect(tokenStore.getAccess()).toBeNull()
    expect(onExpired).toHaveBeenCalled()
  })

  it('ends the session and shows the message when the account is suspended, even on a public read', async () => {
    tokenStore.set('a1', 'r1')
    const expired = vi.fn()
    setSessionExpiredHandler(expired)
    mock.onGet('/states').reply(403, { code: 'ACCOUNT_SUSPENDED', detail: 'Your account is suspended' })

    await expect(api.get('/states')).rejects.toMatchObject({ response: { status: 403 } })

    expect(tokenStore.getAccess()).toBeNull()
    expect(tokenStore.getRefresh()).toBeNull()
    expect(expired).toHaveBeenCalledTimes(1)
    expect(toast.error).toHaveBeenCalledWith('Your account is suspended')
    expect(mock.history.post).toHaveLength(0)
  })

  it('ignores a suspended answer meant for an older token, so a fresh sign-in survives', async () => {
    tokenStore.set('old', 'r1')
    const expired = vi.fn()
    setSessionExpiredHandler(expired)
    vi.mocked(toast.error).mockClear()
    mock.onGet('/states').reply(() => {
      tokenStore.set('new', 'r2')
      return [403, { code: 'ACCOUNT_SUSPENDED', detail: 'Suspended' }]
    })

    await expect(api.get('/states')).rejects.toBeDefined()

    expect(tokenStore.getAccess()).toBe('new')
    expect(expired).not.toHaveBeenCalled()
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('does nothing for a suspended answer when nobody is signed in', async () => {
    const expired = vi.fn()
    setSessionExpiredHandler(expired)
    vi.mocked(toast.error).mockClear()
    mock.onGet('/states').reply(403, { code: 'ACCOUNT_SUSPENDED', detail: 'Suspended' })

    await expect(api.get('/states')).rejects.toBeDefined()

    expect(expired).not.toHaveBeenCalled()
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('leaves the sign-in call to its own form', async () => {
    tokenStore.set('a1', 'r1')
    const expired = vi.fn()
    setSessionExpiredHandler(expired)
    vi.mocked(toast.error).mockClear()
    mock.onPost('/auth/login').reply(403, { code: 'ACCOUNT_SUSPENDED', detail: 'Suspended' })

    await expect(api.post('/auth/login', {})).rejects.toBeDefined()

    expect(tokenStore.getAccess()).toBe('a1')
    expect(expired).not.toHaveBeenCalled()
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('shows one message and ends the session once for parallel suspended answers', async () => {
    tokenStore.set('a1', 'r1')
    const expired = vi.fn()
    setSessionExpiredHandler(expired)
    vi.mocked(toast.error).mockClear()
    mock.onGet().reply(403, { code: 'ACCOUNT_SUSPENDED', detail: 'Suspended' })

    await Promise.allSettled([api.get('/states'), api.get('/me'), api.get('/notifications')])

    expect(expired).toHaveBeenCalledTimes(1)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('reads a suspended answer that arrives as a blob', async () => {
    tokenStore.set('a1', 'r1')
    const expired = vi.fn()
    setSessionExpiredHandler(expired)
    vi.mocked(toast.error).mockClear()
    mock.onGet('/owner/earnings').reply(403, new Blob([JSON.stringify({ code: 'ACCOUNT_SUSPENDED', detail: 'Your account is suspended' })]))

    await expect(api.get('/owner/earnings', { responseType: 'blob' })).rejects.toBeDefined()

    expect(tokenStore.getAccess()).toBeNull()
    expect(expired).toHaveBeenCalledTimes(1)
    expect(toast.error).toHaveBeenCalledWith('Your account is suspended')
  })

  it('shows the suspended message when the refresh itself is refused for it', async () => {
    tokenStore.set('old', 'r1')
    const expired = vi.fn()
    setSessionExpiredHandler(expired)
    vi.mocked(toast.error).mockClear()
    mock.onGet('/me').reply(401, { code: 'UNAUTHORIZED' })
    mock.onPost('/auth/refresh').reply(403, { code: 'ACCOUNT_SUSPENDED', detail: 'Your account is suspended' })

    await expect(api.get('/me')).rejects.toBeDefined()

    expect(tokenStore.getAccess()).toBeNull()
    expect(expired).toHaveBeenCalledTimes(1)
    expect(toast.error).toHaveBeenCalledTimes(1)
    expect(toast.error).toHaveBeenCalledWith('Your account is suspended')
  })

  it('keeps the session on other 403 answers', async () => {
    tokenStore.set('a1', 'r1')
    const expired = vi.fn()
    setSessionExpiredHandler(expired)
    mock.onGet('/admin/queues').reply(403, { code: 'FORBIDDEN', detail: 'Not allowed' })

    await expect(api.get('/admin/queues')).rejects.toBeDefined()

    expect(tokenStore.getAccess()).toBe('a1')
    expect(expired).not.toHaveBeenCalled()
  })

  it('does not try to refresh failed auth calls', async () => {
    mock.onPost('/auth/login').reply(401, { code: 'INVALID_CREDENTIALS' })
    const refreshSpy = vi.fn(() => [200, {}] as [number, object])
    mock.onPost('/auth/refresh').reply(refreshSpy)

    await expect(api.post('/auth/login', {})).rejects.toBeTruthy()

    expect(refreshSpy).not.toHaveBeenCalled()
  })

  it('keeps tokens and does not expire the session when refresh fails transiently', async () => {
    tokenStore.set('old', 'r1')
    const onExpired = vi.fn()
    setSessionExpiredHandler(onExpired)
    mock.onGet('/me').reply(401, { code: 'UNAUTHORIZED' })
    mock.onPost('/auth/refresh').networkError()

    await expect(api.get('/me')).rejects.toBeTruthy()

    expect(tokenStore.getAccess()).toBe('old')
    expect(tokenStore.getRefresh()).toBe('r1')
    expect(onExpired).not.toHaveBeenCalled()
  })

  it('shares one refresh between concurrent 401s and retries both', async () => {
    tokenStore.set('old', 'r1')
    mock.onGet('/me').reply((config) =>
      config.headers?.Authorization === 'Bearer new' ? [200, { id: 1 }] : [401, { code: 'UNAUTHORIZED' }],
    )
    mock.onGet('/other').reply((config) =>
      config.headers?.Authorization === 'Bearer new' ? [200, { id: 2 }] : [401, { code: 'UNAUTHORIZED' }],
    )
    mock.onPost('/auth/refresh').reply(200, { accessToken: 'new', refreshToken: 'r2' })

    const [a, b] = await Promise.all([api.get('/me'), api.get('/other')])

    expect(a.data).toEqual({ id: 1 })
    expect(b.data).toEqual({ id: 2 })
    expect(mock.history.post.filter((r) => r.url === '/auth/refresh')).toHaveLength(1)
  })

  it('retries with the stored token without refreshing when another tab already refreshed', async () => {
    tokenStore.set('old', 'r1')
    mock.onGet('/me').reply((config) => {
      if (config.headers?.Authorization === 'Bearer old') {
        // Simulates another tab rotating the tokens while this request was in flight.
        tokenStore.set('fresh', 'r2')
        return [401, { code: 'UNAUTHORIZED' }]
      }
      return config.headers?.Authorization === 'Bearer fresh' ? [200, { id: 1 }] : [401, {}]
    })
    mock.onPost('/auth/refresh').reply(200, { accessToken: 'never', refreshToken: 'never' })

    const res = await api.get('/me')

    expect(res.data).toEqual({ id: 1 })
    expect(mock.history.post.filter((r) => r.url === '/auth/refresh')).toHaveLength(0)
    expect(tokenStore.getAccess()).toBe('fresh')
    expect(tokenStore.getRefresh()).toBe('r2')
  })

  describe('server errors', () => {
    beforeEach(() => vi.mocked(toast.error).mockClear())

    it('shows one friendly toast when loading fails with a 5xx, however many requests fail together, and still rejects', async () => {
      mock.onGet('/a').reply(500, { code: 'INTERNAL', detail: 'NullPointerException at Foo.java:12' })
      mock.onGet('/b').reply(503)

      const results = await Promise.allSettled([api.get('/a'), api.get('/b')])

      expect(results.map((r) => r.status)).toEqual(['rejected', 'rejected'])
      expect(toast.error).toHaveBeenCalledTimes(2)
      const calls = vi.mocked(toast.error).mock.calls
      expect(calls[0][0]).toBe('Something went wrong on our side. Please try again in a moment.')
      // Same toast id both times, so sonner shows a single toast.
      expect(calls[0][1]).toEqual({ id: 'server-error' })
      expect(calls[1][1]).toEqual({ id: 'server-error' })
    })

    it('stays quiet for client errors, network failures and failed writes (their forms show the message inline)', async () => {
      mock.onGet('/missing').reply(404, { code: 'NOT_FOUND' })
      mock.onGet('/down').networkError()
      mock.onPost('/save').reply(500, { code: 'INTERNAL', detail: 'Could not save' })

      await Promise.allSettled([api.get('/missing'), api.get('/down'), api.post('/save', {})])

      expect(toast.error).not.toHaveBeenCalled()
    })
  })
})
