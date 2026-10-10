import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { AdminUser } from '../../lib/adminManage'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }

const user = (id: number, overrides: Partial<AdminUser> = {}): AdminUser => ({
  id, firstName: 'Asha', lastName: 'Rao', email: 'asha@example.com', phone: '9876543210', role: 'DRIVER', status: 'ACTIVE',
  emailVerified: true, createdAt: '2026-09-01T10:00:00Z', bookingsCount: 4, listingsCount: 0, ...overrides,
})

const page = <T,>(content: T[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

describe('admin users', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => mock.restore())

  const calls = () => mock.history.get.filter((r) => r.url === '/admin/users')

  it('lists users with their role, status and counts', async () => {
    mock.onGet('/admin/users').reply(200, page([
      user(5),
      user(6, { firstName: 'Ravi', lastName: 'Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', status: 'SUSPENDED', bookingsCount: 0, listingsCount: 3 }),
    ]))
    renderApp('/admin/users')

    const asha = within(await screen.findByRole('article', { name: 'Asha Rao' }))
    expect(calls()[0].params).toEqual({ page: 0, size: 20 })
    expect(asha.getByText('asha@example.com')).toBeInTheDocument()
    expect(asha.getByText('9876543210')).toBeInTheDocument()
    expect(asha.getByText('Driver')).toBeInTheDocument()
    expect(asha.getByText('Active')).toBeInTheDocument()
    expect(asha.getByText('4 bookings · 0 listings')).toBeInTheDocument()
    const ravi = within(screen.getByRole('article', { name: 'Ravi Kumar' }))
    expect(ravi.getByText('Owner')).toBeInTheDocument()
    expect(ravi.getByText('Suspended')).toBeInTheDocument()
    expect(ravi.getByRole('button', { name: 'Activate' })).toBeInTheDocument()
    expect(ravi.queryByRole('button', { name: 'Suspend' })).not.toBeInTheDocument()
  })

  it('filters by role and status, and searches on submit', async () => {
    mock.onGet('/admin/users').reply(200, page([user(5)]))
    const u = userEvent.setup()
    renderApp('/admin/users')
    await screen.findByRole('article', { name: 'Asha Rao' })

    await u.selectOptions(screen.getByLabelText('Role'), 'Owner')
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ role: 'OWNER', page: 0, size: 20 }))
    await u.selectOptions(screen.getByLabelText('Status'), 'Suspended')
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ role: 'OWNER', status: 'SUSPENDED', page: 0, size: 20 }))

    const before = calls().length
    await u.type(screen.getByLabelText('Search users'), 'asha')
    expect(calls()).toHaveLength(before)
    await u.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ role: 'OWNER', status: 'SUSPENDED', q: 'asha', page: 0, size: 20 }))
  })

  it('searches by name or email', async () => {
    mock.onGet('/admin/users').reply(200, page([user(5)]))
    renderApp('/admin/users')

    expect(await screen.findByPlaceholderText('Name or email')).toBeInTheDocument()
  })

  it('steps back when the last user of a later page is gone, but not on stale placeholder data', async () => {
    mock.onGet('/admin/users').reply((config) =>
      config.params.page === 1 ? [200, page([], 2, 1)] : [200, page([user(5)], 2, 0)],
    )
    const u = userEvent.setup()
    renderApp('/admin/users')
    await screen.findByRole('article', { name: 'Asha Rao' })

    await u.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(calls().filter((r) => r.params.page === 0).length).toBeGreaterThanOrEqual(1))
    await waitFor(() => expect(calls().at(-1)!.params.page).toBe(0))
    expect(await screen.findByRole('article', { name: 'Asha Rao' })).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    mock.onGet('/admin/users').reply(200, page([]))
    renderApp('/admin/users')
    expect(await screen.findByText('No users match these filters.')).toBeInTheDocument()
  })

  it('shows the server message when the list fails', async () => {
    mock.onGet('/admin/users').reply(500, { code: 'INTERNAL', detail: 'Users are down' })
    renderApp('/admin/users')
    expect(await screen.findByText('Users are down')).toBeInTheDocument()
  })

  it('suspends a user with a reason and reloads', async () => {
    mock.onGet('/admin/users').replyOnce(200, page([user(5)]))
    mock.onGet('/admin/users').reply(200, page([user(5, { status: 'SUSPENDED' })]))
    mock.onPost('/admin/users/5/suspend').reply(200, user(5, { status: 'SUSPENDED' }))
    const u = userEvent.setup()
    renderApp('/admin/users')

    await u.click(within(await screen.findByRole('article', { name: 'Asha Rao' })).getByRole('button', { name: 'Suspend' }))
    const dialog = within(await screen.findByRole('dialog', { name: 'Suspend Asha Rao?' }))
    await u.type(dialog.getByLabelText('Reason'), 'Repeated abuse')
    await u.click(dialog.getByRole('button', { name: 'Suspend' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Asha Rao suspended'))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({ reason: 'Repeated abuse' })
    expect(await screen.findByRole('button', { name: 'Activate' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('requires a reason of at most 300 characters', async () => {
    mock.onGet('/admin/users').reply(200, page([user(5)]))
    const u = userEvent.setup()
    renderApp('/admin/users')
    await u.click(within(await screen.findByRole('article', { name: 'Asha Rao' })).getByRole('button', { name: 'Suspend' }))
    const dialog = within(await screen.findByRole('dialog'))

    await u.click(dialog.getByRole('button', { name: 'Suspend' }))
    expect(await dialog.findByText('Please give a reason')).toBeInTheDocument()

    await u.click(dialog.getByLabelText('Reason'))
    await u.paste('x'.repeat(301))
    await u.click(dialog.getByRole('button', { name: 'Suspend' }))
    expect(await dialog.findByText('Use at most 300 characters')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('explains CANNOT_SUSPEND in the dialog', async () => {
    mock.onGet('/admin/users').reply(200, page([user(5)]))
    mock.onPost('/admin/users/5/suspend').reply(409, { code: 'CANNOT_SUSPEND', detail: 'nope' })
    const u = userEvent.setup()
    renderApp('/admin/users')
    await u.click(within(await screen.findByRole('article', { name: 'Asha Rao' })).getByRole('button', { name: 'Suspend' }))
    const dialog = within(await screen.findByRole('dialog'))
    await u.type(dialog.getByLabelText('Reason'), 'Because')
    await u.click(dialog.getByRole('button', { name: 'Suspend' }))

    expect(await dialog.findByText('Admins and your own account can’t be suspended.')).toBeInTheDocument()
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('activates a suspended user', async () => {
    mock.onGet('/admin/users').replyOnce(200, page([user(6, { status: 'SUSPENDED' })]))
    mock.onGet('/admin/users').reply(200, page([user(6)]))
    mock.onPost('/admin/users/6/activate').reply(200, user(6))
    const u = userEvent.setup()
    renderApp('/admin/users')

    await u.click(within(await screen.findByRole('article', { name: 'Asha Rao' })).getByRole('button', { name: 'Activate' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Asha Rao activated'))
    expect(await screen.findByRole('button', { name: 'Suspend' })).toBeInTheDocument()
  })

  it('shows the server message when activation fails', async () => {
    mock.onGet('/admin/users').reply(200, page([user(6, { status: 'SUSPENDED' })]))
    mock.onPost('/admin/users/6/activate').reply(404, { code: 'NOT_FOUND', detail: 'User not found' })
    const u = userEvent.setup()
    renderApp('/admin/users')

    await u.click(await screen.findByRole('button', { name: 'Activate' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('User not found'))
  })

  it('does not offer suspending admins or yourself', async () => {
    mock.onGet('/admin/users').reply(200, page([
      user(1, { firstName: 'Admin', lastName: 'User', role: 'ADMIN' }),
      user(2, { firstName: 'Other', lastName: 'Admin', role: 'ADMIN' }),
      user(3, { firstName: 'Asha', lastName: 'Rao' }),
    ]))
    renderApp('/admin/users')

    const self = within(await screen.findByRole('article', { name: 'Admin User' }))
    expect(self.getByRole('button', { name: 'Suspend' })).toBeDisabled()
    expect(within(screen.getByRole('article', { name: 'Other Admin' })).getByRole('button', { name: 'Suspend' })).toBeDisabled()
    expect(within(screen.getByRole('article', { name: 'Asha Rao' })).getByRole('button', { name: 'Suspend' })).toBeEnabled()
  })
})
