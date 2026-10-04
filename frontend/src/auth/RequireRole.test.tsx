import { screen } from '@testing-library/react'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { api } from '../lib/api'
import { tokenStore } from '../lib/tokenStore'
import { renderApp } from '../test/renderApp'

describe('RequireRole', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  it('sends anonymous visitors to login', async () => {
    renderApp('/driver')

    expect(await screen.findByRole('heading', { name: /log in/i })).toBeInTheDocument()
  })

  it('redirects a user with the wrong role to their own home', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, {
      id: 2, name: 'Priya Sharma', email: 'owner@example.com', phone: null,
      role: 'OWNER', emailVerified: true, avatarUrl: null,
    })

    renderApp('/driver')

    expect(await screen.findByRole('heading', { name: /welcome, priya/i })).toBeInTheDocument()
    expect(screen.getByText(/parking owner/i)).toBeInTheDocument()
  })
})
