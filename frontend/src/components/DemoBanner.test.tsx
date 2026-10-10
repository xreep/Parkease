import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { api } from '../lib/api'
import { renderApp } from '../test/renderApp'

const TEXT = "Demo site — sample data, test payments only. Don't enter real personal details."

describe('demo banner', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    mock = new MockAdapter(api)
    mock.onGet('/states').reply(200, [])
  })
  afterEach(() => mock.restore())

  it('shows when the backend reports demo mode, above the navigation', async () => {
    mock.onGet('/health').reply(200, { status: 'UP', demoMode: true })
    renderApp('/')

    const banner = await screen.findByRole('region', { name: 'Demo site notice' })
    expect(banner).toHaveTextContent(TEXT)
    expect(banner).toHaveTextContent(/demo logins/i)
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(banner.compareDocumentPosition(nav) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('can be dismissed, and stays dismissed for the rest of the session', async () => {
    mock.onGet('/health').reply(200, { status: 'UP', demoMode: true })
    const first = renderApp('/')
    await userEvent.click(await screen.findByRole('button', { name: 'Dismiss demo notice' }))
    expect(screen.queryByRole('region', { name: 'Demo site notice' })).not.toBeInTheDocument()
    first.unmount()

    renderApp('/')
    await screen.findByRole('navigation', { name: 'Main' })
    await waitFor(() => expect(mock.history.get.some((r) => r.url === '/health')).toBe(true))
    expect(screen.queryByRole('region', { name: 'Demo site notice' })).not.toBeInTheDocument()
  })

  it.each([
    ['demo mode is off', () => mock.onGet('/health').reply(200, { status: 'UP', demoMode: false })],
    ['the flag is absent (an older backend)', () => mock.onGet('/health').reply(200, { status: 'UP' })],
    ['the health call fails', () => mock.onGet('/health').reply(500)],
    ['the health endpoint is not there', () => mock.onGet('/health').reply(404)],
  ])('stays hidden when %s', async (_name, arrange) => {
    arrange()
    renderApp('/')
    await screen.findByRole('navigation', { name: 'Main' })
    await waitFor(() => expect(mock.history.get.some((r) => r.url === '/health')).toBe(true))
    await new Promise((r) => setTimeout(r, 30))
    expect(screen.queryByRole('region', { name: 'Demo site notice' })).not.toBeInTheDocument()
  })
})
