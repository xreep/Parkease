import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { OwnerBookingDto } from '../../lib/bookings'
import type { OwnerStatsDto } from '../../lib/ownerDashboard'
import { addDays } from '../../lib/time'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

const owner = { id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null }
const verified = { verificationStatus: 'VERIFIED', documentType: null, rejectionReason: null }

// 9 Oct 2026, 11:30 in IST.
const NOW = new Date('2026-10-09T06:00:00Z')
const TODAY = '2026-10-09'

const upcoming: OwnerBookingDto = {
  id: 5, bookingCode: 'PE-UPC001', status: 'CONFIRMED', listingId: 7, listingTitle: 'FC Road Parking', slotLabel: 'A-3',
  startTime: '2026-10-12T04:30:00Z', endTime: '2026-10-12T06:30:00Z', vehicleType: 'FOUR_WHEELER', plateNumber: 'MH12AB1234',
  driverFirstName: 'Rahul', baseAmount: 240, ownerNet: 216, approvalDeadline: null, createdAt: '2026-10-09T08:00:00Z',
}

function stats(days: number, overrides: Partial<OwnerStatsDto> = {}): OwnerStatsDto {
  const from = addDays(TODAY, -(days - 1))
  return {
    from, to: TODAY,
    totals: { earningsNet: 12480, bookings: 42, cancellations: 3, occupancyPercent: 37.5, avgRating: 4.6, reviewCount: 18 },
    balances: { held: 1200, pendingPayout: 3400, paid: 7880 },
    pendingApprovals: 2,
    upcoming: [upcoming],
    series: Array.from({ length: days }, (_, i) => ({ date: addDays(from, i), earningsNet: i * 10, bookings: i % 3 })),
    ...overrides,
  }
}

describe('owner overview', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, owner)
    mock.onGet('/owner/profile').reply(200, verified)
    mock.onGet('/owner/listings').reply(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    mock.onGet('/owner/stats').reply((config) => {
      const from = config.params.from as string
      const days = Math.round((new Date(`${config.params.to}T00:00:00Z`).getTime() - new Date(`${from}T00:00:00Z`).getTime()) / 86_400_000) + 1
      return [200, stats(days)]
    })
  })

  afterEach(() => {
    mock.restore()
    vi.useRealTimers()
  })

  const statsCalls = () => mock.history.get.filter((r) => r.url === '/owner/stats')

  it('asks for the last 30 days by default and shows the key figures', async () => {
    renderApp('/owner')

    const earnings = await screen.findByRole('group', { name: 'Earnings' })
    expect(statsCalls()[0].params).toEqual({ from: '2026-09-10', to: TODAY })
    expect(within(earnings).getByText('₹12,480')).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: 'Bookings' })).getByText('42')).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: 'Occupancy' })).getByText('37.5%')).toBeInTheDocument()
    const rating = screen.getByRole('group', { name: 'Rating' })
    expect(within(rating).getByText('4.6')).toBeInTheDocument()
    expect(within(rating).getByText('18 reviews')).toBeInTheDocument()
    const approvals = screen.getByRole('group', { name: 'Pending approvals' })
    expect(within(approvals).getByText('2')).toBeInTheDocument()
    expect(within(approvals).getByRole('link', { name: 'Review approvals' })).toHaveAttribute('href', '/owner/bookings')
  })

  it('shows the held, pending payout and paid balances', async () => {
    renderApp('/owner')

    const balances = await screen.findByRole('region', { name: 'Balances' })
    expect(within(within(balances).getByRole('group', { name: 'Held' })).getByText('₹1,200')).toBeInTheDocument()
    expect(within(within(balances).getByRole('group', { name: 'Pending payout' })).getByText('₹3,400')).toBeInTheDocument()
    expect(within(within(balances).getByRole('group', { name: 'Paid' })).getByText('₹7,880')).toBeInTheDocument()
  })

  it('draws the earnings bars and the bookings line from the daily series', async () => {
    renderApp('/owner')

    const bars = await screen.findByRole('img', { name: 'Earnings per day' })
    expect(bars.querySelectorAll('rect[data-bar]')).toHaveLength(30)
    const line = screen.getByRole('img', { name: 'Bookings per day' })
    expect(line.querySelectorAll('line[data-point]')).toHaveLength(30)
    expect(screen.getByRole('table', { name: 'Earnings per day' })).toBeInTheDocument()
  })

  it('lists the next bookings', async () => {
    renderApp('/owner')

    const list = await screen.findByRole('list', { name: 'Upcoming bookings' })
    const item = within(list).getByRole('listitem')
    expect(item).toHaveTextContent('PE-UPC001')
    expect(item).toHaveTextContent('FC Road Parking')
    expect(item).toHaveTextContent('Rahul')
    expect(item).toHaveTextContent('Slot A-3')
  })

  it('says so when nothing is coming up', async () => {
    mock.onGet('/owner/stats').reply(200, stats(30, { upcoming: [] }))
    renderApp('/owner')

    expect(await screen.findByText('No upcoming bookings.')).toBeInTheDocument()
  })

  it('refetches when the range changes', async () => {
    const user = userEvent.setup()
    renderApp('/owner')
    await screen.findByRole('group', { name: 'Earnings' })

    await user.selectOptions(screen.getByLabelText('Range'), 'Last 7 days')

    await waitFor(() => expect(statsCalls().at(-1)!.params).toEqual({ from: '2026-10-03', to: TODAY }))
    await waitFor(() => expect(screen.getByRole('img', { name: 'Earnings per day' }).querySelectorAll('rect[data-bar]')).toHaveLength(7))

    await user.selectOptions(screen.getByLabelText('Range'), 'Last 90 days')
    await waitFor(() => expect(statsCalls().at(-1)!.params).toEqual({ from: '2026-07-12', to: TODAY }))
  })

  it('reports a failed load without hiding the rest of the page', async () => {
    mock.onGet('/owner/stats').reply(500, { code: 'INTERNAL', detail: 'Stats are down' })
    renderApp('/owner')

    expect(await screen.findByText('Stats are down')).toBeInTheDocument()
    expect(screen.getByText('Your listings')).toBeInTheDocument()
  })

  it('keeps the verification prompt on top for an owner who is not verified', async () => {
    mock.onGet('/owner/profile').reply(200, { verificationStatus: 'UNSUBMITTED', documentType: null, rejectionReason: null })
    renderApp('/owner')

    const prompt = await screen.findByRole('link', { name: 'Start verification' })
    const earnings = await screen.findByRole('group', { name: 'Earnings' })
    expect(prompt.compareDocumentPosition(earnings) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})
