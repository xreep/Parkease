import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { api } from '../../lib/api'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

const driver = {
  id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null,
  role: 'DRIVER', emailVerified: true, avatarUrl: null,
}

describe('Navbar logout', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  it('logging out from a protected page lands on the home page, not the login redirect', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, driver)
    mock.onPost('/auth/logout').reply(204)
    renderApp('/account')

    await screen.findByRole('heading', { name: /your account/i })
    await userEvent.click(screen.getAllByRole('button', { name: /log out/i })[0])

    expect(await screen.findByRole('heading', { name: /parking, reserved before you arrive/i })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /^log in$/i })).not.toBeInTheDocument()
    expect(tokenStore.getAccess()).toBeNull()
  })

  it('has a Find parking link to the search page', async () => {
    renderApp('/')
    expect(screen.getByRole('link', { name: 'Find parking' })).toHaveAttribute('href', '/search')
  })

  it('gives drivers a My bookings link, and owners none', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, driver)
    renderApp('/account')
    expect((await screen.findAllByRole('link', { name: 'My bookings' }))[0]).toHaveAttribute('href', '/driver/bookings')
  })

  it('does not show My bookings to owners', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, { ...driver, role: 'OWNER' })
    renderApp('/account')
    await screen.findByRole('heading', { name: /your account/i })
    expect(screen.queryByRole('link', { name: 'My bookings' })).not.toBeInTheDocument()
  })
})
