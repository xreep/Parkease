import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, setSessionExpiredHandler } from './api'
import { tokenStore } from './tokenStore'

describe('api client', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
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

  it('does not try to refresh failed auth calls', async () => {
    mock.onPost('/auth/login').reply(401, { code: 'INVALID_CREDENTIALS' })
    const refreshSpy = vi.fn(() => [200, {}] as [number, object])
    mock.onPost('/auth/refresh').reply(refreshSpy)

    await expect(api.post('/auth/login', {})).rejects.toBeTruthy()

    expect(refreshSpy).not.toHaveBeenCalled()
  })
})
