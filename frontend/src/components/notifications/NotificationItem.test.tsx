import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { NotificationDto } from '../../lib/notifications'
import { NotificationItem } from './NotificationItem'

const navigate = vi.fn()
vi.mock('react-router-dom', () => ({ useNavigate: () => navigate }))

const item = (link: string | null): NotificationDto => ({
  id: 7, type: 'BOOKING_CONFIRMED', title: 'Booking confirmed', body: 'Body', link, read: false, createdAt: new Date().toISOString(),
})

describe('NotificationItem links', () => {
  let mock: MockAdapter

  beforeEach(() => {
    navigate.mockClear()
    mock = new MockAdapter(api)
    mock.onPost('/notifications/7/read').reply(204)
  })

  afterEach(() => mock.restore())

  async function click(link: string | null) {
    const user = userEvent.setup()
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ul><NotificationItem notification={item(link)} now={Date.now()} /></ul>
      </QueryClientProvider>,
    )
    await user.click(screen.getByRole('button', { name: /Booking confirmed/ }))
    await waitFor(() => expect(mock.history.post).toHaveLength(1))
  }

  it.each(['//evil.com', '/\\evil.com', 'javascript:alert(1)', 'https://x'])('does not navigate to %s, but still marks it read', async (link) => {
    await click(link)
    expect(navigate).not.toHaveBeenCalled()
  })

  it('navigates to an app path', async () => {
    await click('/driver/bookings/91')
    expect(navigate).toHaveBeenCalledWith('/driver/bookings/91')
  })
})
