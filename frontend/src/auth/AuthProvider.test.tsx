import { QueryClient } from '@tanstack/react-query'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

describe('AuthProvider query cache', () => {
  const ownerA = { id: 7, name: 'Ravi Kumar', email: 'a@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null }
  const ownerB = { ...ownerA, id: 8, name: 'Meera Rao', email: 'b@example.com' }
  const profile = (verificationStatus: string) => ({
    verificationStatus, documentType: null, hasDocument: false, documentSubmittedAt: null, rejectionReason: null,
    verifiedAt: null, payoutUpi: null, payoutAccountName: null, payoutIfsc: null, payoutBankAccountLast4: null,
  })

  it('does not show the previous owner\'s cached data to the next user', async () => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    const mock = new MockAdapter(api)
    let current = profile('VERIFIED')
    mock.onGet('/me').reply(200, ownerA)
    mock.onGet('/owner/profile').reply(() => [200, current])
    mock.onGet('/owner/listings').reply(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    mock.onPost('/auth/logout').reply(204)
    mock.onPost('/auth/login').reply(200, { accessToken: 'b', refreshToken: 'rb', expiresIn: 900, user: ownerB })
    // A long staleTime, as in production: without clearing, B would be served A's cached profile.
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 60_000 } } })
    const profileGets = () => mock.history.get.filter((r) => r.url === '/owner/profile').length

    renderApp('/owner', queryClient)
    expect(await screen.findByText("You're verified. You can submit listings for approval.")).toBeInTheDocument()
    expect(profileGets()).toBe(1)

    await userEvent.click(screen.getByRole('button', { name: 'Log out' }))
    current = profile('UNSUBMITTED')
    await userEvent.click(await screen.findByRole('link', { name: 'Log in' }))
    await userEvent.type(await screen.findByLabelText('Email'), 'b@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'secret123')
    await userEvent.click(screen.getByRole('button', { name: /log in/i }))

    expect(await screen.findByText('Verify your identity to start listing parking.')).toBeInTheDocument()
    expect(screen.queryByText("You're verified. You can submit listings for approval.")).not.toBeInTheDocument()
    expect(profileGets()).toBe(2)
    mock.restore()
  })
})
