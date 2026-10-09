import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { api } from '../../lib/api'
import type { NotificationDto } from '../../lib/notifications'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

const driver = { id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null, role: 'DRIVER', emailVerified: true, avatarUrl: null }

function notification(overrides: Partial<NotificationDto> = {}): NotificationDto {
  return {
    id: 7, type: 'BOOKING_CONFIRMED', title: 'Booking confirmed', body: 'PE-8KQ2M4 at Metro Hub Parking is confirmed.',
    link: '/driver/bookings/91', read: false, createdAt: new Date(Date.now() - 5 * 60_000).toISOString(), ...overrides,
  }
}

/** Yesterday at 12:00 local: "Yesterday" whatever the time of day the test runs. */
function yesterdayNoon() {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  d.setHours(12, 0, 0, 0)
  return d.toISOString()
}

const page = (content: NotificationDto[]) => ({ content, page: 0, size: 10, totalElements: content.length, totalPages: 1 })

describe('notification bell', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  function signIn() {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, driver)
  }

  it('is not shown to signed-out users', async () => {
    renderApp('/')
    expect(await screen.findByRole('link', { name: 'Find parking' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Notifications/ })).not.toBeInTheDocument()
    expect(mock.history.get.filter((r) => r.url === '/notifications/unread-count')).toHaveLength(0)
  })

  it('shows the unread count from the server as a badge', async () => {
    signIn()
    mock.onGet('/notifications/unread-count').reply(200, { count: 3 })
    renderApp('/account')

    const bell = await screen.findByRole('button', { name: 'Notifications (3 unread)' })
    expect(within(bell).getByText('3')).toBeInTheDocument()
  })

  it('hides the badge at zero and shows 9+ above nine', async () => {
    signIn()
    mock.onGet('/notifications/unread-count').replyOnce(200, { count: 0 })
    const { unmount } = renderApp('/account')
    const bell = await screen.findByRole('button', { name: 'Notifications (0 unread)' })
    expect(within(bell).queryByTestId('unread-badge')).not.toBeInTheDocument()
    unmount()

    mock.onGet('/notifications/unread-count').reply(200, { count: 14 })
    renderApp('/account')
    const busy = await screen.findByRole('button', { name: 'Notifications (14 unread)' })
    expect(within(busy).getByText('9+')).toBeInTheDocument()
  })

  it('lists the latest ten without marking anything read, then marks one read and opens its link', async () => {
    signIn()
    mock.onGet('/notifications/unread-count').reply(200, { count: 2 })
    mock.onGet('/notifications').reply(200, page([
      notification(),
      notification({ id: 8, title: 'Reminder', body: 'Your parking starts soon.', link: null, read: true, createdAt: yesterdayNoon() }),
    ]))
    mock.onPost('/notifications/7/read').reply(204)
    const user = userEvent.setup()
    renderApp('/account')

    await user.click(await screen.findByRole('button', { name: 'Notifications (2 unread)' }))
    const dialog = await screen.findByRole('dialog', { name: 'Notifications' })
    expect(mock.history.get.find((r) => r.url === '/notifications')?.params).toEqual({ page: 0, size: 10 })
    expect(within(dialog).getByText('5 min ago')).toBeInTheDocument()
    expect(within(dialog).getByText('Yesterday')).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: 'View all' })).toHaveAttribute('href', '/notifications')
    expect(mock.history.post).toHaveLength(0)

    await user.click(within(dialog).getByRole('button', { name: /Booking confirmed/ }))
    await waitFor(() => expect(mock.history.post.map((r) => r.url)).toEqual(['/notifications/7/read']))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Notifications' })).not.toBeInTheDocument())
  })

  it('marks all as read', async () => {
    signIn()
    mock.onGet('/notifications/unread-count').replyOnce(200, { count: 2 }).onGet('/notifications/unread-count').reply(200, { count: 0 })
    mock.onGet('/notifications').reply(200, page([notification(), notification({ id: 8, title: 'Other' })]))
    mock.onPost('/notifications/read-all').reply(204)
    const user = userEvent.setup()
    renderApp('/account')

    await user.click(await screen.findByRole('button', { name: 'Notifications (2 unread)' }))
    await user.click(await screen.findByRole('button', { name: 'Mark all as read' }))

    await waitFor(() => expect(mock.history.post.map((r) => r.url)).toEqual(['/notifications/read-all']))
    expect(await screen.findByRole('button', { name: 'Notifications (0 unread)' })).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    signIn()
    mock.onGet('/notifications/unread-count').reply(200, { count: 0 })
    mock.onGet('/notifications').reply(200, page([]))
    const user = userEvent.setup()
    renderApp('/account')

    await user.click(await screen.findByRole('button', { name: 'Notifications (0 unread)' }))
    expect(await screen.findByText("You're all caught up.")).toBeInTheDocument()
  })

  it('closes on Escape (returning focus to the bell) and on an outside click', async () => {
    signIn()
    mock.onGet('/notifications/unread-count').reply(200, { count: 0 })
    mock.onGet('/notifications').reply(200, page([]))
    const user = userEvent.setup()
    renderApp('/account')

    const bell = await screen.findByRole('button', { name: 'Notifications (0 unread)' })
    await user.click(bell)
    expect(await screen.findByRole('dialog', { name: 'Notifications' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: 'Notifications' })).not.toBeInTheDocument()
    expect(bell).toHaveFocus()

    await user.click(bell)
    expect(await screen.findByRole('dialog', { name: 'Notifications' })).toBeInTheDocument()
    await user.click(screen.getByRole('heading', { name: /your account/i }))
    expect(screen.queryByRole('dialog', { name: 'Notifications' })).not.toBeInTheDocument()
  })
})
