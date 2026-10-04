import { screen, waitFor } from '@testing-library/react'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { api } from '../lib/api'
import { tokenStore } from '../lib/tokenStore'
import { renderApp } from '../test/renderApp'

describe('AuthProvider session restore', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  it('keeps the tokens when /me is 401 and the refresh fails transiently', async () => {
    tokenStore.set('old', 'r1')
    mock.onGet('/me').reply(401, { code: 'UNAUTHORIZED' })
    mock.onPost('/auth/refresh').networkError()

    renderApp('/driver')

    await waitFor(() => expect(mock.history.post.some((r) => r.url === '/auth/refresh')).toBe(true))
    expect(await screen.findByRole('heading', { name: /log in/i })).toBeInTheDocument()
    expect(tokenStore.getAccess()).toBe('old')
    expect(tokenStore.getRefresh()).toBe('r1')
  })

  it('clears the tokens when the refresh token is rejected', async () => {
    tokenStore.set('old', 'bad')
    mock.onGet('/me').reply(401, { code: 'UNAUTHORIZED' })
    mock.onPost('/auth/refresh').reply(401, { code: 'INVALID_REFRESH_TOKEN' })

    renderApp('/driver')

    expect(await screen.findByRole('heading', { name: /log in/i })).toBeInTheDocument()
    expect(tokenStore.getAccess()).toBeNull()
    expect(tokenStore.getRefresh()).toBeNull()
  })
})
