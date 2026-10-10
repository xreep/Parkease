import '@testing-library/jest-dom/vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { AdminStatsDto } from '../../lib/admin'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }
const NOW = new Date('2026-10-09T10:00:00Z')


const stats: AdminStatsDto = {
  from: '2026-09-10', to: '2026-10-09',
  users: { drivers: 120, owners: 30, newDrivers: 12, newOwners: 3, suspended: 2 },
  listings: { approved: 40, pendingReview: 5, suspended: 1, paused: 2 },
  bookings: { created: 200, confirmed: 150, conversionPercent: 75, cancelled: 20, utilizationPercent: 42.5 },
  money: { gmv: 250000, platformRevenue: 25000, refunds: 12000, ownerEarnings: 200000 },
  topStates: [
    { stateId: 1, name: 'Maharashtra', bookings: 90, gmv: 120000 },
    { stateId: 2, name: 'Karnataka', bookings: 60, gmv: 80000 },
  ],
  topCities: [
    { cityId: 10, name: 'Pune', stateName: 'Maharashtra', bookings: 50, gmv: 70000 },
    { cityId: 11, name: 'Bengaluru', stateName: 'Karnataka', bookings: 45, gmv: 60000 },
  ],
  series: [
    { date: '2026-10-07', bookings: 5, gmv: 5000, revenue: 500 },
    { date: '2026-10-08', bookings: 7, gmv: 8000, revenue: 800 },
  ],
}

describe('admin overview', () => {
  let mock: MockAdapter

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    mock.onGet('/admin/queues').reply(200, { pendingOwners: 2, pendingListings: 1 })
  })

  afterEach(() => {
    mock.restore()
    vi.useRealTimers()
  })

  const statsCalls = () => mock.history.get.filter((r) => r.url === '/admin/stats')

  it('has a sectioned navigation with every admin area', async () => {
    mock.onGet('/admin/stats').reply(200, stats)
    renderApp('/admin')

    const nav = within(await screen.findByRole('navigation', { name: 'Sections' }))
    const expected: [string, string][] = [
      ['Overview', '/admin'], ['Owners', '/admin/owners'], ['Listings', '/admin/listings'], ['Bookings', '/admin/bookings'],
      ['Disputes', '/admin/disputes'], ['Payments', '/admin/payments'], ['Payouts', '/admin/payouts'], ['Users', '/admin/users'],
      ['Reviews', '/admin/reviews'], ['Locations', '/admin/locations'], ['Reports', '/admin/reports'],
      ['Settings', '/admin/settings'], ['Audit', '/admin/audit'],
    ]
    for (const [name, href] of expected) expect(nav.getByRole('link', { name })).toHaveAttribute('href', href)
  })

  it('scrolls the active section tab into view', async () => {
    const scrollIntoView = vi.fn()
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = scrollIntoView
    try {
      mock.onGet('/admin/stats').reply(200, stats)
      mock.onGet('/admin/audit').reply(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      renderApp('/admin/audit')

      await screen.findByRole('navigation', { name: 'Sections' })
      await waitFor(() => expect(scrollIntoView).toHaveBeenCalled())
      const active = within(screen.getByRole('navigation', { name: 'Sections' })).getByRole('link', { name: 'Audit' })
      expect(scrollIntoView.mock.contexts.at(-1)).toBe(active)
      expect(scrollIntoView).toHaveBeenLastCalledWith({ inline: 'center', block: 'nearest' })
    } finally {
      Element.prototype.scrollIntoView = original
    }
  })

  it('shows the KPI cards, charts, top tables and queue counts', async () => {
    mock.onGet('/admin/stats').reply(200, stats)
    renderApp('/admin')

    const kpi = async (name: string) => within(await screen.findByRole('group', { name }))
    expect((await kpi('Users')).getByText('150')).toBeInTheDocument()
    expect((await kpi('Users')).getByText('15 new')).toBeInTheDocument()
    expect((await kpi('Listings')).getByText('40')).toBeInTheDocument()
    expect((await kpi('Bookings')).getByText('200')).toBeInTheDocument()
    expect((await kpi('Conversion')).getByText('75%')).toBeInTheDocument()
    expect((await kpi('Utilization')).getByText('42.5%')).toBeInTheDocument()
    expect((await kpi('GMV')).getByText('₹2,50,000')).toBeInTheDocument()
    expect((await kpi('Revenue')).getByText('₹25,000')).toBeInTheDocument()
    expect((await kpi('Refunds')).getByText('₹12,000')).toBeInTheDocument()

    expect(screen.getByRole('img', { name: 'Bookings per day' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'GMV per day' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Platform revenue per day' })).toBeInTheDocument()

    const states = within(screen.getByRole('table', { name: 'Top states' }))
    expect(states.getByRole('row', { name: /Maharashtra 90 ₹1,20,000/ })).toBeInTheDocument()
    const cities = within(screen.getByRole('table', { name: 'Top cities' }))
    expect(cities.getByRole('row', { name: /Pune, Maharashtra 50 ₹70,000/ })).toBeInTheDocument()

    expect(await screen.findByText('2 owners waiting for verification')).toBeInTheDocument()
    expect(screen.getByText('1 listing waiting for approval')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Review owners' })).toHaveAttribute('href', '/admin/owners')
    expect(screen.getByRole('link', { name: 'Review listings' })).toHaveAttribute('href', '/admin/listings')
  })

  it('asks for the last 30 days at first and refetches when the range changes', async () => {
    mock.onGet('/admin/stats').reply(200, stats)
    renderApp('/admin')
    await screen.findByRole('group', { name: 'Users' })
    expect(statsCalls()[0].params).toEqual({ from: '2026-09-10', to: '2026-10-09' })

    fireEvent.change(screen.getByLabelText('Range'), { target: { value: '7' } })

    await waitFor(() => expect(statsCalls().at(-1)!.params).toEqual({ from: '2026-10-03', to: '2026-10-09' }))
  })

  it('shows the server message when the stats fail to load', async () => {
    mock.onGet('/admin/stats').reply(500, { code: 'INTERNAL', detail: 'Stats are down' })
    renderApp('/admin')

    expect(await screen.findByText('Stats are down')).toBeInTheDocument()
  })

  it('shows the server message when the queue counts fail to load', async () => {
    mock.onGet('/admin/stats').reply(200, stats)
    mock.onGet('/admin/queues').reply(500, { code: 'INTERNAL', detail: 'Queues are down' })
    renderApp('/admin')

    expect(await screen.findByText('Queues are down')).toBeInTheDocument()
    expect(await screen.findByRole('group', { name: 'Users' })).toBeInTheDocument()
  })
})

