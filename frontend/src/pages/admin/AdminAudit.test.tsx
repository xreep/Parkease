import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { AdminAction } from '../../lib/admin'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }


const page = <T,>(content: T[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})


const action = (id: number, overrides: Partial<AdminAction> = {}): AdminAction => ({
  id, adminName: 'Admin User', action: 'USER_SUSPENDED', targetType: 'USER', targetId: 7,
  details: 'Reason: spam', createdAt: '2026-10-08T10:00:00Z', ...overrides,
})

describe('admin audit log', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
  })

  afterEach(() => mock.restore())

  const calls = () => mock.history.get.filter((r) => r.url === '/admin/audit')

  it('lists the actions, newest first as served', async () => {
    mock.onGet('/admin/audit').reply(200, page([action(1), action(2, { action: 'SETTINGS_UPDATED', targetType: 'SETTINGS', targetId: null, details: null })]))
    renderApp('/admin/audit')

    const table = within(await screen.findByRole('table', { name: 'Audit log' }))
    expect(calls()[0].params).toEqual({ page: 0, size: 20 })
    expect(table.getByRole('row', { name: /Admin User USER_SUSPENDED USER 7 Reason: spam/ })).toBeInTheDocument()
    expect(table.getByRole('row', { name: /SETTINGS_UPDATED SETTINGS —/ })).toBeInTheDocument()
  })

  it('filters by action and target type', async () => {
    mock.onGet('/admin/audit').reply(200, page([action(1)]))
    const user = userEvent.setup()
    renderApp('/admin/audit')
    await screen.findByRole('table', { name: 'Audit log' })

    await user.selectOptions(screen.getByLabelText('Action'), 'User suspended')
    await user.selectOptions(screen.getByLabelText('Target type'), 'User')
    await user.click(screen.getByRole('button', { name: 'Apply filters' }))

    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ action: 'USER_SUSPENDED', targetType: 'USER', page: 0, size: 20 }))

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ page: 0, size: 20 }))
  })

  it('pages through the log', async () => {
    mock.onGet('/admin/audit').reply((config) => [200, page([action(config.params.page + 1)], 2, config.params.page)])
    const user = userEvent.setup()
    renderApp('/admin/audit')
    await screen.findByRole('table', { name: 'Audit log' })

    await user.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(calls().at(-1)!.params).toMatchObject({ page: 1 }))
  })

  it('shows an empty state and server errors', async () => {
    mock.onGet('/admin/audit').replyOnce(200, page([]))
    renderApp('/admin/audit')
    expect(await screen.findByText('No actions match these filters.')).toBeInTheDocument()
  })

  it('shows the server message on failure', async () => {
    mock.onGet('/admin/audit').reply(500, { code: 'INTERNAL', detail: 'Audit is down' })
    renderApp('/admin/audit')
    expect(await screen.findByText('Audit is down')).toBeInTheDocument()
  })
})
