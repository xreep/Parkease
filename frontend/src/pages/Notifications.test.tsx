import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { api } from '../lib/api'
import type { NotificationDto } from '../lib/notifications'
import { tokenStore } from '../lib/tokenStore'
import { renderApp } from '../test/renderApp'

const owner = { id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null }

function notification(overrides: Partial<NotificationDto> = {}): NotificationDto {
  return {
    id: 7, type: 'OWNER_NEW_BOOKING', title: 'New booking', body: 'Rahul booked slot A-3.', link: '/owner/bookings',
    read: false, createdAt: new Date(Date.now() - 5 * 60_000).toISOString(), ...overrides,
  }
}

const page = (content: NotificationDto[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

describe('notifications page', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
    mock.onGet('/notifications/unread-count').reply(200, { count: 1 })
  })

  afterEach(() => mock.restore())

  function signIn() {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, owner)
  }

  const listCalls = () => mock.history.get.filter((r) => r.url === '/notifications')

  it('sends signed-out visitors to log in', async () => {
    renderApp('/notifications')
    expect(await screen.findByRole('heading', { name: /^log in$/i })).toBeInTheDocument()
  })

  it('lists notifications (any role) and pages through them', async () => {
    signIn()
    const first = page([notification()], 2, 0)
    const second = page([notification({ id: 9, title: 'Older one', read: true })], 2, 1)
    mock.onGet('/notifications').reply((config) => [200, config.params.page === 0 ? first : second])
    const user = userEvent.setup()
    renderApp('/notifications')

    expect(await screen.findByRole('heading', { name: 'Notifications' })).toBeInTheDocument()
    expect(await screen.findByText('New booking')).toBeInTheDocument()
    expect(screen.getByText('5 min ago')).toBeInTheDocument()
    expect(listCalls()[0].params).toEqual({ page: 0, size: 20 })

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Older one')).toBeInTheDocument()
    expect(screen.queryByText('New booking')).not.toBeInTheDocument()
    expect(listCalls().at(-1)?.params).toEqual({ page: 1, size: 20 })
  })

  it('filters to unread on the client', async () => {
    signIn()
    mock.onGet('/notifications').reply(200, page([notification(), notification({ id: 9, title: 'Seen already', read: true })]))
    const user = userEvent.setup()
    renderApp('/notifications')

    expect(await screen.findByText('Seen already')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Unread' }))
    expect(screen.queryByText('Seen already')).not.toBeInTheDocument()
    expect(screen.getByText('New booking')).toBeInTheDocument()
    expect(listCalls()).toHaveLength(1)
  })

  it('marks one read and opens its link', async () => {
    signIn()
    mock.onGet('/notifications').reply(200, page([notification()]))
    mock.onPost('/notifications/7/read').reply(204)
    mock.onGet('/owner/bookings').reply(200, page([]))
    const user = userEvent.setup()
    renderApp('/notifications')

    await user.click(await screen.findByRole('button', { name: /New booking/ }))
    await waitFor(() => expect(mock.history.post.map((r) => r.url)).toContain('/notifications/7/read'))
    expect(await screen.findByRole('heading', { name: 'Bookings' })).toBeInTheDocument()
  })

  it('marks all as read from the page', async () => {
    signIn()
    mock.onGet('/notifications').reply(200, page([notification()]))
    mock.onPost('/notifications/read-all').reply(204)
    const user = userEvent.setup()
    renderApp('/notifications')

    await screen.findByText('New booking')
    await user.click(await screen.findByRole('button', { name: 'Mark all as read' }))
    await waitFor(() => expect(mock.history.post.map((r) => r.url)).toEqual(['/notifications/read-all']))
  })

  it('shows empty states for both filters', async () => {
    signIn()
    mock.onGet('/notifications').reply(200, page([]))
    const user = userEvent.setup()
    renderApp('/notifications')

    expect(await screen.findByText('No notifications yet.')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Unread' }))
    expect(within(screen.getByRole('tabpanel')).getByText("You're all caught up.")).toBeInTheDocument()
  })
})
