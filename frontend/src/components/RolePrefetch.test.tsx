import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import { tokenStore } from '../lib/tokenStore'
import * as pages from '../pages/lazyPages'
import { renderApp } from '../test/renderApp'

const users = {
  DRIVER: { id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null, role: 'DRIVER', emailVerified: true, avatarUrl: null },
  OWNER: { id: 2, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null },
  ADMIN: { id: 3, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null },
}

describe('chunk prefetching', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
    mock.onGet('/states').reply(200, [])
    vi.spyOn(pages.OwnerLayout, 'preload')
    vi.spyOn(pages.OwnerHomePage, 'preload')
    vi.spyOn(pages.DriverLayout, 'preload')
    vi.spyOn(pages.AdminLayout, 'preload')
    vi.spyOn(pages.AdminHomePage, 'preload')
    vi.spyOn(pages.SearchPage, 'preload')
    vi.spyOn(pages.LoginPage, 'preload')
  })
  afterEach(() => {
    mock.restore()
    vi.restoreAllMocks()
  })

  it('warms the owner layout and dashboard once an owner is signed in, and not the other roles', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, users.OWNER)
    renderApp('/')

    await waitFor(() => expect(pages.OwnerLayout.preload).toHaveBeenCalled())
    expect(pages.OwnerHomePage.preload).toHaveBeenCalled()
    expect(pages.DriverLayout.preload).not.toHaveBeenCalled()
    expect(pages.AdminLayout.preload).not.toHaveBeenCalled()
  })

  it('warms the admin layout and overview for an admin', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, users.ADMIN)
    renderApp('/')

    await waitFor(() => expect(pages.AdminLayout.preload).toHaveBeenCalled())
    expect(pages.AdminHomePage.preload).toHaveBeenCalled()
  })

  it('warms nothing role-specific for a visitor who is not signed in', async () => {
    renderApp('/')
    await screen.findByRole('navigation', { name: 'Main' })
    await new Promise((r) => setTimeout(r, 50))

    expect(pages.OwnerLayout.preload).not.toHaveBeenCalled()
    expect(pages.DriverLayout.preload).not.toHaveBeenCalled()
    expect(pages.AdminLayout.preload).not.toHaveBeenCalled()
  })

  it('preloads the search and login chunks when their nav links are hovered or focused', async () => {
    renderApp('/')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    const find = [...nav.querySelectorAll('a')].find((a) => a.textContent === 'Find parking')!
    const login = [...nav.querySelectorAll('a')].find((a) => a.textContent === 'Log in')!

    await userEvent.hover(find)
    expect(pages.SearchPage.preload).toHaveBeenCalled()
    expect(pages.LoginPage.preload).not.toHaveBeenCalled()

    login.focus()
    expect(pages.LoginPage.preload).toHaveBeenCalled()
  })
})
